package com.example.automation

import com.example.ai.GeminiLiveClient
import com.example.ai.LiveToolCall
import com.example.data.AgentActivity
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ToolDispatcher(
    private val liveClientProvider: () -> GeminiLiveClient?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun dispatchCalls(calls: List<LiveToolCall>) {
        for (call in calls) {
            val job = scope.launch {
                val result = executeSingleTool(call)
                activeJobs.remove(call.id)
                liveClientProvider()?.sendToolResponse(call.id, call.name, result)
            }
            activeJobs[call.id] = job
        }
    }

    fun cancelToolCalls(ids: List<String>) {
        ids.forEach { id ->
            activeJobs.remove(id)?.cancel()
        }
        PilotAccessibilityService.instance?.stopContinuousScroll()
    }

    fun cancelAllActiveActions() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        PilotAccessibilityService.instance?.stopContinuousScroll()
    }

    private suspend fun executeSingleTool(call: LiveToolCall): JSONObject {
        val service = PilotAccessibilityService.instance
        if (service == null) {
            PilotStateHub.log(
                LogLevel.ERROR,
                "ToolDispatcher",
                "AccessibilityService not enabled for ${call.name}"
            )
            return JSONObject()
                .put("status", "error")
                .put(
                    "message",
                    "PilotAccessibilityService is not enabled. Tell the user to turn on LivePilot AI Accessibility Service on the Home screen."
                )
        }

        val tickerLabel = friendlyTickerForTool(call)
        PilotStateHub.updateSession {
            it.copy(
                activity = if (call.name == "start_continuous_scroll") AgentActivity.SCROLLING else AgentActivity.EXECUTING_TOOL,
                statusTicker = tickerLabel
            )
        }

        val result = runCatching {
            val args = call.args
            when (call.name) {
                "open_app" -> service.openAppByName(args.optString("app_name", ""))
                "get_screen_ui" -> service.getScreenUiJson()
                "capture_screen_vision" -> service.captureScreenVisionAndStream()
                "start_continuous_scroll" -> service.startContinuousScroll(
                    direction = args.optString("direction", "DOWN"),
                    speed = args.optString("speed", "NORMAL")
                )
                "stop_scrolling" -> service.stopContinuousScroll()
                "scroll_once" -> service.scrollOnce(
                    direction = args.optString("direction", "DOWN"),
                    distancePercent = args.optInt("distance_percent", 55)
                )
                "click_element" -> service.clickElement(
                    index = args.optInt("index", -1),
                    x = args.optInt("x", 0),
                    y = args.optInt("y", 0)
                )
                "long_press" -> service.longPress(
                    x = args.optInt("x", 0),
                    y = args.optInt("y", 0),
                    durationMs = args.optInt("duration_ms", 700)
                )
                "drag_gesture" -> service.dragGesture(
                    startX = args.optInt("start_x", 0),
                    startY = args.optInt("start_y", 0),
                    endX = args.optInt("end_x", 0),
                    endY = args.optInt("end_y", 0),
                    durationMs = args.optInt("duration_ms", 450)
                )
                "type_text" -> service.typeText(
                    index = args.optInt("index", -1),
                    x = args.optInt("x", 0),
                    y = args.optInt("y", 0),
                    text = args.optString("text", ""),
                    pressSend = args.optBoolean("press_send", false)
                )
                "navigate" -> service.navigateGlobal(args.optString("action", "BACK"))
                "open_notification_chat" -> service.openNotificationChat(args.optString("sender_or_app", ""))
                else -> JSONObject()
                    .put("status", "error")
                    .put("message", "Unknown tool: ${call.name}")
            }
        }.getOrElse { err ->
            PilotStateHub.log(LogLevel.ERROR, "ToolDispatcher", "${call.name} threw: ${err.message}")
            JSONObject().put("status", "error").put("message", err.message ?: "Tool execution failed")
        }

        if (call.name != "start_continuous_scroll") {
            PilotStateHub.updateSession {
                it.copy(
                    activity = if (it.isMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                    statusTicker = if (it.isScrollingActive) it.statusTicker else "Listening..."
                )
            }
        }
        return result
    }

    private fun friendlyTickerForTool(call: LiveToolCall): String = when (call.name) {
        "open_app" -> "Opening ${call.args.optString("app_name", "app")}..."
        "get_screen_ui" -> "Inspecting screen UI..."
        "capture_screen_vision" -> "Capturing screen vision..."
        "start_continuous_scroll" -> "Scrolling ${call.args.optString("direction", "DOWN")}..."
        "stop_scrolling" -> "Stopping scroll..."
        "scroll_once" -> "Swiping ${call.args.optString("direction", "DOWN")}..."
        "click_element" -> "Tapping target..."
        "long_press" -> "Long pressing..."
        "drag_gesture" -> "Dragging timeline/slider..."
        "type_text" -> "Typing message..."
        "navigate" -> "Navigating ${call.args.optString("action", "")}..."
        "open_notification_chat" -> "Opening ${call.args.optString("sender_or_app", "chat")}..."
        else -> "Executing ${call.name}..."
    }
}

package com.example.automation

import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.ai.LiveToolCall
import com.example.data.AgentActivity
import com.example.data.AskAiMessage
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
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
                val ok = result.optString("status") == "ok"
                PilotStateHub.recordToolExecuted(
                    toolName = call.name,
                    summary = friendlySummaryForHistory(call),
                    succeeded = ok
                )
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
        val tickerLabel = friendlyTickerForTool(call)
        PilotStateHub.updateSession {
            it.copy(
                activity = if (call.name == "start_continuous_scroll") AgentActivity.SCROLLING else AgentActivity.EXECUTING_TOOL,
                statusTicker = tickerLabel
            )
        }

        // Tools that work even without AccessibilityService enabled
        when (call.name) {
            "wait_ms" -> {
                val ms = call.args.optInt("milliseconds", 700).coerceIn(200, 5000)
                delay(ms.toLong())
                restoreListeningState()
                return JSONObject().put("status", "ok").put("waited_ms", ms)
            }

            "web_search_and_answer" -> {
                val query = call.args.optString("query", "").trim()
                val res = executeWebSearchBridge(query)
                restoreListeningState()
                return res
            }

            "read_recent_notifications" -> {
                val limit = call.args.optInt("limit", 5).coerceIn(1, 10)
                val msgs = PilotStateHub.incomingMessages.value.take(limit)
                val arr = JSONArray()
                msgs.forEach { m ->
                    arr.put(
                        JSONObject()
                            .put("app", m.app.displayName)
                            .put("sender", m.senderName)
                            .put("text", m.messageText)
                            .put("time", m.formattedTime)
                            .put("already_replied", !m.autoRepliedText.isNullOrBlank())
                    )
                }
                restoreListeningState()
                return JSONObject()
                    .put("status", "ok")
                    .put("count", arr.length())
                    .put("messages", arr)
            }
        }

        val service = PilotAccessibilityService.instance
        if (service == null) {
            PilotStateHub.log(
                LogLevel.ERROR,
                "ToolDispatcher",
                "AccessibilityService not enabled for ${call.name}"
            )
            restoreListeningState()
            return JSONObject()
                .put("status", "error")
                .put(
                    "message",
                    "PilotAccessibilityService is not enabled. Tell the user to turn on LivePilot AI Accessibility Service on the Home screen."
                )
        }

        val result = runCatching {
            val args = call.args
            when (call.name) {
                "open_app" -> service.openAppByName(args.optString("app_name", ""))
                "search_youtube" -> service.searchYouTube(args.optString("query", ""))
                "get_device_status" -> service.getDeviceStatusJson()
                "toggle_flashlight" -> service.toggleFlashlight(args.optBoolean("enabled", true))
                "set_alarm_or_timer" -> service.setAlarmOrTimer(
                    mode = args.optString("mode", "TIMER"),
                    hours = args.optInt("hours", 0),
                    minutes = args.optInt("minutes", 5),
                    label = args.optString("label", "LivePilot AI")
                )
                "clipboard_action" -> service.clipboardAction(
                    action = args.optString("action", "READ"),
                    text = args.optString("text", "")
                )
                "click_by_text" -> service.clickByText(
                    rawQuery = args.optString("query", ""),
                    exact = args.optBoolean("exact", false)
                )
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
                "device_control" -> service.deviceControl(
                    action = args.optString("action", ""),
                    value = args.optString("value", "")
                )
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
            restoreListeningState()
        }
        return result
    }

    private suspend fun executeWebSearchBridge(query: String): JSONObject {
        if (query.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Search query was empty.")
        }
        val settings = PilotStateHub.settings.value
        if (settings.apiKey.isBlank()) {
            return JSONObject().put("status", "error").put("message", "API key missing.")
        }
        PilotStateHub.log(LogLevel.TOOL, "WebSearch", "Live Google Search: $query")
        val promptHistory = listOf(
            AskAiMessage(
                id = 1L,
                isUser = true,
                text = "Answer briefly in 2-3 clear sentences suitable for voice readout: $query",
                timestamp = PilotStateHub.formatNow()
            )
        )
        val result = GeminiRestClient.generateChatTurn(
            apiKey = settings.apiKey,
            modelId = settings.textModel,
            history = promptHistory,
            enableGoogleSearch = true
        )
        return result.fold(
            onSuccess = { gen ->
                val sources = JSONArray()
                gen.citations.take(3).forEach { sources.put(it.title) }
                JSONObject()
                    .put("status", "ok")
                    .put("answer", gen.text.take(900))
                    .put("sources", sources)
            },
            onFailure = { err ->
                JSONObject()
                    .put("status", "error")
                    .put("message", err.message ?: "Web search failed")
            }
        )
    }

    private fun restoreListeningState() {
        PilotStateHub.updateSession {
            it.copy(
                activity = if (it.isMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                statusTicker = if (it.isScrollingActive) it.statusTicker else "Listening..."
            )
        }
    }

    private fun friendlyTickerForTool(call: LiveToolCall): String = when (call.name) {
        "open_app" -> "Opening ${call.args.optString("app_name", "app")}..."
        "search_youtube" -> "YouTube: ${call.args.optString("query", "")}"
        "web_search_and_answer" -> "Searching Google..."
        "get_device_status" -> "Reading battery & status..."
        "toggle_flashlight" -> if (call.args.optBoolean("enabled", true)) "Torch ON..." else "Torch OFF..."
        "set_alarm_or_timer" -> "Setting ${call.args.optString("mode", "TIMER").lowercase()}..."
        "clipboard_action" -> "Clipboard ${call.args.optString("action", "READ")}..."
        "read_recent_notifications" -> "Checking inbox..."
        "click_by_text" -> "Tapping '${call.args.optString("query", "item")}'..."
        "get_screen_ui" -> "Inspecting screen UI..."
        "capture_screen_vision" -> "Capturing screen vision..."
        "start_continuous_scroll" -> "Scrolling ${call.args.optString("direction", "DOWN")}..."
        "stop_scrolling" -> "Stopping scroll..."
        "scroll_once" -> "Swiping ${call.args.optString("direction", "DOWN")}..."
        "click_element" -> "Tapping target..."
        "long_press" -> "Long pressing..."
        "drag_gesture" -> "Dragging slider/timeline..."
        "type_text" -> "Typing message..."
        "navigate" -> "Navigating ${call.args.optString("action", "")}..."
        "device_control" -> "Control: ${call.args.optString("action", "")}"
        "open_notification_chat" -> "Opening ${call.args.optString("sender_or_app", "chat")}..."
        "wait_ms" -> "Waiting..."
        else -> "Executing ${call.name}..."
    }

    private fun friendlySummaryForHistory(call: LiveToolCall): String = when (call.name) {
        "open_app" -> "Opened ${call.args.optString("app_name", "app")}"
        "search_youtube" -> "YouTube: ${call.args.optString("query", "").take(22)}"
        "web_search_and_answer" -> "Google Search"
        "get_device_status" -> "Checked Phone Status"
        "toggle_flashlight" -> if (call.args.optBoolean("enabled", true)) "Flashlight ON" else "Flashlight OFF"
        "set_alarm_or_timer" -> "Set ${call.args.optString("mode", "Timer")}"
        "clipboard_action" -> "Clipboard ${call.args.optString("action", "")}"
        "read_recent_notifications" -> "Read Inbox"
        "click_by_text" -> "Tapped '${call.args.optString("query", "").take(18)}'"
        "capture_screen_vision" -> "Captured Screen"
        "get_screen_ui" -> "Read Screen UI"
        "start_continuous_scroll" -> "Auto-Scroll ${call.args.optString("direction", "")}"
        "stop_scrolling" -> "Stopped Scroll"
        "scroll_once" -> "Swiped ${call.args.optString("direction", "")}"
        "click_element" -> "Tapped Screen"
        "drag_gesture" -> "Dragged Gesture"
        "type_text" -> "Typed Text"
        "navigate" -> "Nav ${call.args.optString("action", "")}"
        "device_control" -> call.args.optString("action", "Device Control")
        "open_notification_chat" -> "Opened Chat"
        else -> call.name
    }
}

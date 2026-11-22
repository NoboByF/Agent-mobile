package com.example.automation

import android.content.Context
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.ai.GeneratedImageHelper
import com.example.ai.LiveToolCall
import com.example.data.AgentActivity
import com.example.data.AskAiMessage
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import com.example.messaging.IncomingMessageListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ToolDispatcher(
    private val appContext: Context,
    private val liveClientProvider: () -> GeminiLiveClient?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val autonomousEngine = AutonomousTaskEngine(appContext, liveClientProvider)

    fun dispatchCalls(calls: List<LiveToolCall>) {
        for (call in calls) {
            val job = scope.launch {
                val result = try {
                    executeSingleTool(call)
                } catch (ce: CancellationException) {
                    activeJobs.remove(call.id)
                    throw ce
                }
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

    fun cancelAllActiveActions(includeAutonomous: Boolean = false) {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        PilotAccessibilityService.instance?.stopContinuousScroll()
        if (includeAutonomous) {
            autonomousEngine.stopTask("Emergency STOP pressed")
        }
    }

    fun stopAutonomousTask(reason: String = "Stopped by user") {
        autonomousEngine.stopTask(reason)
    }

    fun isAutonomousTaskRunning(): Boolean = autonomousEngine.isRunning()

    fun shutdown() {
        cancelAllActiveActions(includeAutonomous = true)
        autonomousEngine.shutdown()
        scope.cancel()
    }

    private suspend fun executeSingleTool(call: LiveToolCall): JSONObject {
        val tickerLabel = friendlyTickerForTool(call)
        PilotStateHub.updateSession {
            it.copy(
                activity = if (call.name == "start_continuous_scroll") AgentActivity.SCROLLING else AgentActivity.EXECUTING_TOOL,
                statusTicker = tickerLabel
            )
        }

        val args = call.args
        val service = PilotAccessibilityService.instance

        // Tools that work even without AccessibilityService enabled
        val contextOnlyResult: JSONObject? = when (call.name) {
            "wait_ms" -> {
                val ms = args.optInt("milliseconds", 700).coerceIn(200, 5000)
                delay(ms.toLong())
                JSONObject().put("status", "ok").put("waited_ms", ms)
            }

            "web_search_and_answer" -> {
                val query = args.optString("query", "").trim()
                executeWebSearchBridge(query)
            }

            "read_recent_notifications" -> {
                val limit = args.optInt("limit", 5).coerceIn(1, 10)
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
                JSONObject()
                    .put("status", "ok")
                    .put("count", arr.length())
                    .put("messages", arr)
            }

            "get_device_status" -> {
                service?.getDeviceStatusJson()
                    ?: PilotAccessibilityService.getDeviceStatusWithContext(appContext)
            }

            "toggle_flashlight" -> {
                PilotAccessibilityService.toggleFlashlightWithContext(
                    appContext,
                    args.optBoolean("enabled", true)
                )
            }

            "set_alarm_or_timer" -> {
                PilotAccessibilityService.setAlarmOrTimerWithContext(
                    context = appContext,
                    mode = args.optString("mode", "TIMER"),
                    hours = args.optInt("hours", 0),
                    minutes = args.optInt("minutes", 5),
                    label = args.optString("label", "LivePilot AI")
                )
            }

            "clipboard_action" -> {
                PilotAccessibilityService.clipboardActionWithContext(
                    context = appContext,
                    action = args.optString("action", "READ"),
                    text = args.optString("text", "")
                )
            }

            "search_youtube" -> {
                PilotAccessibilityService.searchYouTubeWithContext(
                    context = appContext,
                    rawQuery = args.optString("query", "")
                )
            }

            "device_control" -> {
                PilotAccessibilityService.deviceControlWithContext(
                    context = appContext,
                    action = args.optString("action", ""),
                    value = args.optString("value", "")
                )
            }

            "start_autonomous_task" -> {
                autonomousEngine.startTask(
                    goal = args.optString("goal", ""),
                    rawMode = args.optString("mode", "AUTO")
                )
            }

            "update_autonomous_task" -> {
                autonomousEngine.updateInstruction(
                    instruction = args.optString("instruction", "")
                )
            }

            "stop_autonomous_task" -> {
                autonomousEngine.stopTask("Stopped by voice command")
            }

            "answer_sensitive_message" -> {
                executeAnswerSensitiveMessage(
                    senderOrApp = args.optString("sender_or_app", ""),
                    ownerAnswer = args.optString("owner_answer", ""),
                    service = service
                )
            }

            "generate_ai_image" -> {
                executeGenerateAiImage(args.optString("prompt", ""))
            }

            "end_live_call" -> {
                val reason = args.optString("reason", "User ended live call").trim()
                PilotStateHub.log(LogLevel.TOOL, "EndCall", "end_live_call triggered: $reason")
                PilotStateHub.activeController?.scheduleEndCallAfterSpeech(delayMs = 4_200L)
                JSONObject()
                    .put("status", "ok")
                    .put("ending_call", true)
                    .put("reason", reason)
                    .put(
                        "instruction",
                        "Say a brief warm goodbye in 1 short sentence; the call will end automatically right after you finish speaking."
                    )
            }

            else -> null
        }

        if (contextOnlyResult != null) {
            restoreListeningState()
            return contextOnlyResult
        }

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

        val result = try {
            when (call.name) {
                "open_app" -> service.openAppByName(args.optString("app_name", ""))
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
                "open_notification_chat" -> service.openNotificationChat(args.optString("sender_or_app", ""))
                "search_on_screen" -> SmartSearchAndFinder.searchOnScreen(
                    service = service,
                    appContext = appContext,
                    rawQuery = args.optString("query", ""),
                    openInGoogleIfNeeded = args.optBoolean("open_in_google_if_needed", false)
                )
                "find_and_interact" -> SmartSearchAndFinder.findAndInteract(
                    service = service,
                    rawTarget = args.optString("target", ""),
                    rawAction = args.optString("action", "CLICK"),
                    maxScrolls = args.optInt("max_scrolls", 4)
                )
                "draw_strokes" -> {
                    val left = args.optInt("canvas_left", 0)
                    val top = args.optInt("canvas_top", 0)
                    val right = args.optInt("canvas_right", 1000)
                    val bottom = args.optInt("canvas_bottom", 1000)
                    val box = if (right > left + 20 && bottom > top + 20) {
                        intArrayOf(left, top, right, bottom)
                    } else {
                        null
                    }
                    val strokes = DrawingGestureHelper.parseStrokesFromJson(args.optString("strokes_json", ""))
                    service.dispatchDrawStrokes(
                        strokes = strokes,
                        normalizedSpace = true,
                        canvasBoundsNorm = box
                    ) { done, total, lbl ->
                        PilotStateHub.updateSession {
                            it.copy(
                                activity = AgentActivity.AUTONOMOUS_TASK,
                                statusTicker = "🎨 $done/$total: $lbl"
                            )
                        }
                    }
                }
                else -> JSONObject()
                    .put("status", "error")
                    .put("message", "Unknown tool: ${call.name}")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            PilotStateHub.log(LogLevel.ERROR, "ToolDispatcher", "${call.name} threw: ${err.message}")
            JSONObject().put("status", "error").put("message", err.message ?: "Tool execution failed")
        }

        if (call.name != "start_continuous_scroll" && !autonomousEngine.isRunning()) {
            restoreListeningState()
        }
        return result
    }

    private suspend fun executeWebSearchBridge(query: String): JSONObject {
        if (query.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Search query was empty.")
        }
        val settings = PilotStateHub.settings.value
        if (!settings.hasAnyApiKey) {
            return JSONObject().put("status", "error").put("message", "No Gemini API key configured.")
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
            apiKey = settings.effectiveApiKey,
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

    private suspend fun executeGenerateAiImage(rawPrompt: String): JSONObject {
        val prompt = rawPrompt.trim()
        if (prompt.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Image prompt was empty.")
        }
        val settings = PilotStateHub.settings.value
        if (!settings.hasAnyApiKey) {
            return JSONObject().put("status", "error").put("message", "No Gemini API key configured.")
        }
        PilotStateHub.log(LogLevel.TOOL, "ImageGen", "Live call generating AI image: $prompt")
        val result = GeminiRestClient.generateImage(
            apiKey = settings.effectiveApiKey,
            prompt = prompt
        )
        return result.fold(
            onSuccess = { gen ->
                val savedFile = GeneratedImageHelper.saveGeneratedBytesToAppFile(
                    context = appContext,
                    bytes = gen.imageBytes,
                    mimeType = gen.mimeType
                )
                val galleryRes = GeneratedImageHelper.saveImageFileToGallery(
                    context = appContext,
                    filePath = savedFile.absolutePath,
                    displayLabel = "LivePilot_AI"
                )
                val caption = gen.captionText.ifBlank {
                    "\"$prompt\" — আপনার ছবিটি তৈরি হয়ে গেছে এবং গ্যালারিতে (Pictures/LivePilot AI) সেভ করা হয়েছে।"
                }
                PilotStateHub.recordGeneratedImageInAskAi(
                    prompt = prompt,
                    imagePath = savedFile.absolutePath,
                    caption = caption,
                    modelUsed = gen.modelUsed
                )
                PilotStateHub.log(
                    LogLevel.TOOL,
                    "ImageGen",
                    "Image generated via ${gen.modelUsed} (savedToGallery=${galleryRes.isSuccess})"
                )
                JSONObject()
                    .put("status", "ok")
                    .put("model", gen.modelUsed)
                    .put("saved_to_gallery", galleryRes.isSuccess)
                    .put("saved_to_ask_ai", true)
                    .put("file_name", savedFile.name)
                    .put(
                        "message",
                        "Image generated successfully and saved to Gallery (Pictures/LivePilot AI) and Ask AI tab. Confirm warmly to the user."
                    )
            },
            onFailure = { err ->
                PilotStateHub.log(LogLevel.ERROR, "ImageGen", "Live call image generation failed: ${err.message}")
                JSONObject()
                    .put("status", "error")
                    .put("message", err.message ?: "Image generation failed.")
            }
        )
    }

    private suspend fun executeAnswerSensitiveMessage(
        senderOrApp: String,
        ownerAnswer: String,
        service: PilotAccessibilityService?
    ): JSONObject {
        val cleanAnswer = ownerAnswer.trim()
        if (cleanAnswer.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Owner answer was empty.")
        }
        val pending = PilotStateHub.findPendingSensitiveMessage(senderOrApp)
            ?: return JSONObject()
                .put("status", "not_found")
                .put("message", "No pending sensitive question matched '${senderOrApp.ifBlank { "latest" }}'.")

        val handle = PilotStateHub.getNotificationHandleById(pending.id)
        val directAction = handle?.replyAction
        if (directAction != null) {
            val sent = IncomingMessageListener.sendDirectReply(
                context = appContext,
                action = directAction,
                replyText = cleanAnswer,
                packageName = pending.packageName,
                senderName = pending.senderName
            )
            if (sent) {
                PilotStateHub.markSensitiveMessageResolved(pending.id, cleanAnswer)
                PilotStateHub.log(
                    LogLevel.TOOL,
                    "SensitiveReply",
                    "Voice-forwarded owner's answer to ${pending.senderName} (${pending.app.displayName}): $cleanAnswer"
                )
                return JSONObject()
                    .put("status", "ok")
                    .put("sender", pending.senderName)
                    .put("app", pending.app.displayName)
                    .put("sent_reply", cleanAnswer)
            }
        }

        // Fallback: open chat visually via Accessibility if Direct Reply action is unavailable
        if (service != null) {
            IncomingMessageListener.registerSentReply(pending.packageName, pending.senderName, cleanAnswer)
            service.openNotificationChat(pending.senderName)
            delay(900L)
            val visualRes = service.typeText(
                index = -1,
                x = 0,
                y = 0,
                text = cleanAnswer,
                pressSend = true
            )
            if (visualRes.optString("status") == "ok") {
                PilotStateHub.markSensitiveMessageResolved(pending.id, cleanAnswer)
                return JSONObject()
                    .put("status", "ok")
                    .put("sender", pending.senderName)
                    .put("app", pending.app.displayName)
                    .put("sent_reply", cleanAnswer)
                    .put("method", "visual_accessibility")
            }
        }

        return JSONObject()
            .put("status", "error")
            .put("message", "Could not send Direct Reply to ${pending.senderName}; please open ${pending.app.displayName} manually or answer from Auto-Chat.")
    }

    private fun restoreListeningState() {
        if (autonomousEngine.isRunning()) return
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
        "answer_sensitive_message" -> "Sending owner's answer..."
        "start_autonomous_task" -> "🤖 Starting autonomous task..."
        "update_autonomous_task" -> "🤖 Updating task..."
        "stop_autonomous_task" -> "Stopping autonomous task..."
        "draw_strokes" -> "🎨 Drawing strokes..."
        "search_on_screen" -> "🔍 Searching '${call.args.optString("query", "").take(18)}'..."
        "find_and_interact" -> "🔎 Finding '${call.args.optString("target", "").take(18)}'..."
        "generate_ai_image" -> "🎨 Generating AI image..."
        "end_live_call" -> "📞 Ending live call..."
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
        "answer_sensitive_message" -> "Answered Sensitive Msg"
        "start_autonomous_task" -> "AutoPilot: ${call.args.optString("goal", "Task").take(20)}"
        "update_autonomous_task" -> "Updated AutoPilot"
        "stop_autonomous_task" -> "Stopped AutoPilot"
        "draw_strokes" -> "Drew Vector Strokes"
        "search_on_screen" -> "Search: ${call.args.optString("query", "").take(20)}"
        "find_and_interact" -> "Found: ${call.args.optString("target", "").take(20)}"
        "generate_ai_image" -> "Generated Image"
        "end_live_call" -> "Ended Live Call"
        else -> call.name
    }
}

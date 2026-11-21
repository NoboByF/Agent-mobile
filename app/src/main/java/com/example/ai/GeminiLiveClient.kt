package com.example.ai

import android.util.Base64
import com.example.data.LiveConnectionPhase
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class LiveToolCall(
    val id: String,
    val name: String,
    val args: JSONObject
)

interface GeminiLiveCallbacks {
    fun onSetupComplete()
    fun onAudioOutput(pcm24kBytes: ByteArray)
    fun onInterrupted()
    fun onToolCall(calls: List<LiveToolCall>)
    fun onToolCallCancellation(ids: List<String>)
    fun onConnectionPhaseChanged(phase: LiveConnectionPhase, detail: String)
    fun onInputTranscript(text: String)
    fun onOutputTranscript(text: String)
}

class GeminiLiveClient(
    private val callbacks: GeminiLiveCallbacks
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    private val setupCompleted = AtomicBoolean(false)
    private val manuallyClosed = AtomicBoolean(false)

    @Volatile
    private var savedResumptionHandle: String? = null

    @Volatile
    private var currentApiKey: String = ""

    @Volatile
    private var currentModel: String = ""

    @Volatile
    private var currentVoice: String = "Kore"

    @Volatile
    private var currentCustomInstructions: String = ""

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    fun isReadyForRealtimeInput(): Boolean = setupCompleted.get() && webSocket != null

    fun connect(
        apiKey: String,
        modelId: String,
        voiceName: String,
        customInstructions: String = ""
    ) {
        manuallyClosed.set(false)
        setupCompleted.set(false)
        reconnectAttempt = 0
        reconnectJob?.cancel()

        currentApiKey = apiKey.trim()
        currentModel = normalizeModelPath(modelId)
        currentVoice = voiceName.trim().ifBlank { "Kore" }
        currentCustomInstructions = customInstructions.trim()

        openSocketInternal(isReconnect = false)
    }

    fun disconnect() {
        manuallyClosed.set(true)
        setupCompleted.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "User ended live session")
        callbacks.onConnectionPhaseChanged(LiveConnectionPhase.IDLE, "Disconnected")
        PilotStateHub.log(LogLevel.WS, "LiveClient", "Session closed cleanly by user.")
    }

    private fun openSocketInternal(isReconnect: Boolean) {
        if (currentApiKey.isBlank()) {
            callbacks.onConnectionPhaseChanged(LiveConnectionPhase.ERROR, "Missing Gemini API Key")
            PilotStateHub.log(LogLevel.ERROR, "LiveClient", "Cannot connect: API key is empty.")
            return
        }

        setupCompleted.set(false)
        webSocket?.cancel()
        webSocket = null

        val phase = if (isReconnect) LiveConnectionPhase.RECONNECTING else LiveConnectionPhase.CONNECTING
        val detail = if (isReconnect) {
            "Reconnecting with session memory..."
        } else {
            "Connecting to $currentModel..."
        }
        callbacks.onConnectionPhaseChanged(phase, detail)

        val encodedKey = URLEncoder.encode(currentApiKey, "UTF-8")
        val url = "$WS_BASE_URL?key=$encodedKey"
        val request = Request.Builder().url(url).build()

        PilotStateHub.log(
            LogLevel.WS,
            "LiveClient",
            if (isReconnect) "Opening reconnect WebSocket (handle=${savedResumptionHandle != null})"
            else "Opening WebSocket for $currentModel (voice=$currentVoice)"
        )

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                PilotStateHub.log(LogLevel.WS, "onOpen", "HTTP ${response.code} connected. Sending setup handshake...")
                callbacks.onConnectionPhaseChanged(LiveConnectionPhase.WAITING_SETUP, "Sending setup handshake...")
                val setupPayload = buildSetupPayload(
                    modelPath = currentModel,
                    voiceName = currentVoice,
                    resumptionHandle = savedResumptionHandle,
                    customInstructions = currentCustomInstructions
                )
                val sent = webSocket.send(setupPayload.toString())
                if (!sent) {
                    PilotStateHub.log(LogLevel.ERROR, "onOpen", "Failed to enqueue setup JSON.")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                PilotStateHub.log(LogLevel.WS, "onClosing", "code=$code reason=${reason.ifBlank { "(none)" }}")
                setupCompleted.set(false)
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                PilotStateHub.log(LogLevel.WS, "onClosed", "code=$code reason=${reason.ifBlank { "(none)" }}")
                setupCompleted.set(false)
                if (!manuallyClosed.get()) {
                    scheduleReconnect("Closed ($code: $reason)")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code?.let { " HTTP $it" } ?: ""
                val msg = "${t.javaClass.simpleName}: ${t.message ?: "Unknown error"}$httpCode"
                PilotStateHub.log(LogLevel.ERROR, "onFailure", msg)
                setupCompleted.set(false)
                if (!manuallyClosed.get()) {
                    scheduleReconnect(msg)
                }
            }
        })
    }

    private fun handleIncomingMessage(rawJson: String) {
        val json = runCatching { JSONObject(rawJson) }.getOrElse { err ->
            PilotStateHub.log(LogLevel.ERROR, "Parse", "Malformed WS frame: ${err.message}")
            return
        }

        if (json.has("setupComplete")) {
            reconnectAttempt = 0
            setupCompleted.set(true)
            PilotStateHub.log(LogLevel.WS, "setupComplete", "Handshake verified! Starting full-duplex audio.")
            callbacks.onConnectionPhaseChanged(LiveConnectionPhase.READY, "Connected • Listening")
            callbacks.onSetupComplete()
            return
        }

        json.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val newHandle = update.optString("newHandle", "")
            val resumable = update.optBoolean("resumable", true)
            if (newHandle.isNotBlank() && resumable) {
                savedResumptionHandle = newHandle
                PilotStateHub.updateSession { it.copy(sessionHandle = newHandle.take(12) + "…") }
                PilotStateHub.log(LogLevel.WS, "Resumption", "Cached session resumption handle.")
            }
        }

        json.optJSONObject("goAway")?.let { goAway ->
            val timeLeft = goAway.optString("timeLeft", "soon")
            PilotStateHub.log(LogLevel.WS, "goAway", "Server requested reconnect (timeLeft=$timeLeft). Reconnecting...")
            if (!manuallyClosed.get()) {
                scheduleReconnect("Server goAway ($timeLeft)", immediate = true)
            }
        }

        json.optJSONObject("toolCallCancellation")?.let { cancelObj ->
            val idsArray = cancelObj.optJSONArray("ids")
            val ids = mutableListOf<String>()
            if (idsArray != null) {
                for (i in 0 until idsArray.length()) {
                    ids.add(idsArray.optString(i))
                }
            }
            PilotStateHub.log(LogLevel.TOOL, "toolCallCancellation", "Cancelled calls: $ids")
            callbacks.onToolCallCancellation(ids)
        }

        json.optJSONObject("toolCall")?.let { toolCallObj ->
            val funcCalls = toolCallObj.optJSONArray("functionCalls")
            if (funcCalls != null && funcCalls.length() > 0) {
                val calls = mutableListOf<LiveToolCall>()
                for (i in 0 until funcCalls.length()) {
                    val item = funcCalls.optJSONObject(i) ?: continue
                    val id = item.optString("id", "call_$i")
                    val name = item.optString("name", "")
                    val args = item.optJSONObject("args") ?: JSONObject()
                    if (name.isNotBlank()) {
                        calls.add(LiveToolCall(id = id, name = name, args = args))
                    }
                }
                if (calls.isNotEmpty()) {
                    PilotStateHub.log(
                        LogLevel.TOOL,
                        "toolCall",
                        calls.joinToString { "${it.name}(${it.args})" }
                    )
                    callbacks.onToolCall(calls)
                }
            }
        }

        json.optJSONObject("serverContent")?.let { content ->
            if (content.optBoolean("interrupted", false)) {
                PilotStateHub.log(LogLevel.AUDIO, "Barge-In", "Server signaled interruption — flushing audio & gestures.")
                callbacks.onInterrupted()
            }

            content.optJSONObject("inputTranscription")?.optString("text", "")?.let { text ->
                if (text.isNotBlank()) {
                    PilotStateHub.appendCaption("You", text)
                    callbacks.onInputTranscript(text)
                }
            }

            content.optJSONObject("outputTranscription")?.optString("text", "")?.let { text ->
                if (text.isNotBlank()) {
                    PilotStateHub.appendCaption("LivePilot", text)
                    callbacks.onOutputTranscript(text)
                }
            }

            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val mime = inlineData.optString("mimeType", "")
                        val b64 = inlineData.optString("data", "")
                        if (b64.isNotBlank() && mime.startsWith("audio/")) {
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            callbacks.onAudioOutput(pcm)
                        }
                    }
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (manuallyClosed.get()) return
        reconnectJob?.cancel()
        reconnectAttempt += 1
        if (reconnectAttempt > 6) {
            callbacks.onConnectionPhaseChanged(LiveConnectionPhase.ERROR, "Disconnected: $reason")
            PilotStateHub.log(LogLevel.ERROR, "Reconnect", "Max reconnect attempts reached ($reason).")
            return
        }
        val backoffMs = if (immediate) 400L else minOf(1200L * reconnectAttempt, 8000L)
        callbacks.onConnectionPhaseChanged(
            LiveConnectionPhase.RECONNECTING,
            "Reconnecting (#$reconnectAttempt)..."
        )
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (!manuallyClosed.get()) {
                openSocketInternal(isReconnect = true)
            }
        }
    }

    fun sendAudioPcm(pcmBytes: ByteArray): Boolean {
        if (!setupCompleted.get()) return false
        val ws = webSocket ?: return false
        val base64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("mimeType", "audio/pcm;rate=16000")
                    .put("data", base64)
            )
        )
        return ws.send(payload.toString())
    }

    fun sendVisionFrame(jpegBytes: ByteArray): Boolean {
        if (!setupCompleted.get()) return false
        val ws = webSocket ?: return false
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val payload = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "video",
                JSONObject()
                    .put("mimeType", "image/jpeg")
                    .put("data", base64)
            )
        )
        val ok = ws.send(payload.toString())
        PilotStateHub.log(LogLevel.WS, "Vision", "Streamed JPEG frame (${jpegBytes.size / 1024} KB) sent=$ok")
        return ok
    }

    fun sendSystemAlert(alertText: String): Boolean {
        if (!setupCompleted.get()) return false
        val ws = webSocket ?: return false
        val payload = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", alertText))
                            )
                    )
                )
                .put("turnComplete", true)
        )
        val ok = ws.send(payload.toString())
        PilotStateHub.log(LogLevel.WS, "SystemAlert", "Injected alert into Live session: ${alertText.take(90)}")
        return ok
    }

    fun sendUserTextTurn(text: String): Boolean {
        if (!setupCompleted.get()) return false
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val ws = webSocket ?: return false
        val payload = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", clean))
                            )
                    )
                )
                .put("turnComplete", true)
        )
        val ok = ws.send(payload.toString())
        if (ok) {
            PilotStateHub.appendCaption("You", clean)
            PilotStateHub.log(LogLevel.WS, "TextTurn", "Sent typed command: ${clean.take(80)}")
        }
        return ok
    }

    fun sendToolResponse(callId: String, toolName: String, result: JSONObject): Boolean {
        val ws = webSocket ?: return false
        val responseEntry = JSONObject()
            .put("id", callId)
            .put("name", toolName)
            .put("response", JSONObject().put("result", result))

        val payload = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", JSONArray().put(responseEntry))
        )
        val ok = ws.send(payload.toString())
        PilotStateHub.log(LogLevel.TOOL, "toolResponse", "$toolName ($callId) sent=$ok")
        return ok
    }

    companion object {
        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        fun normalizeModelPath(modelId: String): String {
            val cleaned = modelId.trim().removePrefix("models/")
            return "models/${cleaned.ifBlank { "gemini-3.8-live" }}"
        }

        fun buildSetupPayload(
            modelPath: String,
            voiceName: String,
            resumptionHandle: String?,
            customInstructions: String = ""
        ): JSONObject {
            val prebuiltVoice = JSONObject().put("voiceName", voiceName.ifBlank { "Kore" })
            val speechConfig = JSONObject().put(
                "voiceConfig",
                JSONObject().put("prebuiltVoiceConfig", prebuiltVoice)
            )
            val generationConfig = JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", speechConfig)

            val sessionResumption = JSONObject()
            if (!resumptionHandle.isNullOrBlank()) {
                sessionResumption.put("handle", resumptionHandle)
            }

            val systemInstruction = JSONObject().put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "text",
                        GeminiToolSchemas.buildSystemInstruction(customInstructions)
                    )
                )
            )

            val setup = JSONObject()
                .put("model", modelPath)
                .put("generationConfig", generationConfig)
                .put("outputAudioTranscription", JSONObject())
                .put("inputAudioTranscription", JSONObject())
                .put("sessionResumption", sessionResumption)
                .put("systemInstruction", systemInstruction)
                .put("tools", GeminiToolSchemas.buildToolsJsonArray())

            return JSONObject().put("setup", setup)
        }

        suspend fun testLiveHandshake(
            apiKey: String,
            modelId: String,
            voiceName: String
        ): Triple<Boolean, String, Long> {
            val trimmedKey = apiKey.trim()
            if (trimmedKey.isBlank()) {
                return Triple(false, "Enter your Gemini API Key in Settings first.", 0L)
            }

            val startMs = System.currentTimeMillis()
            val normalizedModel = normalizeModelPath(modelId)
            val resultDeferred = CompletableDeferred<Pair<Boolean, String>>()

            val client = OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val encodedKey = URLEncoder.encode(trimmedKey, "UTF-8")
            val request = Request.Builder().url("$WS_BASE_URL?key=$encodedKey").build()

            PilotStateHub.log(LogLevel.WS, "TestConnect", "Testing handshake with $normalizedModel...")

            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val payload = buildSetupPayload(normalizedModel, voiceName, null)
                    webSocket.send(payload.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleTestMsg(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleTestMsg(webSocket, bytes.utf8())
                }

                private fun handleTestMsg(webSocket: WebSocket, raw: String) {
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json != null && json.has("setupComplete")) {
                        resultDeferred.complete(true to "Handshake OK ($normalizedModel)")
                        webSocket.close(1000, "Diagnostic complete")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(
                            false to "Server closed ($code): ${reason.ifBlank { "Check model ID or API key" }}"
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!resultDeferred.isCompleted) {
                        val codePart = response?.code?.let { "HTTP $it - " } ?: ""
                        resultDeferred.complete(false to "$codePart${t.message ?: "Connection failed"}")
                    }
                }
            })

            val outcome = withTimeoutOrNull(14_000L) { resultDeferred.await() }
                ?: (false to "Timed out waiting for setupComplete")
            ws.close(1000, "Test done")

            val elapsed = System.currentTimeMillis() - startMs
            PilotStateHub.log(
                if (outcome.first) LogLevel.INFO else LogLevel.ERROR,
                "TestConnect",
                "${outcome.second} (${elapsed}ms)"
            )
            return Triple(outcome.first, outcome.second, elapsed)
        }
    }
}

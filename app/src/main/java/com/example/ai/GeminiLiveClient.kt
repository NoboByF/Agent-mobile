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
    private var currentKeyEntryId: String? = null

    @Volatile
    private var currentKeyLabel: String? = null

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
        apiKey: String = "",
        modelId: String,
        voiceName: String,
        customInstructions: String = ""
    ) {
        manuallyClosed.set(false)
        setupCompleted.set(false)
        reconnectAttempt = 0
        reconnectJob?.cancel()

        currentModel = normalizeModelPath(modelId)
        currentVoice = voiceName.trim().ifBlank { "Kore" }
        currentCustomInstructions = customInstructions.trim()

        val selection = ApiKeyPoolManager.selectBestKey()
        if (selection.entry != null) {
            currentApiKey = selection.entry.key.trim()
            currentKeyEntryId = selection.entry.id
            currentKeyLabel = selection.entry.label
            PilotStateHub.updateSession { it.copy(activeKeyLabel = selection.entry.label) }
        } else if (apiKey.isNotBlank() && selection.totalEnabledKeys == 0) {
            currentApiKey = apiKey.trim()
            currentKeyEntryId = null
            currentKeyLabel = "Key 1"
            PilotStateHub.updateSession { it.copy(activeKeyLabel = "Key 1") }
        } else {
            val msg = ApiKeyPoolManager.buildNoKeyAvailableMessage(selection)
            callbacks.onConnectionPhaseChanged(LiveConnectionPhase.ERROR, msg)
            PilotStateHub.log(LogLevel.ERROR, "LiveClient", msg)
            return
        }

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
        val previousWs = webSocket
        webSocket = null
        previousWs?.cancel()

        val keyTag = currentKeyLabel?.let { " [$it]" }.orEmpty()
        val phase = if (isReconnect) LiveConnectionPhase.RECONNECTING else LiveConnectionPhase.CONNECTING
        val detail = if (isReconnect) {
            "Reconnecting$keyTag..."
        } else {
            "Connecting to ${currentModel.removePrefix("models/")}$keyTag..."
        }
        callbacks.onConnectionPhaseChanged(phase, detail)

        val encodedKey = URLEncoder.encode(currentApiKey, "UTF-8")
        val url = "$WS_BASE_URL?key=$encodedKey"
        val request = Request.Builder().url(url).build()

        PilotStateHub.log(
            LogLevel.WS,
            "LiveClient",
            if (isReconnect) "Opening reconnect WebSocket$keyTag (handle=${savedResumptionHandle != null})"
            else "Opening WebSocket$keyTag for $currentModel (voice=$currentVoice)"
        )

        val activeWs = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                PilotStateHub.log(LogLevel.WS, "onOpen", "HTTP ${response.code} connected$keyTag. Sending setup handshake...")
                callbacks.onConnectionPhaseChanged(LiveConnectionPhase.WAITING_SETUP, "Syncing setup$keyTag...")
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
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                handleIncomingMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                PilotStateHub.log(LogLevel.WS, "onClosing", "code=$code reason=${reason.ifBlank { "(none)" }}")
                setupCompleted.set(false)
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                PilotStateHub.log(LogLevel.WS, "onClosed", "code=$code reason=${reason.ifBlank { "(none)" }}")
                setupCompleted.set(false)
                if (!manuallyClosed.get()) {
                    handleDisconnectWithKeyFailover(
                        httpCode = null,
                        wsCloseCode = code,
                        reason = reason.ifBlank { "Closed ($code)" }
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== this@GeminiLiveClient.webSocket) return
                val httpCode = response?.code
                val httpTag = httpCode?.let { " HTTP $it" } ?: ""
                val msg = "${t.javaClass.simpleName}: ${t.message ?: "Unknown error"}$httpTag"
                PilotStateHub.log(LogLevel.ERROR, "onFailure", msg)
                setupCompleted.set(false)
                if (!manuallyClosed.get()) {
                    handleDisconnectWithKeyFailover(
                        httpCode = httpCode,
                        wsCloseCode = null,
                        reason = msg
                    )
                }
            }
        })
        webSocket = activeWs
    }

    /**
     * Checks if the disconnect was caused by quota exhaustion, rate-limiting, or an invalid key.
     * If so, rotates seamlessly to the next healthy backup key in ApiKeyPoolManager and reconnects.
     */
    private fun handleDisconnectWithKeyFailover(
        httpCode: Int?,
        wsCloseCode: Int?,
        reason: String
    ) {
        val classification = ApiKeyPoolManager.classifyFailure(
            httpCode = httpCode,
            wsCloseCode = wsCloseCode,
            message = reason
        )
        val keyId = currentKeyEntryId

        if (classification.kind != ApiFailureKind.TRANSIENT && keyId != null) {
            val failedLabel = currentKeyLabel ?: "Active Key"
            val nextKey = ApiKeyPoolManager.reportFailureAndRotate(
                failedKeyId = keyId,
                classification = classification
            )
            if (nextKey != null) {
                // Resumption handles are scoped per API key/project, so clear before switching
                savedResumptionHandle = null
                currentApiKey = nextKey.key.trim()
                currentKeyEntryId = nextKey.id
                currentKeyLabel = nextKey.label
                reconnectAttempt = 0
                PilotStateHub.updateSession {
                    it.copy(
                        activeKeyLabel = nextKey.label,
                        sessionHandle = null,
                        statusTicker = "Switched to ${nextKey.label} ($failedLabel full)"
                    )
                }
                scheduleReconnect("Failover to ${nextKey.label}", immediate = true)
                return
            } else {
                val selection = ApiKeyPoolManager.selectBestKey()
                val exhaustedMsg = ApiKeyPoolManager.buildNoKeyAvailableMessage(selection)
                callbacks.onConnectionPhaseChanged(LiveConnectionPhase.ERROR, exhaustedMsg)
                PilotStateHub.log(LogLevel.ERROR, "KeyPool", exhaustedMsg)
                return
            }
        }

        // Transient network hiccup or non-quota close: retry with backoff
        scheduleReconnect(reason, immediate = false)
    }

    private fun handleIncomingMessage(rawJson: String) {
        val json = runCatching { JSONObject(rawJson) }.getOrElse { err ->
            PilotStateHub.log(LogLevel.ERROR, "Parse", "Malformed WS frame: ${err.message}")
            return
        }

        if (json.has("setupComplete")) {
            reconnectAttempt = 0
            setupCompleted.set(true)
            currentKeyEntryId?.let { ApiKeyPoolManager.reportSuccess(it) }
            val keyTag = currentKeyLabel?.let { " [$it]" }.orEmpty()
            PilotStateHub.log(LogLevel.WS, "setupComplete", "Handshake verified$keyTag! Starting full-duplex audio.")
            callbacks.onConnectionPhaseChanged(LiveConnectionPhase.READY, "Connected$keyTag • Listening")
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
        val backoffMs = if (immediate) 350L else minOf(1200L * reconnectAttempt, 8000L)
        callbacks.onConnectionPhaseChanged(
            LiveConnectionPhase.RECONNECTING,
            if (immediate) reason else "Reconnecting (#$reconnectAttempt)..."
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

        private val sharedTestClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

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

        /**
         * Tests Live API WebSocket handshake with automatic multi-key failover if active key is quota-limited.
         */
        suspend fun testLiveHandshake(
            apiKey: String = "",
            modelId: String,
            voiceName: String
        ): Triple<Boolean, String, Long> {
            val settings = PilotStateHub.settings.value
            val pool = ApiKeyPoolManager.effectivePool(settings)
            val triedIds = mutableSetOf<String>()
            val maxAttempts = if (pool.isNotEmpty() && settings.autoRotateApiKeys) {
                pool.count { it.enabled }.coerceIn(1, 8)
            } else {
                1
            }

            val normalizedModel = normalizeModelPath(modelId)
            var lastMessage = "Connection failed"
            var lastElapsed = 0L

            for (attempt in 1..maxAttempts) {
                val selection = if (pool.isNotEmpty()) {
                    ApiKeyPoolManager.selectBestKey(
                        settings = PilotStateHub.settings.value,
                        excludeIds = triedIds
                    )
                } else {
                    null
                }
                val entry = selection?.entry
                val keyToUse = entry?.key?.trim() ?: apiKey.trim()
                if (keyToUse.isBlank()) {
                    val msg = if (selection != null) {
                        ApiKeyPoolManager.buildNoKeyAvailableMessage(selection)
                    } else {
                        "Add at least one Gemini API Key in Settings first."
                    }
                    return Triple(false, msg, 0L)
                }
                entry?.let { triedIds.add(it.id) }

                val labelTag = entry?.let { " [${it.label}]" }.orEmpty()
                val startMs = System.currentTimeMillis()
                val outcome = runSingleHandshakeTest(keyToUse, normalizedModel, voiceName, labelTag)
                lastElapsed = System.currentTimeMillis() - startMs

                if (outcome.success) {
                    entry?.let { ApiKeyPoolManager.reportSuccess(it.id) }
                    val msg = "Handshake OK ($normalizedModel$labelTag)"
                    PilotStateHub.log(LogLevel.INFO, "TestConnect", "$msg (${lastElapsed}ms)")
                    return Triple(true, msg, lastElapsed)
                }

                lastMessage = outcome.message
                PilotStateHub.log(LogLevel.ERROR, "TestConnect", "${outcome.message}$labelTag (${lastElapsed}ms)")

                if (entry != null) {
                    val classification = ApiKeyPoolManager.classifyFailure(
                        httpCode = outcome.httpCode,
                        wsCloseCode = outcome.wsCloseCode,
                        message = outcome.message
                    )
                    if (classification.kind != ApiFailureKind.TRANSIENT) {
                        val next = ApiKeyPoolManager.reportFailureAndRotate(entry.id, classification)
                        if (next != null && attempt < maxAttempts) {
                            continue
                        }
                    }
                }
                break
            }

            return Triple(false, lastMessage, lastElapsed)
        }

        private data class HandshakeAttemptOutcome(
            val success: Boolean,
            val message: String,
            val httpCode: Int? = null,
            val wsCloseCode: Int? = null
        )

        private suspend fun runSingleHandshakeTest(
            rawKey: String,
            normalizedModel: String,
            voiceName: String,
            labelTag: String
        ): HandshakeAttemptOutcome {
            val resultDeferred = CompletableDeferred<HandshakeAttemptOutcome>()
            val encodedKey = URLEncoder.encode(rawKey, "UTF-8")
            val request = Request.Builder().url("$WS_BASE_URL?key=$encodedKey").build()

            PilotStateHub.log(LogLevel.WS, "TestConnect", "Testing handshake with $normalizedModel$labelTag...")

            val ws = sharedTestClient.newWebSocket(request, object : WebSocketListener() {
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
                        resultDeferred.complete(HandshakeAttemptOutcome(true, "OK"))
                        webSocket.close(1000, "Diagnostic complete")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(
                            HandshakeAttemptOutcome(
                                success = false,
                                message = "Server closed ($code): ${reason.ifBlank { "Check model ID or API key" }}",
                                wsCloseCode = code
                            )
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!resultDeferred.isCompleted) {
                        val codePart = response?.code?.let { "HTTP $it - " } ?: ""
                        resultDeferred.complete(
                            HandshakeAttemptOutcome(
                                success = false,
                                message = "$codePart${t.message ?: "Connection failed"}",
                                httpCode = response?.code
                            )
                        )
                    }
                }
            })

            val outcome = withTimeoutOrNull(14_000L) { resultDeferred.await() }
                ?: HandshakeAttemptOutcome(false, "Timed out waiting for setupComplete")
            ws.close(1000, "Test done")
            return outcome
        }
    }
}

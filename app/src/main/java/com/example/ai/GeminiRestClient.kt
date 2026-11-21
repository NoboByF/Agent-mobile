package com.example.ai

import com.example.data.AskAiMessage
import com.example.data.DiscoveredModel
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import com.example.data.WebCitation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ChatGenerationResult(
    val text: String,
    val citations: List<WebCitation>,
    val modelUsed: String
)

object GeminiRestClient {

    private const val REST_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Queries GET /v1beta/models?key={apiKey} and discovers both Live/Native-Audio models
     * and standard text/generateContent models so users can switch with one tap.
     */
    suspend fun fetchAvailableModels(apiKey: String): Result<List<DiscoveredModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = apiKey.trim()
                require(trimmed.isNotBlank()) { "Please enter your Gemini API Key first." }

                val encodedKey = URLEncoder.encode(trimmed, "UTF-8")
                val request = Request.Builder()
                    .url("$REST_BASE/models?key=$encodedKey")
                    .get()
                    .build()

                PilotStateHub.log(LogLevel.INFO, "Models", "Fetching available models from v1beta/models...")

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errMsg = extractApiError(bodyStr, response.code)
                        PilotStateHub.log(LogLevel.ERROR, "Models", "Fetch failed: $errMsg")
                        error(errMsg)
                    }

                    val root = JSONObject(bodyStr)
                    val arr = root.optJSONArray("models") ?: JSONArray()
                    val discovered = mutableListOf<DiscoveredModel>()

                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val fullName = obj.optString("name", "")
                        val id = fullName.removePrefix("models/")
                        if (id.isBlank() || !id.contains("gemini", ignoreCase = true)) continue

                        val displayName = obj.optString("displayName", id)
                        val methodsArr = obj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        val methods = mutableSetOf<String>()
                        for (m in 0 until methodsArr.length()) {
                            methods.add(methodsArr.optString(m))
                        }

                        val isLive = methods.contains("bidiGenerateContent") ||
                            id.contains("live", ignoreCase = true) ||
                            id.contains("native-audio", ignoreCase = true)
                        val supportsGen = methods.contains("generateContent")

                        if (isLive || supportsGen) {
                            discovered.add(
                                DiscoveredModel(
                                    id = id,
                                    displayName = displayName,
                                    isLiveCapable = isLive,
                                    supportsGenerateContent = supportsGen
                                )
                            )
                        }
                    }

                    val sorted = discovered.sortedWith(
                        compareByDescending<DiscoveredModel> { it.isLiveCapable }
                            .thenBy { it.id }
                    )
                    PilotStateHub.log(
                        LogLevel.INFO,
                        "Models",
                        "Discovered ${sorted.size} Gemini models (${sorted.count { it.isLiveCapable }} live-capable)."
                    )
                    sorted
                }
            }
        }

    /**
     * Multi-turn Gemini chat with optional Google Search Grounding (google_search tool).
     */
    suspend fun generateChatTurn(
        apiKey: String,
        modelId: String,
        history: List<AskAiMessage>,
        enableGoogleSearch: Boolean
    ): Result<ChatGenerationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = apiKey.trim()
            require(trimmed.isNotBlank()) { "Add your Gemini API Key in Settings to use Ask AI." }

            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-4-hydrogen" }
            val encodedKey = URLEncoder.encode(trimmed, "UTF-8")

            val contents = JSONArray()
            history.takeLast(20).forEach { msg ->
                contents.put(
                    JSONObject()
                        .put("role", if (msg.isUser) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                )
            }

            val systemInstruction = JSONObject().put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "text",
                        "You are LivePilot AI's intelligent bilingual assistant (Bengali & English). Give accurate, clear, well-structured answers. Respond in Bengali if the user writes in Bengali or Banglish, and English otherwise. When Google Search results are available, synthesize up-to-date facts clearly."
                    )
                )
            )

            val payload = JSONObject()
                .put("system_instruction", systemInstruction)
                .put("contents", contents)

            if (enableGoogleSearch) {
                payload.put(
                    "tools",
                    JSONArray().put(JSONObject().put("google_search", JSONObject()))
                )
            }

            val url = "$REST_BASE/models/$cleanModel:generateContent?key=$encodedKey"
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            PilotStateHub.log(
                LogLevel.INFO,
                "AskAI",
                "Calling $cleanModel (searchGrounding=$enableGoogleSearch)..."
            )

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errMsg = extractApiError(bodyStr, response.code)
                    PilotStateHub.log(LogLevel.ERROR, "AskAI", "Error: $errMsg")
                    error(errMsg)
                }

                val root = JSONObject(bodyStr)
                val candidates = root.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                    ?: error("No response candidate returned by $cleanModel.")

                val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                        if (t.isNotBlank()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(t)
                        }
                    }
                }

                val citations = parseGroundingCitations(firstCandidate.optJSONObject("groundingMetadata"))
                val finalText = sb.toString().trim().ifBlank { "I couldn't generate a text reply for that prompt." }

                PilotStateHub.log(
                    LogLevel.INFO,
                    "AskAI",
                    "Received response (${finalText.length} chars, ${citations.size} web sources)."
                )
                ChatGenerationResult(
                    text = finalText,
                    citations = citations,
                    modelUsed = cleanModel
                )
            }
        }
    }

    /**
     * Generates a concise, natural auto-reply for incoming imo/WhatsApp/Messenger/Telegram messages
     * using the customizable text/auto-reply model ID configured in Settings.
     */
    suspend fun generateAutoReply(
        apiKey: String,
        modelId: String,
        personaPrompt: String,
        appName: String,
        senderName: String,
        incomingText: String,
        screenContext: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = apiKey.trim()
            require(trimmed.isNotBlank()) { "API key not configured for Auto-Reply." }

            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-4-hydrogen" }
            val encodedKey = URLEncoder.encode(trimmed, "UTF-8")

            val promptText = buildString {
                append("Messaging App: ").append(appName).append('\n')
                append("Sender: ").append(senderName).append('\n')
                append("Incoming Message: \"").append(incomingText).append("\"\n")
                if (!screenContext.isNullOrBlank()) {
                    append("Visible Chat Screen Context: ").append(screenContext.take(700)).append('\n')
                }
                append("Write ONLY the exact short reply message text to send back (no quotes, no explanations).")
            }

            val payload = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "$personaPrompt Keep replies short (1-2 sentences max), warm, and directly ready to send."
                            )
                        )
                    )
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", promptText)))
                    )
                )

            val request = Request.Builder()
                .url("$REST_BASE/models/$cleanModel:generateContent?key=$encodedKey")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(extractApiError(bodyStr, response.code))
                }
                val root = JSONObject(bodyStr)
                val parts = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                val reply = buildString {
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            append(parts.optJSONObject(i)?.optString("text", "").orEmpty())
                        }
                    }
                }.trim().removeSurrounding("\"")
                require(reply.isNotBlank()) { "Empty auto-reply returned." }
                reply
            }
        }
    }

    private fun parseGroundingCitations(metadata: JSONObject?): List<WebCitation> {
        if (metadata == null) return emptyList()
        val chunks = metadata.optJSONArray("groundingChunks") ?: return emptyList()
        val seen = LinkedHashMap<String, WebCitation>()
        for (i in 0 until chunks.length()) {
            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val uri = web.optString("uri", "").trim()
            val title = web.optString("title", "").trim().ifBlank { uri }
            if (uri.startsWith("http") && !seen.containsKey(uri)) {
                seen[uri] = WebCitation(title = title, url = uri)
            }
        }
        return seen.values.take(6)
    }

    private fun extractApiError(body: String, httpCode: Int): String {
        val parsed = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message", "")
        }.getOrNull()
        return if (!parsed.isNullOrBlank()) {
            "HTTP $httpCode: $parsed"
        } else {
            "HTTP $httpCode request failed."
        }
    }
}

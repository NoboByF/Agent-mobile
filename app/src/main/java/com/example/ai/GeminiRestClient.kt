package com.example.ai

import android.util.Base64
import com.example.data.AskAiMessage
import com.example.data.DiscoveredModel
import com.example.data.GeminiApiKeyEntry
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
    val modelUsed: String,
    val keyLabelUsed: String? = null
)

private class GeminiHttpException(
    val httpCode: Int,
    override val message: String
) : Exception(message)

object GeminiRestClient {

    private const val REST_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Executes a Gemini REST call with automatic Multi-API-Key failover.
     * If a key hits rate-limit, daily quota, or is invalid, marks it in ApiKeyPoolManager
     * and seamlessly retries with the next healthy key in the user's pool.
     */
    private inline fun <T> executeWithPoolFailover(
        explicitApiKey: String,
        tag: String,
        block: (keyEntry: GeminiApiKeyEntry?, rawApiKey: String) -> T
    ): T {
        val settings = PilotStateHub.settings.value
        val pool = ApiKeyPoolManager.effectivePool(settings)

        // Direct explicit-key fallback if caller supplied a key not managed in pool
        if (pool.isEmpty()) {
            val trimmed = explicitApiKey.trim()
            require(trimmed.isNotBlank()) { "Add at least one Gemini API Key in Settings first." }
            return block(null, trimmed)
        }

        val triedIds = mutableSetOf<String>()
        val maxAttempts = if (settings.autoRotateApiKeys) {
            pool.count { it.enabled }.coerceIn(1, 10)
        } else {
            1
        }
        var lastError: Throwable? = null

        for (attempt in 1..maxAttempts) {
            val selection = ApiKeyPoolManager.selectBestKey(
                settings = PilotStateHub.settings.value,
                excludeIds = triedIds
            )
            val entry = selection.entry
            if (entry == null) {
                val msg = ApiKeyPoolManager.buildNoKeyAvailableMessage(selection)
                throw lastError ?: IllegalStateException(msg)
            }
            triedIds.add(entry.id)

            try {
                val output = block(entry, entry.key)
                ApiKeyPoolManager.reportSuccess(entry.id)
                return output
            } catch (e: GeminiHttpException) {
                lastError = e
                val classification = ApiKeyPoolManager.classifyFailure(
                    httpCode = e.httpCode,
                    message = e.message
                )
                if (classification.kind == ApiFailureKind.TRANSIENT) {
                    throw e
                }
                val promoted = ApiKeyPoolManager.reportFailureAndRotate(
                    failedKeyId = entry.id,
                    classification = classification
                )
                if (promoted != null && attempt < maxAttempts) {
                    PilotStateHub.log(
                        LogLevel.INFO,
                        tag,
                        "Failover retry #$attempt using ${promoted.label} (${promoted.maskedKey()})"
                    )
                    continue
                }
                val nextSelection = ApiKeyPoolManager.selectBestKey(
                    settings = PilotStateHub.settings.value,
                    excludeIds = triedIds
                )
                if (nextSelection.entry == null) {
                    throw IllegalStateException(
                        "${e.message} • ${ApiKeyPoolManager.buildNoKeyAvailableMessage(nextSelection)}"
                    )
                }
                throw e
            }
        }
        throw lastError ?: IllegalStateException("Gemini API request failed across all configured keys.")
    }

    /**
     * Queries GET /v1beta/models?key={apiKey} using automatic multi-key failover.
     */
    suspend fun fetchAvailableModels(apiKey: String = ""): Result<List<DiscoveredModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                executeWithPoolFailover(apiKey, "Models") { entry, activeKey ->
                    val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                    val request = Request.Builder()
                        .url("$REST_BASE/models?key=$encodedKey")
                        .get()
                        .build()

                    val labelSuffix = entry?.let { " [${it.label}]" }.orEmpty()
                    PilotStateHub.log(LogLevel.INFO, "Models", "Fetching available models from v1beta/models$labelSuffix...")

                    client.newCall(request).execute().use { response ->
                        val bodyStr = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val errMsg = extractApiError(bodyStr, response.code)
                            PilotStateHub.log(LogLevel.ERROR, "Models", "Fetch failed: $errMsg")
                            throw GeminiHttpException(response.code, errMsg)
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
        }

    /**
     * Multi-turn Gemini chat with Google Search Grounding, strict role alternation sanitizer,
     * and automatic Multi-API-Key failover.
     */
    suspend fun generateChatTurn(
        apiKey: String = "",
        modelId: String,
        history: List<AskAiMessage>,
        enableGoogleSearch: Boolean
    ): Result<ChatGenerationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
            val sanitizedTurns = buildSanitizedContents(history.takeLast(20))
            require(sanitizedTurns.length() > 0) { "Prompt cannot be empty." }

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
                .put("contents", sanitizedTurns)

            if (enableGoogleSearch) {
                payload.put(
                    "tools",
                    JSONArray().put(JSONObject().put("google_search", JSONObject()))
                )
            }
            val bodyJson = payload.toString()

            executeWithPoolFailover(apiKey, "AskAI") { entry, activeKey ->
                val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                val url = "$REST_BASE/models/$cleanModel:generateContent?key=$encodedKey"
                val request = Request.Builder()
                    .url(url)
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val labelSuffix = entry?.let { " [${it.label}]" }.orEmpty()
                PilotStateHub.log(
                    LogLevel.INFO,
                    "AskAI",
                    "Calling $cleanModel$labelSuffix (searchGrounding=$enableGoogleSearch)..."
                )

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errMsg = extractApiError(bodyStr, response.code)
                        PilotStateHub.log(LogLevel.ERROR, "AskAI", "Error$labelSuffix: $errMsg")
                        throw GeminiHttpException(response.code, errMsg)
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
                        "Received response$labelSuffix (${finalText.length} chars, ${citations.size} web sources)."
                    )
                    ChatGenerationResult(
                        text = finalText,
                        citations = citations,
                        modelUsed = cleanModel,
                        keyLabelUsed = entry?.label
                    )
                }
            }
        }
    }

    /**
     * Generates a concise, natural auto-reply for incoming messages with automatic Multi-API-Key failover.
     */
    suspend fun generateAutoReply(
        apiKey: String = "",
        modelId: String,
        personaPrompt: String,
        appName: String,
        senderName: String,
        incomingText: String,
        screenContext: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }

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
            val bodyJson = payload.toString()

            executeWithPoolFailover(apiKey, "AutoReply") { _, activeKey ->
                val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                val request = Request.Builder()
                    .url("$REST_BASE/models/$cleanModel:generateContent?key=$encodedKey")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw GeminiHttpException(response.code, extractApiError(bodyStr, response.code))
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
    }

    /**
     * Ensures Gemini REST contents strictly alternate user/model turns and start with a user turn.
     * Prevents HTTP 400 errors after a previous failed turn left consecutive user messages in history.
     */
    internal fun buildSanitizedContents(history: List<AskAiMessage>): JSONArray {
        val merged = mutableListOf<Pair<String, StringBuilder>>()
        for (msg in history) {
            val text = msg.text.trim()
            if (text.isEmpty()) continue
            val role = if (msg.isUser) "user" else "model"
            if (merged.isEmpty() && role != "user") {
                continue
            }
            val last = merged.lastOrNull()
            if (last != null && last.first == role) {
                last.second.append("\n").append(text)
            } else {
                merged.add(role to StringBuilder(text))
            }
        }
        val arr = JSONArray()
        for ((role, sb) in merged) {
            arr.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", sb.toString())))
            )
        }
        return arr
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

    /**
     * Builds an ordered list of non-Live Vision+Reasoning models to try if the primary
     * selected agentModel returns HTTP 404 (not found / unsupported on this API key).
     */
    internal fun resolveAgentFallbackModels(
        primaryModel: String,
        discovered: List<DiscoveredModel> = PilotStateHub.discoveredModels.value
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        val cleanPrimary = primaryModel.trim().removePrefix("models/")
        if (cleanPrimary.isNotBlank()) candidates.add(cleanPrimary)
        candidates.add("gemini-2.5-flash")
        candidates.add("gemini-2.5-pro")
        candidates.add("gemini-2.0-flash")
        discovered
            .filter { it.supportsGenerateContent && !it.id.contains("native-audio", ignoreCase = true) }
            .forEach { candidates.add(it.id) }
        return candidates.toList()
    }

    /**
     * Multimodal Vision + Structured JSON call for AutonomousTaskEngine (screen understanding,
     * multi-step UI planning, visual completion auditing, and vector drawing) with both:
     * 1) Automatic Multi-API-Key failover (429/quota/invalid key)
     * 2) Automatic Non-Live Reasoning Model fallback (if a model ID returns HTTP 404)
     */
    suspend fun generateVisionJson(
        apiKey: String = "",
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        jpegBytes: ByteArray?,
        temperature: Double = 0.2,
        maxOutputTokens: Int = 8192
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val userParts = JSONArray()
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                userParts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", b64)
                    )
                )
            }
            userParts.put(JSONObject().put("text", userPrompt))

            val payload = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemPrompt))
                    )
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", userParts)
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", temperature)
                        .put("maxOutputTokens", maxOutputTokens)
                        .put("responseMimeType", "application/json")
                )

            val bodyJson = payload.toString()
            val modelCandidates = resolveAgentFallbackModels(modelId)
            var lastModelError: Throwable? = null

            for ((idx, candidateModel) in modelCandidates.withIndex()) {
                try {
                    return@runCatching executeWithPoolFailover(apiKey, "AutoPilot") { entry, activeKey ->
                        val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                        val url = "$REST_BASE/models/$candidateModel:generateContent?key=$encodedKey"
                        val request = Request.Builder()
                            .url(url)
                            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                            .build()

                        val labelSuffix = entry?.let { " [${it.label}]" }.orEmpty()
                        PilotStateHub.log(
                            LogLevel.INFO,
                            "AutoPilot",
                            "Action Brain -> $candidateModel$labelSuffix (${(jpegBytes?.size ?: 0) / 1024}KB)"
                        )

                        client.newCall(request).execute().use { response ->
                            val bodyStr = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                val errMsg = extractApiError(bodyStr, response.code)
                                PilotStateHub.log(LogLevel.ERROR, "AutoPilot", "Vision error ($candidateModel)$labelSuffix: $errMsg")
                                throw GeminiHttpException(response.code, errMsg)
                            }

                            val root = JSONObject(bodyStr)
                            val parts = root.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                            val rawText = buildString {
                                if (parts != null) {
                                    for (i in 0 until parts.length()) {
                                        val partObj = parts.optJSONObject(i) ?: continue
                                        // Skip internal reasoning thought parts if returned by Gemini 2.5 thinking models
                                        if (partObj.optBoolean("thought", false)) continue
                                        val t = partObj.optString("text", "").orEmpty()
                                        if (t.isNotBlank()) append(t)
                                    }
                                }
                            }.trim()

                            extractCleanJsonObject(rawText)
                        }
                    }
                } catch (e: GeminiHttpException) {
                    lastModelError = e
                    val isModelUnsupported = e.httpCode == 404 ||
                        (e.httpCode == 400 && e.message.contains("not supported", ignoreCase = true))
                    if (isModelUnsupported && idx < modelCandidates.lastIndex) {
                        PilotStateHub.log(
                            LogLevel.INFO,
                            "AutoPilot",
                            "Model '$candidateModel' unavailable (HTTP ${e.httpCode}); switching Action Brain to '${modelCandidates[idx + 1]}'"
                        )
                        continue
                    }
                    throw e
                }
            }
            throw lastModelError ?: IllegalStateException("No available Action Brain model could handle the request.")
        }
    }

    internal fun extractCleanJsonObject(rawText: String): JSONObject {
        val stripped = rawText
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        runCatching { return JSONObject(stripped) }
        val firstBrace = stripped.indexOf('{')
        val lastBrace = stripped.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return JSONObject(stripped.substring(firstBrace, lastBrace + 1))
        }
        error("Vision planner returned non-JSON response.")
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

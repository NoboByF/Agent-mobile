package com.example.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Executes a Gemini REST call with automatic Multi-API-Key failover.
     * If a key hits rate-limit, daily quota, or is invalid, marks it in ApiKeyPoolManager
     * and seamlessly retries with the next healthy key in the user's pool.
     */
    private inline fun <T> executeWithPoolFailover(
        explicitApiKey: String,
        tag: String,
        penalizeKeyOnQuota: Boolean = true,
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

            var didTransientRetry = false
            while (true) {
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
                        if (!didTransientRetry) {
                            didTransientRetry = true
                            PilotStateHub.log(
                                LogLevel.INFO,
                                tag,
                                "Transient HTTP ${e.httpCode}; brief 550ms retry..."
                            )
                            Thread.sleep(550L)
                            continue
                        }
                        throw e
                    }
                    // If caller still has fallback models to try (e.g., 2.5-pro 429 -> 2.5-flash),
                    // only rotate to another key for this model without locking out the key for fallback models.
                    if (!penalizeKeyOnQuota && classification.kind != ApiFailureKind.INVALID_KEY) {
                        if (attempt < maxAttempts) {
                            PilotStateHub.log(
                                LogLevel.INFO,
                                tag,
                                "Model quota on ${entry.label}; trying another key before model fallback..."
                            )
                            break
                        }
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
                        break
                    }
                    val nextSelection = ApiKeyPoolManager.selectBestKey(
                        settings = PilotStateHub.settings.value,
                        excludeIds = triedIds
                    )
                    if (nextSelection.entry == null && pool.count { it.enabled && it.key.isNotBlank() } > 1) {
                        throw IllegalStateException(
                            "${e.message} • ${ApiKeyPoolManager.buildNoKeyAvailableMessage(nextSelection)}"
                        )
                    }
                    throw e
                }
            }
            continue
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
        enableGoogleSearch: Boolean,
        allowModelFallback: Boolean = true
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

            val modelsToTry = if (allowModelFallback && cleanModel != "gemini-2.5-flash") {
                listOf(cleanModel, "gemini-2.5-flash")
            } else {
                listOf(cleanModel)
            }
            var lastChatError: Throwable? = null
            for ((idx, targetModel) in modelsToTry.withIndex()) {
                val hasNextModel = idx < modelsToTry.lastIndex
                try {
                    return@runCatching executeWithPoolFailover(
                        explicitApiKey = apiKey,
                        tag = "AskAI",
                        penalizeKeyOnQuota = !hasNextModel
                    ) { entry, activeKey ->
                        val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                        val url = "$REST_BASE/models/$targetModel:generateContent?key=$encodedKey"
                        val request = Request.Builder()
                            .url(url)
                            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                            .build()

                        val labelSuffix = entry?.let { " [${it.label}]" }.orEmpty()
                        PilotStateHub.log(
                            LogLevel.INFO,
                            "AskAI",
                            "Calling $targetModel$labelSuffix (searchGrounding=$enableGoogleSearch)..."
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
                                ?: error("No response candidate returned by $targetModel.")

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
                                modelUsed = targetModel,
                                keyLabelUsed = entry?.label
                            )
                        }
                    }
                } catch (e: Throwable) {
                    lastChatError = e
                    val msg = e.message.orEmpty()
                    val canFallback = hasNextModel && (
                        (e is GeminiHttpException && (e.httpCode == 404 || e.httpCode == 429 || e.httpCode in 500..599)) ||
                            msg.contains("429") || msg.contains("404") || msg.contains("503") ||
                            msg.contains("quota", ignoreCase = true) || msg.contains("high demand", ignoreCase = true)
                        )
                    if (canFallback) {
                        PilotStateHub.log(LogLevel.INFO, "AskAI", "Model '$targetModel' unavailable; falling back to '${modelsToTry[idx + 1]}'")
                        continue
                    }
                    throw e
                }
            }
            throw lastChatError ?: IllegalStateException("Chat generation failed.")
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

            val modelsToTry = if (cleanModel != "gemini-2.5-flash") {
                listOf(cleanModel, "gemini-2.5-flash")
            } else {
                listOf(cleanModel)
            }
            var lastAutoError: Throwable? = null
            for ((idx, targetModel) in modelsToTry.withIndex()) {
                val hasNextModel = idx < modelsToTry.lastIndex
                try {
                    return@runCatching executeWithPoolFailover(
                        explicitApiKey = apiKey,
                        tag = "AutoReply",
                        penalizeKeyOnQuota = !hasNextModel
                    ) { _, activeKey ->
                        val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                        val request = Request.Builder()
                            .url("$REST_BASE/models/$targetModel:generateContent?key=$encodedKey")
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
                } catch (e: Throwable) {
                    lastAutoError = e
                    val msg = e.message.orEmpty()
                    val canFallback = hasNextModel && (
                        (e is GeminiHttpException && (e.httpCode == 404 || e.httpCode == 429 || e.httpCode in 500..599)) ||
                            msg.contains("429") || msg.contains("404") || msg.contains("503") ||
                            msg.contains("quota", ignoreCase = true) || msg.contains("high demand", ignoreCase = true)
                        )
                    if (canFallback) {
                        continue
                    }
                    throw e
                }
            }
            throw lastAutoError ?: IllegalStateException("Auto-reply generation failed.")
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
     * multi-step UI planning, visual completion auditing, and vector drawing) with:
     * 1) Thinking-token budget protection (prevents Gemini 2.5 Flash/Pro from burning maxOutputTokens on thoughts)
     * 2) Automatic Multi-API-Key failover (429/quota/invalid key)
     * 3) Automatic Non-Live Reasoning Model fallback (if a model ID returns HTTP 404)
     */
    suspend fun generateVisionJson(
        apiKey: String = "",
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        jpegBytes: ByteArray?,
        temperature: Double = 0.2,
        maxOutputTokens: Int = 8192,
        thinkingBudget: Int? = 512,
        allowModelFallback: Boolean = true
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

            val safeMaxTokens = maxOutputTokens.coerceAtLeast(4096)
            val modelCandidates = if (allowModelFallback) {
                resolveAgentFallbackModels(modelId)
            } else {
                listOf(modelId.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" })
            }
            var lastModelError: Throwable? = null

            fun buildPayloadJson(candidateModel: String, includeThinking: Boolean): String {
                val genConfig = JSONObject()
                    .put("temperature", temperature)
                    .put("maxOutputTokens", safeMaxTokens)
                    .put("responseMimeType", "application/json")

                if (includeThinking && candidateModel.contains("2.5")) {
                    val budget = if (candidateModel.contains("pro", ignoreCase = true)) {
                        (thinkingBudget ?: 1024).coerceAtLeast(256)
                    } else {
                        (thinkingBudget ?: 512).coerceIn(0, 2048)
                    }
                    genConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
                }

                return JSONObject()
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
                    .put("generationConfig", genConfig)
                    .toString()
            }

            for ((idx, candidateModel) in modelCandidates.withIndex()) {
                var allowThinkingConfig = candidateModel.contains("2.5")
                val hasFallbackModel = allowModelFallback && idx < modelCandidates.lastIndex
                while (true) {
                    try {
                        val bodyJson = buildPayloadJson(candidateModel, allowThinkingConfig)
                        return@runCatching executeWithPoolFailover(
                            explicitApiKey = apiKey,
                            tag = "AutoPilot",
                            penalizeKeyOnQuota = !hasFallbackModel
                        ) { entry, activeKey ->
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
                                val firstCand = root.optJSONArray("candidates")?.optJSONObject(0)
                                val finishReason = firstCand?.optString("finishReason", "").orEmpty()
                                val parts = firstCand?.optJSONObject("content")?.optJSONArray("parts")
                                val rawText = buildString {
                                    if (parts != null) {
                                        for (i in 0 until parts.length()) {
                                            val partObj = parts.optJSONObject(i) ?: continue
                                            if (partObj.optBoolean("thought", false)) continue
                                            val t = partObj.optString("text", "").orEmpty()
                                            if (t.isNotBlank()) append(t)
                                        }
                                    }
                                }.trim()

                                if (rawText.isBlank()) {
                                    error("Empty JSON from $candidateModel (finishReason=$finishReason)")
                                }
                                extractCleanJsonObject(rawText)
                            }
                        }
                    } catch (e: GeminiHttpException) {
                        lastModelError = e
                        if (allowThinkingConfig && e.httpCode == 400 &&
                            e.message.contains("thinking", ignoreCase = true)
                        ) {
                            allowThinkingConfig = false
                            continue
                        }
                        val canSwitchModel = allowModelFallback && idx < modelCandidates.lastIndex && (
                            e.httpCode == 404 ||
                                e.httpCode == 429 ||
                                e.httpCode in 500..599 ||
                                (e.httpCode == 400 && e.message.contains("not supported", ignoreCase = true)) ||
                                e.message.contains("high demand", ignoreCase = true) ||
                                e.message.contains("overloaded", ignoreCase = true)
                            )
                        if (canSwitchModel) {
                            PilotStateHub.log(
                                LogLevel.INFO,
                                "AutoPilot",
                                "Model '$candidateModel' hit HTTP ${e.httpCode}; auto-switching to '${modelCandidates[idx + 1]}'"
                            )
                            break
                        }
                        throw e
                    } catch (ioe: IOException) {
                        lastModelError = ioe
                        if (allowModelFallback && idx < modelCandidates.lastIndex) {
                            PilotStateHub.log(
                                LogLevel.INFO,
                                "AutoPilot",
                                "Network/timeout on '$candidateModel' (${ioe.message}); trying '${modelCandidates[idx + 1]}'"
                            )
                            break
                        }
                        throw ioe
                    } catch (other: Throwable) {
                        lastModelError = other
                        val msg = other.message.orEmpty()
                        val shouldFallback = allowModelFallback && idx < modelCandidates.lastIndex && (
                            msg.contains("HTTP 503", ignoreCase = true) ||
                                msg.contains("HTTP 429", ignoreCase = true) ||
                                msg.contains("HTTP 404", ignoreCase = true) ||
                                msg.contains("high demand", ignoreCase = true) ||
                                msg.contains("Empty JSON", ignoreCase = true)
                            )
                        if (shouldFallback) {
                            PilotStateHub.log(
                                LogLevel.INFO,
                                "AutoPilot",
                                "Fallback from '$candidateModel' -> '${modelCandidates[idx + 1]}' (${msg.take(60)})"
                            )
                            break
                        }
                        throw other
                    }
                }
            }
            throw lastModelError ?: IllegalStateException("No available Action Brain model could handle the request.")
        }
    }

    /**
     * Real diagnostic test for the configured Action / Drawing Vision Brain model.
     * Sends a lightweight synthetic canvas image and verifies Vision + JSON + vector stroke generation.
     */
    suspend fun testVisionAgentModel(
        apiKey: String = "",
        modelId: String
    ): Triple<Boolean, String, Long> {
        val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
        val started = System.currentTimeMillis()
        val testJpeg = runCatching {
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(20, 24, 34))
            val whitePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
            c.drawRect(36f, 52f, 220f, 204f, whitePaint)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            bmp.recycle()
            baos.toByteArray()
        }.getOrNull()

        val result = generateVisionJson(
            apiKey = apiKey,
            modelId = cleanModel,
            systemPrompt = "You are verifying Vision + Canvas Drawing JSON capability. Respond strictly in JSON.",
            userPrompt = "Detect the white rectangle box and generate 3 simple curved strokes inside it. Return JSON: {\"canvas_box\": [140,200,860,800], \"strokes\": [{\"label\":\"Arc\",\"points\":[[200,500],[500,300],[800,500]]}]}",
            jpegBytes = testJpeg,
            temperature = 0.1,
            maxOutputTokens = 4096,
            thinkingBudget = if (cleanModel.contains("pro", ignoreCase = true)) 256 else 0,
            allowModelFallback = false
        )
        val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)

        return result.fold(
            onSuccess = { json ->
                val strokesCount = json.optJSONArray("strokes")?.length() ?: 0
                Triple(
                    true,
                    "Ready: $cleanModel responded (${latency}ms, Vision + $strokesCount test strokes OK).",
                    latency
                )
            },
            onFailure = { err ->
                val msg = err.message ?: "Request failed"
                val hint = if (msg.contains("503") || msg.contains("high demand", ignoreCase = true)) {
                    " (Model is busy right now — tasks will auto-fallback to gemini-2.5-flash)"
                } else {
                    ""
                }
                Triple(false, "$cleanModel failed (${latency}ms): $msg$hint", latency)
            }
        )
    }

    /**
     * Real diagnostic test for the configured Text / Ask AI / Search Grounding model.
     */
    suspend fun testTextModel(
        apiKey: String = "",
        modelId: String
    ): Triple<Boolean, String, Long> {
        val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
        val started = System.currentTimeMillis()
        val result = generateChatTurn(
            apiKey = apiKey,
            modelId = cleanModel,
            history = listOf(
                AskAiMessage(
                    id = 1L,
                    isUser = true,
                    text = "Reply in 3 words only: Model is ready.",
                    timestamp = PilotStateHub.formatNow()
                )
            ),
            enableGoogleSearch = false,
            allowModelFallback = false
        )
        val latency = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        return result.fold(
            onSuccess = { gen ->
                Triple(
                    true,
                    "Ready: ${gen.modelUsed} replied \"${gen.text.take(40)}\" (${latency}ms).",
                    latency
                )
            },
            onFailure = { err ->
                Triple(false, "$cleanModel failed (${latency}ms): ${err.message}", latency)
            }
        )
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

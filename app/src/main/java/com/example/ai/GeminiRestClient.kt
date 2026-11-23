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
import com.example.data.PersonalDataItem
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

data class SmartAutoReplyResult(
    val isSensitive: Boolean,
    val sensitivityReason: String? = null,
    val replyText: String,
    val questionForOwner: String = "",
    val messageType: String = "NORMAL" // NORMAL | ABUSIVE | SPAM_NONSENSE | SENSITIVE_OWNER_ONLY
)

data class OwnerVoiceConsultationResult(
    val action: String, // SEND_REPLY | DISCUSS | CANCEL
    val spokenToOwner: String,
    val replyToSender: String = ""
)

data class GeneratedImageResult(
    val imageBytes: ByteArray,
    val mimeType: String,
    val captionText: String,
    val modelUsed: String,
    val keyLabelUsed: String? = null
)

private class GeminiHttpException(
    val httpCode: Int,
    override val message: String
) : Exception(message)

object GeminiRestClient {

    private const val REST_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val UPLOAD_BASE = "https://generativelanguage.googleapis.com/upload/v1beta/files"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
     * Multimodal Photo/Video/Audio/PDF/File attachments (inline + Gemini Files API upload),
     * and automatic Multi-API-Key failover.
     */
    suspend fun generateChatTurn(
        apiKey: String = "",
        modelId: String,
        history: List<AskAiMessage>,
        enableGoogleSearch: Boolean,
        allowModelFallback: Boolean = true,
        preparedAttachments: List<PreparedAttachment> = emptyList()
    ): Result<ChatGenerationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
            val sanitizedTurns = buildSanitizedContents(history.takeLast(20))
            require(sanitizedTurns.length() > 0 || preparedAttachments.isNotEmpty()) { "Prompt cannot be empty." }

            val systemInstruction = JSONObject().put(
                "parts",
                JSONArray().put(
                    JSONObject().put(
                        "text",
                        "You are LivePilot AI's intelligent multimodal bilingual assistant (Bengali & English). Analyze attached photos, videos, audio, PDFs, and files thoroughly and accurately. Give clear, well-structured answers. Respond in Bengali if the user writes in Bengali or Banglish, and English otherwise. When Google Search results are available, synthesize up-to-date facts clearly."
                    )
                )
            )

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
                        // Build per-key contents because Gemini Files API URIs are scoped to the uploading API key/project
                        val turnContents = JSONArray(sanitizedTurns.toString())
                        if (preparedAttachments.isNotEmpty()) {
                            attachPreparedMediaToLatestUserTurn(
                                contents = turnContents,
                                attachments = preparedAttachments,
                                activeApiKey = activeKey
                            )
                        }

                        val payload = JSONObject()
                            .put("system_instruction", systemInstruction)
                            .put("contents", turnContents)

                        if (enableGoogleSearch) {
                            payload.put(
                                "tools",
                                JSONArray().put(JSONObject().put("google_search", JSONObject()))
                            )
                        }
                        val bodyJson = payload.toString()

                        val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                        val url = "$REST_BASE/models/$targetModel:generateContent?key=$encodedKey"
                        val request = Request.Builder()
                            .url(url)
                            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                            .build()

                        val labelSuffix = entry?.let { " [${it.label}]" }.orEmpty()
                        val attNote = if (preparedAttachments.isNotEmpty()) ", ${preparedAttachments.size} attachment(s)" else ""
                        PilotStateHub.log(
                            LogLevel.INFO,
                            "AskAI",
                            "Calling $targetModel$labelSuffix (searchGrounding=$enableGoogleSearch$attNote)..."
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

    private fun attachPreparedMediaToLatestUserTurn(
        contents: JSONArray,
        attachments: List<PreparedAttachment>,
        activeApiKey: String
    ) {
        val lastTurn = if (contents.length() > 0) {
            contents.optJSONObject(contents.length() - 1)
        } else {
            null
        }
        val targetParts = if (lastTurn != null && lastTurn.optString("role") == "user") {
            lastTurn.optJSONArray("parts") ?: JSONArray().also { lastTurn.put("parts", it) }
        } else {
            val newParts = JSONArray()
            contents.put(JSONObject().put("role", "user").put("parts", newParts))
            newParts
        }

        val mediaParts = mutableListOf<JSONObject>()
        for (att in attachments) {
            when {
                !att.extractedText.isNullOrBlank() -> {
                    mediaParts.add(JSONObject().put("text", att.extractedText))
                }
                !att.inlineBase64.isNullOrBlank() && !att.inlineMimeType.isNullOrBlank() -> {
                    mediaParts.add(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", att.inlineMimeType)
                                .put("data", att.inlineBase64)
                        )
                    )
                }
                att.rawBytesForUpload != null && !att.inlineMimeType.isNullOrBlank() -> {
                    val fileUri = uploadFileToGeminiFilesApi(
                        activeApiKey = activeApiKey,
                        bytes = att.rawBytesForUpload,
                        mimeType = att.inlineMimeType,
                        displayName = att.meta.displayName
                    )
                    mediaParts.add(
                        JSONObject().put(
                            "file_data",
                            JSONObject()
                                .put("mime_type", att.inlineMimeType)
                                .put("file_uri", fileUri)
                        )
                    )
                }
            }
        }

        // Prepend media parts before the user's text prompt for best Gemini multimodal accuracy
        if (mediaParts.isNotEmpty()) {
            val combined = JSONArray()
            mediaParts.forEach { combined.put(it) }
            for (i in 0 until targetParts.length()) {
                combined.put(targetParts.get(i))
            }
            val turnObj = contents.getJSONObject(contents.length() - 1)
            turnObj.put("parts", combined)
        }
    }

    /**
     * Uploads larger video/audio/PDF files (10MB..45MB) using Gemini Files API resumable upload
     * and waits briefly for video processing to become ACTIVE.
     */
    private fun uploadFileToGeminiFilesApi(
        activeApiKey: String,
        bytes: ByteArray,
        mimeType: String,
        displayName: String
    ): String {
        val encodedKey = URLEncoder.encode(activeApiKey.trim(), "UTF-8")
        PilotStateHub.log(
            LogLevel.INFO,
            "Attachments",
            "Uploading $displayName (${bytes.size / 1024}KB, $mimeType) to Gemini Files API..."
        )

        // Step 1: Initiate resumable upload
        val initMeta = JSONObject().put("file", JSONObject().put("display_name", displayName.take(80)))
        val initReq = Request.Builder()
            .url("$UPLOAD_BASE?key=$encodedKey")
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(initMeta.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val uploadUrl = client.newCall(initReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GeminiHttpException(resp.code, extractApiError(bodyStr, resp.code))
            }
            resp.header("X-Goog-Upload-URL")
                ?: error("Gemini Files API did not return upload URL.")
        }

        // Step 2: Upload raw bytes and finalize
        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(bytes.toRequestBody(mimeType.toMediaType()))
            .build()

        val fileObj = client.newCall(uploadReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GeminiHttpException(resp.code, extractApiError(bodyStr, resp.code))
            }
            val root = JSONObject(bodyStr)
            root.optJSONObject("file") ?: root
        }

        val fileName = fileObj.optString("name", "")
        var fileUri = fileObj.optString("uri", "")
        var state = fileObj.optString("state", "ACTIVE")

        // Step 3: Poll until video/media processing finishes (up to ~24s)
        var attempts = 0
        while (state.equals("PROCESSING", ignoreCase = true) && fileName.isNotBlank() && attempts < 12) {
            attempts++
            Thread.sleep(2000L)
            val statusReq = Request.Builder()
                .url("$REST_BASE/$fileName?key=$encodedKey")
                .get()
                .build()
            client.newCall(statusReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val statusJson = JSONObject(resp.body?.string().orEmpty())
                    state = statusJson.optString("state", "ACTIVE")
                    statusJson.optString("uri", "").takeIf { it.isNotBlank() }?.let { fileUri = it }
                }
            }
        }

        if (state.equals("FAILED", ignoreCase = true)) {
            error("Gemini could not process uploaded file '$displayName'.")
        }
        require(fileUri.isNotBlank()) { "Gemini Files API returned empty file URI." }
        PilotStateHub.log(LogLevel.INFO, "Attachments", "Upload ready ($state): $displayName")
        return fileUri
    }

    /**
     * Smart Context-Aware Auto-Reply + Sensitive Question Classifier.
     * Detects private/financial/sensitive/unknown-personal questions and defers them safely
     * with "আমি জেনে বলছি।" while formulating a clear question for the phone owner.
     */
    suspend fun generateSmartAutoReply(
        apiKey: String = "",
        modelId: String,
        personaPrompt: String,
        appName: String,
        senderName: String,
        incomingText: String,
        conversationHistory: List<String> = emptyList(),
        screenContext: String? = null,
        sensitiveGuardEnabled: Boolean = true,
        personalDataFacts: List<PersonalDataItem> = PilotStateHub.personalData.value,
        ownerDisplayName: String = ""
    ): Result<SmartAutoReplyResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
            val defaultDeferral = com.example.messaging.SensitiveMessageGuard.defaultDeferralReply(
                incomingText = incomingText,
                ownerName = ownerDisplayName
            )
            val shareableFacts = personalDataFacts.filter {
                it.shareInAutoReply && it.title.isNotBlank() && it.content.isNotBlank()
            }

            val promptText = buildString {
                append("Messaging App: ").append(appName).append('\n')
                append("Sender: ").append(senderName).append('\n')
                if (ownerDisplayName.isNotBlank()) {
                    append("Account Owner Name: ").append(ownerDisplayName).append('\n')
                }
                if (conversationHistory.isNotEmpty()) {
                    append("Recent Conversation History (oldest to newest):\n")
                    conversationHistory.takeLast(6).forEach { append("- ").append(it).append('\n') }
                }
                append("LATEST Incoming Message from ").append(senderName).append(": \"").append(incomingText).append("\"\n")
                if (!screenContext.isNullOrBlank()) {
                    append("Visible Chat Screen Context: ").append(screenContext.take(700)).append('\n')
                }
                append("Output strict JSON only.")
            }

            val systemPrompt = buildString {
                append(personaPrompt.trim()).append("\n\n")
                if (shareableFacts.isNotEmpty()) {
                    append("OWNER'S SAVED PERSONAL DATA (\"আমার ডাটা\" — TRUSTED FACTS YOU ARE ALLOWED TO SHARE):\n")
                    shareableFacts.take(30).forEach { fact ->
                        append("- [").append(fact.category).append("] ")
                            .append(fact.title).append(": ")
                            .append(fact.content).append('\n')
                    }
                    append("\n")
                }
                append(
                    """
                    STRICT AUTO-REPLY & MODERATION RULES:
                    1. First classify the sender's LATEST message into ONE exact "message_type":
                       - "ABUSIVE": Profanity, slurs, insults, verbal abuse, rudeness, or hostile scolding ("বকাবাজি / গালাগালি").
                       - "SPAM_NONSENSE": Meaningless gibberish, random letters/emojis, trolling, "আবুল তাবুল" nonsense, or intentional spam.
                       - "SENSITIVE_OWNER_ONLY": ONLY when genuinely IMPOSSIBLE for an AI to answer or fulfill on its own — specifically: private passwords/PINs/OTPs/NID, sending real money/loans/bKash/bank transfers, private live location/address NOT in Saved Personal Data, urgent real-world emergencies, or binding real-life personal commitments/decisions that ONLY the human account owner can make.
                       - "NORMAL": EVERYTHING ELSE! Casual chat, greetings, questions, advice, jokes, writing a poem ("কবিতা"), writing a story/song/essay/caption/code, translations, math, general knowledge, answering facts from OWNER'S SAVED PERSONAL DATA, or creative requests (like describing/designing an image or giving a creative response when asked to create a picture).

                    2. CRITICAL — DO NOT OVER-ESCALATE!
                       - NEVER mark normal chat, poems ("কবিতা"), image creation requests ("ছবি তৈরি"), jokes, study help, general questions, or anything answered by OWNER'S SAVED PERSONAL DATA as "SENSITIVE_OWNER_ONLY"!
                       - If the sender asks for a poem, story, advice, explanation, or creative content, FULFILL IT YOURSELF beautifully and completely in "reply" (you may write multiple lines for a poem or creative request) with "message_type": "NORMAL"!
                       - If the sender asks to generate/draw an image in text chat, handle it yourself politely in "reply" (e.g. share creative text/emoji artwork or ask what details they want) with "message_type": "NORMAL" — NEVER disturb the owner for it!
                       - For ordinary short chat messages, keep "reply" concise (1-2 natural human-like sentences matching Bengali, Banglish, or English) and NEVER repeat greetings already in Recent Conversation History.
                    """.trimIndent()
                )
                if (sensitiveGuardEnabled) {
                    append(
                        """

                        3. WHEN "message_type" == "SENSITIVE_OWNER_ONLY":
                           - Set "is_sensitive": true
                           - DO NOT guess or fabricate the owner's private answer!
                           - Set "reply" to EXACTLY: "$defaultDeferral"
                           - Set "sensitivity_reason" to a short 2-4 word Bengali label (e.g. "আর্থিক অনুরোধ", "ব্যক্তিগত তথ্য", "জরুরি সিদ্ধান্ত").
                           - Set "question_for_owner" to a concise Bengali question asking the owner what answer to send to $senderName.
                        """.trimIndent()
                    )
                }
                append(
                    """

                    Return ONLY valid JSON:
                    {
                      "message_type": "NORMAL",
                      "is_sensitive": false,
                      "sensitivity_reason": "",
                      "reply": "exact message text to send back",
                      "question_for_owner": ""
                    }
                    """.trimIndent()
                )
            }

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
                            .put("parts", JSONArray().put(JSONObject().put("text", promptText)))
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.35)
                        .put("responseMimeType", "application/json")
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
                            val rawText = buildString {
                                if (parts != null) {
                                    for (i in 0 until parts.length()) {
                                        val p = parts.optJSONObject(i) ?: continue
                                        if (p.optBoolean("thought", false)) continue
                                        append(p.optString("text", ""))
                                    }
                                }
                            }.trim()

                            val parsedJson = runCatching { extractCleanJsonObject(rawText) }.getOrNull()
                            if (parsedJson != null) {
                                val rawType = parsedJson.optString("message_type", "NORMAL").trim().uppercase()
                                val normalizedType = when (rawType) {
                                    "ABUSIVE", "SPAM_NONSENSE", "SENSITIVE_OWNER_ONLY", "NORMAL" -> rawType
                                    else -> if (parsedJson.optBoolean("is_sensitive", false)) "SENSITIVE_OWNER_ONLY" else "NORMAL"
                                }
                                val isCreative = com.example.messaging.SensitiveMessageGuard.isCreativeOrAiSolvableRequest(incomingText)
                                val isSensitive = sensitiveGuardEnabled &&
                                    !isCreative &&
                                    (normalizedType == "SENSITIVE_OWNER_ONLY" || parsedJson.optBoolean("is_sensitive", false)) &&
                                    normalizedType != "ABUSIVE" &&
                                    normalizedType != "SPAM_NONSENSE"
                                val reason = parsedJson.optString("sensitivity_reason", "").trim().ifBlank { null }
                                val reply = when {
                                    normalizedType == "ABUSIVE" || normalizedType == "SPAM_NONSENSE" -> ""
                                    isSensitive -> defaultDeferral
                                    else -> parsedJson.optString("reply", "").trim().removeSurrounding("\"")
                                }
                                if (normalizedType == "NORMAL" && !isSensitive) {
                                    require(reply.isNotBlank()) { "Empty auto-reply returned." }
                                }
                                val ownerQ = parsedJson.optString("question_for_owner", "").trim()
                                    .ifBlank { "$senderName জিজ্ঞেস করেছে: \"${incomingText.take(120)}\" — কী উত্তর পাঠাবো?" }
                                SmartAutoReplyResult(
                                    isSensitive = isSensitive,
                                    sensitivityReason = reason,
                                    replyText = reply,
                                    questionForOwner = if (isSensitive) ownerQ else "",
                                    messageType = if (isSensitive) "SENSITIVE_OWNER_ONLY" else normalizedType
                                )
                            } else {
                                val plain = rawText.removeSurrounding("\"").trim()
                                require(plain.isNotBlank()) { "Empty auto-reply returned." }
                                SmartAutoReplyResult(
                                    isSensitive = false,
                                    replyText = plain,
                                    messageType = "NORMAL"
                                )
                            }
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
     * Generates a concise, natural auto-reply for incoming messages (compat wrapper).
     */
    suspend fun generateAutoReply(
        apiKey: String = "",
        modelId: String,
        personaPrompt: String,
        appName: String,
        senderName: String,
        incomingText: String,
        screenContext: String? = null
    ): Result<String> = generateSmartAutoReply(
        apiKey = apiKey,
        modelId = modelId,
        personaPrompt = personaPrompt,
        appName = appName,
        senderName = senderName,
        incomingText = incomingText,
        screenContext = screenContext,
        sensitiveGuardEnabled = PilotStateHub.settings.value.sensitiveQuestionGuardEnabled
    ).map { it.replyText }

    /**
     * Live conversational voice consultation between the phone owner and AI when an important/sensitive
     * incoming message arrives. The owner can ask what the sender wrote previously, ask AI's opinion
     * on what reply would be best, refine the wording, cancel, or approve sending the reply!
     */
    suspend fun consultOwnerOnIncomingMessage(
        apiKey: String = "",
        modelId: String,
        senderName: String,
        appName: String,
        incomingMessage: String,
        recentSenderHistory: List<String>,
        ownerPersonalData: List<PersonalDataItem>,
        consultationTranscript: List<String>,
        ownerLatestUtterance: String,
        ownerDisplayName: String = "",
        decisionHint: String? = null
    ): Result<OwnerVoiceConsultationResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
            val shareableFacts = ownerPersonalData.filter { it.title.isNotBlank() && it.content.isNotBlank() }

            val systemPrompt = buildString {
                append(
                    """
                    You are LivePilot AI having a natural, live hands-free voice conversation with the phone owner about an important incoming message from $senderName on $appName.
                    Speak concisely (1-3 clear spoken sentences, no markdown/bullets/emojis) in natural Bangladeshi Bengali (or English if the owner speaks English).

                    You MUST decide ONE exact "action" and return strict JSON only:
                    1. "DISCUSS": Use when the owner is asking you a question or consulting with you BEFORE sending anything — for example:
                       - Asking what $senderName messaged earlier ("আগে ও কী মেসেজ করেছিল?", "আগে কী বলেছে?") -> Summarize the Recent Chat History aloud naturally and ask what they'd like to reply.
                       - Asking for your advice/suggestion ("তোমার মতে কী রিপ্লাই দিলে ভালো হবে?", "কী বলবো বলো তো?") -> Suggest a smart, polite reply aloud and ask if you should send it.
                       - Asking any follow-up question, checking personal data, or thinking out loud.
                       Set "reply_to_sender" to "" (empty) for DISCUSS!
                    2. "SEND_REPLY": Use ONLY when the owner clearly tells you what message to send ("ওকে বলো আমি এখন ব্যস্ত", "পাঠিয়ে দাও আমি আধা ঘণ্টা পর কল দেবো") OR explicitly approves your previously suggested reply ("হ্যাঁ এটাই পাঠাও", "ঠিক আছে পাঠিয়ে দাও", "পাঠাও", "yes send it").
                       Put the exact clean, polished message text to send to $senderName in "reply_to_sender" (NO meta-words like "ওকে বলো"), and put a brief warm spoken confirmation in "spoken_to_owner".
                    3. "CANCEL": Use when the owner says not to send anything, cancel, ignore, or stop ("বাদ দাও", "পাঠাতে হবে না", "থাক", "বাতিল", "cancel", "ignore").
                       Set "reply_to_sender" to "" and put a brief confirmation in "spoken_to_owner".

                    Return ONLY valid JSON:
                    {
                      "action": "DISCUSS" | "SEND_REPLY" | "CANCEL",
                      "spoken_to_owner": "concise natural spoken response to the owner",
                      "reply_to_sender": "exact message to send to $senderName when action is SEND_REPLY, else empty string"
                    }
                    """.trimIndent()
                )
                if (shareableFacts.isNotEmpty()) {
                    append("\n\nOWNER'S SAVED PERSONAL DATA (\"আমার ডাটা\"):\n")
                    shareableFacts.take(20).forEach { fact ->
                        append("- [").append(fact.category).append("] ")
                            .append(fact.title).append(": ")
                            .append(fact.content).append('\n')
                    }
                }
            }

            val userPrompt = buildString {
                if (ownerDisplayName.isNotBlank()) {
                    append("Owner Name: ").append(ownerDisplayName).append('\n')
                }
                append("Messaging App: ").append(appName).append('\n')
                append("Sender: ").append(senderName).append('\n')
                append("Latest Important Incoming Message: \"").append(incomingMessage).append("\"\n\n")

                append("Recent Chat History with $senderName (oldest to newest):\n")
                if (recentSenderHistory.isEmpty()) {
                    append("- (No earlier messages recorded before this one)\n")
                } else {
                    recentSenderHistory.takeLast(10).forEach { line ->
                        append("- ").append(line).append('\n')
                    }
                }

                if (consultationTranscript.isNotEmpty()) {
                    append("\nLive Voice Consultation So Far:\n")
                    consultationTranscript.takeLast(10).forEach { turn ->
                        append("- ").append(turn).append('\n')
                    }
                }

                append("\nOwner just spoke: \"").append(ownerLatestUtterance).append("\"\n")
                if (!decisionHint.isNullOrBlank()) {
                    append("Calibrated Decision Engine Hint: ").append(decisionHint).append('\n')
                }
                append("Return strict JSON only.")
            }

            val payload = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", 0.35)
                        .put("responseMimeType", "application/json")
                )
            val bodyJson = payload.toString()

            val modelsToTry = if (cleanModel != "gemini-2.5-flash") {
                listOf(cleanModel, "gemini-2.5-flash")
            } else {
                listOf(cleanModel)
            }

            var lastErr: Throwable? = null
            for ((idx, targetModel) in modelsToTry.withIndex()) {
                val hasNext = idx < modelsToTry.lastIndex
                try {
                    return@runCatching executeWithPoolFailover(
                        explicitApiKey = apiKey,
                        tag = "VoiceConsult",
                        penalizeKeyOnQuota = !hasNext
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
                            val rawText = buildString {
                                if (parts != null) {
                                    for (i in 0 until parts.length()) {
                                        val p = parts.optJSONObject(i) ?: continue
                                        if (p.optBoolean("thought", false)) continue
                                        append(p.optString("text", ""))
                                    }
                                }
                            }.trim()
                            parseOwnerConsultationJson(rawText, ownerLatestUtterance)
                        }
                    }
                } catch (e: Throwable) {
                    lastErr = e
                    val msg = e.message.orEmpty()
                    val canFallback = hasNext && (
                        (e is GeminiHttpException && (e.httpCode == 404 || e.httpCode == 429 || e.httpCode in 500..599)) ||
                            msg.contains("429") || msg.contains("404") || msg.contains("503")
                        )
                    if (canFallback) continue
                    throw e
                }
            }
            throw lastErr ?: IllegalStateException("Owner voice consultation failed.")
        }
    }

    internal fun parseOwnerConsultationJson(
        rawText: String,
        fallbackUtterance: String
    ): OwnerVoiceConsultationResult {
        val json = runCatching { extractCleanJsonObject(rawText) }.getOrNull()
        if (json != null) {
            val rawAction = json.optString("action", "DISCUSS").trim().uppercase()
            val normalizedAction = when (rawAction) {
                "SEND_REPLY", "SEND", "REPLY" -> "SEND_REPLY"
                "CANCEL", "STOP", "IGNORE" -> "CANCEL"
                else -> "DISCUSS"
            }
            val spoken = json.optString("spoken_to_owner", "").trim()
            val reply = json.optString("reply_to_sender", "").trim().removeSurrounding("\"")
            return if (normalizedAction == "SEND_REPLY" && reply.isBlank()) {
                OwnerVoiceConsultationResult(
                    action = "DISCUSS",
                    spokenToOwner = spoken.ifBlank { "ঠিক কী লিখে পাঠাবো একটু পরিষ্কার করে বলুন?" },
                    replyToSender = ""
                )
            } else {
                OwnerVoiceConsultationResult(
                    action = normalizedAction,
                    spokenToOwner = spoken.ifBlank {
                        when (normalizedAction) {
                            "SEND_REPLY" -> "ঠিক আছে, উত্তরটি পাঠিয়ে দিচ্ছি।"
                            "CANCEL" -> "ঠিক আছে, কোনো উত্তর পাঠানো হলো না।"
                            else -> "বলুন, কী উত্তর পাঠাতে চান?"
                        }
                    },
                    replyToSender = if (normalizedAction == "SEND_REPLY") reply else ""
                )
            }
        }
        return OwnerVoiceConsultationResult(
            action = "DISCUSS",
            spokenToOwner = rawText.trim().ifBlank { "দুঃখিত, আরেকবার বলুন। আপনি কি \"$fallbackUtterance\" পাঠাতে চান?" },
            replyToSender = ""
        )
    }

    /**
     * Generates a real AI image from a Bengali or English prompt using Gemini native image models
     * (with multi-API-key failover + automatic fallback across image-capable models and Imagen).
     */
    suspend fun generateImage(
        apiKey: String = "",
        prompt: String
    ): Result<GeneratedImageResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPrompt = prompt.trim()
            require(cleanPrompt.isNotBlank()) { "ছবি তৈরির জন্য একটি প্রম্পট লিখুন।" }

            val discovered = PilotStateHub.discoveredModels.value
            val geminiImageModels = LinkedHashSet<String>().apply {
                discovered
                    .filter {
                        it.supportsGenerateContent &&
                            it.id.contains("image", ignoreCase = true) &&
                            !it.id.contains("imagen", ignoreCase = true)
                    }
                    .forEach { add(it.id) }
                add("gemini-2.5-flash-image")
                add("gemini-2.0-flash-exp-image-generation")
                add("gemini-2.0-flash-preview-image-generation")
            }.toList()

            val enhancedPrompt = "Generate a vivid, high-quality, detailed image for this request: $cleanPrompt"
            val genContentPayload = JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", enhancedPrompt)))
                    )
                )
                .put(
                    "generationConfig",
                    JSONObject().put(
                        "responseModalities",
                        JSONArray().put("TEXT").put("IMAGE")
                    )
                )
                .toString()

            var lastError: Throwable? = null

            // Phase 1: Try Gemini native multimodal image generation models
            for ((idx, candidateModel) in geminiImageModels.withIndex()) {
                val hasMoreCandidates = idx < geminiImageModels.lastIndex || true
                try {
                    return@runCatching executeWithPoolFailover(
                        explicitApiKey = apiKey,
                        tag = "ImageGen",
                        penalizeKeyOnQuota = !hasMoreCandidates
                    ) { entry, activeKey ->
                        val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                        val url = "$REST_BASE/models/$candidateModel:generateContent?key=$encodedKey"
                        val request = Request.Builder()
                            .url(url)
                            .post(genContentPayload.toRequestBody(JSON_MEDIA_TYPE))
                            .build()

                        val labelSuffix = entry?.let { " [${it.label}]" }.orEmpty()
                        PilotStateHub.log(
                            LogLevel.INFO,
                            "ImageGen",
                            "Generating image via $candidateModel$labelSuffix: \"${cleanPrompt.take(60)}\""
                        )

                        client.newCall(request).execute().use { response ->
                            val bodyStr = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                val errMsg = extractApiError(bodyStr, response.code)
                                PilotStateHub.log(LogLevel.ERROR, "ImageGen", "Error ($candidateModel)$labelSuffix: $errMsg")
                                throw GeminiHttpException(response.code, errMsg)
                            }
                            parseGeneratedImageFromGenerateContent(
                                bodyStr = bodyStr,
                                modelUsed = candidateModel,
                                keyLabel = entry?.label
                            ) ?: error("No image data returned by $candidateModel.")
                        }
                    }
                } catch (e: Throwable) {
                    lastError = e
                    PilotStateHub.log(
                        LogLevel.INFO,
                        "ImageGen",
                        "Model $candidateModel unavailable (${e.message?.take(70)}); trying next image model..."
                    )
                }
            }

            // Phase 2: Fallback to Imagen predict endpoint if available on the user's API key
            val imagenModels = listOf("imagen-3.0-generate-002", "imagen-3.0-fast-generate-001")
            val imagenPayload = JSONObject()
                .put("instances", JSONArray().put(JSONObject().put("prompt", cleanPrompt)))
                .put("parameters", JSONObject().put("sampleCount", 1))
                .toString()

            for ((idx, imagenModel) in imagenModels.withIndex()) {
                val isLast = idx == imagenModels.lastIndex
                try {
                    return@runCatching executeWithPoolFailover(
                        explicitApiKey = apiKey,
                        tag = "ImageGen",
                        penalizeKeyOnQuota = isLast
                    ) { entry, activeKey ->
                        val encodedKey = URLEncoder.encode(activeKey.trim(), "UTF-8")
                        val url = "$REST_BASE/models/$imagenModel:predict?key=$encodedKey"
                        val request = Request.Builder()
                            .url(url)
                            .post(imagenPayload.toRequestBody(JSON_MEDIA_TYPE))
                            .build()

                        PilotStateHub.log(LogLevel.INFO, "ImageGen", "Trying fallback Imagen model $imagenModel...")
                        client.newCall(request).execute().use { response ->
                            val bodyStr = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                throw GeminiHttpException(response.code, extractApiError(bodyStr, response.code))
                            }
                            parseGeneratedImageFromImagen(
                                bodyStr = bodyStr,
                                modelUsed = imagenModel,
                                keyLabel = entry?.label
                            ) ?: error("No image returned by $imagenModel.")
                        }
                    }
                } catch (e: Throwable) {
                    lastError = e
                }
            }

            throw lastError ?: IllegalStateException(
                "ছবি তৈরি করা যায়নি। আপনার Gemini API Key-তে Image Generation মডেল অ্যাক্সেস বা কোটা আছে কিনা চেক করুন।"
            )
        }
    }

    internal fun parseGeneratedImageFromGenerateContent(
        bodyStr: String,
        modelUsed: String,
        keyLabel: String? = null
    ): GeneratedImageResult? {
        val root = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return null
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null

        var imageBytes: ByteArray? = null
        var mimeType = "image/png"
        val captionBuilder = StringBuilder()

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
            if (inline != null && imageBytes == null) {
                val dataB64 = inline.optString("data", "").trim()
                val mime = inline.optString("mimeType", inline.optString("mime_type", "image/png"))
                if (dataB64.isNotBlank() && mime.startsWith("image/", ignoreCase = true)) {
                    imageBytes = runCatching { Base64.decode(dataB64, Base64.DEFAULT) }.getOrNull()
                    mimeType = mime
                }
            }
            val text = part.optString("text", "").trim()
            if (text.isNotBlank() && !part.optBoolean("thought", false)) {
                if (captionBuilder.isNotEmpty()) captionBuilder.append("\n")
                captionBuilder.append(text)
            }
        }

        val bytes = imageBytes ?: return null
        if (bytes.isEmpty()) return null
        return GeneratedImageResult(
            imageBytes = bytes,
            mimeType = mimeType,
            captionText = captionBuilder.toString().trim(),
            modelUsed = modelUsed,
            keyLabelUsed = keyLabel
        )
    }

    internal fun parseGeneratedImageFromImagen(
        bodyStr: String,
        modelUsed: String,
        keyLabel: String? = null
    ): GeneratedImageResult? {
        val root = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return null
        val firstPred = root.optJSONArray("predictions")?.optJSONObject(0) ?: return null
        val b64 = firstPred.optString("bytesBase64Encoded", "").trim()
        if (b64.isBlank()) return null
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val mime = firstPred.optString("mimeType", "image/png").ifBlank { "image/png" }
        return GeneratedImageResult(
            imageBytes = bytes,
            mimeType = mime,
            captionText = "",
            modelUsed = modelUsed,
            keyLabelUsed = keyLabel
        )
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

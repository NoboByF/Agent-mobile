package com.example.ai

import com.example.data.GeminiApiKeyEntry
import com.example.data.LogLevel
import com.example.data.PersonalDataItem
import com.example.data.PilotSettings
import com.example.data.PilotStateHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed class DecisionQuestion {
    data class Noul(
        val instructions: String
    ) : DecisionQuestion()

    data class Choice(
        val instructions: String,
        val criteria: Map<String, String>
    ) : DecisionQuestion()

    data class Score(
        val instructions: String,
        val criteria: List<String>
    ) : DecisionQuestion()
}

data class NoulAnswer(
    val probability: Double,
    val confidence: Double
) {
    val isYes: Boolean get() = probability >= 0.5
}

data class ChoiceAnswer(
    val choice: String,
    val probabilities: Map<String, Double>,
    val confidence: Double
)

data class ScoreAnswer(
    val score: Double,
    val probabilities: Map<String, Double>,
    val confidence: Double
)

data class DecisionBatchResult(
    val noulAnswers: Map<String, NoulAnswer>,
    val choiceAnswers: Map<String, ChoiceAnswer>,
    val scoreAnswers: Map<String, ScoreAnswer>,
    val modelUsed: String,
    val latencyMs: Long,
    val keyLabelUsed: String? = null
)

data class IncomingMessageDecision(
    val category: String, // NORMAL | ABUSIVE | SPAM_NONSENSE | SENSITIVE_OWNER_ONLY | IMAGE_REQUEST
    val categoryConfidence: Double,
    val needsOwnerProb: Double,
    val answerableByPersonalDataProb: Double,
    val urgencyScore: Double,
    val latencyMs: Long,
    val keyLabelUsed: String? = null
)

data class OwnerVoiceIntentDecision(
    val action: String, // DISCUSS | SEND_REPLY | CANCEL
    val confidence: Double,
    val explicitSendApprovalProb: Double,
    val latencyMs: Long
)

data class TaskVerificationDecision(
    val goalCompletedProb: Double,
    val goalCompletedConfidence: Double,
    val hitBlockerProb: Double,
    val latencyMs: Long
)

data class DestructiveActionDecision(
    val isDestructiveProb: Double,
    val confidence: Double,
    val latencyMs: Long
)

private class DecisionHttpException(
    val httpCode: Int,
    override val message: String
) : Exception(message)

/**
 * Dedicated client for Google's calibrated Decision model (`gemini-decision-1`)
 * via POST https://generativelanguage.googleapis.com/v1beta/decisions.
 *
 * Returns fast, calibrated yes/no (`noul`), multi-class (`choice`), and rubric (`score`)
 * probabilities with zero output tokens, using the user's Multi-API Key failover pool.
 */
object GeminiDecisionClient {

    private const val DECISIONS_URL = "https://generativelanguage.googleapis.com/v1beta/decisions"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    internal fun buildDecisionsRequestJson(
        modelId: String,
        input: String,
        questions: Map<String, DecisionQuestion>
    ): JSONObject {
        val cleanModel = modelId.trim().removePrefix("models/").ifBlank { PilotSettings.DEFAULT_DECISION_MODEL }
        val questionsObj = JSONObject()
        questions.forEach { (key, question) ->
            val qObj = JSONObject()
            when (question) {
                is DecisionQuestion.Noul -> {
                    qObj.put("type", "noul")
                    qObj.put("instructions", question.instructions)
                }
                is DecisionQuestion.Choice -> {
                    qObj.put("type", "choice")
                    qObj.put("instructions", question.instructions)
                    val criteriaObj = JSONObject()
                    question.criteria.forEach { (choiceKey, desc) ->
                        criteriaObj.put(choiceKey, desc)
                    }
                    qObj.put("criteria", criteriaObj)
                }
                is DecisionQuestion.Score -> {
                    qObj.put("type", "score")
                    qObj.put("instructions", question.instructions)
                    val criteriaArr = JSONArray()
                    question.criteria.forEach { criteriaArr.put(it) }
                    qObj.put("criteria", criteriaArr)
                }
            }
            questionsObj.put(key, qObj)
        }
        return JSONObject()
            .put("model", cleanModel)
            .put("input", input)
            .put("questions", questionsObj)
    }

    internal fun parseDecisionsResponseJson(
        bodyStr: String,
        modelUsed: String,
        latencyMs: Long = 0L,
        keyLabelUsed: String? = null
    ): DecisionBatchResult {
        val root = JSONObject(bodyStr)
        val answersObj = root.optJSONObject("answers")
            ?: throw IllegalStateException("Decisions API returned no 'answers' object.")

        val noulMap = mutableMapOf<String, NoulAnswer>()
        val choiceMap = mutableMapOf<String, ChoiceAnswer>()
        val scoreMap = mutableMapOf<String, ScoreAnswer>()

        val keys = answersObj.keys()
        while (keys.hasNext()) {
            val qKey = keys.next()
            val item = answersObj.optJSONObject(qKey) ?: continue
            val type = item.optString("type", "").trim().lowercase(Locale.US)
            val confidence = item.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
            when {
                type == "noul" || (type.isBlank() && item.has("noul")) -> {
                    val prob = item.optDouble("noul", item.optDouble("probability", 0.0)).coerceIn(0.0, 1.0)
                    noulMap[qKey] = NoulAnswer(probability = prob, confidence = confidence)
                }
                type == "choice" || (type.isBlank() && item.has("choice")) -> {
                    val choiceVal = item.optString("choice", "").trim()
                    val probsObj = item.optJSONObject("probabilities")
                    val probs = mutableMapOf<String, Double>()
                    if (probsObj != null) {
                        val pKeys = probsObj.keys()
                        while (pKeys.hasNext()) {
                            val pk = pKeys.next()
                            probs[pk] = probsObj.optDouble(pk, 0.0).coerceIn(0.0, 1.0)
                        }
                    }
                    choiceMap[qKey] = ChoiceAnswer(
                        choice = choiceVal,
                        probabilities = probs,
                        confidence = confidence
                    )
                }
                type == "score" || (type.isBlank() && item.has("score")) -> {
                    val scoreVal = item.optDouble("score", 0.0)
                    val probsObj = item.optJSONObject("probabilities")
                    val probs = mutableMapOf<String, Double>()
                    if (probsObj != null) {
                        val pKeys = probsObj.keys()
                        while (pKeys.hasNext()) {
                            val pk = pKeys.next()
                            probs[pk] = probsObj.optDouble(pk, 0.0).coerceIn(0.0, 1.0)
                        }
                    }
                    scoreMap[qKey] = ScoreAnswer(
                        score = scoreVal,
                        probabilities = probs,
                        confidence = confidence
                    )
                }
            }
        }

        return DecisionBatchResult(
            noulAnswers = noulMap,
            choiceAnswers = choiceMap,
            scoreAnswers = scoreMap,
            modelUsed = modelUsed,
            latencyMs = latencyMs,
            keyLabelUsed = keyLabelUsed
        )
    }

    suspend fun evaluateDecisions(
        apiKey: String = "",
        modelId: String = PilotStateHub.settings.value.decisionModel,
        input: String,
        questions: Map<String, DecisionQuestion>,
        tag: String = "Decision1",
        penalizeKeyOnQuota: Boolean = false
    ): Result<DecisionBatchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(input.isNotBlank()) { "Decision input cannot be empty." }
            require(questions.isNotEmpty()) { "At least one decision question is required." }

            val cleanModel = modelId.trim().removePrefix("models/").ifBlank { PilotSettings.DEFAULT_DECISION_MODEL }
            val payloadJson = buildDecisionsRequestJson(
                modelId = cleanModel,
                input = input,
                questions = questions
            ).toString()

            executeWithPoolFailover(
                explicitApiKey = apiKey,
                tag = tag,
                penalizeKeyOnQuota = penalizeKeyOnQuota
            ) { entry, activeKey ->
                val startMs = System.currentTimeMillis()
                val request = Request.Builder()
                    .url(DECISIONS_URL)
                    .header("x-goog-api-key", activeKey.trim())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                client.newCall(request).execute().use { response ->
                    val latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errMsg = extractApiError(bodyStr, response.code)
                        throw DecisionHttpException(response.code, errMsg)
                    }
                    parseDecisionsResponseJson(
                        bodyStr = bodyStr,
                        modelUsed = cleanModel,
                        latencyMs = latencyMs,
                        keyLabelUsed = entry?.label
                    )
                }
            }
        }
    }

    /**
     * Fast, calibrated incoming-message triage for Auto-Chat before invoking a generative model.
     * Accurately separates ABUSIVE, SPAM_NONSENSE, SENSITIVE_OWNER_ONLY, IMAGE_REQUEST, and NORMAL.
     */
    suspend fun classifyIncomingMessage(
        apiKey: String = "",
        modelId: String = PilotStateHub.settings.value.decisionModel,
        appName: String,
        senderName: String,
        incomingText: String,
        conversationHistory: List<String>,
        personalDataFacts: List<PersonalDataItem>
    ): Result<IncomingMessageDecision> {
        val shareableFacts = personalDataFacts.filter {
            it.shareInAutoReply && it.title.isNotBlank() && it.content.isNotBlank()
        }
        val inputContext = buildString {
            append("Messaging App: ").append(appName).append('\n')
            append("Sender: ").append(senderName).append('\n')
            if (conversationHistory.isNotEmpty()) {
                append("Recent Conversation History:\n")
                conversationHistory.takeLast(6).forEach { append("- ").append(it).append('\n') }
            }
            if (shareableFacts.isNotEmpty()) {
                append("Owner's Saved Personal Data (allowed to share automatically):\n")
                shareableFacts.take(20).forEach { fact ->
                    append("- [").append(fact.category).append("] ")
                        .append(fact.title).append(": ").append(fact.content.take(180)).append('\n')
                }
            }
            append("LATEST Incoming Message from ").append(senderName).append(": \"")
                .append(incomingText.take(600)).append("\"")
        }

        val questions = linkedMapOf<String, DecisionQuestion>(
            "category" to DecisionQuestion.Choice(
                instructions = "Classify the LATEST incoming message into the single most accurate category.",
                criteria = linkedMapOf(
                    "ABUSIVE" to "Profanity, slurs, insults, verbal abuse, rudeness, or hostile scolding (e.g., Bengali/Banglish/English গালাগালি or বকাবাজি).",
                    "SPAM_NONSENSE" to "Meaningless gibberish, random letters/emojis, trolling, or nonsensical spam (আবুল তাবুল).",
                    "SENSITIVE_OWNER_ONLY" to "Strictly private, financial, or real-world personal commitment ONLY the human owner can answer (e.g., passwords, OTP, PIN, NID, sending money/loan/bKash/bank transfer, private live address/location NOT present in Owner's Saved Personal Data, urgent real-life emergency, or binding personal promises).",
                    "IMAGE_REQUEST" to "The sender is explicitly asking the assistant to generate, draw, or create a brand-new picture/image/artwork (e.g., 'একটা পাখির ছবি তৈরি করে দাও', 'draw a sunset image').",
                    "NORMAL" to "Everything else: greetings, normal chat, questions, advice, poems (কবিতা), stories, jokes, study help, general knowledge, or personal questions already answered by Owner's Saved Personal Data."
                )
            ),
            "needs_owner" to DecisionQuestion.Noul(
                instructions = "Does this message strictly require interrupting the human phone owner personally because an AI assistant cannot safely or accurately handle it using general knowledge or Owner's Saved Personal Data?"
            ),
            "answerable_by_personal_data" to DecisionQuestion.Noul(
                instructions = "Can the sender's LATEST message be answered directly using the facts listed in Owner's Saved Personal Data above?"
            ),
            "urgency" to DecisionQuestion.Score(
                instructions = "Rate how urgent the sender's LATEST message is.",
                criteria = listOf(
                    "0: Casual chat, low urgency, greetings, jokes, or creative prompts.",
                    "1: Normal question or moderate everyday request.",
                    "2: Urgent real-world situation, time-sensitive emergency, or critical financial/personal request."
                )
            )
        )

        return evaluateDecisions(
            apiKey = apiKey,
            modelId = modelId,
            input = inputContext,
            questions = questions,
            tag = "Decision1"
        ).map { batch ->
            val catAns = batch.choiceAnswers["category"]
            val normalizedCat = when (catAns?.choice?.uppercase(Locale.US)) {
                "ABUSIVE", "SPAM_NONSENSE", "SENSITIVE_OWNER_ONLY", "IMAGE_REQUEST", "NORMAL" ->
                    catAns.choice.uppercase(Locale.US)
                else -> "NORMAL"
            }
            IncomingMessageDecision(
                category = normalizedCat,
                categoryConfidence = catAns?.confidence ?: 0.0,
                needsOwnerProb = batch.noulAnswers["needs_owner"]?.probability ?: 0.0,
                answerableByPersonalDataProb = batch.noulAnswers["answerable_by_personal_data"]?.probability ?: 0.0,
                urgencyScore = batch.scoreAnswers["urgency"]?.score ?: 0.0,
                latencyMs = batch.latencyMs,
                keyLabelUsed = batch.keyLabelUsed
            )
        }
    }

    /**
     * Calibrated decision routing for Hands-Free Live Voice Consultation when the phone owner speaks.
     * Accurately distinguishes DISCUSS vs SEND_REPLY vs CANCEL before generating spoken Bengali text.
     */
    suspend fun classifyOwnerVoiceIntent(
        apiKey: String = "",
        modelId: String = PilotStateHub.settings.value.decisionModel,
        senderName: String,
        appName: String,
        incomingMessage: String,
        consultationTranscript: List<String>,
        ownerLatestUtterance: String
    ): Result<OwnerVoiceIntentDecision> {
        val inputContext = buildString {
            append("App: ").append(appName).append(" | Sender: ").append(senderName).append('\n')
            append("Important Incoming Message: \"").append(incomingMessage.take(300)).append("\"\n")
            if (consultationTranscript.isNotEmpty()) {
                append("Consultation Transcript So Far:\n")
                consultationTranscript.takeLast(8).forEach { append("- ").append(it).append('\n') }
            }
            append("Owner's Latest Spoken Utterance: \"").append(ownerLatestUtterance.take(300)).append("\"")
        }

        val questions = linkedMapOf<String, DecisionQuestion>(
            "action" to DecisionQuestion.Choice(
                instructions = "Decide what the phone owner wants LivePilot AI to do right now.",
                criteria = linkedMapOf(
                    "DISCUSS" to "Owner is asking a question, checking earlier chat history ('আগে কী বলেছে?'), asking AI for advice/suggestion ('কী রিপ্লাই দিলে ভালো হবে?'), or thinking aloud BEFORE sending.",
                    "SEND_REPLY" to "Owner clearly dictates the reply message to send ('ওকে বলো আমি এখন ব্যস্ত') OR explicitly approves sending a suggested reply ('হ্যাঁ এটাই পাঠাও', 'পাঠিয়ে দাও', 'yes send it').",
                    "CANCEL" to "Owner wants to cancel, ignore, skip, or stop without sending any reply ('বাদ দাও', 'পাঠাতে হবে না', 'থাক', 'বাতিল', 'cancel')."
                )
            ),
            "explicit_send_approval" to DecisionQuestion.Noul(
                instructions = "Did the owner clearly instruct or explicitly approve sending a reply message to the sender right now?"
            )
        )

        return evaluateDecisions(
            apiKey = apiKey,
            modelId = modelId,
            input = inputContext,
            questions = questions,
            tag = "Decision1"
        ).map { batch ->
            val actAns = batch.choiceAnswers["action"]
            val normalizedAction = when (actAns?.choice?.uppercase(Locale.US)) {
                "SEND_REPLY", "CANCEL", "DISCUSS" -> actAns.choice.uppercase(Locale.US)
                else -> "DISCUSS"
            }
            OwnerVoiceIntentDecision(
                action = normalizedAction,
                confidence = actAns?.confidence ?: 0.0,
                explicitSendApprovalProb = batch.noulAnswers["explicit_send_approval"]?.probability ?: 0.0,
                latencyMs = batch.latencyMs
            )
        }
    }

    /**
     * Fast calibrated completion check for AutonomousTaskEngine before announcing a task is complete.
     */
    suspend fun verifyTaskOutcome(
        apiKey: String = "",
        modelId: String = PilotStateHub.settings.value.decisionModel,
        goal: String,
        claimedReport: String,
        stepHistory: List<String>,
        compactUiSummary: String
    ): Result<TaskVerificationDecision> {
        val inputContext = buildString {
            append("User Original Goal: ").append(goal).append('\n')
            append("Agent Claimed Report: ").append(claimedReport.ifBlank { "(none)" }).append('\n')
            if (stepHistory.isNotEmpty()) {
                append("Recent Steps Executed:\n")
                stepHistory.takeLast(10).forEach { append("- ").append(it).append('\n') }
            }
            append("Current Screen UI Tree Summary:\n").append(compactUiSummary.take(1800))
        }

        val questions = linkedMapOf<String, DecisionQuestion>(
            "goal_completed" to DecisionQuestion.Noul(
                instructions = "Based strictly on the executed steps and current screen UI tree, is EVERY part of the User Original Goal genuinely completed right now?"
            ),
            "hit_blocker_or_error" to DecisionQuestion.Noul(
                instructions = "Is the screen currently stuck on an error message, login/OTP wall, permission dialog, or incomplete step that prevents completion?"
            )
        )

        return evaluateDecisions(
            apiKey = apiKey,
            modelId = modelId,
            input = inputContext,
            questions = questions,
            tag = "Decision1"
        ).map { batch ->
            val completed = batch.noulAnswers["goal_completed"]
            val blocker = batch.noulAnswers["hit_blocker_or_error"]
            TaskVerificationDecision(
                goalCompletedProb = completed?.probability ?: 0.5,
                goalCompletedConfidence = completed?.confidence ?: 0.0,
                hitBlockerProb = blocker?.probability ?: 0.0,
                latencyMs = batch.latencyMs
            )
        }
    }

    /**
     * Calibrated destructive/irreversible UI action check for borderline confirmation taps
     * (e.g., Confirm/Submit/Order/Transfer buttons) during autonomous phone control.
     */
    suspend fun evaluateDestructiveAction(
        apiKey: String = "",
        modelId: String = PilotStateHub.settings.value.decisionModel,
        userGoal: String,
        actionLabel: String,
        targetDescriptor: String,
        currentPackage: String
    ): Result<DestructiveActionDecision> {
        val inputContext = buildString {
            append("User Goal: ").append(userGoal).append('\n')
            append("Current App Package: ").append(currentPackage).append('\n')
            append("Planned Step Label: ").append(actionLabel).append('\n')
            append("Target Button / UI Element: ").append(targetDescriptor)
        }

        val questions = linkedMapOf<String, DecisionQuestion>(
            "is_destructive_or_irreversible" to DecisionQuestion.Noul(
                instructions = "Would tapping this target button cause an irreversible financial payment/transfer, account/file deletion, app uninstall, factory reset, or binding order purchase that the user did not explicitly confirm?"
            )
        )

        return evaluateDecisions(
            apiKey = apiKey,
            modelId = modelId,
            input = inputContext,
            questions = questions,
            tag = "Decision1"
        ).map { batch ->
            val ans = batch.noulAnswers["is_destructive_or_irreversible"]
            DestructiveActionDecision(
                isDestructiveProb = ans?.probability ?: 0.0,
                confidence = ans?.confidence ?: 0.0,
                latencyMs = batch.latencyMs
            )
        }
    }

    /**
     * Live connection & calibration test for Settings -> Decision Brain card.
     */
    suspend fun testDecisionModel(
        apiKey: String = "",
        modelId: String = PilotSettings.DEFAULT_DECISION_MODEL
    ): Triple<Boolean, String, Long> {
        val cleanModel = modelId.trim().removePrefix("models/").ifBlank { PilotSettings.DEFAULT_DECISION_MODEL }
        val res = evaluateDecisions(
            apiKey = apiKey,
            modelId = cleanModel,
            input = "Sender: Karim | Message: ভাই জরুরি ৫০০০ টাকা বিকাশ করে দাও এখনই।",
            questions = linkedMapOf(
                "needs_owner" to DecisionQuestion.Noul(
                    instructions = "Does this message ask for money or a sensitive financial action that only the human phone owner can approve?"
                )
            ),
            tag = "DecisionTest",
            penalizeKeyOnQuota = false
        )
        return res.fold(
            onSuccess = { batch ->
                val ans = batch.noulAnswers["needs_owner"]
                val probPct = ((ans?.probability ?: 0.0) * 100.0).toInt()
                val confPct = ((ans?.confidence ?: 0.0) * 100.0).toInt()
                val keyNote = batch.keyLabelUsed?.let { " • $it" }.orEmpty()
                PilotStateHub.log(
                    LogLevel.INFO,
                    "Decision1",
                    "Test passed on $cleanModel: needs_owner=${probPct}% (conf=${confPct}%) in ${batch.latencyMs}ms"
                )
                Triple(
                    true,
                    "✓ $cleanModel ready (score=${probPct}%, conf=${confPct}% • ${batch.latencyMs}ms$keyNote)",
                    batch.latencyMs
                )
            },
            onFailure = { err ->
                PilotStateHub.log(LogLevel.ERROR, "Decision1", "Test failed on $cleanModel: ${err.message}")
                Triple(
                    false,
                    err.message ?: "Decision model test failed.",
                    0L
                )
            }
        )
    }

    private inline fun <T> executeWithPoolFailover(
        explicitApiKey: String,
        tag: String,
        penalizeKeyOnQuota: Boolean,
        block: (keyEntry: GeminiApiKeyEntry?, rawApiKey: String) -> T
    ): T {
        val settings = PilotStateHub.settings.value
        val pool = ApiKeyPoolManager.effectivePool(settings)

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
                throw lastError ?: IllegalStateException(ApiKeyPoolManager.buildNoKeyAvailableMessage(selection))
            }
            triedIds.add(entry.id)

            try {
                val output = block(entry, entry.key)
                ApiKeyPoolManager.reportSuccess(entry.id)
                return output
            } catch (e: DecisionHttpException) {
                lastError = e
                val classification = ApiKeyPoolManager.classifyFailure(
                    httpCode = e.httpCode,
                    message = e.message
                )
                if (classification.kind == ApiFailureKind.TRANSIENT) {
                    throw e
                }
                if (!penalizeKeyOnQuota && classification.kind != ApiFailureKind.INVALID_KEY) {
                    if (attempt < maxAttempts) {
                        PilotStateHub.log(
                            LogLevel.INFO,
                            tag,
                            "Decision model quota on ${entry.label}; trying backup key..."
                        )
                        continue
                    }
                    throw e
                }
                val promoted = ApiKeyPoolManager.reportFailureAndRotate(
                    failedKeyId = entry.id,
                    classification = classification
                )
                if (promoted != null && attempt < maxAttempts) {
                    continue
                }
                throw e
            }
        }
        throw lastError ?: IllegalStateException("Gemini Decisions API failed across configured keys.")
    }

    private fun extractApiError(bodyStr: String, code: Int): String {
        val parsed = runCatching {
            val root = JSONObject(bodyStr)
            root.optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return if (parsed.isNotBlank()) "HTTP $code: $parsed" else "HTTP $code: ${bodyStr.take(180)}"
    }
}

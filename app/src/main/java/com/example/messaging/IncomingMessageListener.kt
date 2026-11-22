package com.example.messaging

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.ai.GeminiRestClient
import com.example.automation.PilotAccessibilityService
import com.example.data.AutoChatMode
import com.example.data.AutoReplyDelivery
import com.example.data.IncomingMessageRecord
import com.example.data.LogLevel
import com.example.data.MessagingApp
import com.example.data.PilotStateHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class IncomingMessageListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var connectedAtMs: Long = 0L

    private val recentFingerprints = ConcurrentHashMap<String, Long>()
    private val inFlightConversations = ConcurrentHashMap<String, Boolean>()
    private val lastAutoReplyAtMs = ConcurrentHashMap<String, Long>()
    private val pendingDebounceJobs = ConcurrentHashMap<String, Job>()
    private val pendingBatchMessages = ConcurrentHashMap<String, MutableList<Pair<IncomingMessageRecord, Notification.Action?>>>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedAtMs = System.currentTimeMillis()
        PilotStateHub.ensureInitialized(applicationContext)
        PilotStateHub.log(LogLevel.INFO, "AutoChat", "IncomingMessageListener connected (anti-loop + sensitive guard active).")
    }

    override fun onDestroy() {
        pendingDebounceJobs.values.forEach { it.cancel() }
        pendingDebounceJobs.clear()
        pendingBatchMessages.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        PilotStateHub.ensureInitialized(applicationContext)

        val pkg = sbn.packageName ?: return
        val messagingApp = MessagingApp.fromPackage(pkg) ?: return
        val notification = sbn.notification ?: return

        // 1. Ignore summary shells, ongoing calls/uploads, and local-only status notifications
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_LOCAL_ONLY) != 0) return

        val now = System.currentTimeMillis()
        // Ignore stale notifications replayed from before listener connection or older than 45 seconds
        val postTime = sbn.postTime
        if (postTime > 0L) {
            if (connectedAtMs > 0L && postTime < connectedAtMs - 3_000L) return
            if (now - postTime > 45_000L) return
        }

        // 2. Extract real latest message via MessagingStyle (CRITICAL: filters out our own RemoteInput replies!)
        val extracted = SensitiveMessageGuard.extractIncomingMessage(notification)
        if (extracted.isSelfOrSystem) {
            return
        }
        val sender = extracted.senderName.trim()
        val text = extracted.messageText.trim()
        if (sender.isBlank() || text.isBlank()) return

        // 3. Sent-Reply Echo Guard: never treat our own recently sent reply as an incoming message!
        if (isRecentSelfEcho(pkg, sender, text, now)) {
            PilotStateHub.log(
                LogLevel.SAFETY,
                "AutoChat",
                "Blocked self-reply echo on ${messagingApp.displayName} ($sender): \"${text.take(40)}\""
            )
            return
        }

        // 4. Long-window fingerprint deduplication (5 minutes) so Android notification updates never re-trigger replies
        val convKey = conversationKey(pkg, sender)
        val fingerprint = "$convKey|${normalizeForMatch(text)}"
        val lastSeen = recentFingerprints[fingerprint]
        if (lastSeen != null && (now - lastSeen) < FINGERPRINT_DEDUP_MS) return
        recentFingerprints[fingerprint] = now
        if (recentFingerprints.size > 200) {
            recentFingerprints.entries.removeIf { now - it.value > FINGERPRINT_DEDUP_MS }
        }

        val replyAction = findDirectReplyAction(notification)
        val record = IncomingMessageRecord(
            id = UUID.randomUUID().toString(),
            app = messagingApp,
            packageName = pkg,
            senderName = sender,
            messageText = text,
            timestampMs = now,
            formattedTime = PilotStateHub.formatNow(),
            hasDirectReply = replyAction != null
        )

        PilotStateHub.recordIncomingMessage(
            record = record,
            contentIntent = notification.contentIntent,
            replyAction = replyAction
        )
        appendConversationHistory(convKey, "$sender: $text")
        PilotStateHub.log(
            LogLevel.INFO,
            "AutoChat",
            "Incoming ${messagingApp.displayName} from $sender: \"${text.take(60)}\""
        )

        val settings = PilotStateHub.settings.value
        if (messagingApp !in settings.enabledMessagingApps) return

        when (settings.autoChatMode) {
            AutoChatMode.LIVE_VOICE_ALERT -> {
                val controller = PilotStateHub.activeController
                if (controller != null) {
                    val alert = "[SYSTEM EVENT: New message on ${messagingApp.displayName} from $sender: \"$text\"]. Inform the user briefly in 1 short sentence and ask if they want you to open ${messagingApp.displayName} and reply."
                    controller.pushIncomingMessageAlert(alert)
                }
            }
            AutoChatMode.FULL_AUTO_REPLY -> {
                enqueueDebouncedAutoReply(convKey, record, replyAction)
            }
        }
    }

    /**
     * Batches rapid consecutive messages from the same sender (2.5s debounce window)
     * and guarantees ONLY ONE reply is generated & sent per turn, eliminating reply floods.
     */
    private fun enqueueDebouncedAutoReply(
        convKey: String,
        record: IncomingMessageRecord,
        replyAction: Notification.Action?
    ) {
        val batch = pendingBatchMessages.computeIfAbsent(convKey) { mutableListOf() }
        synchronized(batch) {
            batch.add(record to replyAction)
        }

        pendingDebounceJobs.remove(convKey)?.cancel()
        pendingDebounceJobs[convKey] = scope.launch {
            delay(DEBOUNCE_WINDOW_MS)
            pendingDebounceJobs.remove(convKey)

            // Wait briefly if a previous reply for this exact sender is still sending
            if (inFlightConversations.putIfAbsent(convKey, true) != null) {
                PilotStateHub.log(LogLevel.INFO, "AutoReply", "Skipped overlapping auto-reply for ${record.senderName}.")
                return@launch
            }

            try {
                val items = pendingBatchMessages.remove(convKey)?.let { list ->
                    synchronized(list) { list.toList() }
                }.orEmpty()
                if (items.isEmpty()) return@launch

                val latestEntry = items.last()
                val latestRecord = latestEntry.first
                val latestReplyAction = items.lastOrNull { it.second != null }?.second

                // Enforce per-sender anti-flood cooldown (10s minimum gap between consecutive auto-replies)
                val now = System.currentTimeMillis()
                val lastReplied = lastAutoReplyAtMs[convKey] ?: 0L
                val elapsed = now - lastReplied
                if (elapsed < PER_SENDER_COOLDOWN_MS) {
                    val waitLeft = PER_SENDER_COOLDOWN_MS - elapsed
                    delay(waitLeft)
                }

                // Combine multi-bubble rapid texts into one clean prompt
                val combinedIncoming = items
                    .map { it.first.messageText.trim() }
                    .distinct()
                    .joinToString("\n")

                executeSingleSafeAutoReply(
                    convKey = convKey,
                    record = latestRecord,
                    combinedIncomingText = combinedIncoming,
                    replyAction = latestReplyAction
                )
            } finally {
                inFlightConversations.remove(convKey)
            }
        }
    }

    private suspend fun executeSingleSafeAutoReply(
        convKey: String,
        record: IncomingMessageRecord,
        combinedIncomingText: String,
        replyAction: Notification.Action?
    ) {
        val settings = PilotStateHub.settings.value
        if (!settings.hasAnyApiKey) {
            PilotStateHub.log(LogLevel.ERROR, "AutoReply", "Skipped auto-reply: API key not set.")
            return
        }

        val shareablePersonalData = PilotStateHub.personalData.value.filter {
            it.shareInAutoReply && it.title.isNotBlank() && it.content.isNotBlank()
        }

        // Layer 1: Fast local sensitive question check.
        // Ultra-critical security secrets (OTP/PIN/password/NID) are ALWAYS deferred locally.
        // Other topics (e.g. address/profession/schedule) can defer to Layer 2 Gemini if the user
        // explicitly saved facts in "আমার ডাটা", so AI can answer from saved personal data!
        val localSensitive = if (settings.sensitiveQuestionGuardEnabled) {
            val assessment = SensitiveMessageGuard.assessSensitivityLocally(combinedIncomingText)
            val isHardSecret = assessment.reason?.contains("পাসওয়ার্ড") == true
            if (assessment.isSensitive && (isHardSecret || shareablePersonalData.isEmpty())) {
                assessment
            } else {
                SensitiveAssessment(isSensitive = false)
            }
        } else {
            SensitiveAssessment(isSensitive = false)
        }

        var isSensitive = localSensitive.isSensitive
        var sensitivityReason = localSensitive.reason
        var ownerQuestion = localSensitive.questionForOwner
        var finalReplyText = localSensitive.deferralReply

        if (!isSensitive) {
            // Layer 2: Context-aware Gemini Smart Auto-Reply + Personal Data ("আমার ডাটা") + AI Sensitive Detector
            val screenContext = if (settings.autoReplyDelivery == AutoReplyDelivery.VISUAL_ACCESSIBILITY || replyAction == null) {
                val accessibility = PilotAccessibilityService.instance
                if (accessibility != null) {
                    accessibility.openNotificationChat(record.senderName)
                    delay(1200)
                    accessibility.getScreenUiJson().toString()
                } else {
                    null
                }
            } else {
                null
            }

            val historySnapshot = getConversationHistory(convKey)
            val aiResult = GeminiRestClient.generateSmartAutoReply(
                apiKey = settings.effectiveApiKey,
                modelId = settings.textModel,
                personaPrompt = settings.autoReplyPersona,
                appName = record.app.displayName,
                senderName = record.senderName,
                incomingText = combinedIncomingText,
                conversationHistory = historySnapshot,
                screenContext = screenContext,
                sensitiveGuardEnabled = settings.sensitiveQuestionGuardEnabled,
                personalDataFacts = shareablePersonalData
            )

            val smart = aiResult.getOrElse { err ->
                PilotStateHub.log(LogLevel.ERROR, "AutoReply", "AI reply generation failed: ${err.message}")
                return
            }

            isSensitive = smart.isSensitive
            sensitivityReason = smart.sensitivityReason
            ownerQuestion = smart.questionForOwner
            finalReplyText = smart.replyText
        }

        if (finalReplyText.isBlank()) return

        // Register sent reply BEFORE dispatching RemoteInput so instant WhatsApp notification echo is 100% blocked!
        registerSentReply(record.packageName, record.senderName, finalReplyText)
        lastAutoReplyAtMs[convKey] = System.currentTimeMillis()

        val delivered = deliverReply(
            settingsDelivery = settings.autoReplyDelivery,
            record = record,
            replyAction = replyAction,
            replyText = finalReplyText
        )

        if (!delivered) {
            PilotStateHub.log(LogLevel.ERROR, "AutoReply", "Could not deliver reply to ${record.senderName}.")
            return
        }

        appendConversationHistory(convKey, "Me: $finalReplyText")

        if (isSensitive) {
            val resolvedQuestion = ownerQuestion.ifBlank {
                "${record.senderName} (${record.app.displayName}) জিজ্ঞেস করেছে: \"${combinedIncomingText.take(140)}\" — এর উত্তর কী পাঠাবো?"
            }
            PilotStateHub.markMessageSensitiveEscalated(
                recordId = record.id,
                deferralReply = finalReplyText,
                reason = sensitivityReason,
                ownerQuestion = resolvedQuestion
            )
            PilotStateHub.log(
                LogLevel.SAFETY,
                "SensitiveGuard",
                "Deferred sensitive message from ${record.senderName} (${sensitivityReason ?: "Sensitive"}) with \"$finalReplyText\" and escalated to owner."
            )

            // Automatic Escalation 1: Ask the owner over active Live Voice Call (if running)
            // OR launch Screen-Off Loud Speaker Voice Ask + Distant Mic Listener!
            val controller = PilotStateHub.activeController
            if (controller != null) {
                val voiceEscalation = buildString {
                    append("[URGENT SENSITIVE MESSAGE ESCALATION: On ${record.app.displayName}, ${record.senderName} asked a sensitive question: \"${combinedIncomingText.take(180)}\"")
                    if (!sensitivityReason.isNullOrBlank()) append(" (Category: $sensitivityReason)")
                    append(". I did NOT answer it; I already replied \"$finalReplyText\" automatically. ")
                    append("Immediately ask the user clearly in Bengali what answer they want to send back to ${record.senderName}. ")
                    append("When the user gives you the answer, call answer_sensitive_message(sender_or_app=\"${record.senderName}\", owner_answer=\"...\") to forward it automatically!]")
                }
                controller.pushIncomingMessageAlert(voiceEscalation)
            } else if (settings.handsFreeVoiceEscalationEnabled) {
                HandsFreeVoiceAskService.startEscalation(
                    context = applicationContext,
                    recordId = record.id,
                    senderName = record.senderName,
                    appName = record.app.displayName,
                    packageName = record.packageName,
                    messageText = combinedIncomingText
                )
            }

            // Automatic Escalation 2: ALWAYS post a high-priority owner notification with Direct Inline Reply
            val updatedRecord = PilotStateHub.incomingMessages.value.firstOrNull { it.id == record.id }
                ?: record.copy(autoRepliedText = finalReplyText, isSensitive = true, needsOwnerAnswer = true)
            SensitiveMessageGuard.postSensitiveOwnerAlertNotification(
                context = applicationContext,
                record = updatedRecord,
                ownerQuestion = resolvedQuestion,
                sensitivityReason = sensitivityReason
            )
        } else {
            PilotStateHub.markMessageAutoReplied(record.id, finalReplyText)
            PilotStateHub.log(
                LogLevel.INFO,
                "AutoReply",
                "Replied cleanly once to ${record.senderName} (${record.app.displayName}): \"$finalReplyText\""
            )
        }
    }

    private suspend fun deliverReply(
        settingsDelivery: AutoReplyDelivery,
        record: IncomingMessageRecord,
        replyAction: Notification.Action?,
        replyText: String
    ): Boolean {
        if (settingsDelivery == AutoReplyDelivery.SILENT_DIRECT_REPLY && replyAction != null) {
            return sendDirectReply(
                context = applicationContext,
                action = replyAction,
                replyText = replyText,
                packageName = record.packageName,
                senderName = record.senderName
            )
        }

        // Visual Accessibility Reply Mode (or fallback when app has no Direct Reply RemoteInput)
        val accessibility = PilotAccessibilityService.instance
        if (accessibility == null) {
            // If silent direct reply action still exists as fallback, use it!
            if (replyAction != null) {
                return sendDirectReply(
                    context = applicationContext,
                    action = replyAction,
                    replyText = replyText,
                    packageName = record.packageName,
                    senderName = record.senderName
                )
            }
            PilotStateHub.log(LogLevel.ERROR, "AutoReply", "Visual Auto-Reply needs Accessibility Service enabled.")
            return false
        }

        accessibility.openNotificationChat(record.senderName)
        delay(900)
        val res = accessibility.typeText(
            index = -1,
            x = 0,
            y = 0,
            text = replyText,
            pressSend = true
        )
        return res.optString("status") == "ok"
    }

    private fun findDirectReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { action ->
            val inputs = action.remoteInputs
            inputs != null && inputs.isNotEmpty()
        }
    }

    companion object {
        private const val DEBOUNCE_WINDOW_MS = 2_400L
        private const val PER_SENDER_COOLDOWN_MS = 10_000L
        private const val FINGERPRINT_DEDUP_MS = 5 * 60_000L
        private const val SELF_ECHO_MEMORY_MS = 5 * 60_000L

        private data class SentEchoRecord(
            val normalizedText: String,
            val sentAtMs: Long
        )

        private val recentSentReplies = ConcurrentHashMap<String, CopyOnWriteArrayList<SentEchoRecord>>()
        private val conversationHistoryMap = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

        internal fun conversationKey(packageName: String, senderName: String): String =
            "${packageName.trim().lowercase()}|${senderName.trim().lowercase()}"

        internal fun normalizeForMatch(text: String): String =
            text.trim().lowercase().replace(Regex("\\s+"), " ")

        fun registerSentReply(
            packageName: String,
            senderName: String,
            replyText: String,
            nowMs: Long = System.currentTimeMillis()
        ) {
            val norm = normalizeForMatch(replyText)
            if (norm.isBlank()) return
            val key = conversationKey(packageName, senderName)
            val list = recentSentReplies.computeIfAbsent(key) { CopyOnWriteArrayList() }
            list.add(SentEchoRecord(norm, nowMs))
            // Trim stale records
            list.removeIf { nowMs - it.sentAtMs > SELF_ECHO_MEMORY_MS }
            while (list.size > 20) {
                list.removeAt(0)
            }
        }

        fun isRecentSelfEcho(
            packageName: String,
            senderName: String,
            incomingText: String,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean {
            val normIncoming = normalizeForMatch(incomingText)
            if (normIncoming.isBlank()) return false

            // Check both exact conversation key and package-wide recent replies (handles group/renamed title variations)
            val exactKey = conversationKey(packageName, senderName)
            val exactList = recentSentReplies[exactKey]
            if (exactList != null && exactList.any { rec ->
                    (nowMs - rec.sentAtMs) <= SELF_ECHO_MEMORY_MS &&
                        (normIncoming == rec.normalizedText ||
                            normIncoming.endsWith(": ${rec.normalizedText}") ||
                            (rec.normalizedText.length >= 8 && normIncoming.contains(rec.normalizedText)))
                }
            ) {
                return true
            }

            // Fallback: check if exact same reply text was just sent in the same messaging app within last 45s
            val pkgPrefix = "${packageName.trim().lowercase()}|"
            for ((k, list) in recentSentReplies) {
                if (!k.startsWith(pkgPrefix)) continue
                if (list.any { rec -> (nowMs - rec.sentAtMs) <= 45_000L && normIncoming == rec.normalizedText }) {
                    return true
                }
            }
            return false
        }

        internal fun clearEchoCacheForTest() {
            recentSentReplies.clear()
            conversationHistoryMap.clear()
        }

        private fun appendConversationHistory(convKey: String, line: String) {
            val list = conversationHistoryMap.computeIfAbsent(convKey) { CopyOnWriteArrayList() }
            list.add(line.take(220))
            while (list.size > 10) {
                list.removeAt(0)
            }
        }

        private fun getConversationHistory(convKey: String): List<String> =
            conversationHistoryMap[convKey]?.toList().orEmpty()

        fun sendDirectReply(
            context: Context,
            action: Notification.Action,
            replyText: String,
            packageName: String = "",
            senderName: String = ""
        ): Boolean {
            val remoteInputs = action.remoteInputs ?: return false
            if (remoteInputs.isEmpty()) return false
            if (packageName.isNotBlank() && senderName.isNotBlank()) {
                registerSentReply(packageName, senderName, replyText)
            }
            return runCatching {
                val intent = Intent()
                val bundle = Bundle()
                for (input in remoteInputs) {
                    bundle.putCharSequence(input.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                action.actionIntent.send(context, 0, intent)
                true
            }.getOrDefault(false)
        }
    }
}

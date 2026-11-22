package com.example.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.example.MainActivity
import com.example.R
import com.example.data.IncomingMessageRecord
import com.example.data.LogLevel
import com.example.data.PilotStateHub

data class ExtractedNotificationMessage(
    val senderName: String,
    val messageText: String,
    val conversationTitle: String?,
    val isSelfOrSystem: Boolean,
    val skipReason: String? = null
)

data class SensitiveAssessment(
    val isSensitive: Boolean,
    val reason: String? = null,
    val deferralReply: String = "",
    val questionForOwner: String = ""
)

object SensitiveMessageGuard {

    const val CHANNEL_SENSITIVE_ALERTS = "livepilot_sensitive_questions_v1"
    const val KEY_OWNER_REPLY_TEXT = "key_owner_sensitive_reply_text"
    const val EXTRA_RECORD_ID = "extra_sensitive_record_id"
    const val EXTRA_NOTIFICATION_ID = "extra_sensitive_notif_id"
    const val ACTION_OWNER_SENSITIVE_REPLY = "com.example.action.OWNER_SENSITIVE_REPLY"

    private val SELF_NAMES = setOf(
        "you", "me", "আপনি", "আমি", "yourself", "owner"
    )

    private val SYSTEM_TITLE_NOISE = listOf(
        "whatsapp", "messenger", "telegram", "imo",
        "checking for new messages", "backup in progress",
        "whatsapp web", "finished backup"
    )

    private val SYSTEM_TEXT_NOISE = listOf(
        "checking for new messages",
        "calling…",
        "calling...",
        "incoming voice call",
        "incoming video call",
        "ongoing voice call",
        "ongoing video call",
        "missed voice call",
        "missed video call",
        "backup in progress",
        "deleting messages",
        "messages from",
        "new messages",
        "unread messages",
        "this message was deleted",
        "you deleted this message",
        "waiting for this message"
    )

    // Fast local heuristic keywords for sensitive/private/financial/personal questions (Bengali, Banglish & English)
    private val SENSITIVE_PATTERNS: List<Pair<String, Regex>> = listOf(
        "আর্থিক / টাকা / পেমেন্ট সংক্রান্ত" to Regex(
            "(?i)(টাকা|ধার|পাঠাও|পাঠান|বিকাশ|নগদ|রকেট|উপায়|ব্যাংক|একাউন্ট|অ্যাকাউন্ট|কার্ড|লোন|বেতন|স্যালারি|দাম কত|কত টাকা|পাওনা|হিসাব|bkash|nagad|rocket|upay|bank|account\\s*number|card\\s*number|credit\\s*card|debit\\s*card|send\\s*money|lend|loan|borrow|transfer|salary|payment|pay\\s*me|taka|dhar)"
        ),
        "পাসওয়ার্ড / পিন / OTP / সিকিউরিটি" to Regex(
            "(?i)(পাসওয়ার্ড|পাসওয়ার্ড|পিন|ওটিপি|কোডটা|ভেরিফিকেশন|লগইন|password|passcode|pin\\s*code|otp|verification\\s*code|secret\\s*code|login\\s*code|2fa|cvv|nid|এনআইডি|পাসপোর্ট|passport)"
        ),
        "ব্যক্তিগত ঠিকানা / লোকেশন / নাম্বার" to Regex(
            "(?i)(বাসার\\s*ঠিকানা|ঠিকানা\\s*দাও|এখন\\s*কোথায়|এখন\\s*কোথায়|কোথায়\\s*আছ|কোথায়\\s*আছ|লোকেশন\\s*দাও|ফোন\\s*নাম্বার|নাম্বারটা\\s*দাও|home\\s*address|exact\\s*address|live\\s*location|where\\s*are\\s*you\\s*right\\s*now|phone\\s*number|personal\\s*number)"
        ),
        "ব্যক্তিগত সিদ্ধান্ত / কমিটমেন্ট / জরুরি বিষয়" to Regex(
            "(?i)(দেখা\\s*করবে|দেখা\\s*করব|আসবে\\s*কখন|কয়টায়\\s*আসবে|কয়টায়\\s*আসবে|কনফার্ম\\s*কর|কথা\\s*দাও|বিয়ে|বিয়ে|ডিভোর্স|গোপন\\s*কথা|ব্যক্তিগত|হাসপাতাল|অসুস্থ|জরুরি\\s*দরকার|emergency|hospital|promise|confirm\\s*meeting|private\\s*matter|confidential|secret|agreement|contract|deal)"
        )
    )

    /**
     * Accurately extracts the latest real incoming message from an Android Notification.
     * Inspects MessagingStyle (EXTRA_MESSAGES) first so self-replies posted via RemoteInput
     * are ALWAYS detected and ignored — preventing infinite auto-reply self-loops!
     */
    fun extractIncomingMessage(notification: Notification): ExtractedNotificationMessage {
        val extras = notification.extras
        if (extras == null) {
            return ExtractedNotificationMessage("", "", null, true, "Missing extras")
        }

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawConvTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val selfDisplayName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }

        // 1. Inspect AndroidX NotificationCompat.MessagingStyle (used by WhatsApp, Messenger, Telegram, imo)
        val messagingStyle = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()

        if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
            val userDisplayName = messagingStyle.user.name?.toString()?.trim()
            val lastMsg = messagingStyle.messages.last()
            val msgText = lastMsg.text?.toString()?.trim().orEmpty()
            val msgPerson = lastMsg.person
            val msgSenderName = msgPerson?.name?.toString()?.trim().orEmpty()

            // In Android MessagingStyle, a message with person == null OR person matching the self user is OUR OWN reply!
            val isSelf = msgPerson == null ||
                msgSenderName.isBlank() ||
                isSelfName(msgSenderName, selfDisplayName, userDisplayName) ||
                (messagingStyle.user.key != null && messagingStyle.user.key == msgPerson.key)

            if (isSelf) {
                return ExtractedNotificationMessage(
                    senderName = rawConvTitle ?: rawTitle,
                    messageText = msgText,
                    conversationTitle = rawConvTitle,
                    isSelfOrSystem = true,
                    skipReason = "Own outgoing RemoteInput message in MessagingStyle"
                )
            }

            val resolvedSender = when {
                !rawConvTitle.isNullOrBlank() && rawConvTitle != msgSenderName -> "$msgSenderName ($rawConvTitle)"
                msgSenderName.isNotBlank() -> msgSenderName
                else -> rawTitle
            }

            if (isSystemNoise(resolvedSender, msgText)) {
                return ExtractedNotificationMessage(
                    senderName = resolvedSender,
                    messageText = msgText,
                    conversationTitle = rawConvTitle,
                    isSelfOrSystem = true,
                    skipReason = "System or summary notification noise"
                )
            }

            return ExtractedNotificationMessage(
                senderName = resolvedSender,
                messageText = msgText,
                conversationTitle = rawConvTitle,
                isSelfOrSystem = false
            )
        }

        // 2. Fallback for apps not using standard MessagingStyle
        val rawText = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()

        // Strip trailing line if bigText contains multiple lines stacked ("Sender: msg")
        val latestLine = rawText.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        val effectiveText = latestLine.ifBlank { rawText }

        if (effectiveText.startsWith("You:", ignoreCase = true) ||
            effectiveText.startsWith("Me:", ignoreCase = true) ||
            effectiveText.startsWith("আপনি:") ||
            effectiveText.startsWith("আমি:")
        ) {
            return ExtractedNotificationMessage(
                senderName = rawTitle,
                messageText = effectiveText,
                conversationTitle = rawConvTitle,
                isSelfOrSystem = true,
                skipReason = "Self-prefixed line in notification text"
            )
        }

        if (isSelfName(rawTitle, selfDisplayName, null) || isSystemNoise(rawTitle, effectiveText)) {
            return ExtractedNotificationMessage(
                senderName = rawTitle,
                messageText = effectiveText,
                conversationTitle = rawConvTitle,
                isSelfOrSystem = true,
                skipReason = "System or self fallback notification"
            )
        }

        return ExtractedNotificationMessage(
            senderName = rawTitle,
            messageText = effectiveText,
            conversationTitle = rawConvTitle,
            isSelfOrSystem = false
        )
    }

    private fun isSelfName(candidate: String, selfExtra: String?, styleUserName: String?): Boolean {
        val norm = candidate.trim().lowercase()
        if (norm in SELF_NAMES) return true
        if (!selfExtra.isNullOrBlank() && norm == selfExtra.trim().lowercase()) return true
        if (!styleUserName.isNullOrBlank() && norm == styleUserName.trim().lowercase()) return true
        return false
    }

    fun isSystemNoise(title: String, text: String): Boolean {
        val tLower = title.trim().lowercase()
        val mLower = text.trim().lowercase()
        if (tLower.isBlank() || mLower.isBlank()) return true
        if (tLower in SYSTEM_TITLE_NOISE) return true
        if (Regex("^\\d+\\s+(new\\s+)?messages?.*$").matches(mLower)) return true
        if (Regex("^\\d+\\s+নতুন\\s+মেসেজ.*$").matches(mLower)) return true
        if (SYSTEM_TEXT_NOISE.any { mLower.contains(it) }) return true
        return false
    }

    /**
     * Local fast sensitive-question detector (Layer 1 before Gemini JSON classifier).
     */
    fun assessSensitivityLocally(messageText: String): SensitiveAssessment {
        val clean = messageText.trim()
        if (clean.isBlank()) return SensitiveAssessment(isSensitive = false)

        for ((reason, regex) in SENSITIVE_PATTERNS) {
            if (regex.containsMatchIn(clean)) {
                return SensitiveAssessment(
                    isSensitive = true,
                    reason = reason,
                    deferralReply = defaultDeferralReply(clean),
                    questionForOwner = "উনি জিজ্ঞেস করেছেন: \"${clean.take(140)}\" — এর উত্তর কী পাঠাবো?"
                )
            }
        }
        return SensitiveAssessment(isSensitive = false)
    }

    fun isLikelyBengaliOrBanglish(text: String): Boolean {
        if (text.any { it in '\u0980'..'\u09FF' }) return true
        val lower = text.lowercase()
        val banglishHints = listOf(
            "kemon", "kothay", "taka", "bhai", "vai", "ami", "tumi", "apni",
            "ki kor", "kobe", "ekhon", "basha", "basa", "dhar", "lagbe", "bolo", "bolen", "accho", "acho"
        )
        return banglishHints.any { lower.contains(it) }
    }

    fun defaultDeferralReply(incomingText: String): String {
        return if (isLikelyBengaliOrBanglish(incomingText)) {
            "আমি জেনে বলছি।"
        } else {
            "Let me check and get back to you."
        }
    }

    /**
     * Posts an automatic high-priority notification asking the phone owner for the answer
     * to a sensitive incoming question, complete with Direct Inline Reply so the owner can
     * answer right from the notification shade OR tap to open Auto-Chat.
     */
    fun postSensitiveOwnerAlertNotification(
        context: Context,
        record: IncomingMessageRecord,
        ownerQuestion: String,
        sensitivityReason: String?
    ) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_SENSITIVE_ALERTS,
                    "Sensitive Question Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Asks you automatically when someone sends a sensitive message on WhatsApp, imo, Messenger, or Telegram"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val notifId = (record.id.hashCode() and 0x0FFFFFFF) + 4100

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_TAB, "AUTO_CHAT")
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Direct Inline Reply action so the owner can reply directly from notification bar
            val remoteInput = RemoteInput.Builder(KEY_OWNER_REPLY_TEXT)
                .setLabel("${record.senderName}-কে পাঠানোর উত্তর লিখুন...")
                .build()

            val replyIntent = Intent(context, SensitiveOwnerReplyReceiver::class.java).apply {
                action = ACTION_OWNER_SENSITIVE_REPLY
                putExtra(EXTRA_RECORD_ID, record.id)
                putExtra(EXTRA_NOTIFICATION_ID, notifId)
            }
            val replyPendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                notifId + 1,
                replyIntent,
                replyPendingFlags
            )

            val replyAction = NotificationCompat.Action.Builder(
                R.mipmap.ic_launcher,
                "উত্তর পাঠান",
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .build()

            val reasonTag = sensitivityReason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val bodyText = buildString {
                append("${record.senderName}: \"${record.messageText.take(150)}\"\n")
                append("✓ ওকে \"${record.autoRepliedText ?: "আমি জেনে বলছি।"}\" পাঠানো হয়েছে।\n")
                append(ownerQuestion.ifBlank { "এখন এখান থেকেই সঠিক উত্তর লিখে পাঠান।" })
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_SENSITIVE_ALERTS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("⚠️ সেনসিটিভ প্রশ্ন — ${record.senderName} (${record.app.displayName})$reasonTag")
                .setContentText("\"${record.messageText.take(90)}\" — আপনার উত্তর প্রয়োজন")
                .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openPendingIntent)
                .addAction(replyAction)
                .build()

            nm.notify(notifId, notification)
            PilotStateHub.log(
                LogLevel.SAFETY,
                "SensitiveGuard",
                "Asked owner via high-priority notification for ${record.senderName} (${record.app.displayName})."
            )
        }.onFailure { err ->
            PilotStateHub.log(LogLevel.ERROR, "SensitiveGuard", "Failed to post owner alert notification: ${err.message}")
        }
    }
}

package com.example.messaging

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.data.LogLevel
import com.example.data.PilotStateHub

/**
 * Handles direct inline answers typed by the phone owner inside the Sensitive Question
 * escalation notification and forwards them seamlessly to WhatsApp / imo / Messenger / Telegram.
 */
class SensitiveOwnerReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != SensitiveMessageGuard.ACTION_OWNER_SENSITIVE_REPLY) return
        PilotStateHub.ensureInitialized(context.applicationContext)

        val remoteInputBundle = RemoteInput.getResultsFromIntent(intent) ?: return
        val ownerReply = remoteInputBundle
            .getCharSequence(SensitiveMessageGuard.KEY_OWNER_REPLY_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()

        val recordId = intent.getStringExtra(SensitiveMessageGuard.EXTRA_RECORD_ID).orEmpty()
        val notifId = intent.getIntExtra(SensitiveMessageGuard.EXTRA_NOTIFICATION_ID, -1)

        if (ownerReply.isBlank() || recordId.isBlank()) return

        val handle = PilotStateHub.getNotificationHandleById(recordId)
        val record = PilotStateHub.incomingMessages.value.firstOrNull { it.id == recordId }

        if (handle?.replyAction != null) {
            val sent = IncomingMessageListener.sendDirectReply(
                context = context.applicationContext,
                action = handle.replyAction,
                replyText = ownerReply,
                packageName = handle.packageName,
                senderName = handle.senderName
            )
            if (sent) {
                PilotStateHub.markSensitiveMessageResolved(recordId, ownerReply)
                ChatModerationEngine.markOwnerRespondedForSender(
                    IncomingMessageListener.conversationKey(handle.packageName, handle.senderName)
                )
                PilotStateHub.log(
                    LogLevel.INFO,
                    "SensitiveGuard",
                    "Owner inline answer forwarded to ${handle.senderName}: \"$ownerReply\""
                )
            } else {
                PilotStateHub.log(
                    LogLevel.ERROR,
                    "SensitiveGuard",
                    "Inline forward failed for ${handle.senderName}; open chat manually."
                )
            }
        } else if (handle?.contentIntent != null) {
            runCatching { handle.contentIntent.send() }
            PilotStateHub.markSensitiveMessageResolved(recordId, ownerReply)
            ChatModerationEngine.markOwnerRespondedForSender(
                IncomingMessageListener.conversationKey(handle.packageName, handle.senderName)
            )
        } else if (record != null) {
            PilotStateHub.markSensitiveMessageResolved(recordId, ownerReply)
            ChatModerationEngine.markOwnerRespondedForSender(
                IncomingMessageListener.conversationKey(record.packageName, record.senderName)
            )
        }

        if (notifId > 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }
    }
}

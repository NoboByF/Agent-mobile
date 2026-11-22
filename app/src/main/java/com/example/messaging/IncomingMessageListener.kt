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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class IncomingMessageListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentFingerprints = ConcurrentHashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        PilotStateHub.ensureInitialized(applicationContext)
        PilotStateHub.log(LogLevel.INFO, "AutoChat", "IncomingMessageListener connected.")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        PilotStateHub.ensureInitialized(applicationContext)

        val pkg = sbn.packageName ?: return
        val messagingApp = MessagingApp.fromPackage(pkg) ?: return
        val notification = sbn.notification ?: return

        // Ignore ongoing call/status notifications and group summary shells
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()

        if (sender.isBlank() || text.isBlank()) return

        // Deduplicate rapid notification updates within 6 seconds
        val fingerprint = "$pkg|$sender|$text"
        val now = System.currentTimeMillis()
        val lastSeen = recentFingerprints[fingerprint]
        if (lastSeen != null && (now - lastSeen) < 6000L) return
        recentFingerprints[fingerprint] = now
        if (recentFingerprints.size > 120) {
            recentFingerprints.entries.removeIf { now - it.value > 60_000L }
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
                triggerFullAutoReply(record, replyAction)
            }
        }
    }

    private fun triggerFullAutoReply(
        record: IncomingMessageRecord,
        replyAction: Notification.Action?
    ) {
        scope.launch {
            val settings = PilotStateHub.settings.value
            if (!settings.hasAnyApiKey) {
                PilotStateHub.log(LogLevel.ERROR, "AutoReply", "Skipped auto-reply: API key not set.")
                return@launch
            }

            if (settings.autoReplyDelivery == AutoReplyDelivery.SILENT_DIRECT_REPLY && replyAction != null) {
                val replyResult = GeminiRestClient.generateAutoReply(
                    apiKey = settings.effectiveApiKey,
                    modelId = settings.textModel,
                    personaPrompt = settings.autoReplyPersona,
                    appName = record.app.displayName,
                    senderName = record.senderName,
                    incomingText = record.messageText
                )
                replyResult.onSuccess { replyText ->
                    val sent = sendDirectReply(applicationContext, replyAction, replyText)
                    if (sent) {
                        PilotStateHub.markMessageAutoReplied(record.id, replyText)
                        PilotStateHub.log(
                            LogLevel.INFO,
                            "AutoReply",
                            "Silent Direct Reply sent to ${record.senderName} (${record.app.displayName}): \"$replyText\""
                        )
                    } else {
                        PilotStateHub.log(LogLevel.ERROR, "AutoReply", "RemoteInput send failed.")
                    }
                }.onFailure { err ->
                    PilotStateHub.log(LogLevel.ERROR, "AutoReply", "AI reply generation failed: ${err.message}")
                }
            } else {
                // Visual Accessibility Reply Mode (or fallback when app has no Direct Reply RemoteInput)
                val accessibility = PilotAccessibilityService.instance
                if (accessibility == null) {
                    PilotStateHub.log(
                        LogLevel.ERROR,
                        "AutoReply",
                        "Visual Auto-Reply needs Accessibility Service enabled."
                    )
                    return@launch
                }

                accessibility.openNotificationChat(record.senderName)
                delay(1400)
                val screenJson = accessibility.getScreenUiJson().toString()
                val replyResult = GeminiRestClient.generateAutoReply(
                    apiKey = settings.effectiveApiKey,
                    modelId = settings.textModel,
                    personaPrompt = settings.autoReplyPersona,
                    appName = record.app.displayName,
                    senderName = record.senderName,
                    incomingText = record.messageText,
                    screenContext = screenJson
                )
                replyResult.onSuccess { replyText ->
                    accessibility.typeText(
                        index = -1,
                        x = 0,
                        y = 0,
                        text = replyText,
                        pressSend = true
                    )
                    PilotStateHub.markMessageAutoReplied(record.id, replyText)
                    PilotStateHub.log(
                        LogLevel.INFO,
                        "AutoReply",
                        "Visual Accessibility reply sent to ${record.senderName}: \"$replyText\""
                    )
                }.onFailure { err ->
                    PilotStateHub.log(LogLevel.ERROR, "AutoReply", "Visual reply error: ${err.message}")
                }
            }
        }
    }

    private fun findDirectReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { action ->
            val inputs = action.remoteInputs
            inputs != null && inputs.isNotEmpty()
        }
    }

    companion object {
        fun sendDirectReply(
            context: Context,
            action: Notification.Action,
            replyText: String
        ): Boolean {
            val remoteInputs = action.remoteInputs ?: return false
            if (remoteInputs.isEmpty()) return false
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

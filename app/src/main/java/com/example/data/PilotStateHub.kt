package com.example.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class CachedNotificationHandle(
    val recordId: String,
    val app: MessagingApp,
    val packageName: String,
    val senderName: String,
    val contentIntent: PendingIntent?,
    val replyAction: Notification.Action?
)

interface OverlaySessionController {
    fun toggleMute()
    fun toggleAutoVision()
    fun triggerManualVisionCapture()
    fun emergencyStopActions()
    fun endCallSession()
    fun scheduleEndCallAfterSpeech(delayMs: Long = 3200L) {}
    fun setOverlayTemporarilyHidden(hidden: Boolean)
    fun setOverlayPassThrough(passThrough: Boolean)
    fun showTouchIndicator(x: Int, y: Int, label: String = "")
    fun pushIncomingMessageAlert(alertText: String)
    fun sendUserTextCommand(commandText: String): Boolean
    fun sendVisionFrame(jpegBytes: ByteArray): Boolean
}

object PilotStateHub {

    private val logIdCounter = AtomicLong(1L)
    private val captionIdCounter = AtomicLong(1L)
    private val toolEventIdCounter = AtomicLong(1L)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var preferences: PilotPreferences? = null

    private val _settings = MutableStateFlow(PilotSettings())
    val settings: StateFlow<PilotSettings> = _settings.asStateFlow()

    private val _sessionState = MutableStateFlow(LiveSessionState())
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    private val _logs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticLogEntry>> = _logs.asStateFlow()

    private val _captions = MutableStateFlow<List<LiveCaptionLine>>(emptyList())
    val captions: StateFlow<List<LiveCaptionLine>> = _captions.asStateFlow()

    private val _recentToolEvents = MutableStateFlow<List<RecentToolEvent>>(emptyList())
    val recentToolEvents: StateFlow<List<RecentToolEvent>> = _recentToolEvents.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<IncomingMessageRecord>>(emptyList())
    val incomingMessages: StateFlow<List<IncomingMessageRecord>> = _incomingMessages.asStateFlow()

    private val _discoveredModels = MutableStateFlow<List<DiscoveredModel>>(emptyList())
    val discoveredModels: StateFlow<List<DiscoveredModel>> = _discoveredModels.asStateFlow()

    private val _permissions = MutableStateFlow(PermissionSnapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    private val _personalData = MutableStateFlow<List<PersonalDataItem>>(emptyList())
    val personalData: StateFlow<List<PersonalDataItem>> = _personalData.asStateFlow()

    @Volatile
    var onSettingsChangedListener: ((PilotSettings) -> Unit)? = null

    @Volatile
    var onExternalAskAiMessagesAdded: ((List<AskAiMessage>) -> Unit)? = null

    private val notificationHandles = ConcurrentHashMap<String, CachedNotificationHandle>()

    @Volatile
    var activeController: OverlaySessionController? = null

    @Volatile
    var pendingStartupCommand: String? = null

    fun ensureInitialized(context: Context) {
        if (preferences == null) {
            val prefs = PilotPreferences(context.applicationContext)
            preferences = prefs
            _settings.value = prefs.loadSettings()
            _personalData.value = prefs.loadPersonalData()
        }
    }

    fun getPreferences(context: Context): PilotPreferences {
        ensureInitialized(context)
        return preferences!!
    }

    fun updateSettings(transform: (PilotSettings) -> PilotSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        preferences?.saveSettings(updated)
        onSettingsChangedListener?.invoke(updated)
    }

    /**
     * Applies cloud-synced settings without re-triggering the outbound cloud push loop.
     */
    fun applySettingsFromCloud(cloudSettings: PilotSettings) {
        _settings.value = cloudSettings
        preferences?.saveSettings(cloudSettings)
    }

    fun setPersonalData(items: List<PersonalDataItem>) {
        _personalData.value = items
        preferences?.savePersonalData(items)
    }

    fun upsertPersonalDataLocal(item: PersonalDataItem) {
        val updated = _personalData.value.filterNot { it.id == item.id }.let { listOf(item) + it }
        _personalData.value = updated
        preferences?.savePersonalData(updated)
    }

    fun removePersonalDataLocal(entryId: String) {
        val updated = _personalData.value.filterNot { it.id == entryId }
        _personalData.value = updated
        preferences?.savePersonalData(updated)
    }

    fun updateSession(transform: (LiveSessionState) -> LiveSessionState) {
        _sessionState.update(transform)
    }

    fun recordToolExecuted(toolName: String, summary: String, succeeded: Boolean) {
        val event = RecentToolEvent(
            id = toolEventIdCounter.getAndIncrement(),
            toolName = toolName,
            summary = summary,
            succeeded = succeeded,
            timestamp = formatNow()
        )
        _recentToolEvents.update { (listOf(event) + it).take(12) }
        _sessionState.update {
            it.copy(
                toolsExecutedCount = it.toolsExecutedCount + 1,
                lastToolSummary = "${if (succeeded) "✓" else "!"} $summary"
            )
        }
    }

    fun clearRecentToolEvents() {
        _recentToolEvents.value = emptyList()
    }

    fun updateAudioLevels(mic: Float? = null, ai: Float? = null) {
        _sessionState.update { state ->
            state.copy(
                micLevel = mic ?: state.micLevel,
                aiAudioLevel = ai ?: state.aiAudioLevel
            )
        }
    }

    fun updatePermissions(snapshot: PermissionSnapshot) {
        _permissions.value = snapshot
    }

    fun setDiscoveredModels(models: List<DiscoveredModel>) {
        _discoveredModels.value = models
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = DiagnosticLogEntry(
            id = logIdCounter.getAndIncrement(),
            timestamp = formatNow(),
            level = level,
            tag = tag,
            message = message
        )
        _logs.update { current ->
            (current + entry).takeLast(300)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun appendCaption(speaker: String, chunkText: String) {
        val trimmed = chunkText.trim()
        if (trimmed.isEmpty()) return
        _sessionState.update { it.copy(lastCaptionPreview = "$speaker: ${trimmed.take(72)}") }
        _captions.update { current ->
            val last = current.lastOrNull()
            if (last != null && last.speaker == speaker && last.text.length < 240) {
                val merged = if (last.text.endsWith(" ") || trimmed.startsWith(" ")) {
                    last.text + trimmed
                } else {
                    "${last.text} $trimmed"
                }
                current.dropLast(1) + last.copy(text = merged, timestamp = formatNow())
            } else {
                (current + LiveCaptionLine(
                    id = captionIdCounter.getAndIncrement(),
                    speaker = speaker,
                    text = trimmed,
                    timestamp = formatNow()
                )).takeLast(60)
            }
        }
    }

    fun clearCaptions() {
        _captions.value = emptyList()
    }

    fun recordIncomingMessage(
        record: IncomingMessageRecord,
        contentIntent: PendingIntent?,
        replyAction: Notification.Action?
    ) {
        notificationHandles[record.id] = CachedNotificationHandle(
            recordId = record.id,
            app = record.app,
            packageName = record.packageName,
            senderName = record.senderName,
            contentIntent = contentIntent,
            replyAction = replyAction
        )
        _incomingMessages.update { current ->
            (listOf(record) + current).take(60)
        }
    }

    fun markMessageAutoReplied(recordId: String, replyText: String, statusNote: String? = null) {
        _incomingMessages.update { current ->
            current.map { msg ->
                if (msg.id == recordId) {
                    msg.copy(
                        autoRepliedText = replyText,
                        aiStatus = "REPLIED",
                        aiStatusNote = statusNote ?: msg.aiStatusNote
                    )
                } else {
                    msg
                }
            }
        }
    }

    fun markMessageStatus(recordId: String, status: String?, note: String?) {
        _incomingMessages.update { current ->
            current.map { msg ->
                if (msg.id == recordId) {
                    msg.copy(
                        aiStatus = status,
                        aiStatusNote = note
                    )
                } else {
                    msg
                }
            }
        }
    }

    fun markWaitingMessagesForConversationStatus(
        packageName: String,
        senderName: String,
        status: String,
        note: String
    ) {
        val normPkg = packageName.trim().lowercase()
        val normSender = senderName.trim().lowercase()
        _incomingMessages.update { current ->
            current.map { msg ->
                val matchesPkg = msg.packageName.trim().lowercase() == normPkg
                val matchesSender = normSender.isBlank() ||
                    msg.senderName.trim().lowercase() == normSender ||
                    msg.senderName.trim().lowercase().contains(normSender)
                if (matchesPkg && matchesSender && msg.aiStatus == "WAITING") {
                    msg.copy(aiStatus = status, aiStatusNote = note)
                } else {
                    msg
                }
            }
        }
    }

    fun markMessageGeneratedImage(recordId: String, imagePath: String, replyText: String? = null) {
        _incomingMessages.update { current ->
            current.map { msg ->
                if (msg.id == recordId) {
                    msg.copy(
                        generatedImagePath = imagePath,
                        autoRepliedText = replyText ?: msg.autoRepliedText,
                        aiStatus = "REPLIED",
                        aiStatusNote = "AI ছবি তৈরি করেছে"
                    )
                } else {
                    msg
                }
            }
        }
    }

    fun recordGeneratedImageInAskAi(
        prompt: String,
        imagePath: String,
        caption: String,
        modelUsed: String
    ) {
        val prefs = preferences ?: return
        val current = prefs.loadAskAiHistory()
        val nextBaseId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
        val nowLabel = formatNow()
        val userMsg = AskAiMessage(
            id = nextBaseId,
            isUser = true,
            text = "🎨 $prompt",
            timestamp = nowLabel
        )
        val aiMsg = AskAiMessage(
            id = nextBaseId + 1L,
            isUser = false,
            text = caption.ifBlank { "আপনার জন্য ছবিটি তৈরি করে গ্যালারিতে সেভ করা হয়েছে।" },
            modelUsed = modelUsed,
            timestamp = nowLabel,
            generatedImagePath = imagePath
        )
        val updated = (current + userMsg + aiMsg).takeLast(30)
        prefs.saveAskAiHistory(updated)
        onExternalAskAiMessagesAdded?.invoke(listOf(userMsg, aiMsg))
    }

    fun markMessageSensitiveEscalated(
        recordId: String,
        deferralReply: String,
        reason: String?,
        ownerQuestion: String
    ) {
        _incomingMessages.update { current ->
            current.map { msg ->
                if (msg.id == recordId) {
                    msg.copy(
                        autoRepliedText = deferralReply,
                        isSensitive = true,
                        sensitivityReason = reason,
                        needsOwnerAnswer = true,
                        ownerQuestion = ownerQuestion,
                        aiStatus = "SENSITIVE",
                        aiStatusNote = reason ?: "আপনার সিদ্ধান্ত প্রয়োজন"
                    )
                } else {
                    msg
                }
            }
        }
    }

    fun markSensitiveMessageResolved(recordId: String, ownerReply: String) {
        _incomingMessages.update { current ->
            current.map { msg ->
                if (msg.id == recordId) {
                    msg.copy(
                        needsOwnerAnswer = false,
                        ownerResolvedReply = ownerReply,
                        autoRepliedText = ownerReply,
                        aiStatus = "REPLIED",
                        aiStatusNote = "আপনি নিজে উত্তর পাঠিয়েছেন"
                    )
                } else {
                    msg
                }
            }
        }
    }

    fun findPendingSensitiveMessage(query: String = ""): IncomingMessageRecord? {
        val pending = _incomingMessages.value.filter { it.needsOwnerAnswer }
        if (pending.isEmpty()) return null
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return pending.first()
        return pending.firstOrNull {
            it.id == query ||
                it.senderName.lowercase().contains(normalized) ||
                it.app.displayName.lowercase().contains(normalized)
        } ?: pending.first()
    }

    fun clearIncomingMessages() {
        _incomingMessages.value = emptyList()
        notificationHandles.clear()
    }

    fun findNotificationHandle(query: String): CachedNotificationHandle? {
        val normalized = query.trim().lowercase()
        val orderedIds = _incomingMessages.value.map { it.id }
        if (normalized.isEmpty()) {
            val latestId = orderedIds.firstOrNull() ?: return null
            return notificationHandles[latestId]
        }
        for (id in orderedIds) {
            val handle = notificationHandles[id] ?: continue
            if (handle.senderName.lowercase().contains(normalized)) return handle
        }
        for (id in orderedIds) {
            val handle = notificationHandles[id] ?: continue
            if (handle.app.displayName.lowercase().contains(normalized) ||
                handle.packageName.lowercase().contains(normalized)
            ) {
                return handle
            }
        }
        return null
    }

    fun getNotificationHandleById(recordId: String): CachedNotificationHandle? =
        notificationHandles[recordId]

    fun formatNow(): String = synchronized(timeFormat) { timeFormat.format(Date()) }
}

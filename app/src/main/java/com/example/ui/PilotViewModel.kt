package com.example.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.automation.PilotAccessibilityService
import com.example.data.AskAiMessage
import com.example.data.AutoChatMode
import com.example.data.AutoReplyDelivery
import com.example.data.ConnectionTestState
import com.example.data.DiagnosticLogEntry
import com.example.data.DiscoveredModel
import com.example.data.IncomingMessageRecord
import com.example.data.LiveCaptionLine
import com.example.data.LiveSessionState
import com.example.data.LogLevel
import com.example.data.MessagingApp
import com.example.data.PermissionSnapshot
import com.example.data.PilotSettings
import com.example.data.PilotStateHub
import com.example.messaging.IncomingMessageListener
import com.example.service.FloatingCallOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class AskAiUiState(
    val messages: List<AskAiMessage> = emptyList(),
    val searchGroundingEnabled: Boolean = true,
    val isGenerating: Boolean = false,
    val errorText: String? = null
)

class PilotViewModel(application: Application) : AndroidViewModel(application) {

    private val chatIdCounter = AtomicLong(1L)

    val settings: StateFlow<PilotSettings> = PilotStateHub.settings
    val sessionState: StateFlow<LiveSessionState> = PilotStateHub.sessionState
    val permissions: StateFlow<PermissionSnapshot> = PilotStateHub.permissions
    val logs: StateFlow<List<DiagnosticLogEntry>> = PilotStateHub.logs
    val captions: StateFlow<List<LiveCaptionLine>> = PilotStateHub.captions
    val incomingMessages: StateFlow<List<IncomingMessageRecord>> = PilotStateHub.incomingMessages
    val discoveredModels: StateFlow<List<DiscoveredModel>> = PilotStateHub.discoveredModels

    private val _connectionTest = MutableStateFlow(ConnectionTestState())
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    private val _modelFetchStatus = MutableStateFlow<String?>(null)
    val modelFetchStatus: StateFlow<String?> = _modelFetchStatus.asStateFlow()

    private val _askAiState = MutableStateFlow(AskAiUiState())
    val askAiState: StateFlow<AskAiUiState> = _askAiState.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    init {
        PilotStateHub.ensureInitialized(application.applicationContext)
        refreshPermissions()
    }

    fun dismissBanner() {
        _bannerMessage.value = null
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        val mic = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(ctx)
        val accessibility = isAccessibilityServiceEnabled(ctx)
        val notificationAccess = isNotificationListenerEnabled(ctx)

        PilotStateHub.updatePermissions(
            PermissionSnapshot(
                micGranted = mic,
                overlayGranted = overlay,
                accessibilityEnabled = accessibility,
                notificationAccessEnabled = notificationAccess
            )
        )
    }

    fun startOrStopLiveCall() {
        val ctx = getApplication<Application>()
        if (sessionState.value.isServiceRunning) {
            FloatingCallOverlayService.stop(ctx)
            return
        }

        val currentSettings = settings.value
        if (currentSettings.apiKey.isBlank()) {
            _bannerMessage.value = "Add your Gemini API Key in Settings before starting a live call."
            return
        }
        refreshPermissions()
        if (!permissions.value.micGranted) {
            _bannerMessage.value = "Grant Microphone permission first to start voice calls."
            return
        }

        FloatingCallOverlayService.start(ctx)
    }

    fun toggleMute() {
        PilotStateHub.activeController?.toggleMute()
    }

    fun triggerVisionCapture() {
        PilotStateHub.activeController?.triggerManualVisionCapture()
    }

    fun emergencyStop() {
        PilotStateHub.activeController?.emergencyStopActions()
    }

    fun testLiveConnection() {
        if (_connectionTest.value.isTesting) return
        val current = settings.value
        viewModelScope.launch {
            _connectionTest.value = ConnectionTestState(isTesting = true, message = "Testing v1beta handshake...")
            val (ok, message, latency) = GeminiLiveClient.testLiveHandshake(
                apiKey = current.apiKey,
                modelId = current.liveModel,
                voiceName = current.voiceName
            )
            _connectionTest.value = ConnectionTestState(
                isTesting = false,
                success = ok,
                message = message,
                latencyMs = latency
            )
        }
    }

    fun fetchModels() {
        if (_isFetchingModels.value) return
        val key = settings.value.apiKey
        viewModelScope.launch {
            _isFetchingModels.value = true
            _modelFetchStatus.value = "Querying v1beta/models..."
            val result = GeminiRestClient.fetchAvailableModels(key)
            result.onSuccess { models ->
                PilotStateHub.setDiscoveredModels(models)
                val liveCount = models.count { it.isLiveCapable }
                val textCount = models.count { it.supportsGenerateContent }
                _modelFetchStatus.value = "Found ${models.size} models ($liveCount Live, $textCount Text/Chat). Tap any chip to select."
            }.onFailure { err ->
                _modelFetchStatus.value = err.message ?: "Failed to fetch models."
            }
            _isFetchingModels.value = false
        }
    }

    // --- Settings Updates ---

    fun updateApiKey(key: String) {
        PilotStateHub.updateSettings { it.copy(apiKey = key) }
    }

    fun updateLiveModel(modelId: String) {
        val clean = modelId.trim().removePrefix("models/")
        PilotStateHub.updateSettings { it.copy(liveModel = clean) }
    }

    fun updateTextModel(modelId: String) {
        val clean = modelId.trim().removePrefix("models/")
        PilotStateHub.updateSettings { it.copy(textModel = clean) }
    }

    fun updateVoiceName(voice: String) {
        PilotStateHub.updateSettings { it.copy(voiceName = voice) }
    }

    fun updateSpeakerphone(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(speakerphoneOn = enabled) }
        if (sessionState.value.isServiceRunning) {
            FloatingCallOverlayService.updateSpeaker(getApplication(), enabled)
        }
    }

    fun updateAutoChatMode(mode: AutoChatMode) {
        PilotStateHub.updateSettings { it.copy(autoChatMode = mode) }
    }

    fun updateAutoReplyDelivery(delivery: AutoReplyDelivery) {
        PilotStateHub.updateSettings { it.copy(autoReplyDelivery = delivery) }
    }

    fun updateAutoReplyPersona(prompt: String) {
        PilotStateHub.updateSettings { it.copy(autoReplyPersona = prompt) }
    }

    fun toggleMessagingApp(app: MessagingApp) {
        PilotStateHub.updateSettings { current ->
            val next = if (app in current.enabledMessagingApps) {
                current.enabledMessagingApps - app
            } else {
                current.enabledMessagingApps + app
            }
            current.copy(enabledMessagingApps = next)
        }
    }

    // --- Ask AI Multi-Turn Chat & Google Search Grounding ---

    fun toggleSearchGrounding(enabled: Boolean) {
        _askAiState.update { it.copy(searchGroundingEnabled = enabled) }
    }

    fun sendAskAiPrompt(promptText: String) {
        val clean = promptText.trim()
        if (clean.isEmpty() || _askAiState.value.isGenerating) return

        val userMsg = AskAiMessage(
            id = chatIdCounter.getAndIncrement(),
            isUser = true,
            text = clean,
            timestamp = PilotStateHub.formatNow()
        )
        val updatedHistory = _askAiState.value.messages + userMsg
        _askAiState.update {
            it.copy(
                messages = updatedHistory,
                isGenerating = true,
                errorText = null
            )
        }

        val currentSettings = settings.value
        val searchEnabled = _askAiState.value.searchGroundingEnabled
        viewModelScope.launch {
            val result = GeminiRestClient.generateChatTurn(
                apiKey = currentSettings.apiKey,
                modelId = currentSettings.textModel,
                history = updatedHistory,
                enableGoogleSearch = searchEnabled
            )
            result.onSuccess { gen ->
                val aiMsg = AskAiMessage(
                    id = chatIdCounter.getAndIncrement(),
                    isUser = false,
                    text = gen.text,
                    citations = gen.citations,
                    modelUsed = gen.modelUsed,
                    timestamp = PilotStateHub.formatNow()
                )
                _askAiState.update {
                    it.copy(
                        messages = it.messages + aiMsg,
                        isGenerating = false,
                        errorText = null
                    )
                }
            }.onFailure { err ->
                _askAiState.update {
                    it.copy(
                        isGenerating = false,
                        errorText = err.message ?: "Request failed. Check API key or select another text model in Settings."
                    )
                }
            }
        }
    }

    fun clearAskAiThread() {
        _askAiState.update { it.copy(messages = emptyList(), errorText = null) }
    }

    // --- Auto-Chat Quick Actions ---

    fun openChatFromFeed(record: IncomingMessageRecord) {
        val handle = PilotStateHub.getNotificationHandleById(record.id)
        if (handle?.contentIntent != null) {
            runCatching { handle.contentIntent.send() }
                .onFailure {
                    PilotAccessibilityService.instance?.openAppByName(record.app.displayName)
                }
        } else {
            val svc = PilotAccessibilityService.instance
            if (svc != null) {
                svc.openAppByName(record.app.displayName)
            } else {
                _bannerMessage.value = "Enable Accessibility Service to jump directly into ${record.app.displayName}."
            }
        }
    }

    fun sendQuickAiReplyFromFeed(record: IncomingMessageRecord) {
        val handle = PilotStateHub.getNotificationHandleById(record.id)
        val currentSettings = settings.value
        if (currentSettings.apiKey.isBlank()) {
            _bannerMessage.value = "Configure your Gemini API Key in Settings first."
            return
        }

        viewModelScope.launch {
            val replyResult = GeminiRestClient.generateAutoReply(
                apiKey = currentSettings.apiKey,
                modelId = currentSettings.textModel,
                personaPrompt = currentSettings.autoReplyPersona,
                appName = record.app.displayName,
                senderName = record.senderName,
                incomingText = record.messageText
            )
            replyResult.onSuccess { replyText ->
                val directAction = handle?.replyAction
                if (directAction != null) {
                    val sent = IncomingMessageListener.sendDirectReply(
                        getApplication(),
                        directAction,
                        replyText
                    )
                    if (sent) {
                        PilotStateHub.markMessageAutoReplied(record.id, replyText)
                        _bannerMessage.value = "Sent AI reply to ${record.senderName}: \"$replyText\""
                    } else {
                        _bannerMessage.value = "Direct reply failed; tap Open Chat to reply visually."
                    }
                } else {
                    _bannerMessage.value = "Drafted reply (no Direct Reply on notification): \"$replyText\""
                }
            }.onFailure { err ->
                _bannerMessage.value = "Auto-reply error: ${err.message}"
            }
        }
    }

    fun clearIncomingFeed() {
        PilotStateHub.clearIncomingMessages()
    }

    fun clearLogs() {
        PilotStateHub.clearLogs()
        PilotStateHub.clearCaptions()
    }

    fun buildLogsExportText(): String {
        return logs.value.joinToString("\n") { entry ->
            "[${entry.timestamp}] [${entry.level.name}] ${entry.tag}: ${entry.message}"
        }
    }

    private fun isAccessibilityServiceEnabled(ctx: Application): Boolean {
        if (PilotAccessibilityService.instance != null) return true
        val expected = ComponentName(ctx, PilotAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isNotificationListenerEnabled(ctx: Application): Boolean {
        val expected = ComponentName(ctx, IncomingMessageListener::class.java).flattenToString()
        val flat = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}

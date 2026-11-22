package com.example.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.ApiKeyPoolManager
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.automation.PilotAccessibilityService
import com.example.data.AppThemeMode
import com.example.data.AskAiMessage
import com.example.data.AutoChatMode
import com.example.data.AutoReplyDelivery
import com.example.data.ConnectionTestState
import com.example.data.DiagnosticLogEntry
import com.example.data.DiscoveredModel
import com.example.data.IncomingMessageRecord
import com.example.data.LiveCaptionLine
import com.example.data.LiveSessionState
import com.example.data.MessagingApp
import com.example.data.PermissionSnapshot
import com.example.data.PilotRoutine
import com.example.data.PilotSettings
import com.example.data.PilotStateHub
import com.example.data.RecentToolEvent
import com.example.data.RoutineAccent
import com.example.data.VoiceLanguage
import com.example.messaging.IncomingMessageListener
import com.example.service.FloatingCallOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class AskAiUiState(
    val messages: List<AskAiMessage> = emptyList(),
    val searchGroundingEnabled: Boolean = true,
    val isGenerating: Boolean = false,
    val errorText: String? = null
)

class PilotViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PilotStateHub.getPreferences(application.applicationContext)
    private val chatIdCounter: AtomicLong

    val settings: StateFlow<PilotSettings> = PilotStateHub.settings
    val sessionState: StateFlow<LiveSessionState> = PilotStateHub.sessionState
    val permissions: StateFlow<PermissionSnapshot> = PilotStateHub.permissions
    val logs: StateFlow<List<DiagnosticLogEntry>> = PilotStateHub.logs
    val captions: StateFlow<List<LiveCaptionLine>> = PilotStateHub.captions
    val recentToolEvents: StateFlow<List<RecentToolEvent>> = PilotStateHub.recentToolEvents
    val incomingMessages: StateFlow<List<IncomingMessageRecord>> = PilotStateHub.incomingMessages
    val discoveredModels: StateFlow<List<DiscoveredModel>> = PilotStateHub.discoveredModels

    private val _routines = MutableStateFlow(prefs.loadRoutines())
    val routines: StateFlow<List<PilotRoutine>> = _routines.asStateFlow()

    private val _connectionTest = MutableStateFlow(ConnectionTestState())
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    private val _agentModelTest = MutableStateFlow(ConnectionTestState())
    val agentModelTest: StateFlow<ConnectionTestState> = _agentModelTest.asStateFlow()

    private val _textModelTest = MutableStateFlow(ConnectionTestState())
    val textModelTest: StateFlow<ConnectionTestState> = _textModelTest.asStateFlow()

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    private val _modelFetchStatus = MutableStateFlow<String?>(null)
    val modelFetchStatus: StateFlow<String?> = _modelFetchStatus.asStateFlow()

    private val _askAiState = MutableStateFlow(
        AskAiUiState(messages = prefs.loadAskAiHistory())
    )
    val askAiState: StateFlow<AskAiUiState> = _askAiState.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    init {
        val maxExistingId = _askAiState.value.messages.maxOfOrNull { it.id } ?: 0L
        chatIdCounter = AtomicLong(maxExistingId + 1L)
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
        if (!currentSettings.hasAnyApiKey) {
            _bannerMessage.value = "Add at least one Gemini API Key in Settings before starting a live call."
            return
        }
        val selection = ApiKeyPoolManager.selectBestKey(currentSettings)
        if (selection.entry == null) {
            _bannerMessage.value = ApiKeyPoolManager.buildNoKeyAvailableMessage(selection)
            return
        }
        refreshPermissions()
        if (!permissions.value.micGranted) {
            _bannerMessage.value = "Grant Microphone permission first to start voice calls."
            return
        }

        FloatingCallOverlayService.start(ctx)
    }

    /**
     * One-tap Smart Routine launcher: executes immediately if call is live,
     * or auto-starts the Live Call and triggers the routine automatically upon connect!
     */
    fun launchRoutine(routine: PilotRoutine) {
        if (sessionState.value.isServiceRunning) {
            sendSilentCommandToCall(routine.prompt)
            _bannerMessage.value = "Running '${routine.title}'..."
            return
        }
        val currentSettings = settings.value
        if (!currentSettings.hasAnyApiKey) {
            _bannerMessage.value = "Add at least one Gemini API Key in Settings first to run '${routine.title}'."
            return
        }
        val selection = ApiKeyPoolManager.selectBestKey(currentSettings)
        if (selection.entry == null) {
            _bannerMessage.value = ApiKeyPoolManager.buildNoKeyAvailableMessage(selection)
            return
        }
        refreshPermissions()
        if (!permissions.value.micGranted) {
            _bannerMessage.value = "Grant Microphone permission first to run '${routine.title}'."
            return
        }
        PilotStateHub.pendingStartupCommand = routine.prompt
        FloatingCallOverlayService.start(getApplication())
        _bannerMessage.value = "Starting call & queuing '${routine.title}'..."
    }

    fun addCustomRoutine(
        title: String,
        subtitle: String,
        prompt: String,
        accent: RoutineAccent
    ) {
        val cleanTitle = title.trim()
        val cleanPrompt = prompt.trim()
        if (cleanTitle.isEmpty() || cleanPrompt.isEmpty()) return
        val newRoutine = PilotRoutine(
            id = "custom_${UUID.randomUUID().toString().take(8)}",
            title = cleanTitle,
            subtitle = subtitle.trim().ifBlank { "Custom Automation" },
            prompt = cleanPrompt,
            accent = accent,
            isBuiltIn = false
        )
        val updated = listOf(newRoutine) + _routines.value
        _routines.value = updated
        prefs.saveCustomRoutines(updated)
        _bannerMessage.value = "Saved custom routine '${newRoutine.title}'!"
    }

    fun deleteCustomRoutine(id: String) {
        val updated = _routines.value.filterNot { it.id == id && !it.isBuiltIn }
        _routines.value = updated
        prefs.saveCustomRoutines(updated)
    }

    fun toggleMute() {
        PilotStateHub.activeController?.toggleMute()
    }

    fun toggleAutoVision() {
        PilotStateHub.activeController?.toggleAutoVision()
    }

    fun triggerVisionCapture() {
        PilotStateHub.activeController?.triggerManualVisionCapture()
    }

    fun emergencyStop() {
        PilotStateHub.activeController?.emergencyStopActions()
    }

    fun sendSilentCommandToCall(commandText: String) {
        val clean = commandText.trim()
        if (clean.isEmpty()) return
        val controller = PilotStateHub.activeController
        if (controller == null) {
            _bannerMessage.value = "Start a Live Call first to send live voice/tool commands."
            return
        }
        val ok = controller.sendUserTextCommand(clean)
        if (!ok) {
            _bannerMessage.value = "Live call is still connecting—please wait a moment."
        }
    }

    fun testLiveConnection() {
        if (_connectionTest.value.isTesting) return
        val current = settings.value
        viewModelScope.launch {
            _connectionTest.value = ConnectionTestState(isTesting = true, message = "Testing v1beta WebSocket handshake...")
            val (ok, message, latency) = GeminiLiveClient.testLiveHandshake(
                apiKey = current.effectiveApiKey,
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

    fun testAgentModelConnection() {
        if (_agentModelTest.value.isTesting) return
        val current = settings.value
        if (!current.hasAnyApiKey) {
            _agentModelTest.value = ConnectionTestState(
                isTesting = false,
                success = false,
                message = "Add at least one Gemini API Key first."
            )
            return
        }
        viewModelScope.launch {
            _agentModelTest.value = ConnectionTestState(
                isTesting = true,
                message = "Testing Vision + Canvas JSON on ${current.agentModel}..."
            )
            val (ok, message, latency) = GeminiRestClient.testVisionAgentModel(
                apiKey = current.effectiveApiKey,
                modelId = current.agentModel
            )
            _agentModelTest.value = ConnectionTestState(
                isTesting = false,
                success = ok,
                message = message,
                latencyMs = latency
            )
        }
    }

    fun testTextModelConnection() {
        if (_textModelTest.value.isTesting) return
        val current = settings.value
        if (!current.hasAnyApiKey) {
            _textModelTest.value = ConnectionTestState(
                isTesting = false,
                success = false,
                message = "Add at least one Gemini API Key first."
            )
            return
        }
        viewModelScope.launch {
            _textModelTest.value = ConnectionTestState(
                isTesting = true,
                message = "Testing Ask AI & Search model ${current.textModel}..."
            )
            val (ok, message, latency) = GeminiRestClient.testTextModel(
                apiKey = current.effectiveApiKey,
                modelId = current.textModel
            )
            _textModelTest.value = ConnectionTestState(
                isTesting = false,
                success = ok,
                message = message,
                latencyMs = latency
            )
        }
    }

    fun fetchModels() {
        if (_isFetchingModels.value) return
        val key = settings.value.effectiveApiKey
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

    // --- Multi-Gemini API Key Pool Management ---

    fun addApiKeys(rawInput: String, customLabel: String = "") {
        val (added, duplicates) = ApiKeyPoolManager.addKeysFromInput(rawInput, customLabel)
        _bannerMessage.value = when {
            added > 0 && duplicates > 0 -> "Added $added API key(s) ($duplicates duplicate skipped)."
            added > 0 -> "Added $added Gemini API key(s) to failover pool."
            duplicates > 0 -> "This API key is already saved in your pool."
            else -> "Paste a valid Gemini API key first."
        }
    }

    fun removeApiKey(keyId: String) {
        ApiKeyPoolManager.removeKey(keyId)
    }

    fun setActiveApiKey(keyId: String) {
        ApiKeyPoolManager.setActiveKey(keyId)
        val label = settings.value.activeKeyDisplayLabel ?: "Selected key"
        _bannerMessage.value = "Active API key switched to $label."
    }

    fun toggleApiKeyEnabled(keyId: String) {
        ApiKeyPoolManager.toggleKeyEnabled(keyId)
    }

    fun updateAutoRotateApiKeys(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(autoRotateApiKeys = enabled) }
    }

    fun resetApiKeyHealth() {
        ApiKeyPoolManager.resetAllKeyHealth()
        _bannerMessage.value = "Cleared cooldown & error flags for all saved API keys."
    }

    // --- Settings Updates ---

    fun updateApiKey(key: String) {
        PilotStateHub.updateSettings { it.copy(apiKey = key) }
    }

    fun updateLiveModel(modelId: String) {
        val clean = modelId.trim().removePrefix("models/")
        PilotStateHub.updateSettings { it.copy(liveModel = clean) }
    }

    fun updateAgentModel(modelId: String) {
        val clean = modelId.trim().removePrefix("models/")
        PilotStateHub.updateSettings { it.copy(agentModel = clean) }
    }

    fun updateVerifyTaskCompletion(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(verifyTaskCompletion = enabled) }
    }

    fun updateTextModel(modelId: String) {
        val clean = modelId.trim().removePrefix("models/")
        PilotStateHub.updateSettings { it.copy(textModel = clean) }
    }

    fun updateVoiceName(voice: String) {
        PilotStateHub.updateSettings { it.copy(voiceName = voice) }
    }

    fun updateVoiceLanguage(language: VoiceLanguage) {
        PilotStateHub.updateSettings { it.copy(voiceLanguage = language) }
    }

    fun updateSpeakerphone(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(speakerphoneOn = enabled) }
        if (sessionState.value.isServiceRunning) {
            FloatingCallOverlayService.updateSpeaker(getApplication(), enabled)
        }
    }

    fun updateAutoVisionDefault(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(autoVisionDefaultOn = enabled) }
    }

    fun updateAutoVisionInterval(seconds: Int) {
        PilotStateHub.updateSettings { it.copy(autoVisionIntervalSec = seconds.coerceIn(2, 10)) }
    }

    fun updateShowTouchVisualizer(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(showTouchVisualizer = enabled) }
    }

    fun updateShowFloatingCaptions(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(showFloatingCaptions = enabled) }
    }

    fun updateThemeMode(mode: AppThemeMode) {
        PilotStateHub.updateSettings { it.copy(themeMode = mode) }
    }

    fun cycleThemeMode(currentlyDark: Boolean) {
        val next = if (currentlyDark) AppThemeMode.LIGHT else AppThemeMode.DARK
        PilotStateHub.updateSettings { it.copy(themeMode = next) }
    }

    fun updateCustomVoiceInstructions(instructions: String) {
        PilotStateHub.updateSettings { it.copy(customVoiceInstructions = instructions) }
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

        val currentSettings = settings.value
        if (!currentSettings.hasAnyApiKey) {
            _askAiState.update {
                it.copy(errorText = "Add at least one Gemini API Key in Settings to use Ask AI.")
            }
            return
        }

        val userMsg = AskAiMessage(
            id = chatIdCounter.getAndIncrement(),
            isUser = true,
            text = clean,
            timestamp = PilotStateHub.formatNow()
        )
        val updatedHistory = _askAiState.value.messages + userMsg
        prefs.saveAskAiHistory(updatedHistory)
        _askAiState.update {
            it.copy(
                messages = updatedHistory,
                isGenerating = true,
                errorText = null
            )
        }

        val searchEnabled = _askAiState.value.searchGroundingEnabled
        viewModelScope.launch {
            val result = GeminiRestClient.generateChatTurn(
                apiKey = currentSettings.effectiveApiKey,
                modelId = currentSettings.textModel,
                history = updatedHistory,
                enableGoogleSearch = searchEnabled
            )
            result.onSuccess { gen ->
                val modelBadge = if (!gen.keyLabelUsed.isNullOrBlank() && settings.value.apiKeys.size > 1) {
                    "${gen.modelUsed} • ${gen.keyLabelUsed}"
                } else {
                    gen.modelUsed
                }
                val aiMsg = AskAiMessage(
                    id = chatIdCounter.getAndIncrement(),
                    isUser = false,
                    text = gen.text,
                    citations = gen.citations,
                    modelUsed = modelBadge,
                    timestamp = PilotStateHub.formatNow()
                )
                val finalHistory = _askAiState.value.messages + aiMsg
                prefs.saveAskAiHistory(finalHistory)
                _askAiState.update {
                    it.copy(
                        messages = finalHistory,
                        isGenerating = false,
                        errorText = null
                    )
                }
            }.onFailure { err ->
                _askAiState.update {
                    it.copy(
                        isGenerating = false,
                        errorText = err.message ?: "Request failed. Check API keys or select another text model in Settings."
                    )
                }
            }
        }
    }

    fun clearAskAiThread() {
        prefs.saveAskAiHistory(emptyList())
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

    fun sendTemplateDirectReply(record: IncomingMessageRecord, replyText: String) {
        val handle = PilotStateHub.getNotificationHandleById(record.id)
        val directAction = handle?.replyAction
        if (directAction != null) {
            val sent = IncomingMessageListener.sendDirectReply(
                getApplication(),
                directAction,
                replyText
            )
            if (sent) {
                PilotStateHub.markMessageAutoReplied(record.id, replyText)
                _bannerMessage.value = "Replied to ${record.senderName}: \"$replyText\""
            } else {
                _bannerMessage.value = "Direct reply failed; tap Open Chat to reply."
            }
        } else {
            _bannerMessage.value = "This notification doesn't support Direct Reply; tap Open Chat instead."
        }
    }

    fun sendQuickAiReplyFromFeed(record: IncomingMessageRecord) {
        val handle = PilotStateHub.getNotificationHandleById(record.id)
        val currentSettings = settings.value
        if (!currentSettings.hasAnyApiKey) {
            _bannerMessage.value = "Configure at least one Gemini API Key in Settings first."
            return
        }

        viewModelScope.launch {
            val replyResult = GeminiRestClient.generateAutoReply(
                apiKey = currentSettings.effectiveApiKey,
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
        PilotStateHub.clearRecentToolEvents()
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

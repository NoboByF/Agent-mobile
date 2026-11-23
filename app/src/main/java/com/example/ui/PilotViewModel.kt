package com.example.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.ApiKeyPoolManager
import com.example.ai.GeminiDecisionClient
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.ai.GeneratedImageHelper
import com.example.ai.MediaAttachmentHelper
import com.example.ai.PreparedAttachment
import com.example.automation.PilotAccessibilityService
import com.example.data.AppThemeMode
import com.example.data.AskAiMessage
import com.example.data.AutoChatMode
import com.example.data.AutoReplyDelivery
import com.example.data.ChatAttachmentMeta
import com.example.data.ConnectionTestState
import com.example.data.DiagnosticLogEntry
import com.example.data.DiscoveredModel
import com.example.data.IncomingMessageRecord
import com.example.data.LiveCaptionLine
import com.example.data.LiveSessionState
import com.example.data.MessagingApp
import com.example.data.PermissionSnapshot
import com.example.data.PersonalDataItem
import com.example.data.PilotRoutine
import com.example.data.PilotSettings
import com.example.data.PilotStateHub
import com.example.data.RecentToolEvent
import com.example.data.RoutineAccent
import com.example.data.VoiceLanguage
import com.example.firebase.CloudSyncRepository
import com.example.firebase.DeviceProfileIdentity
import com.example.firebase.FirebaseRuntimeConfig
import com.example.messaging.ChatModerationEngine
import com.example.messaging.IncomingMessageListener
import com.example.service.FloatingCallOverlayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class AskAiUiState(
    val messages: List<AskAiMessage> = emptyList(),
    val pendingAttachments: List<ChatAttachmentMeta> = emptyList(),
    val searchGroundingEnabled: Boolean = true,
    val imageModeEnabled: Boolean = false,
    val isGenerating: Boolean = false,
    val attachmentStatusText: String? = null,
    val errorText: String? = null
)

class PilotViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PilotStateHub.getPreferences(application.applicationContext)
    private val cloudRepo = CloudSyncRepository(application.applicationContext)
    private val chatIdCounter: AtomicLong

    val deviceProfile: DeviceProfileIdentity = cloudRepo.deviceProfile

    val settings: StateFlow<PilotSettings> = PilotStateHub.settings
    val sessionState: StateFlow<LiveSessionState> = PilotStateHub.sessionState
    val permissions: StateFlow<PermissionSnapshot> = PilotStateHub.permissions
    val logs: StateFlow<List<DiagnosticLogEntry>> = PilotStateHub.logs
    val captions: StateFlow<List<LiveCaptionLine>> = PilotStateHub.captions
    val recentToolEvents: StateFlow<List<RecentToolEvent>> = PilotStateHub.recentToolEvents
    val incomingMessages: StateFlow<List<IncomingMessageRecord>> = PilotStateHub.incomingMessages
    val discoveredModels: StateFlow<List<DiscoveredModel>> = PilotStateHub.discoveredModels
    val personalData: StateFlow<List<PersonalDataItem>> = PilotStateHub.personalData

    private val _cloudSyncStatus = MutableStateFlow("Syncing device profile...")
    val cloudSyncStatus: StateFlow<String> = _cloudSyncStatus.asStateFlow()

    private val _routines = MutableStateFlow(prefs.loadRoutines())
    val routines: StateFlow<List<PilotRoutine>> = _routines.asStateFlow()

    private val _connectionTest = MutableStateFlow(ConnectionTestState())
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    private val _agentModelTest = MutableStateFlow(ConnectionTestState())
    val agentModelTest: StateFlow<ConnectionTestState> = _agentModelTest.asStateFlow()

    private val _textModelTest = MutableStateFlow(ConnectionTestState())
    val textModelTest: StateFlow<ConnectionTestState> = _textModelTest.asStateFlow()

    private val _decisionModelTest = MutableStateFlow(ConnectionTestState())
    val decisionModelTest: StateFlow<ConnectionTestState> = _decisionModelTest.asStateFlow()

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

    private var pendingSettingsSyncJob: Job? = null
    private var initialDeviceCloudChecked = false
    private var initialPersonalDataSeeded = false

    init {
        val maxExistingId = _askAiState.value.messages.maxOfOrNull { it.id } ?: 0L
        chatIdCounter = AtomicLong(maxExistingId + 1L)
        PilotStateHub.onExternalAskAiMessagesAdded = { added ->
            if (added.isNotEmpty()) {
                val maxAddedId = added.maxOfOrNull { it.id } ?: 0L
                if (maxAddedId >= chatIdCounter.get()) {
                    chatIdCounter.set(maxAddedId + 1L)
                }
                _askAiState.update { current ->
                    val existingIds = current.messages.map { it.id }.toHashSet()
                    val fresh = added.filter { it.id !in existingIds }
                    if (fresh.isEmpty()) current else current.copy(messages = (current.messages + fresh).takeLast(80))
                }
                added.forEach { msg ->
                    viewModelScope.launch { cloudRepo.saveAskAiMessage(msg) }
                }
            }
        }
        refreshPermissions()
        startCloudSyncIfAuthenticated()
    }

    private fun startCloudSyncIfAuthenticated() {
        val currentUser = runCatching { FirebaseRuntimeConfig.auth(getApplication()).currentUser }.getOrNull()
        if (currentUser == null) {
            _cloudSyncStatus.value = "লোকাল মোড চালু (অফলাইন সেভ) • ${deviceProfile.displayLabel}"
            return
        }

        val accountLabel = currentUser.email?.takeIf { it.isNotBlank() }
            ?: currentUser.displayName?.takeIf { it.isNotBlank() }
            ?: "Firebase"

        // Seed ownerDisplayName automatically from signed-in account if user hasn't set a custom name yet
        val fbDisplayName = currentUser.displayName?.trim().orEmpty()
        if (PilotStateHub.settings.value.ownerDisplayName.isBlank() && fbDisplayName.isNotBlank()) {
            PilotStateHub.updateSettings { it.copy(ownerDisplayName = fbDisplayName.take(80)) }
        }

        // Push any local settings/API-key/model change automatically to this account's Firestore doc
        PilotStateHub.onSettingsChangedListener = { updatedSettings ->
            scheduleAccountConfigCloudSave(updatedSettings)
        }

        // 1. Observe this account's cross-device cloud config (API keys, models, deleted models, owner name, settings)
        viewModelScope.launch {
            cloudRepo.observeAccountConfig().collect { cloudSettings ->
                if (cloudSettings != null) {
                    initialDeviceCloudChecked = true
                    val currentLocal = PilotStateHub.settings.value
                    val merged = cloudSettings.copy(
                        ownerDisplayName = if (cloudSettings.ownerDisplayName.isBlank() && fbDisplayName.isNotBlank()) {
                            fbDisplayName.take(80)
                        } else {
                            cloudSettings.ownerDisplayName
                        },
                        decisionModel = currentLocal.decisionModel,
                        useDecisionModel = currentLocal.useDecisionModel
                    )
                    PilotStateHub.applySettingsFromCloud(merged)
                    _cloudSyncStatus.value = "Account Cloud Synced • $accountLabel"
                } else if (!initialDeviceCloudChecked) {
                    initialDeviceCloudChecked = true
                    // First cloud sync for this account: back up existing local settings to Firestore
                    val res = cloudRepo.saveAccountConfig(PilotStateHub.settings.value)
                    _cloudSyncStatus.value = if (res.isSuccess) {
                        "Account Cloud Synced • $accountLabel"
                    } else {
                        "Cloud backup pending (offline)"
                    }
                }
            }
        }

        // 2. Observe Personal Data ("আমার ডাটা") for this device
        viewModelScope.launch {
            cloudRepo.observePersonalData().collect { cloudItems ->
                if (cloudItems.isNotEmpty()) {
                    initialPersonalDataSeeded = true
                    PilotStateHub.setPersonalData(cloudItems)
                } else if (!initialPersonalDataSeeded) {
                    initialPersonalDataSeeded = true
                    val localExisting = PilotStateHub.personalData.value
                    if (localExisting.isNotEmpty()) {
                        localExisting.forEach { item ->
                            launch { cloudRepo.upsertPersonalData(item) }
                        }
                    } else {
                        PilotStateHub.setPersonalData(emptyList())
                    }
                } else {
                    PilotStateHub.setPersonalData(emptyList())
                }
            }
        }

        // 3. Observe Custom Routines for this device
        viewModelScope.launch {
            cloudRepo.observeCustomRoutines().collect { cloudCustom ->
                if (cloudCustom.isNotEmpty()) {
                    val combined = cloudCustom + PilotSettings.DEFAULT_ROUTINES
                    _routines.value = combined
                    prefs.saveCustomRoutines(combined)
                }
            }
        }

        // 4. Observe Ask AI History for this device (preserving device-local generated image paths)
        viewModelScope.launch {
            cloudRepo.observeAskAiMessages().collect { cloudMessages ->
                if (cloudMessages.isNotEmpty() && !_askAiState.value.isGenerating) {
                    val localImageMap = buildMap<Long, String> {
                        prefs.loadAskAiHistory().forEach { local ->
                            if (!local.generatedImagePath.isNullOrBlank()) put(local.id, local.generatedImagePath)
                        }
                        _askAiState.value.messages.forEach { local ->
                            if (!local.generatedImagePath.isNullOrBlank()) put(local.id, local.generatedImagePath)
                        }
                    }
                    val mergedMessages = cloudMessages.map { msg ->
                        val localPath = localImageMap[msg.id]
                        if (localPath != null && msg.generatedImagePath.isNullOrBlank()) {
                            msg.copy(generatedImagePath = localPath)
                        } else {
                            msg
                        }
                    }
                    val maxId = mergedMessages.maxOfOrNull { it.id } ?: 0L
                    if (maxId >= chatIdCounter.get()) {
                        chatIdCounter.set(maxId + 1L)
                    }
                    prefs.saveAskAiHistory(mergedMessages)
                    _askAiState.update { it.copy(messages = mergedMessages) }
                }
            }
        }
    }

    private fun scheduleAccountConfigCloudSave(settingsSnapshot: PilotSettings) {
        val currentUser = runCatching { FirebaseRuntimeConfig.auth(getApplication()).currentUser }.getOrNull() ?: return
        val accountLabel = currentUser.email?.takeIf { it.isNotBlank() } ?: "Firebase"
        pendingSettingsSyncJob?.cancel()
        pendingSettingsSyncJob = viewModelScope.launch {
            delay(450L)
            _cloudSyncStatus.value = "Saving account to Firebase..."
            val res = cloudRepo.saveAccountConfig(settingsSnapshot)
            _cloudSyncStatus.value = if (res.isSuccess) {
                "Account Cloud Synced • $accountLabel"
            } else {
                "Sync retry pending"
            }
        }
    }

    fun syncNowToCloud() {
        val currentUser = runCatching { FirebaseRuntimeConfig.auth(getApplication()).currentUser }.getOrNull() ?: return
        val accountLabel = currentUser.email?.takeIf { it.isNotBlank() } ?: "Firebase"
        viewModelScope.launch {
            _cloudSyncStatus.value = "Syncing account data..."
            val cfgRes = cloudRepo.saveAccountConfig(PilotStateHub.settings.value)
            PilotStateHub.personalData.value.forEach { cloudRepo.upsertPersonalData(it) }
            _routines.value.filter { !it.isBuiltIn }.forEach { cloudRepo.saveCustomRoutine(it) }
            _askAiState.value.messages.takeLast(30).forEach { cloudRepo.saveAskAiMessage(it) }
            if (cfgRes.isSuccess) {
                _cloudSyncStatus.value = "Account Cloud Synced • $accountLabel"
                _bannerMessage.value = "✓ আপনার অ্যাকাউন্টের সকল ডাটা (API Key, মডেল, আমার ডাটা, সেটিংস) Firebase ক্লাউডে সেভ হয়েছে।"
            } else {
                _cloudSyncStatus.value = "Sync failed"
                _bannerMessage.value = "ক্লাউড সিঙ্ক ব্যর্থ হয়েছে: ${cfgRes.exceptionOrNull()?.localizedMessage ?: "ইন্টারনেট চেক করুন"}"
            }
        }
    }

    fun exportDeviceBackupJson(): String =
        prefs.exportDeviceBackupJson(
            deviceId = deviceProfile.deviceId,
            deviceLabel = deviceProfile.displayLabel
        )

    fun importDeviceBackupJson(rawJson: String) {
        val result = prefs.importDeviceBackupJson(rawJson)
        result.onSuccess { summary ->
            PilotStateHub.applySettingsFromCloud(prefs.loadSettings())
            PilotStateHub.setPersonalData(prefs.loadPersonalData())
            _routines.value = prefs.loadRoutines()
            val restoredChat = prefs.loadAskAiHistory()
            val maxId = restoredChat.maxOfOrNull { it.id } ?: 0L
            if (maxId >= chatIdCounter.get()) {
                chatIdCounter.set(maxId + 1L)
            }
            _askAiState.update { it.copy(messages = restoredChat) }
            _bannerMessage.value = summary
            if (runCatching { FirebaseRuntimeConfig.auth(getApplication()).currentUser }.getOrNull() != null) {
                syncNowToCloud()
            }
        }.onFailure { err ->
            _bannerMessage.value = "ব্যাকআপ রিস্টোর ব্যর্থ: ${err.localizedMessage ?: "সঠিক JSON ফাইল দিন"}"
        }
    }

    fun showNotice(message: String) {
        _bannerMessage.value = message
    }

    override fun onCleared() {
        PilotStateHub.onSettingsChangedListener = null
        PilotStateHub.onExternalAskAiMessagesAdded = null
        pendingSettingsSyncJob?.cancel()
        super.onCleared()
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
        viewModelScope.launch { cloudRepo.saveCustomRoutine(newRoutine) }
        _bannerMessage.value = "Saved custom routine '${newRoutine.title}'!"
    }

    fun deleteCustomRoutine(id: String) {
        val updated = _routines.value.filterNot { it.id == id && !it.isBuiltIn }
        _routines.value = updated
        prefs.saveCustomRoutines(updated)
        viewModelScope.launch { cloudRepo.deleteCustomRoutine(id) }
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

    fun testDecisionModelConnection() {
        if (_decisionModelTest.value.isTesting) return
        val current = settings.value
        if (!current.hasAnyApiKey) {
            _decisionModelTest.value = ConnectionTestState(
                isTesting = false,
                success = false,
                message = "Add at least one Gemini API Key first."
            )
            return
        }
        viewModelScope.launch {
            _decisionModelTest.value = ConnectionTestState(
                isTesting = true,
                message = "Testing calibrated Decisions API on ${current.decisionModel}..."
            )
            val (ok, message, latency) = GeminiDecisionClient.testDecisionModel(
                apiKey = current.effectiveApiKey,
                modelId = current.decisionModel
            )
            _decisionModelTest.value = ConnectionTestState(
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

    fun updateDecisionModel(modelId: String) {
        val clean = modelId.trim().removePrefix("models/")
        PilotStateHub.updateSettings { it.copy(decisionModel = clean) }
    }

    fun updateUseDecisionModel(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(useDecisionModel = enabled) }
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

    fun updateSensitiveQuestionGuard(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(sensitiveQuestionGuardEnabled = enabled) }
    }

    fun updateHandsFreeVoiceEscalation(enabled: Boolean) {
        PilotStateHub.updateSettings { it.copy(handsFreeVoiceEscalationEnabled = enabled) }
    }

    fun updateOwnerDisplayName(name: String) {
        val clean = name.trim().take(80)
        PilotStateHub.updateSettings { it.copy(ownerDisplayName = clean) }
        _bannerMessage.value = if (clean.isNotBlank()) {
            "✓ অ্যাকাউন্টের নাম \"$clean\" সেভ হয়েছে — আপনি দূরে থাকলে রিপ্লাইতে এই নাম যাবে।"
        } else {
            "অ্যাকাউন্টের কাস্টম নাম মুছে ফেলা হয়েছে।"
        }
    }

    fun deleteModelFromDevice(modelId: String) {
        val clean = modelId.trim().removePrefix("models/")
        if (clean.isBlank()) return
        PilotStateHub.updateSettings { current ->
            val updatedDeleted = (current.deletedModels + clean).distinct()
            current.copy(deletedModels = updatedDeleted)
        }
        _bannerMessage.value = "মডেল '$clean' হাইড/ডিলিট করা হয়েছে (আপনার অ্যাকাউন্টে Firebase-এ সেভ হয়েছে)।"
    }

    fun restoreDeletedModel(modelId: String) {
        val clean = modelId.trim()
        PilotStateHub.updateSettings { current ->
            current.copy(deletedModels = current.deletedModels.filterNot { it.equals(clean, ignoreCase = true) })
        }
        _bannerMessage.value = "মডেল '$clean' আবার ফিরিয়ে আনা হয়েছে।"
    }

    fun restoreAllDeletedModels() {
        PilotStateHub.updateSettings { it.copy(deletedModels = emptyList()) }
        _bannerMessage.value = "সব ডিলিট করা মডেল ফিরিয়ে আনা হয়েছে।"
    }

    // --- Personal Data ('আমার ডাটা') Management (Synced per Device in Firebase) ---

    fun savePersonalDataEntry(
        existingId: String?,
        title: String,
        content: String,
        category: String,
        shareInAutoReply: Boolean
    ) {
        val cleanTitle = title.trim()
        val cleanContent = content.trim()
        if (cleanTitle.isBlank() || cleanContent.isBlank()) {
            _bannerMessage.value = "তথ্যের শিরোনাম এবং বিস্তারিত উভয়ই লিখুন।"
            return
        }
        val entry = PersonalDataItem(
            id = existingId?.takeIf { it.isNotBlank() } ?: "pd_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            title = cleanTitle.take(120),
            content = cleanContent.take(1500),
            category = category.trim().ifBlank { "পরিচয়" }.take(50),
            shareInAutoReply = shareInAutoReply,
            updatedAtMs = System.currentTimeMillis()
        )
        PilotStateHub.upsertPersonalDataLocal(entry)
        viewModelScope.launch {
            val res = cloudRepo.upsertPersonalData(entry)
            if (res.isFailure) {
                _bannerMessage.value = "লোকালি সেভ হয়েছে, Firebase সিঙ্ক পেন্ডিং।"
            }
        }
        _bannerMessage.value = "✓ \"${entry.title}\" আপনার ডাটায় সেভ হয়েছে!"
    }

    fun togglePersonalDataShare(item: PersonalDataItem) {
        val updated = item.copy(
            shareInAutoReply = !item.shareInAutoReply,
            updatedAtMs = System.currentTimeMillis()
        )
        PilotStateHub.upsertPersonalDataLocal(updated)
        viewModelScope.launch { cloudRepo.upsertPersonalData(updated) }
    }

    fun deletePersonalDataEntry(entryId: String) {
        PilotStateHub.removePersonalDataLocal(entryId)
        viewModelScope.launch { cloudRepo.deletePersonalData(entryId) }
        _bannerMessage.value = "তথ্যটি ডিলিট করা হয়েছে।"
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

    // --- Ask AI Multi-Turn Chat, Multimodal File/Photo/Video Upload & Google Search Grounding ---

    fun toggleSearchGrounding(enabled: Boolean) {
        _askAiState.update { it.copy(searchGroundingEnabled = enabled) }
    }

    fun toggleImageGenerationMode(enabled: Boolean) {
        _askAiState.update { it.copy(imageModeEnabled = enabled) }
    }

    fun saveGeneratedImageToGallery(filePath: String) {
        viewModelScope.launch {
            val res = GeneratedImageHelper.saveImageFileToGallery(
                context = getApplication(),
                filePath = filePath,
                displayLabel = "LivePilot_AI"
            )
            _bannerMessage.value = if (res.isSuccess) {
                "✓ ছবিটি ফোনের গ্যালারিতে (Pictures/LivePilot AI) সেভ হয়েছে।"
            } else {
                "গ্যালারিতে সেভ ব্যর্থ: ${res.exceptionOrNull()?.localizedMessage ?: "আবার চেষ্টা করুন"}"
            }
        }
    }

    fun shareGeneratedImage(filePath: String, caption: String = "", targetPackage: String? = null) {
        val ok = GeneratedImageHelper.shareImageFile(
            context = getApplication(),
            filePath = filePath,
            caption = caption,
            targetPackage = targetPackage
        )
        if (!ok) {
            _bannerMessage.value = "ছবিটি শেয়ার করা যায়নি — ফাইলটি পাওয়া যাচ্ছে না।"
        }
    }

    fun addAskAiAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        val existing = _askAiState.value.pendingAttachments
        val remainingSlots = (4 - existing.size).coerceAtLeast(0)
        if (remainingSlots == 0) {
            _askAiState.update { it.copy(errorText = "একসাথে সর্বোচ্চ ৪টি ছবি/ভিডিও/ফাইল যুক্ত করা যাবে।") }
            return
        }
        val newlyAdded = uris.take(remainingSlots).mapNotNull { uri ->
            runCatching { MediaAttachmentHelper.inspectUri(ctx, uri) }.getOrNull()
        }
        if (newlyAdded.isNotEmpty()) {
            _askAiState.update {
                it.copy(
                    pendingAttachments = it.pendingAttachments + newlyAdded,
                    errorText = null
                )
            }
        }
    }

    fun removeAskAiAttachment(attachmentId: String) {
        _askAiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == attachmentId })
        }
    }

    fun sendAskAiPrompt(promptText: String) {
        val clean = promptText.trim()
        val pending = _askAiState.value.pendingAttachments
        if ((clean.isEmpty() && pending.isEmpty()) || _askAiState.value.isGenerating) return

        val currentSettings = settings.value
        if (!currentSettings.hasAnyApiKey) {
            _askAiState.update {
                it.copy(errorText = "Add at least one Gemini API Key in Settings to use Ask AI.")
            }
            return
        }

        val effectivePrompt = clean.ifBlank {
            "এই ছবি/ভিডিও/ফাইলটি ভালোভাবে বিশ্লেষণ করে বাংলায় পরিষ্কারভাবে উত্তর দাও।"
        }
        val shouldGenerateImage = pending.isEmpty() && (
            _askAiState.value.imageModeEnabled ||
                GeneratedImageHelper.isImageGenerationRequest(effectivePrompt)
            )

        val userMsg = AskAiMessage(
            id = chatIdCounter.getAndIncrement(),
            isUser = true,
            text = effectivePrompt,
            attachments = pending,
            timestamp = PilotStateHub.formatNow()
        )
        val updatedHistory = _askAiState.value.messages + userMsg
        prefs.saveAskAiHistory(updatedHistory)
        viewModelScope.launch { cloudRepo.saveAskAiMessage(userMsg) }
        _askAiState.update {
            it.copy(
                messages = updatedHistory,
                pendingAttachments = emptyList(),
                isGenerating = true,
                attachmentStatusText = when {
                    shouldGenerateImage -> "🎨 AI দিয়ে ছবি তৈরি হচ্ছে..."
                    pending.isNotEmpty() -> "Preparing ${pending.size} file(s) / media for Gemini..."
                    else -> null
                },
                errorText = null
            )
        }

        val searchEnabled = _askAiState.value.searchGroundingEnabled
        viewModelScope.launch {
            if (shouldGenerateImage) {
                val imgResult = GeminiRestClient.generateImage(
                    apiKey = currentSettings.effectiveApiKey,
                    prompt = effectivePrompt
                )
                imgResult.onSuccess { gen ->
                    val savedFile = GeneratedImageHelper.saveGeneratedBytesToAppFile(
                        context = getApplication(),
                        bytes = gen.imageBytes,
                        mimeType = gen.mimeType
                    )
                    val galleryOk = GeneratedImageHelper.saveImageFileToGallery(
                        context = getApplication(),
                        filePath = savedFile.absolutePath,
                        displayLabel = "LivePilot_AI"
                    ).isSuccess
                    val modelBadge = if (!gen.keyLabelUsed.isNullOrBlank() && settings.value.apiKeys.size > 1) {
                        "${gen.modelUsed} • ${gen.keyLabelUsed}"
                    } else {
                        gen.modelUsed
                    }
                    val captionText = gen.captionText.ifBlank {
                        if (galleryOk) {
                            "আপনার প্রম্পট অনুযায়ী ছবি তৈরি হয়ে গেছে এবং গ্যালারিতে (Pictures/LivePilot AI) সেভ করা হয়েছে।"
                        } else {
                            "আপনার প্রম্পট অনুযায়ী ছবি তৈরি হয়ে গেছে।"
                        }
                    }
                    val aiMsg = AskAiMessage(
                        id = chatIdCounter.getAndIncrement(),
                        isUser = false,
                        text = captionText,
                        modelUsed = modelBadge,
                        generatedImagePath = savedFile.absolutePath,
                        timestamp = PilotStateHub.formatNow()
                    )
                    val finalHistory = _askAiState.value.messages + aiMsg
                    prefs.saveAskAiHistory(finalHistory)
                    launch { cloudRepo.saveAskAiMessage(aiMsg) }
                    _askAiState.update {
                        it.copy(
                            messages = finalHistory,
                            isGenerating = false,
                            attachmentStatusText = null,
                            errorText = null
                        )
                    }
                }.onFailure { err ->
                    _askAiState.update {
                        it.copy(
                            isGenerating = false,
                            attachmentStatusText = null,
                            errorText = err.message ?: "ছবি তৈরি করা যায়নি। API Key কোটা চেক করে আবার চেষ্টা করুন।"
                        )
                    }
                }
                return@launch
            }

            val prepared = mutableListOf<PreparedAttachment>()
            for (meta in pending) {
                val prepResult = MediaAttachmentHelper.prepareForGemini(getApplication(), meta)
                val item = prepResult.getOrElse { err ->
                    _askAiState.update {
                        it.copy(
                            isGenerating = false,
                            attachmentStatusText = null,
                            errorText = err.message ?: "Failed to read attachment '${meta.displayName}'."
                        )
                    }
                    return@launch
                }
                prepared.add(item)
            }

            if (prepared.any { it.rawBytesForUpload != null }) {
                _askAiState.update {
                    it.copy(attachmentStatusText = "Uploading large video/file to Gemini Files API...")
                }
            } else if (prepared.isNotEmpty()) {
                _askAiState.update {
                    it.copy(attachmentStatusText = "Analyzing attached media with ${currentSettings.textModel}...")
                }
            }

            val result = GeminiRestClient.generateChatTurn(
                apiKey = currentSettings.effectiveApiKey,
                modelId = currentSettings.textModel,
                history = updatedHistory,
                enableGoogleSearch = searchEnabled,
                preparedAttachments = prepared
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
                launch { cloudRepo.saveAskAiMessage(aiMsg) }
                _askAiState.update {
                    it.copy(
                        messages = finalHistory,
                        isGenerating = false,
                        attachmentStatusText = null,
                        errorText = null
                    )
                }
            }.onFailure { err ->
                _askAiState.update {
                    it.copy(
                        isGenerating = false,
                        attachmentStatusText = null,
                        errorText = err.message ?: "Request failed. Check API keys or select another text model in Settings."
                    )
                }
            }
        }
    }

    fun clearAskAiThread() {
        prefs.saveAskAiHistory(emptyList())
        viewModelScope.launch { cloudRepo.clearAskAiMessages() }
        _askAiState.update {
            it.copy(
                messages = emptyList(),
                pendingAttachments = emptyList(),
                attachmentStatusText = null,
                errorText = null
            )
        }
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

    fun sendOwnerSensitiveAnswer(record: IncomingMessageRecord, ownerAnswer: String) {
        val clean = ownerAnswer.trim()
        if (clean.isBlank()) return
        val handle = PilotStateHub.getNotificationHandleById(record.id)
        val directAction = handle?.replyAction
        if (directAction != null) {
            val sent = IncomingMessageListener.sendDirectReply(
                context = getApplication(),
                action = directAction,
                replyText = clean,
                packageName = record.packageName,
                senderName = record.senderName
            )
            if (sent) {
                PilotStateHub.markSensitiveMessageResolved(record.id, clean)
                ChatModerationEngine.markOwnerRespondedForSender(
                    IncomingMessageListener.conversationKey(record.packageName, record.senderName)
                )
                _bannerMessage.value = "✓ ${record.senderName}-কে আপনার উত্তর পাঠানো হয়েছে: \"$clean\""
                return
            }
        }

        // Visual Accessibility fallback if Direct Reply handle expired or is unavailable
        val svc = PilotAccessibilityService.instance
        if (svc != null) {
            viewModelScope.launch {
                IncomingMessageListener.registerSentReply(record.packageName, record.senderName, clean)
                svc.openNotificationChat(record.senderName)
                delay(900L)
                val visualRes = svc.typeText(
                    index = -1,
                    x = 0,
                    y = 0,
                    text = clean,
                    pressSend = true
                )
                if (visualRes.optString("status") == "ok") {
                    PilotStateHub.markSensitiveMessageResolved(record.id, clean)
                    ChatModerationEngine.markOwnerRespondedForSender(
                        IncomingMessageListener.conversationKey(record.packageName, record.senderName)
                    )
                    _bannerMessage.value = "✓ ${record.senderName}-কে চ্যাট খুলে উত্তর পাঠানো হয়েছে।"
                } else {
                    _bannerMessage.value = "Direct Reply পাওয়া যায়নি; Open Chat চাপ দিয়ে উত্তর পাঠান।"
                }
            }
        } else {
            _bannerMessage.value = "Direct Reply পাওয়া যায়নি; Open Chat চাপ দিয়ে ${record.senderName}-কে উত্তর পাঠান।"
        }
    }

    fun sendTemplateDirectReply(record: IncomingMessageRecord, replyText: String) {
        val handle = PilotStateHub.getNotificationHandleById(record.id)
        val directAction = handle?.replyAction
        if (directAction != null) {
            val sent = IncomingMessageListener.sendDirectReply(
                context = getApplication(),
                action = directAction,
                replyText = replyText,
                packageName = record.packageName,
                senderName = record.senderName
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
                        context = getApplication(),
                        action = directAction,
                        replyText = replyText,
                        packageName = record.packageName,
                        senderName = record.senderName
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

package com.example.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PilotPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoLocalDeviceMode(): Boolean =
        prefs.getBoolean(KEY_AUTO_LOCAL_DEVICE_MODE, false)

    fun setAutoLocalDeviceMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOCAL_DEVICE_MODE, enabled).apply()
    }

    fun loadSettings(): PilotSettings {
        val enabledNames = prefs.getStringSet(KEY_ENABLED_APPS, null)
        val enabledApps = if (enabledNames == null) {
            MessagingApp.entries.toSet()
        } else {
            enabledNames.mapNotNull { name ->
                runCatching { MessagingApp.valueOf(name) }.getOrNull()
            }.toSet()
        }

        val autoChatMode = runCatching {
            AutoChatMode.valueOf(
                prefs.getString(KEY_AUTO_CHAT_MODE, AutoChatMode.LIVE_VOICE_ALERT.name)
                    ?: AutoChatMode.LIVE_VOICE_ALERT.name
            )
        }.getOrDefault(AutoChatMode.LIVE_VOICE_ALERT)

        val autoReplyDelivery = runCatching {
            AutoReplyDelivery.valueOf(
                prefs.getString(KEY_AUTO_REPLY_DELIVERY, AutoReplyDelivery.SILENT_DIRECT_REPLY.name)
                    ?: AutoReplyDelivery.SILENT_DIRECT_REPLY.name
            )
        }.getOrDefault(AutoReplyDelivery.SILENT_DIRECT_REPLY)

        val themeMode = runCatching {
            AppThemeMode.valueOf(
                prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
                    ?: AppThemeMode.SYSTEM.name
            )
        }.getOrDefault(AppThemeMode.SYSTEM)

        val voiceLanguage = runCatching {
            VoiceLanguage.valueOf(
                prefs.getString(KEY_VOICE_LANGUAGE, VoiceLanguage.BENGALI_BD.name)
                    ?: VoiceLanguage.BENGALI_BD.name
            )
        }.getOrDefault(VoiceLanguage.BENGALI_BD)

        val legacySingleKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        val parsedKeys = loadApiKeysJson()
        val rawMigratedKeys = if (parsedKeys.isEmpty() && legacySingleKey.isNotBlank()) {
            listOf(
                GeminiApiKeyEntry(
                    id = "key_primary",
                    label = "Key 1",
                    key = legacySingleKey,
                    enabled = true
                )
            )
        } else {
            parsedKeys
        }
        val migratedKeys = if (rawMigratedKeys.count { it.enabled && it.key.isNotBlank() } == 1) {
            rawMigratedKeys.map { entry ->
                if (!entry.isInvalid && entry.cooldownUntilMs > 0L) {
                    entry.copy(cooldownUntilMs = 0L, lastError = null)
                } else {
                    entry
                }
            }
        } else {
            rawMigratedKeys
        }
        val savedActiveId = prefs.getString(KEY_ACTIVE_API_KEY_ID, null)
        val resolvedActiveId = when {
            migratedKeys.any { it.id == savedActiveId } -> savedActiveId
            else -> migratedKeys.firstOrNull { it.enabled }?.id ?: migratedKeys.firstOrNull()?.id
        }
        val effectiveKey = migratedKeys.firstOrNull { it.id == resolvedActiveId }?.key
            ?: migratedKeys.firstOrNull { it.enabled }?.key
            ?: legacySingleKey

        val rawLiveModel = prefs.getString(KEY_LIVE_MODEL, PilotSettings.DEFAULT_LIVE_MODEL)
            .orEmpty()
            .ifBlank { PilotSettings.DEFAULT_LIVE_MODEL }
        val migratedLiveModel = if (rawLiveModel in LEGACY_PLACEHOLDER_LIVE_MODELS) {
            PilotSettings.DEFAULT_LIVE_MODEL
        } else {
            rawLiveModel
        }

        val rawTextModel = prefs.getString(KEY_TEXT_MODEL, PilotSettings.DEFAULT_TEXT_MODEL)
            .orEmpty()
            .ifBlank { PilotSettings.DEFAULT_TEXT_MODEL }
        val migratedTextModel = if (rawTextModel in LEGACY_PLACEHOLDER_TEXT_MODELS) {
            PilotSettings.DEFAULT_TEXT_MODEL
        } else {
            rawTextModel
        }

        val rawAgentModel = prefs.getString(KEY_AGENT_MODEL, null)
            .orEmpty()
            .ifBlank { migratedTextModel }
        val migratedAgentModel = if (rawAgentModel in LEGACY_PLACEHOLDER_TEXT_MODELS) {
            PilotSettings.DEFAULT_AGENT_MODEL
        } else {
            rawAgentModel
        }

        val deletedModelsSet = prefs.getStringSet(KEY_DELETED_MODELS, emptySet()).orEmpty()
        val deletedModels = deletedModelsSet.map { it.trim() }.filter { it.isNotBlank() }.sorted()

        return PilotSettings(
            apiKey = effectiveKey,
            apiKeys = migratedKeys,
            activeApiKeyId = resolvedActiveId,
            autoRotateApiKeys = prefs.getBoolean(KEY_AUTO_ROTATE_API_KEYS, true),
            liveModel = migratedLiveModel,
            agentModel = migratedAgentModel,
            textModel = migratedTextModel,
            deletedModels = deletedModels,
            verifyTaskCompletion = prefs.getBoolean(KEY_VERIFY_TASK_COMPLETION, true),
            voiceName = prefs.getString(KEY_VOICE_NAME, PilotSettings.DEFAULT_VOICE)
                .orEmpty()
                .ifBlank { PilotSettings.DEFAULT_VOICE },
            voiceLanguage = voiceLanguage,
            speakerphoneOn = prefs.getBoolean(KEY_SPEAKERPHONE, true),
            autoVisionDefaultOn = prefs.getBoolean(KEY_AUTO_VISION_DEFAULT, false),
            autoVisionIntervalSec = prefs.getInt(KEY_AUTO_VISION_INTERVAL, 4).coerceIn(2, 10),
            showTouchVisualizer = prefs.getBoolean(KEY_SHOW_TOUCH_VISUALIZER, true),
            showFloatingCaptions = prefs.getBoolean(KEY_SHOW_FLOATING_CAPTIONS, true),
            themeMode = themeMode,
            customVoiceInstructions = prefs.getString(KEY_CUSTOM_VOICE_INSTRUCTIONS, "").orEmpty(),
            autoChatMode = autoChatMode,
            autoReplyDelivery = autoReplyDelivery,
            autoReplyPersona = prefs.getString(
                KEY_AUTO_REPLY_PERSONA,
                PilotSettings.DEFAULT_AUTO_REPLY_PERSONA
            ).orEmpty().ifBlank { PilotSettings.DEFAULT_AUTO_REPLY_PERSONA },
            sensitiveQuestionGuardEnabled = prefs.getBoolean(KEY_SENSITIVE_GUARD_ENABLED, true),
            handsFreeVoiceEscalationEnabled = prefs.getBoolean(KEY_HANDS_FREE_VOICE_ESCALATION, true),
            enabledMessagingApps = enabledApps
        )
    }

    fun saveSettings(settings: PilotSettings) {
        val syncedKeys = if (settings.apiKeys.isEmpty() && settings.apiKey.isNotBlank()) {
            listOf(
                GeminiApiKeyEntry(
                    id = "key_primary",
                    label = "Key 1",
                    key = settings.apiKey.trim(),
                    enabled = true
                )
            )
        } else {
            settings.apiKeys
        }
        val activeId = settings.activeApiKeyId?.takeIf { id -> syncedKeys.any { it.id == id } }
            ?: syncedKeys.firstOrNull { it.enabled }?.id
            ?: syncedKeys.firstOrNull()?.id
        val mirrorKey = syncedKeys.firstOrNull { it.id == activeId }?.key
            ?: syncedKeys.firstOrNull { it.enabled }?.key
            ?: settings.apiKey.trim()

        prefs.edit()
            .putString(KEY_API_KEY, mirrorKey)
            .putString(KEY_API_KEYS_JSON, serializeApiKeysJson(syncedKeys))
            .putString(KEY_ACTIVE_API_KEY_ID, activeId)
            .putBoolean(KEY_AUTO_ROTATE_API_KEYS, settings.autoRotateApiKeys)
            .putString(KEY_LIVE_MODEL, settings.liveModel.trim().removePrefix("models/"))
            .putString(KEY_AGENT_MODEL, settings.agentModel.trim().removePrefix("models/"))
            .putString(KEY_TEXT_MODEL, settings.textModel.trim().removePrefix("models/"))
            .putStringSet(KEY_DELETED_MODELS, settings.deletedModels.map { it.trim() }.filter { it.isNotBlank() }.toSet())
            .putBoolean(KEY_VERIFY_TASK_COMPLETION, settings.verifyTaskCompletion)
            .putString(KEY_VOICE_NAME, settings.voiceName.trim())
            .putString(KEY_VOICE_LANGUAGE, settings.voiceLanguage.name)
            .putBoolean(KEY_SPEAKERPHONE, settings.speakerphoneOn)
            .putBoolean(KEY_AUTO_VISION_DEFAULT, settings.autoVisionDefaultOn)
            .putInt(KEY_AUTO_VISION_INTERVAL, settings.autoVisionIntervalSec.coerceIn(2, 10))
            .putBoolean(KEY_SHOW_TOUCH_VISUALIZER, settings.showTouchVisualizer)
            .putBoolean(KEY_SHOW_FLOATING_CAPTIONS, settings.showFloatingCaptions)
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putString(KEY_CUSTOM_VOICE_INSTRUCTIONS, settings.customVoiceInstructions.trim())
            .putString(KEY_AUTO_CHAT_MODE, settings.autoChatMode.name)
            .putString(KEY_AUTO_REPLY_DELIVERY, settings.autoReplyDelivery.name)
            .putString(KEY_AUTO_REPLY_PERSONA, settings.autoReplyPersona.trim())
            .putBoolean(KEY_SENSITIVE_GUARD_ENABLED, settings.sensitiveQuestionGuardEnabled)
            .putBoolean(KEY_HANDS_FREE_VOICE_ESCALATION, settings.handsFreeVoiceEscalationEnabled)
            .putStringSet(KEY_ENABLED_APPS, settings.enabledMessagingApps.map { it.name }.toSet())
            .apply()
    }

    private fun loadApiKeysJson(): List<GeminiApiKeyEntry> {
        val raw = prefs.getString(KEY_API_KEYS_JSON, null)
        return parseApiKeysJson(raw)
    }

    fun loadPersonalData(): List<PersonalDataItem> {
        val raw = prefs.getString(KEY_PERSONAL_DATA_JSON, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    val content = obj.optString("content", "").trim()
                    if (title.isBlank() || content.isBlank()) continue
                    add(
                        PersonalDataItem(
                            id = obj.optString("id", "pd_$i").ifBlank { "pd_$i" },
                            title = title,
                            content = content,
                            category = obj.optString("category", "পরিচয়").ifBlank { "পরিচয়" },
                            shareInAutoReply = obj.optBoolean("shareInAutoReply", true),
                            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun savePersonalData(items: List<PersonalDataItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("content", item.content)
                    .put("category", item.category)
                    .put("shareInAutoReply", item.shareInAutoReply)
                    .put("updatedAtMs", item.updatedAtMs)
            )
        }
        prefs.edit().putString(KEY_PERSONAL_DATA_JSON, arr.toString()).apply()
    }
        // Helper methods moved to companion object for reuse by CloudSyncRepository.
    private fun serializeApiKeysJson(keys: List<GeminiApiKeyEntry>): String =
        Companion.serializeApiKeysJson(keys)

    fun loadRoutines(): List<PilotRoutine> {
        val raw = prefs.getString(KEY_CUSTOM_ROUTINES, null)
        val custom = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val accent = runCatching {
                            RoutineAccent.valueOf(obj.optString("accent", RoutineAccent.COBALT.name))
                        }.getOrDefault(RoutineAccent.COBALT)
                        add(
                            PilotRoutine(
                                id = obj.optString("id"),
                                title = obj.optString("title"),
                                subtitle = obj.optString("subtitle"),
                                prompt = obj.optString("prompt"),
                                accent = accent,
                                isBuiltIn = false
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        return custom + PilotSettings.DEFAULT_ROUTINES
    }

    fun saveCustomRoutines(allRoutines: List<PilotRoutine>) {
        val arr = JSONArray()
        allRoutines.filter { !it.isBuiltIn }.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("subtitle", r.subtitle)
                    .put("prompt", r.prompt)
                    .put("accent", r.accent.name)
            )
        }
        prefs.edit().putString(KEY_CUSTOM_ROUTINES, arr.toString()).apply()
    }

    fun loadAskAiHistory(): List<AskAiMessage> {
        val raw = prefs.getString(KEY_ASK_AI_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val citationsArr = obj.optJSONArray("citations") ?: JSONArray()
                    val citations = buildList {
                        for (c in 0 until citationsArr.length()) {
                            val cObj = citationsArr.optJSONObject(c) ?: continue
                            add(
                                WebCitation(
                                    title = cObj.optString("title"),
                                    url = cObj.optString("url")
                                )
                            )
                        }
                    }
                    val attachmentsArr = obj.optJSONArray("attachments") ?: JSONArray()
                    val attachments = buildList {
                        for (a in 0 until attachmentsArr.length()) {
                            val aObj = attachmentsArr.optJSONObject(a) ?: continue
                            val kind = runCatching {
                                AttachmentKind.valueOf(aObj.optString("kind", AttachmentKind.OTHER.name))
                            }.getOrDefault(AttachmentKind.OTHER)
                            add(
                                ChatAttachmentMeta(
                                    id = aObj.optString("id", "att_$a"),
                                    displayName = aObj.optString("displayName", "file"),
                                    mimeType = aObj.optString("mimeType", "application/octet-stream"),
                                    sizeBytes = aObj.optLong("sizeBytes", 0L),
                                    kind = kind,
                                    uriString = aObj.optString("uriString", "")
                                )
                            )
                        }
                    }
                    add(
                        AskAiMessage(
                            id = obj.optLong("id"),
                            isUser = obj.optBoolean("isUser"),
                            text = obj.optString("text"),
                            citations = citations,
                            attachments = attachments,
                            modelUsed = obj.optString("modelUsed").ifBlank { null },
                            timestamp = obj.optString("timestamp")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveAskAiHistory(messages: List<AskAiMessage>) {
        val arr = JSONArray()
        messages.takeLast(30).forEach { msg ->
            val citationsArr = JSONArray()
            msg.citations.forEach { cit ->
                citationsArr.put(JSONObject().put("title", cit.title).put("url", cit.url))
            }
            val attachmentsArr = JSONArray()
            msg.attachments.forEach { att ->
                attachmentsArr.put(
                    JSONObject()
                        .put("id", att.id)
                        .put("displayName", att.displayName)
                        .put("mimeType", att.mimeType)
                        .put("sizeBytes", att.sizeBytes)
                        .put("kind", att.kind.name)
                        .put("uriString", att.uriString)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", msg.id)
                    .put("isUser", msg.isUser)
                    .put("text", msg.text)
                    .put("citations", citationsArr)
                    .put("attachments", attachmentsArr)
                    .put("modelUsed", msg.modelUsed ?: "")
                    .put("timestamp", msg.timestamp)
            )
        }
        prefs.edit().putString(KEY_ASK_AI_HISTORY, arr.toString()).apply()
    }

    fun exportDeviceBackupJson(deviceId: String, deviceLabel: String): String {
        val root = JSONObject()
        root.put("backupVersion", 1)
        root.put("exportedAtMs", System.currentTimeMillis())
        root.put("deviceId", deviceId)
        root.put("deviceLabel", deviceLabel)

        val settings = loadSettings()
        val settingsObj = JSONObject()
            .put("apiKeysJson", serializeApiKeysJson(settings.apiKeys))
            .put("activeApiKeyId", settings.activeApiKeyId.orEmpty())
            .put("autoRotateApiKeys", settings.autoRotateApiKeys)
            .put("liveModel", settings.liveModel)
            .put("agentModel", settings.agentModel)
            .put("textModel", settings.textModel)
            .put("deletedModels", JSONArray(settings.deletedModels))
            .put("verifyTaskCompletion", settings.verifyTaskCompletion)
            .put("voiceName", settings.voiceName)
            .put("voiceLanguage", settings.voiceLanguage.name)
            .put("speakerphoneOn", settings.speakerphoneOn)
            .put("autoVisionDefaultOn", settings.autoVisionDefaultOn)
            .put("autoVisionIntervalSec", settings.autoVisionIntervalSec)
            .put("showTouchVisualizer", settings.showTouchVisualizer)
            .put("showFloatingCaptions", settings.showFloatingCaptions)
            .put("themeMode", settings.themeMode.name)
            .put("customVoiceInstructions", settings.customVoiceInstructions)
            .put("autoChatMode", settings.autoChatMode.name)
            .put("autoReplyDelivery", settings.autoReplyDelivery.name)
            .put("autoReplyPersona", settings.autoReplyPersona)
            .put("sensitiveQuestionGuardEnabled", settings.sensitiveQuestionGuardEnabled)
            .put("handsFreeVoiceEscalationEnabled", settings.handsFreeVoiceEscalationEnabled)
            .put("enabledMessagingApps", JSONArray(settings.enabledMessagingApps.map { it.name }))

        root.put("settings", settingsObj)
        root.put("personalDataJson", prefs.getString(KEY_PERSONAL_DATA_JSON, "[]") ?: "[]")
        root.put("customRoutinesJson", prefs.getString(KEY_CUSTOM_ROUTINES, "[]") ?: "[]")
        root.put("askAiHistoryJson", prefs.getString(KEY_ASK_AI_HISTORY, "[]") ?: "[]")
        return root.toString(2)
    }

    fun importDeviceBackupJson(rawJson: String): Result<String> {
        return runCatching {
            val root = JSONObject(rawJson)
            val sObj = root.optJSONObject("settings")
                ?: throw IllegalArgumentException("ব্যাকআপ ফাইলে settings ডাটা পাওয়া যায়নি।")

            val parsedKeys = parseApiKeysJson(sObj.optString("apiKeysJson", "[]"))
            val activeId = sObj.optString("activeApiKeyId", "").ifBlank { null }
            val resolvedActive = activeId?.takeIf { id -> parsedKeys.any { it.id == id } }
                ?: parsedKeys.firstOrNull { it.enabled }?.id
                ?: parsedKeys.firstOrNull()?.id
            val mirrorKey = parsedKeys.firstOrNull { it.id == resolvedActive }?.key
                ?: parsedKeys.firstOrNull { it.enabled }?.key
                ?: ""

            val deletedArr = sObj.optJSONArray("deletedModels") ?: JSONArray()
            val deletedModels = buildList {
                for (i in 0 until deletedArr.length()) {
                    val m = deletedArr.optString(i, "").trim()
                    if (m.isNotBlank()) add(m)
                }
            }.distinct()

            val appsArr = sObj.optJSONArray("enabledMessagingApps") ?: JSONArray()
            val enabledApps = buildSet {
                for (i in 0 until appsArr.length()) {
                    runCatching { MessagingApp.valueOf(appsArr.optString(i, "")) }.getOrNull()?.let { add(it) }
                }
            }.ifEmpty { MessagingApp.entries.toSet() }

            val restoredSettings = PilotSettings(
                apiKey = mirrorKey,
                apiKeys = parsedKeys,
                activeApiKeyId = resolvedActive,
                autoRotateApiKeys = sObj.optBoolean("autoRotateApiKeys", true),
                liveModel = sObj.optString("liveModel", PilotSettings.DEFAULT_LIVE_MODEL)
                    .ifBlank { PilotSettings.DEFAULT_LIVE_MODEL },
                agentModel = sObj.optString("agentModel", PilotSettings.DEFAULT_AGENT_MODEL)
                    .ifBlank { PilotSettings.DEFAULT_AGENT_MODEL },
                textModel = sObj.optString("textModel", PilotSettings.DEFAULT_TEXT_MODEL)
                    .ifBlank { PilotSettings.DEFAULT_TEXT_MODEL },
                deletedModels = deletedModels,
                verifyTaskCompletion = sObj.optBoolean("verifyTaskCompletion", true),
                voiceName = sObj.optString("voiceName", PilotSettings.DEFAULT_VOICE)
                    .ifBlank { PilotSettings.DEFAULT_VOICE },
                voiceLanguage = runCatching {
                    VoiceLanguage.valueOf(sObj.optString("voiceLanguage", VoiceLanguage.BENGALI_BD.name))
                }.getOrDefault(VoiceLanguage.BENGALI_BD),
                speakerphoneOn = sObj.optBoolean("speakerphoneOn", true),
                autoVisionDefaultOn = sObj.optBoolean("autoVisionDefaultOn", false),
                autoVisionIntervalSec = sObj.optInt("autoVisionIntervalSec", 4).coerceIn(2, 10),
                showTouchVisualizer = sObj.optBoolean("showTouchVisualizer", true),
                showFloatingCaptions = sObj.optBoolean("showFloatingCaptions", true),
                themeMode = runCatching {
                    AppThemeMode.valueOf(sObj.optString("themeMode", AppThemeMode.SYSTEM.name))
                }.getOrDefault(AppThemeMode.SYSTEM),
                customVoiceInstructions = sObj.optString("customVoiceInstructions", ""),
                autoChatMode = runCatching {
                    AutoChatMode.valueOf(sObj.optString("autoChatMode", AutoChatMode.LIVE_VOICE_ALERT.name))
                }.getOrDefault(AutoChatMode.LIVE_VOICE_ALERT),
                autoReplyDelivery = runCatching {
                    AutoReplyDelivery.valueOf(sObj.optString("autoReplyDelivery", AutoReplyDelivery.SILENT_DIRECT_REPLY.name))
                }.getOrDefault(AutoReplyDelivery.SILENT_DIRECT_REPLY),
                autoReplyPersona = sObj.optString("autoReplyPersona", PilotSettings.DEFAULT_AUTO_REPLY_PERSONA)
                    .ifBlank { PilotSettings.DEFAULT_AUTO_REPLY_PERSONA },
                sensitiveQuestionGuardEnabled = sObj.optBoolean("sensitiveQuestionGuardEnabled", true),
                handsFreeVoiceEscalationEnabled = sObj.optBoolean("handsFreeVoiceEscalationEnabled", true),
                enabledMessagingApps = enabledApps
            )
            saveSettings(restoredSettings)

            root.optString("personalDataJson", "").takeIf { it.isNotBlank() }?.let { rawPd ->
                JSONArray(rawPd) // validate JSON array
                prefs.edit().putString(KEY_PERSONAL_DATA_JSON, rawPd).apply()
            }
            root.optString("customRoutinesJson", "").takeIf { it.isNotBlank() }?.let { rawRt ->
                JSONArray(rawRt)
                prefs.edit().putString(KEY_CUSTOM_ROUTINES, rawRt).apply()
            }
            root.optString("askAiHistoryJson", "").takeIf { it.isNotBlank() }?.let { rawChat ->
                JSONArray(rawChat)
                prefs.edit().putString(KEY_ASK_AI_HISTORY, rawChat).apply()
            }

            val restoredLabel = root.optString("deviceLabel", "ডিভাইস ব্যাকআপ").ifBlank { "ডিভাইস ব্যাকআপ" }
            val pdCount = loadPersonalData().size
            "✓ ব্যাকআপ রিস্টোর সফল ($restoredLabel • ${parsedKeys.size} API Key, $pdCount আমার ডাটা)।"
        }
    }

    companion object {
        private const val PREFS_NAME = "livepilot_ai_secure_prefs"
        private const val KEY_AUTO_LOCAL_DEVICE_MODE = "auto_local_device_mode_enabled"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_API_KEYS_JSON = "gemini_api_keys_json"
        private const val KEY_ACTIVE_API_KEY_ID = "gemini_active_api_key_id"
        private const val KEY_AUTO_ROTATE_API_KEYS = "gemini_auto_rotate_api_keys"
        private const val KEY_LIVE_MODEL = "live_model_id"
        private const val KEY_AGENT_MODEL = "agent_model_id"
        private const val KEY_TEXT_MODEL = "text_model_id"
        private const val KEY_VERIFY_TASK_COMPLETION = "verify_task_completion"
        private const val KEY_VOICE_NAME = "live_voice_name"
        private const val KEY_VOICE_LANGUAGE = "live_voice_language"
        private const val KEY_SPEAKERPHONE = "speakerphone_on"
        private const val KEY_AUTO_VISION_DEFAULT = "auto_vision_default"
        private const val KEY_AUTO_VISION_INTERVAL = "auto_vision_interval"
        private const val KEY_SHOW_TOUCH_VISUALIZER = "show_touch_visualizer"
        private const val KEY_SHOW_FLOATING_CAPTIONS = "show_floating_captions"
        private const val KEY_THEME_MODE = "app_theme_mode"
        private const val KEY_CUSTOM_VOICE_INSTRUCTIONS = "custom_voice_instructions"
        private const val KEY_AUTO_CHAT_MODE = "auto_chat_mode"
        private const val KEY_AUTO_REPLY_DELIVERY = "auto_reply_delivery"
        private const val KEY_AUTO_REPLY_PERSONA = "auto_reply_persona"
        private const val KEY_SENSITIVE_GUARD_ENABLED = "sensitive_question_guard_enabled"
        private const val KEY_HANDS_FREE_VOICE_ESCALATION = "hands_free_voice_escalation_enabled"
        private const val KEY_DELETED_MODELS = "deleted_models_set"
        private const val KEY_PERSONAL_DATA_JSON = "personal_data_json"
        private const val KEY_ENABLED_APPS = "enabled_messaging_apps"
        private const val KEY_ASK_AI_HISTORY = "ask_ai_history_json"
        private const val KEY_CUSTOM_ROUTINES = "custom_routines_json"

        private val LEGACY_PLACEHOLDER_LIVE_MODELS = setOf("gemini-3.8-live")
        private val LEGACY_PLACEHOLDER_TEXT_MODELS = setOf(
            "gemini-4-hydrogen",
            "gemini-4-carbon"
        )

        fun parseApiKeysJson(raw: String?): List<GeminiApiKeyEntry> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val key = obj.optString("key", "").trim()
                        if (key.isBlank()) continue
                        add(
                            GeminiApiKeyEntry(
                                id = obj.optString("id", "key_$i").ifBlank { "key_$i" },
                                label = obj.optString("label", "Key ${i + 1}").ifBlank { "Key ${i + 1}" },
                                key = key,
                                enabled = obj.optBoolean("enabled", true),
                                cooldownUntilMs = obj.optLong("cooldownUntilMs", 0L),
                                isInvalid = obj.optBoolean("isInvalid", false),
                                lastError = obj.optString("lastError", "").ifBlank { null },
                                totalRequests = obj.optInt("totalRequests", 0),
                                lastUsedAtMs = obj.optLong("lastUsedAtMs", 0L)
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }

        fun serializeApiKeysJson(keys: List<GeminiApiKeyEntry>): String {
            val arr = JSONArray()
            keys.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("label", entry.label)
                        .put("key", entry.key.trim())
                        .put("enabled", entry.enabled)
                        .put("cooldownUntilMs", entry.cooldownUntilMs)
                        .put("isInvalid", entry.isInvalid)
                        .put("lastError", entry.lastError.orEmpty())
                        .put("totalRequests", entry.totalRequests)
                        .put("lastUsedAtMs", entry.lastUsedAtMs)
                )
            }
            return arr.toString()
        }
    }
}

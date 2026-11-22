package com.example.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PilotPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        val migratedKeys = if (parsedKeys.isEmpty() && legacySingleKey.isNotBlank()) {
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

        return PilotSettings(
            apiKey = effectiveKey,
            apiKeys = migratedKeys,
            activeApiKeyId = resolvedActiveId,
            autoRotateApiKeys = prefs.getBoolean(KEY_AUTO_ROTATE_API_KEYS, true),
            liveModel = migratedLiveModel,
            agentModel = migratedAgentModel,
            textModel = migratedTextModel,
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
            .putStringSet(KEY_ENABLED_APPS, settings.enabledMessagingApps.map { it.name }.toSet())
            .apply()
    }

    private fun loadApiKeysJson(): List<GeminiApiKeyEntry> {
        val raw = prefs.getString(KEY_API_KEYS_JSON, null)
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

    private fun serializeApiKeysJson(keys: List<GeminiApiKeyEntry>): String {
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
                    add(
                        AskAiMessage(
                            id = obj.optLong("id"),
                            isUser = obj.optBoolean("isUser"),
                            text = obj.optString("text"),
                            citations = citations,
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
            arr.put(
                JSONObject()
                    .put("id", msg.id)
                    .put("isUser", msg.isUser)
                    .put("text", msg.text)
                    .put("citations", citationsArr)
                    .put("modelUsed", msg.modelUsed ?: "")
                    .put("timestamp", msg.timestamp)
            )
        }
        prefs.edit().putString(KEY_ASK_AI_HISTORY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "livepilot_ai_secure_prefs"
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
        private const val KEY_ENABLED_APPS = "enabled_messaging_apps"
        private const val KEY_ASK_AI_HISTORY = "ask_ai_history_json"
        private const val KEY_CUSTOM_ROUTINES = "custom_routines_json"

        private val LEGACY_PLACEHOLDER_LIVE_MODELS = setOf("gemini-3.8-live")
        private val LEGACY_PLACEHOLDER_TEXT_MODELS = setOf(
            "gemini-4-hydrogen",
            "gemini-4-carbon"
        )
    }
}

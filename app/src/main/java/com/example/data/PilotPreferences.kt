package com.example.data

import android.content.Context
import android.content.SharedPreferences

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

        return PilotSettings(
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            liveModel = prefs.getString(KEY_LIVE_MODEL, PilotSettings.DEFAULT_LIVE_MODEL)
                .orEmpty()
                .ifBlank { PilotSettings.DEFAULT_LIVE_MODEL },
            textModel = prefs.getString(KEY_TEXT_MODEL, PilotSettings.DEFAULT_TEXT_MODEL)
                .orEmpty()
                .ifBlank { PilotSettings.DEFAULT_TEXT_MODEL },
            voiceName = prefs.getString(KEY_VOICE_NAME, PilotSettings.DEFAULT_VOICE)
                .orEmpty()
                .ifBlank { PilotSettings.DEFAULT_VOICE },
            speakerphoneOn = prefs.getBoolean(KEY_SPEAKERPHONE, true),
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
        prefs.edit()
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_LIVE_MODEL, settings.liveModel.trim().removePrefix("models/"))
            .putString(KEY_TEXT_MODEL, settings.textModel.trim().removePrefix("models/"))
            .putString(KEY_VOICE_NAME, settings.voiceName.trim())
            .putBoolean(KEY_SPEAKERPHONE, settings.speakerphoneOn)
            .putString(KEY_AUTO_CHAT_MODE, settings.autoChatMode.name)
            .putString(KEY_AUTO_REPLY_DELIVERY, settings.autoReplyDelivery.name)
            .putString(KEY_AUTO_REPLY_PERSONA, settings.autoReplyPersona.trim())
            .putStringSet(KEY_ENABLED_APPS, settings.enabledMessagingApps.map { it.name }.toSet())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "livepilot_ai_secure_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_LIVE_MODEL = "live_model_id"
        private const val KEY_TEXT_MODEL = "text_model_id"
        private const val KEY_VOICE_NAME = "live_voice_name"
        private const val KEY_SPEAKERPHONE = "speakerphone_on"
        private const val KEY_AUTO_CHAT_MODE = "auto_chat_mode"
        private const val KEY_AUTO_REPLY_DELIVERY = "auto_reply_delivery"
        private const val KEY_AUTO_REPLY_PERSONA = "auto_reply_persona"
        private const val KEY_ENABLED_APPS = "enabled_messaging_apps"
    }
}

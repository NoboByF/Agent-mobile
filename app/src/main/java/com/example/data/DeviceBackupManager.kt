package com.example.data

import com.example.firebase.DeviceProfileIdentity
import org.json.JSONArray
import org.json.JSONObject

data class DeviceBackupPayload(
    val sourceDeviceModel: String,
    val sourceDeviceId: String,
    val settings: PilotSettings,
    val personalData: List<PersonalDataItem>,
    val customRoutines: List<PilotRoutine>,
    val askAiMessages: List<AskAiMessage>
)

object DeviceBackupManager {

    private const val BACKUP_FORMAT_VERSION = 1

    fun exportToJson(
        deviceProfile: DeviceProfileIdentity,
        settings: PilotSettings,
        personalData: List<PersonalDataItem>,
        allRoutines: List<PilotRoutine>,
        askAiMessages: List<AskAiMessage>
    ): String {
        val root = JSONObject()
        root.put("formatVersion", BACKUP_FORMAT_VERSION)
        root.put("exportedAtMs", System.currentTimeMillis())
        root.put("deviceId", deviceProfile.deviceId)
        root.put("deviceModel", deviceProfile.displayLabel)

        val settingsObj = JSONObject().apply {
            put("apiKeysJson", PilotPreferences.serializeApiKeysJson(settings.apiKeys))
            put("activeApiKeyId", settings.activeApiKeyId.orEmpty())
            put("autoRotateApiKeys", settings.autoRotateApiKeys)
            put("liveModel", settings.liveModel)
            put("agentModel", settings.agentModel)
            put("textModel", settings.textModel)
            put("deletedModels", JSONArray(settings.deletedModels))
            put("verifyTaskCompletion", settings.verifyTaskCompletion)
            put("voiceName", settings.voiceName)
            put("voiceLanguage", settings.voiceLanguage.name)
            put("speakerphoneOn", settings.speakerphoneOn)
            put("autoVisionDefaultOn", settings.autoVisionDefaultOn)
            put("autoVisionIntervalSec", settings.autoVisionIntervalSec)
            put("showTouchVisualizer", settings.showTouchVisualizer)
            put("showFloatingCaptions", settings.showFloatingCaptions)
            put("themeMode", settings.themeMode.name)
            put("customVoiceInstructions", settings.customVoiceInstructions)
            put("autoChatMode", settings.autoChatMode.name)
            put("autoReplyDelivery", settings.autoReplyDelivery.name)
            put("autoReplyPersona", settings.autoReplyPersona)
            put("sensitiveQuestionGuardEnabled", settings.sensitiveQuestionGuardEnabled)
            put("handsFreeVoiceEscalationEnabled", settings.handsFreeVoiceEscalationEnabled)
            put("enabledMessagingApps", JSONArray(settings.enabledMessagingApps.map { it.name }))
        }
        root.put("settings", settingsObj)

        val personalArr = JSONArray()
        personalData.forEach { item ->
            personalArr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("content", item.content)
                    .put("category", item.category)
                    .put("shareInAutoReply", item.shareInAutoReply)
                    .put("updatedAtMs", item.updatedAtMs)
            )
        }
        root.put("personalData", personalArr)

        val routinesArr = JSONArray()
        allRoutines.filter { !it.isBuiltIn }.forEach { r ->
            routinesArr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("subtitle", r.subtitle)
                    .put("prompt", r.prompt)
                    .put("accent", r.accent.name)
            )
        }
        root.put("customRoutines", routinesArr)

        val chatArr = JSONArray()
        askAiMessages.takeLast(40).forEach { msg ->
            val citationsArr = JSONArray()
            msg.citations.forEach { c ->
                citationsArr.put(JSONObject().put("title", c.title).put("url", c.url))
            }
            chatArr.put(
                JSONObject()
                    .put("id", msg.id)
                    .put("isUser", msg.isUser)
                    .put("text", msg.text)
                    .put("modelUsed", msg.modelUsed.orEmpty())
                    .put("timestamp", msg.timestamp)
                    .put("citations", citationsArr)
            )
        }
        root.put("askAiMessages", chatArr)

        return root.toString(2)
    }

    fun parseFromJson(rawJson: String): Result<DeviceBackupPayload> {
        return runCatching {
            val root = JSONObject(rawJson)
            val sObj = root.optJSONObject("settings")
                ?: throw IllegalArgumentException("Invalid LivePilot backup file")

            val parsedKeys = PilotPreferences.parseApiKeysJson(sObj.optString("apiKeysJson", "[]"))
            val savedActive = sObj.optString("activeApiKeyId", "").ifBlank { null }
            val resolvedActive = savedActive?.takeIf { id -> parsedKeys.any { it.id == id } }
                ?: parsedKeys.firstOrNull { it.enabled }?.id
                ?: parsedKeys.firstOrNull()?.id
            val mirrorKey = parsedKeys.firstOrNull { it.id == resolvedActive }?.key
                ?: parsedKeys.firstOrNull { it.enabled }?.key
                ?: ""

            val deletedModels = buildList {
                val arr = sObj.optJSONArray("deletedModels")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optString(i, "").trim()
                        if (m.isNotBlank()) add(m)
                    }
                }
            }.distinct()

            val enabledApps = buildSet {
                val arr = sObj.optJSONArray("enabledMessagingApps")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        runCatching { MessagingApp.valueOf(arr.optString(i, "")) }.getOrNull()?.let { add(it) }
                    }
                }
            }.ifEmpty { MessagingApp.entries.toSet() }

            val settings = PilotSettings(
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

            val personalData = buildList {
                val arr = root.optJSONArray("personalData")
                if (arr != null) {
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
            }

            val customRoutines = buildList {
                val arr = root.optJSONArray("customRoutines")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val title = obj.optString("title", "").trim()
                        val prompt = obj.optString("prompt", "").trim()
                        if (title.isBlank() || prompt.isBlank()) continue
                        add(
                            PilotRoutine(
                                id = obj.optString("id", "routine_$i").ifBlank { "routine_$i" },
                                title = title,
                                subtitle = obj.optString("subtitle", ""),
                                prompt = prompt,
                                accent = runCatching {
                                    RoutineAccent.valueOf(obj.optString("accent", RoutineAccent.COBALT.name))
                                }.getOrDefault(RoutineAccent.COBALT),
                                isBuiltIn = false
                            )
                        )
                    }
                }
            }

            val askAiMessages = buildList {
                val arr = root.optJSONArray("askAiMessages")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val citations = buildList {
                            val cArr = obj.optJSONArray("citations")
                            if (cArr != null) {
                                for (j in 0 until cArr.length()) {
                                    val cObj = cArr.optJSONObject(j) ?: continue
                                    val url = cObj.optString("url", "")
                                    if (url.isNotBlank()) {
                                        add(WebCitation(title = cObj.optString("title", url), url = url))
                                    }
                                }
                            }
                        }
                        add(
                            AskAiMessage(
                                id = obj.optLong("id", (i + 1).toLong()),
                                isUser = obj.optBoolean("isUser", false),
                                text = obj.optString("text", ""),
                                citations = citations,
                                modelUsed = obj.optString("modelUsed", "").ifBlank { null },
                                timestamp = obj.optString("timestamp", "")
                            )
                        )
                    }
                }
            }

            DeviceBackupPayload(
                sourceDeviceModel = root.optString("deviceModel", "Unknown Device"),
                sourceDeviceId = root.optString("deviceId", ""),
                settings = settings,
                personalData = personalData,
                customRoutines = customRoutines,
                askAiMessages = askAiMessages
            )
        }
    }
}

package com.example.firebase

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class DeviceCloudConfigDoc(
    @DocumentId val id: String = "",
    val deviceId: String = "",
    val deviceModel: String = "",
    val deviceManufacturer: String = "",
    val apiKeysJson: String = "[]",
    val activeApiKeyId: String = "",
    val autoRotateApiKeys: Boolean = true,
    val liveModel: String = "",
    val agentModel: String = "",
    val textModel: String = "",
    val deletedModels: List<String> = emptyList(),
    val verifyTaskCompletion: Boolean = true,
    val voiceName: String = "Kore",
    val voiceLanguage: String = "BENGALI_BD",
    val speakerphoneOn: Boolean = true,
    val autoVisionDefaultOn: Boolean = false,
    val autoVisionIntervalSec: Int = 4,
    val showTouchVisualizer: Boolean = true,
    val showFloatingCaptions: Boolean = true,
    val themeMode: String = "SYSTEM",
    val customVoiceInstructions: String = "",
    val autoChatMode: String = "LIVE_VOICE_ALERT",
    val autoReplyDelivery: String = "SILENT_DIRECT_REPLY",
    val autoReplyPersona: String = "",
    val sensitiveQuestionGuardEnabled: Boolean = true,
    val handsFreeVoiceEscalationEnabled: Boolean = true,
    val enabledMessagingApps: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PersonalDataDoc(
    @DocumentId val id: String = "",
    val title: String = "",
    val content: String = "",
    val category: String = "পরিচয়",
    val shareInAutoReply: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudRoutineDoc(
    @DocumentId val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val prompt: String = "",
    val accent: String = "COBALT",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CloudAskAiMessageDoc(
    @DocumentId val id: String = "",
    val clientId: Long = 0L,
    val senderRole: String = "user",
    val text: String = "",
    val modelUsed: String = "",
    val timestampLabel: String = "",
    val citationsJson: String = "[]",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

package com.example.data

enum class LiveConnectionPhase {
    IDLE,
    CONNECTING,
    WAITING_SETUP,
    READY,
    RECONNECTING,
    ERROR
}

enum class AgentActivity {
    IDLE,
    LISTENING,
    SPEAKING,
    EXECUTING_TOOL,
    SCROLLING,
    MUTED
}

enum class AutoChatMode {
    LIVE_VOICE_ALERT,
    FULL_AUTO_REPLY
}

enum class AutoReplyDelivery {
    SILENT_DIRECT_REPLY,
    VISUAL_ACCESSIBILITY
}

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class RoutineAccent {
    COBALT,
    EMERALD,
    VIOLET,
    AMBER,
    ROSE
}

data class PilotRoutine(
    val id: String,
    val title: String,
    val subtitle: String,
    val prompt: String,
    val accent: RoutineAccent = RoutineAccent.COBALT,
    val isBuiltIn: Boolean = false
)

data class RecentToolEvent(
    val id: Long,
    val toolName: String,
    val summary: String,
    val succeeded: Boolean,
    val timestamp: String
)

enum class MessagingApp(
    val displayName: String,
    val packages: Set<String>
) {
    IMO(
        displayName = "imo",
        packages = setOf(
            "com.imo.android.imoim",
            "com.imo.android.imoimhd",
            "com.imo.android.imoimbeta"
        )
    ),
    WHATSAPP(
        displayName = "WhatsApp",
        packages = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
    ),
    MESSENGER(
        displayName = "Messenger",
        packages = setOf("com.facebook.orca")
    ),
    TELEGRAM(
        displayName = "Telegram",
        packages = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web"
        )
    );

    companion object {
        fun fromPackage(packageName: String): MessagingApp? =
            entries.firstOrNull { packageName in it.packages }
    }
}

enum class LogLevel {
    INFO,
    WS,
    TOOL,
    AUDIO,
    CAPTION,
    SAFETY,
    ERROR
}

data class DiagnosticLogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
)

data class LiveCaptionLine(
    val id: Long,
    val speaker: String, // "You" or "LivePilot"
    val text: String,
    val timestamp: String
)

data class IncomingMessageRecord(
    val id: String,
    val app: MessagingApp,
    val packageName: String,
    val senderName: String,
    val messageText: String,
    val timestampMs: Long,
    val formattedTime: String,
    val hasDirectReply: Boolean,
    val autoRepliedText: String? = null
)

data class WebCitation(
    val title: String,
    val url: String
)

data class AskAiMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val citations: List<WebCitation> = emptyList(),
    val modelUsed: String? = null,
    val timestamp: String
)

data class DiscoveredModel(
    val id: String,
    val displayName: String,
    val isLiveCapable: Boolean,
    val supportsGenerateContent: Boolean
)

data class PermissionSnapshot(
    val micGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val notificationAccessEnabled: Boolean = false
) {
    val allGranted: Boolean
        get() = micGranted && overlayGranted && accessibilityEnabled && notificationAccessEnabled

    val grantedCount: Int
        get() = listOf(
            micGranted,
            overlayGranted,
            accessibilityEnabled,
            notificationAccessEnabled
        ).count { it }
}

data class ConnectionTestState(
    val isTesting: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
    val latencyMs: Long? = null
)

data class LiveSessionState(
    val isServiceRunning: Boolean = false,
    val phase: LiveConnectionPhase = LiveConnectionPhase.IDLE,
    val activity: AgentActivity = AgentActivity.IDLE,
    val statusTicker: String = "Ready to pilot your phone",
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val isScrollingActive: Boolean = false,
    val isAutoVisionActive: Boolean = false,
    val micLevel: Float = 0f,
    val aiAudioLevel: Float = 0f,
    val callStartedAtMs: Long? = null,
    val toolsExecutedCount: Int = 0,
    val lastToolSummary: String? = null,
    val lastCaptionPreview: String = "",
    val sessionHandle: String? = null,
    val lastError: String? = null
)

data class PilotSettings(
    val apiKey: String = "",
    val liveModel: String = DEFAULT_LIVE_MODEL,
    val textModel: String = DEFAULT_TEXT_MODEL,
    val voiceName: String = DEFAULT_VOICE,
    val speakerphoneOn: Boolean = true,
    val autoVisionDefaultOn: Boolean = false,
    val autoVisionIntervalSec: Int = 4,
    val showTouchVisualizer: Boolean = true,
    val showFloatingCaptions: Boolean = true,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val customVoiceInstructions: String = "",
    val autoChatMode: AutoChatMode = AutoChatMode.LIVE_VOICE_ALERT,
    val autoReplyDelivery: AutoReplyDelivery = AutoReplyDelivery.SILENT_DIRECT_REPLY,
    val autoReplyPersona: String = DEFAULT_AUTO_REPLY_PERSONA,
    val enabledMessagingApps: Set<MessagingApp> = setOf(
        MessagingApp.IMO,
        MessagingApp.WHATSAPP,
        MessagingApp.MESSENGER,
        MessagingApp.TELEGRAM
    )
) {
    companion object {
        const val DEFAULT_LIVE_MODEL = "gemini-3.8-live"
        const val DEFAULT_TEXT_MODEL = "gemini-4-hydrogen"
        const val DEFAULT_VOICE = "Kore"
        const val DEFAULT_AUTO_REPLY_PERSONA =
            "Reply politely, naturally, and concisely in Bengali or English matching the sender's language as my personal assistant."

        val LIVE_MODEL_PRESETS = listOf(
            "gemini-3.8-live",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-3.1-flash-live-preview"
        )

        val TEXT_MODEL_PRESETS = listOf(
            "gemini-4-hydrogen",
            "gemini-4-carbon",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash"
        )

        val VOICE_PRESETS = listOf(
            "Kore",
            "Aoede",
            "Puck",
            "Charon",
            "Fenrir"
        )

        val DEFAULT_ROUTINES = listOf(
            PilotRoutine(
                id = "builtin_morning",
                title = "Live Briefing",
                subtitle = "Weather, news & battery",
                prompt = "Check my device battery and time with get_device_status, then search Google with web_search_and_answer for today's top Bangladesh news and Dhaka weather, and brief me warmly in Bengali.",
                accent = RoutineAccent.COBALT,
                isBuiltIn = true
            ),
            PilotRoutine(
                id = "builtin_inbox",
                title = "Inbox Reader",
                subtitle = "Read imo & WhatsApp",
                prompt = "Call read_recent_notifications to check my latest incoming messages and tell me briefly who messaged me and ask if I want to reply.",
                accent = RoutineAccent.ROSE,
                isBuiltIn = true
            ),
            PilotRoutine(
                id = "builtin_youtube",
                title = "YouTube DJ",
                subtitle = "Search & play music",
                prompt = "Open YouTube with search_youtube for 'Bangla lofi chill songs', wait 1200ms, then inspect the screen and play the top result.",
                accent = RoutineAccent.EMERALD,
                isBuiltIn = true
            ),
            PilotRoutine(
                id = "builtin_vision",
                title = "Screen Guide",
                subtitle = "Watch & assist live",
                prompt = "Capture screen vision right now, inspect the UI, and briefly explain in Bengali what is on my screen and how you can help.",
                accent = RoutineAccent.VIOLET,
                isBuiltIn = true
            ),
            PilotRoutine(
                id = "builtin_torch",
                title = "Torch & Status",
                subtitle = "Quick hardware check",
                prompt = "Check my phone status using get_device_status and tell me battery percentage, time, and volume level in one short sentence.",
                accent = RoutineAccent.AMBER,
                isBuiltIn = true
            )
        )
    }
}

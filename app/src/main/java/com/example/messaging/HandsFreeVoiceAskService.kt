package com.example.messaging

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.ai.GeminiDecisionClient
import com.example.ai.GeminiRestClient
import com.example.automation.PilotAccessibilityService
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Screen-Off / Distant Hands-Free Loud Speaker Voice Escalation Service.
 *
 * When a sensitive or important incoming message arrives (even while the phone screen is OFF
 * and the owner is across the room):
 * 1. Wakes CPU briefly with PARTIAL_WAKE_LOCK and ensures speaker playback is audible.
 * 2. Reads the sender, app, and message aloud loudly via Bengali/English TextToSpeech.
 * 3. Automatically activates the microphone (SpeechRecognizer in bn-BD) so the user can
 *    speak their reply from a distance.
 * 4. Sends the spoken reply back to the sender automatically (Direct Reply + Accessibility fallback),
 *    marks the pending sensitive message resolved, and confirms aloud!
 */
class HandsFreeVoiceAskService : Service(), TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousMusicVolume: Int = -1
    private var previousSpeakerphone: Boolean = false

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var currentRecordId: String = ""
    private var currentSenderName: String = ""
    private var currentAppName: String = ""
    private var currentPackageName: String = ""
    private var currentMessageText: String = ""
    private var currentOwnerName: String = ""
    private var awayNoticeAlreadySent: Boolean = false
    private var ownerEngaged: Boolean = false
    private var consultTurnCount: Int = 0
    private var silentAfterEngageCount: Int = 0
    private val consultationTranscript = mutableListOf<String>()
    private var isStopped: Boolean = false

    private val safetyTimeoutRunnable = Runnable {
        if (!isStopped) {
            if (!ownerEngaged) {
                PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Owner did not respond in time; sending named away notice once.")
                sendOwnerAwayNoticeAndStop()
            } else {
                PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Live voice consultation idle timeout; stopping cleanly.")
                stopSelfSafely()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Hands-free voice ask cancelled by user.")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val recordId = intent?.getStringExtra(EXTRA_RECORD_ID).orEmpty()
        val sender = intent?.getStringExtra(EXTRA_SENDER_NAME).orEmpty()
        val appName = intent?.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val pkgName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val messageText = intent?.getStringExtra(EXTRA_MESSAGE_TEXT).orEmpty()
        val ownerName = intent?.getStringExtra(EXTRA_OWNER_NAME).orEmpty()

        if (recordId.isBlank() || sender.isBlank() || messageText.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        isStopped = false
        currentRecordId = recordId
        currentSenderName = sender
        currentAppName = appName
        currentPackageName = pkgName
        currentMessageText = messageText
        currentOwnerName = ownerName.ifBlank {
            ChatModerationEngine.resolveOwnerDisplayName(
                PilotStateHub.settings.value,
                PilotStateHub.personalData.value
            )
        }
        awayNoticeAlreadySent = false
        ownerEngaged = false
        consultTurnCount = 0
        silentAfterEngageCount = 0
        consultationTranscript.clear()

        startAsForeground("🔊 $sender ($appName)-এর মেসেজ পড়ে শোনানো হচ্ছে...")
        acquireWakeLockAndAudio()

        // Single clean safety cutoff (42s) — asks ONCE if away, extends smoothly if owner talks back
        rescheduleSafetyTimeout(42_000L)

        // Initialize TTS to read the message loudly ONCE
        shutdownTts()
        tts = TextToSpeech(applicationContext, this)
        return START_NOT_STICKY
    }

    private fun rescheduleSafetyTimeout(delayMs: Long) {
        mainHandler.removeCallbacks(safetyTimeoutRunnable)
        mainHandler.postDelayed(safetyTimeoutRunnable, delayMs)
    }

    override fun onInit(status: Int) {
        if (isStopped) return
        if (status != TextToSpeech.SUCCESS) {
            PilotStateHub.log(LogLevel.ERROR, "VoiceAsk", "TextToSpeech init failed (status=$status).")
            stopSelfSafely()
            return
        }

        val engine = tts ?: return
        val bengaliLocale = Locale("bn", "BD")
        val langResult = engine.setLanguage(bengaliLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            val genericBn = engine.setLanguage(Locale("bn"))
            if (genericBn == TextToSpeech.LANG_MISSING_DATA || genericBn == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
        }
        engine.setSpeechRate(0.95f)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (isStopped) return
                when (utteranceId) {
                    UTTERANCE_READ_MESSAGE,
                    UTTERANCE_DISCUSS_CONTINUE,
                    UTTERANCE_RETRY_PROMPT -> {
                        mainHandler.post { startDistantVoiceListening() }
                    }
                    UTTERANCE_FINAL_DONE -> {
                        mainHandler.postDelayed({ stopSelfSafely() }, 400L)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (isStopped) return
                if (utteranceId == UTTERANCE_READ_MESSAGE || utteranceId == UTTERANCE_DISCUSS_CONTINUE) {
                    mainHandler.post { startDistantVoiceListening() }
                } else {
                    mainHandler.post { stopSelfSafely() }
                }
            }
        })

        val hasMic = hasMicPermission()
        val spokenPrompt = buildString {
            append("গুরুত্বপূর্ণ মেসেজ এসেছে। ")
            append(currentSenderName).append(" ")
            append(currentAppName).append(" এ বলেছে: ")
            append(currentMessageText.take(220)).append("। ")
            if (hasMic) {
                append("কী উত্তর পাঠাবো? চাইলে আগের মেসেজ বা আমার পরামর্শও জিজ্ঞেস করতে পারেন। এখন বলুন।")
            } else {
                append("মাইক্রোফোন পারমিশন না থাকায় নোটিফিকেশন থেকে উত্তর লিখে দিন।")
            }
        }

        PilotStateHub.log(
            LogLevel.AUDIO,
            "VoiceAsk",
            "Reading aloud on loudspeaker for $currentSenderName ($currentAppName)"
        )
        speakAloud(
            text = spokenPrompt,
            utteranceId = if (hasMic) UTTERANCE_READ_MESSAGE else UTTERANCE_FINAL_DONE
        )
    }

    private fun startDistantVoiceListening() {
        if (isStopped) return
        if (!hasMicPermission() || !SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            if (!ownerEngaged) {
                PilotStateHub.log(LogLevel.ERROR, "VoiceAsk", "Mic unavailable; sending named owner-away waiting message.")
                sendOwnerAwayNoticeAndStop()
            } else {
                stopSelfSafely()
            }
            return
        }

        val notifLabel = if (ownerEngaged) {
            "🎤 লাইভ কথা চলছে... ($currentSenderName-এর বিষয়ে বলুন)"
        } else {
            "🎤 শুনছি... ($currentSenderName-কে উত্তর দিতে বা পরামর্শ চাইতে বলুন)"
        }
        updateForegroundNotification(notifLabel)
        PilotStateHub.log(
            LogLevel.AUDIO,
            "VoiceAsk",
            if (ownerEngaged) "Listening for follow-up turn #${consultTurnCount + 1}..." else "Listening ONCE for owner voice reply..."
        )

        destroyRecognizer()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Mic open — listening for owner...")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (isStopped) return
                destroyRecognizer()
                if (!ownerEngaged) {
                    PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Owner away / no speech (code=$error); sending waiting notice once.")
                    // Do NOT nag or retry repeatedly — owner is away, send named waiting reply and finish!
                    sendOwnerAwayNoticeAndStop()
                } else {
                    handleSilenceDuringConsultation(error)
                }
            }

            override fun onResults(results: Bundle?) {
                if (isStopped) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val spoken = matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                destroyRecognizer()

                if (spoken.isBlank()) {
                    if (!ownerEngaged) {
                        sendOwnerAwayNoticeAndStop()
                    } else {
                        handleSilenceDuringConsultation(-1)
                    }
                    return
                }

                handleOwnerSpokenReply(spoken)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
        }

        runCatching {
            recognizer.startListening(listenIntent)
        }.onFailure { err ->
            PilotStateHub.log(LogLevel.ERROR, "VoiceAsk", "Failed to start SpeechRecognizer: ${err.message}")
            sendOwnerAwayNoticeAndStop()
        }
    }

    /**
     * When the owner is away from the phone (didn't answer on the single prompt),
     * sends ONE polite waiting reply with the owner's account name and stops cleanly without nagging.
     */
    private fun sendOwnerAwayNoticeAndStop() {
        if (awayNoticeAlreadySent || isStopped) {
            stopSelfSafely()
            return
        }
        awayNoticeAlreadySent = true
        destroyRecognizer()

        val awayReply = ChatModerationEngine.buildOwnerAwayMessage(currentOwnerName, currentMessageText)
        val convKey = IncomingMessageListener.conversationKey(currentPackageName, currentSenderName)
        if (!ChatModerationEngine.shouldSendAwayNotice(convKey)) {
            PilotStateHub.log(LogLevel.SAFETY, "VoiceAsk", "Away notice already sent recently for $currentSenderName; stopping quietly.")
            stopSelfSafely()
            return
        }

        serviceScope.launch {
            var delivered = false
            val handle = PilotStateHub.getNotificationHandleById(currentRecordId)
                ?: PilotStateHub.findNotificationHandle(currentSenderName)
            val directAction = handle?.replyAction

            if (directAction != null) {
                delivered = IncomingMessageListener.sendDirectReply(
                    context = applicationContext,
                    action = directAction,
                    replyText = awayReply,
                    packageName = currentPackageName,
                    senderName = currentSenderName
                )
            }

            if (!delivered) {
                val svc = PilotAccessibilityService.instance
                if (svc != null) {
                    IncomingMessageListener.registerSentReply(currentPackageName, currentSenderName, awayReply)
                    svc.openNotificationChat(currentSenderName)
                    delay(900L)
                    val visualRes = svc.typeText(
                        index = -1,
                        x = 0,
                        y = 0,
                        text = awayReply,
                        pressSend = true
                    )
                    delivered = visualRes.optString("status") == "ok"
                }
            }

            if (delivered) {
                PilotStateHub.markMessageAutoReplied(currentRecordId, awayReply)
                PilotStateHub.log(
                    LogLevel.SAFETY,
                    "VoiceAsk",
                    "Owner away — auto-sent named waiting reply to $currentSenderName: \"$awayReply\""
                )
            }
            mainHandler.post { stopSelfSafely() }
        }
    }

    private fun handleSilenceDuringConsultation(errorCode: Int) {
        if (isStopped) return
        silentAfterEngageCount++
        if (silentAfterEngageCount <= 1 && consultTurnCount < MAX_CONSULT_TURNS) {
            PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Brief pause during live consultation (code=$errorCode); prompting once.")
            rescheduleSafetyTimeout(35_000L)
            speakAloud("আর কিছু বলবেন, নাকি এখন বন্ধ করবো?", UTTERANCE_DISCUSS_CONTINUE)
        } else {
            PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Consultation finished quietly after pause.")
            speakAloud("ঠিক আছে, এখন বন্ধ করছি।", UTTERANCE_FINAL_DONE)
        }
    }

    private fun handleOwnerSpokenReply(rawSpoken: String) {
        ownerEngaged = true
        silentAfterEngageCount = 0
        rescheduleSafetyTimeout(48_000L)
        extendWakeLockIfHeld()

        val normalized = rawSpoken.trim().lowercase()
        val cancelKeywords = listOf(
            "বাতিল", "ক্যানসেল", "cancel", "থাক", "বাদ দাও", "পাঠাতে হবে না",
            "পাঠানোর দরকার নেই", "দরকার নেই", "ignore", "বন্ধ করো", "থামো"
        )
        if (cancelKeywords.any { normalized == it || normalized.startsWith("$it ") }) {
            PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Owner cancelled voice reply: \"$rawSpoken\"")
            speakAloud("ঠিক আছে, কোনো উত্তর পাঠানো হয়নি।", UTTERANCE_FINAL_DONE)
            return
        }

        val settings = PilotStateHub.settings.value
        if (!settings.hasAnyApiKey) {
            val cleaned = stripLeadingReplyPrefix(rawSpoken)
            deliverFinalReplyToSender(
                replyText = cleaned,
                spokenConfirmation = "ঠিক আছে, $currentSenderName কে পাঠিয়ে দিয়েছি: ${cleaned.take(120)}।"
            )
            return
        }

        PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Owner spoke (turn ${consultTurnCount + 1}): \"$rawSpoken\"")
        updateForegroundNotification("💬 AI পরামর্শ তৈরি করছে... ($currentSenderName)")

        serviceScope.launch {
            val senderHistory = IncomingMessageListener.getConversationHistory(currentPackageName, currentSenderName)
            val personalFacts = PilotStateHub.personalData.value
            val transcriptSnapshot = synchronized(consultationTranscript) { consultationTranscript.toList() }

            var calibratedHint: String? = null
            if (settings.useDecisionModel) {
                val intentDecision = GeminiDecisionClient.classifyOwnerVoiceIntent(
                    apiKey = settings.effectiveApiKey,
                    modelId = settings.decisionModel,
                    senderName = currentSenderName,
                    appName = currentAppName,
                    incomingMessage = currentMessageText,
                    consultationTranscript = transcriptSnapshot,
                    ownerLatestUtterance = rawSpoken
                ).getOrNull()

                if (intentDecision != null) {
                    val confPct = (intentDecision.confidence * 100).toInt()
                    val sendPct = (intentDecision.explicitSendApprovalProb * 100).toInt()
                    PilotStateHub.log(
                        LogLevel.INFO,
                        "Decision1",
                        "VoiceConsult intent=${intentDecision.action} (conf=${confPct}%, sendProb=${sendPct}%, ${intentDecision.latencyMs}ms)"
                    )
                    if (intentDecision.action == "CANCEL" && intentDecision.confidence >= 0.84) {
                        mainHandler.post {
                            speakAloud("ঠিক আছে, কোনো উত্তর পাঠানো হলো না।", UTTERANCE_FINAL_DONE)
                        }
                        return@launch
                    }
                    if (intentDecision.confidence >= 0.72) {
                        calibratedHint = "gemini-decision-1 calibrated action=${intentDecision.action} (confidence=${confPct}%, explicit_send_approval=${sendPct}%). Strongly prefer this action."
                    }
                }
            }

            val consultRes = GeminiRestClient.consultOwnerOnIncomingMessage(
                apiKey = settings.effectiveApiKey,
                modelId = settings.textModel,
                senderName = currentSenderName,
                appName = currentAppName,
                incomingMessage = currentMessageText,
                recentSenderHistory = senderHistory,
                ownerPersonalData = personalFacts,
                consultationTranscript = transcriptSnapshot,
                ownerLatestUtterance = rawSpoken,
                ownerDisplayName = currentOwnerName,
                decisionHint = calibratedHint
            )

            consultRes.fold(
                onSuccess = { decision ->
                    synchronized(consultationTranscript) {
                        consultationTranscript.add("Owner: $rawSpoken")
                        consultationTranscript.add("AI: ${decision.spokenToOwner}")
                    }
                    when (decision.action) {
                        "SEND_REPLY" -> {
                            val replyToSend = decision.replyToSender.trim()
                                .ifBlank { stripLeadingReplyPrefix(rawSpoken) }
                            deliverFinalReplyToSender(
                                replyText = replyToSend,
                                spokenConfirmation = decision.spokenToOwner.ifBlank {
                                    "ঠিক আছে, $currentSenderName কে পাঠিয়ে দিয়েছি: ${replyToSend.take(120)}।"
                                }
                            )
                        }
                        "CANCEL" -> {
                            PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "AI consultation cancelled by owner.")
                            mainHandler.post {
                                speakAloud(
                                    decision.spokenToOwner.ifBlank { "ঠিক আছে, কোনো উত্তর পাঠানো হলো না।" },
                                    UTTERANCE_FINAL_DONE
                                )
                            }
                        }
                        else -> {
                            // Live conversational back-and-forth (history readout, AI advice, refinements)
                            consultTurnCount++
                            PilotStateHub.log(
                                LogLevel.AUDIO,
                                "VoiceAsk",
                                "Live consult turn #$consultTurnCount -> speaking & re-listening: \"${decision.spokenToOwner.take(90)}\""
                            )
                            mainHandler.post {
                                rescheduleSafetyTimeout(48_000L)
                                if (consultTurnCount < MAX_CONSULT_TURNS) {
                                    updateForegroundNotification("🗣️ AI উত্তর বলছে... ($currentSenderName)")
                                    speakAloud(decision.spokenToOwner, UTTERANCE_DISCUSS_CONTINUE)
                                } else {
                                    speakAloud(
                                        "${decision.spokenToOwner}। এখন লাইভ সেশন শেষ করছি, প্রয়োজনে অ্যাপ থেকে উত্তর পাঠাতে পারবেন।",
                                        UTTERANCE_FINAL_DONE
                                    )
                                }
                            }
                        }
                    }
                },
                onFailure = { err ->
                    PilotStateHub.log(LogLevel.ERROR, "VoiceAsk", "Consultation Gemini call failed: ${err.message}")
                    val fallbackReply = stripLeadingReplyPrefix(rawSpoken)
                    deliverFinalReplyToSender(
                        replyText = fallbackReply,
                        spokenConfirmation = "ঠিক আছে, $currentSenderName কে পাঠিয়ে দিয়েছি: ${fallbackReply.take(120)}।"
                    )
                }
            )
        }
    }

    private fun stripLeadingReplyPrefix(rawSpoken: String): String = rawSpoken
        .replace(Regex("^(ওকে\\s+|তাকে\\s+|ওরে\\s+)?(বলো|বলেন|বলে\\s*দাও|লিখো|লিখে\\s*দাও|পাঠাও|পাঠিয়ে\\s*দাও|পাঠিয়ে\\s*দাও|রিপ্লাই\\s*দাও)\\s*(যে\\s*)?"), "")
        .replace(Regex("^(tell\\s+(him|her|them)|reply|send)\\s+(that\\s+)?", RegexOption.IGNORE_CASE), "")
        .trim()
        .ifBlank { rawSpoken.trim() }

    private fun deliverFinalReplyToSender(replyText: String, spokenConfirmation: String) {
        PilotStateHub.log(LogLevel.AUDIO, "VoiceAsk", "Sending approved reply to $currentSenderName: \"$replyText\"")
        updateForegroundNotification("📤 $currentSenderName-কে উত্তর পাঠানো হচ্ছে...")

        serviceScope.launch {
            var delivered = false
            val handle = PilotStateHub.getNotificationHandleById(currentRecordId)
                ?: PilotStateHub.findNotificationHandle(currentSenderName)
            val directAction = handle?.replyAction

            if (directAction != null) {
                delivered = IncomingMessageListener.sendDirectReply(
                    context = applicationContext,
                    action = directAction,
                    replyText = replyText,
                    packageName = currentPackageName,
                    senderName = currentSenderName
                )
            }

            if (!delivered) {
                val svc = PilotAccessibilityService.instance
                if (svc != null) {
                    IncomingMessageListener.registerSentReply(currentPackageName, currentSenderName, replyText)
                    svc.openNotificationChat(currentSenderName)
                    delay(900L)
                    val visualRes = svc.typeText(
                        index = -1,
                        x = 0,
                        y = 0,
                        text = replyText,
                        pressSend = true
                    )
                    delivered = visualRes.optString("status") == "ok"
                }
            }

            if (delivered) {
                IncomingMessageListener.appendConversationHistory(currentPackageName, currentSenderName, "Me: $replyText")
                ChatModerationEngine.markOwnerRespondedForSender(
                    IncomingMessageListener.conversationKey(currentPackageName, currentSenderName)
                )
                PilotStateHub.markSensitiveMessageResolved(currentRecordId, replyText)
                val notifId = (currentRecordId.hashCode() and 0x0FFFFFFF) + 4100
                runCatching {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(notifId)
                }
                PilotStateHub.log(
                    LogLevel.SAFETY,
                    "VoiceAsk",
                    "Hands-free voice reply delivered to $currentSenderName: \"$replyText\""
                )
                mainHandler.post {
                    speakAloud(spokenConfirmation, UTTERANCE_FINAL_DONE)
                }
            } else {
                PilotStateHub.log(LogLevel.ERROR, "VoiceAsk", "Could not deliver spoken reply to $currentSenderName.")
                mainHandler.post {
                    speakAloud(
                        "দুঃখিত, সরাসরি উত্তর পাঠানো যায়নি। অ্যাপ খুলে উত্তর দিন।",
                        UTTERANCE_FINAL_DONE
                    )
                }
            }
        }
    }

    private fun extendWakeLockIfHeld() {
        runCatching {
            val wl = wakeLock
            if (wl != null && wl.isHeld) {
                wl.release()
                wl.acquire(90_000L)
            }
        }
    }

    private fun speakAloud(text: String, utteranceId: String) {
        if (isStopped) return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun acquireWakeLockAndAudio() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LivePilotAI:HandsFreeVoiceAskLock"
                ).apply {
                    acquire(75_000L)
                }
            }
        }

        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = am
            previousSpeakerphone = am.isSpeakerphoneOn
            previousMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (maxVol * 0.85f).toInt().coerceAtLeast(1)
            if (previousMusicVolume < targetVol) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true

            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun releaseWakeLockAndAudio() {
        runCatching {
            val am = audioManager
            if (am != null) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                if (previousMusicVolume >= 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, previousMusicVolume, 0)
                }
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = previousSpeakerphone
            }
        }
        audioFocusRequest = null
        previousMusicVolume = -1

        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground(statusText: String) {
        ensureNotificationChannel()
        val notif = buildForegroundNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission()) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateForegroundNotification(statusText: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildForegroundNotification(statusText))
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hands-Free Voice Ask",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reads important incoming messages aloud even when screen is off and listens for your voice reply"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "AUTO_CHAT")
        }
        val openPending = PendingIntent.getActivity(
            this,
            5101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(this, HandsFreeVoiceAskService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this,
            5102,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("LivePilot Hands-Free Voice Ask")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(R.mipmap.ic_launcher, "বন্ধ করুন", cancelPending)
            .build()
    }

    private fun destroyRecognizer() {
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun shutdownTts() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }

    private fun stopSelfSafely() {
        if (isStopped) return
        isStopped = true
        mainHandler.removeCallbacks(safetyTimeoutRunnable)
        destroyRecognizer()
        shutdownTts()
        releaseWakeLockAndAudio()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        isStopped = true
        mainHandler.removeCallbacks(safetyTimeoutRunnable)
        destroyRecognizer()
        shutdownTts()
        releaseWakeLockAndAudio()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "livepilot_handsfree_voice_ask_v1"
        private const val NOTIF_ID = 5209
        private const val ACTION_CANCEL = "com.example.action.CANCEL_VOICE_ASK"
        private const val EXTRA_RECORD_ID = "extra_record_id"
        private const val EXTRA_SENDER_NAME = "extra_sender_name"
        private const val EXTRA_APP_NAME = "extra_app_name"
        private const val EXTRA_PACKAGE_NAME = "extra_package_name"
        private const val EXTRA_MESSAGE_TEXT = "extra_message_text"
        private const val EXTRA_OWNER_NAME = "extra_owner_name"

        private const val UTTERANCE_READ_MESSAGE = "utt_read_message"
        private const val UTTERANCE_DISCUSS_CONTINUE = "utt_discuss_continue"
        private const val UTTERANCE_RETRY_PROMPT = "utt_retry_prompt"
        private const val UTTERANCE_FINAL_DONE = "utt_final_done"
        private const val MAX_CONSULT_TURNS = 6

        fun startEscalation(
            context: Context,
            recordId: String,
            senderName: String,
            appName: String,
            packageName: String,
            messageText: String,
            ownerName: String = ""
        ) {
            val intent = Intent(context, HandsFreeVoiceAskService::class.java).apply {
                putExtra(EXTRA_RECORD_ID, recordId)
                putExtra(EXTRA_SENDER_NAME, senderName)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_MESSAGE_TEXT, messageText)
                putExtra(EXTRA_OWNER_NAME, ownerName)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { err ->
                PilotStateHub.log(
                    LogLevel.ERROR,
                    "VoiceAsk",
                    "Could not start screen-off voice ask service: ${err.message}"
                )
            }
        }
    }
}

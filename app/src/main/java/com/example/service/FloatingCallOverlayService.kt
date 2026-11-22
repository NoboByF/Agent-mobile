package com.example.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ai.GeminiLiveCallbacks
import com.example.ai.GeminiLiveClient
import com.example.ai.LiveToolCall
import com.example.audio.LiveAudioEngine
import com.example.automation.PilotAccessibilityService
import com.example.automation.ToolDispatcher
import com.example.data.AgentActivity
import com.example.data.LiveConnectionPhase
import com.example.data.LogLevel
import com.example.data.OverlaySessionController
import com.example.data.PilotStateHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingCallOverlayService : Service(), OverlaySessionController, GeminiLiveCallbacks {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var capsuleBg: GradientDrawable? = null
    private var orbView: View? = null
    private var tickerTextView: TextView? = null
    private var captionStripView: TextView? = null
    private var muteButton: TextView? = null
    private var autoVisionButton: TextView? = null
    private var visionButton: TextView? = null
    private var appButton: TextView? = null
    private var collapseButton: TextView? = null
    private var endButton: TextView? = null
    private var orbAnimator: ObjectAnimator? = null

    private var isCompactMode = false

    private var liveClient: GeminiLiveClient? = null
    private var audioEngine: LiveAudioEngine? = null
    private var toolDispatcher: ToolDispatcher? = null
    private var stateObserverJob: Job? = null
    private var autoVisionJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PilotStateHub.ensureInitialized(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                endCallSession()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                if (liveClient == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                toggleMute()
                return START_STICKY
            }
            ACTION_SNAP_VISION -> {
                if (liveClient == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                triggerManualVisionCapture()
                return START_STICKY
            }
            ACTION_UPDATE_SPEAKER -> {
                if (liveClient == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val speaker = intent.getBooleanExtra(EXTRA_SPEAKER, true)
                audioEngine?.setSpeakerphone(speaker)
                PilotStateHub.updateSession { it.copy(isSpeakerOn = speaker) }
                return START_STICKY
            }
        }

        startForegroundSafely()
        PilotStateHub.activeController = this

        if (liveClient == null) {
            startLiveSession()
        }
        return START_STICKY
    }

    /**
     * Guard FOREGROUND_SERVICE_TYPE_SPECIAL_USE with API 34+ (Android 14) check
     * and use FOREGROUND_SERVICE_TYPE_MICROPHONE on Android 11-13 (API 30-33).
     */
    private fun startForegroundSafely() {
        val notification = buildPersistentNotification("LivePilot AI call active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
    }

    private fun startLiveSession() {
        val settings = PilotStateHub.settings.value
        PilotStateHub.clearRecentToolEvents()
        PilotStateHub.updateSession {
            it.copy(
                isServiceRunning = true,
                phase = LiveConnectionPhase.CONNECTING,
                activity = AgentActivity.IDLE,
                statusTicker = "Connecting to ${settings.liveModel}...",
                isMuted = false,
                isSpeakerOn = settings.speakerphoneOn,
                isAutoVisionActive = settings.autoVisionDefaultOn,
                callStartedAtMs = System.currentTimeMillis(),
                toolsExecutedCount = 0,
                lastToolSummary = null,
                lastCaptionPreview = "",
                micLevel = 0f,
                aiAudioLevel = 0f,
                lastError = null
            )
        }

        val engine = LiveAudioEngine(applicationContext) { pcmBytes ->
            liveClient?.sendAudioPcm(pcmBytes)
        }
        audioEngine = engine

        val client = GeminiLiveClient(callbacks = this)
        liveClient = client
        toolDispatcher = ToolDispatcher(applicationContext) { liveClient }

        if (Settings.canDrawOverlays(this)) {
            attachFloatingPillOverlay()
        } else {
            PilotStateHub.log(LogLevel.INFO, "Overlay", "Overlay permission not granted; running audio-only.")
        }

        observeSessionStateForOverlay()
        client.connect(
            apiKey = settings.effectiveApiKey,
            modelId = settings.liveModel,
            voiceName = settings.voiceName,
            customInstructions = settings.customVoiceInstructions
        )
    }

    // --- GeminiLiveCallbacks ---

    override fun onSetupComplete() {
        val settings = PilotStateHub.settings.value
        audioEngine?.start(speakerphone = settings.speakerphoneOn)
        val currentlyMuted = PilotStateHub.sessionState.value.isMuted
        audioEngine?.setMuted(currentlyMuted)
        PilotStateHub.updateSession {
            it.copy(
                phase = LiveConnectionPhase.READY,
                activity = if (currentlyMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                statusTicker = if (currentlyMuted) "Mic muted" else "Listening..."
            )
        }
        if (PilotStateHub.sessionState.value.isAutoVisionActive) {
            startAutoVisionLoop()
        }

        // Auto-run queued Routine if user tapped a 1-tap Routine while idle
        val queued = PilotStateHub.pendingStartupCommand
        if (!queued.isNullOrBlank()) {
            PilotStateHub.pendingStartupCommand = null
            serviceScope.launch {
                delay(450L)
                liveClient?.sendUserTextTurn(queued)
                PilotStateHub.log(LogLevel.INFO, "Routine", "Auto-ran queued routine on connect.")
            }
        }
    }

    override fun onAudioOutput(pcm24kBytes: ByteArray) {
        audioEngine?.enqueueAudioOutput(pcm24kBytes)
        PilotStateHub.updateSession { state ->
            if (state.activity != AgentActivity.SPEAKING && !state.isScrollingActive && !state.isAutonomousTaskRunning) {
                state.copy(activity = AgentActivity.SPEAKING, statusTicker = "Speaking...")
            } else {
                state
            }
        }
    }

    override fun onInterrupted() {
        // Only interrupt AI voice playback when the user speaks (barge-in).
        // Do NOT cancel a running AutonomousTaskEngine job so the user can chat freely while drawing/automating!
        audioEngine?.interruptPlayback()
        if (toolDispatcher?.isAutonomousTaskRunning() != true) {
            toolDispatcher?.cancelAllActiveActions(includeAutonomous = false)
            PilotAccessibilityService.instance?.stopContinuousScroll()
            PilotStateHub.updateSession {
                it.copy(
                    activity = if (it.isMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                    isScrollingActive = false,
                    statusTicker = "Listening..."
                )
            }
        }
    }

    override fun onToolCall(calls: List<LiveToolCall>) {
        toolDispatcher?.dispatchCalls(calls)
    }

    override fun onToolCallCancellation(ids: List<String>) {
        audioEngine?.interruptPlayback()
        toolDispatcher?.cancelToolCalls(ids)
        PilotStateHub.updateSession {
            it.copy(
                activity = if (it.isMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                isScrollingActive = false,
                statusTicker = "Action cancelled"
            )
        }
    }

    override fun onConnectionPhaseChanged(phase: LiveConnectionPhase, detail: String) {
        PilotStateHub.updateSession {
            it.copy(
                phase = phase,
                statusTicker = detail,
                lastError = if (phase == LiveConnectionPhase.ERROR) detail else null
            )
        }
    }

    override fun onInputTranscript(text: String) {
        PilotStateHub.updateSession {
            if (it.isAutonomousTaskRunning) {
                it
            } else {
                it.copy(
                    activity = AgentActivity.LISTENING,
                    statusTicker = "You: ${text.take(36)}"
                )
            }
        }
    }

    override fun onOutputTranscript(text: String) {
        PilotStateHub.updateSession {
            if (it.isAutonomousTaskRunning) {
                it
            } else {
                it.copy(
                    activity = AgentActivity.SPEAKING,
                    statusTicker = text.take(40)
                )
            }
        }
    }

    // --- OverlaySessionController ---

    override fun toggleMute() {
        val nextMuted = !PilotStateHub.sessionState.value.isMuted
        audioEngine?.setMuted(nextMuted)
        PilotStateHub.updateSession {
            it.copy(
                isMuted = nextMuted,
                activity = if (nextMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                statusTicker = if (nextMuted) "Mic muted" else "Listening..."
            )
        }
        updateNotificationText(if (nextMuted) "Mic muted • LivePilot AI" else "Listening • LivePilot AI")
    }

    override fun toggleAutoVision() {
        val next = !PilotStateHub.sessionState.value.isAutoVisionActive
        PilotStateHub.updateSession {
            it.copy(
                isAutoVisionActive = next,
                statusTicker = if (next) "Auto-Vision ON" else "Auto-Vision OFF"
            )
        }
        if (next) {
            startAutoVisionLoop()
        } else {
            autoVisionJob?.cancel()
            autoVisionJob = null
        }
        PilotStateHub.log(LogLevel.INFO, "Vision", "Auto-Vision stream ${if (next) "enabled" else "disabled"}.")
    }

    private fun startAutoVisionLoop() {
        autoVisionJob?.cancel()
        autoVisionJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive && PilotStateHub.sessionState.value.isAutoVisionActive) {
                val intervalSec = PilotStateHub.settings.value.autoVisionIntervalSec.coerceIn(2, 10)
                delay(intervalSec * 1000L)
                if (!isActive || !PilotStateHub.sessionState.value.isAutoVisionActive) break
                if (liveClient?.isReadyForRealtimeInput() != true) continue
                val accessibility = PilotAccessibilityService.instance ?: continue
                if (accessibility.isCurrentScreenBlocked()) continue
                accessibility.captureScreenVisionAndStream()
            }
        }
    }

    override fun triggerManualVisionCapture() {
        serviceScope.launch(Dispatchers.Default) {
            val accessibility = PilotAccessibilityService.instance
            if (accessibility == null) {
                PilotStateHub.log(LogLevel.ERROR, "Vision", "Enable Accessibility Service for screen vision.")
                PilotStateHub.updateSession { it.copy(statusTicker = "Enable Accessibility for Vision") }
                return@launch
            }
            PilotStateHub.updateSession { it.copy(statusTicker = "Capturing screen vision...") }
            val result = accessibility.captureScreenVisionAndStream()
            val ok = result.optString("status") == "ok"
            PilotStateHub.updateSession {
                it.copy(statusTicker = if (ok) "Vision frame sent!" else "Vision capture failed")
            }
        }
    }

    override fun emergencyStopActions() {
        autoVisionJob?.cancel()
        autoVisionJob = null
        audioEngine?.interruptPlayback()
        toolDispatcher?.cancelAllActiveActions(includeAutonomous = true)
        PilotAccessibilityService.instance?.stopContinuousScroll()
        PilotStateHub.log(LogLevel.TOOL, "STOP", "Emergency STOP pressed — halted autonomous task, scroll, auto-vision & gestures.")
        PilotStateHub.updateSession {
            it.copy(
                isScrollingActive = false,
                isAutoVisionActive = false,
                isAutonomousTaskRunning = false,
                autonomousTaskProgress = null,
                activity = if (it.isMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                statusTicker = "Stopped actions • Call active"
            )
        }
    }

    override fun endCallSession() {
        cleanupSessionResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupSessionResources() {
        PilotStateHub.activeController = null
        PilotStateHub.pendingStartupCommand = null
        autoVisionJob?.cancel()
        autoVisionJob = null
        stateObserverJob?.cancel()
        stateObserverJob = null
        toolDispatcher?.shutdown()
        toolDispatcher = null
        audioEngine?.stop()
        audioEngine = null
        liveClient?.disconnect()
        liveClient = null
        removeFloatingOverlay()

        PilotStateHub.updateSession {
            it.copy(
                isServiceRunning = false,
                phase = LiveConnectionPhase.IDLE,
                activity = AgentActivity.IDLE,
                isMuted = false,
                isScrollingActive = false,
                isAutoVisionActive = false,
                isAutonomousTaskRunning = false,
                autonomousTaskProgress = null,
                micLevel = 0f,
                aiAudioLevel = 0f,
                callStartedAtMs = null,
                lastCaptionPreview = "",
                activeKeyLabel = null,
                statusTicker = "Ready to pilot your phone"
            )
        }
    }

    override fun setOverlayTemporarilyHidden(hidden: Boolean) {
        mainHandler.post {
            val view = overlayView ?: return@post
            val params = overlayParams ?: return@post
            view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            params.flags = if (hidden) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (view.isAttachedToWindow) {
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
        }
    }

    override fun setOverlayPassThrough(passThrough: Boolean) {
        mainHandler.post {
            val view = overlayView ?: return@post
            val params = overlayParams ?: return@post
            params.flags = if (passThrough) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            view.alpha = if (passThrough) 0.55f else 1.0f
            if (view.isAttachedToWindow) {
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
        }
    }

    /**
     * Draws a futuristic non-touchable animated radial ripple right at (x, y) over any app
     * whenever LivePilot taps, clicks, long-presses, or drags on screen.
     */
    override fun showTouchIndicator(x: Int, y: Int, label: String) {
        if (!Settings.canDrawOverlays(this)) return
        mainHandler.post {
            val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
            val rippleSize = dp(64)
            val rippleContainer = FrameLayout(this)

            val outerRing = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(rippleSize, rippleSize, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#333B82F6"))
                    setStroke(dp(3), Color.parseColor("#38BDF8"))
                }
            }
            val centerDot = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2563EB"))
                    setStroke(dp(2), Color.WHITE)
                }
            }
            rippleContainer.addView(outerRing)
            rippleContainer.addView(centerDot)

            val params = WindowManager.LayoutParams(
                rippleSize,
                rippleSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - rippleSize / 2).coerceAtLeast(0)
                this.y = (y - rippleSize / 2).coerceAtLeast(0)
            }

            val added = runCatching {
                wm.addView(rippleContainer, params)
                true
            }.getOrDefault(false)
            if (!added) return@post

            val expand = ObjectAnimator.ofPropertyValuesHolder(
                outerRing,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.35f, 1.35f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.35f, 1.35f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.0f)
            ).apply {
                duration = 460L
                interpolator = DecelerateInterpolator()
            }
            val dotFade = ObjectAnimator.ofFloat(centerDot, View.ALPHA, 1f, 0f).apply {
                startDelay = 220L
                duration = 240L
            }
            AnimatorSet().apply {
                playTogether(expand, dotFade)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        runCatching { wm.removeView(rippleContainer) }
                    }
                })
                start()
            }
        }
    }

    override fun pushIncomingMessageAlert(alertText: String) {
        liveClient?.sendSystemAlert(alertText)
    }

    override fun sendUserTextCommand(commandText: String): Boolean {
        return liveClient?.sendUserTextTurn(commandText) ?: false
    }

    override fun sendVisionFrame(jpegBytes: ByteArray): Boolean {
        return liveClient?.sendVisionFrame(jpegBytes) ?: false
    }

    // --- Next-Gen Snap-to-Edge Dynamic Floating Capsule + Subtitle Strip ---

    private fun attachFloatingPillOverlay() {
        mainHandler.post {
            if (overlayView != null) return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val rootColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
            }

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#F20B1220"))
                setStroke(dp(1), Color.parseColor("#883B82F6"))
            }
            capsuleBg = bgDrawable

            val pillRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = bgDrawable
                elevation = dp(14).toFloat()
            }

            val orb = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginEnd = dp(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#3B82F6"))
                    setStroke(dp(2), Color.parseColor("#93C5FD"))
                }
            }
            orbView = orb
            startOrbPulse(orb)

            val ticker = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
                text = "LivePilot AI"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            tickerTextView = ticker

            val muteBtn = createPillActionButton("MIC", "#1E293B", "#E2E8F0") { toggleMute() }
            muteButton = muteBtn

            val autoBtn = createPillActionButton("AUTO", "#1E293B", "#C4B5FD") { toggleAutoVision() }
            autoVisionButton = autoBtn

            val visionBtn = createPillActionButton("EYE", "#4C1D95", "#EDE9FE") { triggerManualVisionCapture() }
            visionButton = visionBtn

            val stopBtn = createPillActionButton("STOP", "#DC2626", "#FFFFFF") { emergencyStopActions() }

            val appBtn = createPillActionButton("APP", "#1E3A8A", "#DBEAFE") {
                val launch = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { startActivity(launch) }
            }
            appButton = appBtn

            val collapseBtn = createPillActionButton("–", "#1E293B", "#CBD5E1") { toggleCompactMode() }
            collapseButton = collapseBtn

            val endBtn = createPillActionButton("END", "#334155", "#FCA5A5") { endCallSession() }
            endButton = endBtn

            pillRow.addView(orb)
            pillRow.addView(ticker)
            pillRow.addView(muteBtn)
            pillRow.addView(autoBtn)
            pillRow.addView(visionBtn)
            pillRow.addView(stopBtn)
            pillRow.addView(appBtn)
            pillRow.addView(collapseBtn)
            pillRow.addView(endBtn)

            val captionStrip = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                }
                maxWidth = dp(310)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#CC0F172A"))
                }
                visibility = View.GONE
            }
            captionStripView = captionStrip

            rootColumn.addView(pillRow)
            rootColumn.addView(captionStrip)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(10)
                y = dp(72)
            }

            setupDragAndSnapListener(rootColumn, params, wm)

            overlayParams = params
            overlayView = rootColumn
            runCatching { wm.addView(rootColumn, params) }
                .onFailure { err ->
                    PilotStateHub.log(LogLevel.ERROR, "Overlay", "Failed to add overlay: ${err.message}")
                    overlayView = null
                }
        }
    }

    private fun toggleCompactMode() {
        isCompactMode = !isCompactMode
        mainHandler.post {
            val vis = if (isCompactMode) View.GONE else View.VISIBLE
            tickerTextView?.visibility = vis
            muteButton?.visibility = vis
            autoVisionButton?.visibility = vis
            visionButton?.visibility = vis
            appButton?.visibility = vis
            endButton?.visibility = vis
            if (isCompactMode) {
                captionStripView?.visibility = View.GONE
            }
            collapseButton?.text = if (isCompactMode) "+" else "–"
            overlayView?.let { v ->
                overlayParams?.let { p ->
                    runCatching { windowManager?.updateViewLayout(v, p) }
                }
            }
        }
    }

    private fun createPillActionButton(
        label: String,
        bgHex: String,
        textHex: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(textHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
            minHeight = dp(31)
            minWidth = dp(30)
            setPadding(dp(5), dp(4), dp(5), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setupDragAndSnapListener(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 6 || abs(dy) > 6) {
                        moved = true
                        val maxY = (resources.displayMetrics.heightPixels - dp(84)).coerceAtLeast(dp(48))
                        params.x = initialX + dx
                        params.y = (initialY + dy).coerceIn(dp(20), maxY)
                        if (view.isAttachedToWindow) {
                            runCatching { wm.updateViewLayout(view, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved && event.action == MotionEvent.ACTION_UP && isCompactMode) {
                        toggleCompactMode()
                    } else if (moved) {
                        // Snap smoothly to nearest horizontal screen edge
                        val screenW = resources.displayMetrics.widthPixels
                        val viewW = view.width.coerceAtLeast(dp(120))
                        val centerX = params.x + viewW / 2
                        val margin = dp(10)
                        val targetX = if (centerX < screenW / 2) {
                            margin
                        } else {
                            (screenW - viewW - margin).coerceAtLeast(margin)
                        }
                        ValueAnimator.ofInt(params.x, targetX).apply {
                            duration = 240L
                            interpolator = OvershootInterpolator(0.8f)
                            addUpdateListener { anim ->
                                params.x = anim.animatedValue as Int
                                if (view.isAttachedToWindow) {
                                    runCatching { wm.updateViewLayout(view, params) }
                                }
                            }
                            start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun startOrbPulse(view: View) {
        orbAnimator?.cancel()
        orbAnimator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.88f, 1.22f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.88f, 1.22f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.78f, 1.0f)
        ).apply {
            duration = 820L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun observeSessionStateForOverlay() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            PilotStateHub.sessionState.collectLatest { state ->
                tickerTextView?.text = state.statusTicker
                val orbColor = when (state.activity) {
                    AgentActivity.LISTENING -> Color.parseColor("#3B82F6")
                    AgentActivity.SPEAKING -> Color.parseColor("#10B981")
                    AgentActivity.AUTONOMOUS_TASK -> Color.parseColor("#A855F7")
                    AgentActivity.EXECUTING_TOOL, AgentActivity.SCROLLING -> Color.parseColor("#F59E0B")
                    AgentActivity.MUTED -> Color.parseColor("#64748B")
                    AgentActivity.IDLE -> Color.parseColor("#7C3AED")
                }
                (orbView?.background as? GradientDrawable)?.setColor(orbColor)

                val energy = maxOf(state.micLevel, state.aiAudioLevel)
                val borderAlpha = ((0.35f + energy * 0.65f) * 255).toInt().coerceIn(90, 255)
                val reactiveBorder = Color.argb(
                    borderAlpha,
                    Color.red(orbColor),
                    Color.green(orbColor),
                    Color.blue(orbColor)
                )
                capsuleBg?.setStroke(dp(if (energy > 0.28f) 2 else 1), reactiveBorder)

                muteButton?.text = if (state.isMuted) "OFF" else "MIC"
                (muteButton?.background as? GradientDrawable)?.setColor(
                    Color.parseColor(if (state.isMuted) "#B45309" else "#1E293B")
                )

                (autoVisionButton?.background as? GradientDrawable)?.setColor(
                    Color.parseColor(if (state.isAutoVisionActive) "#059669" else "#1E293B")
                )

                val showCaption = !isCompactMode &&
                    PilotStateHub.settings.value.showFloatingCaptions &&
                    state.lastCaptionPreview.isNotBlank()
                captionStripView?.apply {
                    visibility = if (showCaption) View.VISIBLE else View.GONE
                    if (showCaption) text = state.lastCaptionPreview
                }
            }
        }
    }

    private fun removeFloatingOverlay() {
        mainHandler.post {
            orbAnimator?.cancel()
            orbAnimator = null
            overlayView?.let { v ->
                runCatching { windowManager?.removeView(v) }
            }
            overlayView = null
            overlayParams = null
            capsuleBg = null
            orbView = null
            tickerTextView = null
            captionStripView = null
            muteButton = null
            autoVisionButton = null
            visionButton = null
            appButton = null
            collapseButton = null
            endButton = null
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.overlay_service_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotificationText(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, buildPersistentNotification(text)) }
    }

    private fun buildPersistentNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingCallOverlayService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val visionIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, FloatingCallOverlayService::class.java).setAction(ACTION_SNAP_VISION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingCallOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mutedLabel = if (PilotStateHub.sessionState.value.isMuted) "Unmute" else "Mute"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, mutedLabel, muteIntent)
            .addAction(android.R.drawable.ic_menu_camera, "Vision", visionIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", stopIntent)
            .build()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    override fun onDestroy() {
        cleanupSessionResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "livepilot_active_call_channel"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.example.livepilot.START_CALL"
        const val ACTION_STOP = "com.example.livepilot.STOP_CALL"
        const val ACTION_TOGGLE_MUTE = "com.example.livepilot.TOGGLE_MUTE"
        const val ACTION_SNAP_VISION = "com.example.livepilot.SNAP_VISION"
        const val ACTION_UPDATE_SPEAKER = "com.example.livepilot.UPDATE_SPEAKER"
        const val EXTRA_SPEAKER = "extra_speaker"

        fun start(context: Context) {
            val intent = Intent(context, FloatingCallOverlayService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingCallOverlayService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun updateSpeaker(context: Context, speakerOn: Boolean) {
            val intent = Intent(context, FloatingCallOverlayService::class.java)
                .setAction(ACTION_UPDATE_SPEAKER)
                .putExtra(EXTRA_SPEAKER, speakerOn)
            context.startService(intent)
        }
    }
}

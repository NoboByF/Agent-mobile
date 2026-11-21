package com.example.service

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
import android.widget.ImageButton
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingCallOverlayService : Service(), OverlaySessionController, GeminiLiveCallbacks {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var orbView: View? = null
    private var tickerTextView: TextView? = null
    private var muteButton: TextView? = null
    private var orbAnimator: ObjectAnimator? = null

    private var liveClient: GeminiLiveClient? = null
    private var audioEngine: LiveAudioEngine? = null
    private var toolDispatcher: ToolDispatcher? = null
    private var stateObserverJob: Job? = null

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
            ACTION_UPDATE_SPEAKER -> {
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
     * Safety Check #1: Guard FOREGROUND_SERVICE_TYPE_SPECIAL_USE with API 34+ (Android 14) check
     * and use FOREGROUND_SERVICE_TYPE_MICROPHONE on Android 11-13 (API 30-33) to prevent crashes.
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
        PilotStateHub.updateSession {
            it.copy(
                isServiceRunning = true,
                phase = LiveConnectionPhase.CONNECTING,
                activity = AgentActivity.IDLE,
                statusTicker = "Connecting to ${settings.liveModel}...",
                isMuted = false,
                isSpeakerOn = settings.speakerphoneOn,
                lastError = null
            )
        }

        val engine = LiveAudioEngine(applicationContext) { pcmBytes ->
            liveClient?.sendAudioPcm(pcmBytes)
        }
        audioEngine = engine

        val client = GeminiLiveClient(callbacks = this)
        liveClient = client
        toolDispatcher = ToolDispatcher { liveClient }

        if (Settings.canDrawOverlays(this)) {
            attachFloatingPillOverlay()
        } else {
            PilotStateHub.log(LogLevel.INFO, "Overlay", "Overlay permission not granted; running audio-only.")
        }

        observeSessionStateForOverlay()
        client.connect(
            apiKey = settings.apiKey,
            modelId = settings.liveModel,
            voiceName = settings.voiceName
        )
    }

    // --- GeminiLiveCallbacks ---

    override fun onSetupComplete() {
        val speaker = PilotStateHub.settings.value.speakerphoneOn
        audioEngine?.start(speakerphone = speaker)
        PilotStateHub.updateSession {
            it.copy(
                phase = LiveConnectionPhase.READY,
                activity = AgentActivity.LISTENING,
                statusTicker = "Listening..."
            )
        }
    }

    override fun onAudioOutput(pcm24kBytes: ByteArray) {
        audioEngine?.enqueueAudioOutput(pcm24kBytes)
        PilotStateHub.updateSession { state ->
            if (state.activity != AgentActivity.SPEAKING && !state.isScrollingActive) {
                state.copy(activity = AgentActivity.SPEAKING, statusTicker = "Speaking...")
            } else {
                state
            }
        }
    }

    override fun onInterrupted() {
        audioEngine?.interruptPlayback()
        toolDispatcher?.cancelAllActiveActions()
        PilotAccessibilityService.instance?.stopContinuousScroll()
        PilotStateHub.updateSession {
            it.copy(
                activity = if (it.isMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                isScrollingActive = false,
                statusTicker = "Interrupted • Listening"
            )
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
            it.copy(
                activity = AgentActivity.LISTENING,
                statusTicker = "You: ${text.take(36)}"
            )
        }
    }

    override fun onOutputTranscript(text: String) {
        PilotStateHub.updateSession {
            it.copy(
                activity = AgentActivity.SPEAKING,
                statusTicker = text.take(40)
            )
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
        audioEngine?.interruptPlayback()
        toolDispatcher?.cancelAllActiveActions()
        PilotAccessibilityService.instance?.stopContinuousScroll()
        PilotStateHub.log(LogLevel.TOOL, "STOP", "Emergency STOP pressed — halted scroll & active gestures.")
        PilotStateHub.updateSession {
            it.copy(
                isScrollingActive = false,
                activity = if (it.isMuted) AgentActivity.MUTED else AgentActivity.LISTENING,
                statusTicker = "Stopped actions • Call active"
            )
        }
    }

    override fun endCallSession() {
        PilotStateHub.activeController = null
        stateObserverJob?.cancel()
        toolDispatcher?.cancelAllActiveActions()
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
                statusTicker = "Ready to pilot your phone"
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    override fun pushIncomingMessageAlert(alertText: String) {
        liveClient?.sendSystemAlert(alertText)
    }

    override fun sendVisionFrame(jpegBytes: ByteArray): Boolean {
        return liveClient?.sendVisionFrame(jpegBytes) ?: false
    }

    // --- Draggable Floating Pill Overlay ---

    private fun attachFloatingPillOverlay() {
        mainHandler.post {
            if (overlayView != null) return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(Color.parseColor("#EE0F172A"))
                    setStroke(dp(1), Color.parseColor("#403B82F6"))
                }
                elevation = dp(10).toFloat()
            }

            val orb = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2563EB"))
                }
            }
            orbView = orb
            startOrbPulse(orb)

            val ticker = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(122), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                }
                text = "LivePilot AI"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            tickerTextView = ticker

            val muteBtn = createPillActionButton("MIC", "#1E293B", "#E2E8F0") {
                toggleMute()
            }
            muteButton = muteBtn

            val visionBtn = createPillActionButton("EYE", "#4C1D95", "#DDD6FE") {
                triggerManualVisionCapture()
            }

            val stopBtn = createPillActionButton("STOP", "#DC2626", "#FFFFFF") {
                emergencyStopActions()
            }

            val endBtn = createPillActionButton("END", "#334155", "#FCA5A5") {
                endCallSession()
            }

            container.addView(orb)
            container.addView(ticker)
            container.addView(muteBtn)
            container.addView(visionBtn)
            container.addView(stopBtn)
            container.addView(endBtn)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(14)
                y = dp(74)
            }

            setupDragTouchListener(container, params, wm)

            overlayParams = params
            overlayView = container
            runCatching { wm.addView(container, params) }
                .onFailure { err ->
                    PilotStateHub.log(LogLevel.ERROR, "Overlay", "Failed to add overlay: ${err.message}")
                    overlayView = null
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            minHeight = dp(34)
            minWidth = dp(38)
            setPadding(dp(7), dp(5), dp(7), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(bgHex))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setupDragTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { wm.updateViewLayout(view, params) }
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
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.88f, 1.24f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.88f, 1.24f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.72f, 1.0f)
        ).apply {
            duration = 850L
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
                    AgentActivity.LISTENING -> Color.parseColor("#3B82F6") // Cobalt Blue
                    AgentActivity.SPEAKING -> Color.parseColor("#10B981")  // Emerald Green
                    AgentActivity.EXECUTING_TOOL, AgentActivity.SCROLLING -> Color.parseColor("#F59E0B") // Amber
                    AgentActivity.MUTED -> Color.parseColor("#64748B")
                    AgentActivity.IDLE -> Color.parseColor("#7C3AED")
                }
                (orbView?.background as? GradientDrawable)?.setColor(orbColor)

                muteButton?.text = if (state.isMuted) "OFF" else "MIC"
                (muteButton?.background as? GradientDrawable)?.setColor(
                    Color.parseColor(if (state.isMuted) "#B45309" else "#1E293B")
                )
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
            orbView = null
            tickerTextView = null
            muteButton = null
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

    private fun buildPersistentNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingCallOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", stopIntent)
            .build()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    override fun onDestroy() {
        serviceScope.cancel()
        removeFloatingOverlay()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "livepilot_active_call_channel"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.example.livepilot.START_CALL"
        const val ACTION_STOP = "com.example.livepilot.STOP_CALL"
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

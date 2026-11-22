package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.AgentActivity
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import com.example.data.SafetyGuardrails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PilotAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var continuousScrollJob: Job? = null

    @Volatile
    private var lastIndexedNodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        PilotStateHub.log(LogLevel.INFO, "Accessibility", "PilotAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Live window tree is inspected on-demand by tool calls for zero idle CPU drain.
    }

    override fun onInterrupt() {
        stopContinuousScroll()
    }

    override fun onDestroy() {
        stopContinuousScroll()
        clearCachedNodes()
        serviceScope.cancel()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun isCurrentScreenBlocked(): Boolean =
        SafetyGuardrails.isPackageBlocked(currentForegroundPackage())

    private fun currentForegroundPackage(): String? =
        rootInActiveWindow?.packageName?.toString()

    private fun checkSafetyGuardrail(): JSONObject? {
        val pkg = currentForegroundPackage()
        if (SafetyGuardrails.isPackageBlocked(pkg)) {
            stopContinuousScroll()
            val warning = SafetyGuardrails.blockedWarningMessage(pkg)
            PilotStateHub.log(LogLevel.SAFETY, "Guardrail", "Blocked action inside protected app: $pkg")
            PilotStateHub.updateSession { it.copy(statusTicker = "Safety shield active ($pkg)") }
            return JSONObject()
                .put("status", "blocked")
                .put("blocked_package", pkg)
                .put("message", warning)
        }
        return null
    }

    private fun emitTouchRipple(x: Int, y: Int, label: String = "") {
        if (PilotStateHub.settings.value.showTouchVisualizer && x > 0 && y > 0) {
            PilotStateHub.activeController?.showTouchIndicator(x, y, label)
        }
    }

    fun getDeviceStatusJson(): JSONObject {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volPct = (curVol * 100) / maxVol
        val ringer = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "NORMAL"
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val networkType = when {
            caps == null -> "OFFLINE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE_DATA"
            else -> "CONNECTED"
        }

        val now = Date()
        val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
        val dateFmt = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(now)
        val dm = resources.displayMetrics

        return JSONObject()
            .put("status", "ok")
            .put("battery_percent", batteryPct)
            .put("is_charging", isCharging)
            .put("local_time", timeFmt)
            .put("local_date", dateFmt)
            .put("network", networkType)
            .put("media_volume_percent", volPct)
            .put("ringer_mode", ringer)
            .put("foreground_package", currentForegroundPackage().orEmpty())
            .put("screen_width", dm.widthPixels)
            .put("screen_height", dm.heightPixels)
    }

    fun toggleFlashlight(enabled: Boolean): JSONObject {
        return runCatching {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val rearWithFlash = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facingBack = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && facingBack
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (rearWithFlash == null) {
                return JSONObject().put("status", "error").put("message", "No flashlight hardware found.")
            }
            cameraManager.setTorchMode(rearWithFlash, enabled)
            PilotStateHub.log(LogLevel.TOOL, "Flashlight", "Torch set to $enabled")
            JSONObject().put("status", "ok").put("flashlight_on", enabled)
        }.getOrElse { err ->
            PilotStateHub.log(LogLevel.ERROR, "Flashlight", "Torch error: ${err.message}")
            JSONObject().put("status", "error").put("message", err.message ?: "Failed to toggle flashlight")
        }
    }

    fun setAlarmOrTimer(mode: String, hours: Int, minutes: Int, label: String): JSONObject =
        setAlarmOrTimerWithContext(this, mode, hours, minutes, label)

    fun clipboardAction(action: String, text: String): JSONObject {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return when (action.trim().uppercase()) {
            "WRITE" -> {
                clipboard.setPrimaryClip(ClipData.newPlainText("LivePilot", text))
                PilotStateHub.log(LogLevel.TOOL, "Clipboard", "Copied ${text.length} chars to clipboard")
                JSONObject().put("status", "ok").put("action", "WRITE").put("copied_length", text.length)
            }
            "READ" -> {
                val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                JSONObject().put("status", "ok").put("action", "READ").put("text", clipText.take(600))
            }
            else -> JSONObject().put("status", "error").put("message", "Unknown clipboard action: $action")
        }
    }

    fun openAppByName(rawQuery: String): JSONObject {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return JSONObject().put("status", "error").put("message", "App name was empty.")
        }

        val normalizedQuery = resolveAlias(query)
        val pm = packageManager

        DIRECT_PACKAGE_HINTS[normalizedQuery.lowercase()]?.forEach { candidatePkg ->
            if (SafetyGuardrails.isPackageBlocked(candidatePkg)) {
                return JSONObject()
                    .put("status", "blocked")
                    .put("message", SafetyGuardrails.blockedWarningMessage(candidatePkg))
            }
            val launchIntent = pm.getLaunchIntentForPackage(candidatePkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                PilotStateHub.log(LogLevel.TOOL, "open_app", "Launched $candidatePkg for '$query'")
                return JSONObject()
                    .put("status", "ok")
                    .put("opened_app", normalizedQuery)
                    .put("package", candidatePkg)
            }
        }

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val targetLower = normalizedQuery.lowercase()

        var bestPkg: String? = null
        var bestLabel: String? = null
        var bestScore = 0

        for (resolveInfo in apps) {
            val pkg = resolveInfo.activityInfo.packageName
            val label = resolveInfo.loadLabel(pm).toString()
            val labelLower = label.lowercase()
            val score = when {
                labelLower == targetLower -> 100
                labelLower.startsWith(targetLower) -> 85
                labelLower.contains(targetLower) -> 70
                pkg.lowercase().contains(targetLower.replace(" ", "")) -> 60
                targetLower.contains(labelLower) && labelLower.length >= 3 -> 55
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestPkg = pkg
                bestLabel = label
            }
        }

        if (bestPkg == null) {
            return JSONObject()
                .put("status", "not_found")
                .put("message", "Could not find an installed app matching '$query'.")
        }

        if (SafetyGuardrails.isPackageBlocked(bestPkg)) {
            return JSONObject()
                .put("status", "blocked")
                .put("message", SafetyGuardrails.blockedWarningMessage(bestPkg))
        }

        val launchIntent = pm.getLaunchIntentForPackage(bestPkg)
            ?: return JSONObject().put("status", "error").put("message", "No launch intent for $bestPkg")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        PilotStateHub.log(LogLevel.TOOL, "open_app", "Launched $bestLabel ($bestPkg)")
        return JSONObject()
            .put("status", "ok")
            .put("opened_app", bestLabel)
            .put("package", bestPkg)
    }

    fun searchYouTube(rawQuery: String): JSONObject {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Search query was empty.")
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val ytUri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        val ytIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedYt = runCatching {
            startActivity(ytIntent)
            true
        }.getOrDefault(false)

        if (!launchedYt) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { startActivity(fallbackIntent) }
                .onFailure { err ->
                    return JSONObject().put("status", "error").put("message", err.message ?: "Could not open YouTube")
                }
        }

        PilotStateHub.log(LogLevel.TOOL, "search_youtube", "Opened YouTube search for '$query'")
        return JSONObject()
            .put("status", "ok")
            .put("query", query)
            .put("in_youtube_app", launchedYt)
    }

    suspend fun clickByText(rawQuery: String, exact: Boolean): JSONObject {
        checkSafetyGuardrail()?.let { return it }
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Query text cannot be empty.")
        }
        if (!exact) {
            return SmartSearchAndFinder.findAndInteract(
                service = this,
                rawTarget = query,
                rawAction = "CLICK",
                maxScrolls = 3
            )
        }
        val hit = SmartSearchAndFinder.findBestMatchingNodeEnhanced(
            root = rootInActiveWindow,
            rawQuery = query,
            exact = true
        )
        if (hit == null) {
            return SmartSearchAndFinder.findAndInteract(
                service = this,
                rawTarget = query,
                rawAction = "CLICK",
                maxScrolls = 2
            )
        }
        val rect = Rect()
        hit.getBoundsInScreen(rect)
        val label = (hit.text ?: hit.contentDescription ?: query).toString().take(80)
        emitTouchRipple(rect.centerX(), rect.centerY(), label)
        if (tryClickNodeOrParent(hit)) {
            PilotStateHub.log(LogLevel.TOOL, "click_by_text", "Clicked '$label' via ACTION_CLICK")
            return JSONObject().put("status", "ok").put("matched", label).put("method", "accessibility_click")
        }
        val tapOk = dispatchTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat(), 60L)
        return JSONObject()
            .put("status", if (tapOk) "ok" else "error")
            .put("matched", label)
            .put("method", "gesture_tap")
            .put("x", rect.centerX())
            .put("y", rect.centerY())
    }

    fun deviceControl(action: String, value: String): JSONObject {
        val act = action.trim().uppercase()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when (act) {
            "VOLUME_UP" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
                JSONObject().put("status", "ok").put("action", act)
            }
            "VOLUME_DOWN" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                JSONObject().put("status", "ok").put("action", act)
            }
            "VOLUME_MUTE" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                JSONObject().put("status", "ok").put("action", act)
            }
            "MEDIA_PLAY_PAUSE" -> {
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                JSONObject().put("status", "ok").put("action", act)
            }
            "MEDIA_NEXT" -> {
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
                JSONObject().put("status", "ok").put("action", act)
            }
            "MEDIA_PREVIOUS" -> {
                dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                JSONObject().put("status", "ok").put("action", act)
            }
            "OPEN_URL" -> {
                val url = value.trim().let {
                    if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val ok = runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
                JSONObject().put("status", if (ok) "ok" else "error").put("url", url)
            }
            "DIAL_NUMBER" -> {
                val cleanNum = value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                if (cleanNum.isBlank()) {
                    return JSONObject().put("status", "error").put("message", "No valid phone number provided.")
                }
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNum")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val ok = runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
                JSONObject().put("status", if (ok) "ok" else "error").put("number", cleanNum)
            }
            "WHATSAPP_CHAT" -> {
                val digits = value.filter { it.isDigit() }
                if (digits.isBlank()) {
                    return JSONObject().put("status", "error").put("message", "Provide phone digits with country code.")
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val ok = runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
                JSONObject().put("status", if (ok) "ok" else "error").put("number", digits)
            }
            else -> JSONObject().put("status", "error").put("message", "Unknown device_control action: $action")
        }
    }

    private fun dispatchMediaKey(audioManager: AudioManager, keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    @Suppress("DEPRECATION")
    fun getScreenUiJson(): JSONObject {
        checkSafetyGuardrail()?.let { return it }

        val root = rootInActiveWindow
            ?: return JSONObject().put("status", "error").put("message", "No active window root available.")

        val dm = resources.displayMetrics
        val pkg = root.packageName?.toString().orEmpty()

        val newNodes = mutableListOf<AccessibilityNodeInfo>()
        val elements = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val maxNodes = 150
        while (queue.isNotEmpty() && newNodes.size < maxNodes) {
            val node = queue.removeFirst()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            if (!node.isVisibleToUser) continue
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val shortId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val meaningful = clickable || editable || text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty()
            if (!meaningful) continue

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 2 || rect.height() <= 2) continue
            // Skip elements completely outside the visible viewport
            if (rect.bottom < 0 || rect.top > dm.heightPixels || rect.right < 0 || rect.left > dm.widthPixels) continue

            val idx = newNodes.size
            newNodes.add(node)

            val item = JSONObject()
                .put("index", idx)
                .put("cx", rect.centerX())
                .put("cy", rect.centerY())
                .put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
                .put("clickable", clickable)
                .put("editable", editable)
            if (scrollable) item.put("scrollable", true)
            if (node.isCheckable) item.put("checked", node.isChecked)
            if (node.isFocused) item.put("focused", true)
            if (text.isNotEmpty()) item.put("text", text.take(100))
            if (desc.isNotEmpty()) item.put("desc", desc.take(100))
            if (hint.isNotEmpty()) item.put("hint", hint.take(60))
            if (shortId.isNotEmpty() && (editable || shortId.contains("search", true) || shortId.contains("url", true))) {
                item.put("id", shortId.take(40))
            }
            elements.put(item)
        }
        lastIndexedNodes = newNodes.toList()

        return JSONObject()
            .put("status", "ok")
            .put("package", pkg)
            .put("screen_width", dm.widthPixels)
            .put("screen_height", dm.heightPixels)
            .put("element_count", elements.length())
            .put("elements", elements)
    }

    /**
     * Captures the current screen as a JPEG byte array, hiding our floating overlay briefly.
     * When [withCoordinateGrid] is true, draws a subtle 0..1000 coordinate ruler grid onto the
     * in-memory JPEG copy (never shown on screen) so Gemini Vision targets coordinates accurately.
     */
    suspend fun captureVisionJpegBytes(
        withCoordinateGrid: Boolean = true,
        maxWidth: Int = 896
    ): ByteArray? {
        if (SafetyGuardrails.isPackageBlocked(currentForegroundPackage())) return null

        val controller = PilotStateHub.activeController
        controller?.setOverlayTemporarilyHidden(true)
        return try {
            delay(115)

            val jpegResult = CompletableDeferred<ByteArray?>()
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            try {
                                val rawBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                if (rawBitmap == null) {
                                    jpegResult.complete(null)
                                    return
                                }
                                val softBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                rawBitmap.recycle()

                                val scaled = scaleBitmapToMaxWidth(softBitmap, maxWidth)
                                if (scaled !== softBitmap) softBitmap.recycle()

                                val finalBitmap = if (withCoordinateGrid) {
                                    val gridded = DrawingGestureHelper.drawCoordinateGridOverlay(scaled)
                                    scaled.recycle()
                                    gridded
                                } else {
                                    scaled
                                }

                                val baos = ByteArrayOutputStream()
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 74, baos)
                                finalBitmap.recycle()
                                jpegResult.complete(baos.toByteArray())
                            } catch (t: Throwable) {
                                PilotStateHub.log(LogLevel.ERROR, "Vision", "Bitmap encode error: ${t.message}")
                                jpegResult.complete(null)
                            } finally {
                                hwBuffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            PilotStateHub.log(LogLevel.ERROR, "Vision", "takeScreenshot failed code=$errorCode")
                            jpegResult.complete(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                PilotStateHub.log(LogLevel.ERROR, "Vision", "takeScreenshot threw: ${t.message}")
                jpegResult.complete(null)
            }

            withTimeoutOrNull(4500L) { jpegResult.await() }
        } finally {
            controller?.setOverlayTemporarilyHidden(false)
        }
    }

    suspend fun captureScreenVisionAndStream(): JSONObject {
        checkSafetyGuardrail()?.let { return it }

        val dm = resources.displayMetrics
        val controller = PilotStateHub.activeController
        val jpegBytes = captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 768)

        if (jpegBytes == null) {
            return JSONObject()
                .put("status", "error")
                .put("message", "Could not capture screen screenshot (rate limit or permission).")
        }

        val streamed = controller?.sendVisionFrame(jpegBytes) ?: false
        return JSONObject()
            .put("status", if (streamed) "ok" else "captured_not_streamed")
            .put("package", currentForegroundPackage().orEmpty())
            .put("screen_width", dm.widthPixels)
            .put("screen_height", dm.heightPixels)
            .put("jpeg_kb", jpegBytes.size / 1024)
            .put("note", "Screenshot streamed with 0..1000 coordinate ruler overlay. For drawing or complex multi-step tasks, prefer start_autonomous_task or draw_strokes.")
    }

    fun startContinuousScroll(direction: String, speed: String): JSONObject {
        checkSafetyGuardrail()?.let { return it }
        stopContinuousScroll()

        val dir = direction.trim().uppercase()
        val spd = speed.trim().uppercase()
        val intervalMs = when (spd) {
            "SLOW" -> 2200L
            "FAST" -> 800L
            else -> 1400L
        }

        PilotStateHub.updateSession {
            it.copy(
                isScrollingActive = true,
                activity = AgentActivity.SCROLLING,
                statusTicker = "Scrolling $dir ($spd)..."
            )
        }

        continuousScrollJob = serviceScope.launch {
            while (isActive) {
                if (SafetyGuardrails.isPackageBlocked(currentForegroundPackage())) {
                    PilotStateHub.log(LogLevel.SAFETY, "Scroll", "Auto-stopped scroll on protected app.")
                    stopContinuousScroll()
                    break
                }
                performSwipeGesture(dir, distancePercent = 58, durationMs = 340L)
                delay(intervalMs)
            }
        }

        PilotStateHub.log(LogLevel.TOOL, "Scroll", "Started continuous scroll dir=$dir speed=$spd")
        return JSONObject()
            .put("status", "ok")
            .put("scrolling", true)
            .put("direction", dir)
            .put("speed", spd)
    }

    fun stopContinuousScroll(): JSONObject {
        val wasRunning = continuousScrollJob?.isActive == true
        continuousScrollJob?.cancel()
        continuousScrollJob = null
        PilotStateHub.updateSession {
            it.copy(
                isScrollingActive = false,
                activity = if (it.activity == AgentActivity.SCROLLING) AgentActivity.LISTENING else it.activity,
                statusTicker = if (wasRunning) "Scrolling stopped" else it.statusTicker
            )
        }
        if (wasRunning) {
            PilotStateHub.log(LogLevel.TOOL, "Scroll", "Stopped continuous scroll.")
        }
        return JSONObject().put("status", "ok").put("was_scrolling", wasRunning)
    }

    suspend fun scrollOnce(direction: String, distancePercent: Int): JSONObject {
        checkSafetyGuardrail()?.let { return it }
        val pct = distancePercent.coerceIn(20, 85)
        val ok = performSwipeGesture(direction.uppercase(), pct, durationMs = 340L)
        return JSONObject()
            .put("status", if (ok) "ok" else "error")
            .put("direction", direction.uppercase())
            .put("distance_percent", pct)
    }

    suspend fun clickElement(index: Int, x: Int, y: Int): JSONObject {
        checkSafetyGuardrail()?.let { return it }

        if (index in lastIndexedNodes.indices) {
            val node = lastIndexedNodes[index]
            val rect = Rect()
            node.getBoundsInScreen(rect)
            emitTouchRipple(rect.centerX(), rect.centerY(), "Tap #$index")
            val clicked = tryClickNodeOrParent(node)
            if (clicked) {
                PilotStateHub.log(LogLevel.TOOL, "Click", "Clicked node #$index via ACTION_CLICK")
                return JSONObject().put("status", "ok").put("method", "accessibility_click").put("index", index)
            }
        }

        if (x > 0 && y > 0) {
            emitTouchRipple(x, y, "Tap")
            val ok = dispatchTapGesture(x.toFloat(), y.toFloat(), durationMs = 60L)
            PilotStateHub.log(LogLevel.TOOL, "Click", "Dispatched 60ms tap at ($x, $y) ok=$ok")
            return JSONObject()
                .put("status", if (ok) "ok" else "error")
                .put("method", "gesture_tap")
                .put("x", x)
                .put("y", y)
        }

        return JSONObject().put("status", "error").put("message", "Invalid node index and coordinates.")
    }

    suspend fun longPress(x: Int, y: Int, durationMs: Int): JSONObject {
        checkSafetyGuardrail()?.let { return it }
        val dur = durationMs.coerceIn(450, 2500).toLong()
        emitTouchRipple(x, y, "Hold")
        val ok = dispatchTapGesture(x.toFloat(), y.toFloat(), durationMs = dur)
        return JSONObject()
            .put("status", if (ok) "ok" else "error")
            .put("x", x)
            .put("y", y)
            .put("duration_ms", dur)
    }

    suspend fun dragGesture(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int
    ): JSONObject {
        checkSafetyGuardrail()?.let { return it }
        val dur = durationMs.coerceIn(150, 3000).toLong()
        val controller = PilotStateHub.activeController
        emitTouchRipple(startX, startY, "Drag")
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val ok = try {
            controller?.setOverlayPassThrough(true)
            dispatchPathGesture(path, dur)
        } finally {
            controller?.setOverlayPassThrough(false)
        }
        emitTouchRipple(endX, endY, "")
        PilotStateHub.log(LogLevel.TOOL, "Drag", "($startX,$startY)->($endX,$endY) ${dur}ms ok=$ok")
        return JSONObject()
            .put("status", if (ok) "ok" else "error")
            .put("from", "$startX,$startY")
            .put("to", "$endX,$endY")
            .put("duration_ms", dur)
    }

    /**
     * Dispatches a multi-stroke freehand drawing sequence using smooth quadratic Bezier Paths.
     * Checks SafetyGuardrails and coroutine cancellation before every stroke.
     */
    suspend fun dispatchDrawStrokes(
        strokes: List<DrawStrokeSpec>,
        normalizedSpace: Boolean = true,
        canvasBoundsNorm: IntArray? = null,
        onStrokeProgress: ((completed: Int, total: Int, label: String) -> Unit)? = null
    ): JSONObject {
        checkSafetyGuardrail()?.let { return it }
        if (strokes.isEmpty()) {
            return JSONObject().put("status", "error").put("message", "No valid drawing strokes provided.")
        }

        val dm = resources.displayMetrics
        val controller = PilotStateHub.activeController
        var completed = 0
        var failed = 0
        val total = strokes.size

        for ((idx, stroke) in strokes.withIndex()) {
            coroutineContext.ensureActive()
            checkSafetyGuardrail()?.let { blocked ->
                return blocked.put("completed_strokes", completed)
            }

            val screenPts = stroke.points.map { (x, y) ->
                DrawingGestureHelper.mapPointToScreen(
                    x = x,
                    y = y,
                    screenWidth = dm.widthPixels,
                    screenHeight = dm.heightPixels,
                    normalizedSpace = normalizedSpace,
                    canvasBoundsNorm = canvasBoundsNorm
                )
            }
            if (screenPts.isEmpty()) continue

            val path = DrawingGestureHelper.buildSmoothAndroidPath(screenPts)
            val durMs = DrawingGestureHelper.computeRecommendedDurationMs(screenPts, stroke.durationMs)
            val label = stroke.label.ifBlank { "Stroke ${idx + 1}" }
            onStrokeProgress?.invoke(idx + 1, total, label)

            val ok = try {
                controller?.setOverlayPassThrough(true)
                dispatchPathGesture(path, durMs)
            } finally {
                controller?.setOverlayPassThrough(false)
            }

            if (ok) completed++ else failed++
            // Small natural lift pause between strokes so web/native canvases separate strokes cleanly
            delay(55L)
        }

        PilotStateHub.log(
            LogLevel.TOOL,
            "DrawStrokes",
            "Completed $completed/$total strokes (failed=$failed)"
        )
        return JSONObject()
            .put("status", if (completed > 0) "ok" else "error")
            .put("completed_strokes", completed)
            .put("total_strokes", total)
            .put("failed_strokes", failed)
    }

    suspend fun clickNormalized(nx: Int, ny: Int, label: String = "Tap"): JSONObject {
        checkSafetyGuardrail()?.let { return it }
        val dm = resources.displayMetrics
        val (px, py) = DrawingGestureHelper.mapPointToScreen(
            x = nx.toFloat(),
            y = ny.toFloat(),
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            normalizedSpace = true
        )
        val controller = PilotStateHub.activeController
        emitTouchRipple(px.toInt(), py.toInt(), label)
        val ok = try {
            controller?.setOverlayPassThrough(true)
            dispatchTapGesture(px, py, 65L)
        } finally {
            controller?.setOverlayPassThrough(false)
        }
        PilotStateHub.log(LogLevel.TOOL, "ClickNorm", "($nx,$ny) -> (${px.toInt()},${py.toInt()}) ok=$ok")
        return JSONObject()
            .put("status", if (ok) "ok" else "error")
            .put("x", px.toInt())
            .put("y", py.toInt())
    }

    suspend fun longPressNormalized(nx: Int, ny: Int, durationMs: Int = 700): JSONObject {
        val dm = resources.displayMetrics
        val (px, py) = DrawingGestureHelper.mapPointToScreen(
            x = nx.toFloat(),
            y = ny.toFloat(),
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            normalizedSpace = true
        )
        return longPress(px.toInt(), py.toInt(), durationMs)
    }

    suspend fun dragNormalized(
        startNx: Int,
        startNy: Int,
        endNx: Int,
        endNy: Int,
        durationMs: Int = 420
    ): JSONObject {
        val dm = resources.displayMetrics
        val (sx, sy) = DrawingGestureHelper.mapPointToScreen(
            x = startNx.toFloat(),
            y = startNy.toFloat(),
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            normalizedSpace = true
        )
        val (ex, ey) = DrawingGestureHelper.mapPointToScreen(
            x = endNx.toFloat(),
            y = endNy.toFloat(),
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            normalizedSpace = true
        )
        return dragGesture(sx.toInt(), sy.toInt(), ex.toInt(), ey.toInt(), durationMs)
    }

    suspend fun typeTextNormalized(
        nx: Int,
        ny: Int,
        text: String,
        pressSend: Boolean
    ): JSONObject {
        val dm = resources.displayMetrics
        val (px, py) = DrawingGestureHelper.mapPointToScreen(
            x = nx.toFloat(),
            y = ny.toFloat(),
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            normalizedSpace = true
        )
        return typeText(index = -1, x = px.toInt(), y = py.toInt(), text = text, pressSend = pressSend)
    }

    suspend fun pinchZoomNormalized(
        centerNx: Int,
        centerNy: Int,
        zoomIn: Boolean,
        spreadNorm: Int = 180,
        durationMs: Int = 420
    ): JSONObject {
        checkSafetyGuardrail()?.let { return it }
        val dm = resources.displayMetrics
        val cx = (centerNx.coerceIn(150, 850) / 1000f) * dm.widthPixels
        val cy = (centerNy.coerceIn(150, 850) / 1000f) * dm.heightPixels
        val halfMin = (dm.widthPixels * 0.05f)
        val halfMax = (dm.widthPixels * (spreadNorm.coerceIn(80, 360) / 1000f))
        val dur = durationMs.coerceIn(220, 1200).toLong()

        val startOffset = if (zoomIn) halfMin else halfMax
        val endOffset = if (zoomIn) halfMax else halfMin

        val path1 = Path().apply {
            moveTo((cx - startOffset).coerceIn(10f, dm.widthPixels - 10f), cy)
            lineTo((cx - endOffset).coerceIn(10f, dm.widthPixels - 10f), cy)
        }
        val path2 = Path().apply {
            moveTo((cx + startOffset).coerceIn(10f, dm.widthPixels - 10f), cy)
            lineTo((cx + endOffset).coerceIn(10f, dm.widthPixels - 10f), cy)
        }

        val controller = PilotStateHub.activeController
        val ok = try {
            controller?.setOverlayPassThrough(true)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path1, 0L, dur))
                .addStroke(GestureDescription.StrokeDescription(path2, 0L, dur))
                .build()
            val deferred = CompletableDeferred<Boolean>()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        deferred.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        deferred.complete(false)
                    }
                },
                null
            )
            if (!dispatched) false else (withTimeoutOrNull(dur + 2000L) { deferred.await() } ?: false)
        } finally {
            controller?.setOverlayPassThrough(false)
        }
        PilotStateHub.log(LogLevel.TOOL, "PinchZoom", "zoomIn=$zoomIn at ($centerNx,$centerNy) ok=$ok")
        return JSONObject().put("status", if (ok) "ok" else "error").put("zoom_in", zoomIn)
    }

    suspend fun typeText(
        index: Int,
        x: Int,
        y: Int,
        text: String,
        pressSend: Boolean
    ): JSONObject {
        checkSafetyGuardrail()?.let { return it }

        val targetNode: AccessibilityNodeInfo? =
            if (index in lastIndexedNodes.indices) lastIndexedNodes[index] else null

        if (targetNode != null) {
            val r = Rect()
            targetNode.getBoundsInScreen(r)
            emitTouchRipple(r.centerX(), r.centerY(), "Type")
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else if (x > 0 && y > 0) {
            emitTouchRipple(x, y, "Type")
            dispatchTapGesture(x.toFloat(), y.toFloat(), 60L)
        }

        delay(220)

        val root = rootInActiveWindow
        val focusedEdit = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: targetNode
            ?: findFirstEditableNode(root)

        if (focusedEdit == null) {
            return JSONObject().put("status", "error").put("message", "Could not focus an editable text box.")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var setOk = focusedEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (!setOk) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LivePilotText", text))
            delay(80)
            setOk = focusedEdit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        var sentAction = "none"
        if (pressSend) {
            delay(240)
            // Always re-query FRESH focused input after typing so ACTION_IME_ENTER succeeds in Chrome & apps
            val freshRoot = rootInActiveWindow
            val freshFocused = freshRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableNode(freshRoot)
                ?: focusedEdit
            val imeSent = freshFocused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (imeSent) {
                sentAction = "ime_enter"
            } else {
                val sendBtn = findSendOrSearchButton(rootInActiveWindow)
                if (sendBtn != null && !sendBtn.isEditable && tryClickNodeOrParent(sendBtn)) {
                    sentAction = "clicked_send_button"
                } else {
                    sentAction = "send_button_not_found"
                }
            }
        }

        PilotStateHub.log(LogLevel.TOOL, "TypeText", "Typed '${text.take(40)}' ok=$setOk send=$sentAction")
        return JSONObject()
            .put("status", if (setOk) "ok" else "partial")
            .put("typed", text)
            .put("send_result", sentAction)
    }

    fun navigateGlobal(action: String): JSONObject {
        val normalized = action.trim().uppercase()
        val globalAction = when (normalized) {
            "BACK" -> GLOBAL_ACTION_BACK
            "HOME" -> GLOBAL_ACTION_HOME
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return JSONObject().put("status", "error").put("message", "Unknown navigation action: $action")
        }
        val ok = performGlobalAction(globalAction)
        return JSONObject().put("status", if (ok) "ok" else "error").put("action", normalized)
    }

    fun openNotificationChat(senderOrApp: String): JSONObject {
        val handle = PilotStateHub.findNotificationHandle(senderOrApp)
        if (handle?.contentIntent != null) {
            val sent = runCatching {
                handle.contentIntent.send()
                true
            }.getOrDefault(false)
            if (sent) {
                PilotStateHub.log(
                    LogLevel.TOOL,
                    "AutoChat",
                    "Opened ${handle.app.displayName} chat with ${handle.senderName} via PendingIntent"
                )
                return JSONObject()
                    .put("status", "ok")
                    .put("method", "notification_pending_intent")
                    .put("app", handle.app.displayName)
                    .put("sender", handle.senderName)
            }
        }

        return openAppByName(senderOrApp)
    }

    private fun expandUiSynonyms(query: String): List<String> {
        val q = query.lowercase()
        val results = mutableListOf(q)
        UI_SYNONYMS.forEach { group ->
            if (group.any { it == q || q.contains(it) }) {
                results.addAll(group)
            }
        }
        return results.distinct()
    }

    private fun findBestMatchingNode(
        root: AccessibilityNodeInfo?,
        queries: List<String>,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (!node.isVisibleToUser) continue
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val tLow = text.lowercase()
            val dLow = desc.lowercase()

            var nodeScore = 0
            for (q in queries) {
                val matched = if (exact) {
                    tLow == q || dLow == q
                } else {
                    tLow.contains(q) || dLow.contains(q)
                }
                if (!matched) continue
                val s = when {
                    (tLow == q || dLow == q) && node.isClickable -> 100
                    (tLow == q || dLow == q) -> 90
                    node.isClickable -> 75
                    else -> 50
                }
                if (s > nodeScore) nodeScore = s
            }
            if (nodeScore > bestScore) {
                bestScore = nodeScore
                bestNode = node
            }
        }
        return bestNode
    }

    private suspend fun performSwipeGesture(
        direction: String,
        distancePercent: Int,
        durationMs: Long
    ): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val deltaY = h * (distancePercent / 100f) * 0.5f
        val deltaX = w * (distancePercent / 100f) * 0.5f
        val cx = w * 0.5f
        val cy = h * 0.52f

        val path = Path()
        when (direction) {
            "DOWN" -> {
                path.moveTo(cx, (cy + deltaY).coerceAtMost(h * 0.85f))
                path.lineTo(cx, (cy - deltaY).coerceAtLeast(h * 0.18f))
            }
            "UP" -> {
                path.moveTo(cx, (cy - deltaY).coerceAtLeast(h * 0.18f))
                path.lineTo(cx, (cy + deltaY).coerceAtMost(h * 0.85f))
            }
            "LEFT" -> {
                path.moveTo((cx + deltaX).coerceAtMost(w * 0.88f), cy)
                path.lineTo((cx - deltaX).coerceAtLeast(w * 0.12f), cy)
            }
            "RIGHT" -> {
                path.moveTo((cx - deltaX).coerceAtLeast(w * 0.12f), cy)
                path.lineTo((cx + deltaX).coerceAtMost(w * 0.88f), cy)
            }
            else -> {
                path.moveTo(cx, h * 0.75f)
                path.lineTo(cx, h * 0.25f)
            }
        }
        return dispatchPathGesture(path, durationMs)
    }

    private suspend fun dispatchTapGesture(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchPathGesture(path, durationMs)
    }

    private suspend fun dispatchPathGesture(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            null
        )
        if (!dispatched) return false
        return withTimeoutOrNull(durationMs + 2500L) { deferred.await() } ?: false
    }

    private fun tryClickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findSendOrSearchButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val keywords = listOf("send", "পাঠান", "search", "খুঁজুন", "post", "reply")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser && n.isClickable) {
                val combined = "${n.text ?: ""} ${n.contentDescription ?: ""} ${n.viewIdResourceName ?: ""}".lowercase()
                if (keywords.any { combined.contains(it) }) {
                    return n
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun scaleBitmapToMaxWidth(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width.toFloat()
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxWidth, targetH, true)
    }

    private fun clearCachedNodes() {
        lastIndexedNodes = emptyList()
    }

    private fun resolveAlias(raw: String): String {
        val cleaned = raw.trim()
        BENGALI_APP_ALIASES.entries.firstOrNull { (k, _) ->
            cleaned.equals(k, ignoreCase = true) || cleaned.contains(k, ignoreCase = true)
        }?.let { return it.value }
        return cleaned
    }

    companion object {
        @Volatile
        var instance: PilotAccessibilityService? = null

        fun setAlarmOrTimerWithContext(
            context: Context,
            mode: String,
            hours: Int,
            minutes: Int,
            label: String
        ): JSONObject {
            val m = mode.trim().uppercase()
            val cleanLabel = label.trim().ifBlank { "LivePilot AI" }
            fun buildIntent(skipUi: Boolean): Intent = if (m == "ALARM") {
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hours.coerceIn(0, 23))
                    putExtra(AlarmClock.EXTRA_MINUTES, minutes.coerceIn(0, 59))
                    putExtra(AlarmClock.EXTRA_MESSAGE, cleanLabel)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                val totalSeconds = ((hours.coerceAtLeast(0) * 3600) + (minutes.coerceAtLeast(0) * 60))
                    .coerceAtLeast(10)
                Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, cleanLabel)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            return runCatching {
                runCatching { context.startActivity(buildIntent(skipUi = true)) }
                    .getOrElse { context.startActivity(buildIntent(skipUi = false)) }
                PilotStateHub.log(LogLevel.TOOL, "Clock", "Set $m ${hours}h ${minutes}m ($cleanLabel)")
                JSONObject()
                    .put("status", "ok")
                    .put("mode", m)
                    .put("hours", hours)
                    .put("minutes", minutes)
                    .put("label", cleanLabel)
            }.getOrElse { err ->
                JSONObject().put("status", "error").put("message", err.message ?: "Could not set $m")
            }
        }

        fun getDeviceStatusWithContext(context: Context, foregroundPkg: String = ""): JSONObject {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val volPct = (curVol * 100) / maxVol
            val ringer = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "SILENT"
                AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
                else -> "NORMAL"
            }

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val networkType = when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE_DATA"
                else -> "CONNECTED"
            }

            val now = Date()
            val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
            val dateFmt = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault()).format(now)
            val dm = context.resources.displayMetrics

            return JSONObject()
                .put("status", "ok")
                .put("battery_percent", batteryPct)
                .put("is_charging", isCharging)
                .put("local_time", timeFmt)
                .put("local_date", dateFmt)
                .put("network", networkType)
                .put("media_volume_percent", volPct)
                .put("ringer_mode", ringer)
                .put("foreground_package", foregroundPkg)
                .put("screen_width", dm.widthPixels)
                .put("screen_height", dm.heightPixels)
        }

        fun toggleFlashlightWithContext(context: Context, enabled: Boolean): JSONObject {
            return runCatching {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val rearWithFlash = cameraManager.cameraIdList.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    val facingBack = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    hasFlash && facingBack
                } ?: cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (rearWithFlash == null) {
                    return JSONObject().put("status", "error").put("message", "No flashlight hardware found.")
                }
                cameraManager.setTorchMode(rearWithFlash, enabled)
                PilotStateHub.log(LogLevel.TOOL, "Flashlight", "Torch set to $enabled")
                JSONObject().put("status", "ok").put("flashlight_on", enabled)
            }.getOrElse { err ->
                PilotStateHub.log(LogLevel.ERROR, "Flashlight", "Torch error: ${err.message}")
                JSONObject().put("status", "error").put("message", err.message ?: "Failed to toggle flashlight")
            }
        }

        fun clipboardActionWithContext(context: Context, action: String, text: String): JSONObject {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return when (action.trim().uppercase()) {
                "WRITE" -> {
                    clipboard.setPrimaryClip(ClipData.newPlainText("LivePilot", text))
                    PilotStateHub.log(LogLevel.TOOL, "Clipboard", "Copied ${text.length} chars to clipboard")
                    JSONObject().put("status", "ok").put("action", "WRITE").put("copied_length", text.length)
                }
                "READ" -> {
                    val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    JSONObject().put("status", "ok").put("action", "READ").put("text", clipText.take(600))
                }
                else -> JSONObject().put("status", "error").put("message", "Unknown clipboard action: $action")
            }
        }

        fun searchYouTubeWithContext(context: Context, rawQuery: String): JSONObject {
            val query = rawQuery.trim()
            if (query.isBlank()) {
                return JSONObject().put("status", "error").put("message", "Search query was empty.")
            }
            val encoded = URLEncoder.encode(query, "UTF-8")
            val ytUri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            val ytIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launchedYt = runCatching {
                context.startActivity(ytIntent)
                true
            }.getOrDefault(false)

            if (!launchedYt) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallbackIntent) }
                    .onFailure { err ->
                        return JSONObject().put("status", "error").put("message", err.message ?: "Could not open YouTube")
                    }
            }
            PilotStateHub.log(LogLevel.TOOL, "search_youtube", "Opened YouTube search for '$query'")
            return JSONObject().put("status", "ok").put("query", query).put("in_youtube_app", launchedYt)
        }

        fun deviceControlWithContext(context: Context, action: String, value: String): JSONObject {
            val act = action.trim().uppercase()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return when (act) {
                "VOLUME_UP" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    JSONObject().put("status", "ok").put("action", act)
                }
                "VOLUME_DOWN" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    JSONObject().put("status", "ok").put("action", act)
                }
                "VOLUME_MUTE" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                    JSONObject().put("status", "ok").put("action", act)
                }
                "MEDIA_PLAY_PAUSE" -> {
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    JSONObject().put("status", "ok").put("action", act)
                }
                "MEDIA_NEXT" -> {
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                    JSONObject().put("status", "ok").put("action", act)
                }
                "MEDIA_PREVIOUS" -> {
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    JSONObject().put("status", "ok").put("action", act)
                }
                "OPEN_URL" -> {
                    val url = value.trim().let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
                    }
                    val ok = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                    JSONObject().put("status", if (ok) "ok" else "error").put("url", url)
                }
                "DIAL_NUMBER" -> {
                    val cleanNum = value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    if (cleanNum.isBlank()) return JSONObject().put("status", "error").put("message", "No valid phone number provided.")
                    val ok = runCatching {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNum")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                    JSONObject().put("status", if (ok) "ok" else "error").put("number", cleanNum)
                }
                "WHATSAPP_CHAT" -> {
                    val digits = value.filter { it.isDigit() }
                    if (digits.isBlank()) return JSONObject().put("status", "error").put("message", "Provide phone digits with country code.")
                    val ok = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    }.getOrDefault(false)
                    JSONObject().put("status", if (ok) "ok" else "error").put("number", digits)
                }
                else -> JSONObject().put("status", "error").put("message", "Unknown device_control action: $action")
            }
        }

        private val UI_SYNONYMS: List<List<String>> = listOf(
            listOf("send", "পাঠান", "reply", "post"),
            listOf("search", "খুঁজুন", "সার্চ"),
            listOf("like", "লাইক", "love"),
            listOf("share", "শেয়ার", "শেয়ার"),
            listOf("subscribe", "সাবস্ক্রাইব"),
            listOf("comment", "কমেন্ট", "comments"),
            listOf("skip", "স্কিপ", "skip ad", "skip ads"),
            listOf("play", "চালু", "resume"),
            listOf("pause", "থামান", "পজ"),
            listOf("next", "পরবর্তী", "নেক্সট"),
            listOf("allow", "অনুমতি", "accept")
        )

        private val BENGALI_APP_ALIASES: Map<String, String> = mapOf(
            "ইউটিউব" to "YouTube",
            "ইউটিউব স্টুডিও" to "YouTube Studio",
            "ইমো" to "imo",
            "হোয়াটসঅ্যাপ" to "WhatsApp",
            "হোয়াটসঅ্যাপ" to "WhatsApp",
            "ফেসবুক" to "Facebook",
            "ক্যাপকাট" to "CapCut",
            "মেসেঞ্জার" to "Messenger",
            "গ্যালারি" to "Gallery",
            "ক্রোম" to "Chrome",
            "টেলিগ্রাম" to "Telegram",
            "ইনশট" to "InShot",
            "ক্যানভা" to "Canva",
            "সেটিংস" to "Settings",
            "ক্যামেরা" to "Camera",
            "টিকটক" to "TikTok",
            "ইনস্টাগ্রাম" to "Instagram",
            "স্পটিফাই" to "Spotify",
            "ম্যাপস" to "Maps",
            "গুগল ম্যাপ" to "Maps",
            "জিমেইল" to "Gmail",
            "প্লে স্টোর" to "Play Store",
            "ক্যালকুলেটর" to "Calculator",
            "ঘড়ি" to "Clock",
            "ফাইল" to "Files"
        )

        private val DIRECT_PACKAGE_HINTS: Map<String, List<String>> = mapOf(
            "youtube" to listOf("com.google.android.youtube"),
            "youtube studio" to listOf("com.google.android.apps.youtube.creator"),
            "imo" to listOf("com.imo.android.imoim", "com.imo.android.imoimhd", "com.imo.android.imoimbeta"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
            "capcut" to listOf("com.lemon.lvoverseas"),
            "inshot" to listOf("com.camerasideas.instashot"),
            "canva" to listOf("com.canva.editor"),
            "vn" to listOf("com.frontrow.vlog"),
            "picsart" to listOf("com.picsart.studio"),
            "lightroom" to listOf("com.adobe.lrmobile"),
            "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
            "chrome" to listOf("com.android.chrome"),
            "spotify" to listOf("com.spotify.music"),
            "instagram" to listOf("com.instagram.android"),
            "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "maps" to listOf("com.google.android.apps.maps"),
            "gmail" to listOf("com.google.android.gm"),
            "play store" to listOf("com.android.vending")
        )
    }
}

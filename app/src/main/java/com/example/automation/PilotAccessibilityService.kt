package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class PilotAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var continuousScrollJob: Job? = null
    private val lastIndexedNodes = mutableListOf<AccessibilityNodeInfo>()

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
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

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

    fun openAppByName(rawQuery: String): JSONObject {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return JSONObject().put("status", "error").put("message", "App name was empty.")
        }

        val normalizedQuery = resolveAlias(query)
        val pm = packageManager

        // Direct package override check first
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

        // Fuzzy match all launchable apps
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

    fun getScreenUiJson(): JSONObject {
        checkSafetyGuardrail()?.let { return it }

        val root = rootInActiveWindow
            ?: return JSONObject().put("status", "error").put("message", "No active window root available.")

        val dm = resources.displayMetrics
        val pkg = root.packageName?.toString().orEmpty()

        clearCachedNodes()
        val elements = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val maxNodes = 85
        while (queue.isNotEmpty() && lastIndexedNodes.size < maxNodes) {
            val node = queue.removeFirst()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            if (!node.isVisibleToUser) continue
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val meaningful = clickable || editable || text.isNotEmpty() || desc.isNotEmpty()
            if (!meaningful) continue

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 2 || rect.height() <= 2) continue

            val idx = lastIndexedNodes.size
            lastIndexedNodes.add(node)

            val item = JSONObject()
                .put("index", idx)
                .put("cx", rect.centerX())
                .put("cy", rect.centerY())
                .put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
                .put("clickable", clickable)
                .put("editable", editable)
            if (text.isNotEmpty()) item.put("text", text.take(100))
            if (desc.isNotEmpty()) item.put("desc", desc.take(100))
            elements.put(item)
        }

        return JSONObject()
            .put("status", "ok")
            .put("package", pkg)
            .put("screen_width", dm.widthPixels)
            .put("screen_height", dm.heightPixels)
            .put("element_count", elements.length())
            .put("elements", elements)
    }

    suspend fun captureScreenVisionAndStream(): JSONObject {
        checkSafetyGuardrail()?.let { return it }

        val dm = resources.displayMetrics
        val controller = PilotStateHub.activeController

        // Temporarily make overlay invisible/non-touchable so it never blocks UI beneath
        controller?.setOverlayTemporarilyHidden(true)
        delay(120)

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

                            val scaled = scaleBitmapToMaxWidth(softBitmap, 768)
                            if (scaled !== softBitmap) softBitmap.recycle()

                            val baos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                            scaled.recycle()
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

        val jpegBytes = withTimeoutOrNull(4500L) { jpegResult.await() }
        controller?.setOverlayTemporarilyHidden(false)

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
            .put("note", "Screenshot streamed into live vision context. Use exact screen_width/screen_height coordinates for taps and drags.")
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
            val clicked = tryClickNodeOrParent(node)
            if (clicked) {
                PilotStateHub.log(LogLevel.TOOL, "Click", "Clicked node #$index via ACTION_CLICK")
                return JSONObject().put("status", "ok").put("method", "accessibility_click").put("index", index)
            }
        }

        if (x > 0 && y > 0) {
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
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val ok = dispatchPathGesture(path, dur)
        PilotStateHub.log(LogLevel.TOOL, "Drag", "($startX,$startY)->($endX,$endY) ${dur}ms ok=$ok")
        return JSONObject()
            .put("status", if (ok) "ok" else "error")
            .put("from", "$startX,$startY")
            .put("to", "$endX,$endY")
            .put("duration_ms", dur)
    }

    suspend fun typeText(
        index: Int,
        x: Int,
        y: Int,
        text: String,
        pressSend: Boolean
    ): JSONObject {
        checkSafetyGuardrail()?.let { return it }

        var targetNode: AccessibilityNodeInfo? =
            if (index in lastIndexedNodes.indices) lastIndexedNodes[index] else null

        if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else if (x > 0 && y > 0) {
            dispatchTapGesture(x.toFloat(), y.toFloat(), 60L)
        }

        delay(220)

        // Re-locate focused editable node in active window
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

        // Clipboard + ACTION_PASTE fallback for custom chat inputs (imo, etc.)
        if (!setOk) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LivePilotText", text))
            delay(80)
            setOk = focusedEdit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        var sentAction = "none"
        if (pressSend) {
            delay(180)
            val sendBtn = findSendOrSearchButton(rootInActiveWindow)
            if (sendBtn != null && tryClickNodeOrParent(sendBtn)) {
                sentAction = "clicked_send_button"
            } else {
                val imeSent = focusedEdit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                sentAction = if (imeSent) "ime_enter" else "send_button_not_found"
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

        // Fallback: open the messaging app directly
        return openAppByName(senderOrApp)
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
                // Scroll down content => swipe finger UP
                path.moveTo(cx, (cy + deltaY).coerceAtMost(h * 0.85f))
                path.lineTo(cx, (cy - deltaY).coerceAtLeast(h * 0.18f))
            }
            "UP" -> {
                // Scroll up content => swipe finger DOWN
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
        lastIndexedNodes.clear()
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

        private val BENGALI_APP_ALIASES: Map<String, String> = mapOf(
            "ইউটিউব" to "YouTube",
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
            "ইনস্টাগ্রাম" to "Instagram"
        )

        private val DIRECT_PACKAGE_HINTS: Map<String, List<String>> = mapOf(
            "youtube" to listOf("com.google.android.youtube"),
            "imo" to listOf("com.imo.android.imoim", "com.imo.android.imoimhd", "com.imo.android.imoimbeta"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "messenger" to listOf("com.facebook.orca", "com.facebook.mlite"),
            "capcut" to listOf("com.lemon.lvoverseas"),
            "inshot" to listOf("com.camerasideas.instashot"),
            "canva" to listOf("com.canva.editor"),
            "telegram" to listOf("org.telegram.messenger"),
            "chrome" to listOf("com.android.chrome")
        )
    }
}

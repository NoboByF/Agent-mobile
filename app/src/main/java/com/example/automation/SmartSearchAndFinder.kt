package com.example.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ai.GeminiRestClient
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Universal Search & Hybrid (Accessibility + Vision) Element Finder.
 * Solves in-app search failures, stale EditText IME submission bugs, multi-scroll item discovery,
 * Bengali/English UI synonym matching, and WebView/browser visual fallback.
 */
object SmartSearchAndFinder {

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.UCMobile.intl"
    )

    private val SEARCH_HINTS = listOf(
        "search", "খুঁজুন", "সার্চ", "খোঁজ", "find", "lookup", "query",
        "url_bar", "omnibox", "search_box", "search_src_text", "search_button",
        "action_search", "menu_search", "search_icon", "search_bar"
    )

    private val BENGALI_ENGLISH_SYNONYMS: List<List<String>> = listOf(
        listOf("search", "সার্চ", "খুঁজুন", "খোঁজ", "খুঁজে", "find"),
        listOf("send", "পাঠান", "পাঠাও", "reply", "post", "submit"),
        listOf("next", "পরবর্তী", "আগে", "continue", "proceed"),
        listOf("back", "পেছনে", "ফিরে", "navigate up"),
        listOf("settings", "সেটিংস", "সেটিং"),
        listOf("download", "ডাউনলোড", "save", "সেভ"),
        listOf("delete", "ডিলিট", "মুছুন", "remove", "trash"),
        listOf("share", "শেয়ার", "শেয়ার"),
        listOf("subscribe", "সাবস্ক্রাইব"),
        listOf("like", "লাইক"),
        listOf("comment", "কমেন্ট", "মন্তব্য"),
        listOf("play", "প্লে", "চালাও", "চালান", "watch"),
        listOf("pause", "পজ", "থামাও"),
        listOf("skip", "স্কিপ", "skip ad", "skip ads"),
        listOf("allow", "অনুমতি", "accept", "agree", "ok", "ঠিক আছে"),
        listOf("cancel", "বাতিল", "close", "বন্ধ", "dismiss"),
        listOf("login", "লগইন", "sign in", "সাইন ইন"),
        listOf("history", "হিস্ট্রি", "ইতিহাস"),
        listOf("profile", "প্রোফাইল", "account", "অ্যাকাউন্ট"),
        listOf("channel", "চ্যানেল"),
        listOf("export", "এক্সপোর্ট"),
        listOf("edit", "এডিট", "সম্পাদনা"),
        listOf("copy", "কপি"),
        listOf("paste", "পেস্ট"),
        listOf("dark mode", "ডার্ক মোড", "dark theme"),
        listOf("wi-fi", "wifi", "ওয়াইফাই", "ওয়াইফাই"),
        listOf("bluetooth", "ব্লুটুথ"),
        listOf("battery", "ব্যাটারি"),
        listOf("display", "ডিসপ্লে", "brightness"),
        listOf("sound", "সাউন্ড", "ভলিউম", "volume"),
        listOf("notifications", "নোটিফিকেশন"),
        listOf("privacy", "প্রাইভেসি", "security", "সিকিউরিটি")
    )

    suspend fun searchOnScreen(
        service: PilotAccessibilityService,
        appContext: Context,
        rawQuery: String,
        openInGoogleIfNeeded: Boolean = false
    ): JSONObject {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Search query was empty.")
        }

        val currentPkg = service.rootInActiveWindow?.packageName?.toString().orEmpty()

        // Direct fast-path for YouTube app
        if (currentPkg == "com.google.android.youtube" && !openInGoogleIfNeeded) {
            return PilotAccessibilityService.searchYouTubeWithContext(appContext, query)
        }

        // Direct fast-path for Google Web Search when requested or when in browser/launcher/self
        val isHomeOrSelf = currentPkg.isBlank() ||
            currentPkg == appContext.packageName ||
            currentPkg.contains("launcher", ignoreCase = true) ||
            currentPkg.contains("nexuslauncher", ignoreCase = true) ||
            currentPkg.contains("trebuchet", ignoreCase = true)

        if (openInGoogleIfNeeded || isHomeOrSelf) {
            return openGoogleWebSearch(appContext, query)
        }

        // Step 1: Check if an editable input field is already visible on screen
        var editNode = findBestSearchEditNode(service.rootInActiveWindow)

        // Step 2: If no search input is open yet, find & tap the Search icon/bar
        if (editNode == null) {
            val searchTrigger = findSearchTriggerNode(service.rootInActiveWindow)
            if (searchTrigger != null) {
                val r = Rect()
                searchTrigger.getBoundsInScreen(r)
                PilotStateHub.activeController?.showTouchIndicator(r.centerX(), r.centerY(), "Search")
                val clicked = tryClickNodeOrAncestors(searchTrigger)
                if (!clicked && r.width() > 0 && r.height() > 0) {
                    service.clickElement(index = -1, x = r.centerX(), y = r.centerY())
                }
                delay(520L)
                editNode = findBestSearchEditNode(service.rootInActiveWindow)
            }
        }

        // Step 3: Vision fallback to locate magnifying glass / search box if Accessibility missed it
        if (editNode == null && PilotStateHub.settings.value.hasAnyApiKey) {
            val visionHit = locateElementViaVision(
                service = service,
                targetDescription = "Search icon (magnifying glass) or Search input box"
            )
            if (visionHit != null) {
                service.clickNormalized(visionHit.first, visionHit.second, "Search")
                delay(580L)
                editNode = findBestSearchEditNode(service.rootInActiveWindow)
            }
        }

        // If still no search field found inside a browser, fall back cleanly to Google Search URL
        if (editNode == null) {
            if (currentPkg in BROWSER_PACKAGES) {
                return openGoogleWebSearch(appContext, query)
            }
            return JSONObject()
                .put("status", "not_found")
                .put("message", "Could not find a search box or search icon in the current app ($currentPkg).")
        }

        // Step 4: Focus and enter query cleanly (ACTION_SET_TEXT + Clipboard paste fallback)
        val rect = Rect()
        editNode.getBoundsInScreen(rect)
        PilotStateHub.activeController?.showTouchIndicator(rect.centerX(), rect.centerY(), "Search")
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(220L)

        val freshInput = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findBestSearchEditNode(service.rootInActiveWindow)
            ?: editNode

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        var typedOk = freshInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (!typedOk) {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LivePilotSearch", query))
            delay(90L)
            typedOk = freshInput.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        delay(260L)

        // Step 5: Re-fetch FRESH focused node after typing so ACTION_IME_ENTER actually works!
        val submitTarget = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findBestSearchEditNode(service.rootInActiveWindow)
            ?: freshInput

        var submitMethod = "none"
        val imeEnterOk = submitTarget.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (imeEnterOk) {
            submitMethod = "ime_enter"
        } else {
            val submitBtn = findExplicitSubmitButton(service.rootInActiveWindow, excludeNode = submitTarget)
            if (submitBtn != null && tryClickNodeOrAncestors(submitBtn)) {
                submitMethod = "submit_button"
            } else {
                // Tap first autocomplete suggestion matching the query if IME enter wasn't supported
                val suggestion = findBestMatchingNodeEnhanced(
                    root = service.rootInActiveWindow,
                    rawQuery = query,
                    exact = false,
                    excludeEditable = true
                )
                if (suggestion != null && tryClickNodeOrAncestors(suggestion)) {
                    submitMethod = "autocomplete_suggestion"
                }
            }
        }

        PilotStateHub.log(
            LogLevel.TOOL,
            "SmartSearch",
            "Searched '$query' in $currentPkg (typed=$typedOk, submit=$submitMethod)"
        )
        return JSONObject()
            .put("status", if (typedOk) "ok" else "partial")
            .put("query", query)
            .put("package", currentPkg)
            .put("submit_method", submitMethod)
    }

    fun openGoogleWebSearch(context: Context, query: String): JSONObject {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://www.google.com/search?q=$encoded"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        PilotStateHub.log(LogLevel.TOOL, "GoogleSearch", "Opened browser Google Search for '$query' ok=$ok")
        return JSONObject()
            .put("status", if (ok) "ok" else "error")
            .put("method", "google_browser_search")
            .put("query", query)
            .put("url", url)
    }

    /**
     * Finds a specific UI item, setting, video, contact, button, or icon on screen using:
     * 1) Multi-scroll enhanced fuzzy Bengali+English Accessibility search (up to maxScrolls)
     * 2) Automatic Gemini Vision coordinate lookup fallback for WebViews/graphics/thumbnails
     */
    suspend fun findAndInteract(
        service: PilotAccessibilityService,
        rawTarget: String,
        rawAction: String = "CLICK",
        maxScrolls: Int = 4
    ): JSONObject {
        val target = rawTarget.trim()
        if (target.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Target name cannot be empty.")
        }
        val action = rawAction.trim().uppercase()
        val scrollLimit = maxScrolls.coerceIn(0, 6)

        var lastFingerprint = ""
        var matchedNode: AccessibilityNodeInfo? = null
        var scrollsDone = 0

        for (attempt in 0..scrollLimit) {
            matchedNode = findBestMatchingNodeEnhanced(
                root = service.rootInActiveWindow,
                rawQuery = target,
                exact = false,
                excludeEditable = false
            )
            if (matchedNode != null) break

            if (attempt < scrollLimit) {
                val fp = buildScreenTextFingerprint(service.rootInActiveWindow)
                if (attempt > 0 && fp == lastFingerprint && fp.isNotBlank()) {
                    break // Reached bottom of scrollable list
                }
                lastFingerprint = fp
                service.scrollOnce("DOWN", 60)
                scrollsDone++
                delay(520L)
            }
        }

        if (matchedNode != null) {
            val rect = Rect()
            matchedNode.getBoundsInScreen(rect)
            val matchedLabel = extractCombinedNodeLabel(matchedNode).ifBlank { target }.take(80)

            if (action == "CHECK" || action == "SCROLL_TO") {
                return JSONObject()
                    .put("status", "ok")
                    .put("found", true)
                    .put("matched", matchedLabel)
                    .put("x", rect.centerX())
                    .put("y", rect.centerY())
                    .put("scrolls_done", scrollsDone)
            }

            PilotStateHub.activeController?.showTouchIndicator(rect.centerX(), rect.centerY(), matchedLabel.take(18))
            val clicked = tryClickNodeOrAncestors(matchedNode)
            if (clicked) {
                PilotStateHub.log(LogLevel.TOOL, "FindItem", "Clicked '$matchedLabel' after $scrollsDone scroll(s)")
                return JSONObject()
                    .put("status", "ok")
                    .put("method", "accessibility_click")
                    .put("matched", matchedLabel)
                    .put("scrolls_done", scrollsDone)
            }

            val tapRes = service.clickElement(index = -1, x = rect.centerX(), y = rect.centerY())
            return tapRes
                .put("matched", matchedLabel)
                .put("scrolls_done", scrollsDone)
        }

        // Hybrid Vision fallback: check current screen visually via 0..1000 gridded screenshot!
        if (PilotStateHub.settings.value.hasAnyApiKey) {
            val visionHit = locateElementViaVision(service, target)
            if (visionHit != null) {
                if (action == "CHECK" || action == "SCROLL_TO") {
                    return JSONObject()
                        .put("status", "ok")
                        .put("found", true)
                        .put("method", "vision_lookup")
                        .put("norm_x", visionHit.first)
                        .put("norm_y", visionHit.second)
                        .put("scrolls_done", scrollsDone)
                }
                val clickRes = service.clickNormalized(visionHit.first, visionHit.second, target.take(16))
                return clickRes
                    .put("method", "vision_click")
                    .put("matched", target)
                    .put("scrolls_done", scrollsDone)
            }
        }

        return JSONObject()
            .put("status", "not_found")
            .put("found", false)
            .put("scrolls_done", scrollsDone)
            .put("message", "Could not find '$target' after checking screen and $scrollsDone scroll(s).")
    }

    fun expandSynonyms(query: String): List<String> {
        val q = query.trim().lowercase()
        val results = mutableListOf(q)
        BENGALI_ENGLISH_SYNONYMS.forEach { group ->
            if (group.any { term -> q == term || q.contains(term) || term.contains(q) && q.length >= 3 }) {
                results.addAll(group)
            }
        }
        return results.distinct()
    }

    fun findBestMatchingNodeEnhanced(
        root: AccessibilityNodeInfo?,
        rawQuery: String,
        exact: Boolean,
        excludeEditable: Boolean = false
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val terms = expandSynonyms(rawQuery)
        val queryTokens = rawQuery.lowercase()
            .split(Regex("[\\s,._:/-]+"))
            .filter { it.length >= 2 }

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
            if (excludeEditable && node.isEditable) continue

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 4 || rect.height() <= 4) continue

            val combined = extractCombinedNodeLabel(node).lowercase()
            if (combined.isBlank()) continue

            var score = 0
            for (term in terms) {
                val matched = if (exact) {
                    combined == term
                } else {
                    combined.contains(term)
                }
                if (matched) {
                    val s = when {
                        combined == term && node.isClickable -> 120
                        combined == term -> 105
                        combined.startsWith(term) && node.isClickable -> 96
                        node.isClickable -> 85
                        else -> 68
                    }
                    if (s > score) score = s
                }
            }

            // Multi-word token overlap & mild fuzzy matching (handles Bengali/English word order & minor ASR typos)
            if (!exact && score < 80 && queryTokens.isNotEmpty()) {
                val nodeTokens = combined.split(Regex("[\\s,._:/-]+")).filter { it.length >= 2 }
                if (nodeTokens.isNotEmpty()) {
                    var tokenHits = 0
                    for (qt in queryTokens) {
                        val hit = nodeTokens.any { nt ->
                            nt == qt || nt.contains(qt) || (qt.length >= 4 && isFuzzyClose(qt, nt))
                        }
                        if (hit) tokenHits++
                    }
                    val ratio = tokenHits.toFloat() / queryTokens.size.toFloat()
                    if (ratio >= 0.65f) {
                        val tokenScore = (55 + (ratio * 35).toInt()) + if (node.isClickable) 10 else 0
                        if (tokenScore > score) score = tokenScore
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestNode = node
            }
        }
        return if (bestScore >= 60) bestNode else null
    }

    internal fun isFuzzyClose(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 2) return false
        val maxDist = if (a.length >= 6) 2 else 1
        return levenshteinDistance(a, b) <= maxDist
    }

    internal fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val prev = IntArray(n + 1) { it }
        val curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, n + 1)
        }
        return prev[n]
    }

    private fun extractCombinedNodeLabel(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append(' ') }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append(' ') }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append(' ') }
        }
        // For clickable cards/rows with child text, aggregate immediate child labels
        if (sb.isEmpty() && node.isClickable && node.childCount in 1..8) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                child.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    sb.append(it).append(' ')
                }
                child.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    sb.append(it).append(' ')
                }
            }
        }
        return sb.toString().trim()
    }

    private fun findBestSearchEditNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable && focused.isVisibleToUser) return focused

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestEditable: AccessibilityNodeInfo? = null
        var bestScore = 0
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (!n.isVisibleToUser || !n.isEditable) continue
            val meta = "${n.text ?: ""} ${n.contentDescription ?: ""} ${n.viewIdResourceName ?: ""} ${
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText ?: "" else ""
            }".lowercase()
            val score = when {
                SEARCH_HINTS.any { meta.contains(it) } -> 100
                n.isFocused -> 85
                else -> 50
            }
            if (score > bestScore) {
                bestScore = score
                bestEditable = n
            }
        }
        return bestEditable
    }

    private fun findSearchTriggerNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (!n.isVisibleToUser) continue
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (rect.width() <= 4 || rect.height() <= 4) continue

            val meta = "${n.text ?: ""} ${n.contentDescription ?: ""} ${n.viewIdResourceName ?: ""} ${
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText ?: "" else ""
            }".lowercase()
            if (SEARCH_HINTS.any { meta.contains(it) }) {
                val score = (if (n.isClickable) 90 else 60) + (if (rect.top < 450) 15 else 0)
                if (score > bestScore) {
                    bestScore = score
                    best = n
                }
            }
        }
        return best
    }

    private fun findExplicitSubmitButton(
        root: AccessibilityNodeInfo?,
        excludeNode: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val keywords = listOf("go", "submit", "search", "খুঁজুন", "send", "পাঠান", "done")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (!n.isVisibleToUser || !n.isClickable || n.isEditable || n == excludeNode) continue
            val meta = "${n.text ?: ""} ${n.contentDescription ?: ""} ${n.viewIdResourceName ?: ""}".lowercase()
            if (keywords.any { meta.contains(it) }) return n
        }
        return null
    }

    private fun buildScreenTextFingerprint(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 35) {
            val n = queue.removeFirst()
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (!n.isVisibleToUser) continue
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                sb.append(t.take(24)).append('|')
                count++
            }
        }
        return sb.toString()
    }

    private suspend fun locateElementViaVision(
        service: PilotAccessibilityService,
        targetDescription: String
    ): Pair<Int, Int>? {
        val shot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896) ?: return null
        val modelId = PilotStateHub.settings.value.textModel
        val json = GeminiRestClient.generateVisionJson(
            modelId = modelId,
            systemPrompt = "You locate exact UI elements on Android screenshots with a 0..1000 coordinate ruler grid. Return strict JSON only.",
            userPrompt = "Find this element on screen: \"$targetDescription\". Return JSON: {\"found\": true/false, \"x\": 0..1000, \"y\": 0..1000}.",
            jpegBytes = shot,
            temperature = 0.1,
            maxOutputTokens = 256
        ).getOrNull() ?: return null

        if (!json.optBoolean("found", false)) return null
        val x = json.optInt("x", -1)
        val y = json.optInt("y", -1)
        return if (x in 1..999 && y in 1..999) x to y else null
    }

    private fun tryClickNodeOrAncestors(node: AccessibilityNodeInfo): Boolean {
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
}

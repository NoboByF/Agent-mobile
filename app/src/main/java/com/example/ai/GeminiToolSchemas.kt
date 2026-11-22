package com.example.ai

import com.example.data.VoiceLanguage
import org.json.JSONArray
import org.json.JSONObject

object GeminiToolSchemas {

    const val LIVE_SYSTEM_INSTRUCTION: String = """
You are LivePilot AI's Voice Brain — a warm, ultra-fast conversational voice interface on a live phone call.
Your app runs a DUAL-BRAIN ARCHITECTURE:
- YOU (Live Audio model) are the VOICE BRAIN: you listen, talk naturally with the user, and run instant 1-step hardware/app shortcuts.
- start_autonomous_task(...) invokes the STRONGER NON-LIVE ACTION BRAIN (Gemini Vision + Reasoning model with an Independent Visual Completion Auditor) that sees the screen, executes multi-step work, verifies every step visually, and keeps working until the task is 100% genuinely finished!

STRICT ANTI-FAKE-COMPLETION RULE (HIGHEST PRIORITY):
- NEVER say "শেষ করেছি", "করে দিয়েছি", "হয়ে গেছে", or "Done" unless a tool ACTUALLY returned verified success OR you received a [SYSTEM EVENT: ... VERIFIED COMPLETED] message!
- When start_autonomous_task returns status="started", the work has ONLY JUST BEGUN — NEVER say it is finished! Tell the user in 1 short sentence: "আমি কাজটা শুরু করেছি, সম্পূর্ণ শেষ হলেই জানাচ্ছি।" (or equivalent in English).
- If ANY single-step tool returns status="not_found", "partial", or "error", NEVER claim you succeeded! Immediately delegate the full goal to start_autonomous_task(goal="...", mode="AUTO") so the smarter Action Brain inspects the screen and finishes it properly, OR honestly tell the user what failed.

WHEN TO DELEGATE IMMEDIATELY TO THE STRONGER ACTION BRAIN (start_autonomous_task):
- ALWAYS call start_autonomous_task(goal="complete detailed description of user's request", mode="DRAWING" or "AUTO") IMMEDIATELY for:
  1. ANY multi-step task (e.g., "YouTube-এ গিয়ে অমুক গান সার্চ করে প্রথম ভিডিও চালাও", "Chrome-এ গিয়ে ... খুঁজে বের করো", "Settings-এ গিয়ে ডার্ক মোড চালু করো", "WhatsApp-এ অমুককে মেসেজ পাঠাও", "কোনো ফর্ম পূরণ করো").
  2. ANY drawing, sketching, painting, or canvas work ("পাখির ছবি আঁকো", "draw a flower/cat/house").
  3. ANY request where the user asks to find a specific item, video, product, setting, or button that isn't trivially on screen right now.
- While the Action Brain works in the background (even for 2–3 minutes), keep chatting naturally with the user!
- If the user asks for an adjustment mid-task, call update_autonomous_task(instruction="..."). If they say "থামো" or "stop", call stop_autonomous_task() immediately.
- When you receive [SYSTEM EVENT: Autonomous ... VERIFIED COMPLETED], warmly report the real result in 1–2 short sentences!

WHEN TO USE DIRECT INSTANT TOOLS (1-STEP ONLY):
- Simple 1-step commands ONLY: open_app("YouTube") when user ONLY says "ইউটিউব খোলো", search_youtube(query) when user ONLY wants YouTube search results opened, search_on_screen(query, open_in_google_if_needed) for a single search, find_and_interact(target) for tapping one visible button/item, toggle_flashlight, get_device_status, set_alarm_or_timer, clipboard_action, device_control, start_continuous_scroll / stop_scrolling.
- If a direct search or find_and_interact fails or only partially finishes a larger goal, IMMEDIATELY hand off to start_autonomous_task(goal="...", mode="AUTO")!

LIVE WEB SEARCH, DEVICE STATUS & HARDWARE SUPERPOWERS:
- For real-time facts, news, weather, exchange rates, cricket scores, or anything requiring live internet knowledge to speak aloud, call web_search_and_answer(query="...") and speak a crisp summary! (If user wants to SEE Google search results in their browser, use search_on_screen(..., open_in_google_if_needed=true).)
- For battery level, charging state, current local time/date, volume, Wi-Fi/network, or active app, call get_device_status().
- For torch/flashlight requests ("টর্চ জ্বালাও", "turn off flashlight"), call toggle_flashlight(enabled=true/false).
- For timers or alarms ("৫ মিনিটের টাইমার দাও", "set alarm for 7:30 AM"), call set_alarm_or_timer(...).
- For clipboard tasks ("কপি করা লেখাটা পড়ো", "copy this text"), call clipboard_action(action="READ"/"WRITE", text=...).
- For YouTube searches, call search_youtube(query="..."). For volume, media playback, opening URLs, dialing a number, or starting a WhatsApp chat with a phone number, call device_control(...).

CONTINUOUS SCROLLING RULE:
- When asked to keep scrolling ("scroll korte thako", "keep scrolling down"), call start_continuous_scroll immediately.
- The instant the user says "stop", "থামো", "thamo", or asks to click something while scrolling, ALWAYS call stop_scrolling() first, then inspect and tap.

INCOMING MESSAGES & AUTO-CHAT:
- When asked "কোনো মেসেজ আসছে?" or "check my messages", call read_recent_notifications(limit=5) to read recent imo, WhatsApp, Messenger, and Telegram messages aloud.
- When you receive a [SYSTEM EVENT: New message on ...] notice, announce the sender and app in 1 short sentence and ask if the user wants to open the chat and reply.
- If confirmed, call open_notification_chat(sender_or_app), inspect with get_screen_ui(), type with type_text(..., press_send=true), and confirm once sent.

SAFETY GUARDRAILS:
- If any tool returns SAFETY_BLOCKED, stop immediately and politely inform the user by voice that protected financial, banking, authenticator, and password manager apps are blocked for their safety.
"""

    fun buildLanguageLockHeader(language: VoiceLanguage): String = when (language) {
        VoiceLanguage.BENGALI_BD -> """
CRITICAL SPEECH LANGUAGE LOCK — BANGLADESHI BENGALI (বাংলা, bn-BD):
- The user is ALWAYS speaking Bangladeshi Bengali (বাংলা) — occasionally mixing common English app/tech words (YouTube, Chrome, Facebook, WhatsApp, Search, Settings, Draw, Dark Mode, CapCut).
- NEVER interpret the user's audio as Hindi, Urdu, Arabic, Tamil, Nepali, or any unrelated language!
- If noisy audio sounds slightly like Hindi/Urdu/garbled words (for example: "সার্চ" sounding like "सर्च", "খুঁজে দাও" sounding like "खोज दो", "পাখি আঁকো" sounding like "पक्षी आंखो", "থামো" sounding like "थामो"), ALWAYS decode the intended Bengali meaning silently and accurately before responding or calling tools.
- ALWAYS speak back in natural, warm, concise Bangladeshi Bengali (বাংলা) — 1-2 short clear sentences.
""".trimIndent()

        VoiceLanguage.BENGALI_ENGLISH -> """
LANGUAGE & SPEECH LOCK — BENGALI (বাংলা, bn-BD) + ENGLISH:
- The user speaks Bangladeshi Bengali (বাংলা), Banglish, or English. NEVER interpret their speech as Hindi, Urdu, Arabic, or Tamil.
- Always decode phonetically close Bengali/Banglish tech commands accurately and reply naturally in the user's language (concise 1-2 sentences).
""".trimIndent()

        VoiceLanguage.ENGLISH_US -> """
LANGUAGE & SPEECH LOCK — ENGLISH (en-US):
- The user speaks English. Keep replies crisp, friendly, and concise (1-2 short sentences).
""".trimIndent()
    }

    fun buildSystemInstruction(
        customUserInstructions: String,
        voiceLanguage: VoiceLanguage = VoiceLanguage.BENGALI_BD
    ): String {
        val langHeader = buildLanguageLockHeader(voiceLanguage)
        val base = "$langHeader\n\n${LIVE_SYSTEM_INSTRUCTION.trim()}"
        val extra = customUserInstructions.trim()
        return if (extra.isBlank()) {
            base
        } else {
            "$base\n\nUSER PERSONAL PREFERENCES:\n$extra"
        }
    }

    fun buildToolsJsonArray(): JSONArray {
        val declarations = JSONArray()

        declarations.put(
            declareTool(
                name = "open_app",
                description = "Launch an installed Android app by English or Bengali name (e.g. YouTube, ইউটিউব, imo, ইমো, WhatsApp, হোয়াটসঅ্যাপ, CapCut, ক্যাপকাট, Facebook, Messenger, Chrome, Spotify, Instagram, TikTok, Gallery).",
                properties = mapOf(
                    "app_name" to prop("STRING", "Name of the app in English or Bengali")
                ),
                required = listOf("app_name")
            )
        )

        declarations.put(
            declareTool(
                name = "search_youtube",
                description = "Open YouTube directly to search results for a specific video, song, or creator query.",
                properties = mapOf(
                    "query" to prop("STRING", "Search query for YouTube")
                ),
                required = listOf("query")
            )
        )

        declarations.put(
            declareTool(
                name = "web_search_and_answer",
                description = "Query live Google Search to get up-to-date real-time answers (news, weather, cricket scores, prices, facts) during a live voice call.",
                properties = mapOf(
                    "query" to prop("STRING", "Search question in Bengali or English")
                ),
                required = listOf("query")
            )
        )

        declarations.put(
            declareTool(
                name = "get_device_status",
                description = "Read real-time phone status: battery percentage, charging state, local date & time, network connection (Wi-Fi/Mobile), media volume percentage, ringer mode, and current foreground app.",
                properties = emptyMap(),
                required = emptyList()
            )
        )

        declarations.put(
            declareTool(
                name = "toggle_flashlight",
                description = "Turn the phone's rear camera flashlight/torch ON or OFF.",
                properties = mapOf(
                    "enabled" to prop("BOOLEAN", "True to turn flashlight ON, false to turn OFF")
                ),
                required = listOf("enabled")
            )
        )

        declarations.put(
            declareTool(
                name = "set_alarm_or_timer",
                description = "Set a real Android countdown timer or alarm clock.",
                properties = mapOf(
                    "mode" to prop("STRING", "Either TIMER or ALARM"),
                    "hours" to prop("INTEGER", "For ALARM: 24-hour clock hour (0-23). For TIMER: hours (0 if minutes only)"),
                    "minutes" to prop("INTEGER", "For ALARM: minute (0-59). For TIMER: minutes (e.g. 5 for 5 minutes)"),
                    "label" to prop("STRING", "Short label for the alarm or timer")
                ),
                required = listOf("mode", "hours", "minutes", "label")
            )
        )

        declarations.put(
            declareTool(
                name = "clipboard_action",
                description = "Read the current text copied in the Android clipboard, or copy new text to the clipboard.",
                properties = mapOf(
                    "action" to prop("STRING", "Either READ or WRITE"),
                    "text" to prop("STRING", "Text to copy when action is WRITE, otherwise empty string")
                ),
                required = listOf("action", "text")
            )
        )

        declarations.put(
            declareTool(
                name = "read_recent_notifications",
                description = "Read recent incoming messages captured from imo, WhatsApp, Messenger, and Telegram notifications.",
                properties = mapOf(
                    "limit" to prop("INTEGER", "Maximum number of recent messages to return (1 to 10)")
                ),
                required = listOf("limit")
            )
        )

        declarations.put(
            declareTool(
                name = "click_by_text",
                description = "Find a visible UI element matching the given text or description (supports English & Bengali synonyms like 'Subscribe', 'Export', 'Next', 'Skip Ad', 'পাঠান') and click it automatically. Scrolls once to search if not immediately visible.",
                properties = mapOf(
                    "query" to prop("STRING", "Button or item text/description to find and tap"),
                    "exact" to prop("BOOLEAN", "True for exact match, false for partial case-insensitive match")
                ),
                required = listOf("query", "exact")
            )
        )

        declarations.put(
            declareTool(
                name = "get_screen_ui",
                description = "Read the active foreground package name, screen pixel dimensions, and a compact list of visible/interactive UI elements with index, text, content description, clickable/editable/scrollable/checked flags, and center (cx, cy) coordinates.",
                properties = emptyMap(),
                required = emptyList()
            )
        )

        declarations.put(
            declareTool(
                name = "capture_screen_vision",
                description = "Capture a live screenshot of the current screen and stream it immediately as a visual frame so you can see video editors (CapCut, InShot, Canva), timelines, thumbnails, icons, and exact pixel coordinates.",
                properties = emptyMap(),
                required = emptyList()
            )
        )

        declarations.put(
            declareTool(
                name = "start_continuous_scroll",
                description = "Start a smooth non-blocking continuous scrolling loop while keeping the voice call active.",
                properties = mapOf(
                    "direction" to prop("STRING", "Scroll direction: DOWN, UP, LEFT, or RIGHT"),
                    "speed" to prop("STRING", "Scroll speed: SLOW, NORMAL, or FAST")
                ),
                required = listOf("direction", "speed")
            )
        )

        declarations.put(
            declareTool(
                name = "stop_scrolling",
                description = "Immediately stop any active continuous scrolling loop.",
                properties = emptyMap(),
                required = emptyList()
            )
        )

        declarations.put(
            declareTool(
                name = "scroll_once",
                description = "Perform a single smooth swipe scroll gesture.",
                properties = mapOf(
                    "direction" to prop("STRING", "Direction: DOWN, UP, LEFT, or RIGHT"),
                    "distance_percent" to prop("INTEGER", "Percentage of screen to swipe, from 20 to 85")
                ),
                required = listOf("direction", "distance_percent")
            )
        )

        declarations.put(
            declareTool(
                name = "click_element",
                description = "Click a UI element by its index from get_screen_ui, or pass index = -1 to tap exact screen pixel coordinates (x, y) identified from capture_screen_vision.",
                properties = mapOf(
                    "index" to prop("INTEGER", "Element index from get_screen_ui, or -1 for coordinate tap"),
                    "x" to prop("INTEGER", "X pixel coordinate on screen"),
                    "y" to prop("INTEGER", "Y pixel coordinate on screen")
                ),
                required = listOf("index", "x", "y")
            )
        )

        declarations.put(
            declareTool(
                name = "long_press",
                description = "Perform a long-press gesture at screen coordinates (x, y).",
                properties = mapOf(
                    "x" to prop("INTEGER", "X pixel coordinate"),
                    "y" to prop("INTEGER", "Y pixel coordinate"),
                    "duration_ms" to prop("INTEGER", "Hold duration in milliseconds, e.g. 700")
                ),
                required = listOf("x", "y", "duration_ms")
            )
        )

        declarations.put(
            declareTool(
                name = "drag_gesture",
                description = "Smoothly drag from (start_x, start_y) to (end_x, end_y) over duration_ms. Essential for trimming video clips, scrubbing timelines, moving layers, and dragging sliders.",
                properties = mapOf(
                    "start_x" to prop("INTEGER", "Starting X pixel coordinate"),
                    "start_y" to prop("INTEGER", "Starting Y pixel coordinate"),
                    "end_x" to prop("INTEGER", "Ending X pixel coordinate"),
                    "end_y" to prop("INTEGER", "Ending Y pixel coordinate"),
                    "duration_ms" to prop("INTEGER", "Drag duration in milliseconds, e.g. 450")
                ),
                required = listOf("start_x", "start_y", "end_x", "end_y", "duration_ms")
            )
        )

        declarations.put(
            declareTool(
                name = "type_text",
                description = "Focus a text input field (by index or x,y), enter text with clipboard paste fallback, and optionally press Send or Search.",
                properties = mapOf(
                    "index" to prop("INTEGER", "Element index from get_screen_ui, or -1 to tap (x, y) first"),
                    "x" to prop("INTEGER", "X coordinate of input box"),
                    "y" to prop("INTEGER", "Y coordinate of input box"),
                    "text" to prop("STRING", "Text to type into the field"),
                    "press_send" to prop("BOOLEAN", "True to click Send/Search or submit IME action after typing")
                ),
                required = listOf("index", "x", "y", "text", "press_send")
            )
        )

        declarations.put(
            declareTool(
                name = "navigate",
                description = "Perform a global Android navigation action.",
                properties = mapOf(
                    "action" to prop("STRING", "One of: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS")
                ),
                required = listOf("action")
            )
        )

        declarations.put(
            declareTool(
                name = "device_control",
                description = "Control device media playback, media volume, open a web URL, open dialer for a phone number, or jump to a WhatsApp chat by phone number.",
                properties = mapOf(
                    "action" to prop("STRING", "One of: VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, OPEN_URL, DIAL_NUMBER, WHATSAPP_CHAT"),
                    "value" to prop("STRING", "URL for OPEN_URL, phone number for DIAL_NUMBER or WHATSAPP_CHAT, otherwise empty string")
                ),
                required = listOf("action", "value")
            )
        )

        declarations.put(
            declareTool(
                name = "wait_ms",
                description = "Pause briefly (200 to 5000 milliseconds) while waiting for an app transition, video load, or export screen before inspecting the UI again.",
                properties = mapOf(
                    "milliseconds" to prop("INTEGER", "Delay duration in milliseconds (200 to 5000)")
                ),
                required = listOf("milliseconds")
            )
        )

        declarations.put(
            declareTool(
                name = "open_notification_chat",
                description = "Jump directly into an unread imo, WhatsApp, Messenger, or Telegram conversation matching the sender name or app name.",
                properties = mapOf(
                    "sender_or_app" to prop("STRING", "Sender name (e.g. Rahim) or app name (e.g. imo, WhatsApp)")
                ),
                required = listOf("sender_or_app")
            )
        )

        declarations.put(
            declareTool(
                name = "start_autonomous_task",
                description = "Launch LivePilot's non-blocking Autonomous Vision Agent to independently complete complex multi-step tasks in ANY app or browser (forms, editing, settings, multi-screen workflows) OR draw detailed multi-stroke artwork (birds, flowers, houses, sketches) on any drawing app/web canvas (FlipaCreate, FlipaClip, ibisPaint, Canva, Notes) while keeping the live voice call active.",
                properties = mapOf(
                    "goal" to prop("STRING", "Complete clear goal description of what to draw or accomplish autonomously"),
                    "mode" to prop("STRING", "One of: AUTO, DRAWING, or UI_WORKFLOW")
                ),
                required = listOf("goal", "mode")
            )
        )

        declarations.put(
            declareTool(
                name = "update_autonomous_task",
                description = "Send a live mid-task voice instruction or tweak to the currently running autonomous drawing or workflow task without stopping it.",
                properties = mapOf(
                    "instruction" to prop("STRING", "Additional guidance, tweak, or next instruction from the user")
                ),
                required = listOf("instruction")
            )
        )

        declarations.put(
            declareTool(
                name = "stop_autonomous_task",
                description = "Immediately stop and cancel any currently running autonomous drawing or multi-step browser/app task.",
                properties = emptyMap(),
                required = emptyList()
            )
        )

        declarations.put(
            declareTool(
                name = "draw_strokes",
                description = "ONLY for a single quick corrective line/stroke. NEVER use draw_strokes to draw full objects like birds, flowers, portraits, or sketches — ALWAYS call start_autonomous_task(goal=\"...\", mode=\"DRAWING\") for full drawings!",
                properties = mapOf(
                    "canvas_left" to prop("INTEGER", "Canvas left edge in 0..1000 screen coordinates (0 for full screen)"),
                    "canvas_top" to prop("INTEGER", "Canvas top edge in 0..1000 screen coordinates (0 for full screen)"),
                    "canvas_right" to prop("INTEGER", "Canvas right edge in 0..1000 screen coordinates (1000 for full screen)"),
                    "canvas_bottom" to prop("INTEGER", "Canvas bottom edge in 0..1000 screen coordinates (1000 for full screen)"),
                    "strokes_json" to prop("STRING", "JSON array of stroke objects: [{\"label\":\"Wing\",\"points\":[[x,y],[x,y],...]}] with 0..1000 coordinates inside the canvas box")
                ),
                required = listOf("canvas_left", "canvas_top", "canvas_right", "canvas_bottom", "strokes_json")
            )
        )

        declarations.put(
            declareTool(
                name = "search_on_screen",
                description = "Perform a smart, reliable search inside ANY currently open Android app (Chrome, Facebook, Play Store, Settings, WhatsApp, Daraz, Spotify, Instagram, YouTube) by automatically locating the search icon/bar, typing the query, and submitting it — OR directly opening Google Search in the browser when open_in_google_if_needed is true.",
                properties = mapOf(
                    "query" to prop("STRING", "Exact search query text in Bengali or English"),
                    "open_in_google_if_needed" to prop("BOOLEAN", "True to open Google Search directly in browser; false to search inside the currently open app first")
                ),
                required = listOf("query", "open_in_google_if_needed")
            )
        )

        declarations.put(
            declareTool(
                name = "find_and_interact",
                description = "Reliably locate ANY specific button, setting, contact, video, product, tab, or icon on screen using multi-scroll fuzzy Bengali+English Accessibility search PLUS automatic Vision screenshot fallback, then click or scroll to it.",
                properties = mapOf(
                    "target" to prop("STRING", "Name, label, or description of the exact item to find"),
                    "action" to prop("STRING", "One of: CLICK, SCROLL_TO, or CHECK"),
                    "max_scrolls" to prop("INTEGER", "Maximum number of scroll pages to search (1 to 6, recommended 4)")
                ),
                required = listOf("target", "action", "max_scrolls")
            )
        )

        val toolWrapper = JSONObject().put("functionDeclarations", declarations)
        return JSONArray().put(toolWrapper)
    }

    private fun declareTool(
        name: String,
        description: String,
        properties: Map<String, JSONObject>,
        required: List<String>
    ): JSONObject {
        val propsObj = JSONObject()
        properties.forEach { (k, v) -> propsObj.put(k, v) }
        val params = JSONObject()
            .put("type", "OBJECT")
            .put("properties", propsObj)
        if (required.isNotEmpty()) {
            val reqArr = JSONArray()
            required.forEach { reqArr.put(it) }
            params.put("required", reqArr)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", params)
    }

    private fun prop(type: String, description: String): JSONObject =
        JSONObject()
            .put("type", type)
            .put("description", description)
}

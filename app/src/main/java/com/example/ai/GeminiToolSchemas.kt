package com.example.ai

import org.json.JSONArray
import org.json.JSONObject

object GeminiToolSchemas {

    const val LIVE_SYSTEM_INSTRUCTION: String = """
You are LivePilot AI, a lightning-fast, warm, ultra-capable personal AI co-pilot on a live phone call controlling the user's Android phone in real time.

LANGUAGE & VOICE STYLE:
- Speak naturally in Bengali when the user speaks Bengali (or Banglish), and speak English when the user speaks English.
- Keep spoken responses crisp, confident, friendly, and concise (1-2 short sentences) while acting—just like an expert human co-pilot on a phone call.

STRICT TOOL-FIRST RULE:
- NEVER pretend or claim you tapped, opened, searched, checked battery/time, toggled flashlight, set a timer, copied text, or sent a message unless you actually called the real tool and received a successful result.

FAST & ACCURATE UI ACTION STRATEGY:
- To tap a labeled button, tab, icon, or menu item (like "Subscribe", "Play", "Export", "Skip", "Next", "Allow", "পাঠান"), prefer click_by_text(query="...") first!
- For visual/canvas apps (CapCut, InShot, Canva, timelines, sliders, video frames), call capture_screen_vision() to see the real pixels, then use click_element(index=-1, x=..., y=...) or drag_gesture(start_x, start_y, end_x, end_y, duration_ms).
- After opening an app or tapping navigation, call wait_ms(700) if the screen needs a moment to load, then inspect with get_screen_ui() or capture_screen_vision().

LIVE WEB SEARCH, DEVICE STATUS & HARDWARE SUPERPOWERS:
- For real-time facts, news, weather, exchange rates, cricket scores, or anything requiring live internet knowledge, ALWAYS call web_search_and_answer(query="...") and speak a crisp summary of the result!
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

    fun buildSystemInstruction(customUserInstructions: String): String {
        val extra = customUserInstructions.trim()
        return if (extra.isBlank()) {
            LIVE_SYSTEM_INSTRUCTION.trim()
        } else {
            "${LIVE_SYSTEM_INSTRUCTION.trim()}\n\nUSER PERSONAL PREFERENCES:\n$extra"
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

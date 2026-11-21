package com.example.ai

import org.json.JSONArray
import org.json.JSONObject

object GeminiToolSchemas {

    const val LIVE_SYSTEM_INSTRUCTION: String = """
You are LivePilot AI, a lightning-fast, warm, smart personal assistant on a live phone call controlling the user's Android device in real time.

LANGUAGE & VOICE STYLE:
- Speak naturally in Bengali when the user speaks Bengali (or Banglish), and speak English when the user speaks English.
- Keep spoken responses crisp, friendly, and brief (1-2 short sentences) while performing actions—just like a sharp human co-pilot on a phone call.

STRICT TOOL-FIRST RULE:
- NEVER pretend or claim you tapped, opened, scrolled, dragged, or sent a message unless you actually invoked the tool and received a successful result.
- Always call the real tool first: open_app, get_screen_ui, capture_screen_vision, click_element, long_press, drag_gesture, type_text, scroll_once, start_continuous_scroll, stop_scrolling, navigate, or open_notification_chat.

MULTI-STEP WORKFLOW & VISUAL VERIFICATION:
- Maintain full memory of everything completed earlier in this call session.
- For multi-step workflows (like editing video in CapCut/InShot/Canva, browsing YouTube, or chatting on imo/WhatsApp), call get_screen_ui() AND/OR capture_screen_vision() after every major screen change to verify exact coordinates and screen state before tapping or dragging.
- When working in visual/canvas apps (video editors, thumbnails, timelines, sliders), call capture_screen_vision() to see the actual pixels, then use click_element(index=-1, x=..., y=...) or drag_gesture(start_x, start_y, end_x, end_y, duration_ms).

CONTINUOUS SCROLLING RULE:
- When the user asks to keep scrolling (e.g., "scroll korte thako", "keep scrolling down"), call start_continuous_scroll immediately.
- The instant the user says "stop", "থামো", "thamo", or asks to tap/open a specific item while scrolling, ALWAYS call stop_scrolling() first, then inspect the screen and click it.

INCOMING MESSAGES & AUTO-CHAT:
- When you receive a [SYSTEM EVENT: New message on ...] notice, briefly announce the sender and app in 1 short sentence and ask if the user wants you to open the chat and reply.
- If the user says yes, call open_notification_chat(sender_or_app), inspect the chat with get_screen_ui(), type the reply with type_text(..., press_send=true), and confirm once sent.

SAFETY GUARDRAILS:
- If any tool returns SAFETY_BLOCKED, immediately stop attempting automation on that screen and inform the user politely by voice that protected financial, banking, authenticator, and password manager apps are blocked for their safety.
"""

    fun buildToolsJsonArray(): JSONArray {
        val declarations = JSONArray()

        declarations.put(
            declareTool(
                name = "open_app",
                description = "Launch an installed Android app by English or Bengali name (e.g. YouTube, ইউটিউব, imo, ইমো, WhatsApp, হোয়াটসঅ্যাপ, CapCut, ক্যাপকাট, Facebook, Messenger, Chrome, Gallery).",
                properties = mapOf(
                    "app_name" to prop("STRING", "Name of the app in English or Bengali")
                ),
                required = listOf("app_name")
            )
        )

        declarations.put(
            declareTool(
                name = "get_screen_ui",
                description = "Read the active foreground package name, screen pixel dimensions, and a compact list of visible/interactive UI elements with index, text, content description, clickable/editable flags, and center (cx, cy) coordinates.",
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
                    "action" to prop("STRING", "One of: BACK, HOME, RECENTS, NOTIFICATIONS")
                ),
                required = listOf("action")
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

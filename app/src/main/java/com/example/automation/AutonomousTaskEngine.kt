package com.example.automation

import android.content.Context
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.data.AgentActivity
import com.example.data.LogLevel
import com.example.data.PilotStateHub
import com.example.data.SafetyGuardrails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext

enum class AutonomousMode {
    AUTO,
    DRAWING,
    UI_WORKFLOW
}

/**
 * Non-blocking, Vision-guided Autonomous Task & Freehand Drawing Engine.
 * Runs independently alongside the real-time Gemini Live voice call so the user can
 * speak freely while LivePilot executes long multi-step browser/app workflows or
 * multi-minute canvas drawings until completion.
 */
class AutonomousTaskEngine(
    private val appContext: Context,
    private val liveClientProvider: () -> GeminiLiveClient?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var currentGoal: String = ""

    private val userGuidanceQueue = CopyOnWriteArrayList<String>()

    fun isRunning(): Boolean = activeJob?.isActive == true

    fun startTask(goal: String, rawMode: String = "AUTO"): JSONObject {
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Task goal cannot be empty.")
        }
        val service = PilotAccessibilityService.instance
        if (service == null) {
            return JSONObject()
                .put("status", "error")
                .put("message", "Accessibility Service is not enabled. Ask the user to enable LivePilot AI Accessibility first.")
        }
        if (!PilotStateHub.settings.value.hasAnyApiKey) {
            return JSONObject().put("status", "error").put("message", "No Gemini API key configured.")
        }

        // Cancel any previously running autonomous task cleanly before starting the new one
        activeJob?.cancel()
        userGuidanceQueue.clear()
        currentGoal = cleanGoal

        val mode = resolveMode(cleanGoal, rawMode)
        val startedAt = System.currentTimeMillis()

        PilotStateHub.log(LogLevel.TOOL, "AutoPilot", "Started autonomous task ($mode): $cleanGoal")
        updateHudProgress(
            running = true,
            ticker = if (mode == AutonomousMode.DRAWING) "🎨 Inspecting canvas..." else "🤖 Analyzing screen..."
        )

        activeJob = scope.launch {
            try {
                when (mode) {
                    AutonomousMode.DRAWING -> runAutonomousDrawingPipeline(service, cleanGoal, startedAt)
                    AutonomousMode.UI_WORKFLOW, AutonomousMode.AUTO ->
                        runAutonomousWorkflowLoop(service, cleanGoal, startedAt)
                }
            } catch (ce: CancellationException) {
                PilotStateHub.log(LogLevel.TOOL, "AutoPilot", "Autonomous task cancelled.")
                throw ce
            } catch (err: Throwable) {
                PilotStateHub.log(LogLevel.ERROR, "AutoPilot", "Task error: ${err.message}")
                notifyLiveVoice(
                    "[SYSTEM EVENT: Autonomous task stopped due to error: ${err.message?.take(140)}. Briefly inform the user in their language.]"
                )
            } finally {
                if (activeJob?.isActive != true) {
                    updateHudProgress(running = false, ticker = null)
                }
            }
        }

        return JSONObject()
            .put("status", "started")
            .put("mode", mode.name)
            .put("goal", cleanGoal)
            .put(
                "note",
                "Autonomous engine is now executing in background while voice call stays active. Do NOT repeat actions yourself; talk naturally with the user and wait for completion event or user adjustments."
            )
    }

    fun updateInstruction(instruction: String): JSONObject {
        val clean = instruction.trim()
        if (clean.isBlank()) {
            return JSONObject().put("status", "error").put("message", "Instruction was empty.")
        }
        if (!isRunning()) {
            return startTask(clean, "AUTO")
        }
        userGuidanceQueue.add(clean)
        PilotStateHub.log(LogLevel.TOOL, "AutoPilot", "Queued live mid-task guidance: $clean")
        updateHudProgress(running = true, ticker = "🤖 Updating: ${clean.take(24)}")
        return JSONObject()
            .put("status", "ok")
            .put("queued_guidance", clean)
            .put("note", "Guidance injected into the running autonomous task.")
    }

    fun stopTask(reason: String = "Stopped by user"): JSONObject {
        val wasRunning = isRunning()
        activeJob?.cancel()
        activeJob = null
        userGuidanceQueue.clear()
        updateHudProgress(running = false, ticker = if (wasRunning) "Task stopped" else null)
        if (wasRunning) {
            PilotStateHub.log(LogLevel.TOOL, "AutoPilot", "Stopped autonomous task ($reason).")
        }
        return JSONObject()
            .put("status", "ok")
            .put("was_running", wasRunning)
            .put("reason", reason)
    }

    fun shutdown() {
        stopTask("Service shutdown")
        scope.cancel()
    }

    // --- DRAWING PIPELINE (Canvas Prep + Multi-Stage Curved Vector Art + Visual Verification) ---

    private suspend fun runAutonomousDrawingPipeline(
        service: PilotAccessibilityService,
        goal: String,
        startedAtMs: Long
    ) {
        val modelId = PilotStateHub.settings.value.textModel
        var totalStrokesDrawn = 0

        // Phase 1: Inspect screen, detect canvas bounds, prepare zoom/brush/color
        updateHudProgress(true, "🎨 Finding canvas...")
        val initialShot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896)
        if (initialShot == null) {
            notifyLiveVoice("[SYSTEM EVENT: Could not capture screen for drawing. Ask user to check Accessibility permission or leave protected app.]")
            return
        }

        val prepPlan = GeminiRestClient.generateVisionJson(
            modelId = modelId,
            systemPrompt = DRAWING_PREP_SYSTEM_PROMPT,
            userPrompt = buildString {
                append("User Drawing Goal: ").append(goal).append('\n')
                append("The screenshot has a 0..1000 coordinate ruler grid along the top (X) and left (Y) edges.\n")
                append("1. Locate the exact visible drawing canvas rectangle as canvas_box [left, top, right, bottom] in 0..1000 coordinates.\n")
                append("2. If the canvas is very small/zoomed out (e.g. 30% zoom) AND a visible '+' zoom button exists, include 1-2 taps on '+' in prep_actions.\n")
                append("3. If a brush/pencil/pen tool is NOT selected yet or needs switching from eraser/hand tool, include that tap in prep_actions.\n")
                append("4. If no canvas is visible on screen at all, set canvas_found=false and explain in note.\n")
                append("Return strict JSON: {\"canvas_found\": true, \"canvas_box\": [left,top,right,bottom], \"prep_actions\": [{\"type\":\"CLICK\",\"x\":500,\"y\":500,\"label\":\"Zoom in\"}], \"note\": \"...\"}")
            },
            jpegBytes = initialShot,
            temperature = 0.15,
            maxOutputTokens = 1024
        ).getOrElse { err ->
            notifyLiveVoice("[SYSTEM EVENT: Canvas analysis failed: ${err.message?.take(120)}. Inform user briefly.]")
            return
        }

        val canvasFound = prepPlan.optBoolean("canvas_found", true)
        if (!canvasFound) {
            // Fall back to general UI workflow if user needs to open a drawing screen/canvas first
            PilotStateHub.log(LogLevel.INFO, "AutoPilot", "Canvas not immediately visible; switching to UI workflow first.")
            runAutonomousWorkflowLoop(service, goal, startedAtMs)
            return
        }

        var canvasBox = parseCanvasBox(prepPlan.optJSONArray("canvas_box"))
        val prepActions = prepPlan.optJSONArray("prep_actions")
        var ranPrepActions = false
        if (prepActions != null && prepActions.length() > 0) {
            val count = minOf(prepActions.length(), 5)
            for (i in 0 until count) {
                coroutineContext.ensureActive()
                val act = prepActions.optJSONObject(i) ?: continue
                val label = act.optString("label", "Setup").take(22)
                val x = act.optInt("x", -1)
                val y = act.optInt("y", -1)
                if (x in 1..999 && y in 1..999) {
                    updateHudProgress(true, "🎨 $label...")
                    service.clickNormalized(x, y, label)
                    ranPrepActions = true
                    delay(420L)
                }
            }
        }

        // Re-capture after prep taps (especially if zoomed in!) so canvas_box is 100% accurate
        var latestShot = if (ranPrepActions) {
            delay(350L)
            service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896) ?: initialShot
        } else {
            initialShot
        }

        if (ranPrepActions) {
            val confirmObj = GeminiRestClient.generateVisionJson(
                modelId = modelId,
                systemPrompt = "You read 0..1000 coordinate grid screenshots and output only the exact white/active drawing canvas bounding box JSON.",
                userPrompt = "Return strict JSON: {\"canvas_box\": [left, top, right, bottom]} for the active drawing canvas area in 0..1000 screen coordinates.",
                jpegBytes = latestShot,
                temperature = 0.1,
                maxOutputTokens = 256
            ).getOrNull()
            confirmObj?.optJSONArray("canvas_box")?.let {
                parseCanvasBox(it)?.let { updated -> canvasBox = updated }
            }
        }

        val activeCanvasBox = canvasBox ?: intArrayOf(120, 220, 880, 780)
        PilotStateHub.log(
            LogLevel.INFO,
            "AutoPilot",
            "Locked canvas_box=[${activeCanvasBox.joinToString(",")}]"
        )

        // Phase 2: Up to 3 iterative drawing stages (Core Sketch -> Visual Self-Check Details -> Final Polish/User Edits)
        val maxStages = 3
        for (stage in 1..maxStages) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() - startedAtMs > MAX_TASK_DURATION_MS) break
            if (service.isCurrentScreenBlocked()) {
                notifyLiveVoice("[SYSTEM EVENT: Stopped drawing because a protected app appeared on screen.]")
                return
            }

            val liveUserNotes = drainUserGuidance()
            updateHudProgress(
                true,
                when (stage) {
                    1 -> "🎨 Planning artwork..."
                    2 -> "🎨 Reviewing & adding details..."
                    else -> "🎨 Polishing artwork..."
                }
            )

            val stagePrompt = buildString {
                append("User Drawing Goal: ").append(goal).append('\n')
                append("Current Stage: ").append(stage).append(" of ").append(maxStages).append('\n')
                append("Detected Canvas Screen Bounds (0..1000 screen): [")
                    .append(activeCanvasBox.joinToString(",")).append("]\n")
                if (liveUserNotes.isNotEmpty()) {
                    append("NEW LIVE USER INSTRUCTIONS (MUST FOLLOW): ")
                        .append(liveUserNotes.joinToString("; ")).append('\n')
                }
                if (stage == 1) {
                    append(
                        """
                        Generate the complete primary artistic vector drawing for the user's goal!
                        CRITICAL COORDINATE SYSTEM:
                        - All stroke points MUST use CANVAS-RELATIVE coordinates from 0 to 1000:
                          (0,0) = top-left of the white canvas, (500,500) = dead center of canvas, (1000,1000) = bottom-right of canvas.
                        - Keep the main subject well-composed inside 100..900 so nothing is clipped at edges.
                        - Make it genuinely recognizable, graceful, and detailed: use 18 to 32 smooth strokes.
                        - Each curved stroke MUST have 5 to 14 ordered [x, y] waypoints so Bezier smoothing creates real organic curves (e.g., for a bird: curved head crown, pointed beak upper & lower, eye dot, curved throat & breast, back contour, sweeping upper & lower wings with layered feather curves, fanned tail feathers, legs/claws, and a tree branch with leaves).
                        - Return strict JSON:
                          {
                            "done": false,
                            "summary": "Brief description of what this stage draws",
                            "tool_taps": [],
                            "strokes": [
                              {"label": "Head curve", "points": [[420,260],[455,220],[505,215],[545,245],[555,295]]}
                            ]
                          }
                        """.trimIndent()
                    )
                } else {
                    append(
                        """
                        LOOK CAREFULLY at the latest screenshot showing what has ALREADY been drawn on the canvas.
                        - Evaluate the drawing honestly. If it already looks complete, recognizable, and satisfies all user instructions, return {"done": true, "summary": "...", "tool_taps": [], "strokes": []}.
                        - Otherwise, add 8 to 20 high-impact finishing strokes (missing features, feather/leaf details, eye highlights, branch/ground lines, subtle shading strokes, or user-requested changes) in CANVAS-RELATIVE 0..1000 coordinates.
                        - Optionally include screen-coordinate (0..1000) "tool_taps": [{"x":..., "y":..., "label":"..."}] ONLY if changing color or brush preset visibly shown on screen.
                        - Return strict JSON with keys: done, summary, tool_taps, strokes.
                        """.trimIndent()
                    )
                }
            }

            val stagePlan = GeminiRestClient.generateVisionJson(
                modelId = modelId,
                systemPrompt = DRAWING_ARTIST_SYSTEM_PROMPT,
                userPrompt = stagePrompt,
                jpegBytes = latestShot,
                temperature = 0.3,
                maxOutputTokens = 6144
            ).getOrElse { err ->
                PilotStateHub.log(LogLevel.ERROR, "AutoPilot", "Drawing stage $stage failed: ${err.message}")
                if (totalStrokesDrawn > 0) break
                notifyLiveVoice("[SYSTEM EVENT: Drawing planner hit an error: ${err.message?.take(120)}. Inform user.]")
                return
            }

            // Optional color/brush taps before this stage's strokes
            val toolTaps = stagePlan.optJSONArray("tool_taps")
            if (toolTaps != null) {
                for (t in 0 until minOf(toolTaps.length(), 4)) {
                    coroutineContext.ensureActive()
                    val tap = toolTaps.optJSONObject(t) ?: continue
                    val tx = tap.optInt("x", -1)
                    val ty = tap.optInt("y", -1)
                    if (tx in 1..999 && ty in 1..999) {
                        service.clickNormalized(tx, ty, tap.optString("label", "Tool"))
                        delay(300L)
                    }
                }
            }

            val strokes = stagePlan.optJSONArray("strokes")
                ?.let { DrawingGestureHelper.parseStrokesFromJsonArray(it) }
                .orEmpty()

            if (strokes.isNotEmpty()) {
                val res = service.dispatchDrawStrokes(
                    strokes = strokes,
                    normalizedSpace = true,
                    canvasBoundsNorm = activeCanvasBox
                ) { doneCount, totalCount, strokeLabel ->
                    updateHudProgress(true, "🎨 $doneCount/$totalCount: $strokeLabel")
                }
                totalStrokesDrawn += res.optInt("completed_strokes", 0)
                PilotStateHub.recordToolExecuted(
                    toolName = "autonomous_draw_stage_$stage",
                    summary = "Drew ${res.optInt("completed_strokes", 0)} strokes (Stage $stage)",
                    succeeded = res.optString("status") == "ok"
                )
            }

            val isDone = stagePlan.optBoolean("done", false) && userGuidanceQueue.isEmpty()
            if (isDone || (stage >= 2 && strokes.isEmpty() && userGuidanceQueue.isEmpty())) {
                break
            }

            // Capture updated canvas screenshot for visual self-verification in next stage
            if (stage < maxStages) {
                delay(450L)
                latestShot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896) ?: latestShot
            }
        }

        val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(1L)
        PilotStateHub.log(
            LogLevel.INFO,
            "AutoPilot",
            "Drawing finished: $totalStrokesDrawn total strokes in ${elapsedSec}s."
        )
        notifyLiveVoice(
            "[SYSTEM EVENT: Autonomous drawing task COMPLETED! Goal='$goal', drew $totalStrokesDrawn smooth strokes in ${elapsedSec} seconds. Warmly inform the user in 1-2 short sentences in their language and ask if they want any tweaks or more details.]"
        )
    }

    // --- GENERAL AUTONOMOUS MULTI-STEP UI / BROWSER WORKFLOW LOOP ---

    private suspend fun runAutonomousWorkflowLoop(
        service: PilotAccessibilityService,
        goal: String,
        startedAtMs: Long
    ) {
        val modelId = PilotStateHub.settings.value.textModel
        val stepHistory = mutableListOf<String>()
        var consecutiveErrors = 0
        var lastActionSignature = ""
        var repeatCount = 0

        for (step in 1..MAX_WORKFLOW_STEPS) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() - startedAtMs > MAX_TASK_DURATION_MS) {
                notifyLiveVoice("[SYSTEM EVENT: Autonomous task reached the 4-minute safety limit after ${step - 1} steps. Summarize progress to the user and ask if they want to continue.]")
                return
            }
            if (service.isCurrentScreenBlocked()) {
                notifyLiveVoice("[SYSTEM EVENT: Autonomous task stopped immediately because a protected banking/security app is on screen. Politely inform the user.]")
                return
            }

            val liveUserNotes = drainUserGuidance()
            if (liveUserNotes.isNotEmpty()) {
                stepHistory.add("USER LIVE UPDATE: ${liveUserNotes.joinToString("; ")}")
            }

            updateHudProgress(true, "🤖 Step $step: Observing...")
            val screenshot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896)
            val uiDump = service.getScreenUiJson()
            val compactUi = buildCompactUiSummary(uiDump)

            val plan = GeminiRestClient.generateVisionJson(
                modelId = modelId,
                systemPrompt = WORKFLOW_AGENT_SYSTEM_PROMPT,
                userPrompt = buildString {
                    append("Goal: ").append(goal).append('\n')
                    append("Step: ").append(step).append(" / ").append(MAX_WORKFLOW_STEPS).append('\n')
                    if (stepHistory.isNotEmpty()) {
                        append("Recent Actions:\n")
                        stepHistory.takeLast(12).forEach { append("- ").append(it).append('\n') }
                    }
                    if (repeatCount >= 2) {
                        append("WARNING: Previous action repeated with little progress. Try a DIFFERENT approach (e.g., scroll, tap exact visual icon coordinates, or navigate).\n")
                    }
                    append("Visible UI Tree Summary:\n").append(compactUi).append('\n')
                    append("Look at the 0..1000 gridded screenshot and decide the SINGLE best next action (or multi-stroke DRAW_STROKES batch if drawing on a canvas) to complete the goal.")
                },
                jpegBytes = screenshot,
                temperature = 0.2,
                maxOutputTokens = 4096
            ).getOrElse { err ->
                consecutiveErrors++
                PilotStateHub.log(LogLevel.ERROR, "AutoPilot", "Step $step planner error: ${err.message}")
                if (consecutiveErrors >= 3) {
                    notifyLiveVoice("[SYSTEM EVENT: Autonomous task stopped after repeated API errors: ${err.message?.take(120)}. Inform the user.]")
                    return
                }
                delay(1200L)
                return@getOrElse null
            } ?: continue

            consecutiveErrors = 0
            val statusLabel = plan.optString("status_label", "Working...").take(26)
            val done = plan.optBoolean("done", false)
            val needsHelp = plan.optBoolean("needs_user_help", false)
            val finalReport = plan.optString("final_report", "").trim()

            if (done) {
                PilotStateHub.log(LogLevel.INFO, "AutoPilot", "Workflow completed in $step steps: $finalReport")
                notifyLiveVoice(
                    "[SYSTEM EVENT: Autonomous task COMPLETED in $step steps! Goal='$goal'. Report: ${finalReport.ifBlank { "Task finished successfully." }}. Tell the user concisely in their language.]"
                )
                return
            }

            if (needsHelp) {
                PilotStateHub.log(LogLevel.INFO, "AutoPilot", "Needs user input: $finalReport")
                notifyLiveVoice(
                    "[SYSTEM EVENT: Autonomous task paused and needs user input (e.g. password/OTP/confirmation): ${finalReport.ifBlank { "Please check the screen." }}. Explain clearly to the user.]"
                )
                return
            }

            val action = plan.optString("action", "WAIT").trim().uppercase()
            val actionSig = "$action:${plan.optInt("x", -1)},${plan.optInt("y", -1)}:${plan.optString("text", "")}"
            if (actionSig == lastActionSignature) repeatCount++ else {
                lastActionSignature = actionSig
                repeatCount = 0
            }

            updateHudProgress(true, "🤖 $step: $statusLabel")
            val execResult = executeWorkflowAction(service, action, plan)
            val ok = execResult.optString("status") == "ok"

            if (execResult.optString("status") == "blocked") {
                notifyLiveVoice("[SYSTEM EVENT: Action blocked by Safety Guardrails on a financial/security app. Inform user politely.]")
                return
            }

            stepHistory.add("Step $step [$action '$statusLabel'] -> ${execResult.optString("status", "unknown")} ${execResult.optString("message", "").take(70)}")
            PilotStateHub.recordToolExecuted(
                toolName = "auto_${action.lowercase()}",
                summary = statusLabel,
                succeeded = ok
            )

            // Give browser/app UI time to settle before next observation
            val settleDelay = when (action) {
                "OPEN_APP", "OPEN_URL" -> 1400L
                "WAIT" -> 0L
                "DRAW_STROKES" -> 450L
                else -> 650L
            }
            if (settleDelay > 0L) delay(settleDelay)
        }

        notifyLiveVoice("[SYSTEM EVENT: Autonomous task completed $MAX_WORKFLOW_STEPS steps on goal '$goal'. Summarize current progress to the user and ask if they want to continue further.]")
    }

    private suspend fun executeWorkflowAction(
        service: PilotAccessibilityService,
        action: String,
        plan: JSONObject
    ): JSONObject {
        return when (action) {
            "CLICK" -> service.clickNormalized(
                nx = plan.optInt("x", 500),
                ny = plan.optInt("y", 500),
                label = plan.optString("status_label", "Tap")
            )
            "CLICK_TEXT" -> {
                val query = plan.optString("text", "").trim()
                val res = service.clickByText(query, exact = false)
                if (res.optString("status") != "ok" && plan.optInt("x", -1) in 1..999 && plan.optInt("y", -1) in 1..999) {
                    service.clickNormalized(plan.optInt("x"), plan.optInt("y"), query.take(16))
                } else {
                    res
                }
            }
            "TYPE" -> service.typeTextNormalized(
                nx = plan.optInt("x", 500),
                ny = plan.optInt("y", 500),
                text = plan.optString("text", ""),
                pressSend = plan.optBoolean("press_send", false)
            )
            "DRAG" -> service.dragNormalized(
                startNx = plan.optInt("x", 500),
                startNy = plan.optInt("y", 500),
                endNx = plan.optInt("end_x", 500),
                endNy = plan.optInt("end_y", 500),
                durationMs = plan.optInt("duration_ms", 420)
            )
            "LONG_PRESS" -> service.longPressNormalized(
                nx = plan.optInt("x", 500),
                ny = plan.optInt("y", 500),
                durationMs = plan.optInt("duration_ms", 700)
            )
            "SCROLL" -> service.scrollOnce(
                direction = plan.optString("direction", "DOWN"),
                distancePercent = plan.optInt("distance_percent", 55)
            )
            "PINCH_ZOOM" -> service.pinchZoomNormalized(
                centerNx = plan.optInt("x", 500),
                centerNy = plan.optInt("y", 500),
                zoomIn = plan.optBoolean("zoom_in", true),
                spreadNorm = plan.optInt("spread", 180)
            )
            "DRAW_STROKES" -> {
                val canvasBox = parseCanvasBox(plan.optJSONArray("canvas_box"))
                val strokes = plan.optJSONArray("strokes")
                    ?.let { DrawingGestureHelper.parseStrokesFromJsonArray(it) }
                    .orEmpty()
                service.dispatchDrawStrokes(
                    strokes = strokes,
                    normalizedSpace = true,
                    canvasBoundsNorm = canvasBox
                ) { doneCount, totalCount, strokeLabel ->
                    updateHudProgress(true, "🎨 $doneCount/$totalCount: $strokeLabel")
                }
            }
            "OPEN_APP" -> service.openAppByName(plan.optString("text", ""))
            "OPEN_URL" -> PilotAccessibilityService.deviceControlWithContext(
                appContext,
                action = "OPEN_URL",
                value = plan.optString("text", "")
            )
            "NAVIGATE" -> service.navigateGlobal(plan.optString("text", "BACK"))
            "WAIT" -> {
                val ms = plan.optInt("duration_ms", 1000).coerceIn(300, 4000)
                delay(ms.toLong())
                JSONObject().put("status", "ok").put("waited_ms", ms)
            }
            else -> JSONObject().put("status", "error").put("message", "Unknown action: $action")
        }
    }

    private fun buildCompactUiSummary(uiDump: JSONObject): String {
        val pkg = uiDump.optString("package", "unknown")
        val w = uiDump.optInt("screen_width", 1080).coerceAtLeast(1)
        val h = uiDump.optInt("screen_height", 2400).coerceAtLeast(1)
        val arr = uiDump.optJSONArray("elements") ?: return "package=$pkg, no accessibility elements (use visual coordinates)"
        val sb = StringBuilder("package=$pkg (normalized 0..1000):\n")
        val limit = minOf(arr.length(), 45)
        for (i in 0 until limit) {
            val el = arr.optJSONObject(i) ?: continue
            val nx = ((el.optInt("cx", 0) * 1000f) / w).toInt().coerceIn(0, 1000)
            val ny = ((el.optInt("cy", 0) * 1000f) / h).toInt().coerceIn(0, 1000)
            val text = el.optString("text", "")
            val desc = el.optString("desc", "")
            val flags = buildString {
                if (el.optBoolean("clickable")) append("C")
                if (el.optBoolean("editable")) append("E")
                if (el.optBoolean("scrollable")) append("S")
            }
            if (text.isBlank() && desc.isBlank() && flags.isEmpty()) continue
            sb.append("($nx,$ny)[$flags] ")
            if (text.isNotBlank()) sb.append("\"").append(text.take(45)).append("\" ")
            if (desc.isNotBlank() && desc != text) sb.append("desc=\"").append(desc.take(35)).append("\"")
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun parseCanvasBox(arr: JSONArray?): IntArray? {
        if (arr == null || arr.length() < 4) return null
        val l = arr.optInt(0, -1)
        val t = arr.optInt(1, -1)
        val r = arr.optInt(2, -1)
        val b = arr.optInt(3, -1)
        if (l < 0 || t < 0 || r <= l + 20 || b <= t + 20 || r > 1000 || b > 1000) return null
        return intArrayOf(l, t, r, b)
    }

    private fun drainUserGuidance(): List<String> {
        if (userGuidanceQueue.isEmpty()) return emptyList()
        val copy = userGuidanceQueue.toList()
        userGuidanceQueue.clear()
        return copy
    }

    private fun updateHudProgress(running: Boolean, ticker: String?) {
        PilotStateHub.updateSession { state ->
            state.copy(
                isAutonomousTaskRunning = running,
                autonomousTaskProgress = if (running) ticker else null,
                activity = when {
                    running -> AgentActivity.AUTONOMOUS_TASK
                    state.isMuted -> AgentActivity.MUTED
                    else -> AgentActivity.LISTENING
                },
                statusTicker = ticker ?: if (state.isMuted) "Mic muted" else "Listening..."
            )
        }
    }

    private fun notifyLiveVoice(systemMessage: String) {
        liveClientProvider()?.sendSystemAlert(systemMessage)
    }

    companion object {
        private const val MAX_WORKFLOW_STEPS = 35
        private const val MAX_TASK_DURATION_MS = 240_000L // 4 minutes max per autonomous run

        fun resolveMode(goal: String, rawMode: String): AutonomousMode {
            val explicit = rawMode.trim().uppercase()
            if (explicit == "DRAWING") return AutonomousMode.DRAWING
            if (explicit == "UI_WORKFLOW") return AutonomousMode.UI_WORKFLOW

            val lower = goal.lowercase()
            val drawingKeywords = listOf(
                "draw", "sketch", "paint", "doodle", "illustrate", "canvas",
                "ছবি আঁক", "ছবি আক", "আঁকো", "আঁকেন", "আকেন", "আকো", "আঁক", "ড্র কর", "পেইন্ট"
            )
            return if (drawingKeywords.any { lower.contains(it) }) {
                AutonomousMode.DRAWING
            } else {
                AutonomousMode.UI_WORKFLOW
            }
        }

        private const val DRAWING_PREP_SYSTEM_PROMPT = """
You are LivePilot's expert Canvas Inspector for Android drawing apps and browser canvas web apps (FlipaCreate, FlipaClip, ibisPaint, Canva, Chrome Canvas, Notes).
You inspect screenshots overlaid with a 0..1000 coordinate ruler grid and locate the EXACT active white/drawing canvas area and any needed brush/zoom setup taps.
Always respond with valid JSON only. Never include coordinates outside 0..1000.
"""

        private const val DRAWING_ARTIST_SYSTEM_PROMPT = """
You are LivePilot's master digital vector artist controlling an Android touch stylus via smooth quadratic Bezier strokes.
Your job is to draw genuinely recognizable, clean, well-proportioned, expressive artwork (birds, animals, flowers, trees, houses, portraits, diagrams, calligraphy) directly inside the user's canvas.
Rules:
- All stroke points use CANVAS-RELATIVE coordinates [x, y] in 0..1000 (0,0 = canvas top-left, 500,500 = canvas center, 1000,1000 = canvas bottom-right).
- Keep coordinates cleanly inside 80..920 so artwork sits attractively inside the canvas.
- Use smooth multi-point curves (5 to 14 points per curved contour) instead of crude triangles or boxes.
- Break the drawing into clear logical strokes (body silhouette, head, beak, eye, wing arcs, layered feathers, tail, legs, branch, leaves, etc.).
- Output ONLY valid JSON.
"""

        private const val WORKFLOW_AGENT_SYSTEM_PROMPT = """
You are LivePilot's Autonomous Android Vision Agent. You control any Android app or Chrome web browser step-by-step until the user's goal is completely accomplished.
Every screenshot has a 0..1000 coordinate ruler grid (0,0 = top-left, 1000,1000 = bottom-right).
Available actions:
- CLICK: tap normalized (x, y) in 0..1000
- CLICK_TEXT: find & tap text (provide "text" plus backup visual "x", "y")
- TYPE: tap input box at (x, y), enter "text", optionally "press_send": true
- DRAG: drag from (x, y) to (end_x, end_y) over "duration_ms"
- LONG_PRESS: hold at (x, y) for "duration_ms"
- SCROLL: swipe "direction" (DOWN/UP/LEFT/RIGHT), "distance_percent" (25-80)
- PINCH_ZOOM: two-finger pinch at (x, y) with "zoom_in": true/false
- DRAW_STROKES: draw smooth multi-stroke vector paths inside "canvas_box": [l,t,r,b] with "strokes": [{"label":"...","points":[[x,y],...]}]
- OPEN_APP: launch Android app by "text" name
- OPEN_URL: open web URL in "text"
- NAVIGATE: "text" = BACK | HOME | RECENTS
- WAIT: wait "duration_ms" (400-3500) for page/app load

Never touch passwords, OTPs, banking, or payments — set "needs_user_help": true instead.
Always output strict JSON:
{
  "thought": "brief reasoning",
  "status_label": "short 2-4 word status",
  "done": false,
  "needs_user_help": false,
  "final_report": "",
  "action": "CLICK",
  "x": 500, "y": 500,
  "end_x": 500, "end_y": 500,
  "text": "",
  "press_send": false,
  "direction": "DOWN",
  "distance_percent": 55,
  "duration_ms": 420,
  "zoom_in": true
}
"""
    }
}

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
import java.util.concurrent.atomic.AtomicLong
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
    private val taskCounter = AtomicLong(0L)

    @Volatile
    private var currentTaskToken = 0L

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
        val taskToken = taskCounter.incrementAndGet()
        currentTaskToken = taskToken

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
                if (currentTaskToken == taskToken) {
                    activeJob = null
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
        val modelId = PilotStateHub.settings.value.agentModel
        var totalStrokesDrawn = 0

        // Phase 1: Inspect screen, detect canvas bounds, prepare zoom/brush/color
        updateHudProgress(true, "🎨 Finding canvas...")
        val initialShot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896)
        if (initialShot == null) {
            notifyLiveVoice("[SYSTEM EVENT: Could not capture screen for drawing. Ask user to check Accessibility permission or leave protected app.]")
            return
        }

        val prepResult = GeminiRestClient.generateVisionJson(
            modelId = modelId,
            systemPrompt = DRAWING_PREP_SYSTEM_PROMPT,
            userPrompt = buildString {
                append("User Drawing Goal: ").append(goal).append('\n')
                append("The screenshot has a 0..1000 coordinate ruler grid along the top (X) and left (Y) edges.\n")
                append("1. Locate the exact visible drawing canvas rectangle as canvas_box [left, top, right, bottom] in 0..1000 coordinates.\n")
                append("2. Only if the canvas is very tiny/zoomed out AND a visible '+' zoom button exists, include 1 tap on '+' in prep_actions.\n")
                append("3. If an eraser/pan tool is selected instead of a brush/pencil, include 1 tap on the brush/pencil tool in prep_actions.\n")
                append("4. Set canvas_found=false ONLY if there is completely no drawing canvas or white sketch area anywhere on screen.\n")
                append("Return strict JSON: {\"canvas_found\": true, \"canvas_box\": [left,top,right,bottom], \"prep_actions\": [], \"note\": \"...\"}")
            },
            jpegBytes = initialShot,
            temperature = 0.15,
            maxOutputTokens = 4096,
            thinkingBudget = 0
        )

        val prepPlan = prepResult.getOrNull()
        if (prepPlan == null) {
            val errMsg = prepResult.exceptionOrNull()?.message.orEmpty()
            // If it's a hard API key / network failure, report and stop cleanly; otherwise fall back to center canvas box and draw!
            if (errMsg.contains("API key", ignoreCase = true) ||
                errMsg.contains("quota", ignoreCase = true) ||
                errMsg.contains("HTTP 401", ignoreCase = true) ||
                errMsg.contains("HTTP 403", ignoreCase = true)
            ) {
                notifyLiveVoice("[SYSTEM EVENT: Drawing could not start due to API error: ${errMsg.take(120)}. Tell the user briefly.]")
                return
            }
            PilotStateHub.log(LogLevel.INFO, "AutoPilot", "Canvas prep used fallback bounds ($errMsg)")
        }

        val canvasFound = prepPlan?.optBoolean("canvas_found", true) ?: true
        if (!canvasFound) {
            PilotStateHub.log(LogLevel.INFO, "AutoPilot", "Canvas not immediately visible; switching to UI workflow first.")
            runAutonomousWorkflowLoop(service, goal, startedAtMs)
            return
        }

        var canvasBox = prepPlan?.let { parseCanvasBox(it.optJSONArray("canvas_box")) }
        val prepActions = prepPlan?.optJSONArray("prep_actions")
        var ranPrepActions = false
        if (prepActions != null && prepActions.length() > 0) {
            val count = minOf(prepActions.length(), 3)
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
                maxOutputTokens = 4096,
                thinkingBudget = 0
            ).getOrNull()
            confirmObj?.optJSONArray("canvas_box")?.let {
                parseCanvasBox(it)?.let { updated -> canvasBox = updated }
            }
        }

        val activeCanvasBox = canvasBox ?: intArrayOf(150, 260, 850, 720)
        PilotStateHub.log(
            LogLevel.INFO,
            "AutoPilot",
            "Locked canvas_box=[${activeCanvasBox.joinToString(",")}]"
        )

        // Phase 2: 4-Stage Studio Artist Loop (Outline -> Self-Critique/Color/Brush/Fill -> Leaves/Details -> Final Polish & Cleanup)
        val maxStages = 4
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
                    1 -> "🎨 Stage 1: Setting brush & sketch..."
                    2 -> "🎨 Stage 2: Color, size & main form..."
                    3 -> "🎨 Stage 3: Accent details & shading..."
                    else -> "🎨 Stage 4: Cleanup & final polish..."
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
                        Inspect the screenshot. Stage 1 builds a CLEAN, WELL-PROPORTIONED, ATTRACTIVE base structure!
                        ARTISTIC & TOOL CONTROLS:
                        1. Look at the toolbar (brush size slider, color swatch, brush/eraser toggle).
                           If the brush color is unsuitable (e.g. white on white canvas, or wrong starting color) OR brush size needs adjusting OR an eraser is currently selected instead of brush, set "tool_goal" to describe the exact toolbar change needed BEFORE drawing (e.g., "Switch to Brush tool, set dark/clean outline color, set brush size ~5"). Otherwise leave "tool_goal": "".
                        2. All stroke points MUST use CANVAS-RELATIVE coordinates from 0 to 1000:
                           (0,0) = top-left of white canvas, (500,500) = dead center of canvas, (1000,1000) = bottom-right of canvas.
                        3. Compose inside 140..860 so artwork is balanced, centered, and NEVER cramped or sloppy.
                        4. Use 10 to 18 smooth curved strokes (5 to 12 waypoints each) for clean foundational geometry:
                           - For a FLOWER / ROSE / LOTUS: draw a gentle curved central stem, circular sepal base, a round center disk/core, and 6–8 symmetrical wide rounded petals radiating cleanly around the center disk (each petal = wide smooth arc leaving space, NOT pinched tangled figure-8 loops!).
                           - For a BIRD: proportional tilted oval body, smooth neck/head dome, crisp pointed beak, sweeping curved wing silhouette, tail fan curves, perched legs, and a clean branch.
                        - Return strict JSON:
                          {
                            "done": false,
                            "undo_count": 0,
                            "tool_goal": "",
                            "summary": "What Stage 1 sets up and draws",
                            "strokes": [
                              {"label": "Center circle", "points": [[460,360],[500,335],[540,360],[540,400],[500,425],[460,400],[460,360]]}
                            ]
                          }
                        """.trimIndent()
                    )
                } else {
                    append(
                        """
                        CAREFULLY CRITIQUE the latest screenshot of what has been drawn so far!
                        You are a real digital artist controlling ibisPaint / FlipaCreate / FlipaClip / Canvas tools:
                        1. SELF-CORRECTION (Undo / Eraser):
                           - If previous strokes look messy, badly overlapped, lopsided, or ugly, set "undo_count" (1 to 10) to undo bad strokes cleanly FIRST! Or set "tool_goal" to use Eraser + redraw cleanly!
                        2. BRUSH SIZE & COLOR VARIETY (CRITICAL — DO NOT DRAW MONOCHROME STICK ART!):
                           - Use "tool_goal" whenever changing color, adjusting brush size slider (bigger for petal fill/coloring, smaller for fine outlines/veins/eye), or toggling Brush/Eraser!
                           - Example for Stage 2 flower: "Open color picker, choose vibrant rose-red/pink, close color picker, and set brush size slider larger (~12)"
                           - Example for Stage 3 flower: "Open color picker, choose lush green for leaves (or golden yellow for center pollen dots) and set brush size medium-fine (~5)"
                           - Remember if opening a color wheel modal takes multiple taps (tap swatch -> tap color -> close popup), just describe the goal in "tool_goal" — an interactive Vision sub-agent will navigate the popup for you step by step BEFORE dispatching "strokes"!
                        3. STROKES:
                           - Add 8 to 18 intentional strokes (petal shading/fill arcs, secondary layered petals, serrated/curved leaves with midrib veins, golden center texture dots, feathers, highlights, etc.) in CANVAS-RELATIVE 0..1000 coordinates.
                           - Only set "done": true if the drawing genuinely looks rich, colorful/shaded (when tools allow), clean, and complete with zero messy defects.
                        - Return strict JSON:
                          {
                            "done": false,
                            "undo_count": 0,
                            "tool_goal": "",
                            "summary": "Critique and improvements in this stage",
                            "strokes": [...]
                          }
                        """.trimIndent()
                    )
                }
            }

            val stagePlan = GeminiRestClient.generateVisionJson(
                modelId = modelId,
                systemPrompt = DRAWING_ARTIST_SYSTEM_PROMPT,
                userPrompt = stagePrompt,
                jpegBytes = latestShot,
                temperature = 0.25,
                maxOutputTokens = 8192,
                thinkingBudget = 512
            ).getOrElse { err ->
                PilotStateHub.log(LogLevel.ERROR, "AutoPilot", "Drawing stage $stage failed: ${err.message}")
                if (totalStrokesDrawn > 0) break
                notifyLiveVoice("[SYSTEM EVENT: Drawing planner hit an error: ${err.message?.take(120)}. Inform user.]")
                return
            }

            // Step A: Execute Undo taps if artist critiqued previous strokes as messy
            val undoCount = stagePlan.optInt("undo_count", 0).coerceIn(0, 12)
            if (undoCount > 0) {
                updateHudProgress(true, "↩️ Undoing $undoCount mistake(s)...")
                runVisualToolAdjustment(
                    service = service,
                    modelId = modelId,
                    toolGoal = "Tap the Undo button on the toolbar exactly $undoCount time(s) to undo messy strokes, then stop immediately.",
                    initialShot = latestShot,
                    maxSteps = undoCount + 2
                )
                delay(320L)
                latestShot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896) ?: latestShot
            }

            // Step B: Interactive Vision sub-loop for Brush Size slider, Color Wheel popup, or Eraser/Brush switch!
            val toolGoal = stagePlan.optString("tool_goal", "").trim()
            if (toolGoal.isNotBlank()) {
                updateHudProgress(true, "🖌️ ${toolGoal.take(26)}...")
                runVisualToolAdjustment(
                    service = service,
                    modelId = modelId,
                    toolGoal = toolGoal,
                    initialShot = latestShot,
                    maxSteps = 6
                )
                delay(300L)
                latestShot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896) ?: latestShot
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
                    updateHudProgress(true, "🎨 $stage/$maxStages • $doneCount/$totalCount: $strokeLabel")
                }
                totalStrokesDrawn += res.optInt("completed_strokes", 0)
                PilotStateHub.recordToolExecuted(
                    toolName = "autonomous_draw_stage_$stage",
                    summary = "Drew ${res.optInt("completed_strokes", 0)} strokes (Stage $stage)",
                    succeeded = res.optString("status") == "ok"
                )
            }

            val isDone = stagePlan.optBoolean("done", false) && userGuidanceQueue.isEmpty()
            if (isDone || (stage >= 2 && strokes.isEmpty() && undoCount == 0 && toolGoal.isBlank() && userGuidanceQueue.isEmpty())) {
                break
            }

            // Capture updated canvas screenshot for visual self-critique in next stage
            if (stage < maxStages) {
                delay(450L)
                latestShot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896) ?: latestShot
            }
        }

        val elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(1L)
        if (totalStrokesDrawn == 0) {
            PilotStateHub.log(LogLevel.ERROR, "AutoPilot", "Drawing ended with 0 strokes drawn.")
            notifyLiveVoice(
                "[SYSTEM EVENT: Autonomous drawing FAILED (0 strokes were drawn on canvas). Do NOT say it is finished. Honestly tell the user the canvas could not be drawn on and ask them to open the drawing canvas clearly.]"
            )
            return
        }
        PilotStateHub.log(
            LogLevel.INFO,
            "AutoPilot",
            "Drawing finished: $totalStrokesDrawn total strokes in ${elapsedSec}s."
        )
        notifyLiveVoice(
            "[SYSTEM EVENT: Autonomous drawing task VERIFIED COMPLETED! Goal='$goal', drew $totalStrokesDrawn smooth strokes across stages in ${elapsedSec} seconds. Warmly inform the user in 1-2 short sentences in their language and ask if they want any tweaks or more details.]"
        )
    }

    /**
     * Interactive multi-step Vision toolbar controller for drawing apps (ibisPaint, FlipaCreate, FlipaClip, Canva).
     * Supports tapping Color Swatch -> picking color inside Color Wheel popup -> closing popup,
     * dragging Brush Size/Opacity sliders, tapping +/- size buttons, toggling Brush<->Eraser, and tapping Undo/Redo.
     */
    private suspend fun runVisualToolAdjustment(
        service: PilotAccessibilityService,
        modelId: String,
        toolGoal: String,
        initialShot: ByteArray,
        maxSteps: Int = 6
    ) {
        var shot = initialShot
        val history = mutableListOf<String>()

        for (step in 1..maxSteps) {
            coroutineContext.ensureActive()
            val uiSummary = buildCompactUiSummary(service.getScreenUiJson())
            val plan = GeminiRestClient.generateVisionJson(
                modelId = modelId,
                systemPrompt = DRAWING_TOOL_CONTROLLER_PROMPT,
                userPrompt = buildString {
                    append("Toolbar Adjustment Goal: ").append(toolGoal).append('\n')
                    append("Step ").append(step).append(" of ").append(maxSteps).append('\n')
                    if (history.isNotEmpty()) {
                        append("Steps already done:\n")
                        history.forEach { append("- ").append(it).append('\n') }
                    }
                    append("Visible UI Summary:\n").append(uiSummary).append('\n')
                    append("Look at the 0..1000 gridded screenshot. If a Color Wheel or Brush menu popup is STILL open blocking the canvas, tap its close/back/outside margin before setting done=true! Output strict JSON.")
                },
                jpegBytes = shot,
                temperature = 0.12,
                maxOutputTokens = 4096,
                thinkingBudget = 0
            ).getOrNull() ?: return

            if (plan.optBoolean("done", false)) {
                PilotStateHub.log(LogLevel.TOOL, "DrawTools", "Adjusted tools cleanly in ${step - 1} steps: $toolGoal")
                return
            }

            val action = plan.optString("action", "TAP").trim().uppercase()
            val label = plan.optString("label", action).take(22)
            when (action) {
                "TAP" -> {
                    val x = plan.optInt("x", -1)
                    val y = plan.optInt("y", -1)
                    if (x in 1..999 && y in 1..999) {
                        service.clickNormalized(x, y, label)
                        history.add("TAP ($x,$y) '$label'")
                    }
                }
                "DRAG" -> {
                    val sx = plan.optInt("x", -1)
                    val sy = plan.optInt("y", -1)
                    val ex = plan.optInt("end_x", -1)
                    val ey = plan.optInt("end_y", -1)
                    if (sx in 1..999 && sy in 1..999 && ex in 1..999 && ey in 1..999) {
                        service.dragNormalized(sx, sy, ex, ey, durationMs = 360)
                        history.add("DRAG ($sx,$sy)->($ex,$ey) '$label'")
                    }
                }
                "WAIT" -> {
                    delay(450L)
                    history.add("WAIT")
                }
            }
            if (step < maxSteps) {
                delay(380L)
                shot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896) ?: shot
            }
        }
    }

    // --- GENERAL AUTONOMOUS MULTI-STEP UI / BROWSER WORKFLOW LOOP (DUAL-BRAIN + VISUAL AUDITOR) ---

    private suspend fun runAutonomousWorkflowLoop(
        service: PilotAccessibilityService,
        goal: String,
        startedAtMs: Long
    ) {
        val stepHistory = mutableListOf<String>()
        var consecutiveErrors = 0
        var lastActionSignature = ""
        var repeatCount = 0
        var noChangeStreak = 0
        var actionsExecuted = 0
        var auditorRejections = 0

        for (step in 1..MAX_WORKFLOW_STEPS) {
            coroutineContext.ensureActive()
            val settings = PilotStateHub.settings.value
            val modelId = settings.agentModel
            if (System.currentTimeMillis() - startedAtMs > MAX_TASK_DURATION_MS) {
                notifyLiveVoice("[SYSTEM EVENT: Autonomous task reached the 4-minute safety limit after ${step - 1} steps (NOT fully verified complete). Honestly summarize what was done so far and ask if the user wants to continue.]")
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

            updateHudProgress(true, "🧠 Step $step: Thinking...")
            val screenshot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896)
            val beforeUiDump = service.getScreenUiJson()
            val beforePkg = beforeUiDump.optString("package", "unknown")
            val beforeFingerprint = buildUiFingerprint(beforeUiDump)
            val compactUi = buildCompactUiSummary(beforeUiDump)

            val plan = GeminiRestClient.generateVisionJson(
                modelId = modelId,
                systemPrompt = WORKFLOW_AGENT_SYSTEM_PROMPT,
                userPrompt = buildString {
                    append("User Full Goal: ").append(goal).append('\n')
                    append("Step: ").append(step).append(" / ").append(MAX_WORKFLOW_STEPS)
                        .append(" | Real Actions Executed So Far: ").append(actionsExecuted).append('\n')
                    if (stepHistory.isNotEmpty()) {
                        append("Action History & Observed Results:\n")
                        stepHistory.takeLast(14).forEach { append("- ").append(it).append('\n') }
                    }
                    if (repeatCount >= 2 || noChangeStreak >= 2) {
                        append("CRITICAL RECOVERY WARNING: Your recent action repeated or did NOT change the screen! Do NOT tap the same spot again and do NOT claim done. Try a DIFFERENT strategy: use SEARCH, FIND_CLICK, SCROLL, NAVIGATE(BACK), or tap a visually distinct element.\n")
                    }
                    append("Visible UI Tree Summary:\n").append(compactUi).append('\n')
                    append("CRITICAL RULE: Only set done=true if the CURRENT screenshot clearly shows EVERY part of the user's goal is ALREADY completely finished (and remaining_steps is empty). Otherwise set done=false and choose the SINGLE best next action.")
                },
                jpegBytes = screenshot,
                temperature = 0.15,
                maxOutputTokens = 8192,
                thinkingBudget = 512
            ).getOrElse { err ->
                consecutiveErrors++
                PilotStateHub.log(LogLevel.ERROR, "AutoPilot", "Step $step planner error: ${err.message}")
                if (consecutiveErrors >= 3) {
                    notifyLiveVoice("[SYSTEM EVENT: Autonomous task FAILED due to API error: ${err.message?.take(120)}. Do NOT say finished; honestly explain the error.]")
                    return
                }
                delay(1200L)
                return@getOrElse null
            } ?: continue

            consecutiveErrors = 0
            val statusLabel = plan.optString("status_label", "Working...").take(26)
            val claimedDone = plan.optBoolean("done", false)
            val needsHelp = plan.optBoolean("needs_user_help", false)
            val finalReport = plan.optString("final_report", "").trim()
            val remainingSteps = parseStringList(plan.optJSONArray("remaining_steps"))

            if (needsHelp) {
                PilotStateHub.log(LogLevel.INFO, "AutoPilot", "Needs user input: $finalReport")
                notifyLiveVoice(
                    "[SYSTEM EVENT: Autonomous task paused and needs user input (e.g. password/OTP/confirmation): ${finalReport.ifBlank { "Please check the screen." }}. Explain clearly to the user.]"
                )
                return
            }

            if (claimedDone) {
                // Guard 1: Planner itself still listed remaining steps!
                if (remainingSteps.isNotEmpty()) {
                    PilotStateHub.log(
                        LogLevel.INFO,
                        "AutoPilot",
                        "Blocked premature done at step $step; remaining=${remainingSteps.joinToString()}"
                    )
                    stepHistory.add("GUARD REJECTED done=true because remaining_steps is not empty: ${remainingSteps.joinToString(", ")}. Execute the next remaining step now.")
                    continue
                }

                // Guard 2: Independent Strict Visual Auditor checks the real screen before allowing completion!
                if (settings.verifyTaskCompletion && auditorRejections < MAX_AUDITOR_VETOES) {
                    updateHudProgress(true, "🔍 Verifying completion...")
                    val audit = verifyGoalCompleted(
                        service = service,
                        modelId = modelId,
                        goal = goal,
                        stepHistory = stepHistory,
                        claimedReport = finalReport
                    )
                    if (!audit.verifiedDone) {
                        auditorRejections++
                        val missing = audit.missingWork.ifBlank { "Goal is not yet visibly completed on screen." }
                        PilotStateHub.log(
                            LogLevel.INFO,
                            "AutoPilot",
                            "Auditor VETO #$auditorRejections: $missing"
                        )
                        stepHistory.add("VISUAL AUDITOR REJECTED COMPLETION: $missing — Keep executing until this is actually complete on screen!")
                        continue
                    }
                    PilotStateHub.log(LogLevel.INFO, "AutoPilot", "Auditor VERIFIED completion: ${audit.evidence}")
                }

                PilotStateHub.log(LogLevel.INFO, "AutoPilot", "Workflow VERIFIED COMPLETED in $step steps: $finalReport")
                notifyLiveVoice(
                    "[SYSTEM EVENT: Autonomous task VERIFIED COMPLETED in $step steps! Goal='$goal'. Report: ${finalReport.ifBlank { "Task finished and verified on screen." }}. Tell the user concisely in their language.]"
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
            if (ok && action != "WAIT") actionsExecuted++

            if (execResult.optString("status") == "blocked") {
                notifyLiveVoice("[SYSTEM EVENT: Action blocked by Safety Guardrails on a financial/security app. Inform user politely.]")
                return
            }

            // Give browser/app UI time to settle before checking screen diff
            val settleDelay = when (action) {
                "OPEN_APP", "OPEN_URL", "SEARCH" -> 1400L
                "FIND_CLICK" -> 900L
                "WAIT" -> 0L
                "DRAW_STROKES" -> 450L
                else -> 700L
            }
            if (settleDelay > 0L) delay(settleDelay)

            val afterUiDump = service.getScreenUiJson()
            val afterPkg = afterUiDump.optString("package", "unknown")
            val afterFingerprint = buildUiFingerprint(afterUiDump)
            val screenChanged = action == "WAIT" || action == "DRAW_STROKES" ||
                afterPkg != beforePkg || afterFingerprint != beforeFingerprint

            if (screenChanged) {
                noChangeStreak = 0
            } else {
                noChangeStreak++
            }

            val changeNote = if (screenChanged) {
                if (afterPkg != beforePkg) "app=$beforePkg->$afterPkg" else "screen_changed=true"
            } else {
                "NO_SCREEN_CHANGE (action may have missed target)"
            }
            stepHistory.add(
                "Step $step [$action '$statusLabel'] -> ${execResult.optString("status", "unknown")} ($changeNote) ${execResult.optString("message", "").take(60)}"
            )
            PilotStateHub.recordToolExecuted(
                toolName = "auto_${action.lowercase()}",
                summary = statusLabel,
                succeeded = ok
            )
        }

        notifyLiveVoice("[SYSTEM EVENT: Autonomous task reached $MAX_WORKFLOW_STEPS steps on goal '$goal' without full verification. Honestly tell the user what progress was made and ask if they want to continue.]")
    }

    private data class AuditVerdict(
        val verifiedDone: Boolean,
        val missingWork: String,
        val evidence: String
    )

    private suspend fun verifyGoalCompleted(
        service: PilotAccessibilityService,
        modelId: String,
        goal: String,
        stepHistory: List<String>,
        claimedReport: String
    ): AuditVerdict {
        val shot = service.captureVisionJpegBytes(withCoordinateGrid = true, maxWidth = 896)
        val uiSummary = buildCompactUiSummary(service.getScreenUiJson())
        val res = GeminiRestClient.generateVisionJson(
            modelId = modelId,
            systemPrompt = TASK_AUDITOR_SYSTEM_PROMPT,
            userPrompt = buildString {
                append("User Original Goal: ").append(goal).append('\n')
                append("Agent Claimed Report: ").append(claimedReport.ifBlank { "(none)" }).append('\n')
                append("Recent Steps Taken:\n")
                stepHistory.takeLast(8).forEach { append("- ").append(it).append('\n') }
                append("Current Screen UI Summary:\n").append(uiSummary).append('\n')
                append("Look strictly at the CURRENT screenshot. Is EVERY part of the user's goal genuinely, visibly completed right now? Return strict JSON: {\"verified_done\": true/false, \"missing_work\": \"...\", \"evidence\": \"...\"}")
            },
            jpegBytes = shot,
            temperature = 0.1,
            maxOutputTokens = 4096,
            thinkingBudget = 0
        ).getOrNull()

        if (res == null) {
            return AuditVerdict(verifiedDone = true, missingWork = "", evidence = "Auditor skipped on API error")
        }
        return AuditVerdict(
            verifiedDone = res.optBoolean("verified_done", false),
            missingWork = res.optString("missing_work", "").trim(),
            evidence = res.optString("evidence", "").trim()
        )
    }

    private suspend fun executeWorkflowAction(
        service: PilotAccessibilityService,
        action: String,
        plan: JSONObject
    ): JSONObject {
        return when (action) {
            "SEARCH" -> SmartSearchAndFinder.searchOnScreen(
                service = service,
                appContext = appContext,
                rawQuery = plan.optString("text", ""),
                openInGoogleIfNeeded = plan.optBoolean("open_in_google", false)
            )
            "FIND_CLICK" -> SmartSearchAndFinder.findAndInteract(
                service = service,
                rawTarget = plan.optString("text", ""),
                rawAction = "CLICK",
                maxScrolls = plan.optInt("max_scrolls", 4)
            )
            "CLICK_NODE" -> service.clickNodeOrNormalized(
                nodeIndex = plan.optInt("node_index", -1),
                fallbackNx = plan.optInt("x", 500),
                fallbackNy = plan.optInt("y", 500),
                label = plan.optString("status_label", "Tap")
            )
            "CLICK" -> {
                val optIdx = plan.optInt("node_index", -1)
                if (optIdx >= 0) {
                    service.clickNodeOrNormalized(
                        nodeIndex = optIdx,
                        fallbackNx = plan.optInt("x", 500),
                        fallbackNy = plan.optInt("y", 500),
                        label = plan.optString("status_label", "Tap")
                    )
                } else {
                    service.clickNormalized(
                        nx = plan.optInt("x", 500),
                        ny = plan.optInt("y", 500),
                        label = plan.optString("status_label", "Tap")
                    )
                }
            }
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

    private fun buildUiFingerprint(uiDump: JSONObject): String {
        val pkg = uiDump.optString("package", "")
        val arr = uiDump.optJSONArray("elements") ?: return pkg
        val sb = StringBuilder(pkg).append('#').append(arr.length()).append(':')
        val limit = minOf(arr.length(), 28)
        for (i in 0 until limit) {
            val el = arr.optJSONObject(i) ?: continue
            val t = el.optString("text", "").ifBlank { el.optString("desc", "") }
            if (t.isNotBlank()) {
                sb.append(t.take(18)).append('|')
            }
        }
        return sb.toString()
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty()) add(item)
            }
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
            val nodeIdx = el.optInt("index", i)
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
            sb.append("#").append(nodeIdx).append(" ($nx,$ny)[$flags] ")
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
        private const val MAX_AUDITOR_VETOES = 3
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
You are LivePilot's master digital vector artist controlling an Android stylus via smooth quadratic Bezier strokes PLUS full app toolbar control (Brush Size slider, Color Wheel Picker, Brush/Eraser toggle, Undo/Redo).
Rules:
- All stroke points use CANVAS-RELATIVE coordinates [x, y] in 0..1000 (0,0 = canvas top-left, 500,500 = canvas center, 1000,1000 = canvas bottom-right).
- Never draw crude stick-loops or sloppy tangled ovals! Keep main artwork gracefully balanced inside 140..860.
- Use 5 to 12 smooth curved perimeter waypoints per petal, wing, leaf, or contour.
- Actively vary BRUSH SIZE and COLOR across stages via "tool_goal" so artwork looks vibrant, layered, and finished (e.g., clean stem/outline -> bold colored petals/body -> fine vein/pollen/feather/highlight strokes).
- Self-critique honestly: if the previous stage has ugly overlapping lines, use "undo_count" or Eraser to fix them before drawing new strokes.
- Output ONLY valid JSON.
"""

        private const val DRAWING_TOOL_CONTROLLER_PROMPT = """
You are controlling the UI toolbar of a mobile drawing app (such as ibisPaint X, FlipaCreate, FlipaClip, Canva, or Sketchbook) using a 0..1000 gridded screenshot.
Your ONLY job is to complete the requested toolbar/brush/color/eraser/undo adjustment cleanly and then close any open popup so the white drawing canvas is uncovered!
Available JSON actions:
- TAP: tap a visible button/icon/color swatch at (x, y) in 0..1000.
- DRAG: drag a horizontal or vertical slider thumb (e.g. Brush Size or Opacity slider) from (x, y) to (end_x, end_y) in 0..1000.
- WAIT: pause briefly for a popup animation.
- Set "done": true ONLY when the requested tool/color/brush-size/undo change is complete AND any Color Picker / Brush modal popup is CLOSED!
Always output strict JSON:
{"done": false, "action": "TAP", "x": 500, "y": 500, "end_x": 500, "end_y": 500, "label": "Color swatch"}
"""

        private const val WORKFLOW_AGENT_SYSTEM_PROMPT = """
You are LivePilot's Dedicated Non-Live Reasoning & Vision Action Brain controlling an Android phone step-by-step until the user's goal is 100% VISIBLY ACCOMPLISHED on screen.
Every screenshot has a 0..1000 coordinate ruler grid (0,0 = top-left, 1000,1000 = bottom-right). The UI list shows `#index (nx,ny)[flags] "label"`.
PREFER ACCURACY: When your target appears in the UI list (`#index`), ALWAYS use `CLICK_NODE` with `"node_index": index` AND backup `"x": nx, "y": ny`! Only use raw `CLICK` `(x, y)` for unlabeled visual icons or graphics.
Available actions:
- CLICK_NODE: MOST ACCURATE! Tap exact UI element by "node_index" from the `#index` list, plus backup visual "x", "y".
- SEARCH: smart in-app or Google search! Provide "text" (query) and optional "open_in_google": true/false.
- FIND_CLICK: smart multi-scroll fuzzy Bengali+English item finder & clicker! Provide "text" (button/video/setting/contact name) and optional "max_scrolls": 4.
- CLICK: tap exact normalized (x, y) in 0..1000 (use ONLY when target is not in `#index` list).
- CLICK_TEXT: tap visible text ("text" plus backup visual "x", "y")
- TYPE: tap input box at (x, y), type "text", optionally "press_send": true
- DRAG: drag from (x, y) to (end_x, end_y) over "duration_ms"
- LONG_PRESS: hold at (x, y) for "duration_ms"
- SCROLL: swipe "direction" (DOWN/UP/LEFT/RIGHT), "distance_percent" (25-80)
- PINCH_ZOOM: two-finger pinch at (x, y) with "zoom_in": true/false
- DRAW_STROKES: draw smooth multi-stroke vector paths inside "canvas_box": [l,t,r,b] with "strokes": [{"label":"...","points":[[x,y],...]}]
- OPEN_APP: launch Android app by "text" name
- OPEN_URL: open web URL in "text"
- NAVIGATE: "text" = BACK | HOME | RECENTS
- WAIT: wait "duration_ms" (400-3500) for page/app load

CRITICAL ANTI-PREMATURE-COMPLETION RULES:
1. Break the goal into concrete sub-steps in "remaining_steps".
2. NEVER set "done": true while ANY sub-step is still unexecuted or unverified on the screenshot!
3. Opening an app or typing a search is NOT finishing if the user asked to play a video, open a specific item, toggle a setting, or send a message!
4. Never touch passwords, OTPs, banking, or payments — set "needs_user_help": true instead.

Always output strict JSON:
{
  "thought": "brief honest reasoning based on what is actually visible right now",
  "completed_steps": ["..."],
  "remaining_steps": ["..."],
  "status_label": "short 2-4 word status",
  "done": false,
  "needs_user_help": false,
  "final_report": "",
  "action": "CLICK_NODE",
  "node_index": -1,
  "x": 500, "y": 500,
  "end_x": 500, "end_y": 500,
  "text": "",
  "open_in_google": false,
  "max_scrolls": 4,
  "press_send": false,
  "direction": "DOWN",
  "distance_percent": 55,
  "duration_ms": 420,
  "zoom_in": true
}
"""

        private const val TASK_AUDITOR_SYSTEM_PROMPT = """
You are LivePilot's STRICT Independent Visual Completion Auditor.
An automation agent claims it has finished the user's goal. Your ONLY job is to inspect the ACTUAL current Android screenshot and UI tree like a skeptical QA engineer.
Rules:
- Be STRICT and HONEST. Do NOT rubber-stamp completion!
- If the user asked to search AND open/play a specific video/item, merely showing search results is verified_done=false.
- If the user asked to turn on/off a setting, verify the toggle state on screen actually matches.
- If the user asked to send a message, verify the sent message bubble appears in the chat (not just typed in the box).
- If the screen clearly shows the final requested result is achieved, set verified_done=true.
- Output ONLY strict JSON: {"verified_done": true/false, "missing_work": "exact remaining step if false", "evidence": "what you visibly see on screen"}.
"""
    }
}

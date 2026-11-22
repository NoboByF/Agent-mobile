package com.example.automation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToLong

data class DrawStrokeSpec(
    val points: List<Pair<Float, Float>>,
    val durationMs: Long = 0L,
    val label: String = ""
)

object DrawingGestureHelper {

    /**
     * Parses strokes from either:
     * - JSON object containing "strokes": [...]
     * - JSON array of stroke objects: [{"label": "...", "duration_ms": 300, "points": [[x,y], ...]}]
     * - JSON array of raw point arrays: [[[x,y], [x,y]], ...]
     */
    fun parseStrokesFromJson(rawJson: String): List<DrawStrokeSpec> {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) return emptyList()
        return runCatching {
            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                val arr = obj.optJSONArray("strokes") ?: return emptyList()
                parseStrokesFromJsonArray(arr)
            } else {
                parseStrokesFromJsonArray(JSONArray(trimmed))
            }
        }.getOrDefault(emptyList())
    }

    fun parseStrokesFromJsonArray(arr: JSONArray): List<DrawStrokeSpec> {
        val strokes = mutableListOf<DrawStrokeSpec>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            when (item) {
                is JSONObject -> {
                    val ptsArr = item.optJSONArray("points") ?: continue
                    val pts = parsePointArray(ptsArr)
                    if (pts.isNotEmpty()) {
                        strokes.add(
                            DrawStrokeSpec(
                                points = pts,
                                durationMs = item.optLong("duration_ms", 0L),
                                label = item.optString("label", "Stroke ${i + 1}").take(32)
                            )
                        )
                    }
                }
                is JSONArray -> {
                    val pts = parsePointArray(item)
                    if (pts.isNotEmpty()) {
                        strokes.add(DrawStrokeSpec(points = pts, label = "Stroke ${i + 1}"))
                    }
                }
            }
        }
        return strokes
    }

    private fun parsePointArray(ptsArr: JSONArray): List<Pair<Float, Float>> {
        val pts = mutableListOf<Pair<Float, Float>>()
        for (j in 0 until ptsArr.length()) {
            val entry = ptsArr.opt(j) ?: continue
            when (entry) {
                is JSONArray -> {
                    if (entry.length() >= 2) {
                        val x = entry.optDouble(0, Double.NaN)
                        val y = entry.optDouble(1, Double.NaN)
                        if (!x.isNaN() && !y.isNaN()) {
                            pts.add(x.toFloat() to y.toFloat())
                        }
                    }
                }
                is JSONObject -> {
                    val x = entry.optDouble("x", Double.NaN)
                    val y = entry.optDouble("y", Double.NaN)
                    if (!x.isNaN() && !y.isNaN()) {
                        pts.add(x.toFloat() to y.toFloat())
                    }
                }
            }
        }
        return pts
    }

    /**
     * Maps normalized [0..1000] coordinates (either full-screen or inside a detected canvas box)
     * or raw pixel coordinates safely onto the physical Android display.
     */
    fun mapPointToScreen(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        normalizedSpace: Boolean,
        canvasBoundsNorm: IntArray? = null
    ): Pair<Float, Float> {
        val maxX = (screenWidth - 4).coerceAtLeast(10).toFloat()
        val maxY = (screenHeight - 4).coerceAtLeast(10).toFloat()

        if (canvasBoundsNorm != null && canvasBoundsNorm.size == 4) {
            val leftN = canvasBoundsNorm[0].coerceIn(0, 980)
            val topN = canvasBoundsNorm[1].coerceIn(0, 980)
            val rightN = canvasBoundsNorm[2].coerceIn(leftN + 20, 1000)
            val bottomN = canvasBoundsNorm[3].coerceIn(topN + 20, 1000)

            // Apply subtle 3% inner padding so strokes stay cleanly inside the visible white canvas
            val widthN = (rightN - leftN).toFloat()
            val heightN = (bottomN - topN).toFloat()
            val padX = widthN * 0.03f
            val padY = heightN * 0.03f

            val u = (x / 1000f).coerceIn(0f, 1f)
            val v = (y / 1000f).coerceIn(0f, 1f)

            val screenNormX = leftN + padX + u * (widthN - 2f * padX)
            val screenNormY = topN + padY + v * (heightN - 2f * padY)

            val px = (screenNormX / 1000f) * screenWidth
            val py = (screenNormY / 1000f) * screenHeight
            return px.coerceIn(4f, maxX) to py.coerceIn(4f, maxY)
        }

        if (normalizedSpace) {
            val px = (x.coerceIn(0f, 1000f) / 1000f) * screenWidth
            val py = (y.coerceIn(0f, 1000f) / 1000f) * screenHeight
            return px.coerceIn(4f, maxX) to py.coerceIn(4f, maxY)
        }

        return x.coerceIn(4f, maxX) to y.coerceIn(4f, maxY)
    }

    /**
     * Builds a smooth quadratic Bezier interpolated Android Path so freehand artwork
     * looks natural instead of angular line segments.
     */
    fun buildSmoothAndroidPath(screenPoints: List<Pair<Float, Float>>): Path {
        val path = Path()
        if (screenPoints.isEmpty()) return path
        val first = screenPoints.first()
        path.moveTo(first.first, first.second)

        if (screenPoints.size == 1) {
            // Subtle 1.5px micro-stroke ensures canvas web apps register a dot tap
            path.lineTo(first.first + 1.5f, first.second + 1.5f)
            return path
        }
        if (screenPoints.size == 2) {
            path.lineTo(screenPoints[1].first, screenPoints[1].second)
            return path
        }

        for (i in 1 until screenPoints.size - 1) {
            val curr = screenPoints[i]
            val next = screenPoints[i + 1]
            val midX = (curr.first + next.first) * 0.5f
            val midY = (curr.second + next.second) * 0.5f
            path.quadTo(curr.first, curr.second, midX, midY)
        }
        val last = screenPoints.last()
        path.lineTo(last.first, last.second)
        return path
    }

    /**
     * Calculates smooth gesture duration proportional to path length so web/native canvases
     * capture every interpolated touch sample cleanly without skipping segments.
     */
    fun computeRecommendedDurationMs(
        screenPoints: List<Pair<Float, Float>>,
        requestedMs: Long
    ): Long {
        if (screenPoints.size <= 1) return 110L
        var lengthPx = 0.0
        for (i in 1 until screenPoints.size) {
            lengthPx += hypot(
                (screenPoints[i].first - screenPoints[i - 1].first).toDouble(),
                (screenPoints[i].second - screenPoints[i - 1].second).toDouble()
            )
        }
        val autoMs = (240.0 + lengthPx * 1.15).roundToLong().coerceIn(260L, 2000L)
        return if (requestedMs in 140L..2500L) {
            maxOf(requestedMs, autoMs / 2L).coerceIn(220L, 2200L)
        } else {
            autoMs
        }
    }

    /**
     * Draws a crisp, non-intrusive 0..1000 coordinate ruler grid onto a COPY of the screenshot
     * bitmap sent to Gemini Vision (never shown to the user). Dramatically improves tap & canvas accuracy.
     */
    fun drawCoordinateGridOverlay(source: Bitmap): Bitmap {
        val annotated = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val w = annotated.width.toFloat()
        val h = annotated.height.toFloat()

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 0, 229, 255)
            strokeWidth = (w / 420f).coerceIn(1f, 2.2f)
            style = Paint.Style.STROKE
        }
        val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 229, 255)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(195, 8, 15, 30)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 240, 255)
            textSize = (w / 34f).coerceIn(15f, 26f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        // Subtle 50-unit edge tick marks along top & left margins for exact button targeting
        for (minor in 50..950 step 100) {
            val mx = w * (minor / 1000f)
            val my = h * (minor / 1000f)
            canvas.drawLine(mx, 0f, mx, 14f, minorTickPaint)
            canvas.drawLine(0f, my, 14f, my, minorTickPaint)
        }

        val steps = listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)
        for (s in steps) {
            val frac = s / 1000f
            val x = w * frac
            val y = h * frac
            val isMajor = s % 200 == 0
            linePaint.alpha = if (isMajor) 110 else 50
            canvas.drawLine(x, 0f, x, h, linePaint)
            canvas.drawLine(0f, y, w, y, linePaint)

            if (isMajor) {
                val label = s.toString()
                val tw = textPaint.measureText(label)
                val th = textPaint.textSize
                // Top & Bottom X ruler labels
                val tx = (x - tw / 2f).coerceIn(4f, w - tw - 4f)
                canvas.drawRect(tx - 3f, 2f, tx + tw + 3f, th + 6f, pillPaint)
                canvas.drawText(label, tx, th + 2f, textPaint)
                canvas.drawRect(tx - 3f, h - th - 6f, tx + tw + 3f, h - 2f, pillPaint)
                canvas.drawText(label, tx, h - 5f, textPaint)

                // Left & Right Y ruler labels
                val ty = (y + th / 2f).coerceIn(th + 10f, h - 10f)
                canvas.drawRect(2f, ty - th, tw + 8f, ty + 3f, pillPaint)
                canvas.drawText(label, 5f, ty, textPaint)
                canvas.drawRect(w - tw - 8f, ty - th, w - 2f, ty + 3f, pillPaint)
                canvas.drawText(label, w - tw - 5f, ty, textPaint)
            }
        }
        return annotated
    }
}

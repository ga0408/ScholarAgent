package com.jjlee.mynote.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class TimedPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long
)

/**
 * Detects scribble-to-erase gestures: rapid back-and-forth direction reversals
 * using extremum-anchor leg detection (immune to 240Hz/360Hz micro-deltas).
 */
object ScribbleEraserDetector {

    fun isScribble(
        points: List<TimedPoint>,
        minReversals: Int = 3,
        maxDurationMs: Long = 3500L,
        maxAspectCompactnessPx: Float = 1600f,
        minLegPx: Float = 14f
    ): Boolean {
        if (points.size < 6) return false
        val duration = points.last().timestampMs - points.first().timestampMs
        if (duration <= 0L || duration > maxDurationMs) return false

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var pathLen = 0f

        for (i in points.indices) {
            val pt = points[i]
            minX = min(minX, pt.x)
            maxX = max(maxX, pt.x)
            minY = min(minY, pt.y)
            maxY = max(maxY, pt.y)
            if (i > 0) {
                pathLen += hypot(pt.x - points[i - 1].x, pt.y - points[i - 1].y)
            }
        }
        val boxWidth = maxX - minX
        val boxHeight = maxY - minY
        val diagonal = hypot(boxWidth, boxHeight)
        if (diagonal < 18f) return false
        if (boxWidth > maxAspectCompactnessPx && boxHeight > maxAspectCompactnessPx) return false
        // A true back-and-forth scribble folds over its bounding box diagonal at least ~1.9x
        if (pathLen / diagonal < 1.85f) return false

        var xReversals = 0
        var yReversals = 0
        var prevDxSign = 0
        var prevDySign = 0
        var anchorX = points.first().x
        var anchorY = points.first().y

        for (i in 1 until points.size) {
            val pt = points[i]
            val dx = pt.x - anchorX
            if (abs(dx) >= minLegPx) {
                val sign = if (dx > 0f) 1 else -1
                if (prevDxSign != 0 && sign != prevDxSign) {
                    xReversals++
                }
                prevDxSign = sign
                anchorX = pt.x
            } else if ((prevDxSign > 0 && pt.x > anchorX) || (prevDxSign < 0 && pt.x < anchorX)) {
                anchorX = pt.x
            }

            val dy = pt.y - anchorY
            if (abs(dy) >= minLegPx) {
                val sign = if (dy > 0f) 1 else -1
                if (prevDySign != 0 && sign != prevDySign) {
                    yReversals++
                }
                prevDySign = sign
                anchorY = pt.y
            } else if ((prevDySign > 0 && pt.y > anchorY) || (prevDySign < 0 && pt.y < anchorY)) {
                anchorY = pt.y
            }
        }

        return max(xReversals, yReversals) >= minReversals
    }

    fun boundingBoxNormalized(
        points: List<Pair<Float, Float>>,
        padding: Float = 0.003f
    ): FloatArray {
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        for ((x, y) in points) {
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
        }
        return floatArrayOf(
            (minX - padding).coerceIn(0f, 1f),
            (minY - padding).coerceIn(0f, 1f),
            (maxX + padding).coerceIn(0f, 1f),
            (maxY + padding).coerceIn(0f, 1f)
        )
    }

    fun boxesIntersect(a: FloatArray, b: FloatArray): Boolean {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]
    }
}

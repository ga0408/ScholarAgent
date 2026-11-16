package com.jjlee.mynote.ink

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TimedPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long
)

/**
 * Detects scribble-to-erase gestures: rapid back-and-forth direction reversals
 * confined to a compact bounding box.
 */
object ScribbleEraserDetector {

    fun isScribble(
        points: List<TimedPoint>,
        minReversals: Int = 3,
        maxDurationMs: Long = 1600L,
        maxAspectCompactnessPx: Float = 420f
    ): Boolean {
        if (points.size < 6) return false
        val duration = points.last().timestampMs - points.first().timestampMs
        if (duration <= 0L || duration > maxDurationMs) return false

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (pt in points) {
            minX = min(minX, pt.x)
            maxX = max(maxX, pt.x)
            minY = min(minY, pt.y)
            maxY = max(maxY, pt.y)
        }
        val boxWidth = maxX - minX
        val boxHeight = maxY - minY
        if (boxWidth < 12f && boxHeight < 12f) return false
        if (boxWidth > maxAspectCompactnessPx && boxHeight > maxAspectCompactnessPx) return false

        var xReversals = 0
        var yReversals = 0
        var prevDxSign = 0
        var prevDySign = 0

        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y

            if (abs(dx) > 4f) {
                val sign = if (dx > 0f) 1 else -1
                if (prevDxSign != 0 && sign != prevDxSign) {
                    xReversals++
                }
                prevDxSign = sign
            }
            if (abs(dy) > 4f) {
                val sign = if (dy > 0f) 1 else -1
                if (prevDySign != 0 && sign != prevDySign) {
                    yReversals++
                }
                prevDySign = sign
            }
        }

        return max(xReversals, yReversals) >= minReversals
    }

    fun boundingBoxNormalized(
        points: List<Pair<Float, Float>>,
        padding: Float = 0.015f
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

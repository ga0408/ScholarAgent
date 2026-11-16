package com.jjlee.mynote.ink

import com.jjlee.mynote.data.db.StrokePoint
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Generates interpolated straight-line stroke points between start and end,
 * with gentle horizontal/vertical angle snapping for crisp underlining.
 */
object StraightLineHelper {

    fun buildStraightLinePoints(
        start: StrokePoint,
        end: StrokePoint,
        snapDegrees: Float = 3.5f,
        steps: Int = 16
    ): List<StrokePoint> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val angleDeg = Math.toDegrees(atan2(abs(dy).toDouble(), abs(dx).toDouble())).toFloat()

        val snappedEnd = when {
            angleDeg <= snapDegrees -> end.copy(y = start.y)
            abs(90f - angleDeg) <= snapDegrees -> end.copy(x = start.x)
            else -> end
        }

        val count = steps.coerceAtLeast(2)
        val avgPressure = ((start.pressure + snappedEnd.pressure) / 2f).coerceIn(0.1f, 1f)
        return buildList(count + 1) {
            for (i in 0..count) {
                val t = i.toFloat() / count.toFloat()
                add(
                    StrokePoint(
                        x = start.x + (snappedEnd.x - start.x) * t,
                        y = start.y + (snappedEnd.y - start.y) * t,
                        pressure = avgPressure,
                        tilt = snappedEnd.tilt,
                        orientation = snappedEnd.orientation
                    )
                )
            }
        }
    }
}

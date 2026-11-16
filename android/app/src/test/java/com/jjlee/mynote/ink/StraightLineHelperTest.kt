package com.jjlee.mynote.ink

import com.jjlee.mynote.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StraightLineHelperTest {

    @Test
    fun `snaps nearly horizontal line to exact horizontal`() {
        val start = StrokePoint(0.1f, 0.5f, pressure = 0.6f)
        val end = StrokePoint(0.8f, 0.51f, pressure = 0.8f) // ~0.8 deg
        val pts = StraightLineHelper.buildStraightLinePoints(start, end)

        assertTrue(pts.size >= 10)
        for (pt in pts) {
            assertEquals(0.5f, pt.y, 0.0001f)
        }
        assertEquals(0.1f, pts.first().x, 0.0001f)
        assertEquals(0.8f, pts.last().x, 0.0001f)
    }

    @Test
    fun `preserves diagonal line without snapping`() {
        val start = StrokePoint(0.1f, 0.1f, pressure = 0.5f)
        val end = StrokePoint(0.6f, 0.6f, pressure = 0.5f) // 45 deg
        val pts = StraightLineHelper.buildStraightLinePoints(start, end)

        assertEquals(0.6f, pts.last().x, 0.0001f)
        assertEquals(0.6f, pts.last().y, 0.0001f)
    }
}

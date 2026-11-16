package com.jjlee.mynote.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushConfigTest {

    @Test
    fun `highlighter uses multiply blend and clamped translucent alpha`() {
        val highlighter = BrushState(type = BrushType.HIGHLIGHTER, alpha = 0.35f)
        assertTrue(highlighter.usesMultiplyBlend())
        assertTrue(highlighter.effectiveAlpha() in 0.1f..0.85f)
    }

    @Test
    fun `translucent pen does not use multiply blend and preserves transparency`() {
        val translucent = BrushState(type = BrushType.TRANSLUCENT_PEN, alpha = 0.45f)
        assertFalse(translucent.usesMultiplyBlend())
        assertTrue(translucent.effectiveAlpha() in 0.15f..0.9f)
    }

    @Test
    fun `pressure increases pen stroke width`() {
        val pen = BrushState(type = BrushType.PEN, widthDp = 4f)
        val light = pen.computeStrokeWidthPx(density = 2f, pressure = 0.1f)
        val heavy = pen.computeStrokeWidthPx(density = 2f, pressure = 0.9f)
        assertTrue(heavy > light)
    }
}

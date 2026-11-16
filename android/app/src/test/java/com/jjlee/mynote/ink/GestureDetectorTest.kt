package com.jjlee.mynote.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureDetectorTest {

    @Test
    fun `detects rapid back and forth scribble as erase gesture`() {
        val points = listOf(
            TimedPoint(100f, 100f, 0L),
            TimedPoint(160f, 105f, 40L),
            TimedPoint(105f, 112f, 80L),
            TimedPoint(165f, 118f, 120L),
            TimedPoint(108f, 125f, 160L),
            TimedPoint(162f, 130f, 200L),
            TimedPoint(110f, 135f, 240L)
        )
        assertTrue(ScribbleEraserDetector.isScribble(points))
    }

    @Test
    fun `does not trigger scribble on normal left to right writing`() {
        val points = listOf(
            TimedPoint(100f, 100f, 0L),
            TimedPoint(130f, 102f, 50L),
            TimedPoint(160f, 99f, 100L),
            TimedPoint(190f, 103f, 150L),
            TimedPoint(220f, 101f, 200L),
            TimedPoint(250f, 104f, 250L),
            TimedPoint(280f, 100f, 300L)
        )
        assertFalse(ScribbleEraserDetector.isScribble(points))
    }

    @Test
    fun `bounding boxes intersect correctly`() {
        val a = ScribbleEraserDetector.boundingBoxNormalized(listOf(0.2f to 0.2f, 0.3f to 0.3f))
        val b = ScribbleEraserDetector.boundingBoxNormalized(listOf(0.28f to 0.28f, 0.4f to 0.4f))
        val c = ScribbleEraserDetector.boundingBoxNormalized(listOf(0.7f to 0.7f, 0.8f to 0.8f))

        assertTrue(ScribbleEraserDetector.boxesIntersect(a, b))
        assertFalse(ScribbleEraserDetector.boxesIntersect(a, c))
    }
}

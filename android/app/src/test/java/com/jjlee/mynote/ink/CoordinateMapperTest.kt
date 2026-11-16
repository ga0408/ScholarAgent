package com.jjlee.mynote.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun `round trip normalized to pixel and back preserves coordinates`() {
        val width = 1600f
        val height = 2400f
        val (nx, ny) = CoordinateMapper.toNormalized(400f, 1200f, width, height)
        assertEquals(0.25f, nx, 0.0001f)
        assertEquals(0.5f, ny, 0.0001f)

        val (px, py) = CoordinateMapper.toPixel(nx, ny, width, height)
        assertEquals(400f, px, 0.01f)
        assertEquals(1200f, py, 0.01f)
    }

    @Test
    fun `clamps out of bounds pixels to unit range`() {
        val (nx, ny) = CoordinateMapper.toNormalized(-50f, 3000f, 1000f, 2000f)
        assertEquals(0f, nx, 0.0001f)
        assertEquals(1f, ny, 0.0001f)
    }
}

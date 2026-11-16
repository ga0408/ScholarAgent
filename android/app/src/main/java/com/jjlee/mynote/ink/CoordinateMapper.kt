package com.jjlee.mynote.ink

/**
 * Converts between normalized [0f..1f] document coordinates and view/canvas pixel coordinates.
 */
object CoordinateMapper {

    fun toNormalized(px: Float, py: Float, width: Float, height: Float): Pair<Float, Float> {
        if (width <= 0f || height <= 0f) return 0f to 0f
        return (px / width).coerceIn(0f, 1f) to (py / height).coerceIn(0f, 1f)
    }

    fun toPixel(nx: Float, ny: Float, width: Float, height: Float): Pair<Float, Float> {
        return (nx * width) to (ny * height)
    }
}

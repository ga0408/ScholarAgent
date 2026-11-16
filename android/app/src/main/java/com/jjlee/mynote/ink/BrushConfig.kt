package com.jjlee.mynote.ink

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.abs

enum class BrushType {
    PEN,
    HIGHLIGHTER,
    TRANSLUCENT_PEN,
    FOUNTAIN_PEN,
    ERASER;

    companion object {
        fun fromName(name: String): BrushType =
            entries.firstOrNull { it.name == name } ?: PEN
    }
}

data class BrushState(
    val type: BrushType = BrushType.PEN,
    val colorArgb: Int = 0xFF1E293B.toInt(),
    val widthDp: Float = 3.5f,
    val alpha: Float = 1.0f
) {
    fun effectiveAlpha(): Float = when (type) {
        BrushType.HIGHLIGHTER -> alpha.coerceIn(0.1f, 0.85f)
        BrushType.TRANSLUCENT_PEN -> alpha.coerceIn(0.15f, 0.9f)
        BrushType.PEN, BrushType.FOUNTAIN_PEN -> alpha.coerceIn(0.2f, 1.0f)
        BrushType.ERASER -> 1.0f
    }

    fun usesMultiplyBlend(): Boolean = type == BrushType.HIGHLIGHTER

    fun computeStrokeWidthPx(
        density: Float,
        pressure: Float,
        tiltRad: Float = 0f
    ): Float {
        val basePx = widthDp * density
        val p = pressure.coerceIn(0.05f, 1.0f)
        return when (type) {
            BrushType.PEN -> basePx * (0.45f + 0.85f * p)
            BrushType.FOUNTAIN_PEN -> {
                val tiltFactor = 1f + (abs(tiltRad) / 1.57f).coerceIn(0f, 0.6f)
                basePx * (0.35f + 1.05f * p) * tiltFactor
            }
            BrushType.HIGHLIGHTER -> basePx * 3.8f
            BrushType.TRANSLUCENT_PEN -> basePx * (0.8f + 0.4f * p)
            BrushType.ERASER -> basePx * 4.0f
        }
    }

    fun toAndroidPaint(
        density: Float,
        pressure: Float = 0.5f,
        tiltRad: Float = 0f,
        forOverlay: Boolean = false
    ): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = if (type == BrushType.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colorArgb
            alpha = (effectiveAlpha() * 255).toInt().coerceIn(0, 255)
            strokeWidth = computeStrokeWidthPx(density, pressure, tiltRad)
            // PorterDuff.Mode.MULTIPLY against a transparent View layer produces 0 (invisible).
            // Only apply MULTIPLY when drawing directly onto an opaque PDF page canvas.
            if (!forOverlay && usesMultiplyBlend()) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        }
    }
}

object BrushPresets {
    val defaultColors = listOf(
        Color.parseColor("#1E293B"), // Ink Slate
        Color.parseColor("#DC2626"), // Red
        Color.parseColor("#2563EB"), // Blue
        Color.parseColor("#16A34A"), // Green
        Color.parseColor("#FACC15"), // Highlighter Yellow
        Color.parseColor("#FB7185"), // Highlighter Pink
        Color.parseColor("#38BDF8"), // Highlighter Cyan
        Color.parseColor("#A855F7")  // Purple
    )
}

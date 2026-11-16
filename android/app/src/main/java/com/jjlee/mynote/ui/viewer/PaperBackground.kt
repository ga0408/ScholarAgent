package com.jjlee.mynote.ui.viewer

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

enum class PaperStyle(val label: String) {
    BLANK("무지"),
    LINED("줄노트"),
    GRID("모눈")
}

@Composable
fun PaperBackground(
    style: PaperStyle,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = Color.White)
        val lineColor = Color(0xFFCBD5E1)
        val spacingPx = 56f

        when (style) {
            PaperStyle.BLANK -> Unit
            PaperStyle.LINED -> {
                var y = spacingPx * 1.5f
                while (y < size.height) {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.5f
                    )
                    y += spacingPx
                }
            }
            PaperStyle.GRID -> {
                var x = spacingPx
                while (x < size.width) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.2f
                    )
                    x += spacingPx
                }
                var y = spacingPx
                while (y < size.height) {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.2f
                    )
                    y += spacingPx
                }
            }
        }
    }
}

fun drawAndroidPaperBackground(
    canvas: AndroidCanvas,
    width: Float,
    height: Float,
    style: PaperStyle
) {
    canvas.drawColor(AndroidColor.WHITE)
    if (style == PaperStyle.BLANK) return

    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(203, 213, 225)
        strokeWidth = 1f
    }
    val spacing = 28f
    when (style) {
        PaperStyle.BLANK -> Unit
        PaperStyle.LINED -> {
            var y = spacing * 1.5f
            while (y < height) {
                canvas.drawLine(0f, y, width, y, paint)
                y += spacing
            }
        }
        PaperStyle.GRID -> {
            var x = spacing
            while (x < width) {
                canvas.drawLine(x, 0f, x, height, paint)
                x += spacing
            }
            var y = spacing
            while (y < height) {
                canvas.drawLine(0f, y, width, y, paint)
                y += spacing
            }
        }
    }
}

package com.jjlee.mynote.ink

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.jjlee.mynote.data.db.StrokeEntity
import com.jjlee.mynote.data.db.StrokePoint

/**
 * Shared stroke rendering helper used by both the live StylusCanvasView and PdfExporter
 * to ensure identical appearance (including Multiply highlighter blending).
 */
object StrokeRenderer {

    fun drawStrokes(
        canvas: Canvas,
        strokes: List<StrokeEntity>,
        width: Float,
        height: Float,
        density: Float,
        forOverlay: Boolean = true
    ) {
        val path = Path()
        for (stroke in strokes) {
            val pts = StrokePoint.decodeList(stroke.pointsJson)
            if (pts.isEmpty()) continue
            val state = BrushState(
                type = BrushType.fromName(stroke.brushType),
                colorArgb = stroke.colorArgb,
                widthDp = stroke.baseWidthDp,
                alpha = stroke.alpha
            )
            drawSingleStroke(canvas, state, pts, width, height, density, path, forOverlay)
        }
    }

    fun drawSingleStroke(
        canvas: Canvas,
        state: BrushState,
        pts: List<StrokePoint>,
        width: Float,
        height: Float,
        density: Float,
        reusablePath: Path = Path(),
        forOverlay: Boolean = true
    ) {
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            val (px, py) = CoordinateMapper.toPixel(pts[0].x, pts[0].y, width, height)
            val paint = state.toAndroidPaint(density, pts[0].pressure, pts[0].tilt, forOverlay).apply {
                style = Paint.Style.FILL
            }
            canvas.drawCircle(px, py, paint.strokeWidth / 2f, paint)
            return
        }

        val paint = state.toAndroidPaint(density, pts[0].pressure, pts[0].tilt, forOverlay)
        if (state.type == BrushType.HIGHLIGHTER || state.type == BrushType.TRANSLUCENT_PEN) {
            reusablePath.reset()
            val (x0, y0) = CoordinateMapper.toPixel(pts[0].x, pts[0].y, width, height)
            reusablePath.moveTo(x0, y0)
            for (i in 1 until pts.size) {
                val (prevX, prevY) = CoordinateMapper.toPixel(pts[i - 1].x, pts[i - 1].y, width, height)
                val (curX, curY) = CoordinateMapper.toPixel(pts[i].x, pts[i].y, width, height)
                reusablePath.quadTo(prevX, prevY, (prevX + curX) / 2f, (prevY + curY) / 2f)
            }
            val (lx, ly) = CoordinateMapper.toPixel(pts.last().x, pts.last().y, width, height)
            reusablePath.lineTo(lx, ly)
            canvas.drawPath(reusablePath, paint)
        } else {
            for (i in 1 until pts.size) {
                val (x1, y1) = CoordinateMapper.toPixel(pts[i - 1].x, pts[i - 1].y, width, height)
                val (x2, y2) = CoordinateMapper.toPixel(pts[i].x, pts[i].y, width, height)
                paint.strokeWidth = state.computeStrokeWidthPx(
                    density = density,
                    pressure = (pts[i - 1].pressure + pts[i].pressure) / 2f,
                    tiltRad = pts[i].tilt
                )
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
        }
    }
}

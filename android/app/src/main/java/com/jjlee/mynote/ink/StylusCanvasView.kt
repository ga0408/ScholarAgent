package com.jjlee.mynote.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.jjlee.mynote.data.db.StrokeEntity
import com.jjlee.mynote.data.db.StrokePoint
import kotlin.math.hypot

/**
 * Low-latency stylus-only drawing surface:
 * - Hardware palm rejection: ignores TOOL_TYPE_FINGER for ink input
 * - Captures MotionEvent historical samples for 144Hz/360Hz smoothness
 * - Supports pressure, tilt, orientation, barrel-button erase, and scribble-to-erase
 * - Multi-touch tap shortcuts: 2-finger tap = Undo, 3-finger tap = Redo
 */
class StylusCanvasView(context: Context) : View(context) {

    var brushState: BrushState = BrushState()
    var strokesVisible: Boolean = true
    var straightLineMode: Boolean = false
    var gestureOnlyMode: Boolean = false
    var gestureMappings: Map<GestureTrigger, GestureAction> = DefaultGestureMappings.defaults
    var onStrokeCompleted: ((BrushState, List<StrokePoint>) -> Unit)? = null
    var onEraseIdsRequested: ((List<Long>) -> Unit)? = null
    var onEraseAtNormalizedPoint: ((Float, Float) -> Unit)? = null
    var onEraseInNormalizedBox: ((Float, Float, Float, Float) -> Unit)? = null
    var onGestureTriggered: ((GestureTrigger) -> Unit)? = null
    var onTwoFingerTapUndo: (() -> Unit)? = null
    var onThreeFingerTapRedo: (() -> Unit)? = null

    private data class CachedStroke(
        val id: Long,
        val brush: BrushState,
        val points: List<StrokePoint>
    )

    private data class PendingStroke(
        val brush: BrushState,
        val points: List<StrokePoint>
    )

    private var committedStrokes: List<StrokeEntity> = emptyList()
    private var cachedCommittedStrokes: List<CachedStroke> = emptyList()
    private val decodedPointsCache = HashMap<String, List<StrokePoint>>()
    private var committedBitmap: Bitmap? = null
    private var committedCanvas: Canvas? = null
    private var committedBitmapDirty = true
    private var renderedCommittedCount = 0
    private val pendingStrokes = mutableListOf<PendingStroke>()
    private val activePoints = mutableListOf<StrokePoint>()
    private val activeTimedPoints = mutableListOf<TimedPoint>()
    private var straightStartPoint: StrokePoint? = null
    private var activeIsErasing = false
    private var activeIsScribbling = false
    private var lastHoverButtonState = 0
    private var lastBarrelPressTimeMs = 0L
    private var hoverPrimaryPressDownMs = 0L

    // Multi-touch tap & swipe tracking
    private var maxPointerCountInGesture = 0
    private var fingerDownTimeMs = 0L
    private var twoFingerStartY = 0f
    private var twoFingerStartSpan = 0f
    private var twoFingerSwipeHandled = false
    private var threeFingerStartY = 0f
    private var threeFingerSwipeHandled = false
    private var secondaryButtonHandled = false

    private val density = resources.displayMetrics.density
    private val reusablePath = Path()
    private val eraserGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = android.graphics.Color.argb(120, 239, 68, 68)
        strokeCap = Paint.Cap.ROUND
    }

    init {
        setLayerType(LAYER_TYPE_NONE, null)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            val btn = event.buttonState
            val primaryNow = (btn and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            val primaryPrev = (lastHoverButtonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            val secondaryNow = (btn and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
            val secondaryPrev = (lastHoverButtonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
            lastHoverButtonState = btn

            val now = System.currentTimeMillis()
            if (secondaryNow && !secondaryPrev) {
                onGestureTriggered?.invoke(GestureTrigger.BARREL_SECONDARY_PRESS)
                return true
            }
            if (primaryNow && !primaryPrev) {
                if (now - lastBarrelPressTimeMs in 35L..500L) {
                    lastBarrelPressTimeMs = 0L
                    hoverPrimaryPressDownMs = 0L
                    onGestureTriggered?.invoke(GestureTrigger.PEN_DOUBLE_TAP)
                    return true
                }
                lastBarrelPressTimeMs = now
                hoverPrimaryPressDownMs = now
                return true
            }
            if (!primaryNow && primaryPrev && hoverPrimaryPressDownMs > 0L) {
                val heldMs = now - hoverPrimaryPressDownMs
                hoverPrimaryPressDownMs = 0L
                // Quick hover click/double-tap on Lenovo pen sensor without touching glass
                if (heldMs in 10L..340L && activePoints.isEmpty() && !activeIsErasing) {
                    onGestureTriggered?.invoke(GestureTrigger.PEN_DOUBLE_TAP)
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL
        ) {
            onGestureTriggered?.invoke(GestureTrigger.PEN_DOUBLE_TAP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun setStrokes(strokes: List<StrokeEntity>) {
        if (strokes === committedStrokes) return
        val prevCached = cachedCommittedStrokes
        committedStrokes = strokes
        cachedCommittedStrokes = strokes.mapNotNull { entity ->
            val pts = decodedPointsCache.getOrPut(entity.pointsJson) {
                StrokePoint.decodeList(entity.pointsJson)
            }
            if (pts.isEmpty()) null
            else CachedStroke(
                id = entity.id,
                brush = BrushState(
                    type = BrushType.fromName(entity.brushType),
                    colorArgb = entity.colorArgb,
                    widthDp = entity.baseWidthDp,
                    alpha = entity.alpha
                ),
                points = pts
            )
        }
        if (decodedPointsCache.size > strokes.size * 2 + 64) {
            val activeKeys = strokes.mapTo(HashSet(strokes.size)) { it.pointsJson }
            decodedPointsCache.keys.retainAll(activeKeys)
        }

        // Check if new list is identical or strictly an append onto the already-rendered prefix
        val isIdentical = !committedBitmapDirty &&
            committedBitmap != null &&
            cachedCommittedStrokes.size == prevCached.size &&
            renderedCommittedCount == prevCached.size &&
            prevCached.indices.all { i ->
                prevCached[i].points === cachedCommittedStrokes[i].points
            }
        if (isIdentical) {
            if (pendingStrokes.isNotEmpty()) {
                pendingStrokes.clear()
                invalidate()
            }
            return
        }

        val isAppendOnly = !committedBitmapDirty &&
            committedBitmap != null &&
            cachedCommittedStrokes.size >= prevCached.size &&
            renderedCommittedCount == prevCached.size &&
            prevCached.indices.all { i ->
                prevCached[i].points === cachedCommittedStrokes[i].points
            }

        if (isAppendOnly) {
            val c = committedCanvas
            val bmp = committedBitmap
            if (c != null && bmp != null && bmp.width > 0 && bmp.height > 0) {
                val w = bmp.width.toFloat()
                val h = bmp.height.toFloat()
                for (i in renderedCommittedCount until cachedCommittedStrokes.size) {
                    val s = cachedCommittedStrokes[i]
                    StrokeRenderer.drawSingleStroke(c, s.brush, s.points, w, h, density, reusablePath)
                }
                renderedCommittedCount = cachedCommittedStrokes.size
            }
        } else {
            committedBitmapDirty = true
            renderedCommittedCount = 0
        }

        // Drop any pending strokes that are now present in committedStrokes
        if (pendingStrokes.isNotEmpty()) {
            pendingStrokes.removeAll { pending ->
                val pFirst = pending.points.firstOrNull()
                val pLast = pending.points.lastOrNull()
                cachedCommittedStrokes.any { committed ->
                    committed.points.size == pending.points.size &&
                        committed.points.firstOrNull() == pFirst &&
                        committed.points.lastOrNull() == pLast
                }
            }
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            committedBitmap?.recycle()
            committedBitmap = null
            committedCanvas = null
            committedBitmapDirty = true
            renderedCommittedCount = 0
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        committedBitmap?.recycle()
        committedBitmap = null
        committedCanvas = null
        committedBitmapDirty = true
        renderedCommittedCount = 0
    }

    private fun ensureCommittedBitmap(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        var bmp = committedBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            committedBitmap = bmp
            committedCanvas = Canvas(bmp)
            committedBitmapDirty = true
        }
        if (committedBitmapDirty) {
            val c = committedCanvas ?: return
            c.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val wf = w.toFloat()
            val hf = h.toFloat()
            for (stroke in cachedCommittedStrokes) {
                StrokeRenderer.drawSingleStroke(
                    c, stroke.brush, stroke.points, wf, hf, density, reusablePath
                )
            }
            renderedCommittedCount = cachedCommittedStrokes.size
            committedBitmapDirty = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Finger input is handled by Compose pointerInput in PdfNoteScreen
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            return false
        }

        if (gestureOnlyMode) {
            return false
        }

        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            return false
        }

        val primaryHeld = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val secondaryHeld = (event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
        val primaryAction = gestureMappings[GestureTrigger.BARREL_PRIMARY_HOLD] ?: GestureAction.ERASE

        val barrelErase = (primaryHeld && primaryAction == GestureAction.ERASE) ||
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
            brushState.type == BrushType.ERASER

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
                activePoints.clear()
                activeTimedPoints.clear()
                straightStartPoint = null
                secondaryButtonHandled = false
                activeIsScribbling = false
                hoverPrimaryPressDownMs = 0L

                if (secondaryHeld) {
                    secondaryButtonHandled = true
                    onGestureTriggered?.invoke(GestureTrigger.BARREL_SECONDARY_PRESS)
                    return true
                }
                if (primaryHeld) {
                    val now = System.currentTimeMillis()
                    if (now - lastBarrelPressTimeMs in 35L..500L) {
                        lastBarrelPressTimeMs = 0L
                        secondaryButtonHandled = true
                        onGestureTriggered?.invoke(GestureTrigger.PEN_DOUBLE_TAP)
                        return true
                    }
                    lastBarrelPressTimeMs = now
                    if (primaryAction != GestureAction.ERASE) {
                        onGestureTriggered?.invoke(GestureTrigger.BARREL_PRIMARY_HOLD)
                        return true
                    }
                }

                activeIsErasing = barrelErase
                appendSamples(event)
                if (straightLineMode && !activeIsErasing) {
                    straightStartPoint = activePoints.firstOrNull()
                }
                if (activeIsErasing) {
                    eraseAtCurrentPoints()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (secondaryHeld && !secondaryButtonHandled) {
                    secondaryButtonHandled = true
                    onGestureTriggered?.invoke(GestureTrigger.BARREL_SECONDARY_PRESS)
                }
                if (barrelErase) activeIsErasing = true
                if (straightLineMode && !activeIsErasing && straightStartPoint != null) {
                    updateStraightLinePreview(event)
                } else {
                    appendSamples(event)
                }
                if (activeIsErasing) {
                    eraseAtCurrentPoints()
                } else if (!straightLineMode && !activeIsScribbling && activeTimedPoints.size >= 10) {
                    if (ScribbleEraserDetector.isScribble(activeTimedPoints)) {
                        activeIsScribbling = true
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (secondaryButtonHandled) {
                    secondaryButtonHandled = false
                    activePoints.clear()
                    activeTimedPoints.clear()
                    activeIsScribbling = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (straightLineMode && !activeIsErasing && straightStartPoint != null) {
                    updateStraightLinePreview(event)
                } else {
                    appendSamples(event)
                }
                if (activeIsErasing) {
                    eraseAtCurrentPoints()
                } else if (activePoints.isNotEmpty()) {
                    if (!straightLineMode && (activeIsScribbling || ScribbleEraserDetector.isScribble(activeTimedPoints))) {
                        val scribbleAction = gestureMappings[GestureTrigger.SCRIBBLE] ?: GestureAction.ERASE
                        if (scribbleAction == GestureAction.ERASE) {
                            eraseByScribbleBox()
                        } else {
                            onGestureTriggered?.invoke(GestureTrigger.SCRIBBLE)
                        }
                    } else {
                        val ptsSnapshot = activePoints.toList()
                        pendingStrokes.add(PendingStroke(brushState, ptsSnapshot))
                        onStrokeCompleted?.invoke(brushState, ptsSnapshot)
                    }
                }
                activePoints.clear()
                activeTimedPoints.clear()
                straightStartPoint = null
                activeIsErasing = false
                activeIsScribbling = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleFingerGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                maxPointerCountInGesture = 1
                fingerDownTimeMs = event.eventTime
                twoFingerSwipeHandled = false
                threeFingerSwipeHandled = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointerCountInGesture = maxOf(maxPointerCountInGesture, event.pointerCount)
                if (event.pointerCount == 2) {
                    twoFingerStartY = averagePointerY(event)
                    twoFingerStartSpan = pointerSpan(event)
                } else if (event.pointerCount == 3) {
                    threeFingerStartY = averagePointerY(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && !twoFingerSwipeHandled) {
                    val dy = averagePointerY(event) - twoFingerStartY
                    val spanDelta = kotlin.math.abs(pointerSpan(event) - twoFingerStartSpan)
                    if (dy < -75f * density && spanDelta < 65f * density) {
                        twoFingerSwipeHandled = true
                        onGestureTriggered?.invoke(GestureTrigger.TWO_FINGER_SWIPE_UP)
                    }
                } else if (event.pointerCount == 3 && !threeFingerSwipeHandled) {
                    val dy = averagePointerY(event) - threeFingerStartY
                    if (dy < -80f * density) {
                        threeFingerSwipeHandled = true
                        onGestureTriggered?.invoke(GestureTrigger.THREE_FINGER_SWIPE_UP)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - fingerDownTimeMs
                if (!twoFingerSwipeHandled && !threeFingerSwipeHandled && duration < 280L) {
                    when (maxPointerCountInGesture) {
                        2 -> {
                            onGestureTriggered?.invoke(GestureTrigger.TWO_FINGER_TAP)
                            onTwoFingerTapUndo?.invoke()
                        }
                        3 -> {
                            onGestureTriggered?.invoke(GestureTrigger.THREE_FINGER_TAP)
                            onThreeFingerTapRedo?.invoke()
                        }
                    }
                }
                maxPointerCountInGesture = 0
                twoFingerSwipeHandled = false
                threeFingerSwipeHandled = false
            }
        }
    }

    private fun pointerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun averagePointerY(event: MotionEvent): Float {
        if (event.pointerCount == 0) return 0f
        var sum = 0f
        for (i in 0 until event.pointerCount) {
            sum += event.getY(i)
        }
        return sum / event.pointerCount
    }

    private fun updateStraightLinePreview(event: MotionEvent) {
        val start = straightStartPoint ?: return
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val (nx, ny) = CoordinateMapper.toNormalized(event.x, event.y, w, h)
        val end = StrokePoint(
            x = nx,
            y = ny,
            pressure = event.pressure,
            tilt = event.getAxisValue(MotionEvent.AXIS_TILT),
            orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)
        )
        activePoints.clear()
        activePoints.addAll(StraightLineHelper.buildStraightLinePoints(start, end))
    }

    private fun appendSamples(event: MotionEvent) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
        val orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION)

        for (i in 0 until event.historySize) {
            val hx = event.getHistoricalX(i)
            val hy = event.getHistoricalY(i)
            val hp = event.getHistoricalPressure(i)
            val (nx, ny) = CoordinateMapper.toNormalized(hx, hy, w, h)
            activePoints.add(StrokePoint(nx, ny, hp, tilt, orientation))
            activeTimedPoints.add(TimedPoint(hx, hy, event.getHistoricalEventTime(i)))
        }

        val cx = event.x
        val cy = event.y
        val cp = event.pressure
        val (nx, ny) = CoordinateMapper.toNormalized(cx, cy, w, h)
        activePoints.add(StrokePoint(nx, ny, cp, tilt, orientation))
        activeTimedPoints.add(TimedPoint(cx, cy, event.eventTime))
    }

    private fun eraseAtCurrentPoints() {
        val last = activePoints.lastOrNull() ?: return
        onEraseAtNormalizedPoint?.invoke(last.x, last.y)
        val hitRadiusNorm = 0.025f
        val hitIds = cachedCommittedStrokes.filter { stroke ->
            stroke.points.any { pt -> hypot(pt.x - last.x, pt.y - last.y) <= hitRadiusNorm }
        }.map { it.id }

        if (hitIds.isNotEmpty()) {
            onEraseIdsRequested?.invoke(hitIds)
        }
    }

    private fun eraseByScribbleBox() {
        val scribbleBox = ScribbleEraserDetector.boundingBoxNormalized(
            activePoints.map { it.x to it.y },
            padding = 0.003f
        )
        onEraseInNormalizedBox?.invoke(scribbleBox[0], scribbleBox[1], scribbleBox[2], scribbleBox[3])
        val hitDistNorm = 0.017f
        val scribbleStep = maxOf(1, activePoints.size / 36)
        val sampledScribble = activePoints.filterIndexed { idx, _ -> idx % scribbleStep == 0 }

        val hitIds = cachedCommittedStrokes.filter { stroke ->
            val pts = stroke.points
            if (pts.isEmpty()) return@filter false
            // Require actual points of the stroke to lie inside the scribble box AND near scribble points
            var closeHits = 0
            val minRequiredHits = if (pts.size <= 3) 1 else 2
            for (pt in pts) {
                if (pt.x in scribbleBox[0]..scribbleBox[2] && pt.y in scribbleBox[1]..scribbleBox[3]) {
                    if (sampledScribble.any { sp -> hypot(pt.x - sp.x, pt.y - sp.y) <= hitDistNorm }) {
                        closeHits++
                        if (closeHits >= minRequiredHits) return@filter true
                    }
                }
            }
            false
        }.map { it.id }

        if (hitIds.isNotEmpty()) {
            onEraseIdsRequested?.invoke(hitIds)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!strokesVisible) return

        val iw = width
        val ih = height
        val w = iw.toFloat()
        val h = ih.toFloat()
        if (w <= 0f || h <= 0f) return

        ensureCommittedBitmap(iw, ih)
        committedBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        for (pending in pendingStrokes) {
            StrokeRenderer.drawSingleStroke(
                canvas, pending.brush, pending.points, w, h, density, reusablePath
            )
        }

        if (activePoints.isNotEmpty()) {
            if (activeIsErasing || activeIsScribbling) {
                drawGuidePath(canvas, activePoints, w, h)
            } else {
                StrokeRenderer.drawSingleStroke(
                    canvas, brushState, activePoints, w, h, density, reusablePath
                )
            }
        }
    }

    private fun drawGuidePath(canvas: Canvas, pts: List<StrokePoint>, w: Float, h: Float) {
        if (pts.size < 2) return
        reusablePath.reset()
        val (sx, sy) = CoordinateMapper.toPixel(pts.first().x, pts.first().y, w, h)
        reusablePath.moveTo(sx, sy)
        for (i in 1 until pts.size) {
            val (px, py) = CoordinateMapper.toPixel(pts[i].x, pts[i].y, w, h)
            reusablePath.lineTo(px, py)
        }
        canvas.drawPath(reusablePath, eraserGuidePaint)
    }
}

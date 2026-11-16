package com.jjlee.mynote.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
    private val pendingStrokes = mutableListOf<PendingStroke>()
    private val activePoints = mutableListOf<StrokePoint>()
    private val activeTimedPoints = mutableListOf<TimedPoint>()
    private var straightStartPoint: StrokePoint? = null
    private var activeIsErasing = false

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
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setStrokes(strokes: List<StrokeEntity>) {
        if (strokes === committedStrokes) return
        committedStrokes = strokes
        cachedCommittedStrokes = strokes.mapNotNull { entity ->
            val pts = StrokePoint.decodeList(entity.pointsJson)
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)

        // Finger input: detect 2/3-finger tap and 3-finger swipe up even in gestureOnlyMode
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            handleFingerGesture(event)
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
                parent?.requestDisallowInterceptTouchEvent(true)
                activePoints.clear()
                activeTimedPoints.clear()
                straightStartPoint = null
                secondaryButtonHandled = false

                if (secondaryHeld) {
                    secondaryButtonHandled = true
                    onGestureTriggered?.invoke(GestureTrigger.BARREL_SECONDARY_PRESS)
                    return true
                }
                if (primaryHeld && primaryAction != GestureAction.ERASE) {
                    onGestureTriggered?.invoke(GestureTrigger.BARREL_PRIMARY_HOLD)
                    return true
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
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (secondaryButtonHandled) {
                    secondaryButtonHandled = false
                    activePoints.clear()
                    activeTimedPoints.clear()
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
                    if (!straightLineMode && ScribbleEraserDetector.isScribble(activeTimedPoints)) {
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
        val hitRadiusNorm = 0.025f
        val hitIds = committedStrokes.filter { stroke ->
            val pts = StrokePoint.decodeList(stroke.pointsJson)
            pts.any { pt -> hypot(pt.x - last.x, pt.y - last.y) <= hitRadiusNorm }
        }.map { it.id }

        if (hitIds.isNotEmpty()) {
            onEraseIdsRequested?.invoke(hitIds)
        }
    }

    private fun eraseByScribbleBox() {
        val scribbleBox = ScribbleEraserDetector.boundingBoxNormalized(
            activePoints.map { it.x to it.y }
        )
        val hitIds = committedStrokes.filter { stroke ->
            val pts = StrokePoint.decodeList(stroke.pointsJson)
            if (pts.isEmpty()) return@filter false
            val strokeBox = ScribbleEraserDetector.boundingBoxNormalized(
                pts.map { it.x to it.y },
                padding = 0.005f
            )
            ScribbleEraserDetector.boxesIntersect(scribbleBox, strokeBox)
        }.map { it.id }

        if (hitIds.isNotEmpty()) {
            onEraseIdsRequested?.invoke(hitIds)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!strokesVisible) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        for (stroke in cachedCommittedStrokes) {
            StrokeRenderer.drawSingleStroke(
                canvas, stroke.brush, stroke.points, w, h, density, reusablePath
            )
        }

        for (pending in pendingStrokes) {
            StrokeRenderer.drawSingleStroke(
                canvas, pending.brush, pending.points, w, h, density, reusablePath
            )
        }

        if (activePoints.isNotEmpty()) {
            if (activeIsErasing) {
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

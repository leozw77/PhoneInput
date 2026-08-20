package com.phoneinputenhanced.nativeclient

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

class TouchpadView(
    context: Context,
    private val listener: Listener,
) : View(context) {
    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onScroll(x: Int, y: Int)
        fun onLeftClick()
        fun onRightClick()
        fun onLeftButton(down: Boolean)
        fun onOpenTextInput()
        fun onDragLockChanged(enabled: Boolean)
    }

    private var config = GestureConfig()
    private var hapticEnabled = true
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 39, 47) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(69, 74, 86)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(156, 162, 178)
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val radius = dp(18f)
    private var hintLine1 = "轻触左键 · 双指轻触右键"
    private var hintLine2 = "双指滚动 · 双指长按约 0.5 秒输入"

    private var suppressedUntilAllUp = false
    private var twoPointerId1 = MotionEvent.INVALID_POINTER_ID
    private var twoPointerId2 = MotionEvent.INVALID_POINTER_ID

    private val holdRunnable = Runnable {
        engine.onTwoFingerHoldTimeout(SystemClock.uptimeMillis())
    }

    private val motionBatcher = MotionFrameBatcher(
        scheduleFrame = { action -> postOnAnimation(action) },
        moveSink = { dx, dy -> listener.onMove(dx, dy) },
        scrollSink = { x, y -> listener.onScroll(x, y) },
    )

    private val engine = NativeGestureEngine(config) { output ->
        when (output) {
            is NativeGestureEngine.Output.MouseMove -> motionBatcher.addMove(output.dx, output.dy)
            is NativeGestureEngine.Output.Scroll -> motionBatcher.addScroll(output.x, output.y)
            NativeGestureEngine.Output.LeftClick -> {
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                listener.onLeftClick()
            }
            NativeGestureEngine.Output.RightClick -> {
                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                listener.onRightClick()
            }
            NativeGestureEngine.Output.LeftDown -> {
                motionBatcher.flushNow()
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                listener.onLeftButton(true)
            }
            NativeGestureEngine.Output.LeftUp -> {
                // The final drag delta must reach Windows before LEFT_UP.
                motionBatcher.flushNow()
                listener.onLeftButton(false)
            }
            NativeGestureEngine.Output.OpenTextInput -> {
                haptic(HapticFeedbackConstants.LONG_PRESS)
                listener.onOpenTextInput()
            }
            is NativeGestureEngine.Output.DragLockChanged -> {
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                listener.onDragLockChanged(output.enabled)
            }
            is NativeGestureEngine.Output.Hint -> {
                hintLine1 = output.text
                hintLine2 = when {
                    output.text.contains("三指") -> "当前版本仅处理单指和双指"
                    output.text.contains("输入框") -> "松手不会再触发右键"
                    output.text.contains("双指滚动") -> "上下 / 左右均可滚动"
                    output.text.contains("右键") -> "双指轻触采用 Windows 触控板习惯"
                    output.text.contains("锁定") -> "拖动锁定可用下方按钮随时释放"
                    output.text.contains("拖动") -> "松手结束拖动；Chrome 标签不会提前误拖"
                    else -> "Native 1.4.0"
                }
                invalidate()
            }
        }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = RectF(1f, 1f, width - 1f, height - 1f)
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        canvas.drawText(hintLine1, width / 2f, height / 2f - dp(5f), hintPaint)
        canvas.drawText(hintLine2, width / 2f, height / 2f + dp(20f), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                suppressedUntilAllUp = false
                clearTwoPointerTracking()
                removeCallbacks(holdRunnable)
                engine.onSingleDown(event.x, event.y, event.eventTime)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && !suppressedUntilAllUp) {
                    removeCallbacks(holdRunnable)
                    twoPointerId1 = event.getPointerId(0)
                    twoPointerId2 = event.getPointerId(1)
                    engine.onTwoFingerDown(
                        event.getX(0), event.getY(0),
                        event.getX(1), event.getY(1),
                        event.eventTime,
                    )
                    postDelayed(holdRunnable, engine.twoFingerHoldDelayMs())
                } else {
                    removeCallbacks(holdRunnable)
                    suppressedUntilAllUp = true
                    clearTwoPointerTracking()
                    engine.suppressForTooManyPointers()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (suppressedUntilAllUp) return true
                if (twoPointerId1 != MotionEvent.INVALID_POINTER_ID && twoPointerId2 != MotionEvent.INVALID_POINTER_ID) {
                    val index1 = event.findPointerIndex(twoPointerId1)
                    val index2 = event.findPointerIndex(twoPointerId2)
                    if (index1 < 0 || index2 < 0) return true

                    for (h in 0 until event.historySize) {
                        engine.onTwoFingerMove(
                            event.getHistoricalX(index1, h), event.getHistoricalY(index1, h),
                            event.getHistoricalX(index2, h), event.getHistoricalY(index2, h),
                            event.getHistoricalEventTime(h),
                        )
                    }
                    engine.onTwoFingerMove(
                        event.getX(index1), event.getY(index1),
                        event.getX(index2), event.getY(index2),
                        event.eventTime,
                    )
                    if (engine.state == NativeGestureEngine.State.TwoFingerScroll) removeCallbacks(holdRunnable)
                    return true
                }

                if (event.pointerCount != 1) return true
                for (i in 0 until event.historySize) {
                    engine.onSingleMove(
                        event.getHistoricalX(0, i),
                        event.getHistoricalY(0, i),
                        event.getHistoricalEventTime(i),
                    )
                }
                engine.onSingleMove(event.x, event.y, event.eventTime)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upId = event.getPointerId(event.actionIndex)
                if (upId == twoPointerId1 || upId == twoPointerId2) {
                    removeCallbacks(holdRunnable)
                    engine.onTwoFingerUp(event.eventTime)
                    motionBatcher.flushNow()
                    clearTwoPointerTracking()
                    // One physical finger is still on the panel. Ignore it until all fingers leave,
                    // otherwise a two-finger right-click/hold could immediately become a left click.
                    suppressedUntilAllUp = true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                if (suppressedUntilAllUp) {
                    suppressedUntilAllUp = false
                    clearTwoPointerTracking()
                    engine.endSuppression()
                } else {
                    engine.onSingleUp(event.x, event.y, event.eventTime)
                    motionBatcher.flushNow()
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                suppressedUntilAllUp = false
                clearTwoPointerTracking()
                motionBatcher.cancel()
                engine.cancelCurrentPointer()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }


    fun updateSettings(settings: AppSettings) {
        config = settings.toGestureConfig(config)
        hapticEnabled = settings.hapticFeedback
        engine.updateConfig(config)
    }

    private fun haptic(constant: Int) {
        if (hapticEnabled) performHapticFeedback(constant)
    }

    fun toggleDragLock(): Boolean = engine.toggleDragLock()

    fun clearDragLockLocally() {
        engine.setDragLocked(false, emitButton = false)
    }

    fun resetForLifecycle() {
        removeCallbacks(holdRunnable)
        suppressedUntilAllUp = false
        clearTwoPointerTracking()
        motionBatcher.cancel()
        engine.resetAll(emitButtonRelease = false)
    }

    val isDragLocked: Boolean
        get() = engine.isDragLocked

    private fun clearTwoPointerTracking() {
        twoPointerId1 = MotionEvent.INVALID_POINTER_ID
        twoPointerId2 = MotionEvent.INVALID_POINTER_ID
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

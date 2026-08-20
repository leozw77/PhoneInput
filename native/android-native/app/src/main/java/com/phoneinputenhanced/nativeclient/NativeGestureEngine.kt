package com.phoneinputenhanced.nativeclient

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Native gesture state machine retained for Native preview.6.
 *
 * MotionEvent stays outside this class. The engine receives normalized pointer
 * samples and emits protocol-level actions so the gesture policy remains easy
 * to unit-test independently from Android UI/network code.
 */
class NativeGestureEngine(
    private var config: GestureConfig = GestureConfig(),
    private val output: (Output) -> Unit,
) {
    enum class State {
        Idle,
        TapCandidate,
        SingleMove,
        PressDrag,
        DoubleTapDrag,
        DragLocked,
        TwoFingerPending,
        TwoFingerScroll,
        TwoFingerHold,
        Suppressed,
    }

    sealed interface Output {
        data class MouseMove(val dx: Int, val dy: Int) : Output
        data class Scroll(val x: Int, val y: Int) : Output
        data object LeftClick : Output
        data object RightClick : Output
        data object LeftDown : Output
        data object LeftUp : Output
        data object OpenTextInput : Output
        data class Hint(val text: String) : Output
        data class DragLockChanged(val enabled: Boolean) : Output
    }

    var state: State = State.Idle
        private set

    var isDragLocked: Boolean = false
        private set

    private var startX = 0f
    private var startY = 0f
    private var sentX = 0f
    private var sentY = 0f
    private var downTimeMs = 0L
    private var maxMove = 0f
    private var dragArmed = false
    private var secondTap = false
    private var gestureLeftHeld = false

    private var lastTapTimeMs = Long.MIN_VALUE
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var twoStartTimeMs = 0L
    private var twoStartX1 = 0f
    private var twoStartY1 = 0f
    private var twoStartX2 = 0f
    private var twoStartY2 = 0f
    private var twoLastCenterX = 0f
    private var twoLastCenterY = 0f
    private var twoMaxFingerMove = 0f

    fun updateConfig(next: GestureConfig) {
        config = next
    }

    fun twoFingerHoldDelayMs(): Long = config.twoFingerHoldMs

    fun onSingleDown(x: Float, y: Float, timeMs: Long) {
        startX = x
        startY = y
        sentX = x
        sentY = y
        downTimeMs = timeMs
        maxMove = 0f
        dragArmed = false
        gestureLeftHeld = false

        if (isDragLocked) {
            secondTap = false
            state = State.DragLocked
            output(Output.Hint("拖动锁定中 · 滑动即可拖动"))
            return
        }

        secondTap = lastTapTimeMs != Long.MIN_VALUE &&
            timeMs - lastTapTimeMs in 0..config.doubleTapMaxIntervalMs &&
            hypot(x - lastTapX, y - lastTapY) < config.doubleTapMaxDistancePx
        state = State.TapCandidate
        if (secondTap) output(Output.Hint("第二次按住约 0.15 秒并移动可拖动"))
    }

    fun onSingleMove(x: Float, y: Float, timeMs: Long) {
        when (state) {
            State.Idle, State.Suppressed,
            State.TwoFingerPending, State.TwoFingerScroll, State.TwoFingerHold -> return
            State.DragLocked -> {
                emitMoveFromSent(x, y)
                return
            }
            else -> Unit
        }

        val total = hypot(x - startX, y - startY)
        val previousMax = maxMove
        val elapsed = (timeMs - downTimeMs).coerceAtLeast(0L)

        if (state == State.TapCandidate) {
            val armDelay = if (secondTap) config.doubleTapDragArmMs else config.pressDragArmMs
            if (!dragArmed && elapsed >= armDelay && previousMax <= config.tapMoveTolerancePx) {
                dragArmed = true
                output(Output.Hint(if (secondTap) "继续移动即可双击拖动" else "按住已识别 · 继续移动即可拖动"))
            }

            maxMove = maxOf(previousMax, total)
            if (dragArmed) {
                if (total > config.dragStartDistancePx) {
                    beginDrag(if (secondTap) State.DoubleTapDrag else State.PressDrag)
                } else {
                    return
                }
            } else if (total > config.tapMoveTolerancePx) {
                state = State.SingleMove
                secondTap = false
                lastTapTimeMs = Long.MIN_VALUE
                output(Output.Hint("移动鼠标"))
            } else {
                return
            }
        } else {
            maxMove = maxOf(previousMax, total)
        }

        if (state == State.SingleMove || state == State.PressDrag || state == State.DoubleTapDrag) {
            emitMoveFromSent(x, y)
        }
    }

    fun onSingleUp(x: Float, y: Float, timeMs: Long, cancelled: Boolean = false) {
        when (state) {
            State.SingleMove, State.PressDrag, State.DoubleTapDrag, State.DragLocked -> emitMoveFromSent(x, y)
            else -> Unit
        }

        when (state) {
            State.PressDrag, State.DoubleTapDrag -> {
                if (gestureLeftHeld) {
                    output(Output.LeftUp)
                    gestureLeftHeld = false
                }
                if (!cancelled) output(Output.Hint("拖动结束"))
            }
            State.TapCandidate -> {
                if (!cancelled) {
                    output(Output.LeftClick)
                    if (secondTap) {
                        lastTapTimeMs = Long.MIN_VALUE
                        output(Output.Hint("已双击"))
                    } else {
                        lastTapTimeMs = timeMs
                        lastTapX = x
                        lastTapY = y
                        output(Output.Hint("已单击"))
                    }
                }
            }
            else -> Unit
        }

        secondTap = false
        dragArmed = false
        maxMove = 0f
        state = if (isDragLocked) State.DragLocked else State.Idle
    }

    fun onTwoFingerDown(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        timeMs: Long,
    ) {
        releaseSingleOrLockedForMultiPointer()
        lastTapTimeMs = Long.MIN_VALUE
        twoStartTimeMs = timeMs
        twoStartX1 = x1
        twoStartY1 = y1
        twoStartX2 = x2
        twoStartY2 = y2
        twoLastCenterX = (x1 + x2) / 2f
        twoLastCenterY = (y1 + y2) / 2f
        twoMaxFingerMove = 0f
        state = State.TwoFingerPending
        output(Output.Hint("双指轻触右键 · 保持约 0.5 秒打开输入框"))
    }

    fun onTwoFingerMove(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        timeMs: Long,
    ) {
        if (state != State.TwoFingerPending && state != State.TwoFingerScroll) return

        val centerX = (x1 + x2) / 2f
        val centerY = (y1 + y2) / 2f
        val finger1Move = hypot(x1 - twoStartX1, y1 - twoStartY1)
        val finger2Move = hypot(x2 - twoStartX2, y2 - twoStartY2)
        twoMaxFingerMove = maxOf(twoMaxFingerMove, finger1Move, finger2Move)

        val centerDx = centerX - twoLastCenterX
        val centerDy = centerY - twoLastCenterY
        twoLastCenterX = centerX
        twoLastCenterY = centerY

        if (state == State.TwoFingerPending) {
            if (twoMaxFingerMove <= config.twoFingerScrollStartDistancePx) return
            state = State.TwoFingerScroll
            output(Output.Hint("双指滚动中"))
        }

        val direction = if (config.naturalScroll) -1f else 1f
        val scrollX = (centerDx * config.scrollSpeed * direction).roundToInt()
        val scrollY = (centerDy * config.scrollSpeed * direction).roundToInt()
        if (scrollX != 0 || scrollY != 0) {
            output(Output.Scroll(scrollX.coerceIn(-1200, 1200), scrollY.coerceIn(-1200, 1200)))
        }
    }

    fun onTwoFingerHoldTimeout(timeMs: Long) {
        if (state != State.TwoFingerPending) return
        val elapsed = (timeMs - twoStartTimeMs).coerceAtLeast(0L)
        if (elapsed < config.twoFingerHoldMs || twoMaxFingerMove > config.twoFingerHoldMoveTolerancePx) return
        state = State.TwoFingerHold
        output(Output.Hint("双指长按已识别 · 打开输入框"))
        output(Output.OpenTextInput)
    }

    fun onTwoFingerUp(timeMs: Long, cancelled: Boolean = false) {
        val stateAtEnd = state
        if (!cancelled && stateAtEnd == State.TwoFingerPending) {
            val elapsed = (timeMs - twoStartTimeMs).coerceAtLeast(0L)
            if (elapsed <= config.twoFingerTapMaxMs && twoMaxFingerMove < config.twoFingerTapMoveTolerancePx) {
                output(Output.RightClick)
                output(Output.Hint("已发送右键"))
            }
        }
        clearTwoFingerState()
        state = State.Suppressed
    }

    /** Cancel only the current finger gesture. A persistent drag lock is kept. */
    fun cancelCurrentPointer() {
        if (gestureLeftHeld) {
            output(Output.LeftUp)
            gestureLeftHeld = false
        }
        secondTap = false
        dragArmed = false
        maxMove = 0f
        clearTwoFingerState()
        state = if (isDragLocked) State.DragLocked else State.Idle
    }

    /** Three or more pointers are ignored and all held mouse state is released. */
    fun suppressForTooManyPointers() {
        releaseSingleOrLockedForMultiPointer()
        clearTwoFingerState()
        lastTapTimeMs = Long.MIN_VALUE
        state = State.Suppressed
        output(Output.Hint("三指及以上手势已忽略"))
    }

    fun endSuppression() {
        if (state == State.Suppressed) state = State.Idle
    }

    fun toggleDragLock(): Boolean {
        setDragLocked(!isDragLocked, emitButton = true)
        return isDragLocked
    }

    fun setDragLocked(enabled: Boolean, emitButton: Boolean) {
        if (enabled == isDragLocked) return

        if (enabled) {
            if (!gestureLeftHeld && emitButton) output(Output.LeftDown)
            gestureLeftHeld = false
            isDragLocked = true
            state = State.DragLocked
            secondTap = false
            dragArmed = false
            lastTapTimeMs = Long.MIN_VALUE
            output(Output.DragLockChanged(true))
            output(Output.Hint("拖动锁定已开启 · 滑动即可拖动"))
        } else {
            if (emitButton) output(Output.LeftUp)
            gestureLeftHeld = false
            isDragLocked = false
            state = State.Idle
            output(Output.DragLockChanged(false))
            output(Output.Hint("拖动锁定已关闭"))
        }
    }

    /** Clear local state when lifecycle/network already guarantees release. */
    fun resetAll(emitButtonRelease: Boolean) {
        if (emitButtonRelease && (gestureLeftHeld || isDragLocked)) output(Output.LeftUp)
        gestureLeftHeld = false
        if (isDragLocked) output(Output.DragLockChanged(false))
        isDragLocked = false
        secondTap = false
        dragArmed = false
        lastTapTimeMs = Long.MIN_VALUE
        maxMove = 0f
        clearTwoFingerState()
        state = State.Idle
        output(Output.Hint("轻触左键 · 双指轻触右键 · 双指长按输入"))
    }

    private fun releaseSingleOrLockedForMultiPointer() {
        if (gestureLeftHeld || isDragLocked) output(Output.LeftUp)
        gestureLeftHeld = false
        if (isDragLocked) {
            isDragLocked = false
            output(Output.DragLockChanged(false))
        }
        secondTap = false
        dragArmed = false
        maxMove = 0f
    }

    private fun clearTwoFingerState() {
        twoStartTimeMs = 0L
        twoMaxFingerMove = 0f
        twoLastCenterX = 0f
        twoLastCenterY = 0f
    }

    private fun beginDrag(nextState: State) {
        output(Output.LeftDown)
        gestureLeftHeld = true
        lastTapTimeMs = Long.MIN_VALUE
        state = nextState
        output(Output.Hint(if (nextState == State.DoubleTapDrag) "双击按住拖动中" else "按住拖动中"))
    }

    private fun emitMoveFromSent(x: Float, y: Float) {
        val rawDx = x - sentX
        val rawDy = y - sentY
        sentX = x
        sentY = y
        val dx = (rawDx * config.sensitivity).roundToInt()
            .coerceIn(-config.maxDeltaPerSample, config.maxDeltaPerSample)
        val dy = (rawDy * config.sensitivity).roundToInt()
            .coerceIn(-config.maxDeltaPerSample, config.maxDeltaPerSample)
        if (dx != 0 || dy != 0) output(Output.MouseMove(dx, dy))
    }
}

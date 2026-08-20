package com.phoneinputenhanced.nativeclient

/**
 * Coalesces high-frequency MotionEvent output to at most one scheduled flush per display frame.
 *
 * Android can deliver many historical touch samples in one ACTION_MOVE. Sending every sample as
 * an individual WebSocket command makes the writer queue fall behind the finger and feels like
 * cursor stutter/lag. This class preserves the total relative movement while batching those samples.
 */
class MotionFrameBatcher(
    private val scheduleFrame: ((() -> Unit) -> Unit),
    private val moveSink: (Int, Int) -> Unit,
    private val scrollSink: (Int, Int) -> Unit,
) {
    private var scheduled = false
    private var pendingMoveX = 0L
    private var pendingMoveY = 0L
    private var pendingScrollX = 0L
    private var pendingScrollY = 0L

    fun addMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        pendingMoveX += dx.toLong()
        pendingMoveY += dy.toLong()
        ensureScheduled()
    }

    fun addScroll(x: Int, y: Int) {
        if (x == 0 && y == 0) return
        pendingScrollX += x.toLong()
        pendingScrollY += y.toLong()
        ensureScheduled()
    }

    fun flushNow() {
        scheduled = false
        flushChunked(pendingMoveX, pendingMoveY, 480, moveSink)
        flushChunked(pendingScrollX, pendingScrollY, 1200, scrollSink)
        pendingMoveX = 0L
        pendingMoveY = 0L
        pendingScrollX = 0L
        pendingScrollY = 0L
    }

    fun cancel() {
        scheduled = false
        pendingMoveX = 0L
        pendingMoveY = 0L
        pendingScrollX = 0L
        pendingScrollY = 0L
    }

    private fun ensureScheduled() {
        if (scheduled) return
        scheduled = true
        scheduleFrame {
            if (!scheduled) return@scheduleFrame
            flushNow()
        }
    }

    private fun flushChunked(
        rawX: Long,
        rawY: Long,
        limit: Int,
        sink: (Int, Int) -> Unit,
    ) {
        var remainingX = rawX
        var remainingY = rawY
        while (remainingX != 0L || remainingY != 0L) {
            val chunkX = remainingX.coerceIn(-limit.toLong(), limit.toLong()).toInt()
            val chunkY = remainingY.coerceIn(-limit.toLong(), limit.toLong()).toInt()
            sink(chunkX, chunkY)
            remainingX -= chunkX
            remainingY -= chunkY
        }
    }
}

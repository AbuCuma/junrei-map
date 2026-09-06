package cn.anitabi.map.map.google

import android.os.Trace
import android.view.Choreographer
import com.google.android.gms.maps.model.Marker

/**
 * 用**一个 Choreographer 回调**驱动全部标注的淡入淡出（仅限主线程）。
 *
 * 以前是每个标注各起一个 ValueAnimator，大幅平移后的一次 idle 里会有数百个
 * 同时运行，每帧产生数百次 Marker.alpha 写入。Maps SDK 的标注操作一次约 80µs
 * （跨 dynamite 边界），600 个就是一帧 50ms —— Perfetto 实测。
 *
 * 因此：
 * - 由经过时间算出 alpha，只在量化后的档位变化时才写入
 * - 单帧可用时间以 [FadeCurve.TICK_BUDGET_NANOS] 截断，剩余顺延到下一帧
 *   （并发越多，档位自然越粗、完成略微延后，但不会掉帧）
 * - 结束处理（remove）也在同一预算内进行
 */
class MarkerFadeDriver(
    private val choreographer: Choreographer = Choreographer.getInstance(),
    private val clock: () -> Long = System::nanoTime,
) {
    class Handle internal constructor(
        internal val marker: Marker,
        internal val from: Float,
        internal val to: Float,
        internal val startNanos: Long,
        internal val durationNanos: Long,
        internal val onEnd: (() -> Unit)?,
    ) {
        internal var lastStep = Int.MIN_VALUE
        internal var cancelled = false
        internal var finished = false

        /** 最近一次写入 Marker 的 alpha（自己保存一份，避免 getter 的跨边界调用）。 */
        var currentAlpha: Float = from
            internal set

        fun cancel() {
            cancelled = true
        }
    }

    private val active = ArrayList<Handle>()
    private var scheduled = false

    val inFlight: Int get() = active.size

    fun fade(
        marker: Marker,
        from: Float,
        to: Float,
        durationMs: Long,
        delayMs: Long,
        onEnd: (() -> Unit)? = null,
    ): Handle {
        val now = clock()
        val handle = Handle(
            marker = marker,
            from = from,
            to = to,
            startNanos = now + delayMs * 1_000_000L,
            durationNanos = durationMs * 1_000_000L,
            onEnd = onEnd,
        )
        active.add(handle)
        ensureScheduled()
        return handle
    }

    fun cancelAll() {
        for (h in active) h.cancelled = true
        active.clear()
    }

    private fun ensureScheduled() {
        if (scheduled) return
        scheduled = true
        choreographer.postFrameCallback(callback)
    }

    private val callback = Choreographer.FrameCallback { frameTimeNanos ->
        scheduled = false
        Trace.beginSection("MarkerFade.tick")
        tick(frameTimeNanos)
        Trace.endSection()
    }

    private fun tick(frameTimeNanos: Long) {
        val steps = FadeCurve.stepsFor(active.size)
        val deadline = clock() + FadeCurve.TICK_BUDGET_NANOS
        var ended = 0
        var overBudget = false
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val h = iterator.next()
            if (h.cancelled) {
                iterator.remove()
                continue
            }
            if (overBudget) continue // 剩余顺延到下一帧
            val t = FadeCurve.progress(frameTimeNanos - h.startNanos, h.durationNanos)
            if (t >= 1.0) {
                if (!h.finished) {
                    h.marker.alpha = h.to
                    h.currentAlpha = h.to
                    h.finished = true
                }
                if (h.onEnd != null && ended >= FadeCurve.MAX_ENDS_PER_FRAME) continue
                iterator.remove()
                if (h.onEnd != null) {
                    ended++
                    h.onEnd.invoke()
                }
            } else {
                val alpha = FadeCurve.alphaAt(h.from, h.to, t)
                val step = FadeCurve.step(alpha, steps)
                if (step != h.lastStep) {
                    h.lastStep = step
                    h.currentAlpha = alpha
                    h.marker.alpha = alpha
                }
            }
            if (clock() > deadline) overBudget = true
        }
        if (active.isNotEmpty()) ensureScheduled()
    }
}

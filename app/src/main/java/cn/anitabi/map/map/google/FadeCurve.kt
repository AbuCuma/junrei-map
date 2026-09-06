package cn.anitabi.map.map.google

/**
 * 标注淡入淡出的纯函数部分（不依赖 Android，可单元测试）。
 * 缓动为 `1-(1-t)^2` —— 与以前用的 DecelerateInterpolator(1f) 是同一条曲线。
 */
object FadeCurve {
    /** 常规状态下的 alpha 档数。仅在档位变化时才写入 Marker.alpha。 */
    const val STEPS_NORMAL = 32
    /** 并发较多时的档数（把写入次数降到 1/8）。 */
    const val STEPS_LARGE_BATCH = 4
    /** 并发超过此数就降到粗档数。 */
    const val LARGE_BATCH = 150
    /** 单帧内执行结束处理（remove）的上限 —— 把数百个 remove 分散到多帧。 */
    const val MAX_ENDS_PER_FRAME = 48
    /** 单帧可用于标注写入的时间（相对 120Hz 的 8.3ms 预算取保守值）。 */
    const val TICK_BUDGET_NANOS = 2_500_000L

    /** 进度 0..1（开始前饱和为 0）。 */
    fun progress(elapsedNanos: Long, durationNanos: Long): Double {
        if (durationNanos <= 0L) return 1.0
        return (elapsedNanos.toDouble() / durationNanos).coerceIn(0.0, 1.0)
    }

    fun ease(t: Double): Double = 1.0 - (1.0 - t) * (1.0 - t)

    fun alphaAt(from: Float, to: Float, t: Double): Float =
        (from + (to - from) * ease(t)).toFloat()

    /** 按档数量化后的 alpha 档位（同档即可省去写入）。 */
    fun step(alpha: Float, steps: Int): Int = Math.round(alpha * steps)

    fun stepsFor(inFlight: Int): Int = if (inFlight > LARGE_BATCH) STEPS_LARGE_BATCH else STEPS_NORMAL
}

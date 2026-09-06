package cn.anitabi.map.map.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeCurveTest {

    @Test
    fun progressSaturatesBeforeStartAndAfterEnd() {
        assertEquals(0.0, FadeCurve.progress(-50_000_000L, 220_000_000L), 0.0)
        assertEquals(0.5, FadeCurve.progress(110_000_000L, 220_000_000L), 1e-9)
        assertEquals(1.0, FadeCurve.progress(300_000_000L, 220_000_000L), 0.0)
        assertEquals(1.0, FadeCurve.progress(0L, 0L), 0.0)
    }

    @Test
    fun easeMatchesDecelerateInterpolator() {
        // DecelerateInterpolator(1f) = 1 - (1-t)^2
        assertEquals(0.0, FadeCurve.ease(0.0), 1e-9)
        assertEquals(0.75, FadeCurve.ease(0.5), 1e-9)
        assertEquals(1.0, FadeCurve.ease(1.0), 1e-9)
        assertEquals(1f, FadeCurve.alphaAt(0f, 1f, 1.0), 1e-6f)
        assertEquals(0.25f, FadeCurve.alphaAt(1f, 0f, 0.5), 1e-6f)
    }

    @Test
    fun quantizationCoarsensOnLargeBatches() {
        assertEquals(FadeCurve.STEPS_NORMAL, FadeCurve.stepsFor(10))
        assertEquals(FadeCurve.STEPS_NORMAL, FadeCurve.stepsFor(FadeCurve.LARGE_BATCH))
        assertEquals(FadeCurve.STEPS_LARGE_BATCH, FadeCurve.stepsFor(FadeCurve.LARGE_BATCH + 1))
        // 4 段なら 0..1 の間で書き込みは最大 5 回
        val steps = (0..100).map { FadeCurve.step(it / 100f, 4) }.distinct()
        assertTrue(steps.size <= 5)
    }
}

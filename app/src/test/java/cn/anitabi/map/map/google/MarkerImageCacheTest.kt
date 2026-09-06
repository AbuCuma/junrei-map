package cn.anitabi.map.map.google

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerImageCacheTest {
    @Test
    fun sampleSizeIsLargestPowerOfTwoKeepingTarget() {
        assertEquals(1, MarkerImageCache.sampleSize(285, 240))
        assertEquals(2, MarkerImageCache.sampleSize(480, 240))
        assertEquals(2, MarkerImageCache.sampleSize(900, 240))
        assertEquals(4, MarkerImageCache.sampleSize(960, 240))
        assertEquals(1, MarkerImageCache.sampleSize(0, 240))
        assertEquals(1, MarkerImageCache.sampleSize(500, 0))
    }
}

package cn.anitabi.map.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    private fun v(s: String) = AppVersion.parse(s) ?: error("应能解析:$s")

    @Test
    fun parsesTagsAndVersionNames() {
        assertEquals(listOf(0, 1, 1), v("v0.1.1").parts)
        assertEquals(listOf(0, 1, 1), v("0.1.1").parts)
        assertEquals(listOf(1, 2, 3), v(" V1.2.3 ").parts)
        assertEquals("beta.1", v("0.2.0-beta.1").preRelease)
        assertEquals("build7", v("0.2.0+build7").preRelease)
        assertNull(v("0.2.0").preRelease)
    }

    @Test
    fun unparsableIsNull() {
        for (raw in listOf("", "v", "latest", "0.1.x", "1..2", "v0.1-", "0.1.1 (debug)")) {
            assertNull("「$raw」不该被当成版本号", AppVersion.parse(raw))
        }
    }

    @Test
    fun missingSegmentsAreZero() {
        assertEquals(0, v("0.1").compareTo(v("0.1.0")))
        assertTrue(v("0.1.1.1") > v("0.1.1"))
        assertTrue(v("0.2") > v("0.1.9"))
        assertTrue(v("1.0") > v("0.99.99"))
    }

    @Test
    fun preReleaseIsOlderThanRelease() {
        assertTrue(v("0.2.0-beta") < v("0.2.0"))
        assertTrue(v("0.2.0-beta") > v("0.1.9"))
        assertTrue(v("0.2.0-rc1") > v("0.2.0-beta"))
        assertEquals(0, v("0.2.0-rc1").compareTo(v("v0.2.0-rc1")))
    }
}

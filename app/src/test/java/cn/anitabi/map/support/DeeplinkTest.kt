package cn.anitabi.map.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeeplinkTest {

    @Test
    fun parsesSchemeAndHttpsForms() {
        val link = MapDeepLink.parse("anitabi://map?bangumiId=815878&pid=abc&c=139.7671,35.6812&z=16&bids=1,2,3")!!
        assertEquals(815878, link.bangumiId)
        assertEquals("abc", link.pointId)
        // c=lng,lat：経度が先
        assertEquals(35.6812, link.center!!.lat, 1e-9)
        assertEquals(139.7671, link.center!!.lng, 1e-9)
        assertEquals(16.0, link.zoom!!, 1e-9)
        assertEquals(listOf(1, 2, 3), link.bids)

        val https = MapDeepLink.parse("https://ww.anitabi.cn/map?bangumiId=1")!!
        assertEquals(1, https.bangumiId)
        assertEquals(1, MapDeepLink.parse("https://w.junreimap.com/map?bangumiId=1")!!.bangumiId)
    }

    @Test
    fun rejectsUnknownHostsAndPaths() {
        assertNull(MapDeepLink.parse("https://evil.example.com/map?bangumiId=1"))
        assertNull(MapDeepLink.parse("https://anitabi.cn/other?bangumiId=1"))
        assertNull(MapDeepLink.parse("mailto://map?bangumiId=1"))
    }

    @Test
    fun dropsBrokenParamsSilentlyButNullOnAllBroken() {
        // 単個の壊れたパラメータは捨てて残りを生かす
        val link = MapDeepLink.parse("anitabi://map?bangumiId=abc&z=16")!!
        assertNull(link.bangumiId)
        assertEquals(16.0, link.zoom!!, 1e-9)

        // 範囲外の座標は捨てる
        assertNull(MapDeepLink.parse("anitabi://map?c=999,35")?.center)

        // 全部読めなければ null
        assertNull(MapDeepLink.parse("anitabi://map?bangumiId=abc&z=NaN"))
        assertNull(MapDeepLink.parse("anitabi://map"))
    }
}

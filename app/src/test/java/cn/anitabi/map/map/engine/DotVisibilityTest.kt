package cn.anitabi.map.map.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 圆点层的显隐规则：z<12 交给作品标，z≥12 全画。 */
class DotVisibilityTest {

    /**
     * 与网页版一致：z12 以下一个单点都不画。
     *
     * 改前是 `tileDotThreshold` 在 z<12 沿用 [MapRevealLadder.pointThreshold] 的阶梯，
     * 实测东京一带 z5–z11 会画出 1000–2600 个圆点 —— 那正是「低 zoom 太吵」的来源。
     * 聚合改由作品标承担（[MapRevealLadder.workThreshold]，z<13、屏幕上 ≤32 个）。
     */
    @Test
    fun nothingIsDrawnBelowTheFadeStart() {
        for (zoom in listOf(0.0, 5.0, 8.0, 10.0, 11.0, 11.5, 11.7)) {
            assertEquals(
                "z$zoom 不该画单点",
                0f,
                MapRevealLadder.dotFieldOpacity(zoom, filtered = false),
                0f,
            )
        }
    }

    @Test
    fun everythingIsDrawnAtAndAboveFullDensityZoom() {
        for (zoom in listOf(12.0, 13.0, 16.0, 21.0)) {
            assertEquals(1f, MapRevealLadder.dotFieldOpacity(zoom, filtered = false), 0f)
        }
    }

    /** 渐入只是软化「0 → 数千个点」的硬跳变，必须单调且两端闭合。 */
    @Test
    fun opacityRampsMonotonicallyAcrossTheFadeBand() {
        var previous = 0f
        var zoom = MapRevealLadder.DOT_FADE_IN_START
        while (zoom <= MapRevealLadder.FULL_DENSITY_ZOOM) {
            val opacity = MapRevealLadder.dotFieldOpacity(zoom, filtered = false)
            assertTrue("z$zoom 渐入不单调", opacity >= previous)
            assertTrue("z$zoom 越界", opacity in 0f..1f)
            previous = opacity
            zoom += 0.02
        }
        assertEquals(0.5f, MapRevealLadder.dotFieldOpacity(11.85, filtered = false), 0.02f)
    }

    /** 作品模式忽略 zoom 规则 —— 选中作品后任何 zoom 都要看得见它的点。 */
    @Test
    fun workModeIgnoresTheZoomRule() {
        for (zoom in listOf(1.0, 5.0, 11.0, 12.0, 20.0)) {
            assertEquals(1f, MapRevealLadder.dotFieldOpacity(zoom, filtered = true), 0f)
        }
    }
}

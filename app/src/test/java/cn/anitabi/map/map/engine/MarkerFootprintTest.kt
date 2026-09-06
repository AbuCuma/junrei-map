package cn.anitabi.map.map.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 足迹与尺寸表的一致性。
 *
 * 注意这里**测不到位图**：`MarkerIconFactory` 在 `android.graphics` 上，而 `map/engine`
 * 必须保持纯 Kotlin。位图与足迹是否真的对得上，由真机 harness
 * （`MapSceneDeviceHarness.footprintsMatchRenderedBitmaps`）来钉。
 */
class MarkerFootprintTest {

    /** 剧照牌的尾尖即坐标，本体在其正上方、水平居中。 */
    @Test
    fun photoFootprintHangsAboveTheAnchor() {
        for (plate in listOf(MapMarkerMetrics.PhotoPlate.CARD, MapMarkerMetrics.PhotoPlate.BUBBLE)) {
            val footprint = MarkerFootprint.photo(plate)
            assertEquals(0.0, footprint.maxY, 0.0)
            assertEquals(-plate.totalHeight, footprint.minY, 0.0)
            assertEquals(plate.totalWidth, footprint.maxX - footprint.minX, 0.0)
            assertEquals("水平居中", -footprint.minX, footprint.maxX, 0.0)
        }
    }

    /** 意匠的切换点与 `MarkerIconFactory.photoStyle` 必须是同一条边界。 */
    @Test
    fun theCardBubbleBoundaryIsThePhotoCardZoom() {
        assertTrue(MarkerFootprint.photoPlate(MapRevealLadder.PHOTO_CARD_ZOOM) ===
            MapMarkerMetrics.PhotoPlate.BUBBLE)
        assertTrue(MarkerFootprint.photoPlate(MapRevealLadder.PHOTO_CARD_ZOOM + 0.01) ===
            MapMarkerMetrics.PhotoPlate.CARD)
    }

    /** 气球：头 32、尾 10，尾尖即坐标（与 MarkerIconFactory.renderBalloon 同值）。 */
    @Test
    fun balloonFootprintIs32By42WithTheTipAtTheAnchor() {
        assertEquals(32.0, MarkerFootprint.BALLOON.maxX - MarkerFootprint.BALLOON.minX, 0.0)
        assertEquals(42.0, MarkerFootprint.BALLOON.maxY - MarkerFootprint.BALLOON.minY, 0.0)
        assertEquals(0.0, MarkerFootprint.BALLOON.maxY, 0.0)
    }

    /** 作品标以坐标为中心，且宽度容得下 30dp 封面 ＋ 92dp 标签。 */
    @Test
    fun workFootprintIsCentredOnTheAnchor() {
        val work = MarkerFootprint.WORK
        assertEquals(-work.minX, work.maxX, 0.0)
        assertEquals(-work.minY, work.maxY, 0.0)
        assertTrue(work.maxX - work.minX >= 92.0)
        assertTrue(work.maxY - work.minY >= MapMarkerMetrics.WorkMarker.ICON_SIZE)
    }

    @Test
    fun inflationGrowsEverySide() {
        val inflated = MarkerFootprint.BALLOON.inflated(5.0)
        assertEquals(MarkerFootprint.BALLOON.minX - 5.0, inflated.minX, 0.0)
        assertEquals(MarkerFootprint.BALLOON.minY - 5.0, inflated.minY, 0.0)
        assertEquals(MarkerFootprint.BALLOON.maxX + 5.0, inflated.maxX, 0.0)
        assertEquals(MarkerFootprint.BALLOON.maxY + 5.0, inflated.maxY, 0.0)
    }
}

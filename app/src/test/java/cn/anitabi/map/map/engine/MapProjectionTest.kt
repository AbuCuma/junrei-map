package cn.anitabi.map.map.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 投影与圆点尺寸。纯数学，全部在 JVM 上钉死。 */
class MapProjectionTest {

    private fun affine(
        centerLat: Double = 35.6812,
        centerLng: Double = 139.7671,
        zoom: Double = 14.0,
        bearing: Float = 0f,
        density: Float = 3.75f,
        originX: Float = 540f,
        originY: Float = 1200f,
    ) = MapProjection.Affine(
        originWorldX = MapProjection.worldX(centerLng),
        originWorldY = MapProjection.worldY(centerLat),
        originScreenX = originX,
        originScreenY = originY,
        zoom = zoom,
        bearingDegrees = bearing,
        density = density,
    )

    @Test
    fun cameraTargetLandsOnTheAnchorPoint() {
        val a = affine()
        assertEquals(540f, a.projectX(35.6812, 139.7671), 0.01f)
        assertEquals(1200f, a.projectY(35.6812, 139.7671), 0.01f)
    }

    /** 东边的点在右、北边的点在上（屏幕 y 向下）。 */
    @Test
    fun northIsUpAndEastIsRightWithoutBearing() {
        val a = affine()
        assertTrue(a.projectX(35.6812, 139.8) > 540f)
        assertTrue(a.projectX(35.6812, 139.7) < 540f)
        assertTrue(a.projectY(35.8, 139.7671) < 1200f)
        assertTrue(a.projectY(35.5, 139.7671) > 1200f)
    }

    /**
     * GMS 的 bearing 是「相机朝向，自北顺时针」，也就是**屏幕上方所指的方向**。
     * 所以 bearing=90°（相机朝东）时，正北的点会跑到屏幕**左侧**。
     * 符号搞反是这里最容易犯的错 —— 我第一版就把它写成了右侧。
     */
    @Test
    fun bearingRotatesPointsInTheOppositeDirection() {
        val north = 35.8 to 139.7671
        val plain = affine(bearing = 0f)
        val turned = affine(bearing = 90f)

        // 无 bearing：正北在正上方
        assertEquals(540f, plain.projectX(north.first, north.second), 0.5f)
        assertTrue(plain.projectY(north.first, north.second) < 1200f)

        // bearing 90°：相机朝东 ⇒ 正北转到左侧，且与原点的距离不变
        assertTrue(turned.projectX(north.first, north.second) < 540f)
        assertEquals(1200f, turned.projectY(north.first, north.second), 0.5f)

        val d0 = hypot(
            (plain.projectX(north.first, north.second) - 540f).toDouble(),
            (plain.projectY(north.first, north.second) - 1200f).toDouble(),
        )
        val d90 = hypot(
            (turned.projectX(north.first, north.second) - 540f).toDouble(),
            (turned.projectY(north.first, north.second) - 1200f).toDouble(),
        )
        assertEquals(d0, d90, 0.5)
    }

    @Test
    fun bearing180FlipsBothAxes() {
        val a = affine(bearing = 180f)
        assertTrue(a.projectY(35.8, 139.7671) > 1200f)   // 正北跑到下方
        assertTrue(a.projectX(35.6812, 139.8) < 540f)    // 正东跑到左侧
    }

    /** zoom 每加 1，同一对点的屏幕距离翻倍。 */
    @Test
    fun oneZoomLevelDoublesScreenDistance() {
        for (z in 8..18) {
            val lo = affine(zoom = z.toDouble())
            val hi = affine(zoom = z + 1.0)
            val dLo = abs(lo.projectX(35.6812, 139.8) - 540f)
            val dHi = abs(hi.projectX(35.6812, 139.8) - 540f)
            assertEquals("z$z", 2.0, (dHi / dLo).toDouble(), 0.001)
        }
    }

    @Test
    fun projectAllMatchesPerPointProjection() {
        val a = affine()
        val pts = listOf(35.6812 to 139.7671, 35.7 to 139.8, 35.5 to 139.6)
        val world = DoubleArray(pts.size * 2)
        for ((i, p) in pts.withIndex()) {
            world[i * 2] = MapProjection.worldX(p.second)
            world[i * 2 + 1] = MapProjection.worldY(p.first)
        }
        val out = FloatArray(pts.size * 2)
        a.projectAll(world, pts.size, out)
        for ((i, p) in pts.withIndex()) {
            assertEquals(a.projectX(p.first, p.second), out[i * 2], 0.01f)
            assertEquals(a.projectY(p.first, p.second), out[i * 2 + 1], 0.01f)
        }
    }

    @Test
    fun worldCoordinatesRoundTrip() {
        for (lng in listOf(-179.9, -90.0, 0.0, 139.7671, 179.9)) {
            assertEquals(lng, MapProjection.lngOf(MapProjection.worldX(lng)), 1e-9)
        }
        for (lat in listOf(-80.0, -35.0, 0.0, 35.6812, 80.0)) {
            assertEquals(lat, MapProjection.latOf(MapProjection.worldY(lat)), 1e-6)
        }
    }

    /**
     * **上一版缺的正是这一条。**
     *
     * 圆点曾被烘焙进光栅瓦片，屏幕半径 = `烘焙值 × 2^f`：带内涨 2 倍、跨整数 zoom 瞬缩 1.9 倍。
     * 当时的守门测试只断言了「与 Web 目标的静态误差 ≤ 0.55 log2」，从没断言过**连续性**，
     * 所以那个跳变一路绿灯发了出去。
     *
     * 现在半径直接取相机 zoom，这条断言相邻采样之间不出现跳变。
     *
     * 阈值 0.03 的来历：这条曲线自身在 z17.9 附近最陡，0.05 的步进就有 0.0202 的 log2 增长，
     * 那是正常生长不是跳变；而旧实现在整数边界的跳变约 0.9 —— 两者差 30 倍，卡在中间很安全。
     */
    @Test
    fun dotRadiusIsContinuousAcrossIntegerZoomBoundaries() {
        var z = 4.0
        var previous = MapMarkerMetrics.dotRadius(z)
        while (z <= 21.0) {
            z += 0.05
            val current = MapMarkerMetrics.dotRadius(z)
            val step = abs(log2(current / previous))
            assertTrue(
                "z=$z 处半径从 $previous 跳到 $current（log2 步进 $step）",
                step < 0.03,
            )
            previous = current
        }
    }

    /** 描边宽度同样必须连续。 */
    @Test
    fun dotStrokeIsContinuousAcrossIntegerZoomBoundaries() {
        var z = 4.0
        var previous = MapMarkerMetrics.dotStrokeWidth(z)
        while (z <= 21.0) {
            z += 0.05
            val current = MapMarkerMetrics.dotStrokeWidth(z)
            assertTrue("z=$z 描边跳变", abs(log2(current / previous)) < 0.03)
            previous = current
        }
    }
}

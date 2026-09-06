package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 圆点快照：分趟、分组、坐标、命中。 */
class DotFieldTest {

    private val zoom = 16.0

    private fun point(id: String, work: Int, lat: Double = 35.0, lng: Double = 139.0, priority: Int = 0) =
        ScenePoint(id = id, bangumiId = work, lat = lat, lng = lng, priority = priority)

    private val colors = mapOf(1 to 0xFFAA0000.toInt(), 2 to 0xFF00AA00.toInt())
    private fun argbOf(work: Int) = colors[work] ?: 0xFF888888.toInt()

    /** 正常情况下外圈内圈同色；近白主题色才会不同（见 DotField.Group）。 */
    private fun colorOf(work: Int) = DotField.packColors(argbOf(work), argbOf(work))
    private val fallback = DotField.packColors(0xFF888888.toInt(), 0xFF888888.toInt())

    private fun build(points: List<ScenePoint>, zoom: Double = this.zoom) =
        DotField.build(points, zoom, ::colorOf, fallback, MapSceneHitTest.MIN_HIT_RADIUS_DP)

    /** 一个 dp 换多少经度（低纬度近似；测试点都在同一纬度上排开）。 */
    private fun degreesPerDp(zoom: Double) = 360.0 / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom))

    private fun worldPerDp(zoom: Double) = 1.0 / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom))

    /** 绘制圆盘的外缘半径（世界坐标）。 */
    private fun drawnRadius(zoom: Double) =
        (MapMarkerMetrics.dotRadius(zoom) + MapMarkerMetrics.dotStrokeWidth(zoom)) * worldPerDp(zoom)

    @Test
    fun groupsAreContiguousAndCoverEveryPoint() {
        // 彼此拉开 10 倍冲突距离 ⇒ 全部落在第 0 趟，分组语义与分趟无关。
        val step = degreesPerDp(zoom) * DotPasses.conflictDistanceDp(zoom) * 10
        val points = listOf(1, 2, 1, 2, 1).mapIndexed { i, work ->
            point("p$i", work, lng = 139.0 + step * i)
        }
        val field = build(points)

        assertEquals(5, field.count)
        assertEquals(1, field.passes.size)
        val groups = field.passes.single().groups
        assertEquals(2, groups.size)
        var expected = 0
        for (group in groups) {
            assertEquals("组必须首尾相接", expected, group.offset)
            expected += group.count
        }
        assertEquals(5, expected)
        assertEquals(setOf(argbOf(1), argbOf(2)), groups.map { it.outerArgb }.toSet())
        assertEquals(3, groups.first { it.outerArgb == argbOf(1) }.count)
    }

    /**
     * 重叠的圆点必须落在不同趟。这条正是真机反馈 #4（重叠处白边消失）的护栏 ——
     * 旧的单趟画法在这里必然失败。趟内不重叠的证明在 [DotPassesTest]，
     * 这里只钉住 [DotField] 确实把分趟结果带出来了。
     */
    @Test
    fun overlappingPointsLandInDifferentPasses() {
        val nudge = degreesPerDp(zoom) * DotPasses.conflictDistanceDp(zoom) * 0.3
        val field = build(
            listOf(
                point("a", 1, lng = 139.0),
                point("b", 1, lng = 139.0 + nudge),
                point("c", 1, lng = 139.0 + nudge * 2),
            )
        )
        assertEquals(3, field.count)
        assertTrue("重叠的三点应当分到多趟，实际 ${field.passes.size}", field.passes.size >= 2)
        // 趟必须首尾相接地铺满整条数组，否则绘制会漏点或重画。
        var expected = 0
        for (pass in field.passes) {
            assertEquals(expected, pass.offset)
            expected += pass.count
        }
        assertEquals(3, expected)
    }

    @Test
    fun worldCoordinatesMatchTheProjection() {
        val field = build(listOf(point("a", 1, lat = 35.6812, lng = 139.7671)))
        assertEquals(MapProjection.worldX(139.7671), field.world[0], 1e-12)
        assertEquals(MapProjection.worldY(35.6812), field.world[1], 1e-12)
    }

    /** 命中测试打的是**画出去的那份快照**，不是重查索引。 */
    @Test
    fun nearestFindsPointsWithinTheHitRadius() {
        val field = build(listOf(point("a", 1, lat = 35.0, lng = 139.0)))
        val radius = MapSceneHitTest.hitRadiusDp(zoom) / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom))

        val inside = field.nearest(
            MapProjection.worldX(139.0) + radius * 0.5,
            MapProjection.worldY(35.0),
            radius,
            drawnRadius(zoom),
        )
        assertEquals("a", inside?.id)

        assertNull(
            field.nearest(
                MapProjection.worldX(139.0) + radius * 2,
                MapProjection.worldY(35.0),
                radius,
                drawnRadius(zoom),
            )
        )
    }

    /** 同距时留孤立度高的那个 —— 确定性，且与碰撞解的取舍同向。 */
    @Test
    fun nearestPrefersTheMoreIsolatedPointOnTies() {
        val field = build(
            listOf(
                point("low", 1, lat = 35.0, lng = 139.0, priority = 10),
                point("high", 1, lat = 35.0, lng = 139.0, priority = 900),
            )
        )
        val radius = MapSceneHitTest.hitRadiusDp(zoom) / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom))
        val hit = field.nearest(MapProjection.worldX(139.0), MapProjection.worldY(35.0), radius, drawnRadius(zoom))
        assertNotNull(hit)
        assertEquals("high", hit?.id)
    }

    @Test
    fun emptyInputsProduceTheEmptyField() {
        assertTrue(build(emptyList()).isEmpty)
        assertTrue(DotField.EMPTY.isEmpty)
        assertNull(DotField.EMPTY.nearest(0.5, 0.5, 1.0, 1.0))
    }

    /** 世界坐标必须是 Double —— Float 在 z18+ 会有亚像素到数像素的位置误差。 */
    @Test
    fun worldArrayIsDoublePrecision() {
        val field = build(listOf(point("a", 1, lat = 35.6812, lng = 139.7671)))
        val asFloat = field.world[0].toFloat().toDouble()
        assertTrue("Double 与 Float 应当有可测差异", field.world[0] != asFloat)
    }

    /**
     * 密到分不开而**不画**的点（hidden）不可点中 —— 看不见的东西点不中，
     * 否则密集处 tap 会打开一张「不知道哪来的」卡片（真机反馈「点不准」的一半）。
     */
    @Test
    fun hiddenDotsAreNotHittable() {
        // 33+ 个点完全重合 ⇒ 超出 MAX_PASSES 的必然 hidden。
        val points = (0 until DotPasses.MAX_PASSES + 8).map {
            point("p$it", 1, lat = 35.0, lng = 139.0, priority = 1000 - it)
        }
        val field = build(points)
        assertTrue(field.hiddenCount > 0)
        val radius = MapSceneHitTest.hitRadiusDp(zoom) / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom))
        val hit = field.nearest(MapProjection.worldX(139.0), MapProjection.worldY(35.0), radius, drawnRadius(zoom))
        assertNotNull(hit)
        // 命中的必须是**画出来的**：hidden 段在数组尾部。
        val drawnIds = (0 until field.count - field.hiddenCount).map { field.points[it].id }.toSet()
        assertTrue("命中了没画的点 ${hit?.id}", hit!!.id in drawnIds)
    }

    /**
     * tap 落在两个圆盘的交叠处 ⇒ 取**趟号高**（视觉上层）的那个，哪怕另一个圆心更近
     * —— tap 那个像素上显示的就是它（真机反馈「点不准」的另一半）。
     */
    @Test
    fun aTapOnOverlappingDiscsHitsTheVisuallyTopDot() {
        val drawn = drawnRadius(zoom)
        // 两点圆心距 = 1.2×r_drawn：圆盘大面积交叠，但仍是两个点。
        // top(priority 高) 在左，under 在右；tap 点取在交叠区里**偏 under 一侧**。
        val step = drawn * 1.2 * 360.0 // world→经度
        val top = point("top", 1, lat = 35.0, lng = 139.0, priority = 900)
        val under = point("under", 2, lat = 35.0, lng = 139.0 + step, priority = 10)
        val field = build(listOf(top, under))
        assertEquals("两点必须分了趟", 2, field.passes.size)

        val radius = MapSceneHitTest.hitRadiusDp(zoom) / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom))
        // tap 离 under 圆心 0.5×r_drawn、离 top 圆心 0.7×r_drawn：两盘都盖住 tap，under 更近。
        val tapX = MapProjection.worldX(139.0) + drawn * 0.7
        val hit = field.nearest(tapX, MapProjection.worldY(35.0), radius, drawn)
        assertEquals("该命中视觉上层的 top，即便 under 的圆心更近", "top", hit?.id)
    }

    /** tap 在所有圆盘之外、热区之内 ⇒ 退回「最近的已绘制点」。 */
    @Test
    fun aTapOutsideEveryDiscFallsBackToTheNearestDot() {
        val drawn = drawnRadius(zoom)
        val field = build(listOf(point("a", 1, lat = 35.0, lng = 139.0)))
        val radius = MapSceneHitTest.hitRadiusDp(zoom) / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom))
        val hit = field.nearest(
            MapProjection.worldX(139.0) + (drawn + radius) / 2,
            MapProjection.worldY(35.0),
            radius,
            drawn,
        )
        assertEquals("a", hit?.id)
    }

    /** 同输入必得同输出 —— 否则圆点的层序会在相邻两帧之间闪。 */
    @Test
    fun buildIsDeterministic() {
        val nudge = degreesPerDp(zoom) * DotPasses.conflictDistanceDp(zoom) * 0.4
        val points = (0 until 40).map { point("p$it", it % 3 + 1, lng = 139.0 + nudge * it) }
        val a = build(points)
        val b = build(points.shuffled())
        assertEquals(a.count, b.count)
        assertEquals(a.passes.size, b.passes.size)
        assertEquals(a.points.map { it.id }, b.points.map { it.id })
    }
}

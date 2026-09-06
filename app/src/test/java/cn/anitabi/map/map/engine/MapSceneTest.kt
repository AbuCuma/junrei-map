package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.cos
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景的不变式。
 *
 * 这三条正是真机反馈 #2 #3 的根因所在：此前标注与圆点由两条互不知情的循环各自决定，
 * 于是「气球出现了圆点还在」「气球被邻近的圆点盖住」。现在它们由同一次
 * [MapEngine.scene] 产出，不变式在构造过程里就成立。
 */
class MapSceneTest {

    private val zoom = 16.0
    private val lat = 35.0
    private val lng = 139.0

    /** 一个 dp 折合多少纬度（局部线性，与 MapEngine.footprintBox 同一套近似）。 */
    private val degreesPerDpLat =
        1.0 / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom) / 360.0 / cos(Math.toRadians(lat)))

    private fun point(id: String, work: Int, latOffsetDp: Double, priority: Int, image: String? = null) =
        ScenePoint(
            id = id,
            bangumiId = work,
            lat = lat + latOffsetDp * degreesPerDpLat,
            lng = lng,
            priority = priority,
            image = image,
        )

    /**
     * `photo` 会被提升为剧照牌（z16 ＞ PHOTO_REVEAL_ZOOM，且 priority ≥ 3·2^(19−16)＝24）。
     * `under` 落在它的足迹里（小泡从锚点向上 44.5dp），`clear` 在足迹之外。
     */
    private fun engine(): MapEngine = MapEngine().apply {
        load(
            MapDataset(
                points = listOf(
                    point("photo", 1, latOffsetDp = 0.0, priority = 5_000, image = "/p.jpg"),
                    point("under", 1, latOffsetDp = 10.0, priority = 3),
                    point("clear", 1, latOffsetDp = 200.0, priority = 4),
                ),
                works = listOf(
                    MapWorkSeed(1, LatLon(lat, lng), "作品1", null, hasIcon = false, spread = 5_000),
                ),
                version = 1,
            )
        )
    }

    private fun sceneOf(engine: MapEngine, selected: String? = null, focused: Int? = null) =
        engine.scene(
            MapEngineRequest(
                viewport = MapViewport(lat - 1, lat + 1, lng - 1, lng + 1),
                zoom = zoom,
                selectedPointId = selected,
                focusedWorkId = focused,
            ),
            dotColors = { DotField.packColors(it, it) },
            minHitRadiusDp = MapSceneHitTest.MIN_HIT_RADIUS_DP,
        )

    private fun MapScene.dotIds() = dots.points.map { it.id }.toSet()

    /** 一个点在一帧里只有一种形态。 */
    @Test
    fun everyPointHasExactlyOneForm() {
        val scene = sceneOf(engine())
        val annotationIds = scene.annotations.map { it.point.id }.toSet()
        assertTrue("photo 应当是标注", "photo" in annotationIds)
        assertTrue(
            "同一个点不能既是标注又是圆点：${annotationIds intersect scene.dotIds()}",
            (annotationIds intersect scene.dotIds()).isEmpty(),
        )
    }

    /**
     * 选中的点变成气球（自绘层，不是 Marker、不是圆点）—— 真机反馈 #2
     * （点击后气球出现、圆点本身不消失）的直接护栏。
     */
    @Test
    fun theSelectedPointBecomesTheBalloonAndLeavesTheDotField() {
        val engine = engine()
        assertTrue("选中之前它是圆点", "clear" in sceneOf(engine).dotIds())

        val selected = sceneOf(engine, selected = "clear")
        assertEquals("clear", selected.balloon?.id)
        assertTrue("气球不进 Marker 场", selected.annotations.none { it.point.id == "clear" })
        assertFalse("选中的点不能还留在圆点场里", "clear" in selected.dotIds())
        assertEquals("clear", selected.selectedPointId)
    }

    /**
     * **锁定区的回归测试**：气球周围的圆点必须照画 —— 气球画在圆点之上（叠加层内后画），
     * 不需要足迹扫除。旧代码对气球扫外扩足迹，选中后 ~49×59dp 内的点全部不画不可点，
     * 表现为「选中一个点后再点它旁边的点没反应」。
     */
    @Test
    fun dotsNextToTheBalloonStayDrawnAndHittable() {
        // 邻点在锚点**右侧** 20dp：头圆（心在锚点上方 26dp、半径 16dp）盖不到,
        // 但整个落在旧的外扩足迹（半宽 16+halo≈24dp）和旧 GMS 位图框（半宽 22dp）里。
        val degreesPerDpLng = 360.0 / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom))
        val engine = MapEngine().apply {
            load(
                MapDataset(
                    points = listOf(
                        point("sel", 1, latOffsetDp = 0.0, priority = 100),
                        ScenePoint(
                            id = "neighbour", bangumiId = 1,
                            lat = lat, lng = lng + 20.0 * degreesPerDpLng,
                            priority = 50,
                        ),
                    ),
                    works = emptyList(),
                    version = 1,
                )
            )
        }
        val scene = sceneOf(engine, selected = "sel")
        assertTrue("气球旁 20dp 的邻点必须照画", "neighbour" in scene.dotIds())

        val neighbour = scene.dots.points.single { it.id == "neighbour" }
        val hit = MapSceneHitTest.at(neighbour.lat, neighbour.lng, scene)
        assertEquals("旧代码这里被气球锁死", "neighbour", hit?.id)

        // 气球头上 tap 命中选中点自己。
        val headLat = lat + 26.0 * degreesPerDpLat
        assertEquals("sel", MapSceneHitTest.at(headLat, lng, scene)?.id)

        // 头圆之外、旧矩形的尾侧角（横向 12dp、纵向锚点上方 4dp）：不属于气球。
        val cornerLng = lng + 12.0 * degreesPerDpLng
        val cornerLat = lat + 4.0 * degreesPerDpLat
        val cornerHit = MapSceneHitTest.at(cornerLat, cornerLng, scene)
        assertTrue("尾侧角不该命中气球", cornerHit?.id != "sel")
    }

    /**
     * 被标注**压住**的圆点也不画 —— 自绘层在所有 Marker 之上，
     * 光按 id 排除挡不住邻近的点（真机反馈 #3：气球被圆点盖住）。
     */
    @Test
    fun dotsCoveredByAnAnnotationAreNotDrawn() {
        val scene = sceneOf(engine())
        assertFalse("落在剧照牌足迹里的点不该画", "under" in scene.dotIds())
        assertTrue("足迹之外的点照画", "clear" in scene.dotIds())
        assertTrue("遮挡要记账", scene.diagnostics.occludedDots >= 1)

        // 改动之前的规则是「只按 id 排除已经有 Marker 的点」。同一组数据喂给它，
        // `under` 会活下来 —— 也就是这条护栏确实拦得住旧画法。
        val everything = ArrayList<ScenePoint>()
        engine().index.query(MapViewport(lat - 1, lat + 1, lng - 1, lng + 1), -1.0, null, everything)
        val idOnly = everything.map { it.id } - scene.annotations.map { it.point.id }.toSet()
        assertTrue("按 id 排除本应漏掉 under", "under" in idOnly)
    }

    /** 足迹外扩了一个圆点半径，所以「白环压在卡片边缘上」也挡掉。 */
    @Test
    fun theFootprintIsInflatedByTheDotHalo() {
        val plate = MarkerFootprint.photo(MarkerFootprint.photoPlate(zoom))
        val halo = MapMarkerMetrics.dotRadius(zoom) + MapMarkerMetrics.dotStrokeWidth(zoom)
        // 刚好落在小泡上缘之外、但白环仍会压上去的位置。
        val justOutside = -plate.minY + halo * 0.5
        val engine = MapEngine().apply {
            load(
                MapDataset(
                    points = listOf(
                        point("photo", 1, 0.0, priority = 5_000, image = "/p.jpg"),
                        point("halo", 1, justOutside, priority = 3),
                    ),
                    works = emptyList(),
                    version = 1,
                )
            )
        }
        assertFalse("白环会压在卡片边缘上，同样要挡", "halo" in sceneOf(engine).dotIds())
    }

    /**
     * 作品模式忽略 zoom 规则 —— 低 zoom 也要看得见该作品的点。
     *
     * 关掉剧照层来隔离这条规则：z9 的小泡足迹（44.5dp）在地理上有 0.1° 之巨，
     * 本测试的三个点全落在里面，会被正当地判为「被小泡压住」，从而遮蔽掉要测的东西。
     */
    @Test
    fun workModeDrawsDotsBelowTheFullDensityZoom() {
        val engine = engine()
        fun sceneAt(focused: Int?) = engine.scene(
            MapEngineRequest(
                MapViewport(lat - 1, lat + 1, lng - 1, lng + 1),
                zoom = 9.0,
                focusedWorkId = focused,
                isPhotoLayerVisible = false,
            ),
            dotColors = { DotField.packColors(it, it) },
            minHitRadiusDp = MapSceneHitTest.MIN_HIT_RADIUS_DP,
        )
        assertTrue("浏览时 z9 一个单点都不画", sceneAt(null).dots.isEmpty)

        val focused = sceneAt(1)
        assertTrue(focused.filtered)
        assertFalse("作品模式下 z9 也要画", focused.dots.isEmpty)
    }

    /**
     * 命中测试只可能返回**屏幕上存在的东西**：画出去的圆点，或标注本身。
     *
     * 标注的锚点处没有圆点（被足迹剔除），但 tap 在那里必须命中**该标注的点**
     * —— 这是死区兜底：标注的外扩足迹比 GMS marker 的位图大一圈，
     * 不兜底的话那一圈会被当成空白点击、把已开的卡片关掉（真机反馈「点击失效」）。
     */
    @Test
    fun aTapOnAnAnnotationFootprintHitsThatAnnotation() {
        val scene = sceneOf(engine(), selected = "clear")
        assertTrue(scene.annotations.isNotEmpty())
        for (annotation in scene.annotations) {
            val hit = MapSceneHitTest.at(annotation.point.lat, annotation.point.lng, scene)
            assertEquals("锚点处应命中标注自己", annotation.point.id, hit?.id)
        }
    }

    /** 死区本体：泡的外扩圈内、位图与圆点都不在的位置，也要命中该标注。 */
    @Test
    fun aTapInTheInflatedRingAroundAPlateStillHits() {
        val scene = sceneOf(engine())
        val photo = scene.annotations.single { it.point.id == "photo" }
        val plate = MarkerFootprint.photo(MarkerFootprint.photoPlate(scene.zoom))
        val halo = MapMarkerMetrics.dotRadius(scene.zoom) + MapMarkerMetrics.dotStrokeWidth(scene.zoom)
        // 位图右缘再往外半个 halo：位图外、外扩圈内。
        val dpPerLng = MapProjection.WORLD_TILE_SIZE * 2.0.pow(scene.zoom) / 360.0
        val tapLng = photo.point.lng + (plate.maxX + halo * 0.5) / dpPerLng
        val tapLat = photo.point.lat + (-plate.minY / 2) / (dpPerLng / cos(Math.toRadians(photo.point.lat)))
        val hit = MapSceneHitTest.at(tapLat, tapLng, scene)
        assertEquals("photo", hit?.id)
    }

    /** 圆点优先于足迹兜底：tap 落在画出来的圆点上时命中圆点，不被邻近标注抢走。 */
    @Test
    fun aDrawnDotBeatsTheAnnotationFallback() {
        val scene = sceneOf(engine())
        val hit = MapSceneHitTest.at(
            scene.dots.points[0].lat, scene.dots.points[0].lng, scene,
        )
        assertEquals(scene.dots.points[0].id, hit?.id)
    }

    @Test
    fun debugLineMentionsTheCountsThatMatter() {
        val line = sceneOf(engine(), selected = "clear").debugLine()
        assertTrue(line, line.contains("dots="))
        assertTrue(line, line.contains("pass="))
        assertTrue(line, line.contains("occl="))
        assertTrue(line, line.contains("bal1"))
    }
}

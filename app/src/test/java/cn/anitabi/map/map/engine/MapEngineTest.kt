package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEngineTest {

    /**
     * `MapEngine.scene` のうち標注側だけを見るテスト用ショートカット。
     * 圆点の色は本テストの関心事ではないので、作品 ID をそのまま ARGB に流し込む。
     */
    private fun MapEngine.sceneOf(request: MapEngineRequest): MapScene = scene(
        request,
        dotColors = { DotField.packColors(it, it) },
        minHitRadiusDp = MapSceneHitTest.MIN_HIT_RADIUS_DP,
    )

    private fun point(
        id: String,
        bangumiId: Int,
        lat: Double,
        lng: Double,
        priority: Int,
        image: String? = null,
    ) = ScenePoint(id, bangumiId, lat, lng, priority, image = image)

    private fun seed(id: Int, lat: Double, lng: Double, spread: Int) =
        MapWorkSeed(id, LatLon(lat, lng), "作品$id", null, hasIcon = false, spread = spread)

    private val tokyoViewport = MapViewport(35.0, 36.4, 139.0, 140.5)

    /** 東京圏の点：孤立点(priority 5000)・中密度(200)・高密度(30, 8)。 */
    private fun engine(): MapEngine {
        val e = MapEngine()
        e.load(
            MapDataset(
                points = listOf(
                    point("iso", 1, 35.6, 139.7, priority = 5_000, image = "/i.jpg"),
                    point("mid", 1, 35.7, 139.8, priority = 200, image = "/m.jpg"),
                    point("dense-a", 2, 35.68, 139.76, priority = 30),
                    point("dense-b", 2, 35.681, 139.761, priority = 8, image = "/b.jpg"),
                    point("osaka", 3, 34.69, 135.50, priority = 900),
                ),
                works = listOf(
                    seed(1, 35.65, 139.75, spread = 5_000),
                    seed(2, 35.68, 139.76, spread = 30),
                    seed(3, 34.69, 135.50, spread = 900),
                ),
                version = 1,
            )
        )
        return e
    }

    /**
     * 圆点は Marker ではなくなった（[cn.anitabi.map.map.google.PointDotOverlay] の自前描画層が描く）
     * ので、ブラウズ中の frame.annotations は「剧照に昇格した点」だけになる。
     * 昇格しない zoom では 1 つも Marker が要らない。
     */
    @Test
    fun browseFrameCarriesOnlyPhotoMarkers() {
        val e = engine()
        for (zoom in listOf(8.0, 12.0, 14.0)) {
            val frame = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = zoom))
            assertTrue("z$zoom に Marker は要らない", frame.annotations.isEmpty())
        }
        // z16 なら剧照閾値を超える画像点だけが Marker になる
        val z16 = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 16.0))
        assertEquals(setOf("iso", "mid"), z16.annotations.map { it.point.id }.toSet())
        assertTrue(z16.annotations.all { it.usesPhoto })
    }

    /**
     * 圆点の可視性は [MapRevealLadder.dotFieldOpacity] が決める（z<12 は一つも描かず、
     * 聚合は作品標に任せる）。索引側は「見えるとき」に視口内を一つも間引かないことだけを保証する。
     */
    @Test
    fun dotFieldCoversEveryPointInViewWhenVisible() {
        val e = engine()
        val out = ArrayList<ScenePoint>()
        e.index.query(tokyoViewport, -1.0, null, out)
        // 大阪は視口外なので入らない
        assertEquals(setOf("iso", "mid", "dense-a", "dense-b"), out.map { it.id }.toSet())

        // z<12 では層ごと非表示 —— 索引を引くまでもない
        assertEquals(0f, MapRevealLadder.dotFieldOpacity(11.0, filtered = false), 0f)
        assertEquals(1f, MapRevealLadder.dotFieldOpacity(12.0, filtered = false), 0f)
    }

    @Test
    fun workMarkersDisappearAtZoom13() {
        val e = engine()
        // z5：作品閾値 70000 → spread 5000 では出ない → 0 件
        assertTrue(e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 5.0)).works.isEmpty())
        // z12.5：閾値 450 → spread 5000 の作品 1 だけ（30 の作品 2 は落ちる）
        val z12 = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 12.5))
        assertEquals(listOf(1), z12.works.map { it.id })
        // z13：層ごと消える（Web maxzoom: 13）
        assertTrue(e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 13.0)).works.isEmpty())
    }

    @Test
    fun focusedWorkBypassesLadderAndYieldsHullCoordinates() {
        val e = engine()
        // 作品 2 の点は z8 の阶梯では絶対出ないが、作品モードでは阶梯を跳ばして出る。
        // ただし z8 では 2 点が画面上で重なるため、衝突解決が dense-b（画像持ち→剧照昇格）を
        // 残して dense-a を隠す —— MapKit 衝突の等価挙動。
        val z8 = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 8.0, focusedWorkId = 2))
        assertEquals(listOf("dense-b"), z8.annotations.map { it.point.id })
        assertTrue(z8.annotations.single().usesPhoto)
        // 凸包用の座標は衝突と無関係に全点
        assertEquals(2, z8.focusedWorkCoordinates!!.size)
        // 作品モードでは作品標は出さない
        assertTrue(z8.works.isEmpty())

        // z17 でも Marker になるのは画像を持つ dense-b だけ。dense-a は画像が無いので
        // タイル層の圆点として描かれる（Marker 場には現れない）。
        val z17 = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 17.0, focusedWorkId = 2))
        assertEquals(setOf("dense-b"), z17.annotations.map { it.point.id }.toSet())
        // 両方ともタイル層には出る
        val out = ArrayList<ScenePoint>()
        e.index.query(tokyoViewport, -1.0, setOf(2), out)
        assertEquals(setOf("dense-a", "dense-b"), out.map { it.id }.toSet())
    }

    @Test
    fun selectedPointIsNeverDropped() {
        val e = engine()
        // z8 の阶梯では dense-b は出ないが、選択中なら气球として必ず存在する。
        // （气球は Marker ではなく自绘层 —— annotations には入らない。）
        val frame = e.sceneOf(
            MapEngineRequest(tokyoViewport, zoom = 8.0, selectedPointId = "dense-b")
        )
        assertEquals("dense-b", frame.balloon?.id)
        assertTrue(frame.annotations.none { it.point.id == "dense-b" })
    }

    // MARK: 巡礼记录过滤

    private fun MapScene.dotIds() = dots.points.map { it.id }.toSet()

    @Test
    fun visitedOnlyKeepsVisitedDotsAndPhotosOnly() {
        val e = engine()
        val visited = setOf("mid", "dense-a")
        // z17 作品 2 模式:dense-b(有图)本会升格为剧照牌,dense-a 是圆点。
        val scene = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 17.0, focusedWorkId = 2,
                visitFilter = PointVisitFilter.VisitedOnly, visitedPointIds = visited,
            ),
        )
        assertTrue("未完成的 dense-b 不该成为剧照牌", scene.annotations.none { it.point.id == "dense-b" })
        assertEquals(setOf("dense-a"), scene.dotIds())
        // 反过来
        val inverse = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 17.0, focusedWorkId = 2,
                visitFilter = PointVisitFilter.UnvisitedOnly, visitedPointIds = visited,
            ),
        )
        assertEquals(setOf("dense-b"), inverse.annotations.map { it.point.id }.toSet())
        assertFalse("已完成的 dense-a 不该出现", "dense-a" in inverse.dotIds())
    }

    @Test
    fun allFilterIsIdenticalToNoFilter() {
        val e = engine()
        val plain = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 17.0, focusedWorkId = 2))
        val all = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 17.0, focusedWorkId = 2,
                visitFilter = PointVisitFilter.All, visitedPointIds = setOf("dense-a", "dense-b"),
            ),
        )
        assertEquals(plain.dotIds(), all.dotIds())
        assertEquals(plain.annotations.map { it.point.id }, all.annotations.map { it.point.id })
    }

    @Test
    fun workMarkersFollowTheVisitFilter() {
        val e = engine()
        // z12.5 只有作品 1 过阶梯阈值。「只看已完成」但作品 1 一个点都没打过 → 作品标消失。
        val none = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 12.5,
                visitFilter = PointVisitFilter.VisitedOnly, visitedPointIds = setOf("dense-a"),
            ),
        )
        assertTrue(none.works.isEmpty())
        // 打过作品 1 的一个点 → 作品标回来。
        val some = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 12.5,
                visitFilter = PointVisitFilter.VisitedOnly, visitedPointIds = setOf("iso"),
            ),
        )
        assertEquals(listOf(1), some.works.map { it.id })
        // 「只看未完成」:作品 1 两个点都打过 → 消失;只打一个 → 仍在。
        val done = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 12.5,
                visitFilter = PointVisitFilter.UnvisitedOnly, visitedPointIds = setOf("iso", "mid"),
            ),
        )
        assertTrue(done.works.isEmpty())
        val partial = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 12.5,
                visitFilter = PointVisitFilter.UnvisitedOnly, visitedPointIds = setOf("iso"),
            ),
        )
        assertEquals(listOf(1), partial.works.map { it.id })
    }

    @Test
    fun visitFilterNeverHidesTheSelectedBalloon() {
        val e = engine()
        val scene = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 17.0, focusedWorkId = 2, selectedPointId = "dense-b",
                visitFilter = PointVisitFilter.VisitedOnly, visitedPointIds = emptySet(),
            ),
        )
        assertEquals("dense-b", scene.balloon?.id)
    }

    @Test
    fun unknownVisitedIdsAreIgnored() {
        val e = engine()
        val scene = e.sceneOf(
            MapEngineRequest(
                tokyoViewport, zoom = 12.5,
                visitFilter = PointVisitFilter.VisitedOnly, visitedPointIds = setOf("gone-1", "iso"),
            ),
        )
        assertEquals(listOf(1), scene.works.map { it.id })
    }

    @Test
    fun photoFormsGlobalPathAboveRevealZoom() {
        val e = engine()
        // z16（ブラウズ）：photoCardPriorityThreshold = 3×2^3 = 24 → priority 5000/200 の画像点が昇格
        val frame = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 16.0))
        val promoted = frame.annotations.filter { it.usesPhoto }.map { it.point.id }.toSet()
        assertEquals(setOf("iso", "mid"), promoted)

        // z14（reveal 未満）：ブラウズでは 1 枚も出ない
        val below = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 14.0))
        assertTrue(below.annotations.none { it.usesPhoto })
    }

    @Test
    fun photoFormsThemePathRemeasuresWithinWork() {
        val e = engine()
        // 作品 1 モード z10：小泡閾値 400m。作品 1 の画像点 iso/mid は互いに約 13km 離れて
        // いるので、作品内で測り直した priority は両方 400 を大きく超え、両方昇格する。
        val frame = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 10.0, focusedWorkId = 1))
        val promoted = frame.annotations.filter { it.usesPhoto }.map { it.point.id }.toSet()
        assertEquals(setOf("iso", "mid"), promoted)
    }

    @Test
    fun themePrioritiesUseNearestSeenDistance() {
        val e = engine()
        val values = e.themePriorities(setOf(1))
        // 2 点だけ：どちらかが 99999×111000 の初期値側、もう一方が相互距離
        assertEquals(2, values.size)
        val mutual = values.values.min()
        // iso(35.6,139.7) ↔ mid(35.7,139.8)：deg 距離 ≈ 0.1414 → ×111000 ≈ 15.7km
        assertTrue("mutual=$mutual", mutual in 15_000..16_500)
    }

    /** 圆点の上限は無くなった（タイル層は全部描く）。残るのは剧照の枚数上限だけ。 */
    @Test
    fun photoLimitKeepsIsolatedPhotos() {
        val e = MapEngine()
        // 全部画像持ち・十分に離して衝突しないようにする（衝突は枚数上限とは別の関門）。
        val many = (0 until 700).map {
            point("p$it", 1, 35.0 + it * 0.01, 139.0 + it * 0.01, priority = 100 + it, image = "/i.jpg")
        }
        e.load(MapDataset(many, emptyList(), version = 1))
        val frame = e.sceneOf(
            MapEngineRequest(MapViewport(30.0, 45.0, 135.0, 150.0), zoom = 19.0, photoLimit = 100)
        )
        assertEquals(100, frame.annotations.size)
        // priority 降順で残る＝孤立点優先
        assertTrue(frame.annotations.all { it.point.priority >= 700 })
    }

    /** 巡礼记录过滤在 photoLimit 截断之前:排在上限之外的已完成点不能被整体挤掉。 */
    @Test
    fun visitFilterAppliesBeforePhotoLimit() {
        val e = MapEngine()
        val many = (0 until 300).map {
            point("p$it", 1, 35.0 + it * 0.01, 139.0 + it * 0.01, priority = 100 + it, image = "/i.jpg")
        }
        e.load(MapDataset(many, emptyList(), version = 1))
        // 已完成的是 priority 最低的三个 —— 先截后筛的话它们不在前 100 名里。
        val visited = setOf("p0", "p1", "p2")
        val scene = e.sceneOf(
            MapEngineRequest(
                MapViewport(30.0, 45.0, 135.0, 150.0), zoom = 19.0, photoLimit = 100,
                visitFilter = PointVisitFilter.VisitedOnly, visitedPointIds = visited,
            ),
        )
        assertEquals(visited, scene.annotations.map { it.point.id }.toSet())
    }

    /**
     * 剧照の閾値は常に揭示阶梯の閾値以上 —— だから「阶梯で絞ってから剧照を選ぶ」必要が無い。
     * Marker 側が阶梯を一切見なくてよいことの、実行可能な根拠。
     */
    @Test
    fun photoThresholdDominatesLadder() {
        for (half in 0..44) {
            val zoom = half / 2.0
            assertTrue(
                "z$zoom",
                MapRevealLadder.photoCardPriorityThreshold(zoom) >= MapRevealLadder.pointThreshold(zoom),
            )
        }
    }

    @Test
    fun declutterKeepsNonOverlappingBySpreadOrder() {
        val e = MapEngine()
        // z10 で 100dp 箱 ≒ 経度 0.49°。0.1° 間隔の 2 作品は重なり、3 つ目は十分離す。
        val seeds = listOf(
            seed(1, 35.0, 139.0, spread = 900),
            seed(2, 35.0, 139.1, spread = 500), // 1 と重なる → 落ちる
            seed(3, 35.0, 143.0, spread = 300),
        )
        val result = e.declutterForTest(seeds, zoom = 10.0, centerLatitude = 35.0, limit = 32)
        assertEquals(listOf(1, 3), result.map { it.id })
    }

    @Test
    fun versionGateSkipsReload() {
        val e = engine()
        val before = e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 12.0)).annotations.size
        // 同じ version で空データを流しても無視される
        e.load(MapDataset(emptyList(), emptyList(), version = 1))
        assertEquals(before, e.sceneOf(MapEngineRequest(tokyoViewport, zoom = 12.0)).annotations.size)
    }


    @Test
    fun markerMetricsInterpolateExponentially() {
        assertEquals(4.0, MapMarkerMetrics.dotRadius(11.0), 1e-9) // 下端で頭打ち
        assertEquals(8.0, MapMarkerMetrics.dotRadius(18.0), 1e-9)
        assertEquals(16.0, MapMarkerMetrics.dotRadius(23.0), 1e-9) // 上端で頭打ち
        val mid = MapMarkerMetrics.dotRadius(15.0)
        assertTrue("mid=$mid", mid > 4.0 && mid < 8.0)
        // 指数補間なので線形中点(6.0)より小さい
        assertTrue(mid < 6.0)
    }
}

class GeoMathTest {

    @Test
    fun hullDropsOutOfRangePointsButKeepsWork() {
        // 海外点は**点単位**で落とす（MyGO!!!!! のロンドン 2 点で暗幕が丸ごと消えていた）。
        val hull = WorkRegionGeometry.hull(
            listOf(LatLon(35.0, 139.0), LatLon(35.01, 139.01), LatLon(51.5, -0.12), LatLon(51.47, -0.49))
        )
        assertTrue(hull != null && hull.size >= 3)
        // 日本側の点は包含、ロンドンは無視（枠が大西洋まで伸びない）
        for (v in hull!!) assertTrue("lng=${v.lng}", v.lng in 130.0..150.0)

        // 全点が海外なら描かない
        assertNull(WorkRegionGeometry.hull(listOf(LatLon(51.5, -0.12))))
        assertNull(WorkRegionGeometry.hull(emptyList()))
    }

    @Test
    fun singlePointBecomesCircle() {
        val hull = WorkRegionGeometry.hull(listOf(LatLon(35.0, 139.0)), bufferMeters = 1_000.0)!!
        assertEquals(36, hull.size)
        // 全頂点が中心から約 1km
        for (v in hull) {
            val d = cn.anitabi.map.data.AnitabiStore.distanceMeters(LatLon(35.0, 139.0), v)
            assertTrue("d=$d", d in 900.0..1_100.0)
        }
    }

    @Test
    fun bufferedHullContainsOriginalPointsWithMargin() {
        val square = listOf(
            LatLon(35.0, 139.0), LatLon(35.01, 139.0),
            LatLon(35.01, 139.01), LatLon(35.0, 139.01),
        )
        val hull = WorkRegionGeometry.hull(square, bufferMeters = 1_000.0)!!
        assertTrue(hull.size >= 4)
        // 元の点も、辺から 500m 外の点も内側
        assertTrue(WorkRegionGeometry.polygonContains(hull, LatLon(35.005, 139.005)))
        assertTrue(WorkRegionGeometry.polygonContains(hull, LatLon(35.0, 138.996)))
        // 2km 外は外側
        assertFalse(WorkRegionGeometry.polygonContains(hull, LatLon(35.005, 138.98)))
    }
}

class MapCameraPlannerTest {

    @Test
    fun zoomFormulaRoundTrips() {
        val z = MapCameraPlanner.zoom(lngDelta = 0.1, widthDp = 400.0)
        assertEquals(0.1, MapCameraPlanner.lngDelta(z, widthDp = 400.0), 1e-9)
        // 幅 360dp・span 360° → z = log2(360×360/(256×360)) = log2(1.40625)
        assertEquals(0.4918, MapCameraPlanner.zoom(360.0, 360.0), 1e-3)
    }

    @Test
    fun flightDurationClampsAndScales() {
        val near = MapCameraPlanner.flightDurationMs(
            LatLon(35.0, 139.0), 0.01, LatLon(35.001, 139.001), 0.01,
        )
        assertEquals(MapCameraPlanner.MIN_FLY_DURATION_MS + 33, near, 40) // ≈0.35s+ε
        val far = MapCameraPlanner.flightDurationMs(
            LatLon(35.0, 139.0), 0.01, LatLon(43.0, 141.0), 14.0,
        )
        assertEquals(MapCameraPlanner.FLY_DURATION_MS, far)
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Long) {
        assertTrue("expected $expected±$tolerance, got $actual", Math.abs(expected - actual) <= tolerance)
    }

    @Test
    fun flyZoomTableMatchesWeb() {
        assertEquals(15.0, MapCameraPlanner.flyZoom(null), 1e-9)
        assertEquals(15.0, MapCameraPlanner.flyZoom(100), 1e-9)
        assertEquals(18.0, MapCameraPlanner.flyZoom(10), 1e-9)
        assertEquals(22.0, MapCameraPlanner.flyZoom(1), 1e-9)
    }

    @Test
    fun fitBoundsDropsOutliers() {
        // 東京の 4 点 + ニューヨークの離群点 1 つ
        val coords = listOf(
            LatLon(35.68, 139.76), LatLon(35.69, 139.77),
            LatLon(35.70, 139.75), LatLon(35.67, 139.78),
            LatLon(40.7, -74.0),
        )
        val bounds = MapCameraPlanner.fitBounds(coords)!!
        assertTrue("maxLng=${bounds.maxLng}", bounds.maxLng < 141.0) // NY が落ちている
        assertTrue(bounds.minLat > 35.0)
    }

    @Test
    fun singlePointGetsMinimumSpan() {
        val bounds = MapCameraPlanner.enclosingBounds(listOf(LatLon(35.0, 139.0)))!!
        // ±400m ≈ ±0.0036°
        assertTrue(bounds.latDelta > 0.007 && bounds.latDelta < 0.008)
    }
}

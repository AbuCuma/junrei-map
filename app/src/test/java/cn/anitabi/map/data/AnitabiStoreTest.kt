package cn.anitabi.map.data

import cn.anitabi.map.data.model.AnitabiDataset
import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.PointGeo
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.data.model.WorkGroupingMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePrefs : AnitabiPrefs {
    override var cachedModified: Double = 0.0
    override var lastVisitedBangumiId: Int = 0
    override var recentVisitIds: List<Int> = emptyList()
    override var mapBaseStyle: String = "standard"
    override var isPhotoLayerVisible: Boolean = true
    override var pointVisitFilter: String = "All"
    override var restoredDeepLink: String? = null
    override var hasCompletedOnboarding: Boolean = false
    override var isnetExperimentEnabled: Boolean = false
}

class AnitabiStoreTest {

    private fun store(): AnitabiStore =
        AnitabiStore(AnitabiDataLoader(File("/nonexistent")), FakePrefs())

    private fun bangumi(
        id: Int,
        cn: String?,
        title: String? = null,
        points: List<PointGeo> = emptyList(),
    ) = BangumiLite(
        id = id, cn = cn, title = title, city = "日本", colorHex = null, cover = null,
        cat = "TV", center = null, zoom = null, points = points,
    )

    private fun point(
        id: String,
        bangumiId: Int,
        lat: Double,
        lng: Double,
        name: String? = null,
        nameCn: String? = null,
        image: String? = null,
        ep: String? = null,
        isFolder: Boolean = false,
        fid: String? = null,
        folderName: String? = null,
        uid: Int? = null,
    ) = ScenePoint(
        id = id, bangumiId = bangumiId, lat = lat, lng = lng, priority = 0,
        name = name, nameCn = nameCn, image = image, ep = ep,
        isFolder = isFolder, fid = fid, folderName = folderName, uid = uid,
    )

    /** 東京駅近辺 2 点 + 京都 1 点 + 座標だけの点 1 つの最小データセット。 */
    private fun dataset(): AnitabiDataset {
        val tokyoPoints = listOf(
            point("t1", 1, 35.6812, 139.7671, name = "東京駅", nameCn = "东京站", image = "/a.jpg", ep = "3"),
            point("t2", 1, 35.6896, 139.7006, name = "新宿", nameCn = "新宿", image = "/b.jpg", ep = "OP"),
            point("t3", 1, 35.7, 139.75, name = "某校門", nameCn = "某学校门口", image = "/c.jpg"),
            point("t4", 1, 35.71, 139.76, image = "/d.jpg"),
        )
        val kyotoPoints = listOf(
            point("k1", 2, 35.0116, 135.7681, name = "鴨川", nameCn = "鸭川"),
        )
        return AnitabiDataset(
            bangumis = listOf(
                bangumi(1, "作品A", "さくひんA", tokyoPoints.map { PointGeo(it.id, it.lat, it.lng, 0) }),
                bangumi(2, "作品B", null, kyotoPoints.map { PointGeo(it.id, it.lat, it.lng, 0) }),
                bangumi(3, "无点作品"),
            ),
            points = tokyoPoints + kyotoPoints,
            modified = 100.0,
            modifiedByBangumi = mapOf(1 to 50.0, 2 to 80.0),
            hasDetails = true,
        )
    }

    @Test
    fun searchMatchesWorksAndPointsCaseInsensitively() {
        val s = store()
        s.apply(dataset())

        val results = s.search("作品", near = null)
        // 作品 3 件（无点作品含む）が点数降順
        val works = results.filterIsInstance<SearchResult.Bangumi>()
        assertEquals(listOf(1, 2, 3), works.map { it.bangumi.id })

        // 地標検索は name + nameCn の両方に効く
        val pointHits = s.search("东京", near = null).filterIsInstance<SearchResult.Point>()
        assertEquals(listOf("t1"), pointHits.map { it.point.id })

        // 位置があれば地標は距離順 + 距離が埋まる
        val nearKyoto = s.search("宿", near = LatLon(35.0116, 135.7681))
        val p = nearKyoto.filterIsInstance<SearchResult.Point>().first()
        assertTrue(p.distanceMeters!! > 100_000)
    }

    /**
     * 换数据的同时搜索,不能崩、也不能返回不属于任何一代的东西。
     *
     * 这是 I1 的回归测试:派生索引曾以普通字段发布(不参与 snapshot),会**先于**它索引的
     * `points` 对其他线程可见;而搜索索引当时存的是 `points` 的下标 —— 于是「新索引 × 旧数据」
     * 直接越界。窗口宽度取决于 apply() 里余下的重建耗时,所以这里用两万点的数据集把它撑开。
     *
     * 诚实说明:改法本身(索引改持有 ScenePoint)让越界**在结构上不可能**,所以修好之后
     * 这里的断言是恒真的;它作为**长期看门狗**的力度有限,价值主要在于当初确实以
     * `IndexOutOfBoundsException: Index 24000 out of bounds for length 20000` 复现过缺陷。
     */
    @Test
    fun searchStaysConsistentWhileDataIsBeingReplaced() = runTest {
        val s = store()
        // 小的一代只有 20000 点;大的一代把命中项**排在 20000 之后** ——
        // 这样「新索引 × 旧数据」一旦发生就必然越界,而不是碰巧落在两代都有效的低位下标上。
        val small = syntheticDataset(count = 20_000, tailMatches = 0)
        val large = syntheticDataset(count = 26_000, tailMatches = 2_000)
        val validIds = large.points.map { it.id }.toHashSet()
        s.apply(small)

        val writer = launch(Dispatchers.Default) {
            repeat(16) { s.apply(if (it % 2 == 0) large else small) }
        }
        // 写入进行中持续搜索。任何一次越界/撕裂都会在这里炸出来。
        while (writer.isActive) {
            for (hit in s.search("尾站", near = null).filterIsInstance<SearchResult.Point>()) {
                assertTrue("搜索返回了不属于任何一代的地标 ${hit.point.id}", hit.point.id in validIds)
            }
        }
        writer.join()
    }

    /**
     * 撑开 apply() 重建窗口用的合成数据集。
     * 末尾 [tailMatches] 个点带独有的「尾站」名,只有它们会被那条并发搜索命中。
     */
    private fun syntheticDataset(count: Int, tailMatches: Int): AnitabiDataset {
        val pts = List(count) { i ->
            val tail = i >= count - tailMatches
            point(
                "p-$count-$i", 1, 35.0 + i % 100 * 0.001, 139.0 + i % 100 * 0.001,
                name = if (tail) "第${i}尾站" else "普通$i",
            )
        }
        return AnitabiDataset(
            bangumis = listOf(bangumi(1, "作品A", "さくひんA", pts.map { PointGeo(it.id, it.lat, it.lng, 0) })),
            points = pts,
            modified = 100.0,
            modifiedByBangumi = mapOf(1 to 50.0),
            hasDetails = true,
        )
    }

    /**
     * `ep` 字面量恰好等于未分组桶的标记时,不能产生两个同 id 的组 ——
     * WorkCardSheet 用 group.id 做 LazyColumn 的 key,重复会直接抛
     * `IllegalArgumentException`,而 ep 是用户投稿字段,取值不可控。
     */
    @Test
    fun episodeNamedLikeTheUnassignedBucketDoesNotCollide() {
        val s = store()
        val pts = listOf(
            point("a", 1, 35.0, 139.0, name = "有 ep", ep = "none"),
            point("b", 1, 35.1, 139.1, name = "无 ep"),
        )
        s.apply(
            AnitabiDataset(
                bangumis = listOf(bangumi(1, "作品A", points = pts.map { PointGeo(it.id, it.lat, it.lng, 0) })),
                points = pts,
                modified = 1.0,
                modifiedByBangumi = emptyMap(),
                hasDetails = true,
            )
        )

        val groups = s.groupedPoints(1, WorkGroupingMode.Episode)
        assertEquals(2, groups.size)
        assertEquals("组 id 必须唯一", groups.size, groups.map { it.id }.toSet().size)
        // 未分组桶仍然可被显示层识别出来
        assertEquals(listOf("b"), groups.single { it.isUnassigned }.points.map { it.id })
    }

    /** 文件夹分组是同一个 bug 类:fid 也是用户投稿字段,不能与 "name:…"/"default:…" 撞 key。 */
    @Test
    fun folderIdsCannotCollideWithTheOtherGroupKinds() {
        val s = store()
        val pts = listOf(
            // fid 恰好写成另一类分组的 id 形式
            point("f1", 9, 35.0, 139.0, name = "怪文件夹", isFolder = true),
            point("m1", 9, 35.0, 139.0, name = "成员", fid = "f1"),
            point("m2", 9, 35.0, 139.0, name = "旧式", folderName = "f1"),
            point("m3", 9, 35.0, 139.0, name = "散点"),
        )
        s.apply(
            AnitabiDataset(
                bangumis = listOf(bangumi(9, "作品九", points = pts.map { PointGeo(it.id, it.lat, it.lng, 0) })),
                points = pts, modified = 0.0, hasDetails = true,
            )
        )

        val groups = s.groupedPoints(9, WorkGroupingMode.Folder)
        assertEquals("组 id 必须唯一", groups.size, groups.map { it.id }.toSet().size)
    }

    @Test
    fun pointLookupByIdIsIndexed() {
        val s = store()
        assertNull(s.point("t1"))
        s.apply(dataset())
        assertEquals("東京駅", s.point("t1")?.name)
        assertEquals(2, s.point("k1")?.bangumiId)
        assertNull(s.point("nope"))
    }

    @Test
    fun cityMatchingAcceptsChineseAndJapaneseNames() {
        val s = store()
        // 金沢中心近くの点で kanazawa タイルを立てる
        val kanazawaPoint = point("kz1", 1, 36.5613, 136.6562, name = "金沢駅")
        s.apply(
            cn.anitabi.map.data.model.AnitabiDataset(
                bangumis = listOf(bangumi(1, "作品A", points = listOf(PointGeo("kz1", 36.5613, 136.6562, 0)))),
                points = listOf(kanazawaPoint),
                modified = 1.0, modifiedByBangumi = emptyMap(), hasDetails = true,
            )
        )
        // 簡体（金泽）・日本語（金沢）どちらでも同じ都市に当たる（真机反馈批次 7）
        assertEquals(listOf("kanazawa"), s.matchingCities("金泽").map { it.id })
        assertEquals(listOf("kanazawa"), s.matchingCities("金沢").map { it.id })
        assertEquals("金泽", s.matchingCities("金沢").first().nameCn)
    }

    @Test
    fun citiesCountPointsWithinRadiusAndDropEmpty() {
        val s = store()
        s.apply(dataset())
        // 東京 4 点・京都 1 点、他の都市は 0 点で消える
        assertEquals(listOf("tokyo", "kyoto"), s.cities.map { it.id })
        assertEquals(4, s.cities[0].pointCount)
        assertEquals(listOf("東京"), s.matchingCities("東").map { it.name })
    }

    @Test
    fun workListsSortByPointsIdAndModified() {
        val s = store()
        s.apply(dataset())
        assertEquals(listOf(1, 2), s.popularWorks.map { it.id }) // 点数降順、无点作品は除外
        assertEquals(listOf(2, 1), s.newlyAddedWorks.map { it.id }) // id 降順
        assertEquals(listOf(2, 1), s.recentlyUpdatedWorks.map { it.id }) // modified 降順
    }

    @Test
    fun groupedByEpisodePutsNumericFirstThenNonNumericThenUnassigned() {
        val s = store()
        s.apply(dataset())
        val groups = s.groupedPoints(1, WorkGroupingMode.Episode)
        assertEquals(listOf("ep:3", "ep:OP", "unassigned"), groups.map { it.id })
        assertTrue(groups.last().isUnassigned)
        assertEquals(listOf("t3", "t4"), groups.last().points.map { it.id })
    }

    @Test
    fun groupedByFolderUsesFidThenFolderNameThenDefault() {
        val s = store()
        val pts = listOf(
            point("f1", 9, 35.0, 139.0, name = "フォルダ甲", nameCn = "文件夹甲", isFolder = true),
            point("m1", 9, 35.0, 139.0, name = "成员1", fid = "f1"),
            point("m2", 9, 35.0, 139.0, name = "旧式", folderName = "旧组"),
            point("m3", 9, 35.0, 139.0, name = "散点"),
        )
        s.apply(
            AnitabiDataset(
                bangumis = listOf(bangumi(9, "作品九", points = pts.map { PointGeo(it.id, it.lat, it.lng, 0) })),
                points = pts, modified = 0.0, hasDetails = true,
            )
        )
        val groups = s.groupedPoints(9, WorkGroupingMode.Folder)
        assertEquals(3, groups.size)
        // フォルダ点は自身が先頭行
        assertEquals(listOf("f1", "m1"), groups[0].points.map { it.id })
        assertEquals("文件夹甲", groups[0].name)
        assertEquals("name:旧组", groups[1].id)
        assertEquals("default:9", groups[2].id)
        assertEquals("作品九", groups[2].name)
    }

    /**
     * 有点位的作品在**两种模式下都必定至少有一个组** —— 两条分组路径各自都有兜底桶。
     *
     * 这是 `WorkCardSheet` 那个「全部折叠」按钮的前提:它此前按 `groups.size > 1` 显示,
     * 于是无 `fid`/`folderName` 的作品(按分组塌成一个 `default:` 组)与无 `ep` 的作品
     * (按话数塌成一个 `unassigned` 组)会让按钮在两种模式之间一闪一灭。改成
     * `groups.isNotEmpty()` 之后,这条不变式就是「按钮不会消失」的依据,钉住它。
     */
    @Test
    fun bothGroupingModesAlwaysProduceAtLeastOneGroupForAWorkWithPoints() {
        val s = store()
        // 既没有 ep、也没有 fid/folderName —— 两种模式都只会落进各自的兜底桶。
        val pts = listOf(
            point("p1", 7, 35.0, 139.0, name = "散点一"),
            point("p2", 7, 35.1, 139.1, name = "散点二"),
        )
        s.apply(
            AnitabiDataset(
                bangumis = listOf(
                    bangumi(7, "作品七", points = pts.map { PointGeo(it.id, it.lat, it.lng, 0) }),
                    bangumi(8, "无点作品"),
                ),
                points = pts, modified = 0.0, hasDetails = true,
            )
        )
        for (mode in WorkGroupingMode.entries) {
            val groups = s.groupedPoints(7, mode)
            assertEquals("$mode 应恰好塌成一个兜底组", 1, groups.size)
            assertEquals("$mode 不能丢点", listOf("p1", "p2"), groups.single().points.map { it.id })
        }
        // 对照:没有点位的作品两种模式都是空的(此时按钮本就不该出现)。
        for (mode in WorkGroupingMode.entries) {
            assertTrue(s.groupedPoints(8, mode).isEmpty())
        }
    }

    @Test
    fun screenshotCountAndRandomWorkThreshold() {
        val s = store()
        s.apply(dataset())
        assertEquals(4, s.screenshotCount(1))
        assertEquals(0, s.screenshotCount(2))
        // スクショ > 3 は作品 1 のみ
        assertEquals(1, s.randomWork()!!.id)
    }

    @Test
    fun viewportAndCityAggregationSortByCount() {
        val s = store()
        s.apply(dataset())
        val inTokyo = s.worksInViewport(LatLonRegion(35.69, 139.75, 0.2, 0.3))
        assertEquals(listOf(1), inTokyo.map { it.first })
        assertEquals(4, inTokyo[0].second)

        val tokyoTile = s.cities.first { it.id == "tokyo" }
        assertEquals(listOf(1 to 4), s.worksInCity(tokyoTile))
    }

    @Test
    fun rememberVisitKeepsMostRecentTenWithoutDuplicates() {
        val s = store()
        s.apply(dataset())
        s.rememberVisit(1)
        s.rememberVisit(2)
        s.rememberVisit(1)
        assertEquals(listOf(1, 2), s.recentVisits)
        assertEquals(1, s.lastVisitedBangumi!!.id)
    }

    @Test
    fun nearestPointsFiltersByMaxDistance() = runTest {
        val ds = dataset()
        // 東京駅から：東京の 4 点は 50km 圏内、京都は圏外
        val result = AnitabiStore.nearestPoints(
            to = LatLon(35.6812, 139.7671), points = ds.points, limit = 30,
            maxDistance = 50_000.0,
        )
        assertEquals(4, result.size)
        assertEquals("t1", result.first().point.id)
        assertTrue(result.first().distanceMeters < 10.0)
        assertNull(result.find { it.point.id == "k1" })
    }
}

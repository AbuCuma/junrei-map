package cn.anitabi.map.data.search

import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.PointGeo
import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 相关度排序与上限截断。 */
class SearchRankingTest {

    private fun work(id: Int, cn: String?, title: String? = null, pointCount: Int = 0) = BangumiLite(
        id = id, cn = cn, title = title, city = "日本", colorHex = null, cover = null,
        cat = "TV", center = null, zoom = null,
        points = List(pointCount) { PointGeo("p$id-$it", 35.0, 139.0, 0) },
    )

    private fun point(id: String, name: String?, nameCn: String? = null, lat: Double = 35.0) =
        ScenePoint(id = id, bangumiId = 1, lat = lat, lng = 139.0, priority = 0, name = name, nameCn = nameCn)

    private fun query(
        index: SearchIndex,
        needle: String,
        workLimit: Int = 10,
        sceneLimit: Int = 50,
        distanceOf: ((ScenePoint) -> Double)? = null,
    ) = index.query(TextFold.fold(needle), workLimit, sceneLimit, distanceOf)

    @Test
    fun exactFieldBeatsPrefixBeatsSubstring() {
        val index = SearchIndex.build(
            listOf(
                work(1, "前缀命中作品"),   // 「前缀」是字段前缀
                work(2, "命中在中间的前缀"), // 「前缀」在字段中间
                work(3, "前缀"),           // 整字段相等
            ),
            emptyList(),
        )
        assertEquals(listOf(3, 1, 2), query(index, "前缀").works.map { it.id })
    }

    /**
     * 上限截断必须发生在**打分之后**。
     *
     * 这是 `AnitabiStore.search` 旧实现的回归测试:它扫到 workLimit 就 `break`,
     * **之后**才 `sortByDescending { points.size }` —— 索引按 g.json 顺序追加,
     * 所以排在数据集后面的热门作品在被排序看到之前就已经被丢掉了。
     * 这里把地标最多的那部**放在最后**,旧实现下它根本进不了结果。
     */
    @Test
    fun popularWorkLateInTheDatasetSurvivesTheCap() {
        val works = List(200) { i -> work(i + 1, "候选作品$i", pointCount = 1) } +
            work(999, "候选作品最热", pointCount = 900)
        val index = SearchIndex.build(works, emptyList())

        val hits = query(index, "候选作品", workLimit = 10).works
        assertEquals(10, hits.size)
        assertEquals("地标最多的作品必须回到第一位", 999, hits.first().id)
    }

    /** 同形回归:最近的地标排在数据集末尾时,也不能在打分前被截掉。 */
    @Test
    fun nearestSpotLateInTheDatasetSurvivesTheCap() {
        val points = List(200) { i -> point("far-$i", "候选地点$i", lat = 40.0) } +
            point("near", "候选地点最近", lat = 35.0)
        val index = SearchIndex.build(emptyList(), points)

        val hits = query(index, "候选地点", sceneLimit = 50) { p -> abs(p.lat - 35.0) * 111_320.0 }
        assertEquals(50, hits.points.size)
        assertTrue("最近的地标必须进入结果", hits.points.any { it.id == "near" })
    }

    @Test
    fun capsAreRespected() {
        val index = SearchIndex.build(
            List(40) { work(it + 1, "作品$it", pointCount = it) },
            List(400) { point("p$it", "地点$it") },
        )
        val hits = query(index, "0")
        assertTrue(hits.works.size <= 10)
        assertTrue(hits.points.size <= 50)
    }

    /**
     * 超常见查询不得退化成「对上万条命中做一次全排序」。
     * 断言的是结果形状;有界性由 TopK 的固定容量保证。
     */
    @Test
    fun veryCommonQueryStaysBounded() {
        val index = SearchIndex.build(emptyList(), List(5_000) { point("p$it", "第${it}の駅") })
        assertEquals(50, query(index, "の").points.size)
    }

    /** 字段越短,同一处命中的信息量越大。 */
    @Test
    fun shorterFieldWinsOnEqualMatchKind() {
        val index = SearchIndex.build(
            emptyList(),
            listOf(
                point("long", "新宿区西新宿二丁目八番一号東京都庁第一本庁舎北展望室"),
                point("short", "新宿"),
            ),
        )
        assertEquals(listOf("short", "long"), query(index, "宿").points.map { it.id })
    }

    /** cat/city 是全库共用值,不能把真正的标题命中挤掉。 */
    @Test
    fun categoryAndRegionDoNotDrownTitleMatches() {
        // 40 部只靠 cat="TV" 命中的作品,外加 1 部标题里真的有 tv 的。
        val index = SearchIndex.build(
            List(40) { work(it + 1, "无关作品$it", pointCount = 50) } + work(99, "tv动画大全"),
            emptyList(),
        )
        assertEquals("标题命中必须排在 cat 命中之前", 99, query(index, "tv").works.first().id)
    }

    @Test
    fun blankQueryReturnsNothing() {
        val index = SearchIndex.build(listOf(work(1, "作品")), listOf(point("p", "地点")))
        for (raw in listOf("", "   ")) {
            val hits = index.query(TextFold.fold(raw), 10, 50)
            assertTrue("空查询不得有命中: [$raw]", hits.works.isEmpty() && hits.points.isEmpty())
        }
    }
}

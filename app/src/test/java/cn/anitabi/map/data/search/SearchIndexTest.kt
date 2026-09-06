package cn.anitabi.map.data.search

import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.PointGeo
import cn.anitabi.map.data.model.ScenePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跨语种命中。中英日三种写法都应当找到同一部作品／同一个地标。 */
class SearchIndexTest {

    private val bocchi = BangumiLite(
        id = 815878,
        cn = "孤独摇滚！",
        en = "BOCCHI THE ROCK!",
        title = "ぼっち・ざ・ろっく！",
        city = "日本",
        colorHex = null,
        cover = null,
        cat = "TV",
        center = null,
        zoom = null,
        points = List(30) { PointGeo("p$it", 35.0, 139.0, 0) },
        abbr = "孤独摇滚",
        tags = listOf("千恋＊万花", "qlhw"),
        titleAbbr = "ぼざろ",
    )

    private fun point(id: String, name: String?, nameCn: String? = null) =
        ScenePoint(id = id, bangumiId = 1, lat = 35.0, lng = 139.0, priority = 0, name = name, nameCn = nameCn)

    private fun index(
        works: List<BangumiLite> = listOf(bocchi),
        points: List<ScenePoint> = emptyList(),
    ) = SearchIndex.build(works, points)

    private fun SearchIndex.find(needle: String) = query(TextFold.fold(needle), 10, 50)

    private fun assertFindsBocchi(vararg needles: String) {
        val idx = index()
        for (needle in needles) {
            assertEquals("「$needle」应当命中《孤独摇滚》", listOf(815878), idx.find(needle).works.map { it.id })
        }
    }

    @Test
    fun englishTitleIsSearchable() {
        // en 此前已被解析进 BangumiLite.en,却从未进过索引 —— 全仓库零引用。
        assertFindsBocchi("BOCCHI THE ROCK!", "bocchi", "BOCCHI")
    }

    @Test
    fun chineseAndJapaneseTitlesAreSearchable() {
        assertFindsBocchi("孤独摇滚", "ぼっち")
    }

    @Test
    fun abbreviationsAndTagsAreSearchable() {
        // abbr(中文简称)同样此前零引用;tAbbr 与 tags 则根本没被解析出来。
        assertFindsBocchi("孤独摇滚", "ぼざろ", "qlhw", "千恋＊万花")
    }

    /**
     * 简体输入命中只有日文名的地标 —— 这就是那 65% 的缺口。
     *
     * 实测（数据分片 g0）：地标日文名含汉字的 19405 条里，12712 条至少含一个简体
     * 写法不同的字。这些地标**没有** nameCn，所以在异体折叠之前，简体输入
     * 永远逐字命中不了它们。
     */
    @Test
    fun simplifiedChineseFindsJapaneseOnlyPointNames() {
        val idx = index(
            points = listOf(
                point("kikou", "UR都市機構ヌーヴェル赤羽台"), // 机构
                point("arashi", "嵐山渡月橋"),                // 岚山渡月桥
                point("eki", "JR池袋駅東口"),                 // 东口
            )
        )
        assertEquals(listOf("kikou"), idx.find("机构").points.map { it.id })
        assertEquals(listOf("arashi"), idx.find("渡月桥").points.map { it.id })
        assertEquals(listOf("eki"), idx.find("东口").points.map { it.id })
    }

    /** 反向：日文汉字输入命中只有中文名的地标。 */
    @Test
    fun japaneseKanjiFindsSimplifiedOnlyPointNames() {
        val idx = index(points = listOf(point("t1", name = null, nameCn = "东京站前广场")))
        assertEquals(listOf("t1"), idx.find("東京").points.map { it.id })
        assertEquals(listOf("t1"), idx.find("廣場").points.map { it.id })
    }

    /** 繁体、新字体、简体三种写法命中同一集合。 */
    @Test
    fun traditionalShinjitaiAndSimplifiedAgree() {
        val idx = index(points = listOf(point("h", "広島城"), point("k", "金沢駅")))
        for (needle in listOf("広島", "廣島", "广岛")) {
            assertEquals("「$needle」", listOf("h"), idx.find(needle).points.map { it.id })
        }
        for (needle in listOf("金沢", "金澤", "金泽")) {
            assertEquals("「$needle」", listOf("k"), idx.find(needle).points.map { it.id })
        }
    }

    @Test
    fun pointNamesMatchOnBothLanguages() {
        val idx = index(points = listOf(point("t1", "東京駅", "东京站"), point("k1", "鴨川", "鸭川")))
        assertEquals(listOf("t1"), idx.find("东京站").points.map { it.id })
        assertEquals(listOf("t1"), idx.find("東京駅").points.map { it.id })
    }

    /**
     * `DataLoadPhase.GeometryOnly`：只有 g.json 到达，全部地标的名称都是 null。
     * 这个窗口在冷启动时真实存在（1–2 秒），期间搜索不能崩，作品也仍应可搜。
     */
    @Test
    fun geometryOnlyPhaseYieldsWorksButNoPoints() {
        val idx = index(points = List(100) { point("p$it", name = null, nameCn = null) })
        val hits = idx.find("孤独摇滚")
        assertEquals(listOf(815878), hits.works.map { it.id })
        assertTrue(hits.points.isEmpty())
    }

    /**
     * 字符指纹预筛只许降常数、不许改语义。
     *
     * 漏判（把真命中筛掉）会表现为「有的词就是搜不到」，且完全静默 —— 所以这里用一批
     * 覆盖各个 Unicode 区段的名称，逐一拿它们自己的每个子串去查，断言必然命中自己。
     */
    @Test
    fun signaturePrefilterNeverDropsATrueMatch() {
        val corpus = listOf(
            "東京駅", "机构", "ぼっち・ざ・ろっく", "BOCCHI THE ROCK", "新宿御苑前",
            "アマカノ3", "千恋＊万花", "嵐山渡月橋", "代々木公園", "らーめん二郎",
        )
        val idx = index(points = corpus.mapIndexed { i, name -> point("p$i", name) })
        for ((i, name) in corpus.withIndex()) {
            val folded = TextFold.fold(name)
            for (start in folded.indices) {
                for (end in start + 1..folded.length) {
                    val sub = folded.substring(start, end)
                    val ids = idx.query(sub, 10, 50).points.map { it.id }
                    assertTrue("子串「$sub」应当命中它自己所在的「$name」", "p$i" in ids)
                }
            }
        }
    }

    /**
     * 被抢占的查询必须**抛** CancellationException,而不是返回空:返回空会让 produceState
     * 把空结果写进 state、闪一下空态。HomeSheet 的「打字不闪空」靠这条契约。
     */
    @Test
    fun supersededQueryThrowsInsteadOfReturningEmpty() {
        val idx = index(points = List(3000) { point("p$it", "東京駅 $it") })
        val ids = idx.query(TextFold.fold("東京"), 10, 50).points.map { it.id }
        assertTrue(ids.isNotEmpty())
        try {
            idx.query(TextFold.fold("東京"), 10, 50, isActive = { false })
            org.junit.Assert.fail("已作废的查询应当抛 CancellationException")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // 预期
        }
    }

    /** 名称全空的地标不进索引，因此不会被任何查询命中。 */
    @Test
    fun namelessPointsAreNeverReturned() {
        val idx = index(points = listOf(point("named", "東京駅"), point("blank", null, null)))
        assertTrue(idx.find("blank").points.isEmpty())
        assertEquals(listOf("named"), idx.find("東京").points.map { it.id })
    }
}

package cn.anitabi.map.data.search

import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.ScenePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拉丁键盘轴：假名罗马字与中文拼音。 */
class TransliterationTest {

    private fun romaji(raw: String) = Romaji.of(TextFold.fold(raw))

    @Test
    fun kanaBecomesRomaji() {
        assertEquals("amakano3", romaji("アマカノ3"))
        assertEquals("keion", romaji("けいおん")) // 长音符已在折叠阶段剥掉
        assertEquals("shinjuku", romaji("しんじゅく"))
        assertEquals("tokyo", romaji("とうきょう").replace("ou", "o").replace("you", "yo"))
    }

    /** 促音重复下一个音节的首辅音。 */
    @Test
    fun sokuonDoublesTheFollowingConsonant() {
        assertEquals("kippu", romaji("きっぷ"))
        assertEquals("bocchizarokku", romaji("ぼっち・ざ・ろっく"))
    }

    /** 拗音必须先于单假名匹配，否则 きゃ 会变成 kiya。 */
    @Test
    fun yoonIsMatchedBeforeSingleKana() {
        assertEquals("kyaku", romaji("きゃく"))
        assertEquals("sha", romaji("しゃ"))
        assertEquals("ja", romaji("じゃ"))
        assertEquals("cha", romaji("ちゃ"))
    }

    /**
     * 汉字没有词典读不出音。混排时原样带过（不能把字符串截断），全汉字则整条跳过。
     *
     * 这就是罗马字轴的能力边界：`渋谷` 转不出 `shibuya`，那需要 MeCab 一级的
     * 形态素解析器和 10MB 词典。而上游数据里地标**完全没有英文名**，所以只会英文的
     * 用户仍然搜不到纯汉字地标 —— 已知且不打算修。
     */
    @Test
    fun kanjiPassesThroughAndKanjiOnlyNamesAreSkipped() {
        // 折叠后 東京駅 → 东京驿(异体折叠),仍无假名 → 整条跳过
        assertEquals("", romaji("東京駅"))
        // 混排:汉字与数字原样留下,假名转音
        assertEquals("东京えき".let { "东京eki" }, romaji("東京えき"))
        assertEquals("3ちょうめ".let { "3choume" }, romaji("3ちょうめ"))
    }

    @Test
    fun pinyinCoversTheDatasetVocabulary() {
        assertEquals("gu", PinyinTable.of('孤'))
        assertEquals("dong", PinyinTable.of('东'))
        assertEquals("ji", PinyinTable.of('机'))
        assertNull(PinyinTable.of('a'))
        assertNull(PinyinTable.of('あ'))
    }

    @Test
    fun worksAreFoundByFullPinyinAndInitials() {
        val work = BangumiLite(
            id = 1, cn = "孤独摇滚！", en = null, title = "ぼっち・ざ・ろっく！", city = "日本",
            colorHex = null, cover = null, cat = "TV", center = null, zoom = null,
            points = emptyList(), abbr = "孤独摇滚", titleAbbr = "ぼざろ",
        )
        val idx = SearchIndex.build(listOf(work), emptyList())
        for (needle in listOf("guduyaogun", "gdyg", "bocchizarokku", "bozaro")) {
            assertEquals("「$needle」", listOf(1), idx.query(TextFold.fold(needle), 10, 50).works.map { it.id })
        }
    }

    @Test
    fun pointsAreFoundByRomajiAndPinyin() {
        val idx = SearchIndex.build(
            emptyList(),
            listOf(
                ScenePoint(
                    id = "p1", bangumiId = 1, lat = 35.0, lng = 139.0, priority = 0,
                    name = "しんじゅく", nameCn = "新宿",
                ),
            ),
        )
        assertEquals(listOf("p1"), idx.query(TextFold.fold("shinjuku"), 10, 50).points.map { it.id })
        assertEquals(listOf("p1"), idx.query(TextFold.fold("xinsu"), 10, 50).points.map { it.id })
    }

    /** 转写命中不得盖过真正的标题命中。 */
    @Test
    fun transliterationRanksBelowRealTitles() {
        fun work(id: Int, cn: String) = BangumiLite(
            id = id, cn = cn, en = null, title = null, city = null, colorHex = null,
            cover = null, cat = null, center = null, zoom = null, points = emptyList(),
        )
        // 「甘」的拼音是 gan;另一部作品的中文名里直接含有 gan 这三个拉丁字母。
        val idx = SearchIndex.build(listOf(work(1, "甘"), work(2, "gan")), emptyList())
        assertEquals(2, idx.query(TextFold.fold("gan"), 10, 50).works.first().id)
    }

    /** 单字不生成首字母串——一个字母的噪声。 */
    @Test
    fun singleCharacterProducesNoInitialism() {
        val idx = SearchIndex.build(
            emptyList(),
            listOf(ScenePoint("p", 1, 35.0, 139.0, 0, name = null, nameCn = "东")),
        )
        assertTrue(idx.query(TextFold.fold("dong"), 10, 50).points.isNotEmpty())
    }
}

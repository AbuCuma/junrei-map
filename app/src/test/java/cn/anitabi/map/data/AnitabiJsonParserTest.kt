package cn.anitabi.map.data

import cn.anitabi.map.data.model.AnitabiImage
import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.NameLocale
import cn.anitabi.map.data.model.PointDetail
import cn.anitabi.map.data.model.ScenePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnitabiJsonParserTest {

    // g.json：ルート [list, pageSize, modified]、1 作品 18 要素。
    // 数値が文字列で来る・座標 (0,0)・範囲外座標・priority 未設定などの寛容パスを網羅する。
    private val gJson = """
        [
          [
            [815878, "孤独摇滚！", "BOCCHI THE ROCK!", "ぼっち・ざ・ろっく！", "日本",
             "#f5c1c4", "/images/bangumi/815878.jpg", "8.1", "TV",
             35.372558, 139.531213, 15.5,
             ["pt-a", 35.372558, 139.531213, 120,
              "pt-b", "35.309671", "139.550151", "40",
              "pt-zero", 0, 0, 10,
              "pt-bad", 999, 999, 10],
             "孤独摇滚", ["千恋＊万花", "qlhw", "", 42, null], 5, null, "ぼざろ"],
            [123, "只有坐标的作品", null, null, null, null, null, null, null,
             0, 0, null, [], null, "tags 不是数组", null, null],
            ["not-an-id", "壊れた行"],
            [456, "字符串数值作品", null, "タイトル", "日本", "#ffffff", null, 7.2, "剧场版",
             "35.0", "135.0", "10", ["pt-c", 35.0, 135.0, 0], null, null, null]
          ],
          500,
          1755300000.123
        ]
    """.trimIndent()

    @Test
    fun parsesBangumiListWithLenientTypes() {
        val ds = AnitabiJsonParser.parseBangumiList(gJson)!!
        assertEquals(1755300000.123, ds.modified, 1e-6)
        // 壊れた行（id 無し）はスキップされる
        assertEquals(3, ds.bangumis.size)

        val bocchi = ds.bangumis[0]
        assertEquals(815878, bocchi.id)
        assertEquals("孤独摇滚！", bocchi.cn)
        assertEquals(8.1, bocchi.score!!, 1e-9) // 文字列 "8.1" → Double
        assertEquals(5, bocchi.priority)
        assertEquals(35.372558, bocchi.center!!.lat, 1e-9)
        // (0,0) と範囲外 (999,999) の点は捨てられ、文字列座標は通る
        assertEquals(2, bocchi.points.size)
        assertEquals("pt-b", bocchi.points[1].id)
        assertEquals(40, bocchi.points[1].priority)

        // idx14/idx17 は検索専用フィールド。空文字・数値・null は落として拾えるだけ拾う。
        assertEquals(listOf("千恋＊万花", "qlhw"), bocchi.tags)
        assertEquals("ぼざろ", bocchi.titleAbbr)
        assertEquals("BOCCHI THE ROCK!", bocchi.en)
        assertEquals("孤独摇滚", bocchi.abbr)

        val noCoord = ds.bangumis[1]
        // idx14 が配列でなければ空リスト（この行を落とさない）
        assertEquals(emptyList<String>(), noCoord.tags)
        assertNull(noCoord.titleAbbr)
        assertNull(noCoord.center) // (0,0) の center は null
        assertEquals(999, noCoord.priority) // priority 未設定 → 999

        val strNum = ds.bangumis[2]
        assertEquals(10.0, strNum.zoom!!, 1e-9) // 文字列 zoom

        // ScenePoint 側は全作品分がフラットに積まれる
        assertEquals(3, ds.points.size)
        assertEquals(815878, ds.points[0].bangumiId)
        assertFalse(ds.hasDetails)
    }

    // g0-6：1 セル [bangumiId, theme, points[], modified]、1 地標 15 要素。
    // 並び：0:id 1:name 2:folderName 3:isFolder 4:mid 5:uid 6:image 7:fid 8:ep
    //       9:timecodeS 10:note 11:origin 12:originLink 13:cn
    private val g0Json = """
        [
          [815878, "#f5c1c4",
            [
              ["pt-a", "文化服装学院", null, false, null, 4278, "/points/815878/pt-a.jpg", null,
               3, 754, "备注文本", "用户投稿", "https://example.com/src", "文化服装学院CN"],
              ["pt-folder", "取景地集", null, 1, "myMapId", null, null, null,
               null, null, null, null, null, null],
              ["pt-b", "下北泽", null, "true", null, null, null, "pt-folder",
               "OP", "90", null, "地图导入", null, null]
            ],
            1755123456.0]
        ]
    """.trimIndent()

    @Test
    fun parsesPointDetailsWithVariantFlags() {
        val details = HashMap<String, PointDetail>()
        val modified = HashMap<Int, Double>()
        AnitabiJsonParser.parsePointDetails(g0Json, details, modified)

        assertEquals(1755123456.0, modified[815878]!!, 1e-6)

        val a = details["pt-a"]!!
        assertEquals("文化服装学院", a.name)
        assertEquals("文化服装学院CN", a.nameCn)
        assertEquals("3", a.ep) // 数値の話数 → 文字列
        assertEquals(754, a.timecodeS)
        assertEquals(4278, a.uid)
        assertFalse(a.isFolder)

        assertTrue(details["pt-folder"]!!.isFolder) // 数値 1 → true
        val b = details["pt-b"]!!
        assertTrue(b.isFolder) // "true" → true
        assertEquals("OP", b.ep)
        assertEquals(90, b.timecodeS) // 文字列 "90" → Int
        assertEquals("pt-folder", b.fid)
    }

    @Test
    fun mergeFillsDetailsByPointId() {
        val ds = AnitabiJsonParser.parseBangumiList(gJson)!!
        val details = HashMap<String, PointDetail>()
        AnitabiJsonParser.parsePointDetails(g0Json, details, HashMap())
        val merged = AnitabiJsonParser.merge(details, ds.points)
        val a = merged.first { it.id == "pt-a" }
        assertEquals("文化服装学院", a.name)
        assertEquals("EP 3", a.episodeBadge)
        assertEquals("12:34", a.timecodeText)
        // 詳細の無い点はそのまま
        assertNull(merged.first { it.id == "pt-c" }.name)
    }

    @Test
    fun parsesUsersOnFirstCommaOnly() {
        val users = AnitabiJsonParser.parseUsers(
            "4278,ぼっち,ちゃん\n999,  \nbad,名前\n1,单名\n"
        )
        assertEquals("ぼっち,ちゃん", users[4278]) // 昵称内のカンマは保持
        assertNull(users[999]) // 空白のみの昵称は捨てる
        assertEquals("单名", users[1])
        assertEquals(2, users.size)
    }

    @Test
    fun parsesIconIndexWithRelativeSrc() {
        val idx = AnitabiJsonParser.parseIconIndex(
            """{"src": "/d/bangumi-icons.webp?v=hl5ex", "ids": [815878, "123", 456.0]}""",
            origin = "https://w.junreimap.com",
        )!!
        assertEquals("https://w.junreimap.com/d/bangumi-icons.webp?v=hl5ex", idx.spriteUrl)
        assertEquals(listOf(815878, 123, 456), idx.ids)
    }

    @Test
    fun etiquetteRegexMatchesWebRules() {
        fun point(name: String?) = ScenePoint("x", 1, 0.0, 0.0, 0, name = name, nameCn = null)
        assertTrue(point("莵道高校前").needsEtiquetteWarning)
        assertTrue(point("Some School Gate").needsEtiquetteWarning) // 大文字小文字を無視
        assertTrue(point("市立图书馆").needsEtiquetteWarning)
        assertFalse(point("江ノ島展望台").needsEtiquetteWarning)
        assertFalse(point(null).needsEtiquetteWarning)
    }

    @Test
    fun imageUrlRules() {
        // 剥掉 /images 段 + 追加 plan（host 与 web 客户端对本 App 所用 origin 的解析一致）
        assertEquals(
            "https://image-anitabi.magiconch.com/points/1/a.jpg?plan=h160",
            AnitabiImage.url("/images/points/1/a.jpg", plan = "h160"),
        )
        // 作品封面
        assertEquals(
            "https://image-anitabi.magiconch.com/bangumi/115908.jpg?plan=h360",
            AnitabiImage.url("/images/bangumi/115908.jpg", plan = "h360"),
        )
        // 用户投稿（约占数据三成，不在开放 API 文档收录的命名空间内）
        assertEquals(
            "https://image-anitabi.magiconch.com/user/0/bangumi/1/points/x-1.jpg?plan=h160",
            AnitabiImage.url("/images/user/0/bangumi/1/points/x-1.jpg", plan = "h160"),
        )
        // 已带其它查询参数时用 & 追加 plan（ptheme 的 ?v= 版本号）
        assertEquals(
            "https://image-anitabi.magiconch.com/ptheme/1_100_76.webp?v=heqld&plan=h360",
            AnitabiImage.url("/images/ptheme/1_100_76.webp?v=heqld", plan = "h360"),
        )
        // lain.bgm.tv → プロキシ
        assertEquals(
            "https://bgm-api.anitabi.cn/img/pic/cover/l/x.jpg",
            AnitabiImage.url("https://lain.bgm.tv/pic/cover/l/x.jpg", plan = null),
        )
        // http → https 昇格
        assertEquals(
            "https://example.com/i.jpg?plan=h360",
            AnitabiImage.url("http://example.com/i.jpg", plan = "h360"),
        )
        // 已带 plan= 就不再追加
        assertEquals(
            "https://image-anitabi.magiconch.com/a.jpg?plan=h160",
            AnitabiImage.url("a.jpg?plan=h160", plan = "h360"),
        )
        assertNull(AnitabiImage.url("  ", plan = "h160"))
    }

    @Test
    fun withPlanSwitchesSizeAndKeepsOtherQuery() {
        val h360 = "https://image-anitabi.magiconch.com/points/1/a.jpg?plan=h360"
        // 换档
        assertEquals(
            "https://image-anitabi.magiconch.com/points/1/a.jpg?plan=h160",
            AnitabiImage.withPlan(h360, "h160"),
        )
        // 去掉 plan = 完整尺寸
        assertEquals("https://image-anitabi.magiconch.com/points/1/a.jpg", AnitabiImage.withPlan(h360, null))
        // 无 plan 的 URL 补上
        assertEquals(
            "https://image-anitabi.magiconch.com/points/1/a.jpg?plan=h360",
            AnitabiImage.withPlan("https://image-anitabi.magiconch.com/points/1/a.jpg", "h360"),
        )
        // 其它查询参数保留（ptheme 的 ?v= 之类）
        assertEquals(
            "https://image-anitabi.magiconch.com/ptheme/1_100_76.webp?v=heqld&plan=h360",
            AnitabiImage.withPlan("https://image-anitabi.magiconch.com/ptheme/1_100_76.webp?v=heqld", "h360"),
        )
        assertEquals(
            "https://image-anitabi.magiconch.com/ptheme/1_100_76.webp?v=heqld",
            AnitabiImage.withPlan("https://image-anitabi.magiconch.com/ptheme/1_100_76.webp?v=heqld&plan=h160", null),
        )
    }

    @Test
    fun cacheBusterMatchesWebFormula() {
        // Web 版 HS()：base36(epochSec/60/24 + 6)。約 24 分ごとに変わる。
        val epochMillis = 1_755_300_000_000L
        val expected = (1_755_300_000L / 60 / 24 + 6).toString(36)
        assertEquals(expected, AnitabiDataLoader.cacheBuster(epochMillis))
    }

    @Test
    fun displayNameFollowsUiLanguageWithFallbacks() {
        val full = BangumiLite(
            id = 1, cn = "孤独摇滚！", en = "BOCCHI THE ROCK!", title = "ぼっち・ざ・ろっく！",
            city = null, colorHex = null, cover = null, cat = null, center = null, zoom = null, points = emptyList(),
        )
        assertEquals("孤独摇滚！", full.displayName(NameLocale.Zh))
        assertEquals("ぼっち・ざ・ろっく！", full.displayName(NameLocale.Ja))
        assertEquals("BOCCHI THE ROCK!", full.displayName(NameLocale.En))
        // 没有英文题名 → 英文界面退到原题;什么都没有 → #id
        val noEn = full.copy(en = " ")
        assertEquals("ぼっち・ざ・ろっく！", noEn.displayName(NameLocale.En))
        assertEquals("#1", full.copy(cn = null, en = null, title = null).displayName(NameLocale.En))
        // 地标:中文界面用中文名,其它语言用原名(路牌上的那个)
        val point = ScenePoint(id = "p", bangumiId = 1, lat = 0.0, lng = 0.0, priority = 0, name = "東京駅", nameCn = "东京站")
        assertEquals("东京站", point.displayName(NameLocale.Zh))
        assertEquals("東京駅", point.displayName(NameLocale.En))
        assertEquals("東京駅", point.displayName(NameLocale.Ja))
        assertEquals("东京站", point.copy(name = null).displayName(NameLocale.En))
        assertEquals(NameLocale.Zh, NameLocale.fromLanguage("zh-Hant"))
        assertEquals(NameLocale.Ja, NameLocale.fromLanguage("ja"))
        assertEquals(NameLocale.En, NameLocale.fromLanguage("fr"))
    }
}

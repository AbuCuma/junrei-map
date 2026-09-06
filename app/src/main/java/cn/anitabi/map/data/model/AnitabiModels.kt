package cn.anitabi.map.data.model

// anitabi 静态数据（/d/g.json、/d/g0-g6.json）的模型（iOS AnitabiModels.swift 的移植）。
// 字段以基于位置的数组形式下发，排列以 AnitabiJsonParser 的注释为唯一出处。
// 本包不 import android.*/gms —— 这是包结构上的硬性原则。

/** 为不让 gms LatLng 越出边界而自备的坐标值类型。 */
data class LatLon(val lat: Double, val lng: Double) {
    val isValid: Boolean
        get() = lat in -90.0..90.0 && lng in -180.0..180.0
}

/**
 * 作品名 / 地标名跟随哪种界面语言。纯 Kotlin,与 Android `Locale` 解耦,便于 store / engine 在 JVM 上测。
 * UI 侧由 `LocalNameLocale` 按当前配置提供。
 */
enum class NameLocale {
    Zh, Ja, En;

    companion object {
        /** 由 BCP-47 语言码(`zh`、`ja`、其它一律英文)得到。 */
        fun fromLanguage(language: String): NameLocale = when (language.lowercase().substringBefore('-')) {
            "zh" -> Zh
            "ja" -> Ja
            else -> En
        }
    }
}

/** `/d/g.json` 的 1 条记录（18 个字段的数组）。排列参见 parseBangumiList。 */
data class BangumiLite(
    val id: Int,
    /** 中文名 */
    val cn: String?,
    /** 英文名（idx2） */
    val en: String? = null,
    /** 原题（多为日语） */
    val title: String?,
    /** 地域（「日本」「中国」等。不是城市名） */
    val city: String?,
    /** 主题色（`#rrggbb`）。色域不可控 —— 也会来接近白色的值。 */
    val colorHex: String?,
    /** 封面路径（如 `/images/bangumi/{id}.jpg`） */
    val cover: String?,
    /** Bangumi 侧的评分（idx7） */
    val score: Double? = null,
    /** 分类（TV / 剧场版 / 漫画系列 / 游戏 …） */
    val cat: String?,
    val center: LatLon?,
    val zoom: Double?,
    /** 属于该作品的地标坐标列表（详情在 g0-g6 一侧） */
    val points: List<PointGeo>,
    /** 中文简称（idx13）。搜索、显示的辅助。 */
    val abbr: String? = null,
    /**
     * 人工维护的别名／关键词（idx14）。如《千恋＊万花》的 `["千恋＊万花","qlhw"]`、
     * 《摇曳露营》的 `["芳文社","あfろ","Afro"]`。**只用于搜索**，不参与显示。
     */
    val tags: List<String> = emptyList(),
    /** 显示优先级（idx15）。值越小越优先。未设置为 999（与 Web 版相同的默认值）。 */
    val priority: Int = 999,
    /** 原题的简称（idx17，如《【推しの子】》的 `"推しの子"`）。同样只用于搜索。 */
    val titleAbbr: String? = null,
    /** 是否包含在 `bangumi-icons.json` 的 `ids` 中（AnitabiStore 之后回填）。 */
    val hasIcon: Boolean = false,
) {
    /**
     * 显示名,按 UI 语言取:中文 → `cn`;日语 → 原题;英语 → `en`(g.json 的英文题名,不是每部都有),
     * 缺哪个就按「原题 → 中文」退。UI 语言由调用方经 [NameLocale] 传入(model 不碰 Android Locale)。
     */
    fun displayName(locale: NameLocale): String {
        val candidates = when (locale) {
            NameLocale.Zh -> arrayOf(cn, title, en)
            NameLocale.Ja -> arrayOf(title, cn, en)
            NameLocale.En -> arrayOf(en, title, cn)
        }
        return candidates.firstNotNullOfOrNull { it.nilIfBlank() } ?: "#$id"
    }

    val coverUrl: String?
        get() = AnitabiImage.url(cover, plan = "h160")
}

/** g.json 持有的地标几何。 */
data class PointGeo(
    val id: String,
    val lat: Double,
    val lng: Double,
    /** 基于到最近邻点距离的优先级。用于按 zoom 的抽稀。 */
    val priority: Int,
)

/**
 * 落在地图上的 1 个点。g.json 的坐标合并了 g0-g6 的详情。
 * g0-g6 侧的排列参见 AnitabiJsonParser.parsePointDetails 的注释。
 */
data class ScenePoint(
    val id: String,
    val bangumiId: Int,
    val lat: Double,
    val lng: Double,
    val priority: Int,
    // 以下字段在加载 g0-g6 之后填充。
    val name: String? = null,
    val nameCn: String? = null,
    /** 截图的路径 */
    val image: String? = null,
    /** 集数 */
    val ep: String? = null,
    /** 该点自身是否为文件夹（分组的标题）。 */
    val isFolder: Boolean = false,
    /** 以字符串指定的文件夹名。面向没有 fid 的旧数据。 */
    val folderName: String? = null,
    /** Google 我的地图的地图 ID。用于判定是否来自地图导入。 */
    val mid: String? = null,
    /** 投稿者的 User ID。经 AnitabiStore.usersById 解析成显示名。 */
    val uid: Int? = null,
    /** 所属文件夹（isFolder 的点）的 ID。 */
    val fid: String? = null,
    /** 时间码（秒）。数值或字符串都可能来，宽容解析。 */
    val timecodeS: Int? = null,
    /** 备注（原 `mark`）。 */
    val note: String? = null,
    /** 出处。「用户投稿」「地图导入」之类的文案。 */
    val origin: String? = null,
    val originLink: String? = null,
) {
    val coordinate: LatLon get() = LatLon(lat, lng)

    /** 地标只有原名(多为日语)与中文名:中文界面用中文名,其它语言用原名 —— 现场路牌上写的是它。 */
    fun displayName(locale: NameLocale): String {
        val primary = if (locale == NameLocale.Zh) nameCn else name
        val fallback = if (locale == NameLocale.Zh) name else nameCn
        return primary.nilIfBlank() ?: fallback.nilIfBlank() ?: ""
    }

    /** 供列表缩略图用（最大 64×36，h160 就够）。 */
    val thumbnailUrl: String?
        get() = AnitabiImage.url(image, plan = "h160")

    /** 「EP 3」形式的徽章。没有集数则为 null。 */
    val episodeBadge: String?
        get() {
            val ep = ep.nilIfBlank() ?: return null
            if (ep.lowercase().startsWith("ep")) return ep.uppercase()
            if (ep.toIntOrNull() != null) return "EP $ep"
            return ep
        }

    /** 把时间码转成人可读的形式。`m:ss`，60 分钟以上是 `h:mm:ss`。 */
    val timecodeText: String?
        get() {
            val seconds = timecodeS ?: return null
            if (seconds < 0) return null
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

    /**
     * 是否为巡礼礼仪提醒的对象（学校、图书馆等外来者进入容易造成困扰的场所）。
     * 原样移植 web 版的判定正则。先看 nameCn，没有则看 name。
     */
    val needsEtiquetteWarning: Boolean
        get() {
            val text = nameCn.nilIfBlank() ?: name.nilIfBlank() ?: return false
            return EtiquetteRegex.containsMatchIn(text)
        }

    companion object {
        val EtiquetteRegex =
            Regex("学校|莵道高|school|图书馆|library|高中|小学|少年院|中学", RegexOption.IGNORE_CASE)
    }
}

/** g0-g6 侧的地标详情（合并前的中间表示）。 */
data class PointDetail(
    val name: String? = null,
    val nameCn: String? = null,
    val image: String? = null,
    val ep: String? = null,
    val isFolder: Boolean = false,
    val folderName: String? = null,
    val mid: String? = null,
    val uid: Int? = null,
    val fid: String? = null,
    val timecodeS: Int? = null,
    val note: String? = null,
    val origin: String? = null,
    val originLink: String? = null,
)

/** 获取结果。 */
data class AnitabiDataset(
    val bangumis: List<BangumiLite>,
    val points: List<ScenePoint>,
    /** g.json 的 `modified`。用于差分判定。 */
    val modified: Double,
    /** 每个作品的最后更新（g0-g6 各 cell 的第 4 项）。用于「最近更新」的排序。 */
    val modifiedByBangumi: Map<Int, Double> = emptyMap(),
    /** 是否已合并到地标详情（g0-g6）。 */
    val hasDetails: Boolean,
    /**
     * 全部分片是否与服务端同一世代。只要有 1 个分片因获取失败被旧缓存顶替
     * 就为 false —— 调用方绝不能保存 modified（保存的话，之后会误判
     * 「本地已是最新」，该分片将永远停留在旧版）。
     */
    val detailsAreCurrent: Boolean = true,
)

/** 作品详情面板中地标的分组方式。对应 Web 版的「文件夹」「话数」标签页。 */
enum class WorkGroupingMode { Folder, Episode }

/** AnitabiStore.groupedPoints 的 1 个分组。 */
data class PointGroup(
    val id: String,
    val name: String,
    val points: List<ScenePoint>,
    /**
     * 话数分组里「没有 ep」的兜底组。显示层据此换成本地化的「未分组」文案。
     * 文件夹分组的默认组用作品名,不设本标志。
     *
     * 用独立字段而不是约定的 id 字面量:`ep` 是用户投稿字段,曾经用 `id == "ep:none"` 判定,
     * 于是一个 `ep` 恰好写成 `none` 的地标会既撞掉分组 id(LazyColumn 重复 key 直接抛异常),
     * 又被误显示成「未分组」。
     */
    val isUnassigned: Boolean = false,
)

/**
 * 图片路径 → CDN URL 转换。Web 版 `XS`/`Zr` 的最小移植（与 iOS AnitabiImage 同一规则）：
 * 只有 `/images/` 前缀的相对路径需要拼 host，剥掉该前缀后接到 host 之后。
 *
 * **host 的选择依据（2026-08 核对 web 客户端行为）**：web 客户端按自身 origin 选图片 host ——
 * `www.anitabi.cn` / `www-tc.anitabi.cn` 用腾讯 EdgeOne 的 `img-tc.anitabi.cn`；
 * 而 `ww.anitabi.cn`、`w.anitabi.cn` 与 `*.junreimap.com` 用 [PRIMARY_HOST]。
 * 本 App 的数据 origin 正是 `ww.anitabi.cn` / `w.junreimap.com`（见 AnitabiDataLoader.ORIGINS），
 * 因此跟随后者。[FALLBACK_HOST] 是官方开放 API 文档指定的地址，保留作兜底。
 */
object AnitabiImage {

    /**
     * 主 host：web 客户端为本 App 所用的那组 origin 选择的图片地址，由 magiconch.com
     * （神奇海螺试验场，anitabi 页脚链接的运营方、同一维护者）提供。
     *
     * **它不在官方开放 API 文档内**，与 `/d/g.json` 数据管线同属未文档化的内部管线；
     * 选它是因为 web 客户端对同组 origin 就是这么解析的，且实测五个命名空间
     * （`points/`、`user/`、`ptheme/`、`bangumi/`、`icon/`）均可用。详见 README 的
     * 「数据接口使用说明（待社区确认）」。
     */
    const val PRIMARY_HOST = "https://image-anitabi.magiconch.com"

    /**
     * 兜底 host：官方开放 API 文档指定的图片基础地址。
     * 只收录 `points/` 与 `bangumi/` 两个命名空间，2026-08 实测其源站对 CDN 不可达（525），
     * 但保留在候选链里 —— 服务端恢复后即自动受益，且它是唯一有文档契约的地址。
     */
    const val FALLBACK_HOST = "https://image.anitabi.cn"

    fun url(path: String?, plan: String?): String? {
        val raw = path.nilIfBlank() ?: return null

        var absolute = when {
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            raw.startsWith("https://") -> raw
            // 候选链上的两个 host 路径规则一致，都是剥掉 `/images` 段。
            raw.startsWith("/images/") -> PRIMARY_HOST + raw.removePrefix("/images")
            // 以下两支比 web 规则更宽（web 只处理 `/images/` 前缀），属防御性兜底。
            // 注意主 host 是第三方域名，真出现别的相对路径形态时应先确认再放行。
            raw.startsWith("/") -> "$PRIMARY_HOST$raw"
            else -> "$PRIMARY_HOST/$raw"
        }

        // lain.bgm.tv 与官方一致走代理。
        absolute = absolute.replace("https://lain.bgm.tv/", "https://bgm-api.anitabi.cn/img/")

        if (plan == null) return absolute
        // 已带 plan= 就不再追加（iOS URLComponents 判定的移植）。
        val queryStart = absolute.indexOf('?')
        val hasPlan = queryStart >= 0 && absolute.substring(queryStart + 1)
            .split('&').any { it.substringBefore('=') == "plan" }
        if (hasPlan) return absolute
        return absolute + (if (queryStart >= 0) "&" else "?") + "plan=$plan"
    }

    /**
     * 把已构建好的图片 URL 换成另一档尺寸（`plan = null` 即完整尺寸）。
     * 其余查询参数（如 `ptheme` 的 `?v=`）原样保留。
     *
     * 用于「大→小」回退：官方文档不建议在展示界面用完整尺寸，且未缓存的大尺寸
     * 回源失败率明显更高，取不到时退到下一档比什么都不显示好。
     */
    fun withPlan(url: String, plan: String?): String {
        val queryStart = url.indexOf('?')
        val base = if (queryStart < 0) url else url.substring(0, queryStart)
        val kept = if (queryStart < 0) {
            emptyList()
        } else {
            url.substring(queryStart + 1)
                .split('&')
                .filter { it.isNotEmpty() && it.substringBefore('=') != "plan" }
        }
        val params = if (plan == null) kept else kept + "plan=$plan"
        return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
    }
}

fun String?.nilIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

object DistanceFormatter {
    /** 「350 m」「1.2 km」。巡礼中需要 m 级精度，1km 以内保持用 m 输出。 */
    fun string(meters: Double): String {
        if (meters < 1000) return "${Math.round(meters)} m"
        val km = meters / 1000
        return if (km < 10) "%.1f km".format(km) else "${Math.round(km)} km"
    }
}

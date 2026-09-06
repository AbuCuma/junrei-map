package cn.anitabi.map.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import cn.anitabi.map.data.model.AnitabiDataset
import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.NameLocale
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.PointGroup
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.data.model.WorkGroupingMode
import cn.anitabi.map.data.model.nilIfBlank
import cn.anitabi.map.data.search.SearchIndex
import cn.anitabi.map.data.search.TextFold
import java.text.Collator
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 首屏使用的唯一状态（iOS AnitabiStore 的移植）。全量数据都在本地，
// 搜索、附近、都市统计全部在内存中完成（零网络往返＝无加载指示）。

// MARK: - 派生模型

data class NearbyPoint(val point: ScenePoint, val distanceMeters: Double) {
    val id: String get() = point.id
}

data class CityTile(
    val id: String,
    val name: String,
    /** 简体中文名（zh 语言环境显示、中文搜索用）。 */
    val nameCn: String,
    val center: LatLon,
    /** 显示半径（m）。统计与相机移动两者都用。 */
    val radius: Double,
    val pointCount: Int,
)

enum class WorkBrowseTab { RecentlyUpdated, NewlyAdded, Popular }

sealed interface SearchResult {
    val id: String

    data class Bangumi(val bangumi: BangumiLite) : SearchResult {
        override val id get() = "b${bangumi.id}"
    }

    data class Point(val point: ScenePoint, val distanceMeters: Double?) : SearchResult {
        override val id get() = "p${point.id}"
    }
}

sealed interface DataLoadPhase {
    /** 还什么都没有（首次启动 + 等待网络） */
    data object Empty : DataLoadPhase

    /** 只有坐标。地图能画，但名称、截图还没有 */
    data object GeometryOnly : DataLoadPhase

    /** 全部齐了 */
    data object Complete : DataLoadPhase

    data class Failed(val message: String) : DataLoadPhase
}

/** 相当于 UserDefaults 的注入点（实现是 app 层的 SharedPreferences，测试中为内存实现）。 */
interface AnitabiPrefs {
    /** g.json 的 modified 基线。分片被旧缓存顶替时**绝不能保存**。 */
    var cachedModified: Double
    var lastVisitedBangumiId: Int
    var recentVisitIds: List<Int>

    /** 地图的底图样式（"standard" / "hybrid" — iOS map.baseStyle）。 */
    var mapBaseStyle: String

    /** 剧照标图层的显示（iOS map.isPhotoLayerVisible）。 */
    var isPhotoLayerVisible: Boolean

    /** 地图按巡礼记录过滤（`PointVisitFilter.name`；本仓库自有，iOS 无）。非法值由读取方回落 All。 */
    var pointVisitFilter: String

    /** 状态恢复用的深链 URL（iOS map.restoredDeepLink）。**不用空值覆盖**是调用方的约定。 */
    var restoredDeepLink: String?

    var hasCompletedOnboarding: Boolean

    /** 实验性 AI 抠图(ISNet)开关。默认关:开启后才允许下载模型/原生库并启用。 */
    var isnetExperimentEnabled: Boolean
}

/** 视口的最小表达（相当于 MKCoordinateRegion。不把 gms 类型放出边界之外）。 */
data class LatLonRegion(
    val centerLat: Double,
    val centerLng: Double,
    val latDelta: Double,
    val lngDelta: Double,
)

// MARK: - Store

class AnitabiStore(
    private val loader: AnitabiDataLoader,
    private val prefs: AnitabiPrefs,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
) {

    // MARK: 数据

    var bangumis: List<BangumiLite> by mutableStateOf(emptyList())
        private set
    var points: List<ScenePoint> by mutableStateOf(emptyList())
        private set
    var phase: DataLoadPhase by mutableStateOf(DataLoadPhase.Empty)
        private set

    /**
     * 每次替换数据加 1。**派生值的缓存以它为准** —— 名称、剧照是在不改变
     * 点数的前提下后续合流的，「内容是否变了」无法用 points.size 判定。
     */
    var dataGeneration by mutableIntStateOf(0)
        private set

    /**
     * 由 [bangumis] / [points] 派生的全部索引。**必须与它索引的数据同批发布**,
     * 因此它自己也是 snapshot 状态,并且只在 [apply] 里被整体替换一次。
     *
     * 曾经的写法是六个普通 `private var`。普通字段的写入**不参与 snapshot**,会立刻对其他
     * 线程可见 —— 也就是**先于** [points] 的提交可见。而 [apply] 跑在计算线程上,写完索引
     * 之后还要重建都市/作品列表(5 万点量级,数十毫秒)才提交 snapshot;这段窗口里主线程的
     * 搜索拿到的就是「新索引 × 旧数据」。搭配当时以下标引用 [points] 的搜索索引,直接越界崩溃。
     */
    // referentialEqualityPolicy:Indexes 是 data class,默认的结构相等会在每次 apply 时
    // 对 5 万条 searchIndex + 两张大 map 逐一比较(缓存与网络内容相同的常见路径上还会比到底)。
    // 这里每次都是整体替换的新实例,引用相等即可。
    private var indexes: Indexes by mutableStateOf(Indexes(), referentialEqualityPolicy())

    /** 作品 ID → 属于该作品的地标。groupedPoints/screenshotCount/contributorSummary 使用。 */
    val pointsByBangumi: Map<Int, List<ScenePoint>> get() = indexes.pointsByBangumi

    /** 投稿者 User ID → 昵称（/d/users.csv）。在启动的非关键路径上获取。 */
    var usersById: Map<Int, String> by mutableStateOf(emptyMap())
        private set

    /** bangumi-icons.json 的雪碧图 URL。图片本体由 BangumiIconSprite 负责。 */
    var iconSpriteUrl: String? by mutableStateOf(null)
        private set

    // MARK: 派生状态

    var nearby: List<NearbyPoint> by mutableStateOf(emptyList())
        private set
    var cities: List<CityTile> by mutableStateOf(emptyList())
        private set
    var recentlyUpdatedWorks: List<BangumiLite> by mutableStateOf(emptyList())
        private set
    var newlyAddedWorks: List<BangumiLite> by mutableStateOf(emptyList())
        private set
    var popularWorks: List<BangumiLite> by mutableStateOf(emptyList())
        private set

    /** 最近打开的作品。显示在半开档的上下文卡。 */
    var lastVisitedBangumi: BangumiLite? by mutableStateOf(null)
        private set

    /** 最近打开的作品 ID 历史（最多 10 条，新的在前，无重复）。 */
    var recentVisits: List<Int> by mutableStateOf(emptyList())
        private set

    val isEmpty: Boolean get() = points.isEmpty()

    // MARK: - 加载

    /**
     * 1) 缓存即渲染 → 2) 网络刷新。users/icons 由调用方追加加载。
     *
     * 整体切到 [computeDispatcher]:apply() 里的三个 50k 级索引重建不能占用主线程首帧
     * (snapshot state 写线程安全,回主线程渲染由 Compose 自行处理)。
     *
     * **三条 apply 路径(缓存 / partial 回调 / 全量)必须都在 [computeDispatcher] 上。**
     * partial 回调需要显式 `withContext` —— 它由 `refresh` 在 `Dispatchers.IO` 上调用,
     * 不切回来的话同一批状态就会被两个线程写,并可能让两次 `withMutableSnapshot` 撞车
     * (冲突时 `apply().check()` 抛 `SnapshotApplyConflictException`)。
     */
    suspend fun bootstrap() = withContext(computeDispatcher) {
        // 重试路径:清掉上次的 Failed,让 UI 回到骨架屏(加载中)。
        if (isEmpty && phase is DataLoadPhase.Failed) phase = DataLoadPhase.Empty
        loader.loadFromCache()?.let(::apply)

        val known = prefs.cachedModified
        try {
            val fresh = loader.refresh(cachedModified = known) { partial ->
                // 仅 g.json 到达的阶段:有坐标即可先画地图。
                withContext(computeDispatcher) {
                    if (points.isEmpty()) apply(partial)
                }
            }
            apply(fresh)
            // 若有分片用旧缓存顶替,则不推进世代(下次重新拉取该分片)。
            if (fresh.detailsAreCurrent) {
                prefs.cachedModified = fresh.modified
            }
        } catch (c: CancellationException) {
            // 调用方取消(Activity 重建等)不是加载失败——必须重抛,
            // 否则会记下 Failed("Job was cancelled") 且调用方继续在已取消的 scope 里跑。
            throw c
        } catch (e: Exception) {
            if (points.isEmpty()) {
                phase = DataLoadPhase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** users.csv / bangumi-icons.json 的跟进加载（非关键路径）。 */
    suspend fun loadAuxiliary() {
        usersById = loader.loadUsers()
        loader.loadIconIndex()?.let { iconIndex ->
            iconSpriteUrl = iconIndex.spriteUrl
            applyIconIndex(iconIndex.ids)
        }
    }

    internal fun apply(dataset: AnitabiDataset) = Snapshot.withMutableSnapshot {
        // withMutableSnapshot:整组字段对其他线程原子可见。
        // **凡是由 points/bangumis 派生的东西都必须在这里面写**(见 [indexes] 的注释)。
        bangumis = dataset.bangumis
        points = dataset.points
        // 分片被旧缓存顶替时 modifiedByBangumi 为空 —— 沿用上一批,不要清掉。
        val modified = dataset.modifiedByBangumi.takeIf { it.isNotEmpty() }
            ?: indexes.bangumiModified
        indexes = buildIndexes(dataset.bangumis, dataset.points, modified)
        phase = if (dataset.hasDetails) DataLoadPhase.Complete else DataLoadPhase.GeometryOnly

        rebuildCities()
        rebuildWorkLists()
        restoreLastVisited()

        // 世代号必须最后递增:AnitabiMap 按 generation latch 数据集,
        // 若先发布世代、后写内容,并发读方会拿到「新世代+旧数据」并钉住到下一代。
        dataGeneration += 1
    }

    /** 把 bangumi-icons.json 的 ids 回填到 BangumiLite.hasIcon。 */
    private fun applyIconIndex(ids: List<Int>) {
        if (ids.isEmpty()) return
        val idSet = ids.toHashSet()
        // bangumis 与派生索引同批发布,理由同 apply()。
        Snapshot.withMutableSnapshot {
            val updated = bangumis.map { it.copy(hasIcon = idSet.contains(it.id)) }
            bangumis = updated
            indexes = indexes.copy(bangumiById = updated.associateBy { it.id })
        }
    }

    // MARK: - 附近

    /** 按距当前位置由近到远排列地标。50km 以外不算「附近」，直接丢弃。 */
    suspend fun recomputeNearby(around: LatLon, limit: Int = 30) {
        nearby = nearestPoints(
            to = around, points = points, limit = limit,
            maxDistance = 50_000.0, dispatcher = computeDispatcher,
        )
    }

    companion object {
        /**
         * 按距任意坐标由近到远返回地标。等距长方形近似 —— 只是排序用的相对比较，
         * 不需要测地线精度。5 万点的线性扫描，必须在后台执行。
         * （基于当前位置的「附近」与落针卡的「最近的巡礼点」共用。）
         */
        suspend fun nearestPoints(
            to: LatLon,
            points: List<ScenePoint>,
            limit: Int,
            maxDistance: Double? = null,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): List<NearbyPoint> = withContext(dispatcher) {
            val metersPerLat = 111_320.0
            val metersPerLng = 111_320.0 * cos(Math.toRadians(to.lat))
            val squaredLimit = maxDistance?.let { it * it }

            val scored = ArrayList<Pair<ScenePoint, Double>>(minOf(points.size, 4096))
            for (point in points) {
                val dy = (point.lat - to.lat) * metersPerLat
                val dx = (point.lng - to.lng) * metersPerLng
                val squared = dx * dx + dy * dy
                if (squaredLimit != null && squared >= squaredLimit) continue
                scored.add(point to squared)
            }
            scored.sortBy { it.second }
            scored.take(limit).map { NearbyPoint(it.first, sqrt(it.second)) }
        }

        /** 测地距离（haversine）。相当于 iOS 的 CLLocation.distance。 */
        fun distanceMeters(a: LatLon, b: LatLon): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val sinLat = sin(dLat / 2)
            val sinLng = sin(dLng / 2)
            val h = sinLat * sinLat +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sinLng * sinLng
            return 2 * r * asin(sqrt(h.coerceAtMost(1.0)))
        }

        /**
         * 巡礼的主要都市。作品侧的 city 字段是国家/地区粒度（如「日本」），
         * 无法用于都市统计，因此改用按坐标统计真实数据的方式。
         */
        // nameCn 供简体中文用户输入、显示（金沢/金泽、東京/东京在 raw contains 下绝对
        // 不会匹配 —— 真机反馈批次 7）。
        val CITY_CENTERS: List<CityCenter> = listOf(
            CityCenter("tokyo", "東京", "东京", 35.6812, 139.7671, 30_000.0),
            CityCenter("kyoto", "京都", "京都", 35.0116, 135.7681, 22_000.0),
            CityCenter("osaka", "大阪", "大阪", 34.6937, 135.5023, 20_000.0),
            CityCenter("kamakura", "鎌倉", "镰仓", 35.3192, 139.5467, 9_000.0),
            CityCenter("nagoya", "名古屋", "名古屋", 35.1815, 136.9066, 20_000.0),
            CityCenter("sapporo", "札幌", "札幌", 43.0618, 141.3545, 25_000.0),
            CityCenter("fukuoka", "福岡", "福冈", 33.5904, 130.4017, 20_000.0),
            CityCenter("kobe", "神戸", "神户", 34.6901, 135.1955, 18_000.0),
            CityCenter("hiroshima", "広島", "广岛", 34.3853, 132.4553, 18_000.0),
            CityCenter("kanazawa", "金沢", "金泽", 36.5613, 136.6562, 15_000.0),
            CityCenter("numazu", "沼津", "沼津", 35.0955, 138.8635, 12_000.0),
            CityCenter("chichibu", "秩父", "秩父", 35.9924, 139.0784, 12_000.0),
        )
    }

    data class CityCenter(
        val id: String,
        val name: String,
        val nameCn: String,
        val lat: Double,
        val lng: Double,
        val radius: Double,
    )

    // MARK: - 搜索

    /**
     * 本地全文搜索。全部数据都在本地，同步返回＝无需加载指示。
     * 上限为作品 ≤workLimit、地标 ≤sceneLimit（合计默认 60 条）。
     *
     * 匹配与索引本身在 [SearchIndex]（纯 Kotlin，可 JVM 单测）；这里只做
     * 查询串归一化、命中对象的再解析与距离计算。
     */
    fun search(
        query: String,
        near: LatLon?,
        workLimit: Int = 10,
        sceneLimit: Int = 50,
        /** 协作式取消。打字很快时上一次查询早已作废，让它尽早退出。 */
        isActive: () -> Boolean = { true },
    ): List<SearchResult> {
        val needle = TextFold.fold(query.nilIfBlank()).nilIfBlank() ?: return emptyList()
        // 一次读取,整趟搜索用同一份索引。
        val idx = indexes
        val hits = idx.searchIndex.query(
            needle, workLimit, sceneLimit,
            distanceOf = near?.let { n -> { p: ScenePoint -> distanceMeters(n, p.coordinate) } },
            isActive = isActive,
        )

        // 索引持有的是建索引那一刻的 BangumiLite。applyIconIndex 会整体替换 bangumis
        // 与 bangumiById 而**不**重建索引,所以命中后要再解析一次,否则 hasIcon 会是旧值。
        // 只对 ≤10 条结果做,比原先「每次命中都查一次 map」更省。
        // 顺序由 SearchIndex 的相关度决定,这里不再排 —— 原先的
        // `sortByDescending { points.size }` 已作为加成项并入打分。
        val works = hits.works.map { idx.bangumiById[it.id] ?: it }
        val scenes = if (near != null) {
            hits.points.sortedBy { distanceMeters(near, it.coordinate) }
        } else {
            hits.points
        }

        return works.map { SearchResult.Bangumi(it) } +
            scenes.map { point ->
                SearchResult.Point(point, near?.let { distanceMeters(it, point.coordinate) })
            }
    }

    /**
     * 都市名匹配。候选最多只有 12 个，不设上限。不给 SearchResult 加 .city
     * 而做成独立查询，是为了不与地标／作品混排（设计稿 1b）。
     */
    fun matchingCities(query: String): List<CityTile> {
        val needle = TextFold.fold(query.nilIfBlank()).nilIfBlank() ?: return emptyList()
        // 与搜索索引走同一条归一化管线,否则会出现「作品能按简体搜到、都市不能」的不对称。
        return cities.filter {
            TextFold.fold(it.name).contains(needle) || TextFold.fold(it.nameCn).contains(needle)
        }
    }

    // MARK: - 都市

    private fun rebuildCities() {
        if (points.isEmpty()) return
        val counts = HashMap<String, Int>()
        for (point in points) {
            for (city in CITY_CENTERS) {
                val dy = (point.lat - city.lat) * 111_320.0
                val dx = (point.lng - city.lng) * 111_320.0 * cos(Math.toRadians(city.lat))
                if (dx * dx + dy * dy < city.radius * city.radius) {
                    counts[city.id] = (counts[city.id] ?: 0) + 1
                    break // 计入最先命中的都市
                }
            }
        }
        cities = CITY_CENTERS
            .map { CityTile(it.id, it.name, it.nameCn, LatLon(it.lat, it.lng), it.radius, counts[it.id] ?: 0) }
            .filter { it.pointCount > 0 }
            .sortedByDescending { it.pointCount }
    }

    // MARK: - 作品列表

    fun works(tab: WorkBrowseTab): List<BangumiLite> = when (tab) {
        WorkBrowseTab.RecentlyUpdated -> recentlyUpdatedWorks
        WorkBrowseTab.NewlyAdded -> newlyAddedWorks
        WorkBrowseTab.Popular -> popularWorks
    }

    /** 必须在 [indexes] 赋值之后调用 —— 它读的是新一批的 bangumiModified。 */
    private fun rebuildWorkLists() {
        val bangumiModified = indexes.bangumiModified
        val withPoints = bangumis.filter { it.points.isNotEmpty() }

        // 按地标数由多到少＝数据充实的作品
        popularWorks = withPoints.sortedByDescending { it.points.size }.take(20)

        // Bangumi 的 subject id 按注册顺序分配，因此把带地标作品中 id 较大
        // ＝新近获得巡礼数据的作品，作为近似使用。
        newlyAddedWorks = withPoints.sortedByDescending { it.id }.take(20)

        // g0-g6 cell 持有的 modified。未获取则回退到人气排序。
        recentlyUpdatedWorks = if (bangumiModified.isEmpty()) {
            popularWorks
        } else {
            withPoints.sortedByDescending { bangumiModified[it.id] ?: 0.0 }.take(20)
        }
    }

    /** 「随机作品」的候选 = 带截图的地标超过 3 个的作品。 */
    fun randomWork(): BangumiLite? =
        bangumis.filter { screenshotCount(it.id) > 3 }.randomOrNull(random)

    /** 作品的带截图地标数（与 pointsByBangumi 一起预计算）。 */
    fun screenshotCount(bangumiId: Int): Int = indexes.screenshotCountByBangumi[bangumiId] ?: 0

    /** 按地标 ID 查找（O(1)）。 */
    fun point(id: String): ScenePoint? = indexes.pointById[id]

    // MARK: - 视口

    /**
     * 统计在当前地图显示范围内拥有地标的作品，按点数由多到少（chips 用）。
     * 虽是全点线性扫描，但以调用方做防抖为前提。
     */
    fun worksInViewport(region: LatLonRegion): List<Pair<Int, Int>> {
        val minLat = region.centerLat - region.latDelta / 2
        val maxLat = region.centerLat + region.latDelta / 2
        val minLng = region.centerLng - region.lngDelta / 2
        val maxLng = region.centerLng + region.lngDelta / 2

        val counts = HashMap<Int, Int>()
        for (point in points) {
            if (point.lat < minLat || point.lat > maxLat) continue
            if (point.lng < minLng || point.lng > maxLng) continue
            counts[point.bangumiId] = (counts[point.bangumiId] ?: 0) + 1
        }
        return counts.map { it.key to it.value }.sortedByDescending { it.second }
    }

    /**
     * 统计在都市圈（CityTile 中心＋半径的圆）内拥有地标的作品，按点数由多到少。
     * 用于搜索中选中都市时的「该都市的作品」列表（与 web 版相同的动线）。
     * 虽是全点线性扫描，但先用粗略 bbox 收窄再测圆距，一次点按的量级足够便宜。
     */
    fun worksInCity(city: CityTile): List<Pair<Int, Int>> {
        val latHalf = city.radius / 111_320.0
        val lngHalf = latHalf / cos(Math.toRadians(city.center.lat)).coerceAtLeast(0.2)

        val counts = HashMap<Int, Int>()
        for (point in points) {
            if (abs(point.lat - city.center.lat) > latHalf) continue
            if (abs(point.lng - city.center.lng) > lngHalf) continue
            if (distanceMeters(point.coordinate, city.center) > city.radius) continue
            counts[point.bangumiId] = (counts[point.bangumiId] ?: 0) + 1
        }
        return counts.map { it.key to it.value }.sortedByDescending { it.second }
    }

    // MARK: - 作品内分组

    /** 作品详情面板「文件夹」「话数」标签页用的分组。移植自 Web 版逻辑。 */
    fun groupedPoints(
        bangumiId: Int,
        mode: WorkGroupingMode,
        nameLocale: NameLocale = NameLocale.Zh,
    ): List<PointGroup> {
        val pts = pointsByBangumi[bangumiId] ?: emptyList()
        return when (mode) {
            WorkGroupingMode.Folder -> groupedByFolder(pts, bangumiId, nameLocale)
            WorkGroupingMode.Episode -> groupedByEpisode(pts)
        }
    }

    /**
     * folder 模式：isFolder 的点自身成为一组（该点自身是首行），持有 fid 的点归入
     * 对应组，只有 folderName 字符串的点（旧数据）按名称归组，其余进作品名的
     * 默认组。不创建空组。
     */
    private fun groupedByFolder(
        pts: List<ScenePoint>,
        bangumiId: Int,
        nameLocale: NameLocale,
    ): List<PointGroup> {
        val folderOrder = ArrayList<String>()
        val membersByFolderId = HashMap<String, MutableList<ScenePoint>>()
        for (point in pts) {
            if (!point.isFolder) continue
            folderOrder.add(point.id)
            membersByFolderId[point.id] = mutableListOf(point)
        }

        val namedOrder = ArrayList<String>()
        val membersByName = HashMap<String, MutableList<ScenePoint>>()
        val defaultMembers = ArrayList<ScenePoint>()

        for (point in pts) {
            if (point.isFolder) continue
            val fid = point.fid
            if (fid != null && membersByFolderId.containsKey(fid)) {
                membersByFolderId[fid]!!.add(point)
                continue
            }
            val folderName = point.folderName.nilIfBlank()
            if (folderName != null) {
                if (!membersByName.containsKey(folderName)) {
                    namedOrder.add(folderName)
                    membersByName[folderName] = mutableListOf()
                }
                membersByName[folderName]!!.add(point)
                continue
            }
            defaultMembers.add(point)
        }

        val groups = ArrayList<PointGroup>()
        for (fid in folderOrder) {
            val members = membersByFolderId[fid]?.takeIf { it.isNotEmpty() } ?: continue
            val name = members.first().displayName(nameLocale).nilIfBlank() ?: fid
            // 加前缀:fid 是用户投稿字段,裸用它会与下面的 "name:…" / "default:…" 撞成
            // 同一个 LazyColumn key(与话数分组曾经的 "ep:none" 是同一类问题)。
            groups.add(PointGroup(id = "fid:$fid", name = name, points = members))
        }
        for (name in namedOrder) {
            val members = membersByName[name]?.takeIf { it.isNotEmpty() } ?: continue
            groups.add(PointGroup(id = "name:$name", name = name, points = members))
        }
        if (defaultMembers.isNotEmpty()) {
            groups.add(
                PointGroup(
                    id = "default:$bangumiId",
                    name = name(bangumiId, nameLocale),
                    points = defaultMembers,
                )
            )
        }
        return groups
    }

    /**
     * episode 模式：纯数字组按数值升序在前，非数字组按 Collator 顺序随后，
     * 没有 ep 的点归为「未分组」（isUnassigned=true，显示名由 UI 侧本地化）放在最后。
     */
    private fun groupedByEpisode(pts: List<ScenePoint>): List<PointGroup> {
        val numericMembers = LinkedHashMap<String, MutableList<ScenePoint>>()
        val nonNumericMembers = LinkedHashMap<String, MutableList<ScenePoint>>()
        val unassigned = ArrayList<ScenePoint>()

        for (point in pts) {
            val ep = point.ep.nilIfBlank()
            if (ep == null) {
                unassigned.add(point)
                continue
            }
            val target = if (ep.toIntOrNull() != null) numericMembers else nonNumericMembers
            target.getOrPut(ep) { mutableListOf() }.add(point)
        }

        val collator = Collator.getInstance()
        val groups = ArrayList<PointGroup>()
        numericMembers.keys.sortedBy { it.toIntOrNull() ?: 0 }.forEach { ep ->
            groups.add(PointGroup(id = "ep:$ep", name = ep, points = numericMembers[ep]!!))
        }
        nonNumericMembers.keys.sortedWith(compareBy(collator) { it }).forEach { ep ->
            groups.add(PointGroup(id = "ep:$ep", name = ep, points = nonNumericMembers[ep]!!))
        }
        if (unassigned.isNotEmpty()) {
            // name 只是调试兜底:显示层(WorkCardSheet)按 isUnassigned 解析 R.string.ungrouped,
            // 用户看到的是本地化文案,这里的中文字面量不会出现在 UI。
            // 话数组的 id 一律是 "ep:…",所以这个 id 不可能与任何 ep 取值撞车。
            groups.add(PointGroup(id = "unassigned", name = "未分组", points = unassigned, isUnassigned = true))
        }
        return groups
    }

    // MARK: - 参照

    fun bangumi(id: Int): BangumiLite? = indexes.bangumiById[id]

    fun name(bangumiId: Int, nameLocale: NameLocale = NameLocale.Zh): String =
        indexes.bangumiById[bangumiId]?.displayName(nameLocale) ?: ""

    /** 作品的最后更新（g0-g6 cell 的 modified，毫秒 epoch）。未获取的作品为 null。 */
    fun modifiedEpochMillis(bangumiId: Int): Long? =
        indexes.bangumiModified[bangumiId]?.takeIf { it > 0 }?.toLong()

    // MARK: - 投稿者

    /** User ID → 昵称（/d/users.csv）。未获取或无对应项为 null。 */
    fun userNickname(uid: Int): String? = usersById[uid]

    data class ContributorSummary(val top: List<String>, val total: Int)

    /**
     * 作品整体的投稿者摘要：uid 出现次数前 2 名的昵称，以及去重后的投稿者总数。
     * 一条 uid 都没有则为 null。
     */
    fun contributorSummary(bangumiId: Int): ContributorSummary? {
        val pts = pointsByBangumi[bangumiId] ?: return null
        val counts = HashMap<Int, Int>()
        for (point in pts) {
            val uid = point.uid ?: continue
            counts[uid] = (counts[uid] ?: 0) + 1
        }
        if (counts.isEmpty()) return null

        val ranked = counts.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }
        )
        val top = ranked.take(2).mapNotNull { usersById[it.key] }
        return ContributorSummary(top = top, total = counts.size)
    }

    // MARK: - 历史

    /**
     * 记录访问。同时更新 lastVisitedBangumi（单一、保持兼容）与
     * recentVisits（最多 10 条）两者。
     */
    fun rememberVisit(bangumiId: Int) {
        prefs.lastVisitedBangumiId = bangumiId
        val ids = ArrayList(recentVisits)
        ids.removeAll { it == bangumiId }
        ids.add(0, bangumiId)
        prefs.recentVisitIds = ids.take(10)
        restoreLastVisited()
    }

    private fun restoreLastVisited() {
        val id = prefs.lastVisitedBangumiId
        lastVisitedBangumi = if (id == 0) null else indexes.bangumiById[id]
        recentVisits = prefs.recentVisitIds
    }

    /**
     * 由 [bangumis] / [points] 派生的全部索引。整体不可变,只在 [apply] 里被替换一次,
     * 因此索引之间、以及索引与数据之间永远自洽。
     */
    private data class Indexes(
        val bangumiById: Map<Int, BangumiLite> = emptyMap(),
        /** 每个作品的最后更新（g0-g6 cell 的第 4 项）。「最近更新」标签页使用。 */
        val bangumiModified: Map<Int, Double> = emptyMap(),
        /** 搜索用的已归一化索引。不随每次按键重新生成。 */
        val searchIndex: SearchIndex = SearchIndex.EMPTY,
        /** 作品 ID → 属于该作品的地标。 */
        val pointsByBangumi: Map<Int, List<ScenePoint>> = emptyMap(),
        /** 作品 ID → 带截图的地标数。 */
        val screenshotCountByBangumi: Map<Int, Int> = emptyMap(),
        /** 地标 ID → 地标（供点按／深链解析用。避免全点线性扫描）。 */
        val pointById: Map<String, ScenePoint> = emptyMap(),
    )

    private fun buildIndexes(
        bangumis: List<BangumiLite>,
        points: List<ScenePoint>,
        bangumiModified: Map<Int, Double>,
    ): Indexes {
        val grouped = points.groupBy { it.bangumiId }
        return Indexes(
            bangumiById = bangumis.associateBy { it.id },
            bangumiModified = bangumiModified,
            searchIndex = SearchIndex.build(bangumis, points),
            pointsByBangumi = grouped,
            screenshotCountByBangumi = grouped.mapValues { (_, pts) -> pts.count { it.image != null } },
            pointById = points.associateBy { it.id },
        )
    }
}

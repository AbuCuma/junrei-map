package cn.anitabi.map.map.engine

import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.NameLocale
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

// 只负责决定「当前这个相机下该显示哪些标注」的纯计算层（iOS MapEngine actor 的移植）。
// **不做数字聚合气泡**，密度只靠与 Web 版相同的「揭示阶梯」控制。输出是
// 「这一帧应当存在的集合」，add/remove 的差分由 MarkerFieldController 来取。
// 本包不 import android.*/gms —— 这是包结构上的硬性原则。
// 线程约束：调用方在单线程 dispatcher（limitedParallelism(1)）上驱动。

// MARK: - 输入数据

/** 一个作品标的种子。从 BangumiLite 中只抽取地图所需的字段。 */
data class MapWorkSeed(
    val id: Int,
    val coordinate: LatLon,
    val name: String,
    val colorHex: String?,
    val hasIcon: Boolean,
    /**
     * 用于揭示阶梯的值（m）＝该作品各地标最近邻距离的最大值，也就是「这个作品
     * 散布得有多广」。g.json 的 priority（默认 999 的排位）与阶梯的量级
     * 对不上，所以不用。
     */
    val spread: Int,
)

/** 灌入地图的整套数据。5 万点做 `==` 太贵，所以不比较内容，只按 version 判断。 */
data class MapDataset(
    val points: List<ScenePoint>,
    val works: List<MapWorkSeed>,
    val version: Int,
) {
    companion object {
        /**
         * 从 AnitabiStore 生成地图用的种子。作品的中心坐标优先用 g.json 的 center，
         * 未设置时（很多作品是 `[0,0]`）用自身点群的重心。
         */
        fun from(store: AnitabiStore, version: Int, nameLocale: NameLocale = NameLocale.Zh): MapDataset {
            val works = store.bangumis.mapNotNull { bangumi ->
                seedFrom(bangumi, nameLocale)
            }
            return MapDataset(points = store.points, works = works, version = version)
        }

        fun seedFrom(bangumi: BangumiLite, nameLocale: NameLocale = NameLocale.Zh): MapWorkSeed? {
            if (bangumi.points.isEmpty()) return null
            val coordinate = bangumi.center ?: LatLon(
                bangumi.points.sumOf { it.lat } / bangumi.points.size,
                bangumi.points.sumOf { it.lng } / bangumi.points.size,
            )
            return MapWorkSeed(
                id = bangumi.id,
                coordinate = coordinate,
                name = bangumi.displayName(nameLocale),
                colorHex = bangumi.colorHex,
                hasIcon = bangumi.hasIcon,
                spread = bangumi.points.maxOf { it.priority },
            )
        }
    }
}

/** 经纬度矩形。为了不把平台的 Region 类型跨线程传来传去而用的最小表示。 */
data class MapViewport(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
) {
    companion object {
        /** 以中心为基准把视口放大 scale 倍（用于预读）。 */
        fun around(
            centerLat: Double,
            centerLng: Double,
            latDelta: Double,
            lngDelta: Double,
            scale: Double = 1.0,
        ): MapViewport {
            val latHalf = minOf(latDelta * scale / 2, 90.0)
            val lngHalf = minOf(lngDelta * scale / 2, 180.0)
            return MapViewport(
                minLat = centerLat - latHalf,
                maxLat = centerLat + latHalf,
                minLng = centerLng - lngHalf,
                maxLng = centerLng + lngHalf,
            )
        }
    }

    fun contains(lat: Double, lng: Double): Boolean =
        lat in minLat..maxLat && lng in minLng..maxLng
}

// MARK: - 请求与结果

/**
 * 按「巡礼记录」过滤地图（图层菜单的第三段）。纯 Kotlin，prefs 用 `name` 序列化。
 * 与作品过滤（[MapEngineRequest.focusedWorkId] / [MapEngineRequest.selectedWorkIds]）相与。
 */
enum class PointVisitFilter { All, VisitedOnly, UnvisitedOnly }

data class MapEngineRequest(
    val viewport: MapViewport,
    val zoom: Double,
    /** 作品模式。只显示这个作品的点，忽略阶梯全量输出。 */
    val focusedWorkId: Int? = null,
    /** 多作品过滤（chips）。非空时只显示该集合内作品的点，忽略阶梯。 */
    val selectedWorkIds: Set<Int> = emptySet(),
    val isPhotoLayerVisible: Boolean = true,
    /** 选中的地标。必须包含进结果里，以免被上限截掉。 */
    val selectedPointId: String? = null,
    /**
     * 纹理张数的保险。Web 的 2 层没有张数上限，只靠揭示阶梯变疏，
     * 所以设成只要阶梯生效就不会触到的值（实测再密的地方也不到 100 张）。
     */
    val photoLimit: Int = 120,
    val workLimit: Int = 32,
    /**
     * 圆点的取点范围。比 [viewport] 大 —— 甩动时相机能在一次 tick 里跑过一整屏，
     * 圆点集合跟不上就会在前缘露出空白。标注那边不跟着放大，是因为多出来的 Marker
     * 要付 addMarker 的跨界代价，而它们多半还没进屏就又被差分掉了。
     */
    val dotViewport: MapViewport = viewport,
    /** 巡礼记录过滤。作用于圆点、剧照牌与作品标（一个已完成点都没有的作品在「只看已完成」下不出现）。 */
    val visitFilter: PointVisitFilter = PointVisitFilter.All,
    /** 已完成的地标 id。只在 [visitFilter] != All 时被读；不进 dataset，不触发索引重建。 */
    val visitedPointIds: Set<String> = emptySet(),
)

data class MapPointPlan(
    val point: ScenePoint,
    /** 是否以剧照缩略图形态显示（判定在 MapEngine.photoForms）。用哪种图来画由 zoom 决定。 */
    val usesPhoto: Boolean,
)

// MARK: - 引擎

class MapEngine {

    private var points: List<ScenePoint> = emptyList()
    private var works: List<MapWorkSeed> = emptyList()
    private var version = -1

    /**
     * 不可变的空间索引。**一次 volatile 写发布**:如今只有引擎线程读它(命中测试改判已发布的
     * [MapScene],不再查索引),volatile 是为将来再有旁路读者时仍然安全发布(见 [MapPointIndex])。
     */
    @Volatile
    var index: MapPointIndex = MapPointIndex.EMPTY
        private set

    /**
     * 为作品模式小泡重新测量的 priority 缓存（按作品 ID 集合）。
     * 因为是 O(n²)，只在选择变化时重算。
     */
    private var themePriorityCache: Pair<Set<Int>, Map<String, Int>>? = null

    // MARK: 索引构建

    /** 只在数据被替换时重建索引。5 万点耗时几十 ms，但跑在引擎专用线程上。 */
    fun load(dataset: MapDataset) {
        if (dataset.version == version) return
        version = dataset.version
        points = dataset.points
        works = dataset.works

        index = MapPointIndex.build(points, dataset.version)
        themePriorityCache = null
    }

    // MARK: 帧计算

    /**
     * 这一帧的全部内容。**标注与圆点在同一次调用里产出**，圆点集合由标注集合直接派生
     * —— 一个点在一帧里只有一种形态，这条不变式在这里成立，不再依赖两条循环的时序。
     *
     * @param dotColors 作品 ID → 打包好的 (外圈, 内圈) ARGB。主题色要从 `AnitabiStore` 读，
     *   那是 Compose 快照状态，不能在绘制阶段碰，所以在这里就解析好。
     * @param minHitRadiusDp 圆点命中半径的下限，交给 [DotField] 决定命中网格的粗细。
     */
    fun scene(
        request: MapEngineRequest,
        dotColors: (Int) -> Long,
        minHitRadiusDp: Double,
    ): MapScene {
        val startedAt = System.nanoTime()
        if (points.isEmpty()) return MapScene.empty(request.zoom)

        val filterIds: Set<Int>? = when {
            request.focusedWorkId != null -> setOf(request.focusedWorkId)
            request.selectedWorkIds.isNotEmpty() -> request.selectedWorkIds
            else -> null
        }
        val selectedId = request.selectedPointId
        val admits = visitPredicate(request)

        // ---- 气球：选中的点。**不是 Marker、也不是圆点** —— 由叠加层画在圆点之上,
        // 命中也由 MapSceneHitTest 按气球的实际形状判定。永远显示,不受巡礼记录过滤。
        val balloon = selectedId?.let { index.byId[it] }

        // ---- 标注（需要 Marker 的点）：剧照牌。选中的点剔除 —— 它以气球形态存在。
        // 巡礼记录过滤在 photoLimit 截断**之前**做:先截后筛的话,密集处排在 120 名之外的已完成点
        // 会被整体挤掉,「只看已完成」反而一张剧照都没有。
        var plans: MutableList<MapPointPlan> = photoPromotions(request, filterIds, admits)
            .filter { it.id != selectedId }
            .mapTo(ArrayList()) { MapPointPlan(it, usesPhoto = true) }

        // 屏幕空间的碰撞解决（MapKit collision 的替代品）。气球虽不在 plans 里,
        // 它的矩形仍要先占位 —— 剧照牌不该压到气球底下去。
        plans = MarkerCollision.resolve(
            plans,
            zoom = request.zoom,
            centerLatitude = (request.viewport.minLat + request.viewport.maxLat) / 2,
            balloon = balloon,
        ).toMutableList()

        val visibleWorks = if (filterIds == null) ladderWorks(request, admittedWorkIds(request)) else emptyList()

        // ---- 圆点：视口内的点，减去已经变成标注的点、以及被标注压住的点。
        val filtered = filterIds != null
        val candidates = ArrayList<ScenePoint>(512)
        if (MapRevealLadder.dotFieldOpacity(request.zoom, filtered) > 0f) {
            index.query(request.dotViewport, -1.0, filterIds, candidates)
        }
        // 气球只排除**自己的点**,不做足迹扫除:气球画在圆点之上(叠加层内后画),
        // 邻点照画照点 —— 此前对气球也扫足迹,选中后周围 ~49×59dp 的点全部不可点,
        // 表现就是「选中一个点后再点它旁边的点没反应」(真机反馈)。
        val occluded = occludedDotIds(request.zoom, plans, visibleWorks, filterIds)
        val visibleDots = candidates.filterTo(ArrayList(candidates.size)) {
            it.id !in occluded && it.id != selectedId && admits(it)
        }

        val focusedCoordinates = request.focusedWorkId
            ?.let { index.byWork[it] }
            ?.map { it.coordinate }

        return MapScene(
            zoom = request.zoom,
            annotations = plans,
            balloon = balloon,
            works = visibleWorks,
            dots = DotField.build(
                points = visibleDots,
                zoom = request.zoom,
                colorOf = dotColors,
                fallback = dotColors(0),
                minHitRadiusDp = minHitRadiusDp,
            ),
            focusedWorkCoordinates = focusedCoordinates,
            selectedPointId = selectedId,
            filtered = filtered,
            diagnostics = SceneDiagnostics(
                dotCandidates = candidates.size,
                occludedDots = candidates.size - visibleDots.size,
                buildNanos = System.nanoTime() - startedAt,
            ),
        )
    }

    /**
     * 被标注压住、因而不该画的圆点 ID。
     *
     * 自绘的圆点层画在**所有 Marker 之上**（Compose 层在地图之上），所以「压住」只能靠不画
     * 来表达。网页版是圆点画在下面被挡住 —— 差别只在标注边缘一圈，而足迹已经外扩了一个
     * 圆点半径（白描边的外缘），所以观感上看不出来。这条取舍写在 docs/architecture.md。
     */
    private fun occludedDotIds(
        zoom: Double,
        annotations: List<MapPointPlan>,
        works: List<MapWorkSeed>,
        filterIds: Set<Int>?,
    ): Set<String> {
        if (annotations.isEmpty() && works.isEmpty()) return emptySet()

        // 外扩一个圆点的白描边外缘，于是「白环压在卡片边缘上」也一并挡掉。
        val halo = MapMarkerMetrics.dotRadius(zoom) + MapMarkerMetrics.dotStrokeWidth(zoom)
        val plate = MarkerFootprint.photo(MarkerFootprint.photoPlate(zoom)).inflated(halo)
        val work = MarkerFootprint.WORK.inflated(halo)

        val excluded = HashSet<String>(annotations.size * 4 + works.size * 4)
        val scratch = ArrayList<ScenePoint>(32)

        fun sweep(lat: Double, lng: Double, footprint: FootprintDp) {
            scratch.clear()
            index.query(footprintBox(lat, lng, footprint, zoom), -1.0, filterIds, scratch)
            for (point in scratch) excluded.add(point.id)
        }

        for (annotation in annotations) {
            val point = annotation.point
            excluded.add(point.id)
            sweep(point.lat, point.lng, plate)
        }
        for (seed in works) sweep(seed.coordinate.lat, seed.coordinate.lng, work)
        return excluded
    }

    /** 以 [lat]/[lng] 为锚点的 dp 足迹换成经纬度盒（局部线性近似，与 [MarkerCollision] 同一套）。 */
    private fun footprintBox(
        lat: Double,
        lng: Double,
        footprint: FootprintDp,
        zoom: Double,
    ): MapViewport {
        val dpPerLng = MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom) / 360.0
        val dpPerLat = dpPerLng / maxOf(cos(Math.toRadians(lat)), 0.01)
        return MapViewport(
            minLat = lat - footprint.maxY / dpPerLat,
            maxLat = lat - footprint.minY / dpPerLat,
            minLng = lng + footprint.minX / dpPerLng,
            maxLng = lng + footprint.maxX / dpPerLng,
        )
    }

    // MARK: 地标筛选

    // MARK: 形态分配

    /**
     * 决定「哪些点要以剧照 Marker 的形式存在」。底子是 Web 的 2 层（在 z18 交棒）——
     * z>18 用全体点的 priority 阈值，z≤18 用作品内重新测量的最近邻距离抽稀。
     * **只把开始出现的线从 web 挪开**，从 PHOTO_REVEAL_ZOOM 就开始显示，
     * 但判定式继续沿用，所以边界是连续的、只有尺寸在变。
     *
     * 返回的是**被提升为剧照的点本身**，不再是「视口内每个点 + usesPhoto 标志」——
     * 没被提升的点不需要 Marker,瓦片层已经把它们画成圆点了。
     */
    private fun photoPromotions(
        request: MapEngineRequest,
        filterIds: Set<Int>?,
        admits: (ScenePoint) -> Boolean,
    ): List<ScenePoint> {
        if (!request.isPhotoLayerVisible) return emptyList()

        // 用全体点 priority 抽稀的路径（大卡片，以及浏览时的小泡）。作品模式在 z18 及以下
        // 走下面「作品内重新测量」的路径 —— 那条路能给同一作品保留更多的点。
        val usesGlobalPriority = request.zoom > MapRevealLadder.PHOTO_CARD_ZOOM ||
            (filterIds == null && request.zoom > MapRevealLadder.PHOTO_REVEAL_ZOOM)

        val candidates = ArrayList<ScenePoint>(256)
        var eligible = ArrayList<Pair<ScenePoint, Int>>()
        if (usesGlobalPriority) {
            // 相当于 `points-image`。作品模式在 z>18 时也汇入这里（Web 也一样）。
            //
            // 直接拿剧照阈值去查索引,而不是「先取视口全部点再筛」——
            // 剧照阈值恒不低于揭示阶梯的阈值(photoThresholdDominatesLadder 钉住了这一点),
            // 所以这样既等价又便宜。查询是 `>` 而判定是 `>=`,故下界退一格再精确过滤。
            val threshold = MapRevealLadder.photoCardPriorityThreshold(request.zoom)
            index.query(request.viewport, threshold - 1.0, filterIds, candidates)
            for (point in candidates) {
                if (point.image != null && point.priority >= threshold && admits(point)) {
                    eligible.add(point to point.priority)
                }
            }
        } else if (filterIds != null) {
            // 相当于 `bangumi-points-theme`。仅在选中了作品时。
            val threshold = MapRevealLadder.photoBubbleThreshold(request.zoom)
            val priorities = themePriorities(filterIds)
            index.query(request.viewport, -1.0, filterIds, candidates)
            for (point in candidates) {
                if (point.image == null || !admits(point)) continue
                val rank = priorities[point.id] ?: continue
                if (rank > threshold) eligible.add(point to rank)
            }
        }

        if (eligible.isEmpty()) return emptyList()
        if (eligible.size > request.photoLimit) {
            eligible.sortByDescending { it.second }
            eligible = ArrayList(eligible.take(request.photoLimit))
        }
        return eligible.map { it.first }
    }

    /**
     * 小泡抽稀用的 priority 按 Web 的 `A()` 重新测量。**不是点原本带的值
     * （在全部 5 万点上的最近邻距离）** —— 只在该作品的图片点内重新取，所以值会变大，
     * 大范围下也能留下几十张。步骤是「按纬度降序排列，从南边起依次量到此前已见点的最短距离」。
     */
    internal fun themePriorities(workIds: Set<Int>): Map<String, Int> {
        themePriorityCache?.let { (cached, values) ->
            if (cached == workIds) return values
        }

        val seeds = ArrayList<Triple<String, Double, Double>>()
        for (workId in workIds.sorted()) {
            val workPoints = index.byWork[workId] ?: continue
            for (point in workPoints) {
                if (point.image == null) continue
                seeds.add(Triple(point.id, point.lat, point.lng))
            }
        }
        seeds.sortByDescending { it.second }

        val values = HashMap<String, Int>(seeds.size)
        val seenLat = ArrayList<Double>(seeds.size)
        val seenLng = ArrayList<Double>(seeds.size)
        for (seed in seeds.asReversed()) {
            var nearest = 99_999.0
            for (index in seenLat.indices) {
                val dx = seed.third - seenLng[index]
                val dy = seed.second - seenLat[index]
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < nearest) nearest = distance
            }
            seenLat.add(seed.second)
            seenLng.add(seed.third)
            values[seed.first] = ceil(nearest * 111_000).toInt()
        }

        themePriorityCache = workIds to values
        return values
    }

    // MARK: 巡礼记录过滤

    /** 圆点 / 剧照牌用的谓词。All 时恒真,不付 `Set.contains` 的代价。 */
    private fun visitPredicate(request: MapEngineRequest): (ScenePoint) -> Boolean = when (request.visitFilter) {
        PointVisitFilter.All -> { _ -> true }
        PointVisitFilter.VisitedOnly -> { point -> point.id in request.visitedPointIds }
        PointVisitFilter.UnvisitedOnly -> { point -> point.id !in request.visitedPointIds }
    }

    /**
     * 作品标可见的作品集合;null = 不限。作品标只看 [MapWorkSeed],不看点,所以这里按记录算：
     * 「只看已完成」= 至少有一个已完成点的作品;「只看未完成」= 还有未完成点的作品。
     * 代价是 |visited| 量级,不是 50k。数据集里不存在的 id 直接跳过。
     */
    private fun admittedWorkIds(request: MapEngineRequest): Set<Int>? {
        if (request.visitFilter == PointVisitFilter.All) return null
        val visitedByWork = HashMap<Int, Int>()
        for (id in request.visitedPointIds) {
            val point = index.byId[id] ?: continue
            visitedByWork.merge(point.bangumiId, 1, Int::plus)
        }
        return when (request.visitFilter) {
            PointVisitFilter.VisitedOnly -> visitedByWork.keys
            PointVisitFilter.UnvisitedOnly -> index.byWork.keys.filterTo(HashSet()) { workId ->
                (visitedByWork[workId] ?: 0) < (index.byWork[workId]?.size ?: 0)
            }
            PointVisitFilter.All -> null
        }
    }

    // MARK: 作品标

    private fun ladderWorks(request: MapEngineRequest, admittedWorkIds: Set<Int>?): List<MapWorkSeed> {
        val threshold = MapRevealLadder.workThreshold(request.zoom) ?: return emptyList()
        var candidates = works.filter {
            it.spread > threshold &&
                (admittedWorkIds == null || it.id in admittedWorkIds) &&
                request.viewport.contains(it.coordinate.lat, it.coordinate.lng)
        }.sortedByDescending { it.spread }
        if (candidates.size > 200) candidates = candidates.take(200)
        return declutter(
            candidates,
            zoom = request.zoom,
            centerLatitude = (request.viewport.minLat + request.viewport.maxLat) / 2,
            limit = request.workLimit,
        )
    }

    /**
     * 仅供 `MapEngineTest` 触达 [MarkerCollision] 的分散逻辑;生产代码不调用。
     * 不加 `@VisibleForTesting` 是因为 `map/engine/` 必须保持纯 Kotlin
     * (该注解在 androidx.annotation 里,引入会破坏 JVM 单测的前提)。
     */
    internal fun declutterForTest(
        seeds: List<MapWorkSeed>,
        zoom: Double,
        centerLatitude: Double,
        limit: Int,
    ): List<MapWorkSeed> = declutter(seeds, zoom, centerLatitude, limit)

    /**
     * 作品标的重叠自己来解 —— iOS 版这么做是为了对付 MapKit 的自动 declutter 会让带标签的
     * 标注输给底图的地名标签而整个消失；而 Google Maps 侧根本没有碰撞系统，
     * 所以在 Android 上这就是唯一的抽稀机制。
     * 用屏幕坐标的贪心布局决定幸存者（spread 降序）。
     */
    private fun declutter(
        seeds: List<MapWorkSeed>,
        zoom: Double,
        centerLatitude: Double,
        limit: Int,
    ): List<MapWorkSeed> {
        val pointsPerLng = 256 * 2.0.pow(zoom) / 360
        val pointsPerLat = pointsPerLng / maxOf(cos(Math.toRadians(centerLatitude)), 0.01)
        // 30dp 的封面 ＋ 最多 92dp 的标签，含留白。圆点的遮挡判定用的是同一个足迹。
        val box = MarkerFootprint.WORK

        val placed = ArrayList<DoubleArray>() // [minX, minY, maxX, maxY]
        val result = ArrayList<MapWorkSeed>()
        for (seed in seeds) {
            val minX = seed.coordinate.lng * pointsPerLng + box.minX
            val minY = -seed.coordinate.lat * pointsPerLat + box.minY
            val maxX = seed.coordinate.lng * pointsPerLng + box.maxX
            val maxY = -seed.coordinate.lat * pointsPerLat + box.maxY
            val intersects = placed.any { r ->
                minX < r[2] && maxX > r[0] && minY < r[3] && maxY > r[1]
            }
            if (intersects) continue
            placed.add(doubleArrayOf(minX, minY, maxX, maxY))
            result.add(seed)
            if (result.size >= limit) break
        }
        return result
    }
}

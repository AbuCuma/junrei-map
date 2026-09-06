package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 全部地标的不可变空间索引。
 *
 * **构建完成后绝不改写。** 由 [MapEngine] 在引擎线程上造好，经一次 volatile 写发布出去。
 * 当前只有引擎线程读它（命中测试判的是已发布的 [MapScene]，不查索引），
 * 但安全发布 + 全程只读的纪律保留着 ＝ 将来再有旁路读者也不需要任何锁。
 *
 * 派生索引一律持有 [ScenePoint] 的**对象引用**，不存下标 —— AGENTS.md 的硬规则
 * （「派生索引也不要按下标引用另一个容器」）。抽取之前 `MapEngine.indexById` 与
 * `indicesByWork` 存的正是 `points` 的下标，属于既有违规，这次一并修掉。
 */
class MapPointIndex private constructor(
    val version: Int,
    /** 0.02° 网格。key 是纬度格、经度格各 32bit 拼成的。 */
    private val cells: Map<Long, Cell>,
    /** priority 降序。大范围（阈值高）时只扫这条前缀，比按格子扫便宜几个量级。 */
    private val byPriority: Array<ScenePoint>,
    /** 与 [byPriority] 同序的 priority 值(`ScenePoint.priority` 是 Int;单独一条原始数组是为了二分时不追对象引用)。 */
    private val priorityDescending: LongArray,
    val byWork: Map<Int, List<ScenePoint>>,
    val byId: Map<String, ScenePoint>,
) {

    /** 一个网格单元。[maxPriority] 让整格在高阈值下被一次性跳过，不必逐点比。 */
    class Cell(val points: List<ScenePoint>, val maxPriority: Long)

    val size: Int get() = byPriority.size

    /**
     * 落在 [box] 内、且 priority **严格大于** [thresholdExclusive] 的点，追加进 [out]。
     *
     * @param workIds 非空时只取这些作品的点，并且**忽略阈值**（作品模式 / chips 的语义，
     *   与抽取前的 `MapEngine.filteredPoints` 一致）。
     * @param out 由调用方复用，避免每枚瓦片都分配。
     *
     * 无状态、无分配、任意线程可并发调用。
     */
    fun query(
        box: MapViewport,
        thresholdExclusive: Double,
        workIds: Set<Int>?,
        out: MutableList<ScenePoint>,
        limit: Int = Int.MAX_VALUE,
    ) {
        if (out.size >= limit) return

        if (workIds != null) {
            for (workId in workIds) {
                val points = byWork[workId] ?: continue
                for (point in points) {
                    if (!box.contains(point.lat, point.lng)) continue
                    out.add(point)
                    if (out.size >= limit) return
                }
            }
            return
        }

        // 与抽取前 MapEngine.candidateIndices 同一套启发式:
        // 大范围＝阈值高、只有头部几十条能过;近距离＝格子少 —— 每次挑更便宜的那条。
        // 这条前缀路径不是可有可无的:z5 的一枚瓦片跨约 11°,按 0.02° 格子算是 30 万格,
        // 而全日本能过 12000m 阈值的点只有几百个。
        val prefix = countAbove(thresholdExclusive)
        val latCells = ceil((box.maxLat - box.minLat) / CELL_SIZE).toInt() + 1
        val lngCells = ceil((box.maxLng - box.minLng) / CELL_SIZE).toInt() + 1

        if (prefix <= latCells.toLong() * lngCells) {
            for (i in 0 until prefix) {
                val point = byPriority[i]
                if (!box.contains(point.lat, point.lng)) continue
                out.add(point)
                if (out.size >= limit) return
            }
            return
        }

        val minLatCell = floor(box.minLat / CELL_SIZE).toInt()
        val maxLatCell = floor(box.maxLat / CELL_SIZE).toInt()
        val minLngCell = floor(box.minLng / CELL_SIZE).toInt()
        val maxLngCell = floor(box.maxLng / CELL_SIZE).toInt()
        for (latCell in minLatCell..maxLatCell) {
            for (lngCell in minLngCell..maxLngCell) {
                val cell = cells[key(latCell, lngCell)] ?: continue
                // 整格的最大 priority 都过不了阈值,整格跳过。
                if (cell.maxPriority <= thresholdExclusive) continue
                for (point in cell.points) {
                    if (point.priority <= thresholdExclusive) continue
                    if (!box.contains(point.lat, point.lng)) continue
                    out.add(point)
                    if (out.size >= limit) return
                }
            }
        }
    }

    /** priority 严格大于 [threshold] 的条数（在降序数组上二分）。 */
    fun countAbove(threshold: Double): Int {
        var low = 0
        var high = priorityDescending.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (priorityDescending[mid] > threshold) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        const val CELL_SIZE = 0.02

        val EMPTY = MapPointIndex(
            version = -1,
            cells = emptyMap(),
            byPriority = emptyArray(),
            priorityDescending = LongArray(0),
            byWork = emptyMap(),
            byId = emptyMap(),
        )

        private fun key(latCell: Int, lngCell: Int): Long =
            (latCell.toLong() shl 32) or (lngCell.toLong() and 0xFFFF_FFFFL)

        private fun cellKeyOf(lat: Double, lng: Double): Long =
            key(floor(lat / CELL_SIZE).toInt(), floor(lng / CELL_SIZE).toInt())

        fun build(points: List<ScenePoint>, version: Int): MapPointIndex {
            if (points.isEmpty()) return MapPointIndex(version, emptyMap(), emptyArray(), LongArray(0), emptyMap(), emptyMap())

            val sorted = points.sortedByDescending { it.priority }
            val byPriority = sorted.toTypedArray()
            val priorityDescending = LongArray(byPriority.size) { byPriority[it].priority.toLong() }

            val buckets = HashMap<Long, MutableList<ScenePoint>>(points.size / 4)
            val byWork = HashMap<Int, MutableList<ScenePoint>>()
            val byId = HashMap<String, ScenePoint>(points.size)
            for (point in points) {
                buckets.getOrPut(cellKeyOf(point.lat, point.lng)) { ArrayList(2) }.add(point)
                byWork.getOrPut(point.bangumiId) { ArrayList(8) }.add(point)
                byId[point.id] = point
            }
            val cells = HashMap<Long, Cell>(buckets.size)
            for ((cellKey, bucket) in buckets) {
                var max = Long.MIN_VALUE
                for (point in bucket) if (point.priority.toLong() > max) max = point.priority.toLong()
                cells[cellKey] = Cell(bucket, max)
            }

            return MapPointIndex(version, cells, byPriority, priorityDescending, byWork, byId)
        }
    }
}

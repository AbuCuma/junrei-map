package cn.anitabi.map.data

import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.ScenePoint

/** 「巡礼记录」列表里的一部作品：已完成的地标按打卡时间倒序。 */
data class VisitedWorkGroup(
    val workId: Int,
    /** 数据集里的作品；理论上不为 null（找不到作品的点归入孤儿）。 */
    val work: BangumiLite,
    val visited: List<VisitedEntry>,
    /** 该作品在当前数据集里的地标总数（进度分母）。 */
    val totalPoints: Int,
) {
    val latestVisitedAt: Long get() = visited.firstOrNull()?.record?.visitedAt ?: 0L
}

data class VisitedEntry(val point: ScenePoint, val record: VisitRecord)

/** 记录按当前数据集聚类后的结果。 */
data class PilgrimageLogGroups(
    val groups: List<VisitedWorkGroup>,
    /** 记录里有、当前数据集里已经没有的地标数（数据集更新后被删/被合并的点）。只显示计数。 */
    val orphanCount: Int,
) {
    val visitedCount: Int get() = groups.sumOf { it.visited.size }
    val workCount: Int get() = groups.size

    companion object {
        val EMPTY = PilgrimageLogGroups(emptyList(), 0)

        /**
         * 纯函数：按**当前数据集**的作品归属聚类（记录里存的 bangumiId 只是孤儿时的线索，不用于分组），
         * 组内按打卡时间倒序，组间按各自最新一次打卡倒序。
         */
        fun build(
            records: Map<String, VisitRecord>,
            pointById: (String) -> ScenePoint?,
            bangumiById: (Int) -> BangumiLite?,
            pointCountOf: (Int) -> Int,
        ): PilgrimageLogGroups {
            if (records.isEmpty()) return EMPTY
            val byWork = LinkedHashMap<Int, MutableList<VisitedEntry>>()
            var orphans = 0
            for ((id, record) in records) {
                val point = pointById(id)
                if (point == null) {
                    orphans++
                    continue
                }
                byWork.getOrPut(point.bangumiId) { ArrayList() }.add(VisitedEntry(point, record))
            }
            val groups = ArrayList<VisitedWorkGroup>(byWork.size)
            for ((workId, entries) in byWork) {
                val work = bangumiById(workId)
                if (work == null) {
                    orphans += entries.size
                    continue
                }
                entries.sortByDescending { it.record.visitedAt }
                groups.add(VisitedWorkGroup(workId, work, entries, totalPoints = pointCountOf(workId)))
            }
            groups.sortByDescending { it.latestVisitedAt }
            return PilgrimageLogGroups(groups, orphans)
        }
    }
}

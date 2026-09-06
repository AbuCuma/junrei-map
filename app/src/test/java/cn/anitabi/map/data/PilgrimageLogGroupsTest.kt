package cn.anitabi.map.data

import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.ScenePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilgrimageLogGroupsTest {

    private val works = listOf(1, 2).associateWith {
        BangumiLite(
            id = it, cn = "作品$it", title = null, city = "日本", colorHex = null, cover = null,
            cat = "TV", center = null, zoom = null, points = emptyList(),
        )
    }
    private val points = listOf(
        ScenePoint("a", 1, 35.0, 139.0, 0),
        ScenePoint("b", 1, 35.1, 139.1, 0),
        ScenePoint("c", 2, 35.2, 139.2, 0),
        ScenePoint("orphan-work", 9, 35.3, 139.3, 0), // 作品 9 不在数据集里
    ).associateBy { it.id }
    private val counts = mapOf(1 to 5, 2 to 1)

    private fun build(records: Map<String, VisitRecord>) =
        PilgrimageLogGroups.build(records, points::get, works::get, { counts[it] ?: 0 })

    @Test
    fun emptyRecordsGiveEmptyGroups() {
        assertEquals(PilgrimageLogGroups.EMPTY, build(emptyMap()))
    }

    @Test
    fun groupsByCurrentDatasetAndSortsByLatestVisit() {
        val groups = build(
            mapOf(
                "a" to VisitRecord(bangumiId = 1, visitedAt = 10L),
                "c" to VisitRecord(bangumiId = 2, visitedAt = 50L),
                // 记录里写的是作品 7,但当前数据集说 b 属于作品 1 —— 以数据集为准
                "b" to VisitRecord(bangumiId = 7, visitedAt = 30L),
            ),
        )
        assertEquals(listOf(2, 1), groups.groups.map { it.workId })
        val work1 = groups.groups.single { it.workId == 1 }
        assertEquals(listOf("b", "a"), work1.visited.map { it.point.id })
        assertEquals(5, work1.totalPoints)
        assertEquals(3, groups.visitedCount)
        assertEquals(2, groups.workCount)
        assertEquals(0, groups.orphanCount)
    }

    @Test
    fun missingPointsAndMissingWorksCountAsOrphans() {
        val groups = build(
            mapOf(
                "gone" to VisitRecord(1, 1L),
                "orphan-work" to VisitRecord(9, 2L),
                "a" to VisitRecord(1, 3L),
            ),
        )
        assertEquals(2, groups.orphanCount)
        assertEquals(listOf(1), groups.groups.map { it.workId })
        assertTrue(groups.groups.single().visited.single().point.id == "a")
    }
}

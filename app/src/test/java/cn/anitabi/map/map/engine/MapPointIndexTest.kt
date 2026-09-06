package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.ScenePoint
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不可变空间索引。与暴力枚举逐一对拍，并钉住线程安全与「持引用不持下标」。 */
class MapPointIndexTest {

    private fun point(id: String, lat: Double, lng: Double, priority: Int, work: Int = 1) =
        ScenePoint(id = id, bangumiId = work, lat = lat, lng = lng, priority = priority)

    private fun corpus(seed: Int, count: Int): List<ScenePoint> {
        val random = Random(seed)
        return List(count) { i ->
            point(
                id = "p$i",
                lat = 24.0 + random.nextDouble() * 22.0,   // 日本纬度带
                lng = 123.0 + random.nextDouble() * 24.0,
                // priority 跨越好几个数量级,让前缀扫描与格子扫描两条路都被走到
                priority = when (random.nextInt(4)) {
                    0 -> random.nextInt(0, 30)
                    1 -> random.nextInt(30, 1_000)
                    2 -> random.nextInt(1_000, 60_000)
                    else -> random.nextInt(60_000, 2_000_000)
                },
                work = random.nextInt(1, 40),
            )
        }
    }

    private fun brute(points: List<ScenePoint>, box: MapViewport, threshold: Double) =
        points.filter { box.contains(it.lat, it.lng) && it.priority > threshold }.toSet()

    /**
     * 与暴力枚举对拍。盒子大小刻意从「整个日本」扫到「一个街区」，
     * 好让 [MapPointIndex.query] 的两条分支（priority 前缀扫描 / 格子扫描）都被覆盖。
     */
    @Test
    fun queryMatchesBruteForce() {
        val points = corpus(seed = 7, count = 5_000)
        val index = MapPointIndex.build(points, version = 1)
        val random = Random(99)

        repeat(200) {
            val centerLat = 24.0 + random.nextDouble() * 22.0
            val centerLng = 123.0 + random.nextDouble() * 24.0
            val half = 0.005 + random.nextDouble() * 12.0
            val box = MapViewport(centerLat - half, centerLat + half, centerLng - half, centerLng + half)
            val threshold = listOf(-1.0, 0.0, 50.0, 1_000.0, 12_000.0, 100_000.0).random(random)

            val out = ArrayList<ScenePoint>()
            index.query(box, threshold, workIds = null, out = out)
            assertEquals(
                "box=±$half threshold=$threshold",
                brute(points, box, threshold),
                out.toSet(),
            )
        }
    }

    /** 作品过滤忽略阈值 —— 与抽取前 `filteredPoints` 的语义一致。 */
    @Test
    fun workFilterIgnoresTheThreshold() {
        val points = listOf(
            point("a", 35.0, 139.0, priority = 0, work = 1),
            point("b", 35.0, 139.0, priority = 5_000, work = 2),
        )
        val index = MapPointIndex.build(points, version = 1)
        val box = MapViewport(34.0, 36.0, 138.0, 140.0)

        val out = ArrayList<ScenePoint>()
        index.query(box, thresholdExclusive = 1_000.0, workIds = setOf(1), out = out)
        assertEquals(listOf("a"), out.map { it.id })
    }

    /** 整格 maxPriority 都过不了阈值时被跳过，且跳过不能丢点。 */
    @Test
    fun cellPruningNeverDropsAQualifyingPoint() {
        val points = corpus(seed = 3, count = 2_000)
        val index = MapPointIndex.build(points, version = 1)
        // 小盒子 → 走格子扫描分支
        val box = MapViewport(35.0, 35.2, 139.0, 139.2)
        for (threshold in listOf(-1.0, 10.0, 500.0, 50_000.0)) {
            val out = ArrayList<ScenePoint>()
            index.query(box, threshold, workIds = null, out = out)
            assertEquals("threshold=$threshold", brute(points, box, threshold), out.toSet())
        }
    }

    @Test
    fun limitStopsEarly() {
        val points = corpus(seed = 11, count = 3_000)
        val index = MapPointIndex.build(points, version = 1)
        val out = ArrayList<ScenePoint>()
        index.query(MapViewport(0.0, 90.0, 0.0, 180.0), -1.0, null, out, limit = 25)
        assertEquals(25, out.size)
    }

    @Test
    fun countAboveMatchesBruteForce() {
        val points = corpus(seed = 5, count = 1_000)
        val index = MapPointIndex.build(points, version = 1)
        for (threshold in listOf(-1.0, 0.0, 100.0, 5_000.0, 1_000_000.0, 9e9)) {
            assertEquals(
                "threshold=$threshold",
                points.count { it.priority > threshold },
                index.countAbove(threshold),
            )
        }
    }

    /**
     * 索引会被 GMS 的瓦片线程、主线程的命中测试、引擎线程同时读。
     * 这条就是它存在的全部理由 —— 任何意外的共享可变状态都会在这里炸出来。
     */
    @Test
    fun concurrentQueriesAgreeWithSingleThreadedResult() {
        val points = corpus(seed = 21, count = 4_000)
        val index = MapPointIndex.build(points, version = 1)
        val box = MapViewport(34.0, 36.0, 138.0, 141.0)
        val expected = brute(points, box, 100.0)

        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(8) {
                Callable {
                    repeat(500) {
                        val out = ArrayList<ScenePoint>()
                        index.query(box, 100.0, workIds = null, out = out)
                        if (out.toSet() != expected) return@Callable false
                    }
                    true
                }
            }
            val results = pool.invokeAll(tasks, 60, TimeUnit.SECONDS)
            assertTrue("并发查询结果与单线程不一致", results.all { it.get() })
        } finally {
            pool.shutdownNow()
        }
    }

    /** AGENTS.md 的硬规则：派生索引持有对象引用，不持下标。用可执行的形式钉住。 */
    @Test
    fun indexHoldsPointReferencesNotOffsets() {
        val points = corpus(seed = 2, count = 50)
        val index = MapPointIndex.build(points, version = 1)
        val seventh = points[7]
        assertSame(seventh, index.byId[seventh.id])
        assertTrue(index.byWork.getValue(seventh.bangumiId).any { it === seventh })
    }

    @Test
    fun emptyCorpusIsQueryable() {
        val index = MapPointIndex.build(emptyList(), version = 1)
        val out = ArrayList<ScenePoint>()
        index.query(MapViewport(-90.0, 90.0, -180.0, 180.0), -1.0, null, out)
        assertTrue(out.isEmpty())
        assertEquals(0, MapPointIndex.EMPTY.size)
    }
}

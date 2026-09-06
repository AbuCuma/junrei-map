package cn.anitabi.map.data.search

import cn.anitabi.map.data.AnitabiDataLoader
import cn.anitabi.map.data.AnitabiJsonParser
import cn.anitabi.map.data.model.PointDetail
import java.io.File
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实数据上的索引构建与查询耗时。与 RealDataSnapshotTest 一样由
 * `ANITABI_SNAPSHOT_DIR` 门控，CI 里自动跳过。
 *
 * 断言刻意放得很宽：它要拦的是**数量级**回归（比如某次改动把线性扫描变成了
 * 每次按键重建索引），而不是几毫秒的抖动 —— 后者在共享 CI 机器上必然假阳性。
 * 真正有用的是 println 出来的数字，改动前后对比着看。
 *
 * 运行：`ANITABI_SNAPSHOT_DIR=/path/to/dir ./gradlew :app:testDebugUnitTest`
 */
class SearchBenchmarkTest {

    private val dir: File? = System.getenv("ANITABI_SNAPSHOT_DIR")?.let(::File)

    /** 中英日 + 极端形状各取几条。`の` 与 `a` 是最坏情况（指纹预筛几乎排除不掉东西）。 */
    private val queries = listOf(
        "の", "a", "駅", "东京", "機構", "机构", "孤独摇滚", "ぼっち",
        "bocchi", "k-on", "新宿", "らめん", "!!",
    )

    @Test
    fun indexBuildAndQueryStayWithinOrderOfMagnitude() {
        assumeTrue(dir?.resolve("g.json")?.exists() == true)
        val d = dir!!

        var dataset = checkNotNull(AnitabiJsonParser.parseBangumiList(d.resolve("g.json").readText()))
        val details = HashMap<String, PointDetail>()
        val modified = HashMap<Int, Double>()
        for (shard in 0 until AnitabiDataLoader.DETAIL_SHARD_COUNT) {
            val f = d.resolve("g$shard.json")
            if (f.exists()) AnitabiJsonParser.parsePointDetails(f.readText(), details, modified)
        }
        dataset = dataset.copy(points = AnitabiJsonParser.merge(details, dataset.points))

        lateinit var index: SearchIndex
        val buildMillis = measureTimeMillis {
            index = SearchIndex.build(dataset.bangumis, dataset.points)
        }
        println(
            "[search] 索引构建 ${buildMillis}ms" +
                "（作品 ${dataset.bangumis.size} / 地标 ${dataset.points.size}）"
        )

        // 预热一轮再计时:第一次查询还在 JIT 编译 indexOf 的路径上。
        for (q in queries) index.query(TextFold.fold(q), 10, 50)

        val samples = queries.map { q ->
            val folded = TextFold.fold(q)
            val ms = measureTimeMillis { repeat(5) { index.query(folded, 10, 50) } } / 5.0
            println("[search] \"$q\" → ${"%.1f".format(ms)}ms")
            ms
        }
        val worst = samples.max()
        println("[search] 最慢 ${"%.1f".format(worst)}ms")

        // apply() 每次冷启动跑 2-3 次,索引构建直接推迟首帧地图 —— 这条是其中最要紧的。
        assertTrue("索引构建 ${buildMillis}ms 过慢", buildMillis < 2_000)
        assertTrue("最慢查询 ${worst}ms 过慢", worst < 150)
    }
}

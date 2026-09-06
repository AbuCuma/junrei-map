package cn.anitabi.map.data.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.anitabi.map.data.AnitabiDataLoader
import cn.anitabi.map.data.model.NameLocale
import cn.anitabi.map.data.AnitabiJsonParser
import cn.anitabi.map.data.model.PointDetail
import java.io.File
import kotlin.system.measureTimeMillis
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 搜索的真机实测**工具**，不是测试。
 *
 * 数据从 `/data/local/tmp/anitabi-snapshot/` 读（见下），用途只有两个：
 *   1. 拿到 ARM 上的真实耗时 —— JVM 上的数字乘一个猜出来的系数不算数；
 *   2. 确认跨语种命中在真机的 ART/ICU 环境下与 JVM 单测结论一致。
 *
 * 注意：与 `androidTest/` 下的另外三个 harness 一样，它**无断言、需要预置状态**，
 * `connectedCheck` 跑过它不代表任何验证（README「测试说明」、AGENTS.md）。
 * 真正的正确性保证在 `app/src/test/.../data/search/` 的 JVM 单测里。
 *
 * 运行：
 * ```
 * adb shell mkdir -p /data/local/tmp/anitabi-snapshot
 * adb push g.json g0.json … /data/local/tmp/anitabi-snapshot/
 * # 去掉 @Ignore 后
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=cn.anitabi.map.data.search.SearchDeviceHarness
 * adb logcat -d -s System.out:I | grep '\[dev\]'
 * ```
 */
@RunWith(AndroidJUnit4::class)
@Ignore("真机工具,非测试。手工去掉 @Ignore 再跑")
class SearchDeviceHarness {

    @Test
    fun measureAndProbe() {
        // 数据从 /data/local/tmp 读,不用应用自己的缓存 —— connectedAndroidTest 跑完会
        // 卸载被测应用,缓存每次都会被清掉,靠"先启动一次应用"根本站不住。
        //   adb push g.json g0.json ... /data/local/tmp/anitabi-snapshot/
        val dir = File("/data/local/tmp/anitabi-snapshot")
        val list = File(dir, "g.json")
        check(list.exists()) { "先 adb push 数据到 ${dir.absolutePath}" }

        var dataset = checkNotNull(AnitabiJsonParser.parseBangumiList(list.readText()))
        val details = HashMap<String, PointDetail>()
        val modified = HashMap<Int, Double>()
        for (shard in 0 until AnitabiDataLoader.DETAIL_SHARD_COUNT) {
            val f = File(dir, "g$shard.json")
            if (f.exists()) AnitabiJsonParser.parsePointDetails(f.readText(), details, modified)
        }
        dataset = dataset.copy(points = AnitabiJsonParser.merge(details, dataset.points))

        lateinit var index: SearchIndex
        val buildMs = measureTimeMillis { index = SearchIndex.build(dataset.bangumis, dataset.points) }
        println("[dev] 索引构建 ${buildMs}ms（作品 ${dataset.bangumis.size} / 地标 ${dataset.points.size}）")

        // 跨语种探针。左边是查询，右边是它应当能找到的那类写法。
        val probes = listOf(
            "机构" to "简体 → 日文汉字（機構）",
            "东京" to "简体 → 東京",
            "渡月桥" to "简体 → 嵐山渡月橋",
            "広島" to "新字体",
            "廣島" to "繁体",
            "广岛" to "简体",
            "bocchi" to "英文名（en）",
            "guduyaogun" to "拼音全拼",
            "gdyg" to "拼音首字母",
            "ぼざろ" to "日文简称（tAbbr）",
            "qlhw" to "别名（tags）",
            "の" to "最坏情况:单假名",
            "a" to "最坏情况:单拉丁字母",
        )
        for ((q, label) in probes) {
            val folded = TextFold.fold(q)
            repeat(3) { index.query(folded, 10, 50) } // 预热
            var hits: SearchIndex.Hits? = null
            val ms = measureTimeMillis { repeat(5) { hits = index.query(folded, 10, 50) } } / 5.0
            val h = hits!!
            val sample = h.works.firstOrNull()?.displayName(NameLocale.Zh)
                ?: h.points.firstOrNull()?.displayName(NameLocale.Zh) ?: "—"
            println("[dev] \"$q\" [$label] → 作品 ${h.works.size} / 地标 ${h.points.size}," +
                " 首条「$sample」, ${"%.1f".format(ms)}ms")
        }
    }
}

package cn.anitabi.map.data.search

import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.ScenePoint
import java.util.PriorityQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.exp
import kotlin.math.ln

/**
 * 搜索索引。**整体不可变**，只在 `AnitabiStore.apply` 里被替换一次。
 *
 * 本包（`data/search/`）是继 `map/engine/` 之后的第二个**纯 Kotlin 岛**：不 import
 * `android.*`，也不 import `org.json`。搜索的全部逻辑都在这里，因此 JVM 单测能覆盖到
 * 每一条规则 —— 这是把它从 `AnitabiStore` 里抽出来的唯一理由。
 *
 * 刻意**不是** data class：自动生成的 `equals` 会对两个 5 万元素数组逐一比较，
 * 而调用方 `AnitabiStore.indexes` 用的正是 `referentialEqualityPolicy`（见其注释）。
 */
class SearchIndex private constructor(
    /** 每条记录的已折叠可搜索文本，字段间以 [TextFold.FIELD_SEP] 分隔。与 [owners] 等长。 */
    private val keys: Array<String>,
    /**
     * 每条 key 的字符指纹（superimposed coding，比倒排索引更早的经典手法）。
     *
     * 查询的指纹不是某条 key 指纹的子集，那条 key 就必然不含该查询 —— 一次
     * 与运算即可排除，省掉一次 `indexOf`。`LongArray(51500)` 只有 412KB。
     *
     * 它只降常数、不改语义：漏判不可能（子集关系是必要条件），误判只是白跑一次
     * `indexOf`。单字符查询（`の`）几乎排除不掉任何东西，会退回全扫 —— 那条路径的
     * 安全性由 [TopK] 的固定容量保证，不依赖本预筛。
     */
    private val signatures: LongArray,
    /**
     * 每条 key 背后的对象。前 [workCount] 项是 [BangumiLite]，其后是 [ScenePoint]。
     *
     * **持有对象本身，绝不存 `points` / `bangumis` 的下标。** 下标会让索引依赖另一个容器的
     * 长度，读到上一代索引配当前代数据就会越界（真实事故，见 AGENTS.md「派生索引也不要按
     * 下标引用另一个容器」与回归测试 `searchStaysConsistentWhileDataIsBeingReplaced`）。
     * 持有引用后索引自洽 —— 最坏只是返回上一代的结果。
     */
    private val owners: Array<Any>,
    private val workCount: Int,
) {

    /** 一次查询的命中。作品与地标分开，因为 UI 分节展示且各有各的上限。 */
    class Hits(val works: List<BangumiLite>, val points: List<ScenePoint>)

    /**
     * 子串查询，按相关度取前 K 条。
     *
     * @param needle 已经过 [TextFold.fold] 的查询串。
     * @param distanceOf 命中地标到当前位置的距离（米）。null＝没有定位。
     *   **只参与「选哪 50 条」，不决定展示顺序** —— 展示顺序仍由调用方按距离排。
     */
    fun query(
        needle: String,
        workLimit: Int,
        sceneLimit: Int,
        distanceOf: ((ScenePoint) -> Double)? = null,
        isActive: () -> Boolean = { true },
    ): Hits {
        if (needle.isEmpty() || workLimit <= 0 && sceneLimit <= 0) return EMPTY_HITS

        // 有界堆:命中一万条也只占 limit 个槽,绝不对全部命中做一次全排序。
        val works = TopK(workLimit)
        val scenes = TopK(sceneLimit)
        val want = signatureOf(needle)

        for (i in keys.indices) {
            // 协作式取消。打字很快时上一次查询还没跑完就已经作废了,让它尽早退出。
            // **抛异常而非返回空**:返回 Hits 会让调用方把空结果写进 state 并闪一下空态,
            // 破坏 produceState「打字不闪空」的性质。
            if (i and CANCEL_CHECK_MASK == 0 && !isActive()) {
                throw CancellationException("search superseded")
            }
            if (signatures[i] and want != want) continue

            val isWork = i < workCount
            val heap = if (isWork) works else scenes
            if (heap.limit == 0) continue

            val base = scoreOf(keys[i], needle, if (isWork) WORK_WEIGHTS else POINT_WEIGHTS)
            if (base <= 0.0) continue

            val bonus = if (isWork) {
                // 地标数由多到少＝信息量由多到少(与 Web 版排序同一思路)。
                // 用 ln 而非线性:否则一个 900 点的作品会压过所有精确标题命中。
                ln(1.0 + (owners[i] as BangumiLite).points.size) * 12.0
            } else {
                // 有定位时,相关度接近的候选里近的优先挤进 top-K。
                distanceOf?.let { 60.0 * exp(-it(owners[i] as ScenePoint) / 5_000.0) } ?: 0.0
            }
            heap.offer(i, base + bonus)
        }

        @Suppress("UNCHECKED_CAST")
        return Hits(
            works.drain().map { owners[it] as BangumiLite },
            scenes.drain().map { owners[it] as ScenePoint },
        )
    }

    // MARK: - 打分

    /**
     * 一条 key 对 [needle] 的相关度。0＝未命中。
     *
     * 取所有命中位置里的最高分：同一条记录可能在多个字段命中，
     * 应当按**最好的**那次算，而不是首次出现的那次。
     */
    private fun scoreOf(key: String, needle: String, weights: DoubleArray): Double {
        var best = 0.0
        var from = 0
        while (true) {
            val at = key.indexOf(needle, from)
            if (at < 0) break

            val end = at + needle.length
            val fieldStart = key.lastIndexOf(TextFold.FIELD_SEP, at - 1) + 1
            var fieldEnd = key.indexOf(TextFold.FIELD_SEP, end)
            if (fieldEnd < 0) fieldEnd = key.length

            val kind = when {
                fieldStart == at && fieldEnd == end -> EXACT_FIELD
                fieldStart == at -> FIELD_PREFIX
                else -> IN_FIELD
            }
            // 字段越短,同一处命中的信息量越大(Lucene fieldNorm 的同一思路):
            // 「宿」命中「新宿」远比命中一个四十字的长名有意义。
            val norm = 12.0 / (12.0 + (fieldEnd - fieldStart))
            val weight = weights.getOrElse(fieldIndexAt(key, at)) { weights.last() }
            val score = kind * weight * (0.6 + 0.4 * norm)
            if (score > best) best = score

            // 已经是该 key 可能的最高档,不必再找后面的命中。
            if (kind == EXACT_FIELD && weight >= 1.0) break
            from = at + 1
        }
        return best
    }

    /** [at] 落在第几个字段＝它前面有几个分隔符。 */
    private fun fieldIndexAt(key: String, at: Int): Int {
        var count = 0
        for (i in 0 until at) if (key[i] == TextFold.FIELD_SEP) count++
        return count
    }

    /**
     * 容量固定的最小堆。超过容量就丢掉当前最差的一条。
     *
     * 分数相同时**保留下标小的**（＝数据集顺序靠前的），这样同分结果的输出是确定的，
     * 单测才钉得住。
     */
    private class TopK(val limit: Int) {
        private val heap = PriorityQueue<Entry>(
            maxOf(limit, 1),
            compareBy<Entry> { it.score }.thenByDescending { it.index },
        )

        fun offer(index: Int, score: Double) {
            if (limit == 0) return
            heap.add(Entry(index, score))
            if (heap.size > limit) heap.poll()
        }

        /** 按分数降序、同分按下标升序取出。 */
        fun drain(): List<Int> =
            heap.sortedWith(compareByDescending<Entry> { it.score }.thenBy { it.index })
                .map { it.index }

        private class Entry(val index: Int, val score: Double)
    }

    companion object {
        private val EMPTY_HITS = Hits(emptyList(), emptyList())

        val EMPTY = SearchIndex(emptyArray(), LongArray(0), emptyArray(), workCount = 0)

        /** 每扫这么多条查一次取消。2048 条大约几十微秒，足够及时又不至于让检查本身可测。 */
        private const val CANCEL_CHECK_MASK = 2047

        /**
         * 字符指纹：把每个字符散列到 64 位里的 1 位。
         *
         * 用斐波那契散列取高 6 位 —— 相邻码位（`あいうえお`、`abc`）必须散开到不同的位，
         * 直接取低位的话整段假名会挤在几个 bit 上，预筛就基本失效了。
         */
        private fun signatureOf(text: String): Long {
            var sig = 0L
            for (ch in text) {
                if (ch == TextFold.FIELD_SEP) continue
                sig = sig or (1L shl (ch.code * -0x61c88647 ushr 26))
            }
            return sig
        }

        // 命中档位。差值远大于任何权重/长度归一的影响 —— 精确命中永远排在前缀命中之前。
        private const val EXACT_FIELD = 1000.0
        private const val FIELD_PREFIX = 700.0
        private const val IN_FIELD = 300.0

        /**
         * 作品的字段槽。**顺序即 [WORK_WEIGHTS] 的下标**，增删必须同步改两处。
         *
         * `tags` 是多值的，所以固定放在最后 —— 它铺开成若干个槽，下标越界后
         * [scoreOf] 会退到 `weights.last()`，也就是按 tags 的权重计。
         */
        // 作品的固定字段槽下标。改动必须与 workFields / WORK_WEIGHTS 三处同步。
        private const val W_CN = 0
        private const val W_TITLE = 1
        private const val W_ABBR = 3
        private const val W_TABBR = 4
        private const val WORK_FIXED_FIELDS = 7 // 其后是 tags，再其后是转写

        private fun workFields(b: BangumiLite): List<String?> =
            listOf(b.cn, b.title, b.en, b.abbr, b.titleAbbr, b.cat, b.city) + b.tags


        /**
         * cat/city 压到极低是必要的：它们是「TV」「日本」这种全库共用的值，
         * 等权的话查 `TV` 会把所有 TV 番剧灌进结果，把真正的标题命中挤出去。
         *
         * 最后一项是 tags 的权重，同时兼作越界兜底 —— 见 [workFields]。
         */
        private val WORK_WEIGHTS = doubleArrayOf(1.0, 1.0, 0.95, 0.9, 0.9, 0.25, 0.2, 0.85)

        /** 地标的字段槽：`name`、`nameCn`，其后是转写。 */
        private val POINT_WEIGHTS = doubleArrayOf(1.0, 1.0, 0.85)

        /**
         * 在已折叠的 key 末尾追加转写字段：日文假名的罗马字、中文的拼音全拼与首字母。
         *
         * 这些槽的下标必然 ≥ 固定字段数，[scoreOf] 取 `weights.last()`，
         * 也就是与 tags 同档 —— 转写命中不该盖过真正的标题命中。
         *
         * **按语种分开传**是刻意的：只给中文字段生成拼音、只给日文字段生成罗马字。
         * 给日文汉字生成中文读音（`東京駅` → `dongjingyi`）既无意义，又让索引凭空翻倍。
         */
        private fun StringBuilder.appendTransliterations(chinese: List<String>, japanese: List<String>) {
            // 传进来的字段都已折叠过,这里绝不能再折 —— 那是索引构建里最贵的一步。
            val kana = if (japanese.size == 1) japanese[0] else japanese.joinToString("")
            val roman = Romaji.of(kana)
            if (roman.isNotEmpty()) append(TextFold.FIELD_SEP).append(roman)

            val full = StringBuilder()
            val initials = StringBuilder()
            for (field in chinese) {
                for (ch in field) {
                    val syllable = PinyinTable.of(ch) ?: continue
                    full.append(syllable)
                    initials.append(syllable[0])
                }
            }
            if (full.isNotEmpty()) append(TextFold.FIELD_SEP).append(full)
            // 首字母串至少两个字才有意义,一个字的首字母跟一个字母的查询没区别,纯噪声。
            if (initials.length >= 2) append(TextFold.FIELD_SEP).append(initials)
        }

        fun build(bangumis: List<BangumiLite>, points: List<ScenePoint>): SearchIndex {
            val keys = ArrayList<String>(bangumis.size + points.size)
            val owners = ArrayList<Any>(bangumis.size + points.size)

            // 作品只有 1514 条，怎么写都不影响构建耗时，取可读的写法。
            for (bangumi in bangumis) {
                val folded = workFields(bangumi).map(TextFold::fold)
                val sb = StringBuilder(TextFold.joinFields(folded))
                sb.appendTransliterations(
                    // 中文轴只取 cn / abbr / tags；日文标题不生成中文读音（见 PinyinTable）。
                    chinese = listOf(folded[W_CN], folded[W_ABBR]) + folded.drop(WORK_FIXED_FIELDS),
                    japanese = listOf(folded[W_TITLE], folded[W_TABBR]),
                )
                keys.add(sb.toString())
                owners.add(bangumi)
            }
            val workCount = keys.size

            // 地标有 5 万条，是整条构建的热路径：不建中间 List、不走 joinToString。
            for (point in points) {
                // 名称全空的点搜不到也没有意义（GeometryOnly 阶段全部如此）。
                if (point.name == null && point.nameCn == null) continue
                val name = TextFold.fold(point.name)
                val nameCn = TextFold.fold(point.nameCn)
                val sb = StringBuilder(name.length + nameCn.length + 32)
                sb.append(name).append(TextFold.FIELD_SEP).append(nameCn)
                sb.appendTransliterations(chinese = listOf(nameCn), japanese = listOf(name))
                keys.add(sb.toString())
                owners.add(point)
            }

            return SearchIndex(
                keys.toTypedArray(),
                LongArray(keys.size) { signatureOf(keys[it]) },
                owners.toTypedArray(),
                workCount,
            )
        }
    }
}

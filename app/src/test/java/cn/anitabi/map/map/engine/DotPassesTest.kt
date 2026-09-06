package cn.anitabi.map.map.engine

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分趟的护栏。
 *
 * **要护住的性质是「同一趟内任意两个圆点互不重叠」**，不是别的什么近似指标 ——
 * 上一轮圆点尺寸的 bug 之所以能发出去，正是因为护栏测的是静态误差而不是要护的性质。
 * 所以这里额外跑一遍 [everythingInOnePassViolatesTheInvariant]：把「全部塞进第 0 趟」
 * （也就是改动之前的画法）喂给同一条判据，确认它**确实会被判掉**。
 */
class DotPassesTest {

    private val zoom = 16.0
    private val dpPerWorld = MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom)
    private val conflictWorld = DotPasses.conflictDistanceDp(zoom) / dpPerWorld

    /** 沿一条直线按 [spacing]×冲突距离排开的 n 个点。 */
    private fun line(n: Int, spacing: Double): DoubleArray {
        val world = DoubleArray(n * 2)
        for (i in 0 until n) {
            world[i * 2] = 0.5 + conflictWorld * spacing * i
            world[i * 2 + 1] = 0.5
        }
        return world
    }

    /** 同一趟内所有点对的最小距离（HIDDEN 不算 —— 它们不绘制）。返回 -1 表示每趟都不足两个点。 */
    private fun minSamePassDistance(world: DoubleArray, pass: IntArray): Double {
        var min = Double.MAX_VALUE
        for (a in pass.indices) {
            if (pass[a] == DotPasses.HIDDEN) continue
            for (b in a + 1 until pass.size) {
                if (pass[b] == DotPasses.HIDDEN) continue
                if (pass[a] != pass[b]) continue
                val dx = world[a * 2] - world[b * 2]
                val dy = world[a * 2 + 1] - world[b * 2 + 1]
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance < min) min = distance
            }
        }
        return if (min == Double.MAX_VALUE) -1.0 else min
    }

    @Test
    fun pointsInTheSamePassNeverOverlap() {
        // 0.35 倍冲突距离 ⇒ 每个点与前后各两三个点都冲突，必须靠分趟拆开。
        // 遍历序打乱（真实数据的 priority 沿街道不单调）—— 单调链是另一个测试。
        val world = line(60, spacing = 0.35)
        val order = (0 until 60).shuffled(java.util.Random(7)).toIntArray()
        val result = DotPasses.assign(world, order, conflictWorld)

        val min = minSamePassDistance(world, result.pass)
        assertTrue("同趟内出现了重叠：最小距离 $min < 冲突距离 $conflictWorld", min >= conflictWorld)
        assertEquals("乱序 priority 下这种密度不该有点被藏起来", 0, result.hidden)
        assertTrue("应当用到多趟，实际 ${result.passCount}", result.passCount >= 3)
    }

    /**
     * **闪烁的直接护栏**：任何**重叠对**的上下关系必须由遍历序（＝priority 全序）决定，
     * 与冲突图的其余部分无关 —— 冲突图随 zoom 连续变，凡依赖它的性质都会在缩放中翻转。
     *
     * 这正是旧「最高空趟」贪心的反例：A(先)→31、C(与 A 冲突)→30、B(只与 C 冲突)→31，
     * B 反而压在 C 上；换个 zoom，A 与 C 不再冲突，C→31、B→30 —— 同一对 B/C 翻面了。
     * min−1 规则下 B 必须低于 C，无论 A 在不在。
     */
    @Test
    fun overlappingPairsAreAlwaysStackedByOrder() {
        // 显式的 A/C/B 反例（间距 0.9d：相邻冲突、隔一个不冲突）。
        val abc = line(3, spacing = 0.9)
        val r = DotPasses.assign(abc, intArrayOf(0, 1, 2), conflictWorld)
        assertTrue("B(2) 必须低于与之重叠的 C(1)", r.pass[2] < r.pass[1])

        // 随机点集全量验证。
        val world = line(80, spacing = 0.45)
        val order = (0 until 80).shuffled(java.util.Random(11)).toIntArray()
        val rank = IntArray(80); order.forEachIndexed { position, index -> rank[index] = position }
        val result = DotPasses.assign(world, order, conflictWorld)
        for (a in 0 until 80) {
            for (b in a + 1 until 80) {
                if (result.pass[a] == DotPasses.HIDDEN || result.pass[b] == DotPasses.HIDDEN) continue
                val dx = world[a * 2] - world[b * 2]
                val dy = world[a * 2 + 1] - world[b * 2 + 1]
                if (dx * dx + dy * dy >= conflictWorld * conflictWorld) continue
                val earlier = if (rank[a] < rank[b]) a else b
                val later = if (rank[a] < rank[b]) b else a
                assertTrue(
                    "重叠对 ($a,$b)：序靠前的 $earlier 必须在更高的趟",
                    result.pass[earlier] > result.pass[later],
                )
            }
        }
    }

    /**
     * 上一条的推论，直接按症状断言一遍：同一批点在两个邻近 zoom 下各分一次趟，
     * 凡在**两个 zoom 里都重叠**的点对，上下关系必须一致 —— 缩放时不闪。
     */
    @Test
    fun stackingIsStableWhileZooming() {
        val world = line(80, spacing = 0.45)
        val order = (0 until 80).shuffled(java.util.Random(23)).toIntArray()
        val near = DotPasses.assign(world, order, conflictWorld)
        val far = DotPasses.assign(world, order, conflictWorld * 1.3) // 缩小 ≈0.38 档
        for (a in 0 until 80) {
            for (b in a + 1 until 80) {
                if (near.pass[a] == DotPasses.HIDDEN || near.pass[b] == DotPasses.HIDDEN) continue
                if (far.pass[a] == DotPasses.HIDDEN || far.pass[b] == DotPasses.HIDDEN) continue
                val dx = world[a * 2] - world[b * 2]
                val dy = world[a * 2 + 1] - world[b * 2 + 1]
                if (dx * dx + dy * dy >= conflictWorld * conflictWorld) continue // 两边都冲突的对
                val nearOrder = near.pass[a] > near.pass[b]
                val farOrder = far.pass[a] > far.pass[b]
                assertEquals("重叠对 ($a,$b) 在缩放中翻面了", nearOrder, farOrder)
            }
        }
    }

    /**
     * min−1 规则的已知代价与它的自愈：priority 沿链**单调**时每个点都必须低于前一个，
     * 32 个点耗尽全部层。但被藏的点**不进冲突网格**，于是链在断点处断开 ——
     * 下一个点没有已放置的冲突邻居，重新从顶层开始。60 个点的单调链只藏 2 个
     * （第 33、34 个），不是 28 个。
     */
    @Test
    fun aMonotonePriorityChainSelfHealsAfterTheCap() {
        val world = line(60, spacing = 0.35)
        val result = DotPasses.assign(world, IntArray(60) { it }, conflictWorld)
        assertEquals(2, result.hidden)
        assertTrue(minSamePassDistance(world, result.pass) >= conflictWorld)
    }

    /** 改动之前的画法（全部一趟）拿同一条判据一量就露馅 —— 护栏证明自己拦得住。 */
    @Test
    fun everythingInOnePassViolatesTheInvariant() {
        val world = line(60, spacing = 0.35)
        val min = minSamePassDistance(world, IntArray(60))
        assertTrue("单趟画法本应违反不变式，却测出 $min", min < conflictWorld)
    }

    @Test
    fun wellSeparatedPointsAllFitInOnePass() {
        val world = line(50, spacing = 3.0)
        val result = DotPasses.assign(world, IntArray(50) { it }, conflictWorld)
        assertEquals(1, result.passCount)
        assertEquals(1, result.pass.toSet().size)
        assertEquals(0, result.hidden)
    }

    /** 同输入必得同输出 —— 否则圆点的层序会在相邻两帧之间闪。 */
    @Test
    fun assignmentIsDeterministic() {
        val world = line(40, spacing = 0.4)
        val order = IntArray(40) { it }
        val a = DotPasses.assign(world, order, conflictWorld)
        val b = DotPasses.assign(world, order, conflictWorld)
        assertTrue(a.pass.contentEquals(b.pass))
    }

    /**
     * 遍历序**靠前**的点分到**更高**的趟 ＝ 画在上层。
     * 调用方（[DotField]）按 priority 降序排，于是最孤立的点顶在最上面，
     * 而挤不下时舍弃的是最不重要的 —— 见 [theLeastImportantPointsAreTheOnesDropped]。
     */
    @Test
    fun earlierInTheOrderMeansAHigherPass() {
        val world = DoubleArray(4)
        for (i in 0 until 2) { world[i * 2] = 0.5; world[i * 2 + 1] = 0.5 }
        val result = DotPasses.assign(world, intArrayOf(1, 0), conflictWorld)
        assertTrue("靠前的 1 应当在更高的趟", result.pass[1] > result.pass[0])
    }

    /**
     * 挤不下时舍弃**最靠后**的点。方向弄反的话，被丢掉的就是最孤立、最该看见的那些
     * —— 那正是「分不开就不画」这条取舍唯一可能出错的地方。
     */
    @Test
    fun theLeastImportantPointsAreTheOnesDropped() {
        val n = DotPasses.MAX_PASSES + 5
        val world = DoubleArray(n * 2)
        for (i in 0 until n) { world[i * 2] = 0.5; world[i * 2 + 1] = 0.5 }
        val result = DotPasses.assign(world, IntArray(n) { it }, conflictWorld)
        assertEquals(5, result.hidden)
        for (i in 0 until DotPasses.MAX_PASSES) {
            assertTrue("靠前的第 $i 个不该被舍弃", result.pass[i] != DotPasses.HIDDEN)
        }
        for (i in DotPasses.MAX_PASSES until n) {
            assertEquals("靠后的第 $i 个应当被舍弃", DotPasses.HIDDEN, result.pass[i])
        }
    }

    /**
     * 密到 32 趟都分不开时**不画**，而不是塞进最后一趟。
     *
     * 塞进去就等于让它们互相吃掉白环 —— 正是本模块要消灭的症状。于是
     * [pointsInTheSamePassNeverOverlap] 是**无条件**成立的，没有「除了封顶那部分」的尾巴。
     */
    @Test
    fun overflowIsHiddenRatherThanMerged() {
        val n = DotPasses.MAX_PASSES + 5
        val world = DoubleArray(n * 2)
        for (i in 0 until n) { world[i * 2] = 0.5; world[i * 2 + 1] = 0.5 }
        val result = DotPasses.assign(world, IntArray(n) { it }, conflictWorld)
        assertEquals(DotPasses.MAX_PASSES, result.passCount)
        assertEquals(5, result.hidden)
        assertEquals(5, result.pass.count { it == DotPasses.HIDDEN })
        // 画出来的那些仍然两两不重叠。
        val drawn = result.pass.indices.filter { result.pass[it] != DotPasses.HIDDEN }
        for (a in drawn) for (b in drawn) {
            if (a >= b) continue
            assertTrue("重合的点不能同趟", result.pass[a] != result.pass[b])
        }
    }

    @Test
    fun emptyInputIsHandled() {
        val result = DotPasses.assign(DoubleArray(0), IntArray(0), conflictWorld)
        assertEquals(0, result.passCount)
        assertEquals(0, result.hidden)
    }
}

package cn.anitabi.map.map.engine

import kotlin.math.floor

/**
 * 把一帧的圆点划分成若干**趟**，使同一趟内任意两个圆点互不重叠。
 *
 * ## 为什么需要
 *
 * 圆点是「白描边 ＋ 实心圆」。批量画法是先把**全部**点的白描边一次画完、再画填充，
 * 于是 B 的填充会盖掉 A 的白环 —— 两个挨近的圆点之间那道白就没了（真机反馈 #4）。
 * 网页版的 Mapbox circle 层是**逐 feature** 画的，所以每个圆点都保有完整白环。
 *
 * ## 为什么分趟等价于逐点画
 *
 * 趟内互不重叠 ⇒ 趟内「先白后彩」与逐点画结果相同；趟之间按序覆盖 ⇒ 后趟的白环正确地
 * 压在前趟的填充上。合起来与逐点 painter's order **逐像素相同**，而绘制调用数仍是
 * `Σ_p (1 + K_p)`，稀疏处退化成今天的 `1 + K`。逐点画则是 2N 次跨界调用，7000 点做不到。
 *
 * ## 冲突判据
 *
 * A 的白环外缘在 `r + stroke`、B 的填充外缘在 `r`，两者不相交的充要条件是
 * 圆心距 ≥ `2r + stroke`。见 [conflictDistanceDp]。
 */
object DotPasses {

    /**
     * 趟数上限。32 是 [Int] 位图的上限，也就是「不额外花代价所能给到的最大值」。
     *
     * **超出的点不画**（[Result.hidden]），而不是塞进最后一趟 —— 塞进去就等于让它们
     * 互相吃掉白环，正是本模块要消灭的那个症状。能撞到 32 趟，意味着该处有 33 个以上
     * 的点两两重叠在一个直径 [conflictDistanceDp] 的圈里，第 33 个本来也看不见。
     * 它们仍留在 [DotField] 里供命中测试，只是不绘制。
     *
     * 东京 z13 实测有三千来个点落到这里（见 [assign] 的 KDoc）—— 更早之前它们是
     * **并进最后一趟**的，也就是几千个圆点在互相吃白环。z15 只有个位数，
     * 所以当初在 z15 上根本测不出来。
     *
     * 于是「同一趟内任意两点互不重叠」是**无条件**成立的，不再有「除了封顶那部分」的尾巴。
     */
    const val MAX_PASSES = 32

    /**
     * 分趟用的间距 ＝ 几何冲突距离 × 这个余量。
     *
     * 分趟是在**后台 tick 的 zoom** 上算的，而绘制用的是**当前相机的 zoom**：
     * 两者最多差一个 tick（sample 48ms ＋ 场景构建 5–16ms ≈ 65ms）。
     * 缩小时屏幕间距按 2^Δ 收缩、而圆点半径只微降，于是原本刚好够开的两个点会挤到一起，
     * 白环就在缩放的那几帧里被吃掉。
     *
     * 1.2 的实测容忍度是 **Δz ≈ 0.55**（`DotRenderFidelityTest` 扫出来的，0.60 处失效）——
     * 双击缩小约 300ms/档，65ms 的滞后约 0.22 档，留了一倍多的余量。
     * 代价是更多的点分不开：东京 z13 的实测是 1583 → 1968 个不绘制（总数 9796）。
     */
    const val ZOOM_LAG_MARGIN = 1.2

    /** 分趟实际使用的间距（dp）。见 [ZOOM_LAG_MARGIN]。 */
    fun partitionDistanceDp(zoom: Double): Double = conflictDistanceDp(zoom) * ZOOM_LAG_MARGIN

    /**
     * @param pass 与输入同序的趟号。**-1 表示不画**（见 [MAX_PASSES]）
     * @param passCount 实际用到的趟数
     * @param hidden 因为撞到 [MAX_PASSES] 而不画的点数
     */
    class Result(val pass: IntArray, val passCount: Int, val hidden: Int)

    /** 圆心距小于该值（dp）时两个圆点会互相吃掉白描边。 */
    fun conflictDistanceDp(zoom: Double): Double =
        2 * MapMarkerMetrics.dotRadius(zoom) + MapMarkerMetrics.dotStrokeWidth(zoom)

    /**
     * 贪心分层。
     *
     * [order] 要按「越希望被看见的越靠前」排（[DotField] 用 priority 降序，也就是越孤立越靠前）。
     * 槽位规则：**已放置的冲突邻居的最小槽位 − 1**（没有冲突邻居就是最顶层 `MAX_PASSES-1`）。
     *
     * 为什么不是「最高的空趟」：空趟贪心不保证**重叠对**按 priority 排序 ——
     * A(100)→31、C(90, 与 A 冲突)→30、B(80, 只与 C 冲突)→31，B 反而压在 C 上。
     * 这些逆序对取决于冲突图，而冲突图随 zoom 连续变（冲突半径是屏幕距离），
     * 于是缩放时逆序对来回翻，肉眼看就是**点之间的上下关系在闪**（真机反馈）。
     *
     * min−1 规则下，新点严格低于它的**全部**冲突邻居，而这些邻居都比它 priority 高 ⇒
     * **任何重叠对的上下关系 ＝ priority 全序**，与 zoom 无关，缩放时不可能翻转。
     * 绝对槽位仍会随视口漂移，但视觉上只有重叠对的相对序可观察 —— 漂移无害。
     * 「同趟内互不重叠」也自动保持：新点不会与任何冲突邻居同槽。
     *
     * 代价是槽位用得更费（下降链不复用空隙；clique 不变，仍是 31,30,29…），
     * hidden 上升：东京 z13 实测 1968 → 3008（总数 9796）、z16 实测 149 → 285。
     * 被藏的点都埋在深堆叠里、本来就被盖住，顶层（最孤立）的点两种规则下都在画 ——
     * 密集斑块的观感不变，换来的是缩放时层序完全不动。
     * 另外藏掉的点**不进冲突网格**，下降链在断点处会自愈（重新从顶层开始）——
     * 单调 priority 的 60 点链只藏 2 个，不是 28 个（DotPassesTest 钉着）。
     *
     * 同输入必得同输出（确定性），否则层序会在相邻两帧之间闪。
     *
     * @param world 平铺的世界坐标 `[wx0, wy0, wx1, wy1, …]`
     * @param order 遍历次序（[world] 的点下标），重要的在前
     * @param conflictWorld 冲突距离，世界坐标单位（＝ dp ÷ (256·2^zoom)）
     */
    fun assign(world: DoubleArray, order: IntArray, conflictWorld: Double): Result {
        val pass = IntArray(order.size)
        if (order.isEmpty()) return Result(pass, 0, 0)
        if (conflictWorld <= 0.0) return Result(pass, 1, 0)

        val cell = conflictWorld
        val squared = conflictWorld * conflictWorld
        // 格边长恰好是冲突距离 ⇒ 冲突对必定落在 3×3 邻域内。
        val grid = HashMap<Long, MutableList<Int>>(order.size)
        val used = BooleanArray(MAX_PASSES)
        var hidden = 0

        for (index in order) {
            val x = world[index * 2]
            val y = world[index * 2 + 1]
            val cellX = floor(x / cell).toInt()
            val cellY = floor(y / cell).toInt()

            // 已放置的冲突邻居里最低的槽位。降到 0 就可以停：
            // min−1 只会更低，结论（不画）已定 —— 病态聚集处没有这条早退就是 O(N²)。
            var minNeighbour = MAX_PASSES
            neighbours@ for (dx in -1..1) {
                for (dy in -1..1) {
                    val bucket = grid[key(cellX + dx, cellY + dy)] ?: continue
                    for (other in bucket) {
                        val ox = world[other * 2] - x
                        val oy = world[other * 2 + 1] - y
                        if (ox * ox + oy * oy >= squared) continue
                        if (pass[other] < minNeighbour) minNeighbour = pass[other]
                        if (minNeighbour == 0) break@neighbours
                    }
                }
            }

            val slot = minNeighbour - 1
            if (slot < 0) {
                // 分不开就不画。塞进已占的趟等于让它们互相吃白环 —— 那正是要消灭的症状。
                pass[index] = HIDDEN
                hidden++
                continue
            }
            pass[index] = slot
            used[slot] = true
            grid.getOrPut(key(cellX, cellY)) { ArrayList(4) }.add(index)
        }
        return Result(pass, used.count { it }, hidden)
    }

    /** [Result.pass] 里表示「这个点不画」。 */
    const val HIDDEN = -1

    private fun key(cellX: Int, cellY: Int): Long =
        (cellX.toLong() shl 32) or (cellY.toLong() and 0xFFFF_FFFFL)
}

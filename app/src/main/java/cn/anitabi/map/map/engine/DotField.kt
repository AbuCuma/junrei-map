package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * 「这一帧要画哪些圆点」的不可变快照。
 *
 * 由后台 tick 生成、一次引用替换发布给绘制层。之所以做成平铺数组而不是 `List<ScenePoint>`：
 * 绘制层每帧要把全部点变换一遍再喂给 `Canvas.drawPoints(FloatArray, …)`，
 * 走对象列表就得每帧建几千个中间对象。
 *
 * 点先按 [DotPasses] 分**趟**（趟内互不重叠），趟内再按作品颜色排序 ——
 * 于是每趟的白描边是一次 `drawPoints`，趟内每种颜色也各是一次。
 *
 * 命中测试也走这份快照（[nearest]）：**画出去的就是能点中的**，不再另查一次
 * [MapPointIndex]（那正是「气球出现了圆点还在、还能点中看不见的点」那一类 bug 的来源）。
 */
class DotField private constructor(
    /**
     * 平铺的世界坐标 `[wx0, wy0, wx1, wy1, …]`，长度 `count * 2`。
     *
     * **必须是 Double**：世界坐标是 [0,1] 的小数，Float 在东京一带的精度约 2.3e-9，
     * 而 z20 的 `pxPerWorld` 约 1e9 —— 乘出来是 2.3 像素的位置误差（z18 约 0.6 像素）。
     */
    val world: DoubleArray,
    /** 与 [world] 同序的点。命中测试要拿回 id。 */
    val points: Array<ScenePoint>,
    /**
     * 数组里的点总数。**其中前 `passes` 覆盖的那一段才会绘制** ——
     * 尾部是分不开而不画的点（[hiddenCount]），它们仍参与命中测试。
     */
    val count: Int,
    /** 绘制顺序：趟升序。合起来覆盖 `[0, count - hiddenCount)`。 */
    val passes: List<Pass>,
    /**
     * 密到 [DotPasses.MAX_PASSES] 趟都分不开、因而**不绘制**的点数。
     * 它们排在数组尾部，命中测试照旧能选中（诊断用：HUD 上的 `hid=`）。
     */
    val hiddenCount: Int,
    /** 命中网格的格边长（世界坐标单位）。 */
    private val cellSize: Double,
    /** 命中网格：格 → 该格内的点下标。 */
    private val cells: Map<Long, IntArray>,
) {

    /** 一趟。趟内任意两点圆心距 ≥ [DotPasses.conflictDistanceDp]，所以可以整趟批量画白描边。 */
    class Pass(val offset: Int, val count: Int, val groups: List<Group>)

    /**
     * 一段同色的点。[offset] 与 [count] 都以**点**为单位（不是数组下标）。
     *
     * 圆点是「外圈 + 内圈」两个同心实心圆：正常情况下两色相同（就是主题色，只画一趟）；
     * 主题色接近白时 [outerArgb] 换成预混的暗色，内圈仍是主题色 ——
     * 这样在白描边与近白主题色之间补出一条暗边。`drawPoints` 画不出「环」，
     * 所以用两个同心圆等价表达。
     */
    class Group(val outerArgb: Int, val innerArgb: Int, val offset: Int, val count: Int)

    val isEmpty: Boolean get() = count == 0

    /** 不同颜色分组的总数（诊断用：它等于每趟填充的绘制调用数之和）。 */
    val colorGroupCount: Int get() = passes.sumOf { it.groups.size }

    /** 会被绘制的点数。数组前段是绘制的（按趟分段），尾段是 hidden。 */
    private val drawnCount: Int get() = count - hiddenCount

    /** 下标 → 趟号（仅对绘制段有意义）。趟 ≤32 段，线扫足够。 */
    private fun passOf(index: Int): Int {
        for (p in passes.indices) {
            val pass = passes[p]
            if (index < pass.offset + pass.count) return p
        }
        return passes.size
    }

    /**
     * tap 命中哪个点。两级规则，都只考虑**画出来的**点（hidden 段一律排除 ——
     * 看不见的东西不能点中，这正是「密集处点开一张不知道哪来的卡片」的来源）：
     *
     * 1. tap 落在某些点的**绘制圆盘**内（dist ≤ [drawnRadiusWorld]）时，
     *    取其中**趟号最高**者 —— tap 那个像素上显示的就是它。密集堆叠里
     *    「按圆心距取最近」会选中被盖在下面的点，与用户所见不符（真机反馈）。
     * 2. 否则在 [radiusWorld]（≥12dp 的热区）内取**最近**者；同距取高趟。
     *
     * 在世界坐标里比距离而不是屏幕坐标：地图的旋转是等距变换，两者的距离比较**等价**，
     * 而这样就不必对每个候选都调一次 GMS 的 `toScreenLocation`（跨界调用）。
     */
    fun nearest(worldX: Double, worldY: Double, radiusWorld: Double, drawnRadiusWorld: Double): ScenePoint? {
        if (drawnCount == 0 || cellSize <= 0.0) return null
        val cellX = floor(worldX / cellSize).toInt()
        val cellY = floor(worldY / cellSize).toInt()
        val drawnSquared = drawnRadiusWorld * drawnRadiusWorld

        val hitSquared = radiusWorld * radiusWorld
        var topVisible: ScenePoint? = null      // 规则 1：tap 像素的归属
        var topVisiblePass = -1
        var topVisibleSquared = Double.MAX_VALUE
        var nearest: ScenePoint? = null         // 规则 2 的兜底
        var nearestSquared = hitSquared
        var nearestPass = -1

        for (dx in -1..1) {
            for (dy in -1..1) {
                val bucket = cells[key(cellX + dx, cellY + dy)] ?: continue
                for (index in bucket) {
                    if (index >= drawnCount) continue   // hidden：不画就不能点中
                    val ox = world[index * 2] - worldX
                    val oy = world[index * 2 + 1] - worldY
                    val squared = ox * ox + oy * oy
                    // 门槛用**固定的**热区半径,不能用收缩中的 nearestSquared ——
                    // 那会把「更远但趟更高」的规则 1 候选跳过。
                    if (squared > hitSquared) continue
                    val point = points[index]
                    val pass = passOf(index)

                    if (squared <= drawnSquared) {
                        // 高趟优先；同趟取更近的（同趟互不重叠，同趟且都盖住 tap 不可能，
                        // 但 tap 恰在两盘边缘时浮点上可能并存 —— 取近的）。
                        if (pass > topVisiblePass ||
                            (pass == topVisiblePass && squared < topVisibleSquared)
                        ) {
                            topVisible = point
                            topVisiblePass = pass
                            topVisibleSquared = squared
                        }
                    }
                    if (squared < nearestSquared ||
                        (squared == nearestSquared && pass > nearestPass)
                    ) {
                        nearest = point
                        nearestSquared = squared
                        nearestPass = pass
                    }
                }
            }
        }
        return topVisible ?: nearest
    }

    companion object {
        val EMPTY = DotField(DoubleArray(0), emptyArray(), 0, emptyList(), 0, 0.0, emptyMap())

        /** 把外圈/内圈两个 ARGB 打包成一个分桶 key。 */
        fun packColors(outerArgb: Int, innerArgb: Int): Long =
            (outerArgb.toLong() shl 32) or (innerArgb.toLong() and 0xFFFF_FFFFL)

        /**
         * @param points 这一帧要画的圆点（调用方已经按 zoom 规则筛过，并且已经把
         *   「这一帧会以标注形式出现」的点剔掉了 —— 见 `MapEngine.scene`）
         * @param zoom 相机 zoom。决定圆点尺寸，进而决定分趟的冲突距离与命中半径
         * @param colorOf 作品 ID → 打包好的 (外圈, 内圈) ARGB。在后台就解析好，
         *   绘制层不再碰主题色 —— 主题色要从 `AnitabiStore` 读，那是 Compose 快照状态，
         *   不能在绘制阶段碰
         * @param minHitRadiusDp 命中网格按它取格边长（命中半径的下限）
         */
        fun build(
            points: List<ScenePoint>,
            zoom: Double,
            colorOf: (Int) -> Long,
            fallback: Long,
            minHitRadiusDp: Double,
        ): DotField {
            if (points.isEmpty()) return EMPTY

            val dpPerWorld = MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom)
            // priority **降序** ⇒ 越孤立(越该被看见)的点越早分到趟。
            // DotPasses 给靠前的点最高的空趟 ⇒ 它们画在上层；而挤不下时被舍弃的
            // 是最不重要的那些。id 兜底保证同 priority 时也确定（否则层序会逐帧闪）。
            val ordered = points.sortedWith(
                compareByDescending<ScenePoint> { it.priority }.thenBy { it.id }
            )
            val total = ordered.size

            val staging = DoubleArray(total * 2)
            for (i in 0 until total) {
                staging[i * 2] = MapProjection.worldX(ordered[i].lng)
                staging[i * 2 + 1] = MapProjection.worldY(ordered[i].lat)
            }

            val assignment = DotPasses.assign(
                world = staging,
                order = IntArray(total) { it },
                conflictWorld = DotPasses.partitionDistanceDp(zoom) / dpPerWorld,
            )

            // 先按趟、再按颜色分桶。LinkedHashMap ⇒ 桶序＝首次出现序 ⇒ 确定性。
            val byPass = LinkedHashMap<Int, LinkedHashMap<Long, MutableList<Int>>>()
            val hiddenIndices = ArrayList<Int>(assignment.hidden)
            for (i in 0 until total) {
                if (assignment.pass[i] == DotPasses.HIDDEN) {
                    hiddenIndices.add(i)
                    continue
                }
                val point = ordered[i]
                val packed = if (point.bangumiId == 0) fallback else colorOf(point.bangumiId)
                byPass.getOrPut(assignment.pass[i]) { LinkedHashMap() }
                    .getOrPut(packed) { ArrayList(16) }
                    .add(i)
            }

            val world = DoubleArray(total * 2)
            @Suppress("UNCHECKED_CAST")
            val flat = arrayOfNulls<ScenePoint>(total) as Array<ScenePoint>
            val passes = ArrayList<Pass>(assignment.passCount)
            var offset = 0

            for (pass in 0 until DotPasses.MAX_PASSES) {
                val colors = byPass[pass] ?: continue
                val passOffset = offset
                val groups = ArrayList<Group>(colors.size)
                for ((packed, bucket) in colors) {
                    groups.add(
                        Group(
                            outerArgb = (packed ushr 32).toInt(),
                            innerArgb = packed.toInt(),
                            offset = offset,
                            count = bucket.size,
                        )
                    )
                    for (i in bucket) {
                        world[offset * 2] = staging[i * 2]
                        world[offset * 2 + 1] = staging[i * 2 + 1]
                        flat[offset] = ordered[i]
                        offset++
                    }
                }
                passes.add(Pass(passOffset, offset - passOffset, groups))
            }

            // 不画的点排在尾部:绘制只走 passes,命中测试走整条数组。
            for (i in hiddenIndices) {
                world[offset * 2] = staging[i * 2]
                world[offset * 2 + 1] = staging[i * 2 + 1]
                flat[offset] = ordered[i]
                offset++
            }

            // 命中网格。格边长取命中半径，于是 3×3 邻域必定覆盖判定圆。
            val hitDp = max(
                minHitRadiusDp,
                MapMarkerMetrics.dotRadius(zoom) + MapMarkerMetrics.dotStrokeWidth(zoom),
            )
            val cellSize = hitDp / dpPerWorld
            val cells = buildCells(world, total, cellSize)

            return DotField(
                world = world,
                points = flat,
                count = total,
                passes = passes,
                hiddenCount = assignment.hidden,
                cellSize = cellSize,
                cells = cells,
            )
        }

        private fun buildCells(world: DoubleArray, count: Int, cellSize: Double): Map<Long, IntArray> {
            val buckets = HashMap<Long, MutableList<Int>>(count)
            for (i in 0 until count) {
                val cellX = floor(world[i * 2] / cellSize).toInt()
                val cellY = floor(world[i * 2 + 1] / cellSize).toInt()
                buckets.getOrPut(key(cellX, cellY)) { ArrayList(4) }.add(i)
            }
            val cells = HashMap<Long, IntArray>(buckets.size)
            for ((cellKey, bucket) in buckets) cells[cellKey] = bucket.toIntArray()
            return cells
        }

        private fun key(cellX: Int, cellY: Int): Long =
            (cellX.toLong() shl 32) or (cellY.toLong() and 0xFFFF_FFFFL)
    }
}

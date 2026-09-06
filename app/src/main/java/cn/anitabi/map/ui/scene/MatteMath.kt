package cn.anitabi.map.ui.scene

/**
 * 抠图收边的纯像素运算(移植自 iOS SubjectMatteRefiner)。不 import android.*
 * (为了 JVM 单测)。像素为非预乘 ARGB 的 IntArray。
 *
 * 用 **alpha 线性拉伸**修复发丝边缘的脏像素,用**内部填洞**修复被误判为背景的
 * 大块缺损。刻意不用形态学腐蚀/闭运算 —— 头发只有 1~2px,一腐蚀就没了;
 * 闭运算则会把手臂与身体之间本应透出的缝隙也堵死。
 */
object MatteMath {

    /** alpha 低于此值的像素置为全透明 —— 脏边正集中在这个区间。 */
    const val ALPHA_FLOOR = 0.1f

    /** alpha 高于此值的像素拉伸为完全不透明。 */
    const val ALPHA_CEILING = 0.3f

    /** 判定「该像素是背景」的 alpha 阈值(0~255)。填洞用。 */
    const val HOLE_ALPHA_THRESHOLD = 128

    /** 填洞面积上限(占全图比例)。超过说明判定跑偏(多半是被包围的背景),不予填补。 */
    const val MAX_HOLE_AREA_RATIO = 0.25

    // ISNet 用的强收边。ML Kit 的掩码近乎二值,但 ISNet 的概率图会在背景残留
    // 30~140/255 左右的「雾」(中等置信度 alpha) —— 提高拉伸带,并把
    // 「峰值 alpha 偏低的悬浮成分」整块清除(min-max 归一化后,真正主体的
    // 核心必然达到 255,峰值低的成分即可断定为雾)。
    const val ISNET_ALPHA_FLOOR = 0.2f
    const val ISNET_ALPHA_CEILING = 0.55f
    /** 成分的峰值 alpha 低于此值就当作雾整体清除(0~255)。 */
    const val FAINT_PEAK_ALPHA = 204

    /**
     * `alpha' = clamp((alpha - floor) / (ceiling - floor))`。前提是在非预乘空间进行。
     * **就地**改写像素。
     */
    fun stretchAlpha(pixels: IntArray, floorRatio: Float = ALPHA_FLOOR, ceilingRatio: Float = ALPHA_CEILING) {
        val floor = floorRatio * 255f
        val slope = 1f / ((ceilingRatio - floorRatio).coerceAtLeast(0.01f))
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val stretched = (((a - floor) * slope).toInt()).coerceIn(0, 255)
            pixels[i] = (stretched shl 24) or (p and 0x00FF_FFFF)
        }
    }

    /**
     * 对 alpha>0 的每个 4 连通成分测峰值 alpha,把低于 [FAINT_PEAK_ALPHA] 的成分的 alpha
     * 全部清零。只会消掉**未连接**到主体的雾岛(前提是在拉伸切断边缘之后调用)。
     */
    fun removeFaintRegions(pixels: IntArray, width: Int, height: Int, minPeakAlpha: Int = FAINT_PEAK_ALPHA) {
        val count = width * height
        if (count == 0 || pixels.size < count) return
        fun alpha(i: Int) = (pixels[i] ushr 24) and 0xFF
        val visited = BooleanArray(count)
        val stack = ArrayDeque<Int>()
        val component = ArrayList<Int>()
        for (start in 0 until count) {
            if (visited[start] || alpha(start) == 0) continue
            floodComponent(start, width, height, visited, stack, component) { alpha(it) > 0 }
            var peak = 0
            for (index in component) {
                val a = alpha(index)
                if (a > peak) peak = a
            }
            if (peak < minPeakAlpha) {
                for (index in component) pixels[index] = pixels[index] and 0x00FF_FFFF
            }
        }
    }

    /**
     * 从 [start] 出发做 4 连通 flood fill:满足 [include] 的邻居入栈,
     * 组员写入 [component](先清空),[visited] 就地标记。
     * removeFaintRegions 与 interiorHoles 共用(此前是两份 ~40 行的复制)。
     */
    private inline fun floodComponent(
        start: Int,
        width: Int,
        height: Int,
        visited: BooleanArray,
        stack: ArrayDeque<Int>,
        component: ArrayList<Int>,
        include: (Int) -> Boolean,
    ) {
        component.clear()
        visited[start] = true
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            component.add(index)
            val x = index % width
            val y = index / width
            if (x > 0) {
                val i = index - 1
                if (!visited[i] && include(i)) { visited[i] = true; stack.addLast(i) }
            }
            if (x < width - 1) {
                val i = index + 1
                if (!visited[i] && include(i)) { visited[i] = true; stack.addLast(i) }
            }
            if (y > 0) {
                val i = index - width
                if (!visited[i] && include(i)) { visited[i] = true; stack.addLast(i) }
            }
            if (y < height - 1) {
                val i = index + width
                if (!visited[i] && include(i)) { visited[i] = true; stack.addLast(i) }
            }
        }
    }

    /**
     * 从四边向「透明区域」flood fill,返回**不与边相连的透明像素＝内部的洞**。
     * 比闭运算更准确:向外敞开的缝隙 flood fill 能够到达,因而不会被误补,
     * 只有四面被围住的洞才会被正确填补。面积上限按**单个洞**生效。
     */
    fun interiorHoles(pixels: IntArray, width: Int, height: Int): IntArray? {
        val count = width * height
        if (count == 0 || pixels.size < count) return null
        val reached = BooleanArray(count)
        val stack = ArrayDeque<Int>()

        fun alpha(i: Int) = (pixels[i] ushr 24) and 0xFF

        fun seed(index: Int) {
            if (reached[index] || alpha(index) >= HOLE_ALPHA_THRESHOLD) return
            reached[index] = true
            stack.addLast(index)
        }

        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }
        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            val x = index % width
            val y = index / width
            if (x > 0) seed(index - 1)
            if (x < width - 1) seed(index + 1)
            if (y > 0) seed(index - width)
            if (y < height - 1) seed(index + width)
        }

        val maxComponentArea = (count * MAX_HOLE_AREA_RATIO).toInt()
        val visited = reached.copyOf()
        val holes = ArrayList<Int>()
        val component = ArrayList<Int>()

        for (start in 0 until count) {
            if (visited[start] || alpha(start) >= HOLE_ALPHA_THRESHOLD) continue
            floodComponent(start, width, height, visited, stack, component) {
                alpha(it) < HOLE_ALPHA_THRESHOLD
            }
            if (component.size <= maxComponentArea) holes.addAll(component)
        }
        return if (holes.isEmpty()) null else holes.toIntArray()
    }

    /** 用原图的颜色把洞补成完全不透明。 */
    fun fillHoles(pixels: IntArray, holes: IntArray, source: IntArray) {
        for (index in holes) {
            pixels[index] = source[index] or (0xFF shl 24).toInt()
        }
    }

    /**
     * 收边全流程:拉伸 →(强收边时清除雾成分)→ 填洞。就地改写像素。
     * @param strongMatte ISNet 路径为 true(对 ML Kit 的近二值掩码既不必要也可能有害)。
     */
    fun refine(pixels: IntArray, source: IntArray, width: Int, height: Int, strongMatte: Boolean = false) {
        if (strongMatte) {
            stretchAlpha(pixels, ISNET_ALPHA_FLOOR, ISNET_ALPHA_CEILING)
            removeFaintRegions(pixels, width, height)
        } else {
            stretchAlpha(pixels)
        }
        interiorHoles(pixels, width, height)?.let { holes ->
            fillHoles(pixels, holes, source)
        }
    }
}

package cn.anitabi.map.map.engine

import kotlin.math.pow

// Web 样式的尺寸表与揭示阶梯（iOS MapMarkerMetrics / MapRevealLadder 的 1:1 移植）。
// Mapbox GL 的长度单位是 CSS px ＝ 与 dp 1:1，所以数值不做换算直接使用。

object MapMarkerMetrics {

    /** Mapbox 旧式 `{base, stops}` 的指数插值。两端之外按端点值封顶。 */
    fun interpolate(zoom: Double, base: Double, stops: List<Pair<Double, Double>>): Double {
        val first = stops.firstOrNull() ?: return 0.0
        val last = stops.last()
        if (zoom <= first.first) return first.second
        if (zoom >= last.first) return last.second
        for (index in 1 until stops.size) {
            if (zoom > stops[index].first) continue
            val low = stops[index - 1]
            val high = stops[index]
            val ratio = (base.pow(zoom - low.first) - 1) / (base.pow(high.first - low.first) - 1)
            return low.second + (high.second - low.second) * ratio
        }
        return last.second
    }

    /** Web `bangumi-point` 层的 `circle-radius`。返回的是**半径**（dp）。 */
    fun dotRadius(zoom: Double): Double =
        interpolate(zoom, base = 1.75, stops = listOf(12.0 to 4.0, 18.0 to 8.0, 22.0 to 16.0))

    /**
     * Web `bangumi-point` 层的 `circle-stroke-width`。Mapbox 的 circle 描边加在**半径的外侧**，
     * 所以视觉直径是 `2×(radius + stroke)`。
     */
    fun dotStrokeWidth(zoom: Double): Double =
        interpolate(zoom, base = 1.75, stops = listOf(12.0 to 1.5, 18.0 to 3.0, 22.0 to 6.0))

    /**
     * 剧照标一档的尺寸（dp）。**两档意匠相同**（作品色边框 ＋ 滴 ＋ 图片内三角），
     * 不同的只有尺寸 —— Web 是大卡片无框、小泡有框两种意匠，但有反馈指出同屏混用
     * 很怪，于是统一成一种。框色＝作品色，归属一眼可辨。
     */
    data class PhotoPlate(
        val bodyWidth: Double,
        val bodyHeight: Double,
        val cornerRadius: Double,
        val borderWidth: Double,
        /** 挂在本体下方的作品色滴。 */
        val dripWidth: Double,
        val dripHeight: Double,
        /** 叠在滴上、用图片填充的三角。按 overlap 咬进本体。 */
        val innerTailWidth: Double,
        val innerTailHeight: Double,
        val innerTailOverlap: Double,
        /** 尾尖即坐标。在此基础上再抬起的量。 */
        val anchorLift: Double = 0.0,
        /** 整张图的尺寸（含滴下方的留白）。 */
        val totalWidth: Double,
        val totalHeight: Double,
    ) {
        companion object {
            /** 浏览时（z>18）的大卡片。Web `points-image` 的 160×(120+14)px ÷ pixelRatio 2。 */
            val CARD = PhotoPlate(
                bodyWidth = 80.0, bodyHeight = 60.0,
                cornerRadius = 4.0, borderWidth = 1.0,
                dripWidth = 14.0, dripHeight = 7.0,
                innerTailWidth = 11.0, innerTailHeight = 7.5, innerTailOverlap = 1.7,
                anchorLift = 1.0, // 对应 Web 的 `icon-offset: [0,-1]`。
                totalWidth = 80.0, totalHeight = 67.0,
            )

            /**
             * 作品模式的小泡。Web `bangumi-points-theme` 的 100×(76+11+2)px ÷ pixelRatio 2
             * （默认值是 72×54，但实际数据 891/1019 条都是 100×76，故取后者）。
             */
            val BUBBLE = PhotoPlate(
                bodyWidth = 50.0, bodyHeight = 38.0,
                cornerRadius = 3.0, borderWidth = 1.0,
                dripWidth = 11.0, dripHeight = 5.5,
                innerTailWidth = 9.0, innerTailHeight = 6.0, innerTailOverlap = 1.35,
                totalWidth = 50.0, totalHeight = 44.5,
            )
        }
    }

    /** Web `bangumis` 层（作品标）的尺寸。60×60 精灵格按 pixelRatio 2 摆放，所以是 30dp。 */
    object WorkMarker {
        const val ICON_SIZE = 30.0
        const val TEXT_SIZE = 11.0
    }
}

/** 原样保存 Web 版的揭示表。含义是「只显示最近邻距离（m）**超过**该值的点／作品」。 */
object MapRevealLadder {

    /**
     * Web 的 `bangumi-point` 从这一档起 filter 恒为 `true` —— 每个点、无阈值、无碰撞。
     * 实测其 filter 逐字是 `["step",["zoom"], ["all",["get","isDil"]], 9, ["get","isDil"], 12, true]`，
     * 而 `isDil` 客户端从不写入，所以 z12 以下一个单点都不画、z12 起一个不漏。
     */
    const val FULL_DENSITY_ZOOM = 12

    /** 圆点开始淡入的 zoom。见 [dotFieldOpacity]。 */
    const val DOT_FADE_IN_START = 11.7

    /**
     * 圆点层的**整层**不透明度。
     *
     * **这里从「有意与 Web 分歧」改回「与 Web 一致」**：z<12 一个单点都不画，
     * 聚合完全交给作品标（[workThreshold]，z<13、屏幕上 ≤32 个）。
     * Web 的 filter 逐字是 `["step",["zoom"],["all",["get","isDil"]],9,["get","isDil"],12,true]`，
     * 而 `isDil` 客户端从不写入 —— 即 z12 起一个不漏、z12 以下一个不画。
     * 旧的 `tileDotThreshold` 在 z<12 沿用 [pointThreshold] 的阶梯，实测在东京一带
     * 会画出 1000–2600 个圆点，正是「低 zoom 太吵」的来源。
     *
     * [DOT_FADE_IN_START] → [FULL_DENSITY_ZOOM] 之间线性淡入，只为软化「0 → 数千个点」的硬跳变；
     * 这是对 Web 硬切的一处小偏离，只动透明度、不动密度。
     * （考虑过改为渐变半径而不是渐变透明度：不用离屏图层、也没有灰晕问题，
     * 但几千个点以亚像素尺寸浮现会像一片虫群，而且亚像素半径会掉进 Skia 的退化抗锯齿路径。）
     *
     * @param filtered 作品模式 / chips。为真时忽略 zoom 规则，任何 zoom 都全画。
     */
    fun dotFieldOpacity(zoom: Double, filtered: Boolean): Float = when {
        filtered -> 1f
        zoom >= FULL_DENSITY_ZOOM -> 1f
        zoom <= DOT_FADE_IN_START -> 0f
        else -> ((zoom - DOT_FADE_IN_START) / (FULL_DENSITY_ZOOM - DOT_FADE_IN_START)).toFloat()
    }

    /**
     * 地标的阈值。
     *
     * **注意：这不是 Web `bangumi-point` filter 的移植**（此处旧注释曾如此声称，与事实不符）。
     * Web 那条 filter 在 z12 以上根本没有 priority 判据，见 [dotFieldOpacity] 的说明。
     *
     * **这张表如今没有任何调用方**：圆点的显隐已经完全交给 [dotFieldOpacity]（z<12 一个不画）。
     * 留着是因为它记录了「按最近邻距离抽稀」这套做法本身，iOS 版仍在用；
     * 要重新接回来之前先想清楚它与作品标聚合的关系。
     */
    fun pointThreshold(zoom: Double): Double = when {
        zoom < 2 -> 60_000.0
        zoom < 3 -> 36_000.0
        zoom < 4 -> 24_000.0
        zoom < 5 -> 12_000.0
        zoom < 6 -> 6_000.0
        zoom < 7 -> 3_000.0
        zoom < 8 -> 1_500.0
        zoom < 9 -> 600.0
        zoom < 10 -> 300.0
        zoom < 11 -> 150.0
        zoom < 12 -> 100.0
        zoom < 13 -> 40.0
        zoom < 14 -> 20.0
        zoom < 15 -> 10.0
        zoom < 16 -> 5.0
        zoom < 17 -> 3.0
        // 阈值判定是「超过」，为了把 priority 0（坐标完全重合的点）也捡进来，取 -1。
        else -> -1.0
    }

    /**
     * 作品标的阈值。Web `bangumis` 的 filter ＋ `maxzoom: 13`。
     * z13 起为 null ＝ 不显示（近距离下地标才是主角，作品标反而碍事）。
     */
    fun workThreshold(zoom: Double): Double? = when {
        zoom < 2 -> 760_000.0
        zoom < 3 -> 420_000.0
        zoom < 3.5 -> 160_000.0
        zoom < 4 -> 130_000.0
        zoom < 4.5 -> 100_000.0
        zoom < 5 -> 70_000.0
        zoom < 6 -> 55_000.0
        zoom < 7 -> 28_800.0
        zoom < 8 -> 14_400.0
        zoom < 9 -> 7_200.0
        zoom < 10 -> 3_600.0
        zoom < 11 -> 1_800.0
        zoom < 12 -> 900.0
        zoom < 13 -> 450.0 // Web 是 `maxzoom: 13` ＝ 恰好从 z13 起整层消失。
        else -> null
    }

    // MARK: 剧照的揭示

    /** 图从小泡切换为大卡片的 zoom（原样沿用 Web 的层边界）。 */
    const val PHOTO_CARD_ZOOM = 18.0

    /**
     * 浏览时剧照**开始出现**的 zoom。**有意偏离 web** —— web 在 z≤18 一张也不显示，
     * 但那样「不放得非常大就什么都看不到」。取 15，定位钮的落地和从列表跳到最远点
     * 两种场景都能覆盖。密度由 priority 阈值和碰撞回避压住，不会失控。
     */
    const val PHOTO_REVEAL_ZOOM = 15.0

    /** 大卡片的 priority 阈值（原样沿用 Web 的 `3 × 2^(19−z)`）。点的最近邻距离 m **不小于**该值就显示。 */
    fun photoCardPriorityThreshold(zoom: Double): Double = 3 * 2.0.pow(19 - zoom)

    /** 作品模式小泡（Web `bangumi-points-theme`）的揭示阶梯。值单位 m。 */
    fun photoBubbleThreshold(zoom: Double): Double = when {
        zoom < 3 -> 48_000.0
        zoom < 4 -> 24_000.0
        zoom < 5 -> 12_000.0
        zoom < 6 -> 6_000.0
        zoom < 7 -> 3_000.0
        zoom < 8 -> 1_600.0
        zoom < 9 -> 800.0
        zoom < 10 -> 400.0
        zoom < 11 -> 200.0
        zoom < 12 -> 100.0
        zoom < 13 -> 50.0
        zoom < 14 -> 20.0
        zoom < 15 -> 10.0
        zoom < 16 -> 5.0
        zoom < 17 -> 3.0
        zoom < 18 -> 2.0
        zoom < 19 -> 1.0
        else -> 0.0
    }
}

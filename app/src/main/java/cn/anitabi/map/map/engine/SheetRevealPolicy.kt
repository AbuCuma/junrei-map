package cn.anitabi.map.map.engine

import kotlin.math.max

/**
 * 「从地图上选中一个点之后，相机要不要让位」的决策。
 *
 * 纯函数，单位一律是**像素**。此前这段逻辑写在 `AnitabiMap` 的一个 lambda 里，
 * 既测不了也看不出边界（真机上表现为「完全不上移，点被卡片盖住」）。
 *
 * 语义与 iOS 的 `schedulePlacement(.revealed)` 相同：**只在**点会被弹出的卡片挡住、
 * 或太贴近卡上缘时才动，而且只做纯平移（zoom 不变）。其余情况一律不动 ——
 * 已经看得见的东西不该在脚下滑走。
 */
object SheetRevealPolicy {

    /** 点与卡片上缘之间至少要留的空隙。气球本体约 42dp 高，56 让它整个露出来还有余。 */
    const val CLEARANCE_DP = 56.0

    /** 需要执行的 `scrollBy` 位移（像素）。null ＝ 不用动。 */
    data class Shift(val dx: Float, val dy: Float)

    /**
     * @param obstructionLeftPx 横屏时左侧卡片的宽度（竖屏为 0）
     * @param obstructionBottomPx 竖屏时底部 sheet 遮住的高度（横屏为 0）
     *
     * 两种朝向互斥：横屏卡片在左，做水平让位；竖屏卡片在下，做垂直让位。
     */
    fun shiftFor(
        pointScreenX: Float,
        pointScreenY: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        obstructionLeftPx: Float,
        obstructionBottomPx: Float,
        clearancePx: Float,
    ): Shift? {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return null

        if (obstructionLeftPx > 0f) {
            // 横屏：卡片在左 —— 挪到右侧未遮挡区的中线。
            if (pointScreenX >= obstructionLeftPx + clearancePx) return null
            val targetX = obstructionLeftPx + (viewportWidthPx - obstructionLeftPx) / 2f
            return Shift(pointScreenX - targetX, 0f)
        }

        val cardTop = viewportHeightPx - obstructionBottomPx
        if (pointScreenY <= cardTop - clearancePx) return null
        // 未遮挡区的中线，但不贴到状态栏上去。
        val targetY = max(cardTop / 2f, clearancePx)
        return Shift(0f, pointScreenY - targetY)
    }
}

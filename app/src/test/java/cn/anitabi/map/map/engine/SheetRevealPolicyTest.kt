package cn.anitabi.map.map.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选中一个点之后相机要不要让位」的决策。
 *
 * 这段逻辑此前写在 `AnitabiMap` 的一个 lambda 里，既测不了也看不出边界 ——
 * 真机上的表现是「完全不上移，点被卡片盖住」。
 */
class SheetRevealPolicyTest {

    private val width = 1440
    private val height = 3120
    private val clearance = 210f // 56dp @ density 3.75

    private fun portrait(pointY: Float, obstructionBottom: Float) =
        SheetRevealPolicy.shiftFor(
            pointScreenX = 720f,
            pointScreenY = pointY,
            viewportWidthPx = width,
            viewportHeightPx = height,
            obstructionLeftPx = 0f,
            obstructionBottomPx = obstructionBottom,
            clearancePx = clearance,
        )

    /** 已经看得见的东西不该在脚下滑走。 */
    @Test
    fun aPointWellAboveTheCardDoesNotMoveTheCamera() {
        assertNull(portrait(pointY = 400f, obstructionBottom = height * 0.53f))
    }

    /** 卡片会盖住它 —— 挪到未遮挡区的中线。 */
    @Test
    fun aPointUnderTheCardIsLiftedToTheMiddleOfTheClearArea() {
        val bottom = height * 0.53f
        val shift = portrait(pointY = height * 0.72f, obstructionBottom = bottom)!!
        assertEquals(0f, shift.dx, 0f)
        val cardTop = height - bottom
        // scrollBy 的正 dy ＝ 地图内容上移，点落到 cardTop/2。
        assertEquals(height * 0.72f - cardTop / 2f, shift.dy, 0.5f)
        assertTrue("应当是上移", shift.dy > 0f)
    }

    /** 恰好压在「卡上缘 − 留白」这条线上也要动 —— 否则点会贴着卡片边缘。 */
    @Test
    fun theClearanceBandCounts() {
        val bottom = height * 0.53f
        val cardTop = height - bottom
        assertNull(portrait(pointY = cardTop - clearance, obstructionBottom = bottom))
        val justInside = portrait(pointY = cardTop - clearance + 1f, obstructionBottom = bottom)
        assertTrue(justInside != null && justInside.dy > 0f)
    }

    /** 目标位置不贴到状态栏上去。 */
    @Test
    fun theTargetNeverGoesAboveTheClearance() {
        // 卡片几乎占满屏幕 ⇒ cardTop/2 会小于留白。
        val shift = portrait(pointY = height * 0.9f, obstructionBottom = height * 0.95f)!!
        assertEquals(height * 0.9f - clearance, shift.dy, 0.5f)
    }

    /** 横屏：卡片在左，做水平让位。 */
    @Test
    fun landscapeShiftsHorizontally() {
        val left = 420f
        val shift = SheetRevealPolicy.shiftFor(
            pointScreenX = 100f,
            pointScreenY = 500f,
            viewportWidthPx = 3120,
            viewportHeightPx = 1440,
            obstructionLeftPx = left,
            obstructionBottomPx = 0f,
            clearancePx = clearance,
        )!!
        assertEquals(0f, shift.dy, 0f)
        assertEquals(100f - (left + (3120 - left) / 2f), shift.dx, 0.5f)
    }

    @Test
    fun landscapePointAlreadyClearOfTheCardDoesNotMove() {
        assertNull(
            SheetRevealPolicy.shiftFor(
                pointScreenX = 2000f,
                pointScreenY = 500f,
                viewportWidthPx = 3120,
                viewportHeightPx = 1440,
                obstructionLeftPx = 420f,
                obstructionBottomPx = 0f,
                clearancePx = clearance,
            )
        )
    }

    /** 视口还没测量出来时什么都不做（否则会按 0×0 算出一个荒唐的位移）。 */
    @Test
    fun anUnmeasuredViewportIsIgnored() {
        assertNull(
            SheetRevealPolicy.shiftFor(0f, 0f, 0, 0, 0f, 100f, clearance)
        )
    }
}

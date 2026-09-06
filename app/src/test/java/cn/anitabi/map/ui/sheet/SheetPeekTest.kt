package cn.anitabi.map.ui.sheet

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 档位表是 DetentSheet 锚点的唯一来源:每层的档位露出高必须单调,身份条类档位含导航栏。 */
class SheetPeekTest {

    private val height = 800.dp
    private val navBar = 24.dp
    private val cards = listOf(SheetKey.Work(1), SheetKey.Point("p", 1), SheetKey.DroppedPin(35.0, 139.0), SheetKey.PilgrimageLog)

    @Test
    fun homeHasNoMiniDetent() {
        assertNull(SheetPeek.visibleDp(SheetKey.Home, Detent.Mini, height, navBar))
        assertEquals(SheetPeek.homeBar + navBar, SheetPeek.visibleDp(SheetKey.Home, Detent.Medium, height, navBar))
    }

    @Test
    fun cardDetentsAreStrictlyIncreasing() {
        for (key in cards) {
            val mini = SheetPeek.visibleDp(key, Detent.Mini, height, navBar)!!
            val partial = SheetPeek.visibleDp(key, Detent.Medium, height, navBar)!!
            val expanded = SheetPeek.visibleDp(key, Detent.Expanded, height, navBar)!!
            assertTrue("$key: mini $mini < partial $partial", mini < partial)
            assertTrue("$key: partial $partial < expanded $expanded", partial < expanded)
        }
    }

    @Test
    fun miniBarIncludesNavBarButFractionsDoNot() {
        val key = SheetKey.Work(1)
        assertEquals(SheetPeek.miniBar + navBar, SheetPeek.visibleDp(key, Detent.Mini, height, navBar))
        assertEquals(height * 0.42f, SheetPeek.visibleDp(key, Detent.Medium, height, navBar))
        assertEquals(height * 0.92f, SheetPeek.visibleDp(key, Detent.Expanded, height, navBar))
    }

    @Test
    fun droppedPinExpandsToHalfScreen() {
        val key = SheetKey.DroppedPin(0.0, 0.0)
        assertEquals(height * 0.49f, SheetPeek.visibleDp(key, Detent.Expanded, height, navBar))
    }

    @Test
    fun landscapeCardsHaveNoPartialButHomeKeepsIt() {
        for (key in cards) {
            assertNull(SheetPeek.visibleDp(key, Detent.Medium, height, navBar, landscape = true))
            assertEquals(Detent.Expanded, SheetPeek.restDetent(key, landscape = true))
            assertEquals(Detent.Medium, SheetPeek.restDetent(key, landscape = false))
        }
        assertEquals(SheetPeek.homeBar + navBar, SheetPeek.visibleDp(SheetKey.Home, Detent.Medium, height, navBar, landscape = true))
        assertEquals(Detent.Medium, SheetPeek.restDetent(SheetKey.Home, landscape = true))
    }

    /** 分屏 / 小窗:容器只有 400dp 时,图钉卡 0.22 比例档(88dp)本会比身份条(76+24)还矮。 */
    @Test
    fun shortContainerKeepsDetentsOrdered() {
        for (height in listOf(400.dp, 250.dp)) {
            for (key in cards) {
                val mini = SheetPeek.visibleDp(key, Detent.Mini, height, navBar)!!
                val partial = SheetPeek.visibleDp(key, Detent.Medium, height, navBar)!!
                val expanded = SheetPeek.visibleDp(key, Detent.Expanded, height, navBar)!!
                assertTrue("$key@$height: mini $mini < partial $partial", mini < partial)
                assertTrue("$key@$height: partial $partial < expanded $expanded", partial < expanded)
                assertTrue("$key@$height: expanded $expanded <= container", expanded <= height)
            }
            val homeBar = SheetPeek.visibleDp(SheetKey.Home, Detent.Medium, height, navBar)!!
            val homeExpanded = SheetPeek.visibleDp(SheetKey.Home, Detent.Expanded, height, navBar)!!
            assertTrue(homeBar < homeExpanded)
        }
    }
}

package cn.anitabi.map.app

import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.ui.scene.CameraSessionParams
import cn.anitabi.map.ui.sheet.SheetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航状态机的行为测试。
 *
 * seam = [AppRouter] 的公开跳转方法与三个栈属性 —— 这是导航的唯一事实源,
 * UI 只是把它映射成呈现,所以在这一层测到的就是用户能观察到的前进/后退语义。
 */
class AppRouterTest {

    private fun point(id: String, bangumiId: Int) = ScenePoint(
        id = id, bangumiId = bangumiId, lat = 35.0, lng = 139.0, priority = 0,
        name = null, nameCn = null, image = null, ep = null,
        isFolder = false, fid = null, folderName = null, uid = null,
    )

    @Test
    fun startsAtBrowse() {
        val r = AppRouter()
        assertNull(r.workCard)
        assertNull(r.pointCard)
        assertNull(r.droppedPin)
        assertTrue(r.selectedWorkIds.isEmpty())
        assertNull(r.focusedWorkId)
        assertNull(r.selectedPointId)
    }

    @Test
    fun focusingAWorkLeavesChipFilterMode() {
        val r = AppRouter()
        r.toggleWorkFilter(7)
        r.toggleWorkFilter(8)
        assertEquals(setOf(7, 8), r.selectedWorkIds)

        r.focusWork(1)
        // 作品模式与 chips 同时生效的话,地图「要显示什么」会被双重定义。
        assertTrue(r.selectedWorkIds.isEmpty())
        assertEquals(1, r.focusedWorkId)
    }

    @Test
    fun focusingTheSameWorkFromItsPointCardJustGoesBackToTheCard() {
        val r = AppRouter()
        r.focusWork(1)
        r.selectPoint(point("p1", 1))

        // 地标卡上点作品名 = 回到下层的作品卡,呈现与镜头都不该重来一遍。
        r.focusWork(1)
        assertEquals(1, r.focusedWorkId)
        assertNull(r.pointCard)
    }

    @Test
    fun focusingTheSameWorkTwiceIsIdempotent() {
        val r = AppRouter()
        r.focusWork(1)
        val first = r.backStack.last()
        r.focusWork(1)
        // 用 assertSame 钉栈上的**那个元素**:NavDisplay 按元素身份决定要不要重建 entry,
        // 换了新实例就等于「呈现重来一遍」。workCard 是逐次读取派生的投影,不适合做这个断言。
        assertSame(first, r.backStack.last())
        assertEquals(2, r.backStack.size)
        assertNull(r.pointCard)
    }

    @Test
    fun selectingAPointDismissesTheDroppedPin() {
        val r = AppRouter()
        r.dropPin(LatLon(35.0, 139.0))
        r.selectPoint(point("p1", 1))

        assertNull(r.droppedPin)
        assertEquals("p1", r.selectedPointId)
        assertEquals(1, r.pointCard?.bangumiId)
    }

    @Test
    fun closingThePointCardFallsBackToTheWorkCardUnderneath() {
        val r = AppRouter()
        r.focusWork(1)
        r.selectPoint(point("p1", 1))

        r.closePointCard()
        assertNull(r.pointCard)
        assertEquals(1, r.focusedWorkId) // 只退一层,作品卡还在
    }

    @Test
    fun closingAPointCardOpenedWithoutAWorkCardLandsOnBrowse() {
        val r = AppRouter()
        r.selectPoint(point("p1", 1))

        r.closePointCard()
        assertNull(r.pointCard)
        assertNull(r.workCard)
    }

    @Test
    fun backToBrowseClearsTheWholeStack() {
        val r = AppRouter()
        r.toggleWorkFilter(7)
        r.focusWork(1)
        r.selectPoint(point("p1", 1))

        r.backToBrowse()
        assertNull(r.workCard)
        assertNull(r.pointCard)
        assertNull(r.droppedPin)
        assertTrue(r.selectedWorkIds.isEmpty())
    }

    @Test
    fun togglingAChipAddsThenRemovesIt() {
        val r = AppRouter()
        r.toggleWorkFilter(7)
        assertEquals(setOf(7), r.selectedWorkIds)
        r.toggleWorkFilter(7)
        assertTrue(r.selectedWorkIds.isEmpty())
    }

    @Test
    fun togglingAChipLeavesWorkMode() {
        val r = AppRouter()
        r.focusWork(1)
        r.selectPoint(point("p1", 1))

        r.toggleWorkFilter(7)
        assertNull(r.workCard)
        assertNull(r.pointCard)
        assertEquals(setOf(7), r.selectedWorkIds)
    }

    @Test
    fun deepLinkBidsReplaceTheFilterWholesale() {
        val r = AppRouter()
        r.toggleWorkFilter(7)
        r.focusWork(1)

        r.setWorkFilter(setOf(2, 3))
        assertEquals(setOf(2, 3), r.selectedWorkIds) // 替换而非合并
        assertNull(r.workCard)
        assertNull(r.pointCard)
    }

    @Test
    fun droppingAPinLeavesTheCurrentCards() {
        val r = AppRouter()
        r.focusWork(1)
        r.selectPoint(point("p1", 1))

        r.dropPin(LatLon(36.0, 140.0))
        // 长按是「离开当前卡片去看新地点」,所以关掉图钉后回到 Browse 而不是原卡片。
        assertNull(r.workCard)
        assertNull(r.pointCard)
        assertEquals(36.0, r.droppedPin?.coordinate?.lat)

        r.closeDroppedPin()
        assertNull(r.droppedPin)
    }

    @Test
    fun cameraSessionOpensAndCloses() {
        val r = AppRouter()
        val params = CameraSessionParams(
            photoUrl = "https://example.invalid/a.jpg",
            name = "某地",
            themeHex = null,
            location = "35.0,139.0",
        )
        r.openCamera(params)
        assertEquals(params, r.cameraSession)
        r.closeCamera()
        assertNull(r.cameraSession)
    }

    @Test
    fun popRemovesOnlyTheTopAndRefusesAtHome() {
        val r = AppRouter()
        assertFalse("Home 之下没有东西可弹", r.pop())
        r.focusWork(7)
        r.selectPoint(point("p1", 7))
        assertTrue(r.pop())
        assertEquals(listOf(SheetKey.Home, SheetKey.Work(7)), r.backStack)
        assertTrue(r.pop())
        assertEquals(listOf(SheetKey.Home), r.backStack)
        assertFalse(r.pop())
    }

    @Test
    fun pilgrimageLogReplacesTheCardStackAndKeepsWorkModeOff() {
        val r = AppRouter()
        r.focusWork(7)
        r.selectPoint(point("p1", 7))
        r.openPilgrimageLog()
        assertEquals(listOf(SheetKey.Home, SheetKey.PilgrimageLog), r.backStack)
        assertNull("记录页不是作品模式", r.focusedWorkId)
        // 从记录页点一个地标:地标卡叠在记录页之上,关卡回到记录页。
        r.selectPoint(point("p2", 9))
        assertEquals(listOf(SheetKey.Home, SheetKey.PilgrimageLog, SheetKey.Point("p2", 9)), r.backStack)
        assertNull(r.focusedWorkId)
        r.closePointCard()
        assertEquals(listOf(SheetKey.Home, SheetKey.PilgrimageLog), r.backStack)
        // 组头进作品卡:记录页让位给作品卡。
        r.focusWork(9)
        assertEquals(listOf(SheetKey.Home, SheetKey.Work(9)), r.backStack)
        r.closePilgrimageLog()
        assertEquals("不在栈顶时 closePilgrimageLog 不动栈", listOf(SheetKey.Home, SheetKey.Work(9)), r.backStack)
    }
}

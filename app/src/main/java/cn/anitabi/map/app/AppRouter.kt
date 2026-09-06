package cn.anitabi.map.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.ui.scene.CameraSessionParams
import cn.anitabi.map.ui.sheet.SheetKey

/**
 * 导航状态的唯一持有者。把 Web 版的历史操作映射成栈操作
 * （setBangumi → focusWork、setPopup → selectPoint、back() → pop）。
 *
 * 事实源是 [backStack]（Navigation 3 的 back stack，栈底永远是 [SheetKey.Home]）；
 * [workCard] / [pointCard] / [droppedPin] 是它的**投影**，供地图与旧调用方读取，
 * 不是第二份状态。相机指令随入口路径不同而行为不同，因此由 RootScreen 持有。
 */
data class WorkSelection(val id: Int)

data class PointSelection(val id: String, val bangumiId: Int)

/** 长按落下的图钉（由 DroppedPinSheet 接收）。 */
data class DroppedPin(val coordinate: LatLon)

class AppRouter {

    // MARK: 呈现栈

    private val _backStack = mutableStateListOf<SheetKey>(SheetKey.Home)

    /** sheet 的内容栈。**只通过本类的方法改**(架构约束),对外只暴露只读视图;NavDisplay 与 RootScreen 只读它。 */
    val backStack: List<SheetKey> get() = _backStack

    val workCard: WorkSelection?
        get() = backStack.lastOrNull { it is SheetKey.Work }?.let { WorkSelection((it as SheetKey.Work).id) }

    val pointCard: PointSelection?
        get() = (backStack.lastOrNull() as? SheetKey.Point)?.let { PointSelection(it.id, it.bangumiId) }

    val droppedPin: DroppedPin?
        get() = (backStack.lastOrNull() as? SheetKey.DroppedPin)?.let { DroppedPin(LatLon(it.lat, it.lng)) }

    /** 对比拍摄（SceneComparisonScreen）的入口。全屏覆盖，不进 sheet 栈。 */
    var cameraSession: CameraSessionParams? by mutableStateOf(null)

    fun openCamera(params: CameraSessionParams) {
        cameraSession = params
    }

    fun closeCamera() {
        cameraSession = null
    }

    /** 通过 chips 的多作品过滤（Web 的 bids）。与作品模式互斥。 */
    var selectedWorkIds: Set<Int> by mutableStateOf(emptySet())
        private set

    /** 屏幕顶部的临时通知。 */
    val toast = ToastCenter()

    // MARK: 向地图的投影

    val focusedWorkId: Int? get() = workCard?.id
    val selectedPointId: String? get() = pointCard?.id

    // MARK: 跳转

    /**
     * 进入作品模式（web setBangumiNoFly）。相机移动由调用方负责。
     * 收起多作品过滤 —— 作品模式与 chips 同时生效的话，
     * 地图「要显示什么」会被双重定义。
     */
    fun focusWork(id: Int) {
        val index = backStack.indexOfFirst { it is SheetKey.Work && it.id == id }
        if (index >= 0) {
            // 同一作品的卡片已在栈里:只是回到它（呈现与镜头都不动）。
            truncate(index + 1)
            return
        }
        selectedWorkIds = emptySet()
        truncate(1)
        _backStack.add(SheetKey.Work(id))
    }

    /** 选中地标（web setPopup）。栈顶已是地标卡则原地替换。 */
    fun selectPoint(point: ScenePoint) {
        while (backStack.lastOrNull().let { it is SheetKey.DroppedPin || it is SheetKey.Point }) {
            _backStack.removeAt(_backStack.lastIndex)
        }
        _backStack.add(SheetKey.Point(id = point.id, bangumiId = point.bangumiId))
    }

    /** 关闭地标卡片（web back() 退一层）。栈里若还有作品卡片则回到它。 */
    fun closePointCard() {
        if (backStack.lastOrNull() is SheetKey.Point) _backStack.removeAt(_backStack.lastIndex)
    }

    /** 回到 Browse（作品卡片的✕、点击压暗区域）。过滤也全部解除。 */
    fun backToBrowse() {
        truncate(1)
        selectedWorkIds = emptySet()
    }

    /** 系统返回键：退一层。栈底（Home）不可退，返回 false 让 Activity 处理。 */
    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        _backStack.removeAt(_backStack.lastIndex)
        return true
    }

    /** 切换 chips。处于作品模式时先解除再生效。 */
    fun toggleWorkFilter(id: Int) {
        truncate(1)
        selectedWorkIds = if (selectedWorkIds.contains(id)) {
            selectedWorkIds - id
        } else {
            selectedWorkIds + id
        }
    }

    /** 深链 bids 的落地（整体替换多作品过滤）。 */
    fun setWorkFilter(ids: Set<Int>) {
        truncate(1)
        selectedWorkIds = ids
    }

    /**
     * 长按落下图钉。先收起地标／作品卡片 —— 长按是「离开当前卡片去看
     * 新地点」的操作，所以关闭图钉后回到 Browse 而非原卡片才顺理成章。
     */
    fun dropPin(at: LatLon) {
        truncate(1)
        _backStack.add(SheetKey.DroppedPin(at.lat, at.lng))
    }

    fun closeDroppedPin() {
        if (backStack.lastOrNull() is SheetKey.DroppedPin) _backStack.removeAt(_backStack.lastIndex)
    }

    /** 打开「巡礼记录」列表（Home 的入口）。与卡片互斥:栈变成 [Home, PilgrimageLog]。 */
    fun openPilgrimageLog() {
        truncate(1)
        _backStack.add(SheetKey.PilgrimageLog)
    }

    fun closePilgrimageLog() {
        if (backStack.lastOrNull() == SheetKey.PilgrimageLog) _backStack.removeAt(_backStack.lastIndex)
    }

    /** 把栈裁到前 [size] 个元素（至少保留 Home）。 */
    private fun truncate(size: Int) {
        while (backStack.size > maxOf(size, 1)) _backStack.removeAt(_backStack.lastIndex)
    }
}

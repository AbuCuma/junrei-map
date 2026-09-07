package cn.anitabi.map.app

import cn.anitabi.map.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.anitabi.map.data.LatLonRegion
import cn.anitabi.map.data.PilgrimageLogGroups
import cn.anitabi.map.data.update.UpdateChecker
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.map.google.AnitabiMap
import cn.anitabi.map.map.google.MapObstruction
import cn.anitabi.map.map.google.MapCommand
import cn.anitabi.map.map.google.MapIdleState
import cn.anitabi.map.support.AnitabiWebLink
import cn.anitabi.map.support.ExternalLinks
import cn.anitabi.map.ui.components.MapControlStack
import cn.anitabi.map.ui.components.RandomWorkButton
import cn.anitabi.map.ui.home.AboutSheet
import cn.anitabi.map.ui.home.EtiquetteSheet
import cn.anitabi.map.ui.home.HomeSheet
import cn.anitabi.map.ui.home.ViewportChip
import cn.anitabi.map.ui.home.WelcomeOverlay
import cn.anitabi.map.ui.image.ImageViewer
import cn.anitabi.map.ui.point.DroppedPinSheet
import cn.anitabi.map.ui.point.PointCardSheet
import cn.anitabi.map.ui.scene.SceneComparisonScreen
import cn.anitabi.map.theme.LocalAnitabiPalette
import cn.anitabi.map.theme.LocalNameLocale
import cn.anitabi.map.ui.sheet.SheetActions
import cn.anitabi.map.ui.sheet.SheetKey
import cn.anitabi.map.ui.sheet.SheetNavHost
import cn.anitabi.map.ui.sheet.SheetPeek
import cn.anitabi.map.ui.work.WorkCardSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import cn.anitabi.map.ui.sheet.DetentSheet
import cn.anitabi.map.ui.sheet.Detent
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.snapshotFlow
import cn.anitabi.map.map.engine.PointVisitFilter
import cn.anitabi.map.ui.log.PilgrimageLogSheet

/**
 * 宿主画面(相当于 iOS RootView)。全屏地图 + 常驻单一 sheet + 悬浮控件 + toast + 模态组。
 * 导航状态由 AppRouter 唯一持有,这里只负责映射到呈现。
 */
private const val TAG = "RootScreen"

@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
fun RootScreen() {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context) }
    val store = graph.store
    val router = graph.router
    val locationProvider = graph.locationProvider
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val nameLocale = LocalNameLocale.current

    var commandSeq by remember { mutableStateOf(0L) }
    var command by remember { mutableStateOf<MapCommand?>(null) }
    val uiScope = rememberCoroutineScope()
    var idleState by remember { mutableStateOf<MapIdleState?>(null) }
    // 方位以 State 持有、经 provider lambda 下发:旋转手势每帧更新,若在本函数体读值,
    // 整个 RootScreen 逐帧重组(compose-performance: 高频值跨界传 provider)。
    val mapBearing = remember { mutableFloatStateOf(0f) }
    var viewportChips by remember { mutableStateOf<List<ViewportChip>>(emptyList()) }

    var mapType by remember { mutableStateOf(graph.prefs.mapBaseStyle) }
    var photoLayer by remember { mutableStateOf(graph.prefs.isPhotoLayerVisible) }
    // 巡礼记录过滤(图层菜单第三段)。prefs 里存 enum 名,非法值回落 All。
    var visitFilter by remember {
        mutableStateOf(
            runCatching { PointVisitFilter.valueOf(graph.prefs.pointVisitFilter) }.getOrDefault(PointVisitFilter.All),
        )
    }
    val pilgrimageLog = graph.pilgrimageLog
    var locationActive by remember { mutableStateOf(false) }
    // 这四个是纯 UI 开关,用 rememberSaveable 让它们活过进程死亡。
    // 卡片栈与相机位不在这里 —— 它们由 ON_STOP 写入的 prefs.restoredDeepLink 恢复。
    var showWelcome by rememberSaveable { mutableStateOf(!graph.prefs.hasCompletedOnboarding) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showEtiquette by rememberSaveable { mutableStateOf(false) }
    var imageViewerUrl by rememberSaveable { mutableStateOf<String?>(null) }

    // 文案在组合期取好:回调/效果里用 context.getString 会绕开 Compose 的配置变更
    // (lint LocalContextGetResourceValueCall)。
    val locationDeniedMessage = stringResource(R.string.location_permission_denied)
    val deadLinkMessage = stringResource(R.string.this_links_content_doesnt_exist_or_has)

    fun flyToPoint(point: ScenePoint) {
        commandSeq += 1
        command = MapCommand.FlyToPoint(point.lat, point.lng, point.priority, commandSeq)
    }

    // 系统返回键:sheet 栈的 pop 由 NavDisplay 处理(含预测式返回);
    // 欢迎页 / 图片查看器 / 相机这三个全屏覆盖层的返回在它们各自的组合处登记
    // (组合顺序靠后 = 优先级更高,能压过 NavDisplay)。搜索态的返回由 HomeSheet 内的
    // BackHandler(更深的组合优先)先行消费。

    // 位置权限(COARSE 即可 — 与 iOS 相同的粗定位思想)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            locationProvider.isDenied = false
            locationProvider.startUpdates()
            locationActive = true
        } else {
            // 拒绝不能静默:给出提示;再次点击定位按钮时引导到系统设置(见 requestLocation)。
            locationProvider.isDenied = true
            router.toast.show(locationDeniedMessage)
        }
    }

    fun requestLocation() {
        if (locationProvider.isAuthorized) {
            locationProvider.startUpdates()
            locationActive = true
            locationProvider.location?.let { loc ->
                commandSeq += 1
                command = MapCommand.UserLocation(loc.lat, loc.lng, commandSeq)
            }
        } else if (locationProvider.isDenied) {
            // 已拒绝过一次:系统对话框大概率不再弹(永久拒绝时是静默 no-op),直接去设置页。
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
        }
    }

    // bootstrapAttempt:断网首启失败后,HomeSheet 的「重试」按钮通过它重新驱动加载。
    var bootstrapAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(bootstrapAttempt) {
        store.bootstrap()
        store.loadAuxiliary()
    }
    // 巡礼记录与数据集并行加载(幂等;几十 KB 的 IO,远早于用户能点到卡片)。
    LaunchedEffect(Unit) { pilgrimageLog.load() }
    // 检查更新(≤ 每 24 小时一次;关于页可关)。失败静默,关于页能看到原因。
    LaunchedEffect(Unit) { if (graph.prefs.updateAutoCheckEnabled) graph.updateChecker.checkIfDue() }
    val updateState by graph.updateChecker.state.collectAsStateWithLifecycle()
    val availableUpdate = (updateState as? UpdateChecker.State.Available)?.takeUnless { it.skipped }?.release

    // 位置变动就重算附近(移动不足 50m 不重算 — iOS 的节流)。
    LaunchedEffect(locationProvider.location, store.dataGeneration) {
        val loc = locationProvider.location ?: return@LaunchedEffect
        if (store.dataGeneration == 0) return@LaunchedEffect
        if (!locationProvider.shouldRecomputeNearby(loc)) return@LaunchedEffect
        store.recomputeNearby(around = loc)
        locationProvider.markNearbyRecomputed(loc)
        // 首次定位后,若镜头还没动过就飞往当前位置(授权后即刻的体验)
        if (locationActive && command == null) {
            commandSeq += 1
            command = MapCommand.UserLocation(loc.lat, loc.lng, commandSeq)
        }
    }

    // 视口 chips(minZoom 12、2 部作品以上,300ms 防抖 — iOS ViewportWorkChips)。
    LaunchedEffect(Unit) {
        // dataGeneration 必须进 snapshotFlow 的**源**:它只在 collect 里被读时,
        // 冷启动那次「地图先 settle、数据后到」的顺序会让 chips 永远停在空列表,
        // 直到用户手动平移地图再触发一次 idle。
        snapshotFlow { idleState to store.dataGeneration }
            .debounce(300)
            .collect { (idle, generation) ->
                if (idle == null || idle.zoom < 12.0 || generation == 0) {
                    viewportChips = emptyList()
                    return@collect
                }
                // 全点(5 万)的线性扫描,不在主线程上跑。
                val works = withContext(Dispatchers.Default) {
                    store.worksInViewport(
                        LatLonRegion(
                            idle.centerLat, idle.centerLng,
                            idle.viewportLatDelta, idle.viewportLngDelta,
                        )
                    ).take(8)
                }
                viewportChips = if (works.size >= 2) {
                    works.map { (id, count) -> ViewportChip(id, store.name(id, nameLocale), count) }
                } else {
                    emptyList()
                }
            }
    }

    // 深链的 router 侧参数(bangumiId / pid)。等数据到达后再落地,
    // 落地完成后才清除 pendingDeepLink(相机的 c/z 由 AnitabiMap 读取)。
    LaunchedEffect(graph.pendingDeepLink.value, store.dataGeneration) {
        val link = graph.pendingDeepLink.value ?: return@LaunchedEffect
        if (link.bangumiId == null && link.pointId == null && link.bids.isEmpty()) {
            // 纯相机深链＝完整状态:收起卡片与过滤(与 web 相同的全置换语义)。
            router.backToBrowse()
            return@LaunchedEffect // 清除由 AnitabiMap 的 onDeepLinkConsumed 负责
        }
        if (store.dataGeneration == 0) return@LaunchedEffect
        if (link.bids.isNotEmpty()) {
            router.setWorkFilter(link.bids.toSet())
        }
        link.bangumiId?.let { id ->
            if (store.bangumi(id) != null) {
                router.focusWork(id)
            } else {
                router.toast.show(deadLinkMessage)
            }
        }
        link.pointId?.let { pid ->
            val point = store.point(pid)
            if (point != null) {
                router.selectPoint(point)
            } else if (link.bangumiId == null) {
                router.toast.show(deadLinkMessage)
            }
        }
        graph.pendingDeepLink.value = null
    }

    // 状态恢复:进入后台时把当前状态以 canonical URL 保存(不用空值覆盖)。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // 回前台恢复订阅(与 ON_STOP 的注销配对)。
                    if (locationActive) locationProvider.startUpdates()
                }
                Lifecycle.Event.ON_STOP -> {
                    // 后台不保留 fused 订阅:App 的承诺是「仅使用期间粗定位、不追踪」。
                    locationProvider.stopUpdates()
                    // 留着焦点的话 resume 时系统会复原 IME(真机反馈)
                    focusManager.clearFocus()
                    val idle = idleState ?: return@LifecycleEventObserver
                    graph.prefs.restoredDeepLink = AnitabiWebLink.canonical(
                        bangumiId = router.focusedWorkId,
                        pointId = router.selectedPointId,
                        coordinate = LatLon(idle.centerLat, idle.centerLng),
                        zoom = idle.zoom,
                        bids = router.selectedWorkIds.toList(),
                    )
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val topKey = router.backStack.last()

    // 键盘与焦点治理:
    // ① 离开带搜索行的 Home 层时,键盘与焦点一并收起
    LaunchedEffect(topKey) {
        if (topKey != SheetKey.Home) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }
    // ② 键盘被手动收起时,光标(焦点)也一并清除
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) focusManager.clearFocus()
        imeWasVisible = imeVisible
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val palette = LocalAnitabiPalette.current
        val containerHeightPx = with(density) { maxHeight.toPx() }
        // 横屏:同一个三档 sheet 以竖屏宽度停靠在左侧(Google Maps 横屏亦如此),地图的避让用左 padding,
        // 骰子在「未遮挡区的左下」(真机反馈批次 7)。configChanges 下不会重建,状态原样存活。
        val isLandscape = maxWidth > maxHeight

        // 三档 sheet(DetentSheet):锚点全部静态,由容器高 + SheetPeek 换算,在布局相位更新。
        val sheetState = remember { AnchoredDraggableState(initialValue = Detent.Medium) }
        val restDetent = SheetPeek.restDetent(topKey, isLandscape)
        val sheetActions = remember(sheetState, restDetent) {
            SheetActions(
                expand = { uiScope.launch { sheetState.animateTo(Detent.Expanded) } },
                collapse = { uiScope.launch { sheetState.animateTo(restDetent) } },
            )
        }
        // 层切换一律落到该层的落档(竖屏半开、横屏卡片展开;push 与 pop 同样;不再记忆各层档位)。
        // 旋转也走这里:横竖屏的档位集不同,落档最稳。
        // animateTo 内部是 anchoredDrag(target):锚点在布局相位换成新层的之后会带着新锚点重跑,最终停在新层的落档。
        LaunchedEffect(topKey, restDetent) {
            sheetState.animateTo(restDetent)
        }

        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val anchorsFor: (Float) -> DraggableAnchors<Detent> = { heightPx ->
            val containerHeight = with(density) { heightPx.toDp() }
            DraggableAnchors {
                Detent.entries.forEach { detent ->
                    SheetPeek.visibleDp(topKey, detent, containerHeight, navBarBottom, isLandscape)?.let { visible ->
                        detent at heightPx - with(density) { visible.toPx() }
                    }
                }
            }
        }
        val capPx = containerHeightPx * SheetPeek.MAP_OBSTRUCTION_CAP

        // sheet 当前露出的高度(px),不分横竖屏。requireOffset 在锚点装好之前会抛 → 视作 0。
        // **在 lambda 里读**:offset 是快照状态,读它的一方(地图 padding / 控件抬升)
        // 才会随拖拽逐帧失效,RootScreen 本身不陪跑(compose-performance: deferred reads)。
        fun rawVisiblePx(): Float {
            val offset = runCatching { sheetState.requireOffset() }.getOrNull() ?: return 0f
            return (containerHeightPx - offset).coerceIn(0f, containerHeightPx)
        }
        // 弹簧到位后会停在哪:目标档的露出高。
        fun rawTargetPx(): Float {
            val position = sheetState.anchors.positionOf(sheetState.targetValue)
            if (position.isNaN()) return 0f
            return (containerHeightPx - position).coerceIn(0f, containerHeightPx)
        }
        // 控件抬升用:竖屏受 55% 封顶;横屏一律 0 —— sheet 在左侧,控件都在它右边,不随档位抬升。
        fun sheetVisiblePx(): Float = if (isLandscape) 0f else rawVisiblePx()
        fun sheetTargetPx(): Float = if (isLandscape) 0f else rawTargetPx().coerceAtMost(capPx)

        // 横屏 sheet 停靠在 safeDrawing 的起始内边距之内(状态栏/刘海),地图的让位与骰子要把这段也算上,
        // 否则 Google 标志会被 sheet 盖住(真机反馈)。
        val sheetStartInset = WindowInsets.safeDrawing.asPaddingValues().calculateStartPadding(LocalLayoutDirection.current)
        val sheetSidePadding = sheetStartInset + SheetPeek.landscapeWidth + 12.dp
        val obstruction: () -> MapObstruction = if (isLandscape) {
            // 横屏的让位跟着档位走:展开时挡整列(左 padding),收成一条时只挡底部那条身份条(下 padding),
            // 拖拽中按露出高在两者之间插值。此前是固定整列 padding —— 真机实测 GMS 会剔除深入 padding 区的
            // Marker(收起的 sheet 上方那一列里落针、剧照牌都不显示),等于把 43% 的屏幕做成了 Marker 死区。
            fun landscapeObstruction(visiblePx: Float): PaddingValues {
                val anchors = sheetState.anchors
                if (anchors.size == 0) return PaddingValues(start = sheetSidePadding)
                val miniPx = (containerHeightPx - anchors.maxPosition()).coerceAtLeast(0f)
                val fullPx = (containerHeightPx - anchors.minPosition()).coerceAtLeast(miniPx + 1f)
                val f = ((visiblePx - miniPx) / (fullPx - miniPx)).coerceIn(0f, 1f)
                return PaddingValues(
                    start = sheetStartInset + (SheetPeek.landscapeWidth + 12.dp) * f,
                    bottom = with(density) { (miniPx * (1f - f)).toDp() },
                )
            }
            { MapObstruction(current = landscapeObstruction(rawVisiblePx()), target = landscapeObstruction(rawTargetPx())) }
        } else {
            {
                MapObstruction(
                    current = PaddingValues(bottom = with(density) { sheetVisiblePx().coerceAtMost(capPx).toDp() }),
                    target = PaddingValues(bottom = with(density) { sheetTargetPx().toDp() }),
                )
            }
        }

        val mapAndControls: @Composable BoxScope.() -> Unit = {
            AnitabiMap(
                // 当前值与目标值一起交出去。地图侧判「选中一个点之后要不要让位」的判据是
                // 「卡片**最终**会遮住多少」,而弹簧要几百毫秒才到位。
                obstruction = obstruction,
                store = store,
                focusedWorkId = router.focusedWorkId,
                selectedPointId = router.selectedPointId,
                selectedWorkIds = router.selectedWorkIds,
                droppedPin = router.droppedPin?.coordinate,
                command = command,
                onPointTap = { id ->
                    val point = store.point(id)
                    if (point != null) router.selectPoint(point)
                    else Log.w(TAG, "tapped point not in store: $id")
                },
                onWorkTap = { id ->
                    router.focusWork(id)
                    store.rememberVisit(id)
                },
                onDimExit = { router.backToBrowse() },
                onMapTap = { router.closePointCard() },
                onLongPress = { router.dropPin(it) },
                modifier = Modifier.fillMaxSize(),
                mapType = mapType,
                isPhotoLayerVisible = photoLayer,
                visitFilter = visitFilter,
                visitedPointIds = pilgrimageLog.visitedIds,
                visitedGeneration = pilgrimageLog.generation,
                isMyLocationEnabled = locationActive && locationProvider.isAuthorized,
                onCameraIdle = { idleState = it },
                onBearingChanged = { mapBearing.floatValue = it },
                deepLink = graph.pendingDeepLink.value,
                onDeepLinkConsumed = {
                    // 只有相机专用(仅 c/z)的深链才在这里清除。
                    // 带 bangumiId/pid 的由上方的 router 落地 effect 清除。
                    val link = graph.pendingDeepLink.value
                    if (link != null && link.bangumiId == null && link.pointId == null &&
                        link.bids.isEmpty()
                    ) {
                        graph.pendingDeepLink.value = null
                    }
                },
            )

            // 悬浮控件的系统栏避让:横屏时三键导航栏会出现在侧边、手势条贴底,
            // 而横屏没有抬升 → 需要 safeDrawing。竖屏只避让水平向(抬升本身盖过底部栏)。
            val controlsInsets = if (isLandscape) {
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            } else {
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            }
            // 悬浮控件跟随 sheet 顶缘抬升,与地图 padding 同一个 55% 封顶(真机反馈 S3)。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(controlsInsets)
                    .offset { IntOffset(0, -sheetVisiblePx().coerceAtMost(capPx).toInt()) }
                    .padding(end = 12.dp, bottom = 12.dp),
            ) {
                MapControlStack(
                    isLocationActive = locationActive,
                    onLocationTap = { requestLocation() }, // lambda 而非方法引用:方法引用不参与强跳过的 memoize
                    bearing = { mapBearing.floatValue },
                    onCompassTap = {
                        commandSeq += 1
                        command = MapCommand.ResetBearing(seq = commandSeq)
                    },
                    baseStyle = mapType,
                    onBaseStyleChange = {
                        mapType = it
                        graph.prefs.mapBaseStyle = it
                    },
                    isPhotoLayerVisible = photoLayer,
                    onPhotoLayerToggle = {
                        photoLayer = !photoLayer
                        graph.prefs.isPhotoLayerVisible = photoLayer
                    },
                    visitFilter = visitFilter,
                    onVisitFilterChange = {
                        visitFilter = it
                        graph.prefs.pointVisitFilter = it.name
                    },
                )
            }
            // 随机钮:与右侧相同的抬升基准 + 仅叠加 Google logo 带 40dp。
            val attributionBandPx = with(density) { 40.dp.toPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(controlsInsets)
                    .offset { IntOffset(0, -(sheetVisiblePx().coerceAtMost(capPx) + attributionBandPx).toInt()) }
                    // 横屏:骰子在「右侧未遮挡区的左下」= 卡片右缘外侧(定位/图层仍在右)。
                    .padding(start = if (isLandscape) sheetSidePadding + 12.dp else 12.dp, bottom = 12.dp),
            ) {
                RandomWorkButton(onClick = {
                    store.randomWork()?.let { work ->
                        router.focusWork(work.id)
                        store.rememberVisit(work.id)
                    }
                })
            }
        }

        // Home 入口用的汇总(已完成地标数 / 作品数)。与记录页同一套分组规则,两处数字才对得上
        //(点还在、作品已不在数据集里的记录,记录页算孤儿,这里也不数)。
        val visitedSummary = remember(pilgrimageLog.generation, store.dataGeneration) {
            val groups = PilgrimageLogGroups.build(
                records = pilgrimageLog.records,
                pointById = store::point,
                bangumiById = store::bangumi,
                pointCountOf = { store.pointsByBangumi[it]?.size ?: 0 },
            )
            groups.visitedCount to groups.workCount
        }
        val sheetContent: @Composable (SheetKey) -> Unit = { key ->
            when (key) {
                SheetKey.Home -> HomeSheet(
                    store = store,
                    location = locationProvider.location,
                    locationAuthorized = locationProvider.isAuthorized,
                    viewportChips = viewportChips,
                    selectedWorkIds = router.selectedWorkIds,
                    onToggleChip = { router.toggleWorkFilter(it) },
                    onRequestLocation = { requestLocation() },
                    onSelectWork = { id ->
                        router.focusWork(id)
                        store.rememberVisit(id)
                    },
                    onSelectPoint = { point ->
                        router.selectPoint(point)
                        flyToPoint(point) // 从列表进入＝拉近(从地图进入＝不动 — 按路径区分行为)
                    },
                    onSelectCity = { city ->
                        commandSeq += 1
                        // radius*2.4 clamp 到 0.02..4°(原始 radius 会凑得太近)
                        val latHalf = (city.radius * 2.4 / 111_320.0).coerceIn(0.02, 4.0) / 2.0
                        val lngHalf = latHalf / Math.cos(Math.toRadians(city.center.lat))
                        command = MapCommand.FitBounds(
                            minLat = city.center.lat - latHalf,
                            maxLat = city.center.lat + latHalf,
                            minLng = city.center.lng - lngHalf,
                            maxLng = city.center.lng + lngHalf,
                            seq = commandSeq,
                        )
                        // 与 Apple 地图一样,飞往都市后把 sheet 收到初始档以露出地图。
                        sheetActions.collapse()
                    },
                    onOpenAbout = { showAbout = true },
                    onReplayWelcome = { showWelcome = true },
                    visitedSummary = visitedSummary,
                    onOpenPilgrimageLog = { router.openPilgrimageLog() },
                    availableUpdate = availableUpdate,
                    onOpenUpdate = { ExternalLinks.openInCustomTab(context, it.htmlUrl) },
                    onSkipUpdate = { graph.updateChecker.skip(it) },
                    onOpenWeb = {
                        val idle = idleState
                        ExternalLinks.openInCustomTab(
                            context,
                            AnitabiWebLink.canonical(
                                coordinate = idle?.let { LatLon(it.centerLat, it.centerLng) },
                                zoom = idle?.zoom,
                            ),
                        )
                    },
                    onRetryLoad = { bootstrapAttempt += 1 },
                )

                is SheetKey.Work -> WorkCardSheet(
                    store = store,
                    workId = key.id,
                    pilgrimageLog = pilgrimageLog,
                    onClose = { router.backToBrowse() },
                    onSelectPoint = { point ->
                        router.selectPoint(point)
                        flyToPoint(point)
                    },
                )

                is SheetKey.Point -> PointCardSheet(
                    store = store,
                    pointId = key.id,
                    bangumiId = key.bangumiId,
                    onClose = { router.closePointCard() },
                    onOpenWork = { id -> router.focusWork(id) },
                    onShowEtiquette = { showEtiquette = true },
                    onOpenImage = { url -> imageViewerUrl = url },
                    onOpenCamera = { params -> router.openCamera(params) },
                    onToast = { router.toast.show(it) },
                    isVisited = pilgrimageLog.isVisited(key.id),
                    onToggleVisited = { store.point(key.id)?.let { graph.toggleVisited(it) } },
                )

                SheetKey.PilgrimageLog -> PilgrimageLogSheet(
                    store = store,
                    log = pilgrimageLog,
                    onClose = { router.closePilgrimageLog() },
                    onSelectWork = { id ->
                        router.focusWork(id)
                        store.rememberVisit(id)
                    },
                    onSelectPoint = { point ->
                        router.selectPoint(point)
                        flyToPoint(point)
                    },
                )

                is SheetKey.DroppedPin -> DroppedPinSheet(
                    store = store,
                    coordinate = LatLon(key.lat, key.lng),
                    onClose = { router.closeDroppedPin() },
                    onSelectPoint = { point ->
                        router.selectPoint(point)
                        flyToPoint(point)
                    },
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            mapAndControls()
            // 横屏:同一个 sheet 收窄到竖屏宽度、停靠左侧,避开状态栏与刘海;档位按避让后的高度算。
            val sheetModifier = if (isLandscape) {
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Top))
                    .width(SheetPeek.landscapeWidth)
                    .fillMaxHeight()
            } else {
                Modifier.fillMaxSize()
            }
            DetentSheet(
                state = sheetState,
                anchorsFor = anchorsFor,
                modifier = sheetModifier,
            ) {
                SheetNavHost(
                    backStack = router.backStack,
                    actions = sheetActions,
                    onBack = { router.pop() },
                    modifier = Modifier.fillMaxSize(),
                    content = sheetContent,
                )
            }
        }

        if (showWelcome) {
            // 首启引导期间返回=完成引导,而不是退出 App。
            BackHandler {
                showWelcome = false
                graph.prefs.hasCompletedOnboarding = true
            }
            WelcomeOverlay(onStart = {
                graph.prefs.hasCompletedOnboarding = true
                showWelcome = false
            })
        }

        // 对比拍摄:全屏覆盖,强制横屏 + 沉浸式(隐藏通知栏/导航栏 — 真机反馈)。
        router.cameraSession?.let { session ->
            BackHandler { router.closeCamera() }
            val activity = context as? Activity
            DisposableEffect(session) {
                activity?.requestedOrientation =
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                val insetsController = activity?.window?.let { window ->
                    WindowCompat.getInsetsController(window, window.decorView)
                }
                insetsController?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController?.hide(WindowInsetsCompat.Type.systemBars())
                onDispose {
                    activity?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    insetsController?.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            SceneComparisonScreen(
                params = session,
                toast = router.toast,
                onClose = { router.closeCamera() },
            )
        }

        // 图片查看器:内联 overlay(用 Dialog 会变成另一个窗口,toast 被挡,通知栏也盖不住)
        imageViewerUrl?.let { url ->
            BackHandler { imageViewerUrl = null }
            ImageViewer(url = url, toast = router.toast, onDismiss = { imageViewerUrl = null })
        }

        // toast 在最上层(也要盖住相机/图片查看器的全屏 overlay)
        ToastOverlay(
            center = router.toast,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 8.dp),
        )
    }

    if (showAbout) {
        AboutSheet(versionName = graph.versionName, onDismiss = { showAbout = false })
    }
    if (showEtiquette) {
        EtiquetteSheet(onDismiss = { showEtiquette = false })
    }
}

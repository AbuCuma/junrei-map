package cn.anitabi.map.map.google

import android.animation.ValueAnimator
import android.os.Trace
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cn.anitabi.map.R
import cn.anitabi.map.app.AppGraph
import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.map.engine.MapCameraPlanner
import cn.anitabi.map.map.engine.MapDataset
import cn.anitabi.map.map.engine.MapEngine
import cn.anitabi.map.map.engine.DotField
import cn.anitabi.map.map.engine.MapEngineRequest
import cn.anitabi.map.map.engine.PointVisitFilter
import cn.anitabi.map.map.engine.MapSceneHitTest
import cn.anitabi.map.map.engine.SheetRevealPolicy
import cn.anitabi.map.map.engine.MapViewport
import cn.anitabi.map.map.engine.WorkRegionGeometry
import cn.anitabi.map.support.MapDeepLink
import cn.anitabi.map.theme.ColorUtilities
import cn.anitabi.map.theme.LocalNameLocale
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberCameraPositionState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 地图容器（iOS MapCanvas 的移植）。地图**不持有导航状态** ——
 * 选中、作品模式都归 AppRouter 所有，地图只负责「画」和「把操作往上抛」。
 * 相机是一次性的指令流（MapCommand，用 seq 判重 — 与 iOS MapCameraCommand 相同）。
 */
private val JAPAN_OVERVIEW_CENTER = LatLng(36.5, 138.5)

/** RootScreen → 地图的一次性相机指令。 */
sealed interface MapCommand {
    val seq: Long

    data class FlyToPoint(
        val lat: Double,
        val lng: Double,
        /** 点的 priority（最近邻距离 m）。放大倍率由它决定。 */
        val priority: Int?,
        override val seq: Long,
    ) : MapCommand

    data class FitBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double,
        override val seq: Long,
    ) : MapCommand

    /** 定位钮：靠近当前位置（iOS .userLocation(span: 0.01) ≒ z15.5）。 */
    data class UserLocation(val lat: Double, val lng: Double, override val seq: Long) : MapCommand

    /** 指南针钮：方位回到正北（位置、zoom 保持 — 等同 iOS MKCompassButton）。 */
    data class ResetBearing(override val seq: Long) : MapCommand
}

/**
 * 常驻 sheet 对地图的遮挡。**同时给出当前值与目标值**。
 *
 * 只给当前值是不够的：从地图上选中一个点之后要不要让位，判据是「卡片**最终**会遮住多少」，
 * 而弹簧要几百毫秒才到位。此前那段代码用 `debounce(48)` 猜「沉降了没有」——
 * 动画尚未起步时就会到期，于是拿着旧档位的 padding 判定「不需要避让」
 * （真机反馈：完全不上移，点被卡片盖住）。有了 [target]，判据就是确定的：[settled]。
 */
class MapObstruction(val current: PaddingValues, val target: PaddingValues) {
    /** `animateDpAsState` 收敛时值与目标**精确相等**，所以这是一条确定判据。 */
    val settled: Boolean get() = current == target
}

/** 相机静止时向上抛的状态（chips 统计、状态恢复会读 — 相当于 iOS cameraState）。 */
data class MapIdleState(
    val centerLat: Double,
    val centerLng: Double,
    val zoom: Double,
    val viewportLatDelta: Double,
    val viewportLngDelta: Double,
)

@OptIn(MapsComposeExperimentalApi::class, FlowPreview::class)
@Composable
fun AnitabiMap(
    store: AnitabiStore,
    focusedWorkId: Int?,
    selectedPointId: String?,
    selectedWorkIds: Set<Int>,
    droppedPin: LatLon?,
    command: MapCommand?,
    onPointTap: (String) -> Unit,
    onWorkTap: (Int) -> Unit,
    onDimExit: () -> Unit,
    onMapTap: () -> Unit,
    onLongPress: (LatLon) -> Unit,
    modifier: Modifier = Modifier,
    mapType: String = "standard",
    isPhotoLayerVisible: Boolean = true,
    /** 巡礼记录过滤。[visitedPointIds] 不进 SceneKey(集合比较太贵),由 [visitedGeneration] 代表它变了。 */
    visitFilter: PointVisitFilter = PointVisitFilter.All,
    visitedPointIds: Set<String> = emptySet(),
    visitedGeneration: Int = 0,
    isMyLocationEnabled: Boolean = false,
    onCameraIdle: (MapIdleState) -> Unit = {},
    /** 方位（度）。旋转手势中也会持续送达 — 用于自绘指南针钮的显示/旋转。 */
    onBearingChanged: (Float) -> Unit = {},
    deepLink: MapDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    /**
     * 常驻 sheet 遮挡区域(dp)。竖屏是 bottom、横屏是 start(左侧卡片)。
     * 相机目标/bounds fit/Google logo 都以未遮挡区为基准(真机反馈 C3/批次 7)。
     *
     * provider 形式:padding 随 sheet 弹簧逐帧动画,由本层读值 —— 动画帧只重组
     * 地图这一层(GoogleMap 的 contentPadding 是参数,这层重组不可免),
     * RootScreen 及其余兄弟不陪跑(compose-performance: deferred reads)。
     */
    obstruction: () -> MapObstruction = {
        MapObstruction(PaddingValues(0.dp), PaddingValues(0.dp))
    },
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val isDark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val graph = remember { AppGraph.get(context) }
    // 作品标的文字语言。换语言会重建 Activity,所以在这里读一次就够。
    val nameLocale = LocalNameLocale.current

    var mapWidthPx by remember { mutableStateOf(0) }
    var mapHeightPx by remember { mutableStateOf(0) }

    // 自绘圆点层。@Stable 的持有者 + remember ⇒ PointDotOverlay 在 AnitabiMap 每帧重组时
    // 可跳过(它在 sheet 弹簧期间本来就每帧重组,不该再被这里拖累)。
    // 不以 density 为 key:state 只在 MapEffect(Unit) 里 attach 一次,按 density 重建会得到一个
    // 从未 attach 的新实例,圆点层从此空白。density 由绘制阶段的 DrawScope 自己取。
    val dotState = remember { PointDotOverlayState() }
    val obstructionState = rememberUpdatedState(obstruction)

    // MapEffect 的 block 只会跑一次，所以参数经由 State 读取。
    val focusedState = rememberUpdatedState(focusedWorkId)
    val selectedState = rememberUpdatedState(selectedPointId)
    val workIdsState = rememberUpdatedState(selectedWorkIds)
    val pinState = rememberUpdatedState(droppedPin)
    val onPointTapState = rememberUpdatedState(onPointTap)
    val onWorkTapState = rememberUpdatedState(onWorkTap)
    val onDimExitState = rememberUpdatedState(onDimExit)
    val onMapTapState = rememberUpdatedState(onMapTap)
    val onLongPressState = rememberUpdatedState(onLongPress)
    val photoLayerState = rememberUpdatedState(isPhotoLayerVisible)
    val visitFilterState = rememberUpdatedState(visitFilter)
    val visitedIdsState = rememberUpdatedState(visitedPointIds)
    val visitedGenerationState = rememberUpdatedState(visitedGeneration)
    val onCameraIdleState = rememberUpdatedState(onCameraIdle)
    val onBearingState = rememberUpdatedState(onBearingChanged)

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(JAPAN_OVERVIEW_CENTER, 5f)
    }

    val engine = remember { MapEngine() }
    @Suppress("OPT_IN_USAGE")
    val engineDispatcher = remember { Dispatchers.Default.limitedParallelism(1) }
    val sprite = remember {
        BangumiIconSprite(graph.dataLoader, graph.dataCacheDir, graph.okHttpClient)
    }

    // 把深链的 c/z 反映到相机（无动画 — 与 iOS resolveInitialState 相同）。
    // pendingDeepLink 的**清除不在这里做** —— bangumiId/pid 要等数据到达后
    // 由 RootScreen 侧落地再清除（冷启动的竞态对策）。
    var consumedCameraLink by remember { mutableStateOf<MapDeepLink?>(null) }
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        if (link === consumedCameraLink) return@LaunchedEffect
        consumedCameraLink = link
        link.center?.let { center ->
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(center.lat, center.lng),
                    (link.zoom ?: 15.0).toFloat(),
                )
            )
        }
        onDeepLinkConsumed()
    }

    // MapStyleOptions 没有 equals，每次 new 都会让 maps-compose 重新调 setMapStyle。
    // sheet 动画期间 contentBottomPadding 会**每帧**触发重组，深色模式下曾经每帧
    // 都跑一遍 raw 资源解析＋整张地图重设样式。remember 同一实例。
    val darkStyle = remember(context) { MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark) }
    val styleOptions = if (isDark && mapType != "hybrid") darkStyle else null
    val properties = remember(isMyLocationEnabled, mapType, styleOptions) {
        MapProperties(
            isBuildingEnabled = false,
            isIndoorEnabled = false,
            isMyLocationEnabled = isMyLocationEnabled,
            mapType = if (mapType == "hybrid") {
                MapType.HYBRID
            } else {
                MapType.NORMAL
            },
            mapStyleOptions = styleOptions,
        )
    }
    val uiSettings = remember {
        MapUiSettings(
            // 内建指南针固定在左上、会压住状态栏，所以不用，改用右侧控件柱的自绘按钮替代（与 iOS 同构）。
            compassEnabled = false,
            tiltGesturesEnabled = false,
            rotationGesturesEnabled = true,
            mapToolbarEnabled = false,
            zoomControlsEnabled = false,
            indoorLevelPickerEnabled = false,
            myLocationButtonEnabled = false, // 使用自绘的定位钮
        )
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier.onSizeChanged { mapWidthPx = it.width; mapHeightPx = it.height },
    ) {
    GoogleMap(
        modifier = androidx.compose.ui.Modifier.matchParentSize(),
        cameraPositionState = cameraPositionState,
        // 相当于 setContentPadding：newLatLngZoom / newLatLngBounds 会朝 padded 视口的中心靠。
        contentPadding = obstruction().current,
        properties = properties,
        uiSettings = uiSettings,
    ) {
        MapEffect(Unit) { map ->
            // POI 分支的命中测试在下面 tryHitDot 就位之后才挂（见 setOnPoiClickListener 那处）。

            val iconFactory = MarkerIconFactory(density)
            // 标注内的图片最大不过剧照卡的本体宽（80dp）。超过该分辨率的抽样解码。
            val imageCache = MarkerImageCache(
                graph.okHttpClient, scope,
                targetMaxSidePx = ceil(80f * density).toInt(),
            )
            val controller = MarkerFieldController(map, iconFactory, imageCache, sprite, scope, engineDispatcher)
            val overlay = WorkRegionOverlayRenderer(map, density)
            var pinMarker: Marker? = null


            // 主题色每个作品只解析一次（避免每次 idle × 600 点的 hex 解析）。
            // worker 也会读，所以用同步 map。数据世代变化时丢弃。
            val colorCache = ConcurrentHashMap<Int, Color>()
            fun colorFor(bangumiId: Int) = colorCache.getOrPut(bangumiId) {
                ColorUtilities.themeColor(store.bangumi(bangumiId)?.colorHex)
            }
            // padding 读取口一起接上:setPadding 平移底图但不改 CameraPosition,
            // 圆点层必须在绘制阶段读它才能跟上 sheet 弹簧(见 PointDotOverlayState.obstructionProvider)。
            dotState.attach(map) { obstructionState.value() }

            // 相机每动一次就让圆点层的绘制阶段重跑。只写一个计数,不传数值 ——
            // 数值一律在绘制阶段直接向 GMS 要,免得拿到经主线程回调转手的、可能落后一帧的副本。
            scope.launch {
                snapshotFlow { cameraPositionState.position }
                    .collect { dotState.onCameraChanged() }
            }

            /** 气球位图（worker 线程；MarkerIconFactory 的绘制是纯 android.graphics）。 */
            fun balloonSprite(point: ScenePoint): BalloonSprite {
                val rendered = iconFactory.balloonRendered(
                    MarkerIconFactory.IconSpec.Balloon(
                        colorFor(point.bangumiId),
                        point.thumbnailUrl?.let(imageCache::cached),
                        point.thumbnailUrl,
                    )
                )
                return BalloonSprite(
                    worldX = cn.anitabi.map.map.engine.MapProjection.worldX(point.lng),
                    worldY = cn.anitabi.map.map.engine.MapProjection.worldY(point.lat),
                    bitmap = rendered.bitmap,
                    anchorU = rendered.anchorU,
                    anchorV = rendered.anchorV,
                )
            }

            /** 外圈/内圈两个 ARGB 打包成一个分桶 key（近白主题色要在内侧补暗边）。 */
            fun dotColors(bangumiId: Int): Long {
                val theme = colorFor(bangumiId)
                return if (ColorUtilities.needsDarkInnerOutline(theme)) {
                    DotField.packColors(
                        outerArgb = ColorUtilities.mixed(theme, Color.Black, 56f / 255f).toArgb(),
                        innerArgb = theme.toArgb(),
                    )
                } else {
                    DotField.packColors(theme.toArgb(), theme.toArgb())
                }
            }

            var loadedGeneration = -1

            // 从地图上选点时：**只在**会被弹出的详情卡挡住／太贴近卡上缘时，
            // 做纯垂直平移（zoom 不变、x 不变）挪到未遮挡区的中央。其余情况不动
            // （iOS selectPointFromMap → schedulePlacement(.revealed/.centered)、MapCanvas L677-735）。
            // 单一 job:360ms 内连点两个 marker 时,旧的平移任务必须让位,否则两个 scrollBy 打架。
            var placementJob: Job? = null
            controller.onPointTap = { id ->
                onPointTapState.value(id)
                val point = store.point(id)
                if (point != null) {
                    placementJob?.cancel()
                    placementJob = scope.launch {
                        // 等卡片**最终**遮住多少定下来再判定。判据是 current == target
                        // (animateDpAsState 收敛时精确相等),不是「48ms 内没有新值」——
                        // 后者在弹簧尚未起步时就会到期,于是拿着旧档位的 padding 判
                        // 「不需要避让」(真机反馈:完全不上移,点被卡片盖住)。
                        val before = obstructionState.value().target
                        // 目标会不会变,一两帧就见分晓(tap → 重组 → RootScreen 的
                        // LaunchedEffect(topKey) 起 partialExpand)。150ms 是宽松上限;
                        // sheet 本来就不动的情形(已在同一档)靠它快速放行,不呆滞。
                        val moving = withTimeoutOrNull(150) {
                            snapshotFlow { obstructionState.value().target }.first { it != before }
                        }
                        if (moving != null) {
                            withTimeoutOrNull(900) {
                                snapshotFlow { obstructionState.value().settled }.first { it }
                            }
                        }
                        val screen = map.projection.toScreenLocation(LatLng(point.lat, point.lng))
                        val padding = obstructionState.value().current
                        val shift = SheetRevealPolicy.shiftFor(
                            pointScreenX = screen.x.toFloat(),
                            pointScreenY = screen.y.toFloat(),
                            viewportWidthPx = mapWidthPx,
                            viewportHeightPx = mapHeightPx,
                            obstructionLeftPx =
                                padding.calculateLeftPadding(LayoutDirection.Ltr).value * density,
                            obstructionBottomPx =
                                padding.calculateBottomPadding().value * density,
                            clearancePx = (SheetRevealPolicy.CLEARANCE_DP * density).toFloat(),
                        )
                        if (shift != null) {
                            map.animateCamera(
                                CameraUpdateFactory.scrollBy(shift.dx, shift.dy),
                                300,
                                null,
                            )
                        }
                    }
                }
            }
            controller.onWorkTap = { id -> onWorkTapState.value(id) }
            controller.onOtherMarkerTap = { onMapTapState.value() }

            /**
             * 圆点不是 Marker，GMS 不会替我们派发点击 —— 自己在这里补一次命中测试。
             * 判定的对象是**已经发布出去的场景**（`dotState.scene`），所以「看得见」与
             * 「点得中」不可能对不上。命中就走与点 Marker 完全相同的那条路
             * （controller.onPointTap），免得「揭示/让位」的相机微调逻辑出现两份。
             */
            fun tryHitDot(latLng: LatLng): Boolean {
                val hit = MapSceneHitTest.at(latLng.latitude, latLng.longitude, dotState.scene)
                    ?: return false
                controller.onPointTap?.invoke(hit.id)
                return true
            }

            // 空实现会把 POI 上的点击**整个吞掉**（spot 常常正好压在车站、店铺的 POI 图标正上方 →
            // 偏出几 px 就毫无反应）。改前靠「marker 命中优先」绕开这个坑,圆点不再是 marker 之后,
            // 这里也必须先跑一次命中测试,否则压在车站上的圆点会变成完全点不中（真机反馈批次 7）。
            map.setOnPoiClickListener { poi ->
                if (!tryHitDot(poi.latLng)) onMapTapState.value()
            }

            // 地图点击：圆点 → 打开地标；暗幕外侧 → 退出作品模式；其余 → 取消选中。
            map.setOnMapClickListener { latLng ->
                if (tryHitDot(latLng)) return@setOnMapClickListener
                val hull = overlay.hull
                if (focusedState.value != null && hull.isNotEmpty() &&
                    !WorkRegionGeometry.polygonContains(hull, LatLon(latLng.latitude, latLng.longitude))
                ) {
                    onDimExitState.value()
                } else {
                    onMapTapState.value()
                }
            }

            map.setOnMapLongClickListener { latLng ->
                onLongPressState.value(LatLon(latLng.latitude, latLng.longitude))
            }

            sprite.onReady = { controller.refreshWorkIcons(::colorFor) }
            scope.launch { sprite.prepare() }

            // 方位向上抛（仅在 Float 值变化时发射。用于指南针钮的旋转、显隐控制）。
            scope.launch {
                snapshotFlow { cameraPositionState.position.bearing }
                    .collect { onBearingState.value(it) }
            }

            // 落针 marker 跟随 router 的 droppedPin（下落动画为自绘的替代实现）。
            scope.launch {
                snapshotFlow { pinState.value }.collect { pin ->
                    pinMarker?.remove()
                    pinMarker = null
                    if (pin == null) return@collect
                    val marker = map.addMarker(
                        MarkerOptions().position(LatLng(pin.lat, pin.lng)).zIndex(4f)
                    ) ?: return@collect
                    // 不设 tag 就匹配不到 dispatcher 里的任何分支，**点击被默默吞掉**（真机反馈批次 7）。
                    marker.tag = "pin"
                    pinMarker = marker
                    val bounds = map.projection.visibleRegion.latLngBounds
                    val lift = (bounds.northeast.latitude - bounds.southwest.latitude) * 0.06
                    ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = 280
                        interpolator = DecelerateInterpolator(2f)
                        addUpdateListener { anim ->
                            val t = anim.animatedValue as Float
                            marker.position = LatLng(pin.lat + lift * t, pin.lng)
                        }
                        start()
                    }
                }
            }

            data class SceneKey(
                val position: CameraPosition,
                val generation: Int,
                val selected: String?,
                val focused: Int?,
                val workIds: Set<Int>,
                val photoLayer: Boolean,
                val visitFilter: PointVisitFilter,
                val visitedGeneration: Int,
            )

            // **一条循环、一个场景。** 此前标注与圆点各有一条循环、各自决定要画什么,
            // 圆点靠向 MarkerFieldController 打听「哪些点已经有 Marker」来避让 ——
            // 两条循环的时序没有任何约束,真机上于是出现「气球出现了圆点还在」
            // 「气球被邻近的圆点盖住」。现在一个点在一帧里只有一种形态,由 engine.scene 决定。
            var balloonFetchedFor: String? = null
            scope.launch {
                snapshotFlow {
                    SceneKey(
                        cameraPositionState.position,
                        store.dataGeneration,
                        selectedState.value,
                        focusedState.value,
                        workIdsState.value,
                        photoLayerState.value,
                        visitFilterState.value,
                        // 「全部」模式下打卡不改变场景,不让它触发一帧重算。
                        if (visitFilterState.value == PointVisitFilter.All) 0 else visitedGenerationState.value,
                    )
                }
                    // **sample 而不是 debounce**。debounce 每次发射都会重置窗口,而手势中
                    // cameraPositionState.position 每帧都在变 —— 于是连续手势期间它一次都不发,
                    // 整个场景冻结、松手后才哗地补上。sample 则是手势中每 48ms 出一帧。
                    // 48 而不是 120:甩动时相机 120ms 能跑过一整屏,圆点跟不上就会在前缘露出空白。
                    .sample(48)
                    .collect { key ->
                        if (key.generation == 0) return@collect

                        // 数据集只在世代变化时重建（避免每次 idle 的 O(5 万) 扫描）。
                        if (loadedGeneration != key.generation) {
                            colorCache.clear()
                            withContext(engineDispatcher) {
                                engine.load(MapDataset.from(store, key.generation, nameLocale))
                            }
                            loadedGeneration = key.generation
                        }

                        val bounds = map.projection.visibleRegion.latLngBounds
                        val latDelta = bounds.northeast.latitude - bounds.southwest.latitude
                        val lngDelta = bounds.northeast.longitude - bounds.southwest.longitude
                        val zoom = key.position.zoom.toDouble()

                        onCameraIdleState.value(
                            MapIdleState(
                                centerLat = bounds.center.latitude,
                                centerLng = bounds.center.longitude,
                                zoom = zoom,
                                viewportLatDelta = latDelta,
                                viewportLngDelta = lngDelta,
                            )
                        )

                        // 场景计算之后，紧接着**在同一个 worker 上**把需要的图预先画好。
                        // main 侧的 apply 就不再绘制，只做 BitmapDescriptor 化和 addMarker/setIcon。
                        val (scene, framePlan, sprite) = withContext(engineDispatcher) {
                            Trace.beginSection("MapEngine.scene+prewarm")
                            try {
                                val scene = engine.scene(
                                    MapEngineRequest(
                                        viewport = MapViewport.around(
                                            centerLat = bounds.center.latitude,
                                            centerLng = bounds.center.longitude,
                                            latDelta = latDelta,
                                            lngDelta = lngDelta,
                                            scale = 1.4,
                                        ),
                                        zoom = zoom,
                                        focusedWorkId = key.focused,
                                        selectedWorkIds = key.workIds,
                                        isPhotoLayerVisible = key.photoLayer,
                                        selectedPointId = key.selected,
                                        visitFilter = key.visitFilter,
                                        visitedPointIds = visitedIdsState.value,
                                        dotViewport = MapViewport.around(
                                            centerLat = bounds.center.latitude,
                                            centerLng = bounds.center.longitude,
                                            latDelta = latDelta,
                                            lngDelta = lngDelta,
                                            scale = 1.6,
                                        ),
                                    ),
                                    dotColors = ::dotColors,
                                    minHitRadiusDp = MapSceneHitTest.MIN_HIT_RADIUS_DP,
                                )
                                // 绘制计划在同一 worker 上算一次:prewarm 用它,apply 也复用它。
                                val framePlan = controller.plan(scene, ::colorFor)
                                iconFactory.prewarm(framePlan.specs, isFrame = true)
                                // 气球位图也在 worker 上画好(缓存命中时零代价)。
                                Triple(scene, framePlan, scene.balloon?.let { balloonSprite(it) })
                            } finally { Trace.endSection() }
                        }
                        // 圆点先发布、标注后应用:两者在同一个主线程续体里落地,所以
                        // 「气球出现」与「圆点消失」必定同帧。
                        dotState.publish(scene, sprite)
                        controller.apply(scene, ::colorFor, framePlan)
                        // 气球缩略图没到就去取;到货后**仍是同一个选中点**才替换。
                        // 同一次选中只发一次请求:MarkerImageCache 不记失败,取不到的缩略图会让
                        // 手势中每 48ms 一帧都重新发一次 HTTP。换了选中点(或取消再选)才重试。
                        val balloonNow = scene.balloon
                        if (balloonNow == null) balloonFetchedFor = null
                        balloonNow?.let { balloon ->
                            val url = balloon.thumbnailUrl
                            if (url != null && imageCache.cached(url) == null && balloonFetchedFor != balloon.id) {
                                balloonFetchedFor = balloon.id
                                imageCache.load(url) { bitmap ->
                                    if (bitmap == null) return@load
                                    if (dotState.scene.balloon?.id != balloon.id) return@load
                                    scope.launch {
                                        val fresh = withContext(engineDispatcher) { balloonSprite(balloon) }
                                        if (dotState.scene.balloon?.id == balloon.id) {
                                            dotState.publishBalloon(fresh)
                                        }
                                    }
                                }
                            }
                        }

                        val focused = key.focused
                        val coords = scene.focusedWorkCoordinates
                        if (focused != null && coords != null) {
                            val hull = withContext(engineDispatcher) {
                                WorkRegionGeometry.hull(coords)
                            }
                            if (hull != null) {
                                overlay.apply(focused, hull, colorFor(focused))
                            } else {
                                overlay.clear()
                            }
                        } else {
                            // 除 focused==null（作品模式外）之外，coords==null（数据未载入）时也
                            // 不残留上一个作品的暗幕（针对第三态悬空的对策）。
                            overlay.clear()
                        }
                    }
            }

            // 进入作品模式时 fit 到该作品（剔除离群点 + 7% padding）。
            var lastFitWorkId: Int? = null
            scope.launch {
                snapshotFlow { focusedState.value to store.dataGeneration }
                    .collect { (workId, generation) ->
                        if (workId == null) {
                            lastFitWorkId = null
                            return@collect
                        }
                        if (generation == 0 || workId == lastFitWorkId) return@collect
                        val coords = store.pointsByBangumi[workId]?.map { it.coordinate }
                            ?: return@collect
                        lastFitWorkId = workId
                        // iOS refitWork（RootView L719-729）：**原样**容纳凸包的顶点
                        // （trimsOutliers=false）。必定收进与暗幕边框相同的范围。
                        val fit = withContext(Dispatchers.Default) {
                            WorkRegionGeometry.hull(coords)
                                ?.let(MapCameraPlanner::enclosingBounds)
                                ?: MapCameraPlanner.fitBounds(coords)
                        } ?: return@collect
                        animateToBounds(map, cameraPositionState.position, fit, mapWidthPx)
                    }
            }
        }
    }

        // 圆点层。放在 GoogleMap **之后** ＝ 画在地图之上；而 RootScreen 里的控件柱、
        // 骰子按钮、sheet 都是 AnitabiMap 的兄弟且排在它之后，所以仍然压在圆点之上。
        // Spacer + drawBehind 不装 pointerInput，触摸会照常穿透到下面的 MapView。
        PointDotOverlay(state = dotState, modifier = androidx.compose.ui.Modifier.matchParentSize())
    }

    // 来自 RootScreen 的一次性相机指令（用 seq 判重）。
    LaunchedEffect(command?.seq) {
        val cmd = command ?: return@LaunchedEffect
        when (cmd) {
            is MapCommand.FlyToPoint -> {
                val targetZoom = MapCameraPlanner.flyZoom(cmd.priority)
                val current = cameraPositionState.position
                val duration = MapCameraPlanner.flightDurationMs(
                    currentCenter = LatLon(current.target.latitude, current.target.longitude),
                    currentLngDelta = MapCameraPlanner.lngDelta(
                        current.zoom.toDouble(), (mapWidthPx / density).toDouble()
                    ),
                    targetCenter = LatLon(cmd.lat, cmd.lng),
                    targetLngDelta = MapCameraPlanner.lngDelta(
                        targetZoom, (mapWidthPx / density).toDouble()
                    ),
                )
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(cmd.lat, cmd.lng), targetZoom.toFloat()),
                    duration.toInt(),
                )
            }
            is MapCommand.FitBounds -> {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds(
                            LatLng(cmd.minLat, cmd.minLng),
                            LatLng(cmd.maxLat, cmd.maxLng),
                        ),
                        MapCameraPlanner.edgePaddingPx(mapWidthPx.coerceAtLeast(1)),
                    ),
                    800,
                )
            }

            is MapCommand.UserLocation -> {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(cmd.lat, cmd.lng), 15.5f),
                    800,
                )
            }

            is MapCommand.ResetBearing -> {
                val current = cameraPositionState.position
                cameraPositionState.animate(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(current).bearing(0f).build()
                    ),
                    400,
                )
            }
        }
    }
}

private fun animateToBounds(
    map: com.google.android.gms.maps.GoogleMap,
    current: CameraPosition,
    fit: MapCameraPlanner.Bounds,
    mapWidthPx: Int,
) {
    val bounds = map.projection.visibleRegion.latLngBounds
    val duration = MapCameraPlanner.flightDurationMs(
        currentCenter = LatLon(current.target.latitude, current.target.longitude),
        currentLngDelta = bounds.northeast.longitude - bounds.southwest.longitude,
        targetCenter = LatLon(fit.centerLat, fit.centerLng),
        targetLngDelta = fit.lngDelta,
    )
    map.animateCamera(
        CameraUpdateFactory.newLatLngBounds(
            LatLngBounds(
                LatLng(fit.minLat, fit.minLng),
                LatLng(fit.maxLat, fit.maxLng),
            ),
            MapCameraPlanner.edgePaddingPx(mapWidthPx.coerceAtLeast(1)),
        ),
        duration.toInt(),
        null,
    )
}

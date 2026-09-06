package cn.anitabi.map.ui.home

import cn.anitabi.map.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.CityTile
import cn.anitabi.map.data.DataLoadPhase
import cn.anitabi.map.data.SearchResult
import cn.anitabi.map.data.WorkBrowseTab
import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.DistanceFormatter
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.theme.ColorUtilities
import cn.anitabi.map.theme.LocalAnitabiPalette
import cn.anitabi.map.ui.components.workCategoryLabel
import cn.anitabi.map.theme.LocalNameLocale
import cn.anitabi.map.ui.sheet.sheetBottomInset
import cn.anitabi.map.ui.sheet.HomeBrowseSkeleton
import cn.anitabi.map.ui.sheet.LocalSheetActions
import coil3.compose.AsyncImage
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.CheckCircle

/**
 * 常驻 sheet 的根层(iOS HomeSheet + HomeBrowseView + SearchResultsView)。
 * 搜索采用 iOS 的解耦模型(HomeSheet.swift L33-37):
 * - isSearchActive(搜索态)与聚焦是两回事。聚焦 → 激活 + 升档;**失焦后搜索态仍持续**
 *   (键盘收起后还能继续读结果)。
 * - 退出只有「取消」按钮(激活时与 ⋯ 按钮互换)或选中结果时的 endSearch 两条路。
 * - 城市选择是特例:保留文本只收起键盘(iOS pickCity L245-249)。
 */
data class ViewportChip(val id: Int, val name: String, val count: Int)

@Composable
fun HomeSheet(
    store: AnitabiStore,
    location: LatLon?,
    locationAuthorized: Boolean,
    viewportChips: List<ViewportChip>,
    selectedWorkIds: Set<Int>,
    onToggleChip: (Int) -> Unit,
    onRequestLocation: () -> Unit,
    onSelectWork: (Int) -> Unit,
    onSelectPoint: (ScenePoint) -> Unit,
    onSelectCity: (CityTile) -> Unit,
    onOpenAbout: () -> Unit,
    onReplayWelcome: () -> Unit,
    onOpenWeb: () -> Unit,
    /** 巡礼记录的入口（本仓库自有功能）:已完成地标数 / 作品数;为 0 时不显示该段。 */
    visitedSummary: Pair<Int, Int>,
    onOpenPilgrimageLog: () -> Unit,
    onRetryLoad: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalAnitabiPalette.current
    val focusManager = LocalFocusManager.current
    val language = uiLanguage()
    var query by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 作品浏览 tab 由 Browse 与搜索空输入共享(iOS 用 @AppStorage home.browseTab 共享)
    var browseTab by rememberSaveable { mutableStateOf(WorkBrowseTab.Popular.name) }

    fun endSearch() {
        query = ""
        isSearchActive = false
        focusManager.clearFocus()
    }

    // 搜索态的系统返回 = 与「取消」同义(真机反馈 O5)。这里的组合比 NavDisplay 的 BackHandler
    // 更深,enabled 时由这里优先吃掉返回。Home 不在栈顶时本组合根本不存在(只组合栈顶层)。
    BackHandler(enabled = isSearchActive) { endSearch() }

    Column(modifier = modifier.fillMaxSize().padding(top = 10.dp)) {
        // 搜索行(固定) + ⋯ / 取消
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 行高下限 44 + 下方 10。初始档只露这一行:SheetPeek.homeBar(把手 12 + 顶 10 + 44 + 10)
            // 与此处的总高对齐,改一处要改另一处。
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp)
                .heightIn(min = 44.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .background(palette.surface, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                val sheetActions = LocalSheetActions.current
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = palette.ink,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    cursorBrush = SolidColor(palette.ink),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focus ->
                            // 聚焦 → 搜索态 + 展开。失焦时**不解除**搜索态(iOS L110-114)。
                            if (focus.isFocused) {
                                isSearchActive = true
                                sheetActions?.expand()
                            }
                        },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    // placeholder 也是要读的文字,按正文档处理。
                                    stringResource(R.string.search_placeholder),
                                    color = palette.inkTertiary,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(palette.softFill)
                            .clickable { query = "" }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.clear),
                            color = palette.inkSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isSearchActive) {
                // 搜索态下把 ⋯ 换成「取消」(iOS L148-155)
                Text(
                    stringResource(R.string.cancel),
                    color = palette.ink,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { endSearch() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            } else {
                Box {
                    // 正圆 34dp + 图标居中(排除 Text 自撑导致的椭圆/基线偏心 — 真机反馈)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(palette.softFill)
                                .clickable(
                                    role = Role.Button,
                                ) { menuOpen = true },
                        ) {
                            Icon(
                                Icons.Outlined.MoreHoriz,
                                contentDescription = stringResource(R.string.more),
                                tint = palette.inkSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    AnitabiDropdownMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        AnitabiMenuItem(stringResource(R.string.watch_welcome_screen_again)) {
                            menuOpen = false
                            onReplayWelcome()
                        }
                        AnitabiMenuItem(stringResource(R.string.open_in_web_version)) {
                            menuOpen = false
                            onOpenWeb()
                        }
                        AnitabiMenuItem(stringResource(R.string.about)) {
                            menuOpen = false
                            onOpenAbout()
                        }
                    }
                }
            }
        }

        // 视口作品 chips(搜索态下不显示 — iOS L57)
        if (viewportChips.isNotEmpty() && !isSearchActive) {
            Spacer(Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                // chips 每次相机 idle 都会重算,身份不断变化 —— 需要稳定 key。
                items(viewportChips.size, key = { viewportChips[it].id }) { i ->
                    val chip = viewportChips[i]
                    val selected = selectedWorkIds.contains(chip.id)
                    Text(
                        "${chip.name} ${chip.count}",
                        color = if (selected) palette.onAccent else palette.ink,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 220.dp)
                            .clip(CircleShape)
                            .background(if (selected) palette.accentFill else palette.raised)
                            .clickable { onToggleChip(chip.id) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (store.isEmpty) {
            // 空数据分两种:加载失败给出可见的错误+重试(否则断网首启会永远转骨架屏);其余转骨架屏。
            if (store.phase is DataLoadPhase.Failed) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                ) {
                    Text(
                        stringResource(R.string.data_load_failed),
                        color = palette.inkSecondary, fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.retry),
                        color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(palette.surface)
                            .clickable(onClick = onRetryLoad)
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                    )
                }
            } else {
                HomeBrowseSkeleton()
            }
        } else if (isSearchActive) {
            SearchResults(
                store = store,
                query = query,
                location = location,
                browseTab = browseTab,
                onBrowseTab = { browseTab = it },
                onSelectWork = { id ->
                    endSearch()
                    onSelectWork(id)
                },
                onSelectPoint = { point ->
                    endSearch()
                    onSelectPoint(point)
                },
                onSelectCity = { city ->
                    // 城市是特例:保留文本只收起键盘(搜索态维持,镜头在背后飞过去)
                    query = cityDisplayName(city, language)
                    focusManager.clearFocus()
                    onSelectCity(city)
                },
            )
        } else {
            BrowseContent(
                store = store,
                browseTab = browseTab,
                onBrowseTab = { browseTab = it },
                onSelectWork = onSelectWork,
                onSelectCity = onSelectCity,
                locationAuthorized = locationAuthorized,
                onRequestLocation = onRequestLocation,
                onSelectPoint = onSelectPoint,
                visitedSummary = visitedSummary,
                onOpenPilgrimageLog = onOpenPilgrimageLog,
            )
        }
    }
}

// MARK: - 搜索结果(iOS SearchResultsView:空输入是热门城市+作品浏览,有输入是三节分卡)

/**
 * 一次搜索的全部产出。三段合成一个状态，因为它们由同一趟扫描算出 ——
 * 分成两个 `produceState` 会让同一次输入触发两次重组。
 */
private class SearchOutcome(
    val cities: List<CityTile>,
    val works: List<SearchResult.Bangumi>,
    val points: List<SearchResult.Point>,
) {
    companion object {
        val EMPTY = SearchOutcome(emptyList(), emptyList(), emptyList())
    }
}

/** 防抖。低于它人眼看不出结果延迟，高于它连打时会有停顿感。 */
private const val SEARCH_DEBOUNCE_MS = 120L

@Composable
private fun SearchResults(
    store: AnitabiStore,
    query: String,
    location: LatLon?,
    browseTab: String,
    onBrowseTab: (String) -> Unit,
    onSelectWork: (Int) -> Unit,
    onSelectPoint: (ScenePoint) -> Unit,
    onSelectCity: (CityTile) -> Unit,
) {
    val palette = LocalAnitabiPalette.current
    val trimmed = query.trim()

    if (trimmed.isEmpty()) {
        // 空输入:热门城市 + 作品浏览(iOS SearchResultsView L38-43)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + sheetBottomInset()),
        ) {
            cityGridSection(store, onSelectCity)
            workBrowseSection(store, browseTab, onBrowseTab, onSelectWork)
        }
        return
    }

    // produceState + Default 线程:全库(~5 万条)扫描+距离排序不再发生在
    // 主线程的组合期/IME 打字循环内。value 在 key 变化间保留,打字不闪空态。
    // location 取「查询发起时」的快照(rememberUpdatedState)而**不进 key**:
    // 否则后台定位每 50m 更新会静默重排用户正在读的结果。
    val locationState = rememberUpdatedState(location)
    val outcome by produceState(SearchOutcome.EMPTY, trimmed, store.dataGeneration) {
        // produceState 在 key 变化时会取消上一个协程 —— 所以这句 delay 本身就是防抖,
        // 不需要 Flow.debounce。120ms 是在真机上调过的数。加防抖是因为打分版去掉了提前 break,
        // 每次击键都变成一次完整扫描。
        delay(SEARCH_DEBOUNCE_MS)
        val job = coroutineContext[Job]
        value = withContext(Dispatchers.Default) {
            // 城市与作品/地标一趟算完:两个 produceState 会各自触发一次重组。
            val results = store.search(
                trimmed,
                near = locationState.value,
                isActive = { job?.isActive != false },
            )
            SearchOutcome(
                cities = store.matchingCities(trimmed),
                works = results.filterIsInstance<SearchResult.Bangumi>(),
                points = results.filterIsInstance<SearchResult.Point>(),
            )
        }
    }
    val cities = outcome.cities
    val works = outcome.works
    val points = outcome.points

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + sheetBottomInset()),
    ) {
        // 三段随查询出现/消失,行下标会漂移;不给稳定 key 的话滚动位置与行状态会绑到
        // 错误的内容上(produceState 刻意保留旧结果,正好把这个窗口拉到最大)。
        if (cities.isNotEmpty()) {
            item(key = "h:city") { SectionHeader(stringResource(R.string.city)) }
            items(cities.size, key = { "city:${cities[it].id}" }) { i ->
                val city = cities[i]
                RowCard(onClick = { onSelectCity(city) }) {
                    Text(cityDisplayName(city, uiLanguage()), color = palette.ink, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        pluralStringResource(R.plurals.n_spots, city.pointCount, city.pointCount),
                        color = palette.inkTertiary, fontSize = 13.sp,
                    )
                }
            }
        }
        if (works.isNotEmpty()) {
            item(key = "h:work") { SectionHeader(stringResource(R.string.work)) }
            items(works.size, key = { "work:${works[it].bangumi.id}" }) { i ->
                WorkRow(works[i].bangumi, onClick = { onSelectWork(works[i].bangumi.id) })
            }
        }
        if (points.isNotEmpty()) {
            item(key = "h:spots") { SectionHeader(stringResource(R.string.spots)) }
            items(points.size, key = { "spot:${points[it].point.id}" }) { i ->
                val hit = points[i]
                RowCard(onClick = { onSelectPoint(hit.point) }) {
                    PointThumb(hit.point)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            hit.point.displayName(LocalNameLocale.current),
                            color = palette.ink, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            store.name(hit.point.bangumiId, LocalNameLocale.current),
                            color = palette.inkTertiary, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    hit.distanceMeters?.let {
                        Text(
                            DistanceFormatter.string(it),
                            color = palette.inkTertiary, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        if (cities.isEmpty() && works.isEmpty() && points.isEmpty()) {
            item(key = "empty") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                ) {
                    Text("🔍", fontSize = 28.sp, modifier = Modifier.clearAndSetSemantics { })
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.no_results), color = palette.inkTertiary, fontSize = 14.sp)
                }
            }
        }
    }
}

// MARK: - Browse（最近查看 → 附近 → 作品浏览 → 热门城市 → CC 脚注）

@Composable
private fun BrowseContent(
    store: AnitabiStore,
    browseTab: String,
    onBrowseTab: (String) -> Unit,
    onSelectWork: (Int) -> Unit,
    onSelectCity: (CityTile) -> Unit,
    locationAuthorized: Boolean,
    onRequestLocation: () -> Unit,
    onSelectPoint: (ScenePoint) -> Unit,
    visitedSummary: Pair<Int, Int>,
    onOpenPilgrimageLog: () -> Unit,
) {
    val palette = LocalAnitabiPalette.current
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + sheetBottomInset()),
    ) {
        // 最近查看
        store.lastVisitedBangumi?.let { last ->
            item { SectionHeader(stringResource(R.string.recently_viewed)) }
            item { WorkRow(last, onClick = { onSelectWork(last.id) }) }
        }

        // 巡礼记录(本仓库自有):有记录才出现,一张卡进列表页。
        val (visitedCount, visitedWorks) = visitedSummary
        if (visitedCount > 0) {
            item(key = "h:log") { SectionHeader(stringResource(R.string.pilgrimage_log)) }
            item(key = "log") {
                RowCard(onClick = onOpenPilgrimageLog) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = palette.visitedIcon,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.pilgrimage_log_summary, visitedCount, visitedWorks),
                        color = palette.ink, fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text("›", color = palette.inkFaint, fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics { })
                }
            }
        }

        // 附近的圣地
        if (locationAuthorized && store.nearby.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.nearby_sanctuaries)) }
            val shown = store.nearby.take(3)
            items(shown.size, key = { "nearby:${shown[it].id}" }) { i ->
                val item = shown[i]
                RowCard(onClick = { onSelectPoint(item.point) }) {
                    PointThumb(item.point)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.point.displayName(LocalNameLocale.current),
                            color = palette.ink, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            store.name(item.point.bangumiId, LocalNameLocale.current),
                            color = palette.inkTertiary, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        DistanceFormatter.string(item.distanceMeters),
                        color = palette.inkTertiary, fontSize = 12.sp,
                    )
                }
            }
        } else if (!locationAuthorized) {
            item { SectionHeader(stringResource(R.string.nearby_sanctuaries)) }
            item {
                RowCard(onClick = onRequestLocation) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.enable_location_prompt),
                            color = palette.ink, fontSize = 14.sp,
                        )
                        Text(
                            stringResource(R.string.location_privacy_note),
                            color = palette.inkTertiary, fontSize = 12.sp,
                        )
                    }
                    Text("›", color = palette.inkFaint, fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics { })
                }
            }
        }

        workBrowseSection(store, browseTab, onBrowseTab, onSelectWork)
        cityGridSection(store, onSelectCity)

        item {
            Text(
                stringResource(R.string.cc_by_nc_sa_4_0_screenshots),
                color = palette.inkTertiary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp).fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: - 共用节(Browse 与搜索空输入共享 — iOS CityGridSection / WorkBrowseSection)

private fun LazyListScope.workBrowseSection(
    store: AnitabiStore,
    browseTab: String,
    onBrowseTab: (String) -> Unit,
    onSelectWork: (Int) -> Unit,
) {
    item { SectionHeader(stringResource(R.string.browse_works)) }
    item {
        val palette = LocalAnitabiPalette.current
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.track, CircleShape)
                .padding(3.dp),
        ) {
            for (candidate in WorkBrowseTab.entries) {
                val selected = browseTab == candidate.name
                Text(
                    text = when (candidate) {
                        WorkBrowseTab.RecentlyUpdated -> stringResource(R.string.updated)
                        WorkBrowseTab.NewlyAdded -> stringResource(R.string.new_label)
                        WorkBrowseTab.Popular -> stringResource(R.string.popular)
                    },
                    color = if (selected) palette.ink else palette.inkSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(
                            if (selected) palette.thumb
                            else Color.Transparent
                        )
                        .clickable { onBrowseTab(candidate.name) }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
    val tabWorks = store.works(WorkBrowseTab.valueOf(browseTab))
    items(tabWorks.size, key = { "bw:${tabWorks[it].id}" }) { i ->
        WorkRow(tabWorks[i], onClick = { onSelectWork(tabWorks[i].id) })
    }
}

private fun LazyListScope.cityGridSection(
    store: AnitabiStore,
    onSelectCity: (CityTile) -> Unit,
) {
    if (store.cities.isEmpty()) return
    item { SectionHeader(stringResource(R.string.popular_cities)) }
    item {
        val palette = LocalAnitabiPalette.current
        val cities = store.cities
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cities.chunked(3).forEach { rowCities ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowCities.forEach { city ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.surface)
                                .clickable { onSelectCity(city) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(cityDisplayName(city, uiLanguage()), color = palette.ink, fontSize = 14.sp)
                            Text(
                                "${city.pointCount}",
                                color = palette.inkTertiary, fontSize = 11.sp,
                            )
                        }
                    }
                    repeat(3 - rowCities.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// MARK: - 统一菜单(从默认的 M3 风格向 palette 靠拢)

@Composable
fun AnitabiDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalAnitabiPalette.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = palette.opaqueSheetSurface,
        content = content,
    )
}

@Composable
fun AnitabiMenuItem(
    label: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val palette = LocalAnitabiPalette.current
    DropdownMenuItem(
        text = { Text(label, color = palette.ink, fontSize = 14.sp) },
        leadingIcon = leading,
        trailingIcon = trailing,
        onClick = onClick,
    )
}

// MARK: - 共用部件

@Composable
fun SectionHeader(title: String) {
    val palette = LocalAnitabiPalette.current
    Text(
        title,
        color = palette.inkSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
fun RowCard(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val palette = LocalAnitabiPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun WorkRow(work: BangumiLite, onClick: () -> Unit) {
    val palette = LocalAnitabiPalette.current
    RowCard(onClick = onClick) {
        AsyncImage(
            model = work.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 34.dp, height = 46.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ColorUtilities.themeColor(work.colorHex).copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                work.displayName(LocalNameLocale.current), color = palette.ink, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    workCategoryLabel(work.cat),
                    pluralStringResource(R.plurals.n_spots, work.points.size, work.points.size),
                ).joinToString(" · "),
                color = palette.inkTertiary, fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", color = palette.inkFaint, fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
fun PointThumb(point: ScenePoint) {
    val palette = LocalAnitabiPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 56.dp, height = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.mediaPlaceholder),
    ) {
        val url = point.thumbnailUrl
        if (url == null) {
            // 「无图像」= 显示图标。以便与加载中(纯占位)区分开(真机反馈)。
            Icon(
                Icons.Outlined.HideImage,
                contentDescription = null,
                tint = palette.inkFaint,
                modifier = Modifier.size(16.dp),
            )
        } else {
            var failed by remember(url) { mutableStateOf(false) }
            if (failed) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = palette.inkFaint,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 城市显示名:zh 界面用简体名,其余用日文原名(数据的原始名)。 */
private fun cityDisplayName(city: CityTile, language: String): String =
    if (language == "zh") city.nameCn else city.name

/**
 * 界面语言。用 Compose 提供的 locale 使语言成为正式的重组输入 ——
 * `Locale.getDefault()` 不是可观察状态,用户改语言时不会触发重组(lint NonObservableLocale)。
 */
@Composable
private fun uiLanguage(): String = LocalLocale.current.platformLocale.language

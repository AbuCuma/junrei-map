package cn.anitabi.map.ui.work

import cn.anitabi.map.R
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.model.AnitabiImage
import cn.anitabi.map.data.model.NameLocale
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.data.model.WorkGroupingMode
import cn.anitabi.map.data.model.nilIfBlank
import cn.anitabi.map.support.AnitabiWebLink
import cn.anitabi.map.support.ExternalLinks
import cn.anitabi.map.ui.sheet.sheetBottomInset
import cn.anitabi.map.theme.ColorUtilities
import cn.anitabi.map.theme.LocalAnitabiPalette
import cn.anitabi.map.ui.components.workCategoryLabel
import cn.anitabi.map.theme.LocalNameLocale
import cn.anitabi.map.ui.home.PointThumb
import cn.anitabi.map.ui.home.RowCard
import coil3.compose.AsyncImage
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import cn.anitabi.map.ui.sheet.LocalSheetCollapsed
import androidx.compose.material.icons.filled.CheckCircle
import cn.anitabi.map.data.PilgrimageLog

/**
 * 作品卡片(对照 iOS WorkCardSheet)。
 * - 分组折叠:header 整行点按 + chevron 旋转(WorkPointList L96-124)。
 *   折叠状态**不持久化**(打开、分组切换、数据世代变化时全展开 — iOS L96)。
 * - 「全部展开/折叠」在分段控件的右侧(L354-368)。
 * - 外链胶囊:Bangumi / 维基百科 / 萌娘百科 / Google 圣地巡礼(L394-419,横向滚动 + ↗)。
 * - 分享在头部 ✕ 的左边(L250-264)。URL 用 canonical(不沿袭 iOS 硬编码 anitabi.cn 的 bug)。
 */
@Composable
fun WorkCardSheet(
    store: AnitabiStore,
    workId: Int,
    /** 巡礼记录:行尾打勾 + 副标题进度(本仓库自有功能,iOS 无)。在行内读它,只重组可见行。 */
    pilgrimageLog: PilgrimageLog,
    onClose: () -> Unit,
    onSelectPoint: (ScenePoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAnitabiPalette.current
    val context = LocalContext.current
    val work = store.bangumi(workId)
    var groupMode by rememberSaveable { mutableStateOf(WorkGroupingMode.Folder.name) }

    val nameLocale = LocalNameLocale.current
    val groups = remember(workId, groupMode, store.dataGeneration, nameLocale) {
        store.groupedPoints(workId, WorkGroupingMode.valueOf(groupMode), nameLocale)
    }
    // 折叠状态:作品、分组方式、数据世代变化时必定全展开(与 iOS 相同)
    var collapsedGroupIds by remember(workId, groupMode, store.dataGeneration) {
        mutableStateOf(setOf<String>())
    }
    val isFullyExpanded = collapsedGroupIds.isEmpty()
    // 两种模式各自都有兜底桶(无 fid/folderName → 一个 default: 组;无 ep → 一个 unassigned 组),
    // 所以按 `size > 1` 显示折叠键会让同一部作品在两种模式间来回切时按钮一闪一灭。
    // 折叠键只要有组就给(单组时等价于折叠那一组,与标题上的箭头同效);
    // 吸顶维持 `> 1` —— 唯一一个组头永久贴在顶部只是噪音。
    val showsGroupingControls = groups.isNotEmpty()
    val showsStickyHeaders = groups.size > 1

    val screenshotCount = store.screenshotCount(workId)
    // 用 dataGeneration 而不是 usersById.size:同尺寸换内容(刷新后昵称表被整体替换)
    // 时 size 不变,投稿者行会一直停在旧值。同文件其它 remember 用的也是 dataGeneration。
    val contributors = remember(workId, store.dataGeneration, store.usersById) {
        store.contributorSummary(workId)
    }

    Column(modifier = modifier.fillMaxSize().padding(top = 10.dp)) {
        // 身份条:标题+meta / 分享 / ✕(封面移到 infoCard — 对照 iOS header L208-267)
        // 迷你档(LocalSheetCollapsed)下单行省略,其余多行折行。
        val headerTitle = work?.displayName(LocalNameLocale.current) ?: "#$workId"
        val pointCount = store.pointsByBangumi[workId]?.size ?: 0
        // 已完成进度只数当前数据集里还存在的点(记录里的孤儿 id 不算)。≥1 才显示。
        val visitedCount = remember(workId, store.dataGeneration, pilgrimageLog.generation) {
            store.pointsByBangumi[workId]?.count { pilgrimageLog.isVisited(it.id) } ?: 0
        }
        val headerSubtitle = listOfNotNull(
            workCategoryLabel(work?.cat),
            pluralStringResource(R.plurals.n_spots, pointCount, pointCount),
            pluralStringResource(R.plurals.n_photos, screenshotCount, screenshotCount),
            if (visitedCount > 0) stringResource(R.string.visited_progress, visitedCount, pointCount) else null,
        ).joinToString(" · ")
        WorkHeaderRow(
            title = headerTitle, subtitle = headerSubtitle,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            onShare = {
                val url = AnitabiWebLink.canonical(bangumiId = workId)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(send, null))
            },
            onClose = onClose,
        )

        Spacer(Modifier.padding(top = 8.dp))

        // 点位列表(行级懒构建 + 折叠)。infoCard 与分组控件同 iOS 一样属于滚动内容。
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp + sheetBottomInset()),
        ) {
            // infoCard 英雄区(iOS WorkCardSheet.infoCard L297-330):
            // 44×60 主视觉图(bangumi 封面用 h360 的另一档 plan)+「© 原题」+「更新于 yyyy/M/d」。
            item(key = "info") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surface)
                        .padding(10.dp),
                ) {
                    AsyncImage(
                        model = AnitabiImage.url(work?.cover, plan = "h360"),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 44.dp, height = 60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ColorUtilities.themeColor(work?.colorHex).copy(alpha = 0.4f)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        work?.title.nilIfBlank()?.let { title ->
                            Text(
                                "© $title",
                                color = palette.inkSecondary, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        store.modifiedEpochMillis(workId)?.let { millis ->
                            val dateText = remember(millis) {
                                SimpleDateFormat("yyyy/M/d", Locale.ROOT)
                                    .format(Date(millis))
                            }
                            Text(
                                stringResource(R.string.updated_n, dateText),
                                color = palette.inkTertiary, fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            // 分组控件 + 全部展开/折叠(iOS groupingControlsRow)
            item(key = "controls") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .background(palette.track, CircleShape)
                            .padding(3.dp),
                    ) {
                        for ((mode, label) in listOf(
                            WorkGroupingMode.Folder to stringResource(R.string.by_group),
                            WorkGroupingMode.Episode to stringResource(R.string.by_episode),
                        )) {
                            val selected = groupMode == mode.name
                            Text(
                                label,
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
                                    .clickable { groupMode = mode.name }
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                    if (showsGroupingControls) {
                        Text(
                            stringResource(if (isFullyExpanded) R.string.collapse_all else R.string.expand_all),
                            color = palette.ink,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.raised)
                                .clickable {
                                    collapsedGroupIds = if (isFullyExpanded) {
                                        groups.map { it.id }.toSet()
                                    } else {
                                        emptySet()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            for (group in groups) {
                val collapsed = collapsedGroupIds.contains(group.id)
                // 分组头**吸顶**(stickyHeader)。单组的作品不吸顶(与 iOS gate 同旨 ——
                // 唯一一个组头永久贴在顶部只是噪音)。背景用 sheet 面色:行卡也
                // 内缩 16dp,侧沟、4dp 缝隙里露出的都是同色的面 → 视觉上没有断缝。
                val headerContent: @Composable () -> Unit = {
                    // header 整行点按折叠(iOS WorkPointGroupHeader)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // background 在 clip 之前是有意的:吸顶行需要一块**不圆角、满宽**的
                            // 不透明底(含上方那段行距),否则滚动内容会从缝里透出来。
                            .background(palette.opaqueSheetSurface)
                            // 行距放在 clickable **之外** —— 放在里面时按压高亮会连这段空白一起
                            // 亮起来(改前是上 8dp / 下 2dp,4:1 的不对称,看起来就是文字偏下)。
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                collapsedGroupIds = if (collapsed) {
                                    collapsedGroupIds - group.id
                                } else {
                                    collapsedGroupIds + group.id
                                }
                            }
                            // 高亮内部上下对称。总高 6+2+H+2 与改前的 8+H+2 相同 → 零布局位移。
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            if (group.isUnassigned) stringResource(R.string.ungrouped) else group.name,
                            color = palette.inkSecondary, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            pluralStringResource(R.plurals.n_spots, group.points.size, group.points.size),
                            color = palette.inkTertiary, fontSize = 11.sp,
                        )
                        val rotation by animateFloatAsState(
                            targetValue = if (collapsed) -90f else 0f, label = "chevron",
                        )
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(R.string.expand_or_collapse_all_groups),
                            tint = palette.inkTertiary,
                            modifier = Modifier.size(18.dp).rotate(rotation),
                        )
                    }
                }
                if (showsStickyHeaders) {
                    stickyHeader(key = "h:${group.id}") { headerContent() }
                } else {
                    item(key = "h:${group.id}") { headerContent() }
                }
                if (!collapsed) {
                    items(group.points.size, key = { "${group.id}:${group.points[it].id}" }) { i ->
                        val point = group.points[i]
                        RowCard(onClick = { onSelectPoint(point) }) {
                            PointThumb(point)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    point.displayName(LocalNameLocale.current),
                                    color = palette.ink, fontSize = 14.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    point.episodeBadge?.let {
                                        Text(it, color = palette.inkTertiary, fontSize = 11.sp)
                                    }
                                    // 出现时间(iOS WorkPointList L217 把 ep、时间、note 连在一起)
                                    point.timecodeText?.let {
                                        Text(it, color = palette.inkTertiary, fontSize = 11.sp)
                                    }
                                    if (point.needsEtiquetteWarning) {
                                        Text(
                                            stringResource(R.string.etiquette),
                                            color = palette.badgeWarnInk, fontSize = 10.sp,
                                            modifier = Modifier
                                                .background(palette.badgeWarnFill, CircleShape)
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            // 巡礼记录:已完成的行在尾部打绿勾;未完成不画任何东西(用户要求)。
                            if (pilgrimageLog.isVisited(point.id)) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = stringResource(R.string.visited),
                                    tint = palette.visitedIcon,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 外链胶囊(iOS externalLinks — 横向滚动 + ↗)
            item(key = "links") {
                val cn = work?.cn.nilIfBlank() ?: work?.displayName(NameLocale.Zh)
                val googleQuery = (work?.title.nilIfBlank() ?: cn ?: "") + " 聖地巡礼"
                val links = buildList {
                    add("Bangumi" to "https://bgm.tv/subject/$workId")
                    if (cn != null) {
                        add(
                            stringResource(R.string.wikipedia) to
                                "https://zh.wikipedia.org/w/index.php?search=" +
                                URLEncoder.encode(cn, "UTF-8")
                        )
                        add(
                            stringResource(R.string.moegirl) to
                                "https://zh.moegirl.org.cn/" +
                                URLEncoder.encode(cn, "UTF-8").replace("+", "%20")
                        )
                    }
                    add(
                        "Google" to "https://www.google.com/search?q=" +
                            URLEncoder.encode(googleQuery, "UTF-8")
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(links.size, key = { links[it].first }) { i ->
                        val (label, url) = links[i]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.raised)
                                .clickable { ExternalLinks.openInCustomTab(context, url) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(label, color = palette.ink, fontSize = 13.sp)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = null,
                                tint = palette.inkTertiary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }
            }

            // 贡献者
            contributors?.let { summary ->
                item(key = "contrib") {
                    Text(
                        stringResource(
                            R.string.contributors_thanks,
                            summary.top.joinToString("、"),
                            summary.total,
                        ),
                        color = palette.inkTertiary, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** 身份条：标题多行折行，分享/关闭按钮顶部对齐；迷你档下单行省略。 */
@Composable
private fun WorkHeaderRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onShare: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val palette = LocalAnitabiPalette.current
    // 迷你档:单行省略 + 按钮垂直居中,行高下限 44 + 底部 10 —— 与 SheetPeek.miniBar 的构成一致。
    val compact = LocalSheetCollapsed.current
    Row(
        verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
        modifier = if (compact) modifier.padding(bottom = 10.dp).heightIn(min = 44.dp) else modifier,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = palette.inkTertiary, fontSize = 12.sp,
                maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
        }
        CircleIconButton(Icons.Outlined.Share, contentDescription = stringResource(R.string.share), onClick = onShare)
        CloseButton(onClose)
    }
}

/** ✕ 按钮：用 Text 自撑会变成椭圆 + 按字形基线偏心（真机反馈）→ 与 CircleIconButton 同构的正圆。 */
@Composable
fun CloseButton(onClick: () -> Unit) {
    CircleIconButton(
        Icons.Rounded.Close,
        contentDescription = stringResource(R.string.close),
        onClick = onClick,
    )
}

/**
 * 头部的圆形图标按钮（分享等 — iOS CardCircleLabel）。
 * [containerColor] / [tint] 缺省为常规灰底墨色;开关类按钮（巡礼记录的 ✓）在「开」态换成自己的色。
 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    tint: Color? = null,
    stateDescription: String? = null,
) {
    val palette = LocalAnitabiPalette.current
    Box(
        contentAlignment = Alignment.Center,
        // 视觉 34dp 圆不变;minimumInteractiveComponentSize 把触摸目标扩到 48dp
        //(相邻按钮的间距已相应从 8dp 收到 0dp,视觉间隙基本不变)。
        modifier = modifier.minimumInteractiveComponentSize(),
    ) {
        // stateDescription 挂在**可点击的**这层:clickable 会把子孙语义合并成一个节点,
        // 状态只有在同一节点上 TalkBack 才会与「按钮 + 名字」连着读;挂在外层会成为另一个节点。
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(containerColor ?: palette.softFill)
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { if (stateDescription != null) this.stateDescription = stateDescription },
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint ?: palette.inkSecondary, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
fun ActionChip(label: String, onClick: () -> Unit) {
    val palette = LocalAnitabiPalette.current
    Text(
        label,
        color = palette.ink,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(palette.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * 动作排的等宽胶囊（iOS AnitabiCapsuleStyle：primary 只变颜色、尺寸不变 — HIG）。
 * 高度固定 38dp + Box(Center)：用 Text 自撑会按字形基线偏心（真机反馈）。
 * 传入 [icon] 时以图标代替文字置于几何中心（供「⋯」用）。
 */
@Composable
fun ActionCapsule(
    label: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalAnitabiPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // heightIn 而非 height:大字号(1.5-2×)时 13sp 标签会超过 38dp,硬高度会裁字。
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .background(if (primary) palette.accentFill else palette.raised)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 8.dp),
    ) {
        if (icon != null) {
            Icon(
                icon, contentDescription = label,
                tint = if (primary) palette.onAccent else palette.ink,
                modifier = Modifier.size(18.dp),
            )
        } else if (label != null) {
            Text(
                label,
                color = if (primary) palette.onAccent else palette.ink,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

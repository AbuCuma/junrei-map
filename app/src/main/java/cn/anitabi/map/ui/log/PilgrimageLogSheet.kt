package cn.anitabi.map.ui.log

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.anitabi.map.R
import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.PilgrimageLog
import cn.anitabi.map.data.PilgrimageLogGroups
import cn.anitabi.map.data.VisitedWorkGroup
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.theme.ColorUtilities
import cn.anitabi.map.theme.LocalAnitabiPalette
import cn.anitabi.map.theme.LocalNameLocale
import cn.anitabi.map.ui.home.PointThumb
import cn.anitabi.map.ui.home.RowCard
import cn.anitabi.map.ui.sheet.LocalSheetCollapsed
import cn.anitabi.map.ui.sheet.sheetBottomInset
import cn.anitabi.map.ui.work.CloseButton
import coil3.compose.AsyncImage

/**
 * 「巡礼记录」列表：已完成的地标按作品聚类（本仓库自有功能，iOS 无）。
 *
 * 分组是纯函数 [PilgrimageLogGroups.build]，按 `log.generation` 与 `store.dataGeneration` 重算。
 * 组头 → 作品卡；行 → 地标卡（与 Home 同一条 onSelectPoint 路径，会飞过去）。
 * 取消完成只在地标卡的 ✓ 上做 —— 单一入口，列表里不做滑动删除。
 */
@Composable
fun PilgrimageLogSheet(
    store: AnitabiStore,
    log: PilgrimageLog,
    onClose: () -> Unit,
    onSelectWork: (Int) -> Unit,
    onSelectPoint: (ScenePoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAnitabiPalette.current
    val groups = remember(log.generation, store.dataGeneration) {
        PilgrimageLogGroups.build(
            records = log.records,
            pointById = store::point,
            bangumiById = store::bangumi,
            pointCountOf = { store.pointsByBangumi[it]?.size ?: 0 },
        )
    }
    val compact = LocalSheetCollapsed.current

    Column(modifier = modifier.fillMaxSize().padding(top = 10.dp)) {
        // 身份条:标题 + 汇总 / ✕。迷你档构成与其它卡片一致(44 + 10)。
        Row(
            verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .let { if (compact) it.padding(bottom = 10.dp).heightIn(min = 44.dp) else it },
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.pilgrimage_log),
                    color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.pilgrimage_log_summary, groups.visitedCount, groups.workCount),
                    color = palette.inkTertiary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            CloseButton(onClose)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp + sheetBottomInset()),
        ) {
            if (groups.groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.pilgrimage_log_empty),
                        color = palette.inkTertiary, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            for (group in groups.groups) {
                // 组头吸顶:作品封面 + 名字 + 进度,点击去作品卡。
                stickyHeader(key = "w:${group.workId}") {
                    GroupHeader(group, onClick = { onSelectWork(group.workId) })
                }
                items(group.visited.size, key = { "p:${group.visited[it].point.id}" }) { i ->
                    val entry = group.visited[i]
                    RowCard(onClick = { onSelectPoint(entry.point) }) {
                        PointThumb(entry.point)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.point.displayName(LocalNameLocale.current),
                                color = palette.ink, fontSize = 14.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                entry.point.episodeBadge?.let {
                                    Text(it, color = palette.inkTertiary, fontSize = 11.sp)
                                }
                                Text(
                                    relativeTime(entry.record.visitedAt),
                                    color = palette.inkTertiary, fontSize = 11.sp,
                                )
                            }
                        }
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.visited),
                            tint = palette.visitedIcon,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (groups.orphanCount > 0) {
                item(key = "orphans") {
                    Text(
                        stringResource(R.string.pilgrimage_log_orphans, groups.orphanCount),
                        color = palette.inkTertiary, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: VisitedWorkGroup, onClick: () -> Unit) {
    val palette = LocalAnitabiPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 吸顶时盖在滚过的行上,要不透明底
            .background(palette.opaqueSheetSurface)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        AsyncImage(
            model = group.work.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 30.dp, height = 40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ColorUtilities.themeColor(group.work.colorHex).copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.work.displayName(LocalNameLocale.current), color = palette.ink, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.visited_progress, group.visited.size, group.totalPoints),
                color = palette.inkTertiary, fontSize = 12.sp,
            )
        }
        Text("›", color = palette.inkFaint, fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** 「3 天前」之类的相对时间;0(旧文件缺字段)则不显示。 */
@Composable
private fun relativeTime(epochMillis: Long): String =
    if (epochMillis <= 0L) "" else DateUtils.getRelativeTimeSpanString(epochMillis).toString()

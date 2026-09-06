package cn.anitabi.map.ui.point

import cn.anitabi.map.R
import android.content.Intent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.model.AnitabiImage
import cn.anitabi.map.support.AnitabiWebLink
import cn.anitabi.map.data.model.nilIfBlank
import cn.anitabi.map.support.ExternalLinks
import cn.anitabi.map.ui.sheet.sheetBottomInset
import cn.anitabi.map.ui.scene.CameraSessionParams
import cn.anitabi.map.ui.home.AnitabiDropdownMenu
import cn.anitabi.map.ui.home.AnitabiMenuItem
import cn.anitabi.map.theme.LocalAnitabiPalette
import cn.anitabi.map.theme.LocalNameLocale
import cn.anitabi.map.ui.work.ActionChip
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.anitabi.map.ui.work.ActionCapsule
import cn.anitabi.map.ui.work.CircleIconButton
import cn.anitabi.map.ui.work.CloseButton
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import cn.anitabi.map.ui.sheet.LocalSheetCollapsed
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 地标卡片（iOS PointCardSheet 的初版）。剧照全幅 + EP/时间码徽章 + 动作排 + 备注 + 出典。
 * 对比摄影的入口在 M6 接入。
 */
@Composable
fun PointCardSheet(
    store: AnitabiStore,
    pointId: String,
    bangumiId: Int,
    onClose: () -> Unit,
    onOpenWork: (Int) -> Unit,
    onShowEtiquette: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenCamera: (CameraSessionParams) -> Unit,
    onToast: (String) -> Unit,
    /** 巡礼记录:这个地标是否已完成,以及切换它(本仓库自有功能,iOS 无)。 */
    isVisited: Boolean,
    onToggleVisited: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAnitabiPalette.current
    val context = LocalContext.current
    // 文案在组合期取好:回调里用 context.getString 会绕开 Compose 的配置变更
    // (lint LocalContextGetResourceValueCall)。
    val coordinatesCopiedMessage = stringResource(R.string.coordinates_copied)
    // bangumiId 在函数体里被读到,就必须进 key —— 否则换作品但 pointId 相同时会取到旧值。
    val point = remember(pointId, bangumiId, store.dataGeneration) {
        store.pointsByBangumi[bangumiId]?.firstOrNull { it.id == pointId }
    }
    val work = store.bangumi(bangumiId)

    Column(modifier = modifier.fillMaxSize().padding(top = 10.dp)) {
        // 身份条。迷你档(LocalSheetCollapsed)下单行省略,其余多行折行。
        val nameLocale = LocalNameLocale.current
        val headerTitle = point?.displayName(nameLocale) ?: ""
        val headerSubtitle =
            stringResource(R.string.work_pilgrimage_spot, work?.displayName(nameLocale) ?: "#$bangumiId")
        PointHeaderRow(
            title = headerTitle, subtitle = headerSubtitle,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            isVisited = isVisited,
            onToggleVisited = onToggleVisited,
            onSubtitleClick = { onOpenWork(bangumiId) },
            onShare = {
                point?.let { p ->
                    val url = AnitabiWebLink.canonical(
                        bangumiId = bangumiId, pointId = p.id,
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            },
            onClose = onClose,
        )

        if (point == null) return@Column

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp + sheetBottomInset()),
        ) {
            // 条目有增删(礼仪条/备注是条件项),必须给稳定 key ——
            // 否则动作排里 remember 的展开状态会随槽位错位到别的条目上。
            // 礼仪警示条
            if (point.needsEtiquetteWarning) {
                item(key = "etiquette") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.warnFill)
                            .clickable(onClick = onShowEtiquette)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("⚠︎", color = palette.warnIcon, fontSize = 14.sp, modifier = Modifier.clearAndSetSemantics { })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.etiquette_warning_banner),
                            color = palette.warnInk, fontSize = 12.sp,
                        )
                    }
                }
            }

            // 剧照（h360）。没有时显示补传引导。
            item(key = "screenshot") {
                val screenshot = AnitabiImage.url(point.image, plan = "h360")
                if (screenshot != null) {
                    Box {
                        AsyncImage(
                            model = screenshot,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.mediaPlaceholder)
                                .clickable {
                                    // 全屏查看器用 h360（官方文档：不建议在任何展示界面使用
                                    // 完整尺寸截图；h360 即「适合移动设备满宽度查看」的档位）。
                                    // 保存到相册时才在 ImageViewer 内取完整尺寸。
                                    AnitabiImage.url(point.image, plan = "h360")?.let(onOpenImage)
                                },
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        ) {
                            point.episodeBadge?.let { Badge(it) }
                            point.timecodeText?.let { Badge(it) }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.mediaPlaceholder)
                            .clickable {
                                ExternalLinks.openInCustomTab(
                                    context, AnitabiWebLink.createPoint(point, work),
                                )
                            }
                            .padding(vertical = 28.dp),
                    ) {
                        Text(stringResource(R.string.no_screenshot_yet), color = palette.inkSecondary, fontSize = 13.sp)
                        Text(stringResource(R.string.upload_screenshot), color = palette.ink, fontSize = 13.sp)
                    }
                }
            }

            // 动作排（iOS WeightedRow：做对比图 1.4 / 地图 1 / 街景 1 / ⋯ 44dp — L344-375）
            item(key = "actions") {
                var moreOpen by remember { mutableStateOf(false) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (point.image != null) {
                        // primary：深绿实心 + 浅绿字（iOS L355，尺寸与 secondary 相同）
                        ActionCapsule(
                            label = stringResource(R.string.compare_image),
                            primary = true,
                            modifier = Modifier.weight(1.4f),
                        ) {
                            AnitabiImage.url(point.image, plan = null)?.let { url ->
                                onOpenCamera(
                                    CameraSessionParams(
                                        photoUrl = url,
                                        name = point.displayName(nameLocale),
                                        themeHex = work?.colorHex,
                                        location = "%.5f,%.5f".format(point.lat, point.lng),
                                    )
                                )
                            }
                        }
                    }
                    ActionCapsule(
                        label = stringResource(R.string.maps),
                        modifier = Modifier.weight(1f),
                    ) {
                        ExternalLinks.openExternally(
                            context, AnitabiWebLink.googleMaps(point.lat, point.lng),
                        )
                    }
                    ActionCapsule(
                        label = stringResource(R.string.street),
                        modifier = Modifier.weight(1f),
                    ) {
                        ExternalLinks.openExternally(
                            context, AnitabiWebLink.streetView(point.lat, point.lng),
                        )
                    }
                    Box {
                        ActionCapsule(
                            icon = Icons.Outlined.MoreHoriz,
                            modifier = Modifier.width(44.dp),
                        ) { moreOpen = true }
                        AnitabiDropdownMenu(
                            expanded = moreOpen,
                            onDismiss = { moreOpen = false },
                        ) {
                            AnitabiMenuItem(
                                stringResource(R.string.upload_screenshot).trimEnd(' ', '›'),
                                leading = {
                                    Icon(
                                        Icons.Outlined.AddPhotoAlternate,
                                        null, tint = palette.ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            ) {
                                moreOpen = false
                                ExternalLinks.openInCustomTab(
                                    context, AnitabiWebLink.createPoint(point, work),
                                )
                            }
                            AnitabiMenuItem(
                                stringResource(R.string.fix_coordinates),
                                leading = {
                                    Icon(
                                        Icons.Outlined.EditLocationAlt,
                                        null, tint = palette.ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            ) {
                                moreOpen = false
                                ExternalLinks.openInCustomTab(
                                    context, AnitabiWebLink.fixPointGps(point, work),
                                )
                            }
                            AnitabiMenuItem(
                                stringResource(R.string.open_in_web_version),
                                leading = {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.OpenInNew,
                                        null, tint = palette.ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            ) {
                                moreOpen = false
                                ExternalLinks.openInCustomTab(
                                    context,
                                    AnitabiWebLink.canonical(
                                        bangumiId = bangumiId, pointId = point.id,
                                        coordinate = point.coordinate,
                                    ),
                                )
                            }
                            AnitabiMenuItem(
                                stringResource(R.string.copy_coordinates),
                                leading = {
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        null, tint = palette.ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            ) {
                                moreOpen = false
                                val clipboard = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE
                                ) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "coordinates",
                                        "%.6f,%.6f".format(point.lat, point.lng),
                                    )
                                )
                                onToast(coordinatesCopiedMessage)
                            }
                        }
                    }
                }
            }

            // 备注
            point.note?.let { note ->
                item(key = "note") {
                    Text(
                        note,
                        color = palette.inkSecondary, fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.subtle)
                            .padding(12.dp),
                    )
                }
            }

            // 出典行
            item(key = "origin") {
                val originText = buildList {
                    point.origin?.let(::add)
                    point.uid?.let { uid -> store.userNickname(uid)?.let { add("by $it") } }
                }.joinToString(" · ")
                if (originText.isNotEmpty()) {
                    // 官方 API 文档建议:标注 origin 文字并实现 originURL 跳转。
                    val link = point.originLink.nilIfBlank()
                    Text(
                        originText,
                        color = palette.inkTertiary,
                        fontSize = 11.sp,
                        modifier = if (link != null) {
                            Modifier.clickable { ExternalLinks.openExternally(context, link) }
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

/**
 * 身份条：标题多行折行，副标题可点（跳到作品卡），按钮顶部对齐；迷你档下单行省略。
 * 右侧三个圆按钮:✓(巡礼记录)· 分享 · ✕ —— 迷你档也露着,所以在身份条上就能打卡。
 */
@Composable
private fun PointHeaderRow(
    title: String,
    subtitle: String,
    isVisited: Boolean,
    onToggleVisited: () -> Unit,
    modifier: Modifier = Modifier,
    onSubtitleClick: () -> Unit = {},
    onShare: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    val palette = LocalAnitabiPalette.current
    val haptics = LocalHapticFeedback.current
    val visitedLabel = stringResource(R.string.visited)
    val toggleDescription = stringResource(if (isVisited) R.string.a11y_unmark_visited else R.string.a11y_mark_visited)
    // 迷你档:单行省略 + 按钮垂直居中,行高下限 44 + 底部 10 —— 与 SheetPeek.miniBar 的构成一致。
    val compact = LocalSheetCollapsed.current
    Row(
        verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
        modifier = if (compact) modifier.padding(bottom = 10.dp).heightIn(min = 44.dp) else modifier,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = palette.inkTertiary, fontSize = 12.sp,
                maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onSubtitleClick),
            )
        }
        CircleIconButton(
            icon = if (isVisited) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
            contentDescription = toggleDescription,
            onClick = {
                // 打卡用确认触感;取消用普通长按触感,轻重有别。
                haptics.performHapticFeedback(
                    if (isVisited) HapticFeedbackType.LongPress else HapticFeedbackType.Confirm,
                )
                onToggleVisited()
            },
            stateDescription = if (isVisited) visitedLabel else null,
            containerColor = if (isVisited) palette.visitedFill else null,
            tint = if (isVisited) palette.visitedIcon else null,
        )
        CircleIconButton(Icons.Outlined.Share, contentDescription = stringResource(R.string.share), onClick = onShare)
        CloseButton(onClose)
    }
}

@Composable
private fun Badge(text: String) {
    val palette = LocalAnitabiPalette.current
    Text(
        text,
        color = palette.mediaBadgeText,
        fontSize = 11.sp,
        modifier = Modifier
            .background(palette.mediaBadge, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

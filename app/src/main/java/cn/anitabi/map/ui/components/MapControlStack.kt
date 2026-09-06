package cn.anitabi.map.ui.components

import cn.anitabi.map.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PhotoLibrary
import cn.anitabi.map.map.engine.PointVisitFilter
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.anitabi.map.theme.LocalAnitabiPalette
import cn.anitabi.map.ui.home.AnitabiDropdownMenu
import cn.anitabi.map.ui.home.AnitabiMenuItem

/**
 * 地图的浮动控件（对照 iOS MapControlStack）。48dp 按钮纵向成组，玻璃底只对整块画一次。
 * 图标为对应 SF Symbols 的自绘矢量（AnitabiIcons）：
 * dice（双骰重叠）/ location（纸飞机箭头）/ square.2.layers.3d（双层菱形）。
 * 分隔线与 iOS 相同，为 28dp 宽的细线。
 */
@Composable
fun MapControlStack(
    isLocationActive: Boolean,
    onLocationTap: () -> Unit,
    baseStyle: String,
    onBaseStyleChange: (String) -> Unit,
    isPhotoLayerVisible: Boolean,
    onPhotoLayerToggle: () -> Unit,
    visitFilter: PointVisitFilter,
    onVisitFilterChange: (PointVisitFilter) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 地图方位(度)的 provider。≠0 时指南针按钮出现。
     * lambda 形式:旋转手势每帧更新,组合期只在 0.5° 门限翻转时失效
     * (derivedStateOf),针的旋转在绘制相位读值 —— 每帧旋转不再重组本组件。
     */
    bearing: () -> Float = { 0f },
    onCompassTap: () -> Unit = {},
) {
    val palette = LocalAnitabiPalette.current
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        // 指南针**单独放在胶囊上方**（与 iOS 同构 —— 若挂在下方，每次出现/消失
        // 胶囊都会上下跳动）。旋转时淡入，回到正北后淡出，点击回正北。
        val compassVisible by remember(bearing) {
            androidx.compose.runtime.derivedStateOf { kotlin.math.abs(bearing()) > 0.5f }
        }
        val compassDescription = stringResource(R.string.a11y_reset_north)
        androidx.compose.animation.AnimatedVisibility(
            visible = compassVisible,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(palette.opaqueSheetSurface.copy(alpha = 0.96f))
                    .clickable(onClick = onCompassTap, role = androidx.compose.ui.semantics.Role.Button)
                    .semantics {
                        contentDescription = compassDescription
                    },
            ) {
                CompassNeedle(
                    bearing = bearing,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(palette.opaqueSheetSurface.copy(alpha = 0.96f)),
    ) {
        // 定位采用安卓设计语言（圆圈＋准星的 MyLocation — 真机反馈：纸飞机是 iOS 语汇）
        ControlButton(
            icon = if (isLocationActive) Icons.Filled.MyLocation else Icons.Outlined.MyLocation,
            tint = if (isLocationActive) palette.accentFill else palette.ink,
            contentDescription = stringResource(R.string.a11y_my_location),
            onClick = onLocationTap,
        )
        // 分隔线（iOS：宽 28、厚 0.5，叠在玻璃之外）
        HorizontalDivider(
            modifier = Modifier.width(28.dp),
            thickness = 0.5.dp,
            color = palette.hairline,
        )
        Box {
            ControlButton(
                icon = AnitabiIcons.Layers,
                tint = palette.ink,
                contentDescription = stringResource(R.string.a11y_map_layers),
            ) { menuOpen = true }
            AnitabiDropdownMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                // 底图样式：单选组（相当于 iOS Picker — 选中打 ✓、无图标）
                for ((value, label) in listOf(
                    "standard" to stringResource(R.string.standard),
                    "hybrid" to stringResource(R.string.satellite),
                )) {
                    AnitabiMenuItem(
                        label = label,
                        trailing = if (baseStyle == value) {
                            { Icon(Icons.Filled.Check, null, tint = palette.ink, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    ) {
                        onBaseStyleChange(value)
                        menuOpen = false
                    }
                }
                // 用分隔线区分选项类别（iOS 里靠 Picker 的内联分节自然分开）
                HorizontalDivider(color = palette.hairline)
                // 剧照标注：带图标的开关项（iOS Toggle + photo.on.rectangle.angled）
                AnitabiMenuItem(
                    label = stringResource(R.string.photo_pins),
                    leading = {
                        Icon(
                            Icons.Outlined.PhotoLibrary, null,
                            tint = palette.ink, modifier = Modifier.size(18.dp),
                        )
                    },
                    trailing = if (isPhotoLayerVisible) {
                        { Icon(Icons.Filled.Check, null, tint = palette.ink, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                ) {
                    onPhotoLayerToggle()
                    menuOpen = false
                }
                // 巡礼记录:全部 / 只看已完成 / 只看未完成(单选组,与底图同款;本仓库自有,iOS 无)
                HorizontalDivider(color = palette.hairline)
                for ((value, label) in listOf(
                    PointVisitFilter.All to stringResource(R.string.visit_filter_all),
                    PointVisitFilter.VisitedOnly to stringResource(R.string.visit_filter_visited),
                    PointVisitFilter.UnvisitedOnly to stringResource(R.string.visit_filter_unvisited),
                )) {
                    AnitabiMenuItem(
                        label = label,
                        trailing = if (visitFilter == value) {
                            { Icon(Icons.Filled.Check, null, tint = palette.ink, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    ) {
                        onVisitFilterChange(value)
                        menuOpen = false
                    }
                }
            }
        }
    }
    }
}

/** 自绘指南针的针(上半=红色北针、下半=灰色南针)。逆着地图旋转方向转;绘制相位读方位。 */
@Composable
private fun CompassNeedle(bearing: () -> Float, modifier: Modifier = Modifier) {
    val palette = LocalAnitabiPalette.current
    val south = palette.inkTertiary
    androidx.compose.foundation.Canvas(
        modifier = modifier.graphicsLayer { rotationZ = -bearing() }
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val half = w * 0.16f
        // 北针（红）
        val north = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, 0f)
            lineTo(cx - half, h / 2)
            lineTo(cx + half, h / 2)
            close()
        }
        drawPath(north, androidx.compose.ui.graphics.Color(0xFFE5484D))
        // 南针（灰）
        val tail = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, h)
            lineTo(cx - half, h / 2)
            lineTo(cx + half, h / 2)
            close()
        }
        drawPath(tail, south)
    }
}

/** 左下角的「随机作品」按钮（iOS MapRandomWorkButton 用 SF dice；这里用安卓语汇的 Shuffle）。 */
@Composable
fun RandomWorkButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalAnitabiPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(palette.opaqueSheetSurface.copy(alpha = 0.96f))
            .clickable(onClick = onClick),
    ) {
        Icon(
            Icons.Rounded.Shuffle,
            contentDescription = stringResource(R.string.a11y_random_work),
            tint = palette.ink,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}

package cn.anitabi.map.ui.point

import cn.anitabi.map.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.NearbyPoint
import cn.anitabi.map.data.model.DistanceFormatter
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.support.AnitabiWebLink
import cn.anitabi.map.support.ExternalLinks
import cn.anitabi.map.theme.LocalAnitabiPalette
import cn.anitabi.map.theme.LocalNameLocale
import cn.anitabi.map.ui.home.PointThumb
import cn.anitabi.map.ui.home.RowCard
import cn.anitabi.map.ui.home.SectionHeader
import cn.anitabi.map.ui.work.ActionChip
import cn.anitabi.map.ui.work.CloseButton

/**
 * 长按落针卡片（iOS DroppedPinSheet 的初版）。等宽坐标 + 动作排 + 最近的巡礼点。
 * 反查地名（Geocoder）留待后续补充。
 */
@Composable
fun DroppedPinSheet(
    store: AnitabiStore,
    coordinate: LatLon,
    onClose: () -> Unit,
    onSelectPoint: (ScenePoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAnitabiPalette.current
    val context = LocalContext.current
    var nearest by remember { mutableStateOf<List<NearbyPoint>>(emptyList()) }

    LaunchedEffect(coordinate) {
        nearest = AnitabiStore.nearestPoints(coordinate, store.points, limit = 3)
    }

    Column(modifier = modifier.fillMaxSize().padding(top = 10.dp, start = 16.dp, end = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 行高下限 44 + 底部 10（与 iOS compactBarRow 同值）。
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .heightIn(min = 44.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.marked_location), color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "%.5f, %.5f".format(coordinate.lat, coordinate.lng),
                    color = palette.inkTertiary, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            CloseButton(onClose)
        }

        Spacer(Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(stringResource(R.string.google_navigation)) {
                ExternalLinks.openExternally(
                    context, AnitabiWebLink.googleMaps(coordinate.lat, coordinate.lng),
                )
            }
            ActionChip(stringResource(R.string.add_spot_here)) {
                ExternalLinks.openInCustomTab(context, AnitabiWebLink.createPoint(coordinate))
            }
        }

        if (nearest.isNotEmpty()) {
            SectionHeader(stringResource(R.string.spots_nearest_to_this_pin))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (item in nearest) {
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
            }
        }
    }
}

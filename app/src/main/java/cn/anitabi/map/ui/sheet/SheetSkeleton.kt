package cn.anitabi.map.ui.sheet

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.anitabi.map.theme.LocalAnitabiPalette

/**
 * 骨架屏(iOS SheetSkeleton 的初版)。使用与实物相同的尺寸 token,切换时行高不跳动。
 * 扫光只在容器上做 1 次(不逐行叠加 — 与 iOS 相同)。
 */
@Composable
fun HomeBrowseSkeleton(modifier: Modifier = Modifier) {
    val palette = LocalAnitabiPalette.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shimmerX by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1400, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "shimmer",
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        palette.mediaPlaceholder.copy(alpha = 0f),
                        palette.skeletonHighlight.copy(alpha = 0.35f),
                        palette.mediaPlaceholder.copy(alpha = 0f),
                    ),
                    start = Offset(shimmerX * 900f, 0f),
                    end = Offset(shimmerX * 900f + 450f, 220f),
                )
            ),
    ) {
        SkeletonBar(widthFraction = 0.3f)
        repeat(4) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 34.dp, height = 46.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.mediaPlaceholder),
                )
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBar(widthFraction = 0.55f)
                    SkeletonBar(widthFraction = 0.35f, height = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(
    widthFraction: Float,
    height: Dp = 14.dp,
) {
    val palette = LocalAnitabiPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(palette.mediaPlaceholder),
    )
}

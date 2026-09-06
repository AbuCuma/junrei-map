package cn.anitabi.map.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 与 SF Symbols 对应的自绘矢量图标（意匠对照 iOS MapControlStack）。
 * - layers：双层菱形（SF "square.2.layers.3d"）。
 * 随机作品按钮此前是自绘的两枚骰子（SF dice），真机反馈读不出「随机」，已换成安卓语汇的
 * Shuffle（material-icons-extended），故不在此处。
 * 24dp 网格。填充/描边均为 currentColor（跟随 Icon 的 tint）。
 * 定位图标使用安卓语汇的 MyLocation（material-icons-extended），故不在此处。
 */
object AnitabiIcons {

    /** 双层菱形（等角视 — SF "square.2.layers.3d"）。 */
    val Layers: ImageVector by lazy {
        ImageVector.Builder(
            name = "AnitabiLayers",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            // 上层菱形（填充）
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 3.5f)
                lineTo(21f, 8.5f)
                lineTo(12f, 13.5f)
                lineTo(3f, 8.5f)
                close()
            }
            // 下层菱形（仅轮廓，被上层遮住的部分省略）
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.6f, 12.4f)
                lineTo(3f, 13.3f)
                lineTo(12f, 18.3f)
                lineTo(21f, 13.3f)
                lineTo(19.4f, 12.4f)
            }
        }.build()
    }
}

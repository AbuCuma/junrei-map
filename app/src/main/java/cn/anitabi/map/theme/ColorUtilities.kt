package cn.anitabi.map.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

// 把作品主题色（`#rrggbb`、来自服务器、色域不可控）安全用于地图标注的工具
// （iOS ColorUtilities 的移植）。**只有这里解析 hex。**

object ColorUtilities {

    /** 取不到主题色的作品所用的替代色。**只有这一处持有该值。** */
    val NeutralTheme = Color(0xFF8A8A6F)

    /** `#rrggbb` / `rrggbb` / `#rgb`。无法解析的值返回 null（由调用侧回落到中性灰）。 */
    fun parseCssHex(cssHex: String): Color? {
        var s = cssHex.trim().removePrefix("#")
        if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
        if (s.length != 6) return null
        val v = s.toLongOrNull(16) ?: return null
        return Color(
            red = ((v shr 16) and 0xFF) / 255f,
            green = ((v shr 8) and 0xFF) / 255f,
            blue = (v and 0xFF) / 255f,
        )
    }

    /** 从 hex 解析出的主题色，或中性灰。 */
    fun themeColor(cssHex: String?): Color =
        cssHex?.let(::parseCssHex) ?: NeutralTheme

    /**
     * 是否为会与白色描边同化的明亮主题色。
     * 这是「会不会溶进白色描边」的观感判断，用不带伽马校正的简单加权就够了。
     */
    fun needsDarkInnerOutline(color: Color): Boolean =
        (0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue) > 0.82f

    fun mixed(color: Color, other: Color, amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return Color(
            red = color.red + (other.red - color.red) * t,
            green = color.green + (other.green - color.green) * t,
            blue = color.blue + (other.blue - color.blue) * t,
            alpha = color.alpha,
        )
    }

    /**
     * 作品标的标签色。返回把主题色**向黑色靠拢**、使其与背景的对比度满足 minimum 的
     * 最小混合率。用 maxMix 截断是因为混到底会抹掉作品之间的颜色差异＝
     * 地图上的识别标记。
     */
    fun labelColor(
        color: Color,
        background: Color = Color.White,
        minimum: Float = 4.5f,
        maxMix: Float = 0.6f,
    ): Color {
        var candidate = color
        var mix = 0f
        while (mix < maxMix && contrastRatio(candidate, background) < minimum) {
            mix = minOf(mix + 0.05f, maxMix)
            candidate = mixed(color, Color.Black, mix)
        }
        return candidate
    }

    /** WCAG 相对亮度（先还原 sRGB 伽马再加权）。 */
    private fun relativeLuminance(color: Color): Float {
        fun linear(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) +
            0.0722f * linear(color.blue)
    }

    fun contrastRatio(lhs: Color, rhs: Color): Float {
        val l1 = relativeLuminance(lhs)
        val l2 = relativeLuminance(rhs)
        return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    }
}

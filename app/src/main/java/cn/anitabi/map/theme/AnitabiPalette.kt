package cn.anitabi.map.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// 照抄设计稿「圣地巡礼卡片组件」配色的唯一色源（iOS AnitabiPalette.swift 的 1:1 移植）。
// 设计稿只有浅色一份，dark 是按两条原则映射到夜间侧的派生值：
// (1) 墨色与主色互换；(2) 白色薄膜保持白色，只降低不透明度。
// 除此处之外不得出现 hex。

private val RawInk = Color(0xFF132E1A)
private val RawOnInk = Color(0xFFEAFFEC)
private val RawMint = Color(0xFFD4FFD5)
private val RawInkDark = Color(0xFFD7F3DA)
private val RawAccentDark = Color(0xFFA7E9B4)
private val RawOnAccentDark = Color(0xFF0C2113)
private val RawWarnFill = Color(0xFFFFD678)
private val RawWarnLine = Color(0xFFD9A441)
private val RawWarnInk = Color(0xFF5A3D00)
private val RawWarnInkDark = Color(0xFFF3DFAD)
private val RawBadgeWarnInk = Color(0xFF6B4A00)
private val RawCardGlass = Color(0xFFECFFEC)
private val RawCardGlassDark = Color(0xFF10281A)
// 巡礼记录「已完成」(本仓库自有,设计稿没有):比主色更亮的一档绿,与琥珀色的警示区分。
private val RawVisitedGreen = Color(0xFF1E8E3E)
private val RawVisitedGreenDark = Color(0xFF7FD99A)

/** 业务 UI 直接取用本调色板的 token，不经由 MaterialTheme.colorScheme。 */
@Immutable
class AnitabiPalette(
    /** 是否为夜间侧。用于切换不透明面色的合成底色（白 / 深绿）。 */
    val isDark: Boolean,
    /** 标题、行名、主要文字。 */
    val ink: Color,
    /** 副标题、meta 行。承载正文,≥4.5:1。 */
    val inkSecondary: Color,
    /** 小节标题的辅助数值、出处行。承载正文,≥4.5:1。 */
    val inkTertiary: Color,
    /**
     * **非文本 UI 专用**:`›` 符号、图标 tint、sheet 把手。
     * 阈值按 WCAG 的非文本标准 3:1 —— **不要拿它写正文**,正文最低用 [inkTertiary]。
     */
    val inkFaint: Color,
    /** 主操作的实心填充。 */
    val accentFill: Color,
    /** 主操作实心填充上的文字。 */
    val onAccent: Color,
    /** 卡片（sheet）的底色。模糊交给叠放处的 material 等效物负责。 */
    val cardGlass: Color,
    /** 列表卡片、信息卡片的底色。 */
    val surface: Color,
    val surfaceStroke: Color,
    /** 次要操作的胶囊、圆形按钮。 */
    val raised: Color,
    val raisedStroke: Color,
    /** 备注正文的底色。 */
    val subtle: Color,
    /** ✕ 圆形按钮、「清除」chip 的底色。 */
    val softFill: Color,
    /** 分段控件的凹槽。 */
    val track: Color,
    /** 分段控件的滑块。 */
    val thumb: Color,
    /** 行间细线。 */
    val hairline: Color,
    /** 无剧照／加载中的容器。 */
    val mediaPlaceholder: Color,
    /** 扫过骨架屏的高光。 */
    val skeletonHighlight: Color,
    /** 图片上的 EP／时间码徽章。 */
    val mediaBadge: Color,
    val mediaBadgeText: Color,
    /** 礼仪警示（琥珀色）。 */
    val warnFill: Color,
    val warnStroke: Color,
    val warnIcon: Color,
    val warnInk: Color,
    /** 地点行的「注意礼仪」徽章。 */
    val badgeWarnFill: Color,
    val badgeWarnInk: Color,
    /** 巡礼记录「已完成」:头部按钮的底与勾的颜色;行内徽标只用 [visitedIcon]。 */
    val visitedFill: Color,
    val visitedIcon: Color,
) {
    /**
     * Sheet、模态、欢迎卡所铺的**不透明**底色。把 cardGlass 叠在昼＝白、夜＝深绿的底上
     * 合成（blur material 的替代）。**不要在模态类上直接写白色** ——
     * 夜间侧的浅色文字会沉进白底而无法阅读。
     */
    val opaqueSheetSurface: Color = lerp(
        if (isDark) Color(0xFF0E1F14) else Color.White,
        cardGlass.copy(alpha = 1f),
        cardGlass.alpha,
    )
}

val AnitabiPaletteLight = AnitabiPalette(
    isDark = false,
    ink = RawInk,
    // 浅色侧的三档此前是 4.05 / 3.05 / 2.47:1,全部低于 WCAG AA。提到达标值:
    // 正文档(secondary/tertiary)≥4.5:1,inkFaint 只用于非文本 UI 故按 ≥3:1。
    // 深色侧原本就达标,一个值都没动。阈值由 AnitabiPaletteContrastTest 守住。
    inkSecondary = RawInk.copy(alpha = 0.72f),
    inkTertiary = RawInk.copy(alpha = 0.66f),
    inkFaint = RawInk.copy(alpha = 0.52f),
    accentFill = RawInk,
    onAccent = RawOnInk,
    cardGlass = RawCardGlass.copy(alpha = 0.50f),
    surface = Color.White.copy(alpha = 0.50f),
    surfaceStroke = Color.White.copy(alpha = 0.85f),
    // 白 60% 会沉进近白的 sheet 底（opaqueSheetSurface≈#F5FFF5）里，看不出是按钮（真机反馈）
    // → 用墨绿的淡染明示「可按的面」。
    raised = RawInk.copy(alpha = 0.08f),
    raisedStroke = Color.White.copy(alpha = 0.90f),
    subtle = Color.White.copy(alpha = 0.40f),
    softFill = RawInk.copy(alpha = 0.10f),
    track = RawInk.copy(alpha = 0.07f),
    thumb = Color.White,
    hairline = RawInk.copy(alpha = 0.08f),
    mediaPlaceholder = RawInk.copy(alpha = 0.09f),
    skeletonHighlight = Color.White.copy(alpha = 0.55f),
    mediaBadge = RawInk.copy(alpha = 0.85f),
    mediaBadgeText = RawMint,
    warnFill = RawWarnFill.copy(alpha = 0.40f),
    warnStroke = RawWarnLine.copy(alpha = 0.60f),
    warnIcon = RawWarnLine,
    warnInk = RawWarnInk,
    badgeWarnFill = RawWarnLine.copy(alpha = 0.30f),
    badgeWarnInk = RawBadgeWarnInk,
    visitedFill = RawVisitedGreen.copy(alpha = 0.16f),
    visitedIcon = RawVisitedGreen,
)

val AnitabiPaletteDark = AnitabiPalette(
    isDark = true,
    ink = RawInkDark,
    inkSecondary = RawInkDark.copy(alpha = 0.66f),
    inkTertiary = RawInkDark.copy(alpha = 0.52f),
    inkFaint = RawInkDark.copy(alpha = 0.40f),
    accentFill = RawAccentDark,
    onAccent = RawOnAccentDark,
    cardGlass = RawCardGlassDark.copy(alpha = 0.56f),
    surface = Color.White.copy(alpha = 0.07f),
    surfaceStroke = Color.White.copy(alpha = 0.13f),
    raised = Color.White.copy(alpha = 0.13f),
    raisedStroke = Color.White.copy(alpha = 0.20f),
    subtle = Color.White.copy(alpha = 0.05f),
    softFill = Color.White.copy(alpha = 0.14f),
    track = Color.White.copy(alpha = 0.10f),
    thumb = Color.White.copy(alpha = 0.26f),
    hairline = Color.White.copy(alpha = 0.12f),
    mediaPlaceholder = Color.White.copy(alpha = 0.10f),
    skeletonHighlight = Color.White.copy(alpha = 0.14f),
    mediaBadge = Color.Black.copy(alpha = 0.62f),
    mediaBadgeText = RawMint,
    warnFill = RawWarnLine.copy(alpha = 0.22f),
    warnStroke = RawWarnLine.copy(alpha = 0.50f),
    warnIcon = RawWarnLine,
    warnInk = RawWarnInkDark,
    badgeWarnFill = RawWarnLine.copy(alpha = 0.26f),
    badgeWarnInk = RawWarnInkDark,
    visitedFill = RawVisitedGreenDark.copy(alpha = 0.20f),
    visitedIcon = RawVisitedGreenDark,
)

val LocalAnitabiPalette = staticCompositionLocalOf { AnitabiPaletteLight }

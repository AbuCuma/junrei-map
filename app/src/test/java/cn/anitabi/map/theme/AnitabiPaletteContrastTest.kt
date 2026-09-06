package cn.anitabi.map.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调色板的对比度守门。
 *
 * 仓库自己的 [ColorUtilities.labelColor] 已经用 4.5:1 给地图标签做保证,调色板理应遵守同一条线。
 * 这个测试把阈值钉死,避免后人凭观感把 token 调淡回去。
 *
 * 判据取 WCAG 2.x:承载正文的 token ≥4.5:1;只用于非文本 UI(符号、图标 tint、把手)的
 * token ≥3:1。底色用 sheet 的实际合成底色 [AnitabiPalette.opaqueSheetSurface],
 * 因为这些文字实际就画在它上面。
 */
class AnitabiPaletteContrastTest {

    private fun ratioOnSheet(palette: AnitabiPalette, token: Color): Float =
        ColorUtilities.contrastRatio(
            // token 多半带 alpha,先合成到 sheet 底色上再比 —— 直接比会高估。
            token.compositeOver(palette.opaqueSheetSurface),
            palette.opaqueSheetSurface,
        )

    private fun assertTextTokens(palette: AnitabiPalette, name: String) {
        for ((token, label) in listOf(
            palette.ink to "ink",
            palette.inkSecondary to "inkSecondary",
            palette.inkTertiary to "inkTertiary",
        )) {
            val ratio = ratioOnSheet(palette, token)
            assertTrue(
                "$name.$label 对 sheet 底色只有 ${"%.2f".format(ratio)}:1,正文 token 需要 ≥4.5:1",
                ratio >= 4.5f,
            )
        }
    }

    @Test
    fun lightTextTokensMeetWcagAa() = assertTextTokens(AnitabiPaletteLight, "light")

    @Test
    fun darkTextTokensMeetWcagAa() = assertTextTokens(AnitabiPaletteDark, "dark")

    @Test
    fun faintTokenMeetsTheNonTextThreshold() {
        // inkFaint 只用于非文本 UI(`›` 符号、图标 tint、sheet 把手)——
        // 阈值是 3:1,但**不允许**再被用于正文。
        for ((palette, name) in listOf(AnitabiPaletteLight to "light", AnitabiPaletteDark to "dark")) {
            val ratio = ratioOnSheet(palette, palette.inkFaint)
            assertTrue(
                "$name.inkFaint 对 sheet 底色只有 ${"%.2f".format(ratio)}:1,非文本 token 需要 ≥3:1",
                ratio >= 3.0f,
            )
        }
    }

    @Test
    fun textHierarchyStaysOrdered() {
        // 达标之后层次仍要可辨:主 > 次 > 三级 > 弱。
        for ((palette, name) in listOf(AnitabiPaletteLight to "light", AnitabiPaletteDark to "dark")) {
            val ink = ratioOnSheet(palette, palette.ink)
            val secondary = ratioOnSheet(palette, palette.inkSecondary)
            val tertiary = ratioOnSheet(palette, palette.inkTertiary)
            val faint = ratioOnSheet(palette, palette.inkFaint)
            assertTrue("$name:ink 应比 inkSecondary 更强", ink > secondary)
            assertTrue("$name:inkSecondary 应比 inkTertiary 更强", secondary > tertiary)
            assertTrue("$name:inkTertiary 应比 inkFaint 更强", tertiary > faint)
        }
    }

    @Test
    fun visitedIconIsLegibleOnItsFillAndOnTheSheet() {
        // 巡礼记录的 ✓:头部按钮里画在 visitedFill 上,行尾徽标画在 sheet 上;都是非文本 UI(≥3:1)。
        for ((palette, name) in listOf(AnitabiPaletteLight to "light", AnitabiPaletteDark to "dark")) {
            val fill = palette.visitedFill.compositeOver(palette.opaqueSheetSurface)
            val onFill = ColorUtilities.contrastRatio(palette.visitedIcon.compositeOver(fill), fill)
            assertTrue("$name.visitedIcon 对 visitedFill 只有 ${"%.2f".format(onFill)}:1", onFill >= 3.0f)
            val onSheet = ratioOnSheet(palette, palette.visitedIcon)
            assertTrue("$name.visitedIcon 对 sheet 底色只有 ${"%.2f".format(onSheet)}:1", onSheet >= 3.0f)
        }
    }

    @Test
    fun accentFillCarriesReadableLabel() {
        // 主操作是实心按钮,文字画在 accentFill 上而不是 sheet 上。
        for ((palette, name) in listOf(AnitabiPaletteLight to "light", AnitabiPaletteDark to "dark")) {
            val ratio = ColorUtilities.contrastRatio(
                palette.onAccent.compositeOver(palette.accentFill),
                palette.accentFill,
            )
            assertTrue("$name.onAccent 对 accentFill 只有 ${"%.2f".format(ratio)}:1", ratio >= 4.5f)
        }
    }
}

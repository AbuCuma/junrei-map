package cn.anitabi.map.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import cn.anitabi.map.data.model.NameLocale

// 固定薄荷绿品牌色，不使用 Material You 动态色。
// 给 M3 组件（滑杆、按钮等）提供源自调色板的最小 ColorScheme，
// 业务 UI 直接取用 LocalAnitabiPalette 的 token。

private val LightScheme = lightColorScheme(
    primary = AnitabiPaletteLight.accentFill,
    onPrimary = AnitabiPaletteLight.onAccent,
    surface = AnitabiPaletteLight.thumb,
    onSurface = AnitabiPaletteLight.ink,
    onSurfaceVariant = AnitabiPaletteLight.inkSecondary,
)

private val DarkScheme = darkColorScheme(
    primary = AnitabiPaletteDark.accentFill,
    onPrimary = AnitabiPaletteDark.onAccent,
    onSurface = AnitabiPaletteDark.ink,
    onSurfaceVariant = AnitabiPaletteDark.inkSecondary,
)

/**
 * 作品名 / 地标名跟随的界面语言(中文名 / 原题 / 英文题名)。由 [AnitabiTheme] 按当前配置的首选语言提供,
 * 应用内切换语言(`localeConfig`)会换配置、随之重组。
 */
val LocalNameLocale = staticCompositionLocalOf { NameLocale.Zh }

@Composable
fun AnitabiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) AnitabiPaletteDark else AnitabiPaletteLight
    val configuration = LocalConfiguration.current
    val nameLocale = ConfigurationCompat.getLocales(configuration)[0]?.language
        ?.let(NameLocale::fromLanguage) ?: NameLocale.En
    CompositionLocalProvider(LocalAnitabiPalette provides palette, LocalNameLocale provides nameLocale) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content,
        )
    }
}

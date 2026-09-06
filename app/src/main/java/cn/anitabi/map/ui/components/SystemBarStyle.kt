package cn.anitabi.map.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 在场期间把状态栏图标强制为浅色(适配深色满屏背景),离场时恢复原状。
 *
 * enableEdgeToEdge 跟随系统主题:浅色主题下是深色图标,而全屏查看器/首启引导
 * 都在暗色底上铺到状态栏后面 —— 深色图标会看不见(时钟/电量不可读)。
 */
@Composable
fun ForceLightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
            ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

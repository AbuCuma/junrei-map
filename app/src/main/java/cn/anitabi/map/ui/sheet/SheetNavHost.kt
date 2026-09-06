package cn.anitabi.map.ui.sheet

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.anitabi.map.theme.LocalAnitabiPalette
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

/** 层切换的淡入淡出时长（ms）。 */
const val SHEET_LAYER_FADE_MS = 180

/**
 * sheet 内容 = Navigation 3 的 [NavDisplay]。只组合栈顶那一层；
 * `rememberSaveableStateHolderNavEntryDecorator` 让被压在下面的层（Home 的搜索文字、
 * 滚动位置 —— 它们都是 `rememberSaveable` / `rememberLazyListState`）在回来时原样恢复。
 *
 * 此前 4 层内容常驻组合、靠 alpha/zIndex/指针守卫/无障碍隐藏换装 —— 那一整套连同
 * 「淡出期间续渲染旧内容」的补丁都由这里的 back stack 语义替代。
 */
@Composable
fun SheetNavHost(
    backStack: List<SheetKey>,
    actions: SheetActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (SheetKey) -> Unit,
) {
    CompositionLocalProvider(LocalSheetActions provides actions) {
        NavDisplay(
            backStack = backStack,
            modifier = modifier,
            onBack = onBack,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            // 只做淡入淡出:sheet 的高度变化已经由 scaffold 的弹簧表达,再叠一层水平滑动会打架。
            transitionSpec = { fadeIn(tween(SHEET_LAYER_FADE_MS)) togetherWith fadeOut(tween(SHEET_LAYER_FADE_MS)) },
            popTransitionSpec = { fadeIn(tween(SHEET_LAYER_FADE_MS)) togetherWith fadeOut(tween(SHEET_LAYER_FADE_MS)) },
            predictivePopTransitionSpec = { fadeIn(tween(SHEET_LAYER_FADE_MS)) togetherWith fadeOut(tween(SHEET_LAYER_FADE_MS)) },
            entryProvider = { key -> NavEntry(key) { content(key) } },
        )
    }
}

/** sheet 顶端的拖拽把手（[SheetPeek.handleHeight]）。矮一点，Home 的半开档才装得下搜索行。 */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    val palette = LocalAnitabiPalette.current
    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .width(36.dp)
            .height(4.dp)
            .background(palette.inkFaint, CircleShape),
    )
}

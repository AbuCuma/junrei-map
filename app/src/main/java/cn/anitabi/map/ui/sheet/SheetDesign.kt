package cn.anitabi.map.ui.sheet

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * 常驻 sheet 内滚动列表的底部避让:导航栏与软键盘取大者。
 * 展开档时 sheet 一直延伸到物理屏底,列表最后一行会被
 * 导航栏(三键 48dp > 固定 32dp 余量)压住 —— 各 LazyColumn 的
 * contentPadding.bottom 需要叠加这一段。初始档的避让由 [SheetPeek] 的 peek 高度承担,互不重复。
 *
 * 键盘:edge-to-edge 下 `adjustResize` 不缩窗口,键盘只是一段 inset,sheet 的锚点也不随它变
 *(锚点必须静态,见 [DetentSheet]),所以搜索时列表末尾那一键盘高的结果要靠这里的 padding 才滚得出来。
 */
@Composable
fun sheetBottomInset(): Dp =
    WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()

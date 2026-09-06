package cn.anitabi.map.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import cn.anitabi.map.theme.LocalAnitabiPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Google Maps 式的三档底部卡：一条身份条 / 半开 / 展开。
 *
 * 就是 Foundation 的 [AnchoredDraggableState] + 一个把列表滚动接到 sheet 上的 nested scroll —— M3 的
 * `BottomSheetScaffold` 内部也是这两样，只是它写死了两档。此前借它的 Hidden 手势伪造第三档，
 * 与它自己的手势管线互相踩脚（拖拽动画抖、列表偶尔滚不动）；这里自持锚点，档位全部**静态**：
 * [anchorsFor] 由容器高换算，没有实测高度，也就没有「实测 → 重设锚点 → 再实测」的反馈环。
 *
 * 手势约定（与 Google Maps 一致）：
 * - 拖把手或不可滚动的内容：sheet 跟手，松手按位置阈值 + 速度吸附，快甩可跨档；
 * - 列表在顶部时向下拉 → 拖动 sheet；sheet 未展开时向上滑列表 → 先把 sheet 推到顶再滚列表；
 * - 在 [Detent.Mini] 点内容 → 上一档（竖屏半开、横屏卡片直接展开）；
 * - 把手带无障碍的展开 / 收起动作（相邻档）。
 *
 * 锚点在**布局相位**更新（M3 亦然），放置时才读 offset —— 拖拽只触发重新放置，不重组。
 */
@Composable
fun DetentSheet(
    state: AnchoredDraggableState<Detent>,
    anchorsFor: (containerHeightPx: Float) -> DraggableAnchors<Detent>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalAnitabiPalette.current
    val scope = rememberCoroutineScope()
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(state)
    val nestedScroll = remember(state, flingBehavior) { SheetNestedScrollConnection(state, flingBehavior) }
    val shape = RoundedCornerShape(topStart = SheetPeek.CORNER_RADIUS, topEnd = SheetPeek.CORNER_RADIUS)

    // 相邻档(无障碍动作与迷你档点按都用它;横屏卡片没有半开档,所以按锚点里实际存在的算)。
    val anchors = state.anchors
    val current = state.currentValue
    val up = Detent.entries.getOrNull(current.ordinal + 1)?.takeIf { anchors.hasPositionFor(it) }
        ?: Detent.entries.getOrNull(current.ordinal + 2)?.takeIf { anchors.hasPositionFor(it) }
    val down = Detent.entries.getOrNull(current.ordinal - 1)?.takeIf { anchors.hasPositionFor(it) }
        ?: Detent.entries.getOrNull(current.ordinal - 2)?.takeIf { anchors.hasPositionFor(it) }

    Layout(
        modifier = modifier,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(nestedScroll)
                    .anchoredDraggable(state, Orientation.Vertical, flingBehavior = flingBehavior)
                    .shadow(8.dp, shape)
                    .clip(shape)
                    .background(palette.opaqueSheetSurface)
                    // 迷你档下点内容回上一档。clickable 只在抬手时消费,不挡拖拽;身份条上的按钮是子节点,先收到事件。
                    .clickable(
                        enabled = current == Detent.Mini && up != null,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { up?.let { scope.launch { state.animateTo(it) } } },
            ) {
                Column(Modifier.fillMaxSize()) {
                    SheetHandle(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .semantics {
                                if (up != null) expand { scope.launch { state.animateTo(up) }; true }
                                if (down != null) collapse { scope.launch { state.animateTo(down) }; true }
                            },
                    )
                    // 身份条从动画起步那一刻就按迷你档排版,不等落定。
                    CompositionLocalProvider(LocalSheetCollapsed provides (state.targetValue == Detent.Mini)) {
                        content()
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val heightPx = constraints.maxHeight
        val anchors = anchorsFor(heightPx.toFloat())
        // sheet 本体的高度 = 展开档露出的高度,内容尺寸不随档位变。
        val sheetHeight = (heightPx - anchors.minPosition()).roundToInt().coerceIn(0, heightPx)
        val placeable = measurables.single().measure(
            constraints.copy(minHeight = sheetHeight, maxHeight = sheetHeight),
        )
        // 锚点变了(换层 / 旋转 / 系统栏变化)才换;只在这个分支里读 targetValue / offset,
        // 免得测量相位对 offset 建立依赖 —— 那样每一拖拽帧都会重测而不只是重放置。
        if (anchors != state.anchors) {
            // 目标档在新锚点里不存在(横屏卡片没有半开档)→ 取离当前位置最近的。
            val target = state.targetValue.takeIf { anchors.hasPositionFor(it) }
                ?: anchors.closestAnchor(state.offset.takeUnless { it.isNaN() } ?: anchors.minPosition())
                ?: Detent.Expanded
            state.updateAnchors(anchors, target)
        }
        layout(constraints.maxWidth, heightPx) {
            // offset 只在放置相位读。NaN 只在理论上可能(动画目标被换掉的锚点集抽走),兜到目标档的位置。
            val offset = state.offset.takeUnless { it.isNaN() }
                ?: state.anchors.positionOf(state.targetValue).takeUnless { it.isNaN() }
                ?: state.anchors.minPosition()
            placeable.placeRelative(0, offset.roundToInt())
        }
    }
}

/**
 * 列表与 sheet 的联动（移植自 M3 BottomSheetScaffold 的同名连接）：
 * 向上拖先归 sheet,列表吃剩的余量也归 sheet;甩动在 sheet 未到顶时交给 [flingBehavior] 结算。
 */
private class SheetNestedScrollConnection(
    private val state: AnchoredDraggableState<Detent>,
    private val flingBehavior: FlingBehavior,
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        return if (delta < 0 && source == NestedScrollSource.UserInput) {
            Offset(0f, state.dispatchRawDelta(delta))
        } else {
            Offset.Zero
        }
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        val velocity = available.y
        val offset = runCatching { state.requireOffset() }.getOrNull() ?: return Velocity.Zero
        return if (velocity < 0 && offset > state.anchors.minPosition()) {
            fling(velocity)
            available
        } else {
            Velocity.Zero
        }
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        fling(available.y)
        return available
    }

    /**
     * 与 Foundation 的 `AnchoredDraggableNode.fling` 同款:结算**必须**在 [AnchoredDraggableState.anchoredDrag]
     * 里跑。`dispatchRawDelta` 只写 offset,不碰 `currentValue`;直接在外面 performFling 的话 sheet 会停到锚点上、
     * `currentValue` 却留在上一档 —— 由列表带着 sheet 收到迷你档后,点身份条没反应(clickable 看的是 currentValue),
     * 反过来由列表推到展开后点内容会把 sheet 掉回半开。anchoredDrag 还持有拖拽互斥,
     * 能取消同时进行的 animateTo,不会两边逐帧互写 offset。
     */
    private suspend fun fling(velocity: Float) {
        state.anchoredDrag { anchors ->
            val scrollScope = object : ScrollScope {
                override fun scrollBy(pixels: Float): Float {
                    val old = state.offset.takeUnless { it.isNaN() } ?: 0f
                    val new = (old + pixels).coerceIn(anchors.minPosition(), anchors.maxPosition())
                    dragTo(new)
                    return new - old
                }
            }
            with(flingBehavior) { scrollScope.performFling(velocity) }
        }
    }
}

package cn.anitabi.map.ui.sheet

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey

/**
 * 常驻 sheet 的内容栈（Navigation 3 的 back stack 元素）。
 *
 * 栈底永远是 [Home]；作品卡、地标卡、图钉卡压在上面。谁在栈顶，sheet 就显示谁；
 * 系统返回键 = pop。栈由 [cn.anitabi.map.app.AppRouter] 持有 —— 那是导航的唯一事实源，
 * 这里只定义元素类型。
 *
 * 不做 `@Serializable`：栈活在 [cn.anitabi.map.app.AppGraph] 的 router 里（跨配置变更），
 * 进程死亡后的恢复走 `prefs.restoredDeepLink`（canonical URL），不需要 `rememberNavBackStack`。
 */
sealed interface SheetKey : NavKey {
    data object Home : SheetKey
    data class Work(val id: Int) : SheetKey
    data class Point(val id: String, val bangumiId: Int) : SheetKey
    data class DroppedPin(val lat: Double, val lng: Double) : SheetKey
    /** 「巡礼记录」列表（已完成地标按作品聚类；本仓库自有功能）。 */
    data object PilgrimageLog : SheetKey
}

/** sheet 的档位（Google Maps 式：一条身份条 / 半开 / 展开）。Home 没有 [Mini]。 */
enum class Detent { Mini, Medium, Expanded }

/**
 * 三档 sheet 的尺寸表（[DetentSheet] 的锚点由 [visibleDp] 换算）。
 *
 * 最早的 SheetHost 用各层**实测**高度驱动锚点，因此需要防抖、settled 门与重设锚点的反馈环，
 * 真机 bug 不断；这里每层的每一档都是**静态**常量或比例 —— 没有实测，也就没有反馈环。
 */
object SheetPeek {

    /** 拖拽把手占的高度（[SheetHandle]）：8dp 上留白 + 4dp 条。 */
    val handleHeight: Dp = 12.dp

    /**
     * Home 在初始档只露出搜索行（Google Maps 式的「地图优先」）。
     * 构成：把手 12 + 卡片顶留白 10 + 搜索行 44 + 行下留白 10 = 76dp；
     * `HomeSheet` 的搜索行或顶留白改了这里要跟着改。
     */
    val homeBar: Dp = 76.dp

    /**
     * 卡片层的迷你档：只露一条身份条。构成与 [homeBar] 相同：
     * 把手 12 + 卡片顶留白 10 + 身份条行高下限 44 + 行下留白 10。
     * 身份条在迷你档单行省略（[LocalSheetCollapsed]），所以这个高度是确定的。
     */
    val miniBar: Dp = 76.dp

    /** 初始档（PartiallyExpanded）占容器高的比例；Home 用 [homeBar] 而不是比例。 */
    fun mediumFraction(key: SheetKey): Float? = when (key) {
        SheetKey.Home -> null
        is SheetKey.Work -> 0.42f
        is SheetKey.Point -> 0.53f // 剧照要完整露出
        is SheetKey.DroppedPin -> 0.22f
        SheetKey.PilgrimageLog -> 0.53f
    }

    /** 展开档（Expanded）占容器高的上限。留出顶部一截地图。 */
    fun expandedFraction(key: SheetKey): Float = when (key) {
        SheetKey.Home, is SheetKey.Work, is SheetKey.Point, SheetKey.PilgrimageLog -> 0.92f
        is SheetKey.DroppedPin -> 0.49f
    }

    /**
     * 某层在某档露出的高度；该层没有这一档则返回 null。
     * - Home 没有 [Detent.Mini]（它的半开档已经只剩搜索行）；
     * - 横屏（[landscape]）容器矮，卡片的半开档只够露半张封面，所以卡片只保留身份条 / 展开两档
     *   （Google Maps 横屏亦如此）。
     * 身份条类的档位含底部导航栏（免得被手势条踩住），比例档不含。
     */
    fun visibleDp(key: SheetKey, detent: Detent, containerHeight: Dp, navBarBottom: Dp, landscape: Boolean = false): Dp? {
        // 比例档在很矮的容器里(分屏、小窗)可能算得比身份条还矮 —— 档位顺序一旦倒置,
        // 「上一档 / 下一档」(按 Detent 顺序)就会反着走。每一档至少比下一档高出 MIN_STEP。
        fun atLeastAbove(lower: Dp, value: Dp): Dp = value.coerceIn(minOf(lower + MIN_STEP, containerHeight), containerHeight)
        return when (detent) {
            Detent.Mini -> if (key == SheetKey.Home) null else miniBar + navBarBottom
            Detent.Medium -> when {
                key == SheetKey.Home -> homeBar + navBarBottom
                landscape -> null
                else -> atLeastAbove(miniBar + navBarBottom, containerHeight * mediumFraction(key)!!)
            }
            Detent.Expanded -> {
                val below = visibleDp(key, Detent.Medium, containerHeight, navBarBottom, landscape)
                    ?: visibleDp(key, Detent.Mini, containerHeight, navBarBottom, landscape)
                    ?: 0.dp
                atLeastAbove(below, containerHeight * expandedFraction(key))
            }
        }
    }

    /** 相邻两档露出高度的最小差。 */
    private val MIN_STEP: Dp = 24.dp

    /** 层切换后落到的档：有半开档就半开，否则（横屏的卡片）展开。 */
    fun restDetent(key: SheetKey, landscape: Boolean): Detent =
        if (landscape && key != SheetKey.Home) Detent.Expanded else Detent.Medium

    /** 地图 padding 的封顶：再高也要给地图留取景窗（真机反馈 C3）。 */
    const val MAP_OBSTRUCTION_CAP = 0.55f

    /** 横屏：卡片以竖屏宽度停靠在左侧。 */
    val landscapeWidth: Dp = 360.dp

    /** sheet 顶端的圆角。 */
    val CORNER_RADIUS: Dp = 20.dp
}

/** 卡片内容可以对 sheet 做的事（搜索聚焦时展开等）。由宿主提供。 */
class SheetActions(
    val expand: () -> Unit,
    val collapse: () -> Unit,
)

val LocalSheetActions = staticCompositionLocalOf<SheetActions?> { null }

/** sheet 正处于迷你档（只露身份条）。卡片头部据此单行省略，让身份条高度确定。 */
val LocalSheetCollapsed = compositionLocalOf { false }

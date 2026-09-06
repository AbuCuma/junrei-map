package cn.anitabi.map.map.google

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Trace
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import cn.anitabi.map.map.engine.DotRenderer
import cn.anitabi.map.map.engine.MapMarkerMetrics
import cn.anitabi.map.map.engine.MapProjection
import cn.anitabi.map.map.engine.MapRevealLadder
import cn.anitabi.map.map.engine.MapScene
import cn.anitabi.map.map.engine.SceneCanvas
import com.google.android.gms.maps.GoogleMap

/**
 * 叠加层里画的选中气球：世界坐标 + 已绘制的位图 + 锚点。
 * 与 `MarkerIconFactory.Rendered` 的锚点语义一致（U/V ∈ [0,1]，尾尖即坐标）。
 */
class BalloonSprite(
    val worldX: Double,
    val worldY: Double,
    val bitmap: Bitmap,
    val anchorU: Float,
    val anchorV: Float,
)

/**
 * 巡礼点圆点层的状态。**只在主线程碰。**
 *
 * 做成 `@Stable` 的持有者而不是给 composable 传一串 lambda，是为了让
 * [PointDotOverlay] 在重组时可跳过 —— `AnitabiMap` 在 sheet 弹簧动画期间**每帧重组**
 * （`contentPadding` 在组合期被读），不该把圆点层也拖下水。
 */
@Stable
class PointDotOverlayState {

    /** 当前这一帧的场景。快照状态：换代时只让**绘制**失效，不触发重组。 */
    var scene by mutableStateOf(MapScene.empty(0.0))
        private set

    /**
     * 选中气球的位图。气球不是 GMS Marker —— 由本层画在圆点**之上**
     * （层序与网页版一致，且没有 Marker 那个 44×54dp 的位图命中框把邻点锁死）。
     * 缩略图后到时由调用方重渲染再 publish 一次。
     */
    var balloon by mutableStateOf<BalloonSprite?>(null)
        private set

    internal var map: GoogleMap? = null

    /**
     * 地图遮挡（sheet padding）的读取口，由 [attach] 时接上。**必须在绘制阶段读**：
     * `GoogleMap.setPadding` 会把相机目标重新锚定到新的 padded 中心 —— 底图内容平移了，
     * 但 `CameraPosition` 一个字段都没变，靠相机失效链（[onCameraChanged]）根本感知不到。
     * 真机 perfetto 实测：sheet 弹簧期间 GL-Map 在渲染平移、主线程在出帧，
     * 而 dotInvalidate 计数器整段平坦、叠加层 120ms+ 不重画 —— 圆点冻在旧位置再跳到位。
     * 在 draw 里读 padding（Compose 快照读）＝ padding 每帧变化自动使绘制失效。
     */
    internal var obstructionProvider: (() -> MapObstruction)? = null

    /**
     * 由调用方在每次相机变化时写入，仅用于让绘制阶段失效。
     * `mutableIntStateOf` 而不是 `mutableStateOf`：这个值每个相机帧都写一次，
     * 装箱的话就是每帧一次分配。
     */
    internal var invalidateToken by mutableIntStateOf(0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE          // drawPoints 只看描边参数
        strokeCap = Paint.Cap.ROUND         // 圆头笔帽 + strokeWidth=直径 ＝ 一个实心圆
    }
    private var scratch = FloatArray(0)
    private val sink = CanvasSceneSink(paint)

    /** [obstruction] 每次调用都要解引用最新的 provider（横竖屏会换 lambda），不能缓存单个实例。 */
    fun attach(map: GoogleMap, obstruction: () -> MapObstruction) {
        this.map = map
        this.obstructionProvider = obstruction
    }

    fun publish(scene: MapScene, balloon: BalloonSprite?) {
        this.scene = scene
        this.balloon = balloon
    }

    /** 只换气球位图（缩略图到货）。校验由调用方做：仍是同一个选中点才调。 */
    fun publishBalloon(balloon: BalloonSprite?) {
        this.balloon = balloon
    }

    /** 相机动了。写一个计数让绘制阶段重跑 —— 数值本身不用。 */
    fun onCameraChanged() {
        invalidateToken++
        // 诊断轨:与 PointDotOverlay.draw 段对照,可看出「失效了但没画」vs「根本没失效」。
        Trace.setCounter("dotInvalidate", invalidateToken.toLong())
    }

    internal fun scratchFor(size: Int): FloatArray {
        if (scratch.size < size) scratch = FloatArray(size)
        return scratch
    }

    internal fun sinkOn(canvas: android.graphics.Canvas, width: Float, height: Float): SceneCanvas =
        sink.also { it.reset(canvas, width, height) }
}

/**
 * 每帧自己投影、自己画圆点。不经过 GMS 的任何图层。
 *
 * 为什么不是瓦片：GMS 只在**整数 zoom** 换瓦片，带内一律拉伸 `2^f`，于是圆点在带内胀 2 倍、
 * 跨带瞬缩 1.9 倍，而且永远在放大位图。光栅瓦片给的是「地理尺寸恒定」，
 * 网页版要的是「屏幕尺寸恒定」。自绘之后半径直接取 [MapMarkerMetrics.dotRadius]（相机 zoom）。
 *
 * **它画在所有 Marker 之上**（Compose 层在地图之上）。被标注压住的点由
 * `MapEngine.scene` 在构建场景时就剔除了 —— 绘制阶段不需要、也不该知道标注的存在。
 */
@Composable
fun PointDotOverlay(state: PointDotOverlayState, modifier: Modifier = Modifier) {
    Spacer(
        modifier.fillMaxSize().drawBehind {
            Trace.beginSection("PointDotOverlay.draw")
            try {
                drawDots(state)
            } finally {
                Trace.endSection()
            }
        }
    )
}

private fun DrawScope.drawDots(state: PointDotOverlayState) {
            // density 取 DrawScope 自己的:manifest 把 density 交给 Activity 自处理,显示大小改变时
            // 这里会以新 density 重绘;若把 density 存进 state,就得跟着重建 state、重新 attach。
            val density = this.density
            val scene = state.scene
            @Suppress("UNUSED_EXPRESSION")
            state.invalidateToken   // 快照读:相机每动一次就让这一段重跑
            // 快照读 padding:setPadding 平移底图但**不改 CameraPosition**,
            // 相机失效链对它是瞎的 —— 不读这一口,sheet 动、地图跟着平移时圆点会冻在原地
            // (真机反馈:缩放+拖 sheet 时圆点滞留旧位置再跳到位)。值本身用不上,
            // 绘制里的锚点每次都向 GMS 实测(toScreenLocation),这里只为建立依赖。
            state.obstructionProvider?.invoke()?.current

            val map = state.map ?: return
            // **数值一律取 GMS 自己的相机**,而不是 cameraPositionState —— 后者是经主线程
            // 回调转手的副本,可能落后一帧。projection 与 cameraPosition 取自同一次读取,
            // 保证与这一帧真正在渲染的变换一致。
            val camera = map.cameraPosition
            val zoom = camera.zoom.toDouble()
            val opacity = MapRevealLadder.dotFieldOpacity(zoom, scene.filtered)
            val balloon = state.balloon
            val drawsDots = !scene.dots.isEmpty && opacity > 0f
            if (!drawsDots && balloon == null) return

            // 原点必须实测:GoogleMap 的 contentPadding 会把相机目标推离视图中心,
            // 而这份 padding 随 sheet 弹簧逐帧变(RootScreen 的 animateDpAsState)。
            // 自己按视图中心推算会永久偏掉,竖屏最多差近三成屏高。
            val anchor = map.projection.toScreenLocation(camera.target)
            val affine = MapProjection.Affine(
                originWorldX = MapProjection.worldX(camera.target.longitude),
                originWorldY = MapProjection.worldY(camera.target.latitude),
                originScreenX = anchor.x.toFloat(),
                originScreenY = anchor.y.toFloat(),
                zoom = zoom,
                bearingDegrees = camera.bearing,
                density = density,
            )

            drawIntoCanvas { canvas ->
                if (drawsDots) {
                    val buffer = state.scratchFor(scene.dots.count * 2)
                    affine.projectAll(scene.dots.world, scene.dots.count, buffer)
                    DotRenderer.draw(
                        field = scene.dots,
                        xy = buffer,
                        radiusPx = (MapMarkerMetrics.dotRadius(zoom) * density).toFloat(),
                        strokePx = (MapMarkerMetrics.dotStrokeWidth(zoom) * density).toFloat(),
                        innerInsetPx = (0.5 * density).toFloat(),
                        opacity = opacity,
                        canvas = state.sinkOn(canvas.nativeCanvas, size.width, size.height),
                    )
                }

                // 气球画在圆点**之后** ＝ 之上。位图是屏幕对齐的（Marker 语义），
                // 只有锚点跟着地图走。
                if (balloon != null) {
                    val x = affine.projectXOf(balloon.worldX, balloon.worldY)
                    val y = affine.projectYOf(balloon.worldX, balloon.worldY)
                    canvas.nativeCanvas.drawBitmap(
                        balloon.bitmap,
                        x - balloon.anchorU * balloon.bitmap.width,
                        y - balloon.anchorV * balloon.bitmap.height,
                        null,
                    )
                }
            }
}

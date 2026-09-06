package cn.anitabi.map.map.google

import android.graphics.Canvas
import android.graphics.Paint
import cn.anitabi.map.map.engine.SceneCanvas
import kotlin.math.roundToInt

/**
 * [SceneCanvas] 到 `android.graphics.Canvas` 的零开销转发。**每帧复用同一个实例**
 * （每帧几百次调用，不能有分配）。
 */
internal class CanvasSceneSink(private val paint: Paint) : SceneCanvas {
    private var canvas: Canvas? = null
    private var width = 0f
    private var height = 0f

    fun reset(canvas: Canvas, width: Float, height: Float) {
        this.canvas = canvas
        this.width = width
        this.height = height
    }

    override fun beginLayer(alpha: Float) {
        canvas?.saveLayerAlpha(0f, 0f, width, height, (alpha * 255).roundToInt())
    }

    override fun endLayer() {
        canvas?.restore()
    }

    override fun dots(xy: FloatArray, offset: Int, count: Int, colorArgb: Int, diameterPx: Float) {
        val target = canvas ?: return
        paint.color = colorArgb
        paint.strokeWidth = diameterPx
        target.drawPoints(xy, offset * 2, count * 2, paint)
    }
}

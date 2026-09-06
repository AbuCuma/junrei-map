package cn.anitabi.map.map.engine

/**
 * 圆点的绘制指令去处。窄到只有三件事，为的是让**绘制顺序本身可以被单测断言**
 * —— 真机上「圆点重叠时白边消失」这种问题，肉眼之外没有第二种发现手段是不行的。
 *
 * 生产实现是对 `android.graphics.Canvas` 的零开销转发（见 `PointDotOverlay`），
 * 测试实现把调用序列记下来。
 */
interface SceneCanvas {
    /** 开一个离屏图层做**整层**不透明度。 */
    fun beginLayer(alpha: Float)

    fun endLayer()

    /**
     * 画一串实心圆。[offset] 与 [count] 以**点**为单位。
     * 生产实现是 `Canvas.drawPoints` + `Style.STROKE` + `Cap.ROUND`
     * （圆头笔帽 ＋ strokeWidth＝直径 ＝ 一个实心圆），一次调用画完整串。
     */
    fun dots(xy: FloatArray, offset: Int, count: Int, colorArgb: Int, diameterPx: Float)
}

/**
 * 把一个 [DotField] 画出来。纯 Kotlin —— 不碰 `android.*`，所以绘制顺序是 JVM 单测。
 *
 * 顺序规则（三条都有单测钉住）：
 * 1. **趟升序**。趟内互不重叠，趟间按序覆盖 ⇒ 与逐点 painter's order 逐像素相同。
 * 2. **趟内先白描边、后填充**。反过来的话后一组的描边会盖住前一组的填充。
 * 3. **整层不透明度只用 [SceneCanvas.beginLayer]**，绝不逐 paint 设 alpha ——
 *    逐 paint 的话白描边先以 α 落下、主题色再以 α 叠上去，每个圆点边缘都会糊出一圈灰晕。
 */
object DotRenderer {

    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * @param xy 已经投影好的屏幕坐标，与 [field] 同序，长度 ≥ `field.count * 2`
     * @param innerInsetPx 内圈相对外圈的内缩量（近白主题色补暗边用）
     */
    fun draw(
        field: DotField,
        xy: FloatArray,
        radiusPx: Float,
        strokePx: Float,
        innerInsetPx: Float,
        opacity: Float,
        canvas: SceneCanvas,
    ) {
        if (field.isEmpty || opacity <= 0f) return
        val layered = opacity < 1f
        if (layered) canvas.beginLayer(opacity)

        val outerDiameter = (radiusPx + strokePx) * 2
        val fillDiameter = radiusPx * 2
        val innerDiameter = (radiusPx - innerInsetPx) * 2

        for (pass in field.passes) {
            canvas.dots(xy, pass.offset, pass.count, WHITE, outerDiameter)
            for (group in pass.groups) {
                canvas.dots(xy, group.offset, group.count, group.outerArgb, fillDiameter)
            }
            if (innerDiameter > 0f) {
                for (group in pass.groups) {
                    if (group.innerArgb == group.outerArgb) continue
                    canvas.dots(xy, group.offset, group.count, group.innerArgb, innerDiameter)
                }
            }
        }

        if (layered) canvas.endLayer()
    }
}

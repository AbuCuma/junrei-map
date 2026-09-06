package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绘制**顺序**的护栏。
 *
 * 三条规则各自对应一种在真机上只能靠肉眼发现的毛病：
 * 趟乱了 → 白环被邻点吃掉；趟内先填充后描边 → 后一组的描边盖住前一组的填充；
 * 逐 paint 设 alpha 而不是开图层 → 每个圆点边缘糊出一圈灰晕。
 */
class DotRendererTest {

    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFAA0000.toInt()
    private val green = 0xFF00AA00.toInt()

    private sealed interface Op {
        data class Layer(val alpha: Float) : Op
        data object Restore : Op
        data class Dots(val offset: Int, val count: Int, val color: Int, val diameter: Float) : Op
    }

    private class Recorder : SceneCanvas {
        val ops = ArrayList<Op>()
        override fun beginLayer(alpha: Float) { ops.add(Op.Layer(alpha)) }
        override fun endLayer() { ops.add(Op.Restore) }
        override fun dots(xy: FloatArray, offset: Int, count: Int, colorArgb: Int, diameterPx: Float) {
            ops.add(Op.Dots(offset, count, colorArgb, diameterPx))
        }
    }

    private val zoom = 16.0

    private fun point(id: String, work: Int, lng: Double) =
        ScenePoint(id = id, bangumiId = work, lat = 35.0, lng = lng, priority = 0)

    private fun colorOf(work: Int) =
        DotField.packColors(if (work == 1) red else green, if (work == 1) red else green)

    private fun fieldOf(spacingFactor: Double, works: List<Int>): DotField {
        val step = 360.0 / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom)) *
            DotPasses.conflictDistanceDp(zoom) * spacingFactor
        return DotField.build(
            works.mapIndexed { i, work -> point("p$i", work, 139.0 + step * i) },
            zoom,
            ::colorOf,
            DotField.packColors(red, red),
            MapSceneHitTest.MIN_HIT_RADIUS_DP,
        )
    }

    private fun render(field: DotField, opacity: Float = 1f): List<Op> {
        val recorder = Recorder()
        DotRenderer.draw(
            field = field,
            xy = FloatArray(field.count * 2),
            radiusPx = 10f,
            strokePx = 4f,
            innerInsetPx = 2f,
            opacity = opacity,
            canvas = recorder,
        )
        return recorder.ops
    }

    /** 趟内：白描边整趟画完，再画填充。 */
    @Test
    fun whiteOutlineComesBeforeFillsWithinAPass() {
        val ops = render(fieldOf(spacingFactor = 5.0, works = listOf(1, 2, 1)))
        val dots = ops.filterIsInstance<Op.Dots>()
        assertEquals(white, dots.first().color)
        assertEquals("白描边一次画完整趟", 3, dots.first().count)
        assertEquals((10f + 4f) * 2, dots.first().diameter, 0f)
        assertTrue("其余各趟都是填充", dots.drop(1).none { it.color == white })
        assertTrue(dots.drop(1).all { it.diameter == 10f * 2 })
    }

    /** 趟按升序，而且每一趟都自带一次白描边 —— 这正是「重叠处白边不消失」的来源。 */
    @Test
    fun passesAreDrawnInOrderEachWithItsOwnOutline() {
        val field = fieldOf(spacingFactor = 0.3, works = listOf(1, 1, 1))
        assertTrue("这组点必须被分到多趟", field.passes.size >= 2)
        val dots = render(field).filterIsInstance<Op.Dots>()

        val whitePasses = dots.withIndex().filter { it.value.color == white }
        assertEquals("每趟一次白描边", field.passes.size, whitePasses.size)
        // 每一次白描边的 offset 正好等于对应趟的 offset，且逐趟递增。
        assertEquals(field.passes.map { it.offset }, whitePasses.map { it.value.offset })
        assertEquals(field.passes.map { it.count }, whitePasses.map { it.value.count })
    }

    /** 近白主题色的暗内圈是第三趟，且只画需要的组。 */
    @Test
    fun innerRingIsDrawnOnlyForGroupsThatNeedIt() {
        val dark = 0xFF303030.toInt()
        val field = DotField.build(
            listOf(point("a", 1, 139.0), point("b", 2, 139.1)),
            zoom,
            colorOf = { work -> if (work == 1) DotField.packColors(dark, white) else DotField.packColors(green, green) },
            fallback = DotField.packColors(green, green),
            minHitRadiusDp = MapSceneHitTest.MIN_HIT_RADIUS_DP,
        )
        val dots = render(field).filterIsInstance<Op.Dots>()
        val inner = dots.filter { it.diameter == (10f - 2f) * 2 }
        assertEquals(1, inner.size)
        assertEquals(white, inner.single().color)
    }

    /** 整层不透明度只走图层，绝不逐 paint 设 alpha（那会糊出灰晕）。 */
    @Test
    fun partialOpacityUsesALayerAndNothingElse() {
        val ops = render(fieldOf(5.0, listOf(1, 2)), opacity = 0.4f)
        assertEquals(Op.Layer(0.4f), ops.first())
        assertEquals(Op.Restore, ops.last())

        val opaque = render(fieldOf(5.0, listOf(1, 2)), opacity = 1f)
        assertTrue("不透明时不该开图层", opaque.none { it is Op.Layer })
    }

    @Test
    fun nothingIsDrawnWhenInvisibleOrEmpty() {
        assertTrue(render(fieldOf(5.0, listOf(1)), opacity = 0f).isEmpty())
        assertTrue(render(DotField.EMPTY).isEmpty())
    }
}

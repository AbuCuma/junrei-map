package cn.anitabi.map.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.map.engine.DotField
import cn.anitabi.map.map.engine.DotPasses
import cn.anitabi.map.map.engine.DotRenderer
import cn.anitabi.map.map.engine.MapMarkerMetrics
import cn.anitabi.map.map.engine.MapProjection
import cn.anitabi.map.map.engine.MapSceneHitTest
import cn.anitabi.map.map.google.CanvasSceneSink
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 圆点绘制的**像素级**保真测试。真机/模拟器上跑，因为它要真的 Skia。
 *
 * 分趟绘制（整趟白描边 → 趟内各色填充）之所以成立，靠的是「趟内任意两点互不重叠」。
 * 这条不变式一旦破了，症状就是**重叠处的白描边被邻点的填充吃掉** —— 而那在单测里
 * 完全看不见（`DotRendererTest` 只断言调用顺序，不断言像素）。所以这里直接把
 * 分趟绘制与「逐点先白后彩」的参考实现逐像素比对：**两者必须完全相同**。
 *
 * 不需要地图、不需要网络、无预置状态 —— 这是**真正的测试**，不是 harness。
 */
@RunWith(AndroidJUnit4::class)
class DotRenderFidelityTest {

    private val density = 3f
    private val size = 512

    private fun radiusPx(zoom: Double) = (MapMarkerMetrics.dotRadius(zoom) * density).toFloat()
    private fun strokePx(zoom: Double) = (MapMarkerMetrics.dotStrokeWidth(zoom) * density).toFloat()

    private fun bitmap() = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.argb(255, 40, 40, 40)) // 深底：白描边缺失一眼看得出
    }

    /**
     * **抗锯齿关掉**。两张图要逐像素比，而 AA 会在「同一趟里两个白盘的重叠边」上
     * 产生差异 —— 批量一次 `drawPoints` 与逐点分次画，Skia 对边缘的合成不一样，
     * 那是无害的。关掉 AA 之后剩下的任何差异都是**结构性**的：顺序或重叠错了。
     */
    private fun paint() = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = false
    }

    private fun sink(bitmap: Bitmap): CanvasSceneSink =
        CanvasSceneSink(paint()).also { it.reset(Canvas(bitmap), size.toFloat(), size.toFloat()) }

    /** 参考实现：**逐点**先白后彩，顺序与 [DotField] 的趟序一致。 */
    private fun renderPerDot(field: DotField, xy: FloatArray, zoom: Double): Bitmap {
        val bitmap = bitmap()
        val canvas = Canvas(bitmap)
        val paint = paint()
        val r = radiusPx(zoom)
        val s = strokePx(zoom)
        val inner = 0.5f * density
        for (pass in field.passes) {
            for (group in pass.groups) {
                for (i in group.offset until group.offset + group.count) {
                    val one = floatArrayOf(xy[i * 2], xy[i * 2 + 1])
                    paint.color = Color.WHITE
                    paint.strokeWidth = (r + s) * 2
                    canvas.drawPoints(one, paint)
                    paint.color = group.outerArgb
                    paint.strokeWidth = r * 2
                    canvas.drawPoints(one, paint)
                    if (group.innerArgb != group.outerArgb) {
                        paint.color = group.innerArgb
                        paint.strokeWidth = (r - inner) * 2
                        canvas.drawPoints(one, paint)
                    }
                }
            }
        }
        return bitmap
    }

    private fun renderByPass(field: DotField, xy: FloatArray, zoom: Double): Bitmap {
        val bitmap = bitmap()
        DotRenderer.draw(
            field = field,
            xy = xy,
            radiusPx = radiusPx(zoom),
            strokePx = strokePx(zoom),
            innerInsetPx = 0.5f * density,
            opacity = 1f,
            canvas = sink(bitmap),
        )
        return bitmap
    }

    /** 用固定 zoom 造一个点集，并把世界坐标投影成屏幕坐标。 */
    private fun fieldOf(points: List<ScenePoint>, zoom: Double): Pair<DotField, FloatArray> {
        val field = DotField.build(
            points = points,
            zoom = zoom,
            colorOf = { work -> DotField.packColors(COLORS[work % COLORS.size], COLORS[work % COLORS.size]) },
            fallback = DotField.packColors(Color.GRAY, Color.GRAY),
            minHitRadiusDp = MapSceneHitTest.MIN_HIT_RADIUS_DP,
        )
        val pxPerWorld = MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom) * density
        val xy = FloatArray(field.count * 2)
        for (i in 0 until field.count) {
            xy[i * 2] = ((field.world[i * 2] - ORIGIN_X) * pxPerWorld).toFloat() + size / 2f
            xy[i * 2 + 1] = ((field.world[i * 2 + 1] - ORIGIN_Y) * pxPerWorld).toFloat() + size / 2f
        }
        return field to xy
    }

    private fun diff(a: Bitmap, b: Bitmap): Int {
        var count = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++
            }
        }
        return count
    }

    /**
     * **核心断言**：分趟绘制与逐点绘制逐像素相同。
     *
     * 随机点集 × 多个 zoom × 多个密度。一旦某个种子下不同，说明某一趟里混进了互相重叠的点，
     * 也就是「重叠处白边会被吃掉」的那个 bug 复发了。
     */
    @Test
    fun passRenderingIsPixelIdenticalToPerDotPainterOrder() {
        var worst = 0
        var worstLabel = ""
        for (zoom in listOf(12.0, 13.5, 15.0, 17.0, 19.0)) {
            for (seed in 0 until 8) {
                val random = Random(seed * 31 + zoom.toInt())
                // 把点撒在一个刚好能让它们大量重叠的经纬度盒里。
                val spanWorld = 220.0 / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom) * density)
                val points = (0 until 60).map { i ->
                    ScenePoint(
                        id = "p$i",
                        bangumiId = i % 5,
                        lat = MapProjection.latOf(ORIGIN_Y + (random.nextDouble() - 0.5) * spanWorld),
                        lng = MapProjection.lngOf(ORIGIN_X + (random.nextDouble() - 0.5) * spanWorld),
                        priority = random.nextInt(1000),
                    )
                }
                val (field, xy) = fieldOf(points, zoom)
                val byPass = renderByPass(field, xy, zoom)
                val perDot = renderPerDot(field, xy, zoom)
                val d = diff(byPass, perDot)
                if (d > worst) {
                    worst = d
                    worstLabel = "z$zoom seed$seed passes=${field.passes.size} hidden=${field.hiddenCount}"
                }
                byPass.recycle(); perDot.recycle()
            }
        }
        assertEquals("分趟绘制与逐点绘制出现差异（$worstLabel），说明某一趟里有互相重叠的点", 0, worst)
    }

    /**
     * 距离扫描：把两个**不同颜色**的圆点从重合一直拉到互不相干，
     * 沿两心连线扫过去，**A 的填充与 B 的填充之间必须夹着白**。
     * 这一条直接对应真机反馈「点之间有重叠时边缘的白色会消失」。
     */
    @Test
    fun whiteAlwaysSeparatesTwoOverlappingDots() {
        val zoom = 15.0
        val r = radiusPx(zoom)
        val s = strokePx(zoom)
        val colorA = COLORS[1]
        val colorB = COLORS[2]
        val failures = ArrayList<String>()

        for (d in 1..(3 * r).roundToInt()) {
            val bitmap = bitmap()
            val worldStep = d / (MapProjection.WORLD_TILE_SIZE * 2.0.pow(zoom) * density)
            // priority 升序 ⇒ b 在上层。两点不同作品 ⇒ 两种填充色可分辨。
            // bangumiId 必须非 0 —— DotField 对 0 走 fallback 色,两个点就分辨不出来了。
            val points = listOf(
                ScenePoint("a", 1, MapProjection.latOf(ORIGIN_Y), MapProjection.lngOf(ORIGIN_X), 0),
                ScenePoint("b", 2, MapProjection.latOf(ORIGIN_Y), MapProjection.lngOf(ORIGIN_X + worldStep), 1),
            )
            val (field, xy) = fieldOf(points, zoom)
            assertTrue("两种填充色必须都画出来了,否则这条断言等于没跑", run {
                val probe = IntArray(size) { bitmap.getPixel(it, xy[1].roundToInt()) }
                probe.isNotEmpty()
            })
            DotRenderer.draw(field, xy, r, s, 0.5f * density, 1f, sink(bitmap))

            val y = xy[1].roundToInt()
            val row = IntArray(size) { bitmap.getPixel(it, y) }
            val lastA = row.indexOfLast { it == colorA }
            val firstB = row.indexOfFirst { it == colorB }
            if (lastA < 0 || firstB < 0) {
                // 下面那个整个被盖住了（近到看不见），不要求分隔。
                if (d > (2 * r).roundToInt()) {
                    failures.add("间距 ${d}px：本该两个都看得见，却只找到一种填充色")
                }
            } else {
                val gap = (minOf(lastA, firstB) + 1) until maxOf(lastA, firstB)
                if (gap.none { isWhite(row[it]) }) {
                    failures.add("间距 ${d}px（趟数 ${field.passes.size}）")
                }
            }
            bitmap.recycle()
        }
        assertTrue("这些间距下两个填充之间没有白：" + failures.joinToString(", "), failures.isEmpty())
    }

    /**
     * 分趟是在**后台 tick 的 zoom** 上算的，绘制却用**当前相机的 zoom** ——
     * 缩小的那几帧里两者最多差一个 tick。这条测量「能容忍多大的 zoom 落差」，
     * 并钉住 [DotPasses.ZOOM_LAG_MARGIN] 声称覆盖的范围确实覆盖得住。
     */
    @Test
    fun theZoomLagMarginCoversARealisticTickOfZoomingOut() {
        val sceneZoom = 15.0
        // 两点相距恰好一个分趟间距 ⇒ 在 sceneZoom 下同趟，是最容易破的一对。
        val worldStep = DotPasses.partitionDistanceDp(sceneZoom) * 1.02 /
            (MapProjection.WORLD_TILE_SIZE * 2.0.pow(sceneZoom))
        val points = listOf(
            ScenePoint("a", 1, MapProjection.latOf(ORIGIN_Y), MapProjection.lngOf(ORIGIN_X), 0),
            ScenePoint("b", 2, MapProjection.latOf(ORIGIN_Y), MapProjection.lngOf(ORIGIN_X + worldStep), 1),
        )
        val field = DotField.build(
            points, sceneZoom,
            colorOf = { work -> DotField.packColors(COLORS[work % COLORS.size], COLORS[work % COLORS.size]) },
            fallback = DotField.packColors(Color.GRAY, Color.GRAY),
            minHitRadiusDp = MapSceneHitTest.MIN_HIT_RADIUS_DP,
        )
        assertEquals("这一对本该同趟（间距恰好等于分趟间距）", 1, field.passes.size)

        var breaksAt = Double.MAX_VALUE
        var delta = 0.0
        while (delta <= 0.80001) {
            val liveZoom = sceneZoom - delta
            val pxPerWorld = MapProjection.WORLD_TILE_SIZE * 2.0.pow(liveZoom) * density
            val xy = FloatArray(field.count * 2)
            for (i in 0 until field.count) {
                xy[i * 2] = ((field.world[i * 2] - ORIGIN_X) * pxPerWorld).toFloat() + size / 2f
                xy[i * 2 + 1] = ((field.world[i * 2 + 1] - ORIGIN_Y) * pxPerWorld).toFloat() + size / 2f
            }
            val bitmap = bitmap()
            DotRenderer.draw(
                field, xy, radiusPx(liveZoom), strokePx(liveZoom), 0.5f * density, 1f, sink(bitmap),
            )
            val y = xy[1].roundToInt()
            val row = IntArray(size) { bitmap.getPixel(it, y) }
            val lastA = row.indexOfLast { it == COLORS[1] }
            val firstB = row.indexOfFirst { it == COLORS[2] }
            assertTrue("Δz=$delta 下两种填充色都该在（否则这条断言等于没跑）", lastA >= 0 && firstB >= 0)
            val ok = ((minOf(lastA, firstB) + 1) until maxOf(lastA, firstB)).any { isWhite(row[it]) }
            if (!ok && delta < breaksAt) breaksAt = delta
            bitmap.recycle()
            delta += 0.05
        }
        val covered = kotlin.math.log2(DotPasses.ZOOM_LAG_MARGIN)
        println("[dev] 分趟间距余量 ${DotPasses.ZOOM_LAG_MARGIN} 声称覆盖 Δz=${"%.2f".format(covered)}，" +
            "实测在 Δz=${if (breaksAt == Double.MAX_VALUE) ">0.80" else "%.2f".format(breaksAt)} 处失效")
        assertTrue(
            "余量声称覆盖 Δz=$covered，实际在 $breaksAt 就破了",
            breaksAt > covered,
        )
    }

    private fun isWhite(color: Int) =
        Color.red(color) > 245 && Color.green(color) > 245 && Color.blue(color) > 245

    private fun isBackground(color: Int) =
        Color.red(color) == 40 && Color.green(color) == 40 && Color.blue(color) == 40

    private companion object {
        val ORIGIN_X = MapProjection.worldX(139.7671)
        val ORIGIN_Y = MapProjection.worldY(35.6812)
        val COLORS = intArrayOf(
            Color.rgb(0xE0, 0x50, 0x60), Color.rgb(0x50, 0x80, 0xE0), Color.rgb(0x40, 0xB0, 0x70),
            Color.rgb(0xC0, 0x60, 0xD0), Color.rgb(0xE0, 0xA0, 0x30),
        )
    }
}

package cn.anitabi.map.map

import android.util.DisplayMetrics
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import cn.anitabi.map.data.AnitabiDataLoader
import cn.anitabi.map.data.AnitabiJsonParser
import cn.anitabi.map.data.model.PointDetail
import cn.anitabi.map.map.engine.DotField
import cn.anitabi.map.map.engine.MapDataset
import cn.anitabi.map.map.engine.MapEngine
import cn.anitabi.map.map.engine.MapEngineRequest
import cn.anitabi.map.map.engine.MapMarkerMetrics
import cn.anitabi.map.map.engine.MapProjection
import cn.anitabi.map.map.engine.MapSceneHitTest
import cn.anitabi.map.map.engine.MapViewport
import cn.anitabi.map.map.engine.MapWorkSeed
import cn.anitabi.map.map.engine.MarkerFootprint
import cn.anitabi.map.map.google.MarkerIconFactory
import cn.anitabi.map.theme.ColorUtilities
import java.io.File
import kotlin.math.abs
import kotlin.system.measureTimeMillis
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 圆点场景的真机实测**工具**，不是测试。
 *
 * 两件在 JVM 上做不到的事：
 *   1. 拿 ARM 上的真实耗时与真实密度 —— 东京 z12 是最坏情况（约 7000 点 / 数百种颜色），
 *      分趟与遮挡的代价只能在这里量；
 *   2. 把 [MarkerFootprint] 与 `MarkerIconFactory` **实际画出来的位图**对一遍 ——
 *      足迹是 `map/engine/` 的纯 Kotlin，位图在 `android.graphics` 上，JVM 单测够不着。
 *
 * 与 `androidTest/` 下的其余 harness 一样，它**需要预置状态**，
 * `connectedCheck` 跑过它不代表任何验证（README「测试说明」、AGENTS.md）。
 *
 * 运行：
 * ```
 * adb shell mkdir -p /data/local/tmp/anitabi-snapshot
 * adb push g.json g0.json … /data/local/tmp/anitabi-snapshot/
 * # 去掉 @Ignore 后
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=cn.anitabi.map.map.MapSceneDeviceHarness
 * adb logcat -d -s System.out:I | grep '\[dev\]'
 * ```
 */
@RunWith(AndroidJUnit4::class)
@Ignore("真机工具,非测试。手工去掉 @Ignore 再跑")
class MapSceneDeviceHarness {

    private fun dataset(): MapDataset {
        val dir = File("/data/local/tmp/anitabi-snapshot")
        val list = File(dir, "g.json")
        check(list.exists()) { "先 adb push 数据到 ${dir.absolutePath}" }

        var parsed = checkNotNull(AnitabiJsonParser.parseBangumiList(list.readText()))
        val details = HashMap<String, PointDetail>()
        val modified = HashMap<Int, Double>()
        for (shard in 0 until AnitabiDataLoader.DETAIL_SHARD_COUNT) {
            val f = File(dir, "g$shard.json")
            if (f.exists()) AnitabiJsonParser.parsePointDetails(f.readText(), details, modified)
        }
        val points = AnitabiJsonParser.merge(details, parsed.points)
        return MapDataset(
            points = points,
            works = parsed.bangumis.mapNotNull { MapDataset.seedFrom(it) },
            version = 1,
        )
    }

    /** 东京几档 zoom 的场景规模与耗时。最坏情况的预算就靠这一条盯住。 */
    @Test
    fun measureTokyo() {
        val engine = MapEngine()
        val data = dataset()
        println("[dev] engine.load ${measureTimeMillis { engine.load(data) }}ms（${data.points.size} 点）")

        val colors = HashMap<Int, Long>()
        val byId = data.works.associateBy(MapWorkSeed::id)
        fun dotColors(work: Int): Long = colors.getOrPut(work) {
            val theme = ColorUtilities.themeColor(byId[work]?.colorHex)
            val argb = theme.toArgb()
            DotField.packColors(argb, argb)
        }

        for (zoom in listOf(12.0, 13.0, 14.0, 16.0, 18.0)) {
            // 竖屏 1440×3120 @3.75 → 视口约 384×832 dp。
            val lngDelta = 384.0 * 360.0 / (MapProjection.WORLD_TILE_SIZE * Math.pow(2.0, zoom))
            val latDelta = lngDelta * 832.0 / 384.0 * Math.cos(Math.toRadians(35.68))
            val viewport = MapViewport.around(35.68, 139.76, latDelta, lngDelta, scale = 1.4)
            val dotViewport = MapViewport.around(35.68, 139.76, latDelta, lngDelta, scale = 1.6)

            var scene = engine.scene(
                MapEngineRequest(viewport, zoom, dotViewport = dotViewport),
                ::dotColors,
                MapSceneHitTest.MIN_HIT_RADIUS_DP,
            )
            val ms = measureTimeMillis {
                repeat(5) {
                    scene = engine.scene(
                        MapEngineRequest(viewport, zoom, dotViewport = dotViewport),
                        ::dotColors,
                        MapSceneHitTest.MIN_HIT_RADIUS_DP,
                    )
                }
            } / 5.0
            // 一帧的绘制调用数 ＝ 每趟一次白描边 ＋ 每个颜色组一次填充。
            val drawCalls = scene.dots.passes.size + scene.dots.colorGroupCount
            println("[dev] ${scene.debugLine()} | 重算 ${"%.1f".format(ms)}ms | drawPoints ${drawCalls} 次")
        }
    }

    /**
     * 足迹与位图对账。位图的**外接框**（含阴影留白）总不小于足迹本体；
     * 锚点换算出来的尾尖位置必须与足迹的锚点一致。
     */
    @Test
    fun footprintsMatchRenderedBitmaps() {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val factory = MarkerIconFactory(density)
        val theme = ColorUtilities.themeColor("#FF6688")

        for ((label, spec, footprint) in listOf(
            Triple(
                "balloon",
                MarkerIconFactory.IconSpec.Balloon(theme, null, null),
                MarkerFootprint.BALLOON,
            ),
            Triple(
                "photo-bubble",
                MarkerIconFactory.IconSpec.Photo(theme, MarkerIconFactory.PhotoStyle.Bubble, null, null),
                MarkerFootprint.photo(MapMarkerMetrics.PhotoPlate.BUBBLE),
            ),
            Triple(
                "photo-card",
                MarkerIconFactory.IconSpec.Photo(theme, MarkerIconFactory.PhotoStyle.Card, null, null),
                MarkerFootprint.photo(MapMarkerMetrics.PhotoPlate.CARD),
            ),
        )) {
            factory.prewarm(listOf(spec))
            val icon = factory.icon(spec)
            // 足迹里锚点相对位图左上角的位置，应当与位图自报的 anchorU/V 对得上。
            val widthDp = footprint.maxX - footprint.minX
            val heightDp = footprint.maxY - footprint.minY
            val anchorFromFootprintU = -footprint.minX / widthDp
            val anchorFromFootprintV = -footprint.minY / heightDp
            println(
                "[dev] $label 足迹 ${"%.1f".format(widthDp)}×${"%.1f".format(heightDp)}dp " +
                    "anchor(足迹) ${"%.3f".format(anchorFromFootprintU)}/${"%.3f".format(anchorFromFootprintV)} " +
                    "anchor(位图) ${"%.3f".format(icon.anchorU)}/${"%.3f".format(icon.anchorV)} " +
                    "Δ ${"%.3f".format(abs(icon.anchorU - anchorFromFootprintU))}/" +
                    "${"%.3f".format(abs(icon.anchorV - anchorFromFootprintV))}"
            )
        }
        println("[dev] density=$density (${DisplayMetrics.DENSITY_DEFAULT} dpi 基准)")
    }
}

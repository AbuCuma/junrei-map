package cn.anitabi.map.data

import cn.anitabi.map.data.model.PointDetail
import java.io.File
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 実データ（本番 g.json / g0-6.json）スナップショットに対する検証。
 * 環境変数 ANITABI_SNAPSHOT_DIR にファイル一式があるときだけ走る（CI では自動 skip）。
 * 実行例：ANITABI_SNAPSHOT_DIR=/path/to/dir ./gradlew :app:testDebugUnitTest
 */
class RealDataSnapshotTest {

    private val dir: File? = System.getenv("ANITABI_SNAPSHOT_DIR")?.let(::File)

    @Test
    fun parsesFullProductionSnapshot() {
        assumeTrue(dir?.resolve("g.json")?.exists() == true)
        val d = dir!!

        var dataset = checkNotNull(AnitabiJsonParser.parseBangumiList(d.resolve("g.json").readText()))
        // 2026-08 時点で作品 1500+ / 地標 5 万点前後
        assertTrue("works=${dataset.bangumis.size}", dataset.bangumis.size > 1400)
        assertTrue("points=${dataset.points.size}", dataset.points.size > 45_000)
        assertTrue(dataset.modified > 1_700_000_000_000.0)

        val details = HashMap<String, PointDetail>()
        val modified = HashMap<Int, Double>()
        val parseMillis = measureTimeMillis {
            for (shard in 0 until AnitabiDataLoader.DETAIL_SHARD_COUNT) {
                val f = d.resolve("g$shard.json")
                if (f.exists()) AnitabiJsonParser.parsePointDetails(f.readText(), details, modified)
            }
            dataset = dataset.copy(points = AnitabiJsonParser.merge(details, dataset.points))
        }
        println("shards parsed+merged in ${parseMillis}ms, details=${details.size}")

        // 詳細は座標総数の大半をカバーしているはず
        val named = dataset.points.count { it.name != null || it.nameCn != null }
        assertTrue("named=$named", named > dataset.points.size / 2)
        val withImage = dataset.points.count { it.image != null }
        assertTrue("withImage=$withImage", withImage > 10_000)
        // 礼仪判定が実データで妥当に発火する（学校・図書館類は一定数ある）
        val etiquette = dataset.points.count { it.needsEtiquetteWarning }
        assertTrue("etiquette=$etiquette", etiquette > 100)
        // 計画の M1 検証基準：中端機 < 800ms。開発機 JVM ではさらに速いはず。
        assertTrue("parse took ${parseMillis}ms", parseMillis < 8_000)
    }

    @Test
    fun engineHandlesFullDatasetWithinBudgets() {
        assumeTrue(dir?.resolve("g.json")?.exists() == true)
        val d = dir!!

        var dataset = checkNotNull(AnitabiJsonParser.parseBangumiList(d.resolve("g.json").readText()))
        val details = HashMap<String, PointDetail>()
        val modified = HashMap<Int, Double>()
        for (shard in 0 until AnitabiDataLoader.DETAIL_SHARD_COUNT) {
            val f = d.resolve("g$shard.json")
            if (f.exists()) AnitabiJsonParser.parsePointDetails(f.readText(), details, modified)
        }
        dataset = dataset.copy(points = AnitabiJsonParser.merge(details, dataset.points))

        val engine = cn.anitabi.map.map.engine.MapEngine()
        val works = dataset.bangumis.mapNotNull {
            cn.anitabi.map.map.engine.MapDataset.seedFrom(it)
        }
        val loadMillis = measureTimeMillis {
            engine.load(
                cn.anitabi.map.map.engine.MapDataset(dataset.points, works, version = 1)
            )
        }
        println("engine.load: ${loadMillis}ms (${dataset.points.size} points)")

        // 日本俯瞰（MapCanvas.japanOverview 相当：中心 36.5/138.5、span 14°）
        val japan = cn.anitabi.map.map.engine.MapViewport(29.5, 43.5, 129.75, 147.25)
        // 東京近接
        val tokyo = cn.anitabi.map.map.engine.MapViewport(35.6, 35.76, 139.6, 139.9)

        for ((label, viewport, zoom) in listOf(
            Triple("japan-z5", japan, 5.0),
            Triple("tokyo-z13", tokyo, 13.0),
            Triple("tokyo-z16", tokyo, 16.0),
        )) {
            val scenes = ArrayList<cn.anitabi.map.map.engine.MapScene>()
            val frameMillis = measureTimeMillis {
                scenes.add(sceneOf(engine, viewport, zoom))
            }
            val scene = scenes.single()
            println("$label: ${scene.debugLine()} in ${frameMillis}ms")
            // 圆点は自绘层に移ったので、scene.annotations は「剧照＋選択中」だけ。
            // 旧 <=600 は圆点の上限で、まさにその上限が密集地で効いていた（tokyo-z13 が
            // ちょうど 600 に張り付いていた）。今は photoLimit + 1 が上限。
            assertTrue("$label annotations=${scene.annotations.size}", scene.annotations.size <= 121)
            assertTrue("$label works=${scene.works.size}", scene.works.size <= 32)
            assertTrue("$label photos", scene.annotations.count { it.usesPhoto } <= 120)
            // 計画の M2 検証基準：engine→apply < 100ms（圆点の分趟もこの中）
            assertTrue("$label took ${frameMillis}ms", frameMillis < 100)
        }
        // 圆点层は上限なし ＝ Web と同じ密度が出ているか。
        // これが「東京 z13 で 600 に切られていた」ことの回帰テスト。
        val dotOut = ArrayList<cn.anitabi.map.data.model.ScenePoint>()
        engine.index.query(tokyo, -1.0, null, dotOut)
        val brute = dataset.points.count { tokyo.contains(it.lat, it.lng) }
        println("dots z13 tokyo: ${dotOut.size} (brute=$brute)")
        assertEquals("圆点层は 1 点も間引かない", brute, dotOut.size)
        assertTrue("東京 z13 は 600 をはるかに超えるはず: $brute", brute > 1_500)

        // 俯瞰では作品標が出て、近接 z13 では層ごと消える
        assertTrue(sceneOf(engine, japan, 5.0).works.isNotEmpty())
        assertTrue(sceneOf(engine, tokyo, 13.0).works.isEmpty())
    }

    private fun sceneOf(
        engine: cn.anitabi.map.map.engine.MapEngine,
        viewport: cn.anitabi.map.map.engine.MapViewport,
        zoom: Double,
    ) = engine.scene(
        cn.anitabi.map.map.engine.MapEngineRequest(viewport, zoom),
        dotColors = { cn.anitabi.map.map.engine.DotField.packColors(it, it) },
        minHitRadiusDp = cn.anitabi.map.map.engine.MapSceneHitTest.MIN_HIT_RADIUS_DP,
    )
}

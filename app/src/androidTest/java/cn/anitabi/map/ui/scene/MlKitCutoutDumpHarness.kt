package cn.anitabi.map.ui.scene

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 抠图比較用ダンプ（ISNet-Anime spike）。/sdcard/Download/cutout_src 内の jpg を ML Kit で抠いて
 * /sdcard/Download/cutout_mlkit の png に書く。製品コードは触らない。
 */
@RunWith(AndroidJUnit4::class)
/**
 * 注意:这是真机基准/调试**工具**,不是测试 —— 无断言,需要手工在设备上预置文件,
 * `connectedCheck` 跑过它不代表任何验证(README「测试说明」)。
 */
// 标 @Ignore 让 connectedCheck 恢复意义 —— 它此前"通过"并不证明任何事。
// 要跑:./gradlew :app:connectedDebugAndroidTest \
//   -Pandroid.testInstrumentationRunnerArguments.class=…MlKitCutoutDumpHarness
@Ignore("手动工具,不是测试:无断言,把 ML Kit 抠图结果导成 PNG 供人眼比对。")
class MlKitCutoutDumpHarness {
    @Test
    fun dump() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val src = File(base, "cutout_src")
        val out = File(base, "cutout_mlkit").apply { mkdirs() }
        val results = Bundle()
        for (f in src.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.sortedBy { it.name }) {
            val bitmap = BitmapFactory.decodeFile(f.path)
            val t = System.nanoTime()
            val cutout = SubjectExtractor.extract(bitmap)
            val ms = (System.nanoTime() - t) / 1e6
            if (cutout == null) {
                results.putString(f.name, "null")
                continue
            }
            FileOutputStream(File(out, f.name.replace(".jpg", ".png"))).use {
                cutout.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            results.putString(f.name, "coverage=${cutout.coverage} ms=$ms size=${cutout.bitmap.width}x${cutout.bitmap.height}")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, results)
    }
}

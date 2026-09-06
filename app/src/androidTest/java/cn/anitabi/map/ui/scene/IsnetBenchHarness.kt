package cn.anitabi.map.ui.scene

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISNet-Anime の実機実測 spike（製品コードは触らない）。
 * 外部 files/isnet 配下の onnx を、ファイル名の接頭辞で EP を振り分けて測る：
 *   htp_*.onnx → QNN HTP（burst）、cpu_*.onnx → CPU EP 4 threads。
 * HTP では ep.context_enable でコンテキストバイナリも生成し、それを再ロードした時間も測る。
 * 結果は Bundle で返し、出力 PNG は files/cutout_<model>/ に保存する。
 */
@RunWith(AndroidJUnit4::class)
/**
 * 注意:这是真机基准/调试**工具**,不是测试 —— 无断言,需要手工在设备上预置文件,
 * `connectedCheck` 跑过它不代表任何验证(README「测试说明」)。
 */
// 标 @Ignore 让 connectedCheck 恢复意义 —— 它此前"通过"并不证明任何事。
// 要跑:./gradlew :app:connectedDebugAndroidTest \
//   -Pandroid.testInstrumentationRunnerArguments.class=…IsnetBenchHarness
@Ignore("手动工具,不是测试:无断言,需要预先把 .onnx 推到设备外部文件目录,且只在高通设备上有意义。")
class IsnetBenchHarness {

    private var backendPath: String? = null
    private var perfMode: String? = null
    private val size = 1024
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)

    @Test
    fun bench() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val base = ctx.getExternalFilesDir(null)!!
        val results = Bundle()
        val images = File(base, "cutout_src").listFiles().orEmpty()
            .filter { it.name.endsWith(".jpg") }.sortedBy { it.name }.take(4)
        val models = File(base, "isnet").listFiles().orEmpty()
            .filter { it.name.endsWith(".onnx") && !it.name.endsWith("_ctx.onnx") }.sortedBy { it.name }

        // QNN の skel は DSP 側が ADSP_LIBRARY_PATH から探す（アプリ側 FastRPC が open して渡す）。
        // -e adsp testapk → テスト APK の lib dir（= 本番で APK に同梱した場合）
        // -e adsp filesdir → filesDir/qnn へコピーして指す（= 実行時ダウンロード相当）
        val args = InstrumentationRegistry.getArguments()
        val testNativeDir = InstrumentationRegistry.getInstrumentation().context.applicationInfo.nativeLibraryDir
        val adspDir = if (args.getString("adsp") == "filesdir") {
            // テスト APK は extractNativeLibs=false なので zip から取り出す（= ダウンロード済みファイル相当）。
            val dst = File(ctx.filesDir, "qnn").apply { mkdirs() }
            java.util.zip.ZipFile(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir).use { zip ->
                for (name in listOf("libQnnHtpV81Skel.so", "libQnnHtpV81Stub.so")) {
                    val entry = zip.getEntry("lib/arm64-v8a/$name") ?: continue
                    val t = File(dst, name)
                    if (!t.exists() || t.length() != entry.size) zip.getInputStream(entry).use { i -> t.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            dst.path
        } else testNativeDir
        android.system.Os.setenv("ADSP_LIBRARY_PATH", "$adspDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true)
        results.putString("adspDir", adspDir + " skels=" + File(adspDir).list().orEmpty().filter { it.startsWith("libQnnHtpV81") }.size)
        results.putString("soc", "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}")

        // -e libs filesdir → ORT 本体と QNN 本体も filesDir/qnn から読む（完全な実行時ダウンロード相当）。
        var backendPath: String? = null
        if (args.getString("libs") == "filesdir") {
            val dst = File(ctx.filesDir, "qnn").apply { mkdirs() }
            java.util.zip.ZipFile(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir).use { zip ->
                for (name in listOf("libonnxruntime.so", "libonnxruntime4j_jni.so", "libQnnHtp.so", "libQnnSystem.so")) {
                    val entry = zip.getEntry("lib/arm64-v8a/$name") ?: continue
                    val t = File(dst, name)
                    if (!t.exists() || t.length() != entry.size) zip.getInputStream(entry).use { i -> t.outputStream().use { o -> i.copyTo(o) } }
                }
            }
            System.setProperty("onnxruntime.native.path", dst.path)
            System.load(File(dst, "libQnnSystem.so").path)
            System.load(File(dst, "libQnnHtp.so").path)
            // ORT 本体も絶対パスで先に読む（APK に無い場合はこれが唯一の経路）。
            // 先に絶対パスで読んでおくと、APK 内の 111KB の JNI シムの DT_NEEDED は soname で解決される。
            if (File(dst, "libonnxruntime.so").exists()) System.load(File(dst, "libonnxruntime.so").path)
            backendPath = File(dst, "libQnnHtp.so").path
            results.putString("libsDir", dst.path + " files=" + dst.list().orEmpty().joinToString(","))
        }
        this.backendPath = backendPath
        this.perfMode = args.getString("perf")
        val env = OrtEnvironment.getEnvironment()
        for (model in models) {
            val useHtp = model.name.startsWith("htp_")
            try {
                val ctxOnly = args.getString("ctxonly") == "1"
                if (!ctxOnly) {
                    val r = runModel(env, model, images, base, useHtp, contextFile = null)
                    results.putString(model.name, r)
                }
                if (useHtp) {
                    val ctxFile = File(model.parentFile, model.nameWithoutExtension + "_ctx.onnx")
                    val r2 = runModel(env, model, images, base, useHtp = true, contextFile = ctxFile)
                    results.putString(model.name + "[ctx]", r2)
                }
            } catch (t: Throwable) {
                results.putString(model.name + "[error]", t.toString().take(600))
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, results)
    }

    private fun runModel(
        env: OrtEnvironment,
        model: File,
        images: List<File>,
        base: File,
        useHtp: Boolean,
        contextFile: File?,
    ): String {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            if (useHtp) {
                val qnn = mutableMapOf(
                    "htp_performance_mode" to (perfMode ?: "burst"),
                    "htp_graph_finalization_optimization_mode" to "3",
                )
                backendPath?.let { qnn["backend_path"] = it } ?: run { qnn["backend_type"] = "htp" }
                addQnn(qnn)
                if (contextFile != null && !contextFile.exists()) {
                    addConfigEntry("ep.context_enable", "1")
                    addConfigEntry("ep.context_file_path", contextFile.path)
                    addConfigEntry("ep.context_embed_mode", "1")
                }
            }
        }
        val modelPath = if (contextFile != null && contextFile.exists()) contextFile.path else model.path
        Runtime.getRuntime().gc()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val t0 = System.nanoTime()
        val session = env.createSession(modelPath, opts)
        val loadMs = (System.nanoTime() - t0) / 1e6
        val inputName = session.inputNames.first()
        val buf = FloatBuffer.allocate(3 * size * size)
        val times = ArrayList<Long>()
        var peakNative = 0L
        val tag = model.nameWithoutExtension + (if (contextFile != null) "_ctx" else "")
        val outDir = File(base, "cutout_$tag").apply { mkdirs() }
        for (img in images) {
            val bmp = BitmapFactory.decodeFile(img.path)
            val scaled = Bitmap.createScaledBitmap(bmp, size, size, true)
            val px = IntArray(size * size)
            scaled.getPixels(px, 0, size, 0, 0, size, size)
            buf.rewind()
            for (c in 0 until 3) for (i in 0 until size * size) {
                val v = (px[i] shr (16 - 8 * c)) and 0xFF
                buf.put(v / 255f - mean[c])
            }
            buf.rewind()
            val t = System.nanoTime()
            val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, size.toLong(), size.toLong()))
            val out = session.run(mapOf(inputName to tensor))
            @Suppress("UNCHECKED_CAST")
            val mask = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0]
            times.add((System.nanoTime() - t) / 1_000_000)
            peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
            savePng(bmp, mask, File(outDir, img.name.replace(".jpg", ".png")))
            out.close(); tensor.close()
        }
        // 同一画像を追加で 3 回（ウォームアップ後の安定値）
        val warm = ArrayList<Long>()
        if (images.isNotEmpty()) {
            val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, size.toLong(), size.toLong()))
            repeat(3) {
                val t = System.nanoTime()
                session.run(mapOf(inputName to tensor)).close()
                warm.add((System.nanoTime() - t) / 1_000_000)
            }
            tensor.close()
        }
        session.close()
        val ctxSize = contextFile?.takeIf { it.exists() }?.length()?.div(1_000_000)
        return "load=${"%.0f".format(loadMs)}ms infer=$times warm=$warm nativeDeltaMB=${(peakNative - nativeBefore) / 1_000_000}" +
            (if (ctxSize != null) " ctxMB=$ctxSize" else "")
    }

    private fun savePng(bmp: Bitmap, mask: Array<FloatArray>, dst: File) {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (row in mask) for (v in row) { if (v < mn) mn = v; if (v > mx) mx = v }
        val alpha = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val ab = java.nio.ByteBuffer.allocate(size * size)
        for (row in mask) for (v in row) ab.put((((v - mn) / (mx - mn + 1e-6f)) * 255f).toInt().toByte())
        ab.rewind(); alpha.copyPixelsFromBuffer(ab)
        val alphaFull = Bitmap.createScaledBitmap(alpha, bmp.width, bmp.height, true)
        val src = IntArray(bmp.width * bmp.height); bmp.getPixels(src, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val ap = java.nio.ByteBuffer.allocate(bmp.width * bmp.height); alphaFull.copyPixelsToBuffer(ap); ap.rewind()
        for (i in src.indices) src[i] = (src[i] and 0x00FFFFFF) or ((ap.get(i).toInt() and 0xFF) shl 24)
        val outBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(src, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        FileOutputStream(dst).use { outBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

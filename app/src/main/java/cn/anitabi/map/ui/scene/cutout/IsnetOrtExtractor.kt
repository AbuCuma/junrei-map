package cn.anitabi.map.ui.scene.cutout

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.os.Trace
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 用 ONNX Runtime 运行 ISNet-Anime(SkyTNT/anime-segmentation, Apache-2.0)。
 * 输入 1024²·mean(0.485,0.456,0.406)/std 1(与 rembg dis_anime 相同的前处理),输出 1024² 的概率图,
 * 经 min-max 归一化还原为原尺寸 alpha。后段收边与 ML Kit 路径共用(SubjectExtractor)。
 *
 * - HTP:`isnet_w8a16_ctx_<arch>.onnx`(内嵌 QNN context binary)。会话保持(+~100MB、0.2s)。
 * - CPU:`isnet_w8a8.onnx`。推理峰值 +1.1GB,因此**每次都关闭会话**。
 *
 * 原生库全部由 [CutoutRuntimeStore] 以绝对路径 System.load(APK 里只带 83KB 的 JNI 垫片)。
 */
class IsnetOrtExtractor(
    private val store: CutoutRuntimeStore,
    private val tier: DeviceTier,
) {
    private val mutex = Mutex()

    /**
     * 前景 alpha(0..255,原尺寸)。失败抛异常。
     *
     * 会话**每次创建、每次关闭**(HTP 也一样)。从 context binary 重建约 ~0.2s,
     * 而抠图每个页面只跑一次,与其让 DSP/NPU 常驻发热,不如用完就关。
     */
    suspend fun alphaMask(source: Bitmap): ByteArray = mutex.withLock {
        withContext(Dispatchers.Default) {
            Trace.beginSection("Isnet.infer")
            try {
                ensureNativeLoaded()
                val session = createSession()
                try {
                    run(session, source)
                } finally {
                    session.close()
                }
            } finally {
                Trace.endSection()
            }
        }
    }

    private fun createSession(): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            if (tier is DeviceTier.Htp) {
                addQnn(
                    mapOf(
                        "backend_path" to store.file(CutoutManifest.QNN_HTP_LIB).path,
                        // 单发抠图不需要高时钟。power_saver 也只要 62ms(burst 28/balanced 45),体感即时,
                        // 发热与功耗最小(真机 perf sweep 实测)。
                        "htp_performance_mode" to "power_saver",
                    )
                )
            }
        }
        val model = when (tier) {
            is DeviceTier.Htp -> store.file(CutoutManifest.htpModel(tier.htpArch))
            else -> store.file(CutoutManifest.CPU_MODEL)
        }
        return env.createSession(model.path, opts)
    }

    private fun run(session: OrtSession, source: Bitmap): ByteArray {
        val env = OrtEnvironment.getEnvironment()
        val scaled = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== source) scaled.recycle()
        val buf = FloatBuffer.allocate(3 * SIZE * SIZE)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            val m = MEAN[c]
            for (i in 0 until SIZE * SIZE) buf.put(((px[i] shr shift) and 0xFF) / 255f - m)
        }
        buf.rewind()
        val mask: Array<FloatArray>
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                mask = (out[0].value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (row in mask) for (v in row) { if (v < mn) mn = v; if (v > mx) mx = v }
        val range = (mx - mn).coerceAtLeast(1e-6f)
        val small = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        val ab = java.nio.ByteBuffer.allocate(SIZE * SIZE)
        for (row in mask) for (v in row) ab.put((((v - mn) / range) * 255f).toInt().coerceIn(0, 255).toByte())
        ab.rewind()
        small.copyPixelsFromBuffer(ab)
        val full = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()
        val alpha = ByteArray(source.width * source.height)
        val fb = java.nio.ByteBuffer.wrap(alpha)
        full.copyPixelsToBuffer(fb)
        full.recycle()
        return alpha
    }

    private var nativeLoaded = false
    private fun ensureNativeLoaded() {
        if (nativeLoaded) return
        // 先以绝对路径加载 ORT 本体,APK 内 JNI 垫片的 DT_NEEDED 就能按 soname 解析。
        System.load(store.file(CutoutManifest.ORT_LIB).path)
        if (tier is DeviceTier.Htp) {
            System.load(store.file(CutoutManifest.QNN_SYSTEM_LIB).path)
            System.load(store.file(CutoutManifest.QNN_HTP_LIB).path)
            // DSP 侧从 ADSP_LIBRARY_PATH 查找 skel(由应用侧 FastRPC open 后传递,放 app 私有目录即可)。
            android.system.Os.setenv(
                "ADSP_LIBRARY_PATH",
                store.dir.path + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp",
                true,
            )
        }
        nativeLoaded = true
    }

    companion object {
        const val SIZE = 1024
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    }
}

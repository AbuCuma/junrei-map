package cn.anitabi.map.ui.scene

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 从参考剧照中抠出角色(iOS 用 Vision,这里迁移为 ML Kit Subject Segmentation)。
 * 产出为**与原图同尺寸、同构图**的前景位图(角色留在原位,背景透明)——
 * 与 iOS 版相同的设计,保证换图显示时角色不会移位。
 *
 * 这里是 **ML Kit 路径与其他路径共用的收边**。面向二次元的 ISNet-Anime 路径由
 * [cn.anitabi.map.ui.scene.cutout.CutoutEngine] 负责前段(生成 alpha),在 [fromAlpha] 汇合。
 *
 * ML Kit 的分割模块是 unbundled 的,由 Google Play 服务按需下载(manifest 的
 * `com.google.mlkit.vision.DEPENDENCIES` 让它在装 App 时就开始下,但不保证到位)。模块没到时
 * `process()` 以 `MlKitException.UNAVAILABLE` 失败,ML Kit 自己会去请求下载 —— 这里做的是**等它一会儿再试**
 * ([UNAVAILABLE_RETRIES] × [UNAVAILABLE_RETRY_DELAY_MS]),而不是把失败吞成「没有抠图」:那正是
 * 首次使用时抠图开关不出现的原因。
 *
 * **不要**用 `ModuleInstall` API 去查/装这个模块:16.0.0-beta1 的组件注册在 R8 全程序优化之后是坏的,
 * `areModulesAvailable(segmenter)` 在 release 里同步抛 NPE(真机实测,debug 正常),给它补 keep 规则
 * 又会让 `MlKitInitProvider` 启动即崩。v0.1.1 就是因此在正式版里没有抠图开关。
 *
 * 未就绪或设备不支持时返回 null,调用方降级为纯幽灵叠加(绝不因此崩溃)。
 */
object SubjectExtractor {

    enum class Engine { MlKit, IsnetHtp, IsnetCpu }

    data class Cutout(
        val bitmap: Bitmap,
        /** 前景所占面积比。过低的值用作「抠图不可信」的粗判定。 */
        val coverage: Float,
        val engine: Engine = Engine.MlKit,
    )

    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()
        )
    }

    private const val TAG = "SubjectExtractor"

    /** 模块未就绪时的重试:每 3 秒一次,最多 20 次(≈1 分钟,够 Play 服务把几 MB 的模块下完)。 */
    private const val UNAVAILABLE_RETRIES = 20
    private const val UNAVAILABLE_RETRY_DELAY_MS = 3_000L

    /** ML Kit 的 Task 不该无限期不回调;超时按一次失败处理并留日志。 */
    private const val SEGMENT_TIMEOUT_MS = 30_000L

    /** 用 ML Kit 抠前景 + 收边(MatteMath)。失败返回 null(降级不算错误)。 */
    suspend fun extract(source: Bitmap): Cutout? {
        var attempt = 0
        var foreground: Bitmap?
        while (true) {
            val outcome = segmentOnce(source)
            foreground = outcome.bitmap
            if (foreground != null || !outcome.moduleUnavailable || attempt >= UNAVAILABLE_RETRIES) break
            attempt++
            delay(UNAVAILABLE_RETRY_DELAY_MS)
        }
        foreground ?: return null
        val width = foreground.width
        val height = foreground.height
        if (width == 0 || height == 0) return null
        val pixels = IntArray(width * height)
        foreground.getPixels(pixels, 0, width, 0, 0, width, height)
        return finish(pixels, width, height, source, Engine.MlKit)
    }

    /** 由原尺寸 alpha(0..255)组装前景位图并收边(ISNet 路径的汇合点)。 */
    fun fromAlpha(alpha: ByteArray, source: Bitmap, engine: Engine): Cutout {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) pixels[i] = (pixels[i] and 0x00FFFFFF) or ((alpha[i].toInt() and 0xFF) shl 24)
        return finish(pixels, width, height, source, engine)
    }

    /** 收边:在非预乘 ARGB 上做 alpha 拉伸 + 填洞(iOS SubjectMatteRefiner 的流程) + 计算 coverage。 */
    private fun finish(pixels: IntArray, width: Int, height: Int, source: Bitmap, engine: Engine): Cutout {
        val scaledSource = if (source.width != width || source.height != height) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else {
            source
        }
        val sourcePixels = IntArray(width * height)
        scaledSource.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        MatteMath.refine(pixels, sourcePixels, width, height, strongMatte = engine != Engine.MlKit)

        var opaque = 0
        for (p in pixels) {
            if ((p ushr 24) and 0xFF >= 128) opaque++
        }
        val refined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        refined.setPixels(pixels, 0, width, 0, 0, width, height)
        return Cutout(bitmap = refined, coverage = opaque.toFloat() / pixels.size, engine = engine)
    }

    private class SegmentOutcome(val bitmap: Bitmap?, val moduleUnavailable: Boolean)

    private suspend fun segmentOnce(source: Bitmap): SegmentOutcome {
        val outcome = withTimeoutOrNull(SEGMENT_TIMEOUT_MS) {
            suspendCancellableCoroutine<SegmentOutcome> { continuation ->
                segmenter.process(InputImage.fromBitmap(source, 0))
                    .addOnSuccessListener { result ->
                        continuation.resume(SegmentOutcome(result.foregroundBitmap, moduleUnavailable = false))
                    }
                    .addOnFailureListener { e ->
                        // 模块未装好(UNAVAILABLE)之外的失败也留下痕迹 —— 此前这里连日志都没有,
                        // 「开关不出现」只能靠猜。不含图片内容与用户数据。
                        val code = (e as? MlKitException)?.errorCode
                        Log.w(TAG, "segmentation failed (code=$code): $e")
                        continuation.resume(SegmentOutcome(null, moduleUnavailable = code == MlKitException.UNAVAILABLE))
                    }
            }
        }
        if (outcome == null) Log.w(TAG, "segmentation did not return within ${SEGMENT_TIMEOUT_MS}ms")
        // 超时同样当作「再等等」:真机上见过既不成功也不失败的 Task。
        return outcome ?: SegmentOutcome(null, moduleUnavailable = true)
    }
}

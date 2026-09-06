package cn.anitabi.map.ui.scene

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.coroutines.resume
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
 * unbundled 模型由 GMS 在首次使用时下载。未就绪或设备不支持时返回 null,
 * 调用方降级为纯幽灵叠加(绝不因此崩溃)。
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

    /** 等 GMS 把可选模块装好的上限。装不完就先按「没有抠图」用,下次进来再等。 */
    private const val MODULE_INSTALL_TIMEOUT_MS = 90_000L

    /**
     * ML Kit 的主体分割是 unbundled 模块,由 Google Play 服务按需下载。manifest 里的
     * `com.google.mlkit.vision.DEPENDENCIES` 只是「安装应用时顺便下」的提示,并不保证 ——
     * 真机上首次打开拍摄页时模块常常还没到,`process()` 立刻以 UNAVAILABLE 失败,而这里此前把失败
     * 吞成 null,于是抠图开关**永远不出现**(iOS 用的 Vision 是系统自带的,没有这一步)。
     * 所以先显式请求安装并等它装完,再去分割。已装好时立即返回。
     */
    suspend fun ensureModuleInstalled(context: Context): Boolean {
        val client = ModuleInstall.getClient(context)
        val installed = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<Boolean?> { cont ->
                client.areModulesAvailable(segmenter)
                    .addOnSuccessListener { cont.resume(it.areModulesAvailable()) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }
        if (installed == true) return true
        val result = withTimeoutOrNull(MODULE_INSTALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val listener = object : InstallStatusListener {
                    override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                        when (update.installState) {
                            ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> if (cont.isActive) cont.resume(true)
                            ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                            ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> if (cont.isActive) cont.resume(false)
                            else -> Unit
                        }
                    }
                }
                val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()
                client.installModules(request)
                    .addOnSuccessListener { response ->
                        // 已经装好的话不会再有 listener 回调。
                        if (response.areModulesAlreadyInstalled() && cont.isActive) cont.resume(true)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "module install request failed: $e")
                        if (cont.isActive) cont.resume(false)
                    }
                cont.invokeOnCancellation { client.unregisterListener(listener) }
            }
        }
        if (result != true) Log.w(TAG, "subject segmentation module not ready (result=$result)")
        return result == true
    }

    /** 用 ML Kit 抠前景 + 收边(MatteMath)。失败返回 null(降级不算错误)。 */
    suspend fun extract(source: Bitmap): Cutout? {
        val foreground = segment(source) ?: return null
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

    private suspend fun segment(source: Bitmap): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            segmenter.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener { result ->
                    continuation.resume(result.foregroundBitmap)
                }
                .addOnFailureListener { e ->
                    // 模块未装好(MlKitException UNAVAILABLE)等情况降级为不抠图,但要留下痕迹 ——
                    // 此前这里连日志都没有,「开关不出现」只能靠猜。不含图片内容与用户数据。
                    Log.w(TAG, "segmentation failed: $e")
                    continuation.resume(null)
                }
        }
}

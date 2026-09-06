package cn.anitabi.map.ui.scene.cutout

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.util.Log
import cn.anitabi.map.ui.scene.SubjectExtractor
import java.io.File
import cn.anitabi.map.support.writeAtomically
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 抠图引擎的入口。失败链为 **ISNet(HTP) → ISNet(CPU) → ML Kit → 不抠图**。
 *
 * - 设备分档见 [DeviceTier]。所需成果物从 [CutoutManifest] 查得,由 [CutoutRuntimeStore] 保管。
 * - 成果物不齐时**本次先用 ML Kit 即答**,并在后台(非计费网络时)去获取。
 *   下次起改用 ISNet。不让用户等待(与 iOS 相同的「打开即预览」)。
 * - manifest URL 为空(local.properties 未设 CUTOUT_MANIFEST_URL)则只用 ML Kit。
 */
class CutoutEngine(
    private val context: Context,
    private val client: OkHttpClient,
    private val manifestUrl: String,
) {
    sealed interface State {
        data object Idle : State
        /** 在计费网络上等着。用户可用 [downloadOnMeteredNetwork] 放行一次。 */
        data object WaitingForUnmetered : State
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State {
            val progress: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
        }
        data class Ready(val tier: DeviceTier) : State
        /**
         * 准备失败(网络、manifest、校验)。此前被吞成 [Idle],与「没启动」不可区分 ——
         * About 页的开关打开后什么也看不到,用户只能猜。[warmUp] 可重试。
         */
        data class Failed(val reason: String) : State
        /** ISNet 不可用(不支持的设备/无 manifest/已损坏)。只用 ML Kit。 */
        data object Unavailable : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = CutoutRuntimeStore(File(context.filesDir, "cutout"), client)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 实际使用的档位(manifest 缺成果物时降一档)。 */
    @Volatile private var tier: DeviceTier = DeviceTier.detect(context)
    @Volatile private var extractor: IsnetOrtExtractor? = null
    private var prepareJob: Job? = null

    /**
     * 实验性开关(About 页,持久于 prefs;AppGraph 构造时播种)。默认关:
     * 关闭状态下 warmUp/extract 均不触碰 ISNet,不下载任何文件,纯 ML Kit。
     */
    @Volatile var userEnabled: Boolean = false

    val isIsnetEnabled: Boolean
        get() = userEnabled && manifestUrl.isNotBlank() && tier !is DeviceTier.MlKitOnly

    /**
     * 开关由开转关时调用:停掉进行中的准备/下载、释放推理会话、状态回 Idle。
     * 已下载的文件**保留**(再次打开立即可用;删除入口留待后续)。
     */
    fun reset() {
        prepareJob?.cancel()
        prepareJob = null
        extractor = null // ORT session 是逐推理开关的,extractor 本身不持资源
        _state.value = State.Idle
    }

    /** 用户对「用移动数据下载」的一次性放行(本进程内有效)。 */
    @Volatile private var meteredAllowed = false

    /**
     * 开关打开、或打开拍摄页时调用:成果物缺失则开始下载(默认仅限非计费网络)。
     * 状态经 [state] 全程可见:等待 Wi-Fi / 下载中(字节)/ 就绪 / 失败 —— 失败可再调用重试。
     */
    fun warmUp() {
        if (!isIsnetEnabled || extractor != null) return
        if (prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            try {
                prepare()
            } catch (c: CancellationException) {
                throw c // 取消不算失败,不覆写状态
            } catch (t: Throwable) {
                Log.w(TAG, "prepare failed: $t")
                _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** 用户明确同意用移动数据下载:放行一次并立刻开始。 */
    fun downloadOnMeteredNetwork() {
        meteredAllowed = true
        warmUp()
    }

    suspend fun extract(source: Bitmap): SubjectExtractor.Cutout? {
        extractor?.let { ex ->
            try {
                val t0 = System.nanoTime()
                val engine = if (tier is DeviceTier.Htp) SubjectExtractor.Engine.IsnetHtp else SubjectExtractor.Engine.IsnetCpu
                // alphaMask 与收边(MatteMath 全像素运算)都**移出主线程**(不冻结 UI)。
                val cutout = withContext(Dispatchers.Default) {
                    val alpha = ex.alphaMask(source)
                    SubjectExtractor.fromAlpha(alpha, source, engine)
                }
                Log.d(TAG, "cutout via $engine in ${(System.nanoTime() - t0) / 1_000_000}ms coverage=${"%.3f".format(cutout.coverage)}")
                return cutout
            } catch (c: CancellationException) {
                // 调用方取消(如推理中关闭拍摄页)不是硬件故障——必须重抛,
                // 否则会被下面的分支误判为「HTP 挂了」而永久降档到 ML Kit。
                throw c
            } catch (t: Throwable) {
                // HTP 真挂了(SSR 等)/内存不足:本进程内降一档继续。
                Log.w(TAG, "ISNet failed on $tier, degrading: $t")
                degrade()
            }
        }
        warmUp()
        // ML Kit 模块是 GMS 按需下载的可选模块,先确保装好(首次要等下载)。装不上就不抠图。
        if (!SubjectExtractor.ensureModuleInstalled(context)) return null
        // ML Kit 兜底同样要**移出主线程**。`SubjectExtractor.finish()` 是全图像素运算
        // (getPixels ×2、MatteMath 全像素拉伸 + flood fill、createScaledBitmap、setPixels),
        // 而 ISNet 默认是关的 —— 这条兜底才是绝大多数用户实际走的路径。
        // 少了这个 withContext,调用方在 LaunchedEffect(主线程)上直接冻住整个拍摄页。
        return withContext(Dispatchers.Default) { SubjectExtractor.extract(source) }
    }

    private fun degrade() {
        extractor = null
        tier = when (tier) {
            is DeviceTier.Htp -> DeviceTier.Cpu
            else -> DeviceTier.MlKitOnly
        }
        _state.value = if (tier is DeviceTier.MlKitOnly) State.Unavailable else State.Idle
    }

    private suspend fun prepare() {
        val manifest = fetchManifest()
        var required = manifest.requiredArtifacts(tier)
        if (required == null && tier is DeviceTier.Htp) {
            // 这个 HTP 世代的 context binary 尚未下发 → 落到 CPU 档。
            tier = DeviceTier.Cpu
            required = manifest.requiredArtifacts(tier)
        }
        if (required == null || tier is DeviceTier.MlKitOnly) {
            _state.value = State.Unavailable
            return
        }
        // 成果物 URL 同样强制 https(manifest 可给绝对 URL,不必与 manifest 同源)。
        require(allowCleartext || required.all { it.url.startsWith("https://") }) {
            "cutout artifacts must be served over https"
        }
        if (!store.allReady(required)) {
            if (isMetered() && !meteredAllowed) {
                _state.value = State.WaitingForUnmetered
                return
            }
            val total = required.sumOf { it.size }
            _state.value = State.Downloading(0L, total)
            store.ensure(required) { done, t ->
                _state.value = State.Downloading(done, t)
            }
        }
        extractor = IsnetOrtExtractor(store, tier)
        _state.value = State.Ready(tier)
    }

    /**
     * 是否允许非 https 来源。这些成果物会被 System.load 为原生库,
     * 生产必须 https(release 下 targetSdk 36 的 cleartext 默认拦截只是兜底,这里显式关死);
     * debug(adb reverse 本地服务器)豁免。
     */
    private val allowCleartext =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private suspend fun fetchManifest(): CutoutManifest = withContext(Dispatchers.IO) {
        require(allowCleartext || manifestUrl.startsWith("https://")) {
            "cutout manifest must be served over https: $manifestUrl"
        }
        val cached = File(store.dir, "manifest.json")
        val fresh = cached.exists() && System.currentTimeMillis() - cached.lastModified() < MANIFEST_TTL_MS
        val text = if (fresh) {
            cached.readText()
        } else {
            runCatching {
                client.newCall(Request.Builder().url(manifestUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("manifest HTTP ${resp.code}")
                    resp.body.string()
                }
            }.onSuccess { storeManifest(cached, it) }
                .getOrElse { if (cached.exists()) cached.readText() else throw it }
        }
        CutoutManifest.parse(text, manifestUrl)
    }

    /**
     * 原子写入。直接 `writeText` 时若中途进程死亡,文件被截断但 `lastModified` 是新的 ——
     * 之后整整 [MANIFEST_TTL_MS](24 小时)都会走 `fresh` 分支去读这个坏文件,
     * 解析抛异常被 [warmUp] 吞成 `State.Idle`,与「没启动」不可区分。
     */
    private fun storeManifest(target: File, text: String) {
        target.writeAtomically { it.writeText(text) }
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return cm.isActiveNetworkMetered
    }

    companion object {
        private const val TAG = "CutoutEngine"
        private const val MANIFEST_TTL_MS = 24L * 60 * 60 * 1000
    }
}

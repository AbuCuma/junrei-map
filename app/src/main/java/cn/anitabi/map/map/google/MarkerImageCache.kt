package cn.anitabi.map.map.google

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 标注专用的 h160 缩略图小取货口（iOS MarkerImageCache 的移植）。
 * 地图一帧会请求几十张，所以收紧并发连接数，同 URL 的并行请求做合流。
 * 解码在 worker 线程完成后再回主线程（滚动中 hitch 的对策）。
 *
 * - 缓存上限按**字节数**切（按条数的话曾膨胀到 400 × ~180KB ≈ 73MB）。
 * - 超过标注上粘贴尺寸（最大也就 80dp 宽）的图片用 inSampleSize 抽样解码。
 * - JPEG 缩略图没有 alpha，用 RGB_565（ARGB_8888 的一半）。
 */
class MarkerImageCache(
    client: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope,
    /** 图片在标注内占据的最大边长（px）。比这大的图片做抽样。 */
    private val targetMaxSidePx: Int = 0,
) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    // newBuilder() 会共享 Dispatcher，所以这里换一个专用的，免得动到数据加载器和 Coil。
    // 注意：Dispatcher 的 maxRequests* 只对异步 enqueue() 生效，而下面走的是同步
    // execute()，所以并发**不是**由它限住的 —— 真正的上限是 [inFlight]。
    private val session = client.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .dispatcher(Dispatcher())
        .build()

    /**
     * 缩略图并发上限。没有它时实际并发等于 `Dispatchers.IO` 的线程数（约 64）——
     * 首次进入地图时几百个 marker 会同时打向 CDN。
     */
    private val inFlight = Semaphore(MAX_CONCURRENT_LOADS)

    fun cached(url: String): Bitmap? = cache.get(url)

    /** 从主线程调用。completion 也回到主线程。 */
    fun load(url: String, completion: (Bitmap?) -> Unit) {
        cache.get(url)?.let {
            completion(it)
            return
        }
        waiting[url]?.let {
            it.add(completion)
            return
        }
        waiting[url] = mutableListOf(completion)

        scope.launch {
            val bitmap = inFlight.withPermit {
                withContext(Dispatchers.IO) {
                    runCatching {
                        session.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (!response.isSuccessful) return@use null
                            decode(response.body.bytes())
                        }
                    }.getOrNull()
                }
            }
            if (bitmap != null) cache.put(url, bitmap)
            for (callback in waiting.remove(url).orEmpty()) callback(bitmap)
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        if (targetMaxSidePx > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            opts.inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), targetMaxSidePx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { it.prepareToDraw() }
    }

    companion object {
        /** 同时在途的缩略图请求数上限（与此前 Dispatcher 上写的意图值一致）。 */
        private const val MAX_CONCURRENT_LOADS = 4

        /** 在原图长边不低于 target 的范围内取最大的 2 的幂。 */
        fun sampleSize(sourceMaxSide: Int, targetMaxSide: Int): Int {
            if (sourceMaxSide <= 0 || targetMaxSide <= 0) return 1
            var sample = 1
            while (sourceMaxSide / (sample * 2) >= targetMaxSide) sample *= 2
            return sample
        }
    }
}

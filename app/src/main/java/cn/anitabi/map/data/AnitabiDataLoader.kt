package cn.anitabi.map.data

import cn.anitabi.map.data.model.AnitabiDataset
import cn.anitabi.map.data.model.PointDetail
import cn.anitabi.map.support.writeAtomically
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 处理静态数据（全量下载 → 本地保留）的层（iOS AnitabiDataLoader actor 的移植）。
 * `/d/g.json` 含全部作品与全部地标的坐标，`/d/g0-g6.json` 含地标的详情 ——
 * **仅凭 g.json 就能画出地图**，因此首次绘制在那时进行，详情之后再合并。
 *
 * @param cacheDir 全量数据与图标雪碧图共用的存放位置（BangumiIconSprite 也使用同一目录）。
 */
class AnitabiDataLoader(
    private val cacheDir: File,
    client: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** 除测试中替换为 MockWebServer 外，一律使用既定的分发节点。 */
    private val origins: List<String> = ORIGINS,
) {
    companion object {
        /**
         * 分发节点。从头依次尝试,进程内持续使用最先应答的节点。
         * - `ww.anitabi.cn` … 首选。
         * - `w.junreimap.com` … 镜像。2026-08 时与 `ww.` 内容一致。
         *
         * 注意:官方 API 文档明确要求「请勿在任何场景下请求主域 `https://anitabi.cn/`」,
         * 因此这里不含主域(`www.anitabi.cn` 同属主域范畴,已移除)。
         */
        val ORIGINS = listOf(
            "https://ww.anitabi.cn",
            "https://w.junreimap.com",
        )
        const val DETAIL_SHARD_COUNT = 7 // g0 … g6

        /** 与 Web 版 `HS()` 同一公式。约每 24 分钟变化一次。 */
        fun cacheBuster(epochMillis: Long): String =
            ((epochMillis / 1000 / 60 / 24) + 6).toString(36)
    }

    // 无数据往来的超时。即使把所有节点都试到卡死，最坏 45 秒后放弃。
    // 缓存控制由 `d` 参数一侧负责，因此不使用 HTTP 缓存（OkHttp 默认 cache = null）。
    private val session: OkHttpClient = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 最近一次应答的节点下标。把命中的节点设为之后的默认，避免反复往返已挂掉的节点。 */
    @Volatile
    private var preferredOriginIndex = 0

    /** 当前采用的节点（也用于拼装雪碧图的绝对 URL）。 */
    val preferredOrigin: String
        get() = origins[preferredOriginIndex.coerceAtMost(origins.size - 1)]

    private val cacheBuster: String get() = cacheBuster(clock())

    // MARK: 公开 API

    /** 只用磁盘缓存组装。供启动后立即绘制用。 */
    suspend fun loadFromCache(): AnitabiDataset? = withContext(Dispatchers.IO) {
        // 唯一临时名的代价:进程在写入中途被杀就会留下一个孤儿。启动时顺手扫掉,
        // 否则它们只增不减(缓存目录虽可被系统回收,但不该白占)。
        runCatching {
            cacheDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
        val listText = cachedText(CacheSlot.List) ?: return@withContext null
        var dataset = AnitabiJsonParser.parseBangumiList(listText) ?: return@withContext null

        val details = HashMap<String, PointDetail>()
        val modifiedByBangumi = HashMap<Int, Double>()
        for (shard in 0 until DETAIL_SHARD_COUNT) {
            val text = cachedText(CacheSlot.Details(shard)) ?: continue
            AnitabiJsonParser.parsePointDetails(text, details, modifiedByBangumi)
        }
        if (details.isNotEmpty()) {
            dataset = dataset.copy(
                points = AnitabiJsonParser.merge(details, dataset.points),
                modifiedByBangumi = modifiedByBangumi,
                hasDetails = true,
            )
        }
        dataset
    }

    /**
     * 从网络重新拉取。`cachedModified` 与服务端一致时省去详情的重新获取，
     * `onList` 在 g.json 解析完成的时点只调用 1 次（供地图首次绘制用）。
     *
     * `onList` 是 **suspend** 的：调用方要把回调切回自己的数据线程再落库。本方法体跑在
     * [Dispatchers.IO] 上，若回调直接在这里改状态，就会与调用方自己的线程形成双线程写入。
     *
     * **取消是协作式的**：[fetch] 是阻塞调用且分片循环没有天然挂起点，因此每一轮都显式
     * [ensureActive]。少了它，被取消的加载会跑完全部 8 次请求（最坏约 105 秒）并与替换它的
     * 那一轮并发写缓存。
     */
    suspend fun refresh(
        cachedModified: Double,
        onList: suspend (AnitabiDataset) -> Unit = {},
    ): AnitabiDataset = withContext(Dispatchers.IO) {
        val buster = cacheBuster

        ensureActive()
        val listText = fetch("/d/g.json", buster)
        var dataset = AnitabiJsonParser.parseBangumiList(listText)
            ?: throw AnitabiDataException.Malformed("g.json")
        store(listText, CacheSlot.List)
        onList(dataset)

        // 本地已是最新则详情沿用缓存（与官方的增量判定一致）。
        val detailsAreFresh = dataset.modified <= cachedModified

        val details = HashMap<String, PointDetail>()
        val modifiedByBangumi = HashMap<Int, Double>()
        var usedStaleShard = false
        for (shard in 0 until DETAIL_SHARD_COUNT) {
            ensureActive()
            val cached = if (detailsAreFresh) cachedText(CacheSlot.Details(shard)) else null
            val text: String? = cached ?: runCatching { fetch("/d/g$shard.json", buster) }
                .onSuccess { store(it, CacheSlot.Details(shard)) }
                .getOrElse {
                    // 拉取失败就用旧缓存顶替。与其显示详情（名称、剧照）整块缺失的
                    // 地标，不如显示旧一个世代的详情。
                    usedStaleShard = true
                    cachedText(CacheSlot.Details(shard))
                }
            if (text == null) continue // 连缓存也没有，本次跳过
            AnitabiJsonParser.parsePointDetails(text, details, modifiedByBangumi)
        }

        dataset.copy(
            points = AnitabiJsonParser.merge(details, dataset.points),
            modifiedByBangumi = modifiedByBangumi,
            hasDetails = details.isNotEmpty(),
            detailsAreCurrent = !usedStaleShard,
        )
    }

    /**
     * `/d/users.csv` → 投稿者 ID → 昵称的映射（约 1214 行）。
     * 失败时回退到磁盘缓存（成功时覆盖保存）。
     */
    suspend fun loadUsers(): Map<Int, String> = withContext(Dispatchers.IO) {
        runCatching { fetch("/d/users.csv", cacheBuster) }.getOrNull()?.let { text ->
            val map = AnitabiJsonParser.parseUsers(text)
            if (map.isNotEmpty()) {
                store(text, CacheSlot.Users)
                return@withContext map
            }
        }
        cachedText(CacheSlot.Users)?.let(AnitabiJsonParser::parseUsers) ?: emptyMap()
    }

    /**
     * `/d/bangumi-icons.json` → 拥有独立图标的作品 ID 列表与雪碧图 URL。
     * 雪碧图本体的下载不在本方法范围内（由 BangumiIconSprite 负责）。
     */
    suspend fun loadIconIndex(): AnitabiJsonParser.IconIndex? = withContext(Dispatchers.IO) {
        // `src` 是相对路径，要以**当前应答的节点**为基准转成绝对 URL。
        runCatching { fetch("/d/bangumi-icons.json", cacheBuster) }.getOrNull()?.let { text ->
            AnitabiJsonParser.parseIconIndex(text, preferredOrigin)?.let { result ->
                store(text, CacheSlot.Icons)
                return@withContext result
            }
        }
        cachedText(CacheSlot.Icons)?.let { AnitabiJsonParser.parseIconIndex(it, preferredOrigin) }
    }

    // MARK: 网络

    /**
     * 从当前采用的节点起依次尝试所有节点。只要有一个返回 2xx 就把它提升为采用节点，
     * 只有全军覆没时才 throw（调用方在那里退回磁盘缓存）。
     */
    private fun fetch(path: String, buster: String): String {
        val start = preferredOriginIndex
        var lastError: Exception = AnitabiDataException.BadStatus(path)
        for (offset in origins.indices) {
            val index = (start + offset) % origins.size
            try {
                val text = fetch(path, buster, index)
                if (index != preferredOriginIndex) preferredOriginIndex = index
                return text
            } catch (e: IOException) {
                lastError = e
            } catch (e: AnitabiDataException) {
                lastError = e
            }
        }
        throw lastError
    }

    private fun fetch(path: String, buster: String, originIndex: Int): String {
        val request = Request.Builder()
            .url("${origins[originIndex]}$path?d=$buster")
            .header("Accept", "*/*")
            .build()
        session.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AnitabiDataException.BadStatus(path)
            return response.body.string()
        }
    }

    // MARK: 磁盘缓存

    private sealed interface CacheSlot {
        val filename: String

        data object List : CacheSlot {
            override val filename = "g.json"
        }

        data class Details(val shard: Int) : CacheSlot {
            override val filename = "g$shard.json"
        }

        data object Users : CacheSlot {
            override val filename = "users.csv"
        }

        data object Icons : CacheSlot {
            override val filename = "bangumi-icons.json"
        }
    }

    private fun cachedText(slot: CacheSlot): String? =
        runCatching { File(cacheDir, slot.filename).readText() }.getOrNull()

    /**
     * 落盘缓存。原子性由 [writeAtomically] 保证(理由见那里)。
     * 写失败保持静默:本类是 `data/` 里可在 JVM 单测的一层,刻意不引入 `android.util.Log`。
     */
    private fun store(text: String, slot: CacheSlot) {
        File(cacheDir, slot.filename).writeAtomically { it.writeText(text) }
    }
}

/** 消息只面向日志/调试;用户可见文案由 UI 层用资源串给出(HomeSheet 的加载失败态)。 */
sealed class AnitabiDataException(message: String) : Exception(message) {
    class Malformed(file: String) : AnitabiDataException("unexpected data format ($file)")
    class BadStatus(file: String) : AnitabiDataException("failed to fetch $file")
}

package cn.anitabi.map.map.google

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import cn.anitabi.map.data.AnitabiDataLoader
import cn.anitabi.map.support.writeAtomically
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 作品标的圆形图标（iOS BangumiIconSprite 的移植）。所有图标排在一张雪碧图上，
 * 60×60 单元格、每行 20 个，`bangumi-icons.json` 的 `ids` 数组的**排列顺序**即单元格编号。
 * 只裁切被请求到的作品（全部预先裁切会超过 20MB）。
 */
class BangumiIconSprite(
    private val loader: AnitabiDataLoader,
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient(),
) {
    companion object {
        /** 雪碧图每行的单元格数（上游的图集生成规则）。 */
        private const val COLUMNS = 20
    }

    /**
     * 雪碧图与它的单元格索引。**两者必须一次发布** —— 它们是同一份数据的两半,
     * 分成两个字段写就会出现「图已就绪但索引还空」的中间态,[icon] 在那一帧对所有作品
     * 返回 null,地图上的作品标整批退化成纯色点。
     */
    private class Sheet(
        val bitmap: Bitmap,
        val cellSize: Int,
        val cellIndexByBangumiId: Map<Int, Int>,
    )

    /** 唯一的跨线程发布点:主线程写、地图引擎线程读。 */
    @Volatile private var sheet: Sheet? = null

    val isReady: Boolean get() = sheet != null

    /** 准备完成的通知回调。地图侧用它来替换已经放上去的作品标。 */
    var onReady: (() -> Unit)? = null

    private val cellCache = LruCache<Int, Bitmap>(256)

    /** 仅在主线程读写(prepare 的调用方是 Compose 效果),不需要跨线程可见性。 */
    private var isLoading = false

    suspend fun prepare() {
        if (isReady || isLoading) return
        isLoading = true
        try {
            val index = loader.loadIconIndex() ?: return
            val bitmap = withContext(Dispatchers.IO) { fetchSheet(index.spriteUrl) } ?: return
            sheet = Sheet(
                bitmap = bitmap,
                cellSize = bitmap.width / COLUMNS,
                cellIndexByBangumiId = index.ids.withIndex().associate { (offset, id) -> id to offset },
            )
            onReady?.invoke()
        } finally {
            isLoading = false
        }
    }

    /**
     * 作品的图标。未获取、未收录则为 null（调用侧回落到主题点。
     * **不做首字母占位符** —— 不承载含义的文字只会成为地图上的噪声）。
     */
    /** 也可从主线程以外调用（LruCache 自带同步，雪碧图写入后不再变动）。 */
    fun icon(bangumiId: Int): Bitmap? {
        cellCache.get(bangumiId)?.let { return it }
        val sheet = sheet ?: return null
        val cellSize = sheet.cellSize
        if (cellSize <= 0) return null
        val cellIndex = sheet.cellIndexByBangumiId[bangumiId] ?: return null

        val x = (cellIndex % COLUMNS) * cellSize
        val y = (cellIndex / COLUMNS) * cellSize
        if (y + cellSize > sheet.bitmap.height) return null
        val cropped = Bitmap.createBitmap(sheet.bitmap, x, y, cellSize, cellSize)
        cellCache.put(bangumiId, cropped)
        return cropped
    }

    // MARK: 雪碧图的获取（带磁盘缓存）

    /**
     * 文件名要**混入 URL 的版本号（`?v=hl5ex`）**。若用固定文件名，新雪碧图发布后
     * 也会永远沿用旧图，和更新后的 `ids` 排列对不上，导致单元格错位。
     * **不混入主机名** —— 分发节点切换时内容不变，不该让用户重新下载。
     */
    private fun fetchSheet(url: String): Bitmap? {
        val cacheFile = File(cacheDir, "bangumi-icons-${cacheKey(url)}.img")
        if (cacheFile.exists()) {
            BitmapFactory.decodeFile(cacheFile.path)?.let { return it }
        }

        val data = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body.bytes()
            }
        }.getOrNull()

        if (data == null) {
            // 取不到时用手头已有的旧版雪碧图替代。排列可能略有错位，
            // 但比起所有作品标都落回主题点，能显示图标更好。
            return staleSheet()
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return staleSheet()
        // 原子落盘:直接写目标文件时若中途被杀,留下的截断文件会被后续的 staleSheet()
        // 当成可用的旧图去解码。
        if (cacheFile.writeAtomically { it.writeBytes(data) }) discardSheets(keeping = cacheFile)
        return bitmap
    }

    private fun cacheKey(url: String): String {
        val query = url.substringAfter('?', missingDelimiterValue = "")
        val version = query.split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
        val seed = version ?: url.substringAfter("://").substringAfter('/')
        return MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }

    private fun sheetFiles(): List<File> =
        cacheDir.listFiles { f -> f.name.startsWith("bangumi-icons") && f.extension == "img" }
            ?.toList() ?: emptyList()

    private fun staleSheet(): Bitmap? =
        sheetFiles().firstNotNullOfOrNull { BitmapFactory.decodeFile(it.path) }

    /** 清理旧版雪碧图（每张约 500KB，不做囤积）。 */
    private fun discardSheets(keeping: File) {
        for (file in sheetFiles()) {
            if (file.name != keeping.name) file.delete()
        }
    }
}

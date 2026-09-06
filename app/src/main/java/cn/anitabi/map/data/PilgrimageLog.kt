package cn.anitabi.map.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.support.writeAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条巡礼记录：什么时候把哪部作品的地标标为「已完成」。 */
data class VisitRecord(val bangumiId: Int, val visitedAt: Long)

/**
 * 巡礼记录（用户标为「已完成」的地标）—— iOS 版没有的功能，本仓库自有的用户数据。
 *
 * 唯一事实源。UI 直接读 [records] / [visitedIds]（Compose 状态，整体替换，不可变），
 * 地图侧只订阅 [generation]（把 50k 级的 `Set` 放进每 48ms 比一次的 SceneKey 太贵）。
 *
 * 与 [AnitabiStore] 分开：那是只读数据集的容器，`apply()` 的快照纪律不该被用户状态搅进来；
 * 与 [AnitabiPrefs] 分开：记录会长到几千条，SharedPreferences 每次读都要重新解析整串。
 *
 * 持久化是一个小 JSON 文件（`files/pilgrimage_log.json`，进系统备份）：
 * `{"version":1,"visits":[{"id":"…","bangumiId":123,"at":1690000000000}]}`。
 * [load] / [save] 都是 suspend，作用域由调用方持有；toggle 后的落盘由 `AppGraph.toggleVisited`
 * 挂到进程级作用域上（属于「用户已经按下、必须做完」的动作）。
 *
 * 数据集里已经不存在的地标 id **保留**在记录里（不丢用户数据），由 UI 按当前数据集解析。
 */
class PilgrimageLog(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    var records: Map<String, VisitRecord> by mutableStateOf(emptyMap())
        private set

    /** 读它即订阅。 */
    val visitedIds: Set<String> get() = records.keys

    /** 每次变更 +1。地图的 SceneKey 用它代替整个集合。 */
    var generation: Int by mutableIntStateOf(0)
        private set

    private val saveMutex = Mutex()

    /** 守 [records] 的读-改-写:toggle 在主线程,load 的合并可能在 IO 协程回来的任何线程上。 */
    private val stateLock = Any()

    @Volatile
    private var loaded = false

    fun isVisited(id: String): Boolean = id in records

    /** 切换一个地标的完成状态。返回切换后的状态。 */
    fun toggle(point: ScenePoint, now: Long = System.currentTimeMillis()): Boolean = synchronized(stateLock) {
        val next = records.toMutableMap()
        val visited = if (next.remove(point.id) == null) {
            next[point.id] = VisitRecord(bangumiId = point.bangumiId, visitedAt = now)
            true
        } else {
            false
        }
        records = next
        generation += 1
        visited
    }

    /**
     * 从文件读入。幂等（读成功过一次之后就不再读）。文件不存在 → 空；解析失败 → 把坏文件改名为
     * `.corrupt-<时间戳>` 留给用户，按空处理 —— 绝不静默覆盖；**读不到**（IO 异常）→ 不算已加载，
     * 下次再试，这期间也不会落盘（见 [save]）。
     * 读入的记录与内存里已有的（load 完成前用户就点了的）**合并**，内存优先。
     */
    suspend fun load() {
        if (loaded) return
        val fromDisk = withContext(ioDispatcher) { readFile() } ?: return
        synchronized(stateLock) {
            loaded = true
            if (fromDisk.isEmpty()) return
            val merged = LinkedHashMap(fromDisk)
            merged.putAll(records)
            if (merged != records) {
                records = merged
                generation += 1
            }
        }
    }

    /**
     * 落盘当前快照。**先 [load] 再写**:磁盘上的记录还没读进来就写,等于用内存里的半份覆盖掉全部 ——
     * 这是唯一会丢用户数据的路径。Mutex 串行,快照在锁内取:后拿到锁的一定写更新的状态,
     * 连点时最后一次写入即最终态。
     */
    suspend fun save() {
        load()
        if (!loaded) return
        withContext(ioDispatcher) {
            saveMutex.withLock {
                val snapshot = records
                file.writeAtomically { tmp -> tmp.writeText(encode(snapshot)) }
            }
        }
    }

    /** null = 文件在但读不了(IO 异常),调用方不得视为「空」。 */
    private fun readFile(): Map<String, VisitRecord>? {
        if (!file.isFile) return emptyMap()
        val text = runCatching { file.readText() }.getOrElse { return null }
        return runCatching { decode(text) }.getOrElse {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            emptyMap()
        }
    }

    companion object {
        const val FILE_NAME = "pilgrimage_log.json"
        private const val VERSION = 1

        internal fun encode(records: Map<String, VisitRecord>): String {
            val visits = JSONArray()
            for ((id, record) in records) {
                visits.put(
                    JSONObject()
                        .put("id", id)
                        .put("bangumiId", record.bangumiId)
                        .put("at", record.visitedAt),
                )
            }
            return JSONObject().put("version", VERSION).put("visits", visits).toString()
        }

        /** 向前兼容：不认识的 version 也照读 `visits`；缺字段用占位值。 */
        internal fun decode(text: String): Map<String, VisitRecord> {
            val root = JSONObject(text)
            val visits = root.optJSONArray("visits") ?: return emptyMap()
            val out = LinkedHashMap<String, VisitRecord>(visits.length())
            for (i in 0 until visits.length()) {
                val item = visits.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                if (id.isEmpty()) continue
                out[id] = VisitRecord(
                    bangumiId = item.optInt("bangumiId", -1),
                    visitedAt = item.optLong("at", 0L),
                )
            }
            return out
        }
    }
}

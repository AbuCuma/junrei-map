package cn.anitabi.map.data

import cn.anitabi.map.data.model.ScenePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PilgrimageLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String = PilgrimageLog.FILE_NAME) = File(folder.root, name)
    private fun log(f: File = file()) = PilgrimageLog(f, ioDispatcher = Dispatchers.Unconfined)
    private fun point(id: String, bangumiId: Int = 7) = ScenePoint(id, bangumiId, 35.0, 139.0, 100)

    @Test
    fun toggleFlipsAndBumpsGeneration() {
        val log = log()
        assertTrue(log.toggle(point("a"), now = 10L))
        assertTrue(log.isVisited("a"))
        assertEquals(VisitRecord(bangumiId = 7, visitedAt = 10L), log.records["a"])
        assertEquals(1, log.generation)
        assertFalse(log.toggle(point("a")))
        assertFalse(log.isVisited("a"))
        assertEquals(2, log.generation)
    }

    @Test
    fun saveThenLoadRoundTripsRecords() = runTest {
        val log = log()
        log.toggle(point("a", bangumiId = 1), now = 5L)
        log.toggle(point("b", bangumiId = 2), now = 6L)
        log.save()

        val reloaded = log()
        reloaded.load()
        assertEquals(mapOf("a" to VisitRecord(1, 5L), "b" to VisitRecord(2, 6L)), reloaded.records)
        assertEquals(1, reloaded.generation)
    }

    @Test
    fun loadMergesWithTogglesMadeBeforeItFinished() = runTest {
        log().apply { toggle(point("disk", 1), now = 1L); save() }
        val log = log()
        log.toggle(point("memory", 2), now = 2L)
        log.load()
        assertEquals(setOf("disk", "memory"), log.visitedIds)
    }

    @Test
    fun saveBeforeLoadDoesNotDropDiskRecords() = runTest {
        // 磁盘上已有记录;新进程里用户在 load 完成前就打了卡并触发落盘。
        // 落盘必须先把磁盘读进来合并,否则写下去的是内存里的半份,磁盘记录就没了。
        log().apply { toggle(point("disk", 1), now = 1L); save() }
        val log = log()
        log.toggle(point("memory", 2), now = 2L)
        log.save()
        log.load()
        assertEquals(setOf("disk", "memory"), log.visitedIds)
        assertEquals(setOf("disk", "memory"), PilgrimageLog.decode(file().readText()).keys)
    }

    @Test
    fun loadIsIdempotent() = runTest {
        log().apply { toggle(point("a")); save() }
        val log = log()
        log.load()
        val generation = log.generation
        log.load()
        assertEquals(generation, log.generation)
    }

    @Test
    fun missingFileMeansEmpty() = runTest {
        val log = log()
        log.load()
        assertTrue(log.records.isEmpty())
        assertEquals(0, log.generation)
    }

    @Test
    fun corruptFileIsSetAsideNotOverwritten() = runTest {
        val f = file()
        f.writeText("{not json")
        val log = log(f)
        log.load()
        assertTrue(log.records.isEmpty())
        assertFalse("坏文件必须被挪走而不是留在原地", f.exists())
        val kept = folder.root.listFiles()!!.filter { it.name.startsWith("${PilgrimageLog.FILE_NAME}.corrupt-") }
        assertEquals(1, kept.size)
        assertEquals("{not json", kept.single().readText())
    }

    @Test
    fun decodeToleratesUnknownVersionAndMissingFields() {
        val records = PilgrimageLog.decode(
            """{"version":99,"future":true,"visits":[{"id":"x"},{"id":"","at":3},{"id":"y","bangumiId":4,"at":9,"extra":1}]}""",
        )
        assertEquals(mapOf("x" to VisitRecord(-1, 0L), "y" to VisitRecord(4, 9L)), records)
    }

    @Test
    fun encodeIsStableAndDecodable() {
        val original = mapOf("a" to VisitRecord(1, 5L), "b" to VisitRecord(2, 6L))
        assertEquals(original, PilgrimageLog.decode(PilgrimageLog.encode(original)))
    }
}

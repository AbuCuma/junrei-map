package cn.anitabi.map.ui.scene.cutout

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 下载校验链的行为测试。
 *
 * 这条链是运行时 `System.load` 原生库的**唯一防线**
 * (大小 → SHA-256 → `renameTo` → `setReadOnly` → 写 `.ok`),此前零覆盖。
 * 这里测的是可观察结果:什么情况下文件会被接受、什么情况下必须被拒绝并清掉。
 */
class CutoutRuntimeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var root: File

    private val payload = "isnet-runtime-payload".repeat(64).toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = tmp.newFolder("cutout")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun artifact(
        name: String = "libonnxruntime.so",
        size: Long = payload.size.toLong(),
        sha: String = sha256(payload),
    ) = CutoutManifest.Artifact(name = name, url = server.url("/$name").toString(), sha256 = sha, size = size)

    private fun store() = CutoutRuntimeStore(root, OkHttpClient())

    private fun body(bytes: ByteArray) = Buffer().write(bytes)

    @Test
    fun acceptsAMatchingArtifactAndMarksItReadOnlyWithAnOkFile() = runTest {
        server.enqueue(MockResponse.Builder().body(body(payload)).build())
        val s = store()
        val a = artifact()

        s.ensure(listOf(a))

        val target = File(root, a.name)
        assertTrue(target.exists())
        assertEquals(payload.size.toLong(), target.length())
        // 可写文件的 dlopen 将来会被系统拒绝 —— 这一步不能省。
        assertFalse("成果物必须是只读的", target.canWrite())
        assertEquals(a.sha256, File(root, a.name + ".ok").readText().trim())
        assertTrue(s.isReady(a))
        assertFalse(File(root, a.name + ".part").exists())
    }

    @Test
    fun rejectsAndDiscardsAnArtifactWhoseSizeDoesNotMatch() = runTest {
        server.enqueue(MockResponse.Builder().body(body(payload)).build())
        val s = store()
        // manifest 声称的大小比实际大 1 字节
        val a = artifact(size = payload.size.toLong() + 1)

        val failure = runCatching { s.ensure(listOf(a)) }.exceptionOrNull()

        assertTrue("大小不符必须失败", failure != null)
        assertFalse(File(root, a.name).exists())
        assertFalse("残留的 .part 会被下次当成断点续传的起点", File(root, a.name + ".part").exists())
        assertFalse(s.isReady(a))
    }

    @Test
    fun rejectsAndDiscardsAnArtifactWhoseHashDoesNotMatch() = runTest {
        val tampered = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        server.enqueue(MockResponse.Builder().body(body(tampered)).build())
        val s = store()
        // 大小对得上、内容被换掉 —— 只有 sha256 能拦住
        val a = artifact()

        val failure = runCatching { s.ensure(listOf(a)) }.exceptionOrNull()

        assertTrue("sha256 不符必须失败", failure != null)
        assertFalse(File(root, a.name).exists())
        assertFalse(File(root, a.name + ".part").exists())
    }

    @Test
    fun rejectsANonSuccessfulResponse() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        val s = store()
        val a = artifact()

        assertTrue(runCatching { s.ensure(listOf(a)) }.isFailure)
        assertFalse(File(root, a.name).exists())
    }

    @Test
    fun resumesFromAPartialFileWhenTheServerHonorsRange() = runTest {
        val half = payload.size / 2
        File(root, "libonnxruntime.so.part").writeBytes(payload.copyOfRange(0, half))
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .body(body(payload.copyOfRange(half, payload.size)))
                .build()
        )
        val s = store()
        val a = artifact()

        s.ensure(listOf(a))

        assertTrue(s.isReady(a))
        assertEquals(a.sha256, sha256(File(root, a.name).readBytes()))
        val request = server.takeRequest()
        assertEquals("bytes=$half-", request.headers["Range"])
    }

    @Test
    fun restartsFromScratchWhenTheServerIgnoresRange() = runTest {
        val half = payload.size / 2
        File(root, "libonnxruntime.so.part").writeBytes(payload.copyOfRange(0, half))
        // 200 而不是 206:服务端送的是整份,续写就会把前半段重复一遍。
        server.enqueue(MockResponse.Builder().code(200).body(body(payload)).build())
        val s = store()
        val a = artifact()

        s.ensure(listOf(a))

        assertTrue(s.isReady(a))
        assertEquals(payload.size.toLong(), File(root, a.name).length())
    }

    @Test
    fun discardsAPartialFileLargerThanTheExpectedSize() = runTest {
        // manifest 换了新版本、旧 .part 比新成果物还大 —— 续传毫无意义,必须重来。
        File(root, "libonnxruntime.so.part").writeBytes(payload + payload)
        server.enqueue(MockResponse.Builder().body(body(payload)).build())
        val s = store()
        val a = artifact()

        s.ensure(listOf(a))

        assertTrue(s.isReady(a))
        assertEquals(payload.size.toLong(), File(root, a.name).length())
        // 整份重下,不带 Range
        assertEquals(null, server.takeRequest().headers["Range"])
    }

    @Test
    fun alreadyVerifiedArtifactsAreNotDownloadedAgain() = runTest {
        server.enqueue(MockResponse.Builder().body(body(payload)).build())
        val s = store()
        val a = artifact()
        s.ensure(listOf(a))
        val after = server.requestCount

        s.ensure(listOf(a))

        assertEquals("已校验过的成果物不该再次下载", after, server.requestCount)
    }

    @Test
    fun isReadyRejectsAnOkFileThatDisagreesWithTheManifestHash() {
        // 服务端换了新版本(sha 变了),本地旧文件与旧 .ok 必须被判为未就绪。
        File(root, "libonnxruntime.so").writeBytes(payload)
        File(root, "libonnxruntime.so.ok").writeText(sha256(payload))

        assertFalse(store().isReady(artifact(sha = sha256(payload + payload))))
    }
}

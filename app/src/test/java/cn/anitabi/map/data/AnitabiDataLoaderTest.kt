package cn.anitabi.map.data

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnitabiDataLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File

    private val gJson =
        """[[[815878, "孤独摇滚！", null, "ぼっち・ざ・ろっく！", "日本", "#f5c1c4", null, 8.1, "TV",
           35.37, 139.53, 15.0, ["pt-a", 35.37, 139.53, 120], null, null, null]], 500, 100.0]"""

    private fun shardJson(name: String) =
        """[[815878, "#f5c1c4", [["pt-a", "$name", null, false, null, 1, null, null,
           3, 60, null, null, null, null]], 90.0]]"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = tmp.newFolder("anitabi-data")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun loader(vararg origins: String) = AnitabiDataLoader(
        cacheDir = cacheDir,
        clock = { 1_755_300_000_000L },
        origins = origins.toList(),
    )

    private fun serveAll(shardName: String = "文化服装学院") {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/d/g.json" -> MockResponse.Builder().body(gJson).build()
                    path.matches(Regex("/d/g[0-6]\\.json")) ->
                        MockResponse.Builder().body(shardJson(shardName)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
    }

    @Test
    fun refreshMergesDetailsAndCachesToDisk() = runTest {
        serveAll()
        var listCallbacks = 0
        val ds = loader(server.url("/").toString().trimEnd('/'))
            .refresh(cachedModified = 0.0) { listCallbacks++ }

        assertEquals(1, listCallbacks)
        assertTrue(ds.hasDetails)
        assertTrue(ds.detailsAreCurrent)
        assertEquals("文化服装学院", ds.points.single().name)
        assertEquals(90.0, ds.modifiedByBangumi[815878]!!, 1e-9)
        // ディスクキャッシュに落ちている
        assertTrue(File(cacheDir, "g.json").exists())
        assertTrue(File(cacheDir, "g3.json").exists())
    }

    @Test
    fun upToDateModifiedSkipsShardDownloads() = runTest {
        serveAll()
        val l = loader(server.url("/").toString().trimEnd('/'))
        l.refresh(cachedModified = 0.0) // キャッシュを温める
        val before = server.requestCount

        // g.json の modified(100.0) <= cachedModified なら分片はキャッシュを使う
        val ds = l.refresh(cachedModified = 100.0)
        assertEquals(before + 1, server.requestCount) // 増えるのは g.json の 1 本だけ
        assertTrue(ds.hasDetails)
    }

    @Test
    fun failedShardFallsBackToStaleCacheAndClearsCurrentFlag() = runTest {
        // まず旧世代のキャッシュを作る
        serveAll(shardName = "旧世代の名前")
        val origin = server.url("/").toString().trimEnd('/')
        loader(origin).refresh(cachedModified = 0.0)

        // g2 だけ 500 を返すサーバに切り替え
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/d/g.json" -> MockResponse.Builder().body(gJson).build()
                    path == "/d/g2.json" -> MockResponse.Builder().code(500).build()
                    path.matches(Regex("/d/g[0-6]\\.json")) ->
                        MockResponse.Builder().body(shardJson("新世代の名前")).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val ds = loader(origin).refresh(cachedModified = 0.0)
        // 古いキャッシュで代替した分片があるので detailsAreCurrent=false ——
        // 呼び出し側はこのとき modified 基線を保存してはいけない（永久に古いままになる）
        assertFalse(ds.detailsAreCurrent)
        assertTrue(ds.hasDetails) // 旧世代の詳細で埋まってはいる
    }

    @Test
    fun failoverPromotesRespondingOrigin() = runTest {
        val dead = MockWebServer()
        dead.start()
        val deadOrigin = dead.url("/").toString().trimEnd('/')
        dead.close() // 死んだノードにする

        serveAll()
        val l = loader(deadOrigin, server.url("/").toString().trimEnd('/'))
        val ds = l.refresh(cachedModified = 0.0)
        assertNotNull(ds)
        // 応答したノードが採用ノードに昇格している
        assertEquals(server.url("/").toString().trimEnd('/'), l.preferredOrigin)
    }

    @Test
    fun cancelledRefreshStopsBeforeFetchingShards() = runTest {
        serveAll()
        val l = loader(server.url("/").toString().trimEnd('/'))

        val outcome = runCatching {
            withContext(Dispatchers.Default) {
                l.refresh(cachedModified = 0.0) {
                    // 模拟「加载中点了重试按钮」:驱动它的 LaunchedEffect 被重新 key,旧协程取消。
                    currentCoroutineContext().job.cancel()
                }
            }
        }

        assertTrue(outcome.exceptionOrNull() is CancellationException)
        // 分片循环里没有天然挂起点,少了 ensureActive 就会把 7 个分片全部拉完,
        // 并与替换它的那一轮并发写同一批缓存文件。
        assertEquals(1, server.requestCount)
    }

    /**
     * 多轮加载重叠时,每个缓存文件都必须**整份**来自某一轮,且不留临时文件。
     *
     * 说明:这个测试**无法确定性地重现**「两次写入交织」的时间窗(它取决于两次 write 是否
     * 恰好重叠),因此不要把它当作 store() 原子性的唯一凭据 —— 唯一性临时名的必要性是
     * 靠代码审查确立的。这里守的是可确定性断言的那部分不变量。
     */
    @Test
    fun concurrentRefreshesLeaveEveryCacheFileIntact() = runTest {
        // 分片体量要够大,才会真的被写到一半就被另一方插入。
        val bodies = List(4) { shardJson(('A' + it).toString().repeat(400_000)) }
        val servers = List(3) { MockWebServer().also { s -> s.start() } }
        try {
            val all = listOf(server) + servers
            all.forEachIndexed { i, s -> s.dispatcher = fixedDispatcher(bodies[i]) }

            // 多个 loader 共用同一个 cacheDir —— 正是「上一轮加载没被取消掉」时的样子。
            val loaders = all.map { loader(it.url("/").toString().trimEnd('/')) }
            withContext(Dispatchers.IO) {
                loaders.map { l -> launch { l.refresh(cachedModified = 0.0) } }.forEach { it.join() }
            }

            // 每个分片文件必须**整份**来自其中一方,不能是多次写入交织的产物。
            for (shard in 0 until AnitabiDataLoader.DETAIL_SHARD_COUNT) {
                val text = File(cacheDir, "g$shard.json").readText()
                assertTrue("g$shard.json 被写坏了(长度 ${text.length})", text in bodies)
            }
            // 临时文件不留下来。
            assertTrue(cacheDir.listFiles().orEmpty().none { it.name.contains(".tmp") })
        } finally {
            servers.forEach { it.close() }
        }
    }

    private fun fixedDispatcher(shardBody: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                path == "/d/g.json" -> MockResponse.Builder().body(gJson).build()
                path.matches(Regex("/d/g[0-6]\\.json")) -> MockResponse.Builder().body(shardBody).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }

    @Test
    fun loadFromCacheAssemblesWithoutNetwork() = runTest {
        serveAll()
        val origin = server.url("/").toString().trimEnd('/')
        loader(origin).refresh(cachedModified = 0.0)

        // ネットワーク無し（origins が全部死んでいる想定）でもディスクから組める
        val offline = loader("http://127.0.0.1:1")
        val ds = offline.loadFromCache()!!
        assertTrue(ds.hasDetails)
        assertEquals("文化服装学院", ds.points.single().name)
    }
}

package cn.anitabi.map.data.update

import cn.anitabi.map.data.AnitabiPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 检查更新的状态机:与真实 GitHub 的差别只有 URL。
 * 24 小时门用注入的时钟测;失败(含限流)必须不写时间戳、不清缓存。
 * checker 自带 IO 作用域(真实线程),所以用 runBlocking 而不是 runTest 的虚拟时间。
 */
class UpdateCheckerTest {

    private class FakePrefs : AnitabiPrefs {
        override var cachedModified: Double = 0.0
        override var lastVisitedBangumiId: Int = 0
        override var recentVisitIds: List<Int> = emptyList()
        override var mapBaseStyle: String = "standard"
        override var isPhotoLayerVisible: Boolean = true
        override var pointVisitFilter: String = "All"
        override var restoredDeepLink: String? = null
        override var hasCompletedOnboarding: Boolean = false
        override var isnetExperimentEnabled: Boolean = false
        override var updateAutoCheckEnabled: Boolean = true
        override var updateLastCheckedAt: Long = 0L
        override var updateLatestTag: String? = null
        override var updateLatestUrl: String? = null
        override var updateSkippedTag: String? = null
    }

    private lateinit var server: MockWebServer
    private val prefs = FakePrefs()
    private var now = 1_000_000_000_000L
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun checker(installed: String = "0.1.1") =
        UpdateChecker(client, prefs, installed, apiUrl = server.url("/releases/latest").toString(), clock = { now })

    private fun release(tag: String, prerelease: Boolean = false) = MockResponse.Builder()
        .code(200)
        .body("""{"tag_name":"$tag","html_url":"https://github.com/AbuCuma/junrei-map/releases/tag/$tag","prerelease":$prerelease}""")
        .build()

    /** checkNow 同步进入 Checking;等它离开 Checking 为止。 */
    private suspend fun UpdateChecker.settled(): UpdateChecker.State = withTimeout(5_000) {
        state.first { it !is UpdateChecker.State.Checking }
    }

    @Test
    fun newerTagIsAvailableAndCached() = runBlocking {
        server.enqueue(release("v0.2.0"))
        val c = checker()
        c.checkNow()
        val s = c.settled() as UpdateChecker.State.Available
        assertEquals("v0.2.0", s.release.tag)
        assertTrue(!s.skipped)
        assertEquals(now, prefs.updateLastCheckedAt)
        assertEquals("v0.2.0", prefs.updateLatestTag)
        assertEquals("application/vnd.github+json", server.takeRequest().headers["Accept"])
    }

    @Test
    fun sameOrOlderTagIsUpToDate() = runBlocking {
        server.enqueue(release("v0.1.1"))
        val c = checker()
        c.checkNow()
        assertEquals(UpdateChecker.State.UpToDate, c.settled())

        server.enqueue(release("v0.1.0"))
        c.checkNow()
        assertEquals(UpdateChecker.State.UpToDate, c.settled())
    }

    @Test
    fun prereleaseAndUnparsableInstalledVersionNeverPrompt() = runBlocking {
        server.enqueue(release("v9.0.0", prerelease = true))
        val c = checker()
        c.checkNow()
        assertEquals(UpdateChecker.State.UpToDate, c.settled())

        server.enqueue(release("v9.0.0"))
        val weird = checker(installed = "local-build")
        weird.checkNow()
        assertEquals(UpdateChecker.State.UpToDate, weird.settled())
    }

    @Test
    fun rateLimitIsAFailureThatKeepsTimestampAndCache() = runBlocking {
        prefs.updateLatestTag = "v0.2.0"
        prefs.updateLatestUrl = "https://github.com/AbuCuma/junrei-map/releases/tag/v0.2.0"
        server.enqueue(MockResponse.Builder().code(403).body("""{"message":"API rate limit exceeded"}""").build())
        val c = checker()
        // 缓存播种:还没发请求就已经知道有新版本
        assertTrue(c.state.value is UpdateChecker.State.Available)
        c.checkNow()
        val s = c.settled() as UpdateChecker.State.Failed
        assertTrue(s.rateLimited)
        assertEquals(0L, prefs.updateLastCheckedAt)
        assertEquals("v0.2.0", prefs.updateLatestTag)
    }

    @Test
    fun malformedBodyIsAFailureNotUpToDate() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"message":"Not Found"}""").build())
        val c = checker()
        c.checkNow()
        assertTrue(c.settled() is UpdateChecker.State.Failed)
        assertEquals(0L, prefs.updateLastCheckedAt)
    }

    @Test
    fun checkIfDueHonoursTheDailyGateButClockRewindCounts() = runBlocking {
        server.enqueue(release("v0.1.1"))
        val c = checker()
        c.checkIfDue()
        c.settled()
        assertEquals(1, server.requestCount)

        now += UpdateChecker.CHECK_INTERVAL_MS - 1
        c.checkIfDue()
        assertEquals("24 小时内不该再发请求", 1, server.requestCount)

        server.enqueue(release("v0.1.1"))
        now += 2
        c.checkIfDue()
        c.settled()
        assertEquals(2, server.requestCount)

        // 时钟被拨回很久以前:按到期处理
        server.enqueue(release("v0.1.1"))
        now -= 3 * UpdateChecker.CHECK_INTERVAL_MS
        c.checkIfDue()
        c.settled()
        assertEquals(3, server.requestCount)
    }

    @Test
    fun skippingHidesOnlyTheHomeCard() = runBlocking {
        server.enqueue(release("v0.2.0"))
        val c = checker()
        c.checkNow()
        val available = c.settled() as UpdateChecker.State.Available
        c.skip(available.release)
        assertEquals("v0.2.0", prefs.updateSkippedTag)
        assertTrue((c.state.value as UpdateChecker.State.Available).skipped)

        // 下一个 tag 出现:忽略自动失效
        server.enqueue(release("v0.2.1"))
        c.checkNow()
        val next = c.settled() as UpdateChecker.State.Available
        assertTrue(!next.skipped)
        assertNull(prefs.updateLatestTag?.takeIf { it == "v0.2.0" })
    }
}

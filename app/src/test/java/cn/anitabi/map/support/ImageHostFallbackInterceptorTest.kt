package cn.anitabi.map.support

import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 图片兜底拦截器的端到端行为。用 MockWebServer 冒充两个图片 host
 * （DNS 全部解析到本机回环，端口相同 —— 所以两个 host 打到同一台服务器，
 * 靠请求序号编排每次的应答），断言实际尝试过的 URL 序列。
 */
class ImageHostFallbackInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            // 把两个图片域名都解析到 MockWebServer
            .dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) })
            .addInterceptor(ImageHostFallbackInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** 用 host 名请求，端口指向 MockWebServer。 */
    private fun get(host: String, pathAndQuery: String) =
        client.newCall(
            Request.Builder().url("http://$host:${server.port}$pathAndQuery").build()
        ).execute()

    private fun attemptedPaths(count: Int): List<String> =
        (0 until count).map { server.takeRequest().let { r -> "${r.headers["Host"]}${r.url.encodedPath}?${r.url.query}" } }

    @Test
    fun succeedsOnFirstTryWithoutFallback() {
        server.enqueue(MockResponse(code = 200, body = "ok"))

        get(PRIMARY, "/points/1/a.jpg?plan=h360").use { assertEquals(200, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesOnMirrorHostThenAlternatePlan() {
        // 525 = Cloudflare 回源握手失败（本轮实测的主要故障形态）
        server.enqueue(MockResponse(code = 525))
        server.enqueue(MockResponse(code = 525))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        get(PRIMARY, "/points/1/a.jpg?plan=h360").use { assertEquals(200, it.code) }

        assertEquals(3, server.requestCount)
        val attempts = attemptedPaths(3)
        assertTrue("① 原样请求主 host", attempts[0].startsWith(PRIMARY))
        assertTrue("① 保持 h360", attempts[0].endsWith("plan=h360"))
        assertTrue("② 换镜像 host", attempts[1].startsWith(MIRROR))
        assertTrue("② 尺寸不变", attempts[1].endsWith("plan=h360"))
        assertTrue("③ 回主 host", attempts[2].startsWith(PRIMARY))
        assertTrue("③ 换成另一档尺寸", attempts[2].endsWith("plan=h160"))
    }

    @Test
    fun alternatePlanGoesBothWays() {
        // 实测同一张图两档缓存命中互有胜负，h160 也要能升到 h360
        server.enqueue(MockResponse(code = 525))
        server.enqueue(MockResponse(code = 525))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        get(PRIMARY, "/points/1/a.jpg?plan=h160").use { assertEquals(200, it.code) }

        assertTrue(attemptedPaths(3)[2].endsWith("plan=h360"))
    }

    @Test
    fun completeSizeIsNotSilentlyDowngraded() {
        // 无 plan = 完整尺寸（抠图参考图用）。只换 host，不得降档到缩略图。
        server.enqueue(MockResponse(code = 525))
        server.enqueue(MockResponse(code = 525))

        get(PRIMARY, "/points/1/a.jpg?").use { assertEquals(525, it.code) }

        assertEquals("完整尺寸只有两个候选", 2, server.requestCount)
    }

    @Test
    fun retriesOn404BecauseNamespacesDiffer() {
        // 约三分之一的路径不在开放 API 文档收录的命名空间内，主 host 可能没有
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 200, body = "ok"))

        get(PRIMARY, "/ptheme/1_100_76.webp?v=heqld").use { assertEquals(200, it.code) }

        assertEquals(2, server.requestCount)
        assertTrue("其它查询参数要保留", attemptedPaths(2)[1].contains("v=heqld"))
    }

    @Test
    fun notFoundOnBothHostsDoesNotTryAnotherSize() {
        // 换过 host 仍 404 → 对象多半真不存在。换尺寸是同 host 同路径、只改 plan，
        // 必然同样 404，白花一次请求（缺图在用户投稿里并不罕见）。
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 404))

        get(PRIMARY, "/points/1/missing.jpg?plan=h360").use { assertEquals(404, it.code) }

        assertEquals("404 只换 host，不换尺寸", 2, server.requestCount)
    }

    @Test
    fun nonImageHostIsPassedThroughUntouched() {
        // 数据接口有 AnitabiDataLoader 自己的 ORIGINS 故障转移，这里不能插手
        server.enqueue(MockResponse(code = 503))

        get("ww.anitabi.cn", "/d/g.json?d=qlpo").use { assertEquals(503, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun bgmProxyHostIsPassedThroughUntouched() {
        // bangumi 封面经 bgm-api 代理，是绝对 URL。host 判定必须是精确匹配 ——
        // 若日后被"简化"成 endsWith("anitabi.cn")，这些封面会被误换到图片 host。
        server.enqueue(MockResponse(code = 525))

        get("bgm-api.anitabi.cn", "/img/pic/cover/l/x.jpg?plan=h160").use {
            assertEquals(525, it.code)
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun returnsLastFailureWhenEveryCandidateFails() {
        server.enqueue(MockResponse(code = 525))
        server.enqueue(MockResponse(code = 525))
        server.enqueue(MockResponse(code = 404))

        get(PRIMARY, "/points/1/a.jpg?plan=h360").use { assertEquals(404, it.code) }

        assertEquals(3, server.requestCount)
    }

    private companion object {
        const val PRIMARY = "image-anitabi.magiconch.com"
        const val MIRROR = "image.anitabi.cn"
    }
}

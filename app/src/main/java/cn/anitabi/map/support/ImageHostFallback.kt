package cn.anitabi.map.support

import cn.anitabi.map.data.model.AnitabiImage
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 图片请求的逐请求兜底：取不到时换 host、换尺寸再试，最多 3 次。
 *
 * 候选顺序：① 原样 → ② 换另一个 host → ③ 换另一档尺寸（仍在原 host）。
 *
 * 由来（2026-08 实测）：
 * - 两个 host 各有盲区。[AnitabiImage.FALLBACK_HOST]（官方文档地址）只收录 `points/`
 *   与 `bangumi/`，数据里约三分之一的路径（`user/`、`ptheme/`、`icon/`）不在其中；
 *   而主 host 虽然五个命名空间实测都可用，却是未文档化的内部管线，随时可能变动。
 * - 源站对 Cloudflare 不可达期间，只有边缘缓存里的变体能取到，而**同一张图的
 *   h160 与 h360 命中情况互有胜负**（抽样里两种缺失都出现过），因此尺寸也互换重试。
 *   注意这条只对 5xx 成立 —— 404 与尺寸无关，见 [worthRetrying]。
 *
 * **兜底是每张图一次性的成本，不是常态**：拦截器在 Coil 之下改写 URL，所以兜底成功的
 * 响应仍以**最初请求的 URL** 为键落进 Coil 磁盘缓存，下次不会再走候选链。
 *
 * 触发条件：连接异常（DNS/超时）、5xx（含回源失败的 525）、404。
 * 只作用于图片 host —— 数据接口有 AnitabiDataLoader 自己的 ORIGINS 故障转移。
 */
internal class ImageHostFallbackInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val hosts = hostCandidates(request.url) ?: return chain.proceed(request)

        var failure: Response? = null
        var error: IOException? = null

        /** 试一个候选：成功（或不值得重试）就返回响应，否则记下失败并返回 null。 */
        fun attempt(url: HttpUrl): Response? {
            val response = try {
                chain.proceed(request.newBuilder().url(url).build())
            } catch (e: IOException) {
                error = e
                return null
            }
            // 换下一个候选前必须关掉失败的响应体，否则连接不会归还连接池。
            failure?.close()
            if (response.isSuccessful || !worthRetrying(response.code)) return response
            failure = response
            return null
        }

        for (url in hosts) attempt(url)?.let { return it }
        // 所有 host 都失败后才考虑换尺寸。**404 除外**：换过 host 仍是 404 意味着对象
        // 多半真的不存在，而换尺寸是同 host 同路径、只改 plan，必然同样 404 ——
        // 白花一次请求。5xx（回源失败）才有「另一档尺寸恰好还在边缘缓存里」的可能。
        if (failure?.code != 404) {
            alternatePlan(request.url)?.let { alt -> attempt(alt)?.let { return it } }
        }

        failure?.let { return it }
        throw error ?: IOException("image request failed: ${request.url}")
    }

    /**
     * 图片 host 的候选（原样 → 另一个 host）。非图片 host 返回 null（原样放行）。
     * 两个常量若被设成同值，去重可避免每张失败图悄悄翻倍请求。
     */
    private fun hostCandidates(url: HttpUrl): List<HttpUrl>? {
        val primary = AnitabiImage.PRIMARY_HOST.toHttpUrlOrNull()?.host ?: return null
        val fallback = AnitabiImage.FALLBACK_HOST.toHttpUrlOrNull()?.host ?: return null
        val other = when {
            url.host.equals(primary, ignoreCase = true) -> fallback
            url.host.equals(fallback, ignoreCase = true) -> primary
            else -> return null
        }
        return listOf(url, url.newBuilder().host(other).build()).distinct()
    }

    /** h160 ⇄ h360 互换；完整尺寸（无 plan）不参与，避免把高分需求悄悄降档。 */
    private fun alternatePlan(url: HttpUrl): HttpUrl? {
        val alternate = when (url.queryParameter("plan")) {
            "h160" -> "h360"
            "h360" -> "h160"
            else -> return null
        }
        return url.newBuilder().setQueryParameter("plan", alternate).build()
    }

    /**
     * 5xx（525=Cloudflare 回源握手失败）与 404 都值得换 host 再试 —— 两个 host 收录的
     * 命名空间不同，一边 404 另一边未必没有。但 404 不再触发换尺寸（见 intercept）。
     */
    private fun worthRetrying(code: Int): Boolean = code >= 500 || code == 404
}


package cn.anitabi.map.support

import cn.anitabi.map.data.model.LatLon
import java.net.URLDecoder

/**
 * 深链 / 状态恢复共用的地图状态（iOS MapDeepLink 的移植）。
 * 接受的形式：
 * - `anitabi://map?...`（host == "map" 或 path == "/map"）
 * - `https://{host}/map?...`，host 白名单 = anitabi.cn / www / ww / w.junreimap.com
 * 参数：bangumiId / pid / c=lng,lat（**经度在前**，与 web 相同）/ z / bids（逗号分隔）。
 * 一个都读不出则为 null；单个损坏的参数静默丢弃（经分享 App 传来的 URL
 * 经常是坏的）。
 */
data class MapDeepLink(
    val bangumiId: Int? = null,
    val pointId: String? = null,
    val center: LatLon? = null,
    val zoom: Double? = null,
    val bids: List<Int> = emptyList(),
) {
    val isEmpty: Boolean
        get() = bangumiId == null && pointId == null && center == null &&
            zoom == null && bids.isEmpty()

    companion object {
        /** manifest intent-filter 声明的同一组 host(ExternalLinks 用它识别"自家链接")。 */
        val ALLOWED_HOSTS = setOf(
            "anitabi.cn", "www.anitabi.cn", "ww.anitabi.cn", "w.junreimap.com",
        )

        /** 从 URI 字符串解析。非目标 URL、或全部参数均不可读时返回 null。 */
        fun parse(uri: String): MapDeepLink? {
            val schemeEnd = uri.indexOf("://")
            if (schemeEnd < 0) return null
            val scheme = uri.substring(0, schemeEnd).lowercase()
            val rest = uri.substring(schemeEnd + 3)
            val query = rest.substringAfter('?', missingDelimiterValue = "")
            val hostAndPath = rest.substringBefore('?')
            val host = hostAndPath.substringBefore('/').lowercase()
            val path = "/" + hostAndPath.substringAfter('/', missingDelimiterValue = "")

            val isMapTarget = when (scheme) {
                "anitabi" -> host == "map" || path == "/map"
                "https" -> host in ALLOWED_HOSTS && path == "/map"
                else -> false
            }
            if (!isMapTarget) return null

            val params = HashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore('=')
                val value = urlDecode(pair.substringAfter('=', missingDelimiterValue = ""))
                if (key.isNotEmpty() && !params.containsKey(key)) params[key] = value
            }

            val bangumiId = params["bangumiId"]?.toIntOrNull()
            val pointId = params["pid"]?.takeIf { it.isNotBlank() }
            // `c` 是**经度在前**（与 web 相同）。±180 / ±90 范围校验。
            val center = params["c"]?.split(',')?.takeIf { it.size == 2 }?.let { parts ->
                val lng = parts[0].trim().toDoubleOrNull()
                val lat = parts[1].trim().toDoubleOrNull()
                if (lng != null && lat != null &&
                    lng in -180.0..180.0 && lat in -90.0..90.0
                ) {
                    LatLon(lat, lng)
                } else {
                    null
                }
            }
            val zoom = params["z"]?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val bids = params["bids"]?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?: emptyList()

            val link = MapDeepLink(bangumiId, pointId, center, zoom, bids)
            return if (link.isEmpty) null else link
        }

        private fun urlDecode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}

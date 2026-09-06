package cn.anitabi.map.support

import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.data.model.nilIfBlank
import org.json.JSONObject

/**
 * 拼装交给 Web 版的 URL（iOS AnitabiWebLink 的移植）。域名用 `ww.anitabi.cn` ——
 * `anitabi.cn` 会 301 到无法解析的 `www`，原样传过去会以「找不到服务器」
 * 告终（若恢复了，改 PAGE_HOST 一行即可切回）。查询串自己拼：标准 encoder 对 `&` 等的
 * 处理与 Web 版不一致。
 */
object AnitabiWebLink {

    private const val PAGE_HOST = "https://ww.anitabi.cn"

    /** 一切都失败时的落点（地图首页）。 */
    const val FALLBACK = "$PAGE_HOST/map"

    // MARK: 地图（canonical）

    /**
     * 用于分享、「在网页版打开」的正规 URL。`c` 是 **lng 在前**（沿用 Web 版的排列），
     * 小数 4 位，`bids` 逗号分隔。省略的参数不出现在 URL 中。
     */
    fun canonical(
        bangumiId: Int? = null,
        pointId: String? = null,
        coordinate: LatLon? = null,
        zoom: Double? = null,
        bids: List<Int> = emptyList(),
    ): String {
        val items = ArrayList<Pair<String, String>>()
        bangumiId?.let { items.add("bangumiId" to it.toString()) }
        pointId.nilIfBlank()?.let { items.add("pid" to it) }
        coordinate?.let { items.add("c" to "%.4f,%.4f".format(it.lng, it.lat)) }
        zoom?.let { items.add("z" to trimmed(it)) }
        if (bids.isNotEmpty()) items.add("bids" to bids.joinToString(","))
        if (items.isEmpty()) return FALLBACK
        return url("$PAGE_HOST/map", items)
    }

    // MARK: 贡献流程

    /** 补传截图（既有地标的编辑模式）。与 Web 版 `pn()` 同形的 `?data={JSON}&bangumiId&type=edit`。 */
    fun createPoint(point: ScenePoint, bangumi: BangumiLite?): String {
        val payload = JSONObject()
        payload.put("id", point.id)
        payload.put("geo", org.json.JSONArray(listOf(point.lat, point.lng)))
        point.name.nilIfBlank()?.let { payload.put("name", it) }
        point.nameCn.nilIfBlank()?.let { payload.put("cn", it) }
        point.ep.nilIfBlank()?.let { payload.put("ep", it) }
        point.timecodeS?.let { payload.put("s", it) }
        point.image.nilIfBlank()?.let { payload.put("image", it) }
        return url(
            "$PAGE_HOST/create-point",
            listOf(
                "data" to payload.toString(),
                "bangumiId" to (bangumi?.id ?: point.bangumiId).toString(),
                "type" to "edit",
            ),
        )
    }

    /**
     * 长按落针的「在此添加地标」。是还不属于任何作品的**新增**地标，
     * 因此不带 id/bangumiId/type=edit，只传最小合法负载 `{"geo":[lat,lng]}`。
     */
    fun createPoint(at: LatLon): String {
        val payload = JSONObject().put("geo", org.json.JSONArray(listOf(at.lat, at.lng)))
        return url("$PAGE_HOST/create-point", listOf("data" to payload.toString()))
    }

    /**
     * 修正坐标。原样排列 Web 版 `ms()` 的 15 个参数（缺失的值也**以空字符串发送**
     * —— Web 侧的表单没有考虑「键不存在」的情况）。
     */
    fun fixPointGps(point: ScenePoint, bangumi: BangumiLite?): String = url(
        "$PAGE_HOST/fix-point-gps",
        listOf(
            "pid" to point.id,
            "pointName" to (point.nameCn.nilIfBlank() ?: point.name.nilIfBlank() ?: ""),
            "geo0" to point.lat.toString(),
            "geo1" to point.lng.toString(),
            "ep" to (point.ep.nilIfBlank() ?: ""),
            "s" to (point.timecodeS?.toString() ?: ""),
            "pointImage" to (point.image.nilIfBlank() ?: ""),
            "pointOrigin" to (point.origin.nilIfBlank() ?: ""),
            "bangumiId" to (bangumi?.id ?: point.bangumiId).toString(),
            "bangumiName" to (bangumi?.cn.nilIfBlank() ?: bangumi?.title.nilIfBlank() ?: ""),
            "bangumiCity" to (bangumi?.city.nilIfBlank() ?: ""),
            "bangumiCat" to (bangumi?.cat.nilIfBlank() ?: ""),
            "bangumiCp" to (bangumi?.title.nilIfBlank() ?: ""),
            "bangumiCover" to (bangumi?.cover.nilIfBlank() ?: ""),
            "bangumiColor" to (bangumi?.colorHex.nilIfBlank() ?: ""),
        ),
    )

    // MARK: 外部地图

    /** Google 地图。装有 Google Maps 应用时会自动由它打开。 */
    fun googleMaps(lat: Double, lng: Double): String =
        url("https://www.google.com/maps", listOf("q" to "%.6f,%.6f".format(lat, lng)))

    /**
     * 街景。`layer=c` + `cbll` 是应用侧也能解析的经典形式 ——
     * `?api=1&map_action=pano` 有时传不到 Google Maps 应用。
     */
    fun streetView(lat: Double, lng: Double): String =
        "https://maps.google.com/maps?q=&layer=c&cbll=%.6f,%.6f&cbp=11,0,0,0,0".format(lat, lng)

    /** 巡礼礼仪的共同文档（与 Web 版 ⚠️ 图标相同的跳转目标）。 */
    const val ETIQUETTE_ISSUE = "https://github.com/anitabi/anitabi.cn-document/issues/29"

    /** 「关于」的「巡礼数据反馈」。不是特定 issue 而是列表。 */
    const val FEEDBACK_ISSUES = "https://github.com/anitabi/anitabi.cn-document/issues"

    // MARK: 拼装

    /** 不放行 `&+=?#/` 的查询值编码。逗号为对齐 Web 版的观感而保留。 */
    private fun encode(value: String): String {
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            when {
                c.isLetterOrDigit() && c.code < 128 -> sb.append(c)
                c in "-._~!$'()*;:@,[]" -> sb.append(c)
                else -> sb.append("%%%02X".format(byte))
            }
        }
        return sb.toString()
    }

    private fun url(base: String, items: List<Pair<String, String>>): String {
        if (items.isEmpty()) return base
        val query = items.joinToString("&") { "${it.first}=${encode(it.second)}" }
        return "$base?$query"
    }

    /** 输出 `z=14` 而不是 `z=14.0`（仅整数 zoom 时去掉小数点）。 */
    private fun trimmed(value: Double): String =
        if (value == Math.rint(value)) value.toInt().toString() else "%.2f".format(value)
}

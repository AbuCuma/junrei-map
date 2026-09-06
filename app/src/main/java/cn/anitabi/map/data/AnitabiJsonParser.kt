package cn.anitabi.map.data

import cn.anitabi.map.data.model.AnitabiDataset
import cn.anitabi.map.data.model.BangumiLite
import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.PointDetail
import cn.anitabi.map.data.model.PointGeo
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.data.model.nilIfBlank
import org.json.JSONArray
import org.json.JSONObject

// 基于位置的数组解析（iOS AnitabiDataLoader 的 parse 系列的移植）。
// iOS 是对 JSONSerialization 的 DOM 做带范围检查的取值 —— 这里沿用同一方针，
// 宽容地读取 org.json（Android 运行时自带，JVM 测试中为 test 依赖）的 DOM。

object AnitabiJsonParser {

    /**
     * `/d/g.json` → 作品列表 + 全部地标的坐标。根是 `[bangumiLiteList, pageSize, modified]`，
     * 1 个作品是 18 个元素：`0:id 1:cn 2:en 3:title 4:city 5:color 6:cover 7:score 8:cat 9:lat
     * 10:lng 11:zoom 12:points 13:abbr 14:tags 15:priority 16:icon 17:tAbbr`（16 未使用）。
     *
     * 14/17 只供搜索用：14 是人工维护的别名数组（如 `["千恋＊万花","qlhw"]`，实测 1514 部
     * 里 43 部有），17 是日文简称（如 `"推しの子"`，实测 631 部＝41% 有）。16 是图标路径，
     * 图标另有 `bangumi-icons.json` 一路，这里不需要。
     */
    fun parseBangumiList(text: String): AnitabiDataset? {
        val root = runCatching { JSONArray(text) }.getOrNull() ?: return null
        val rawList = root.optJSONArray(0) ?: return null
        val modified = root.double(2) ?: 0.0

        val bangumis = ArrayList<BangumiLite>(rawList.length())
        val points = ArrayList<ScenePoint>(rawList.length() * 30)

        for (i in 0 until rawList.length()) {
            val record = rawList.optJSONArray(i) ?: continue
            val id = record.int(0) ?: continue

            val geos = parsePointGeos(record.optJSONArray(12))
            bangumis.add(
                BangumiLite(
                    id = id,
                    cn = record.string(1),
                    en = record.string(2),
                    title = record.string(3),
                    city = record.string(4),
                    colorHex = record.string(5),
                    cover = record.string(6),
                    score = record.double(7),
                    cat = record.string(8),
                    center = record.coordinate(latIndex = 9, lngIndex = 10),
                    zoom = record.double(11),
                    points = geos,
                    abbr = record.string(13),
                    tags = record.stringList(14),
                    priority = record.int(15) ?: 999,
                    titleAbbr = record.string(17),
                )
            )
            for (geo in geos) {
                points.add(
                    ScenePoint(
                        id = geo.id,
                        bangumiId = id,
                        lat = geo.lat,
                        lng = geo.lng,
                        priority = geo.priority,
                    )
                )
            }
        }
        return AnitabiDataset(
            bangumis = bangumis,
            points = points,
            modified = modified,
            hasDetails = false,
        )
    }

    /** 作品记录的第 12 项。`[id, lat, lng, priority]` 以每 4 个一组平铺排列。 */
    private fun parsePointGeos(flat: JSONArray?): List<PointGeo> {
        if (flat == null || flat.length() < 4) return emptyList()
        val result = ArrayList<PointGeo>(flat.length() / 4)
        var index = 0
        while (index + 3 < flat.length()) {
            val id = flat.string(index)
            val lat = flat.double(index + 1)
            val lng = flat.double(index + 2)
            val priority = flat.int(index + 3) ?: 0
            index += 4
            if (id == null || lat == null || lng == null) continue
            if (!LatLon(lat, lng).isValid) continue
            if (lat == 0.0 && lng == 0.0) continue
            result.add(PointGeo(id = id, lat = lat, lng = lng, priority = priority))
        }
        return result
    }

    /**
     * `/d/g{n}.json` → 按地标 ID 的详情。1 个 cell 是 `[bangumiId, theme, points[], modified]`，
     * 1 个地标是 15 个元素：`0:id 1:name 2:folderName 3:isFolder 4:mid 5:uid 6:image 7:fid 8:ep
     * 9:timecodeS 10:note 11:origin 12:originLink 13:cn`（**与 web 的 JS 解析排列不同**）。
     */
    fun parsePointDetails(
        text: String,
        into: MutableMap<String, PointDetail>,
        modified: MutableMap<Int, Double>,
    ) {
        val cells = runCatching { JSONArray(text) }.getOrNull() ?: return
        for (c in 0 until cells.length()) {
            val cell = cells.optJSONArray(c) ?: continue
            val bangumiId = cell.int(0)
            val cellModified = cell.double(3)
            if (bangumiId != null && cellModified != null) {
                modified[bangumiId] = cellModified
            }
            val rawPoints = cell.optJSONArray(2) ?: continue
            for (p in 0 until rawPoints.length()) {
                val point = rawPoints.optJSONArray(p) ?: continue
                val id = point.string(0) ?: continue
                into[id] = PointDetail(
                    name = point.string(1),
                    nameCn = point.string(13),
                    image = point.string(6),
                    ep = point.episode(8),
                    isFolder = point.bool(3),
                    folderName = point.string(2),
                    mid = point.string(4),
                    uid = point.int(5),
                    fid = point.string(7),
                    timecodeS = point.int(9),
                    note = point.string(10),
                    origin = point.string(11),
                    originLink = point.string(12),
                )
            }
        }
    }

    fun merge(details: Map<String, PointDetail>, points: List<ScenePoint>): List<ScenePoint> {
        if (details.isEmpty()) return points
        return points.map { point ->
            val d = details[point.id] ?: return@map point
            point.copy(
                name = d.name,
                nameCn = d.nameCn,
                image = d.image,
                ep = d.ep,
                isFolder = d.isFolder,
                folderName = d.folderName,
                mid = d.mid,
                uid = d.uid,
                fid = d.fid,
                timecodeS = d.timecodeS,
                note = d.note,
                origin = d.origin,
                originLink = d.originLink,
            )
        }
    }

    /** `id,昵称` 格式的 CSV。昵称可能含逗号，因此**只按第一个逗号**分割。 */
    fun parseUsers(text: String): Map<Int, String> {
        val result = HashMap<Int, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val commaIndex = line.indexOf(',')
            if (commaIndex < 0) continue
            val id = line.substring(0, commaIndex).toIntOrNull() ?: continue
            val name = line.substring(commaIndex + 1).nilIfBlank() ?: continue
            result[id] = name
        }
        return result
    }

    data class IconIndex(val ids: List<Int>, val spriteUrl: String)

    /**
     * `{ "src": "/d/bangumi-icons.webp?v=hash", "ids": [id, ...] }`。
     * `src` 以相对路径给出，转成以传入的分发节点为基准的绝对 URL。
     */
    fun parseIconIndex(text: String, origin: String): IconIndex? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val src = root.optString("src").nilIfBlank() ?: return null
        val rawIds = root.optJSONArray("ids") ?: return null
        val url = absoluteDPath(src, origin)
        val ids = ArrayList<Int>(rawIds.length())
        for (i in 0 until rawIds.length()) {
            rawIds.int(i)?.let(ids::add)
        }
        return IconIndex(ids = ids, spriteUrl = url)
    }

    /** 把 `/d/...` 的相对路径转成以指定节点为基准的绝对 URL。 */
    private fun absoluteDPath(path: String, origin: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> origin + path
        else -> "$origin/$path"
    }
}

// MARK: 基于位置的数组的安全取值（iOS Array<Any> 扩展的移植）

private fun JSONArray.value(index: Int): Any? =
    if (index in 0 until length()) opt(index).takeIf { it != JSONObject.NULL } else null

private fun JSONArray.string(index: Int): String? = (value(index) as? String).nilIfBlank()

/**
 * 字符串数组（tags）。非数组、含非字符串、含空串都按本文件一贯的容错原则处理：
 * 能取多少取多少，取不到就是空列表 —— 别名搜不到远好过整条记录解析失败。
 */
private fun JSONArray.stringList(index: Int): List<String> {
    val array = value(index) as? JSONArray ?: return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        (array.value(i) as? String).nilIfBlank()?.let(out::add)
    }
    return out
}

private fun JSONArray.double(index: Int): Double? = when (val v = value(index)) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

private fun JSONArray.int(index: Int): Int? = when (val v = value(index)) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}

/** 集数可能是数值，也可能是时间码字符串。 */
private fun JSONArray.episode(index: Int): String? = when (val v = value(index)) {
    is String -> v.nilIfBlank()
    is Int, is Long -> v.toString()
    is Number -> {
        val d = v.toDouble()
        if (d == Math.rint(d)) d.toInt().toString() else d.toString()
    }
    else -> null
}

/**
 * `isFolder` 等标志位。接受 Bool / 0-1 数值 / "true"-"1" 字符串，
 * 其余情况（含未设置）降级为 false。
 */
private fun JSONArray.bool(index: Int): Boolean = when (val v = value(index)) {
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v == "true" || v == "1"
    else -> false
}

private fun JSONArray.coordinate(latIndex: Int, lngIndex: Int): LatLon? {
    val lat = double(latIndex) ?: return null
    val lng = double(lngIndex) ?: return null
    if (lat == 0.0 && lng == 0.0) return null
    return LatLon(lat, lng)
}

package cn.anitabi.map.map.google

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cn.anitabi.map.data.model.LatLon
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/**
 * 作品模式的压暗图层（iOS WorkRegionOverlay 的绘制侧）。
 *
 * Google 的 Polygon 在「跨 ±180° 的全球外环 + 洞」下 tessellation 会坏掉（实测），
 * 因此用**一块带洞矩形 + 上下左右 4 块纯矩形带**把全球（lat ±85）无缝铺满：
 *
 * ```
 * ┌───────── 上带 ─────────┐
 * ├─左带─┬─ 带洞矩形 ─┬─右带─┤   ← 洞 = 外扩后的凸包
 * ├───────── 下带 ─────────┤
 * ```
 *
 * 描边不用 Polygon 的 stroke，而是沿凸包的 Polyline（避免在矩形接缝处出现线条）。
 */
class WorkRegionOverlayRenderer(
    private val map: GoogleMap,
    private val density: Float,
) {
    private val polygons = ArrayList<Polygon>(5)
    private var edge: Polyline? = null

    /** 正在显示的作品 ID（同一作品不重建 — iOS applyRegionOverlay 的去重）。 */
    var currentWorkId: Int? = null
        private set

    /** 内环顶点。用于点按的内外判定（点在暗幕外侧 → 退出作品模式）。 */
    var hull: List<LatLon> = emptyList()
        private set

    fun apply(workId: Int, hullVertices: List<LatLon>, themeColor: Color) {
        if (workId == currentWorkId) return
        clear()
        if (hullVertices.size < 3) return

        val fill = themeColor.copy(alpha = 0.5f).toArgb()

        // 带洞矩形取 hull bbox 外扩 3°（范围局部，tessellation 才稳定）。
        val holeHostMinLat = (hullVertices.minOf { it.lat } - 3).coerceAtLeast(-85.0)
        val holeHostMaxLat = (hullVertices.maxOf { it.lat } + 3).coerceAtMost(85.0)
        val holeHostMinLng = (hullVertices.minOf { it.lng } - 3).coerceAtLeast(-179.9)
        val holeHostMaxLng = (hullVertices.maxOf { it.lng } + 3).coerceAtMost(179.9)

        fun addRect(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double, hole: List<LatLng>?) {
            if (minLat >= maxLat || minLng >= maxLng) return
            // Google Polygon 会按「经度最短方向」连接相邻顶点：Δlng > 180° 的边会被反向
            // 折叠，条带被压成细片（实测）。沿纬线的长边按 ≤60° 间隔插入中间顶点
            // （geodesic=false 为默认 → 墨卡托直线，纬线边不会弯曲，也不会露出接缝）。
            val ring = ArrayList<LatLng>()
            var lng = minLng
            while (lng < maxLng) { // 下边：西→东
                ring.add(LatLng(minLat, lng))
                lng += 60.0
            }
            ring.add(LatLng(minLat, maxLng))
            lng = maxLng
            while (lng > minLng) { // 上边：东→西
                ring.add(LatLng(maxLat, lng))
                lng -= 60.0
            }
            ring.add(LatLng(maxLat, minLng))
            val options = PolygonOptions()
                .addAll(ring)
                .fillColor(fill)
                .strokeWidth(0f) // 不在接缝处出线
                .zIndex(0.5f)
            hole?.let(options::addHole)
            polygons.add(map.addPolygon(options))
        }

        val hole = hullVertices.map { LatLng(it.lat, it.lng) }.reversed()
        addRect(holeHostMinLat, holeHostMaxLat, holeHostMinLng, holeHostMaxLng, hole)
        addRect(holeHostMaxLat, 85.0, -179.9, 179.9, null) // 上带
        addRect(-85.0, holeHostMinLat, -179.9, 179.9, null) // 下带
        addRect(holeHostMinLat, holeHostMaxLat, -179.9, holeHostMinLng, null) // 左带
        addRect(holeHostMinLat, holeHostMaxLat, holeHostMaxLng, 179.9, null) // 右带

        // 凸包的描边（iOS 用主题色线条；这里不采用贴地固定宽度的 hack，固定 2dp）
        edge = map.addPolyline(
            PolylineOptions()
                .addAll(hullVertices.map { LatLng(it.lat, it.lng) })
                .add(LatLng(hullVertices.first().lat, hullVertices.first().lng)) // 闭合
                .color(themeColor.toArgb())
                .width(2f * density)
                .zIndex(0.6f)
        )

        hull = hullVertices
        currentWorkId = workId
    }

    fun clear() {
        for (polygon in polygons) polygon.remove()
        polygons.clear()
        edge?.remove()
        edge = null
        hull = emptyList()
        currentWorkId = null
    }
}

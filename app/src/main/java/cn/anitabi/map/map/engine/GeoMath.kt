package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.LatLon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 作品模式压暗层的几何（iOS WorkRegionOverlay 几何部分的移植）。
 * 返回把点群凸包外扩约 1km 的区域。绘制侧（WorkRegionOverlayRenderer）用
 * 世界矩形做外环、这个凸包做内环（hole）的 Polygon 构成反转遮罩。
 */
object WorkRegionGeometry {

    /**
     * 由点群生成压暗层的内环。生成不了则返回 null。Web 版 `Yo()` 的移植。
     * 对判定范围外（海外）的**只丢弃那些点** —— Web 的 `l` 过滤就是按点做的。
     * 此前是「哪怕只有 1 个点在海外就整个作品都不画」，导致 MyGO!!!!!（127 点中有 2 点在伦敦）的
     * 暗幕整个消失（从 iOS 带来的移植 bug，真机反馈批次 7）。
     */
    fun hull(coordinates: List<LatLon>, bufferMeters: Double = 1_000.0): List<LatLon>? {
        val inRange = coordinates.filter {
            it.lng in 0.0..153.0 && it.lat in 20.0..45.0
        }
        if (inRange.isEmpty()) return null

        val hull = bufferedHull(inRange, bufferMeters)
        return if (hull.size >= 3) hull else null
    }

    /** 是否在暗幕内侧（＝作品的领域）。射线法。 */
    fun polygonContains(vertices: List<LatLon>, coordinate: LatLon): Boolean {
        if (vertices.size < 3) return false
        var inside = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[j]
            if ((a.lat > coordinate.lat) != (b.lat > coordinate.lat) &&
                coordinate.lng < (b.lng - a.lng) * (coordinate.lat - a.lat) / (b.lat - a.lat) + a.lng
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    // MARK: 几何（以重心为基准的局部米平面，等距圆柱近似。日本尺度下畸变不成问题）

    private data class Meters(val x: Double, val y: Double)

    /**
     * 相当于 `turf.convex` → `turf.buffer(d, meters)`：把凸包各边沿外法线方向平移 d，
     * 顶点用半径 d 的圆弧（约 12° 一步）衔接。得到与 Web 版相同的圆角区域。
     */
    private fun bufferedHull(coordinates: List<LatLon>, d: Double): List<LatLon> {
        val cLat = coordinates.sumOf { it.lat } / coordinates.size
        val cLng = coordinates.sumOf { it.lng } / coordinates.size
        val mPerLat = 111_320.0
        val mPerLng = 111_320.0 * cos(Math.toRadians(cLat)).coerceAtLeast(0.01)

        fun toMeters(c: LatLon) = Meters((c.lng - cLng) * mPerLng, (c.lat - cLat) * mPerLat)
        fun toCoordinate(p: Meters) = LatLon(cLat + p.y / mPerLat, cLng + p.x / mPerLng)

        val points = coordinates.map(::toMeters)
        val hull = convexHull(points)

        // 1〜2 点或共线：Web 是把点抖散做三角化→buffer ≒ 近似圆。这里直接取最远点半径 + d 的圆。
        if (hull.size < 3) {
            val radius = points.maxOf { sqrt(it.x * it.x + it.y * it.y) } + d
            return (0 until 36).map { step ->
                val angle = step / 36.0 * 2 * Math.PI
                toCoordinate(Meters(cos(angle) * radius, sin(angle) * radius))
            }
        }

        val out = ArrayList<Meters>()
        val n = hull.size
        for (i in 0 until n) {
            val p = hull[i]
            val q = hull[(i + 1) % n]
            val r = hull[(i + 2) % n]
            val dx = q.x - p.x
            val dy = q.y - p.y
            val len = sqrt(dx * dx + dy * dy)
            if (len <= 0.001) continue
            // CCW 凸包 → 外侧是行进方向的右侧 = (dy, -dx)
            val nx = dy / len
            val ny = -dx / len
            out.add(Meters(p.x + nx * d, p.y + ny * d))
            out.add(Meters(q.x + nx * d, q.y + ny * d))

            val dx2 = r.x - q.x
            val dy2 = r.y - q.y
            val len2 = sqrt(dx2 * dx2 + dy2 * dy2)
            if (len2 <= 0.001) continue
            val a1 = atan2(ny, nx)
            var sweep = atan2(-dx2 / len2, dy2 / len2) - a1 // 下一条边的外法线角 − 当前边的外法线角
            while (sweep < 0) sweep += 2 * Math.PI
            if (sweep >= Math.PI) continue // 凸包应小于 180°（防数值噪声）
            val steps = (sweep / (Math.PI / 15)).toInt()
            for (s in 1..steps) {
                val angle = a1 + sweep * s / (steps + 1)
                out.add(Meters(q.x + cos(angle) * d, q.y + sin(angle) * d))
            }
        }
        return out.map(::toCoordinate)
    }

    /** Andrew's monotone chain（米平面，按 CCW 返回）。 */
    private fun convexHull(input: List<Meters>): List<Meters> {
        val points = input.map { it.x to it.y }.toSortedSet(
            compareBy({ it.first }, { it.second })
        ).toList()
        if (points.size < 3) return input

        fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
            (a.first - o.first) * (b.second - o.second) -
                (a.second - o.second) * (b.first - o.first)

        val lower = ArrayList<Pair<Double, Double>>()
        for (point in points) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(point)
        }
        val upper = ArrayList<Pair<Double, Double>>()
        for (point in points.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(point)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return (lower + upper).map { Meters(it.first, it.second) }
    }
}

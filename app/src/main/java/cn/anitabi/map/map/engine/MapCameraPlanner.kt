package cn.anitabi.map.map.engine

import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.model.LatLon
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2

/**
 * 把相机的算术集中在一处（iOS MapCameraPlanner 的移植）。zoom 值对齐 Web 版（Mapbox GL）的
 * 定义 `z = log2(360 × 显示宽度dp / (256 × lngDelta))` —— Google Maps 的
 * cameraPosition.zoom 也是同样的 256/dp 基准，所以理论上 1:1（此等式是已知风险点，M2 阶段真机实测验证）。
 * 揭示阶梯和密度表都以 Web 的 z 为前提，偏离这个公式，标注的出现方式就会整体错位。
 */
object MapCameraPlanner {

    /**
     * 飞行时长的**上限**（Web 版 flyTo 的体感 ≒1.2 秒）。实际时长由 flightDuration
     * 按路程缩短 —— 飞到 200m 外的邻点也花 1.2 秒的话，只会留下「按了却没动」
     * 的空档（动作量要与输入量相称）。
     */
    const val FLY_DURATION_MS = 1200L

    /** 飞行时长的下限。低于它 easeInOut 的起势会被压扁，看起来像一帧切换。 */
    const val MIN_FLY_DURATION_MS = 350L

    /**
     * 由路程（当前视口 → 目标视口）决定飞行时长（ms）。路程是
     * **平移的「几个屏幕」＋缩放的「几档倍率」**之和 —— 与 Mapbox 的 flyTo 同一思路：
     * 飞往远方城市顶满上限（电影感），跳到邻点 0.4〜0.6 秒（敏捷）。
     */
    fun flightDurationMs(
        currentCenter: LatLon,
        currentLngDelta: Double,
        targetCenter: LatLon,
        targetLngDelta: Double,
    ): Long {
        val currentSpan = maxOf(currentLngDelta, 1e-9)
        val targetSpan = maxOf(targetLngDelta, 1e-9)
        // 缩放的档数差。span 每档减半，所以 log2 就是档数。
        val zoomTravel = abs(log2(currentSpan / targetSpan))
        // 平移的屏数。用较宽的那个视口来量 —— 飞行会先拉远再靠近，
        // 巡航高度上的横移才是体感的路程。
        val panTravel = hypot(
            targetCenter.lng - currentCenter.lng,
            targetCenter.lat - currentCenter.lat,
        ) / maxOf(currentSpan, targetSpan)
        val seconds = 0.35 + 0.12 * (panTravel + zoomTravel)
        return (seconds * 1000).toLong().coerceIn(MIN_FLY_DURATION_MS, FLY_DURATION_MS)
    }

    // MARK: 缩放 ↔ 范围

    /** Mapbox 口径的 zoom。widthDp 是地图显示宽度（dp）。 */
    fun zoom(lngDelta: Double, widthDp: Double): Double {
        if (lngDelta <= 0 || widthDp <= 0) return 0.0
        return log2(360 * widthDp / (256 * lngDelta))
    }

    /** 指定 zoom 时的经度跨度（zoom 的反函数）。 */
    fun lngDelta(zoom: Double, widthDp: Double): Double =
        360 * widthDp / (256 * Math.pow(2.0, zoom))

    // MARK: 靠近从列表选中的点

    /**
     * Web 版的密度表。priority 是最近邻距离（m）＝周围的拥挤程度，
     * 所以「能与邻点区分开」的放大倍率由它直接决定。
     */
    fun flyZoom(priority: Int?): Double = when {
        priority == null -> 15.0
        priority > 64 -> 15.0
        priority > 32 -> 16.0
        priority > 16 -> 17.0
        priority > 8 -> 18.0
        priority > 4 -> 19.0
        priority > 2 -> 20.0
        priority > 1 -> 21.0
        else -> 22.0
    }

    // MARK: 作品 fit

    /** 经纬度 bounds（不把 gms LatLngBounds 带出边界层之外）。 */
    data class Bounds(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double,
    ) {
        val centerLat: Double get() = (minLat + maxLat) / 2
        val centerLng: Double get() = (minLng + maxLng) / 2
        val latDelta: Double get() = maxLat - minLat
        val lngDelta: Double get() = maxLng - minLng
    }

    /**
     * 容纳作品点群的范围。防止飞地的离群点导致显示到「整个太平洋」（web 版
     * `fitBoundsByBangumi` 的移植）。判定不用分位数，而用**每个点「到其余全部点的平均距离」**，
     * 丢弃超过 `(最大 + 平均) / 2 × 0.8` 的点（剩余不足 3 点则用原点群）。
     */
    fun fitBounds(coordinates: List<LatLon>): Bounds? {
        if (coordinates.isEmpty()) return null
        if (coordinates.size < 4) return enclosingBounds(coordinates)

        val n = coordinates.size
        val eccentricities = DoubleArray(n)
        for (i in 0 until n) {
            var total = 0.0
            for (j in 0 until n) {
                if (j == i) continue
                total += AnitabiStore.distanceMeters(coordinates[i], coordinates[j])
            }
            eccentricities[i] = total / (n - 1)
        }
        val maxEccentricity = eccentricities.max()
        val meanEccentricity = eccentricities.sum() / n
        val cutoff = (maxEccentricity + meanEccentricity) / 2 * 0.8

        val kept = coordinates.filterIndexed { index, _ -> eccentricities[index] <= cutoff }
        return enclosingBounds(if (kept.size >= 3) kept else coordinates)
    }

    /**
     * **原样**包住给定坐标的范围（不剔除离群点）。
     * 容纳压暗层的顶点（WorkRegionGeometry.hull）时用这个 ——
     * 那个多边形本就由全部点组成，在这里抽稀会让边框伸出屏幕外。
     */
    fun enclosingBounds(coordinates: List<LatLon>): Bounds? {
        if (coordinates.isEmpty()) return null
        var minLat = coordinates[0].lat
        var maxLat = coordinates[0].lat
        var minLng = coordinates[0].lng
        var maxLng = coordinates[0].lng
        for (c in coordinates) {
            if (c.lat < minLat) minLat = c.lat
            if (c.lat > maxLat) maxLat = c.lat
            if (c.lng < minLng) minLng = c.lng
            if (c.lng > maxLng) maxLng = c.lng
        }
        // 只有 1 个点的作品面积为 0，给它最低限度的展开（±400m）。
        val latPad = 400.0 / 111_320.0
        val lngPad = latPad / Math.cos(Math.toRadians(minLat)).coerceAtLeast(0.01)
        if (maxLat - minLat < latPad / 100 && maxLng - minLng < lngPad / 100) {
            return Bounds(minLat - latPad, maxLat + latPad, minLng - lngPad, maxLng + lngPad)
        }
        return Bounds(minLat, maxLat, minLng, maxLng)
    }

    /** 四边 7%。上下的额外 inset（安全区、卡片高度）由调用方追加。 */
    fun edgePaddingPx(viewWidthPx: Int): Int = (viewWidthPx * 0.07).toInt()
}

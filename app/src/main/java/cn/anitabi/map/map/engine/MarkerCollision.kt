package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.cos
import kotlin.math.pow

/**
 * **剧照标之间**的屏幕空间碰撞解决。MapKit 的 collision 系（circle/rectangle/
 * displayPriority）在 Google Maps 上不存在，所以在引擎输出的最后一段自己来解。
 *
 * 规则（iOS 上 MapKit 行为的等价物）：
 * - 气球（选中点，由叠加层绘制、不在 [resolve] 的 plans 里）的矩形**先占位** ——
 *   剧照标不该压到气球底下去。
 * - 剧照标按矩形判定，按 priority（孤立度）降序贪心布局。
 * - 输掉的剧照标直接消失（圆点层已经在同一坐标画了圆点，不需要「降级为圆点」这条路径）。
 */
object MarkerCollision {

    fun resolve(
        plans: List<MapPointPlan>,
        zoom: Double,
        centerLatitude: Double,
        balloon: ScenePoint?,
    ): List<MapPointPlan> {
        if (plans.isEmpty()) return plans
        if (plans.size == 1 && balloon == null) return plans

        val pointsPerLng = 256 * 2.0.pow(zoom) / 360
        val pointsPerLat = pointsPerLng / cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.01)

        val photoFootprint = MarkerFootprint.photo(MarkerFootprint.photoPlate(zoom))
        val ordered = plans.sortedByDescending { it.point.priority }

        val placed = ArrayList<DoubleArray>(ordered.size + 1) // [minX, minY, maxX, maxY]
        val result = ArrayList<MapPointPlan>(ordered.size)

        fun tryPlace(rect: DoubleArray, force: Boolean): Boolean {
            if (!force) {
                for (r in placed) {
                    if (rect[0] < r[2] && rect[2] > r[0] && rect[1] < r[3] && rect[3] > r[1]) {
                        return false
                    }
                }
            }
            placed.add(rect)
            return true
        }

        if (balloon != null) {
            // 气球先占位（相当于 displayPriority .required）。它自己不在 plans 里。
            tryPlace(
                rectAt(balloon.lng * pointsPerLng, -balloon.lat * pointsPerLat, MarkerFootprint.BALLOON),
                force = true,
            )
        }

        for (plan in ordered) {
            val x = plan.point.lng * pointsPerLng
            val y = -plan.point.lat * pointsPerLat
            if (tryPlace(rectAt(x, y, photoFootprint), force = false)) {
                result.add(plan)
            }
            // 放不下就**直接消失**,不再降级为圆点重试 ——
            // 圆点层已经在同一坐标画了圆点,降级等于重复画。
        }
        return result
    }

    /** dp 足迹搬到 (x, y)。足迹的坐标系与这里一致：x 右为正、y 下为正、锚点在原点。 */
    private fun rectAt(x: Double, y: Double, footprint: FootprintDp) = doubleArrayOf(
        x + footprint.minX, y + footprint.minY, x + footprint.maxX, y + footprint.maxY,
    )
}

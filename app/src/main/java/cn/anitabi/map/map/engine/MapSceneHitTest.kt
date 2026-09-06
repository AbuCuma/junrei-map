package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.ScenePoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/**
 * 圆点的命中测试。
 *
 * 圆点是自绘的（`PointDotOverlay`），不是 Marker，GMS 不会替我们派发点击 —— 自己来。
 * **判定的对象是已经发布出去的 [MapScene]**，不是重新查一遍 [MapPointIndex]：
 * 画出去的就是能点中的，反过来也一样。此前两边各查各的，于是能点中屏幕上并不存在的点。
 *
 * 剧照牌与气球**首先**由 GMS 的 `setOnMarkerClickListener` 派发（位图外接框，命中即短路）。
 * 但标注的**外扩足迹**把周围一圈圆点剔掉了，而那一圈比位图大 —— 于是位图边缘之外有一条
 * 「无 marker 也无圆点」的死区，tap 落进去会被当成空白点击、把已开的卡片关掉
 * （真机反馈「点击失效」）。所以圆点未命中时兜底查一次标注足迹（[at] 的第二段）。
 */
object MapSceneHitTest {

    /**
     * 44dp 直径的目标 —— 接近 Android 的 48dp 触控指南。
     *
     * 曾是 12dp（沿用被删掉的 `MarkerIconFactory.renderDot` 24dp 位图热区），但 600dpi
     * 上手指误差 ±3mm ≈ ±19dp，比热区半径还大：稀疏处 miss 一下就被当成空白点击、
     * 把已开的卡片关掉（真机反馈「点击失效」的一部分）。密集处不受影响 ——
     * 圆盘规则优先，热区只在 tap 没落进任何圆盘时兜底取最近。
     * 代价：「点空白关卡片」要离任何点 22dp 以上才触发。
     */
    const val MIN_HIT_RADIUS_DP = 22.0

    /** 这一档 zoom 下圆点的判定半径（dp）：实际画出来的外缘与 [MIN_HIT_RADIUS_DP] 取大者。 */
    fun hitRadiusDp(zoom: Double): Double = max(
        MIN_HIT_RADIUS_DP,
        MapMarkerMetrics.dotRadius(zoom) + MapMarkerMetrics.dotStrokeWidth(zoom),
    )

    /**
     * @param scene 当前发布的场景。它自带的 zoom 可能比相机落后最多一个 tick，
     *   但点的坐标是**地理**的，所以陈旧只影响「刚进视口的点还点不中」，不会点错。
     * @return 命中的地标；没有就是 null。
     */
    fun at(tapLat: Double, tapLng: Double, scene: MapScene): ScenePoint? {
        // 气球最先：它在叠加层里画在圆点之上，视觉最顶层 ⇒ 命中也最优先。
        balloonAt(tapLat, tapLng, scene)?.let { return it }

        val dpPerWorld = MapProjection.WORLD_TILE_SIZE * 2.0.pow(scene.zoom)
        val dot = scene.dots.nearest(
            worldX = MapProjection.worldX(tapLng),
            worldY = MapProjection.worldY(tapLat),
            radiusWorld = hitRadiusDp(scene.zoom) / dpPerWorld,
            drawnRadiusWorld = (MapMarkerMetrics.dotRadius(scene.zoom) +
                MapMarkerMetrics.dotStrokeWidth(scene.zoom)) / dpPerWorld,
        )
        if (dot != null) return dot
        return annotationAt(tapLat, tapLng, scene)
    }

    /**
     * tap 是否落在气球的**实际形状**上：头圆（半径 [BALLOON_HEAD_RADIUS_DP]、
     * 心在锚点上方 [BALLOON_HEAD_CENTER_DP]）＋ 尾部小矩形。
     *
     * 不用整框矩形 —— 32×42 的外接框在尾侧有两块 ~13dp 的透明角，用矩形判定会把
     * 压在角下的圆点挡住。气球不再是 GMS Marker（那个按位图外接框判定、含阴影留白
     * 足有 44×54dp），所以这里的形状就是唯一的判定。
     * bearing≠0 时形状不随地图转（气球本身是屏幕对齐的），近似误差与 [annotationAt] 同款。
     */
    private fun balloonAt(tapLat: Double, tapLng: Double, scene: MapScene): ScenePoint? {
        val balloon = scene.balloon ?: return null
        val dpPerLng = MapProjection.WORLD_TILE_SIZE * 2.0.pow(scene.zoom) / 360.0
        val dpPerLat = dpPerLng / max(cos(Math.toRadians(tapLat)), 0.01)
        val dxDp = (tapLng - balloon.lng) * dpPerLng
        val dyDp = (balloon.lat - tapLat) * dpPerLat

        val headDx = dxDp
        val headDy = dyDp + BALLOON_HEAD_CENTER_DP
        val head = headDx * headDx + headDy * headDy <=
            BALLOON_HEAD_RADIUS_DP * BALLOON_HEAD_RADIUS_DP
        val tail = dxDp in -BALLOON_TAIL_HALF_WIDTH_DP..BALLOON_TAIL_HALF_WIDTH_DP &&
            dyDp in -BALLOON_TAIL_HEIGHT_DP..0.0
        return if (head || tail) balloon else null
    }

    /** 与 `MarkerIconFactory.renderBalloon` 的几何同值（头 32dp、心在 y=16、尾尖在 y=42）。 */
    private const val BALLOON_HEAD_RADIUS_DP = 16.0
    private const val BALLOON_HEAD_CENTER_DP = 26.0
    private const val BALLOON_TAIL_HALF_WIDTH_DP = 5.0
    private const val BALLOON_TAIL_HEIGHT_DP = 12.0

    /**
     * tap 落在哪个标注的足迹里（死区兜底，见类注释）。多个时取**锚点最近**的。
     *
     * 足迹是**屏幕对齐**的 dp 矩形，这里在经纬度里按局部线性近似判定（与
     * `MapEngine.footprintBox` 同一套换算）。bearing≠0 时矩形不随地图转 ——
     * GMS marker 的位图本身也是屏幕对齐的，旋转下两者一起偏，边角误差几 dp，可接受。
     */
    private fun annotationAt(tapLat: Double, tapLng: Double, scene: MapScene): ScenePoint? {
        if (scene.annotations.isEmpty()) return null
        // **与 MapEngine.occludedDotIds 相同的外扩**。死区恰是「位图边缘 → 外扩边缘」
        // 那一圈（圆点被剔到了外扩线之外），不外扩的话兜底盖不住死区。
        val halo = MapMarkerMetrics.dotRadius(scene.zoom) + MapMarkerMetrics.dotStrokeWidth(scene.zoom)
        val plate = MarkerFootprint.photo(MarkerFootprint.photoPlate(scene.zoom)).inflated(halo)
        val dpPerLng = MapProjection.WORLD_TILE_SIZE * 2.0.pow(scene.zoom) / 360.0
        val dpPerLat = dpPerLng / max(cos(Math.toRadians(tapLat)), 0.01)

        var best: ScenePoint? = null
        var bestSquaredDp = Double.MAX_VALUE
        for (annotation in scene.annotations) {
            val point = annotation.point
            // 锚点 → tap 的位移（dp，x 右为正、y 下为正，与足迹坐标系一致）。
            val dxDp = (tapLng - point.lng) * dpPerLng
            val dyDp = (point.lat - tapLat) * dpPerLat
            if (dxDp < plate.minX || dxDp > plate.maxX) continue
            if (dyDp < plate.minY || dyDp > plate.maxY) continue
            val squared = dxDp * dxDp + dyDp * dyDp
            if (squared < bestSquaredDp) {
                bestSquaredDp = squared
                best = point
            }
        }
        return best
    }
}

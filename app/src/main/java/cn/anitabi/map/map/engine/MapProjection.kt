package cn.anitabi.map.map.engine

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web 墨卡托投影。圆点叠加层与命中测试共用这一份。
 *
 * 前身是 `MapTileGeometry` —— 圆点曾经被烘焙进光栅瓦片，而 GMS 只在**整数 zoom** 换瓦片、
 * 带内一律拉伸 `2^f`，于是圆点在带内胀 2 倍、跨带瞬缩 1.9 倍，而且永远在放大位图（模糊）。
 * 光栅瓦片给的是「地理尺寸恒定」，网页版要的是「屏幕尺寸恒定」，二者不可调和，
 * 所以改成每帧自绘，尺寸直接取 [MapMarkerMetrics.dotRadius]（相机 zoom），不再有中间量。
 */
object MapProjection {

    /** Mapbox / GMS 都以 256dp 为一个「世界单位 ÷ 2^zoom」的基准。 */
    const val WORLD_TILE_SIZE = 256.0

    /** 墨卡托在两极发散，标准截断纬度。 */
    private const val MAX_LAT = 85.05112878

    /** 经度 → 世界坐标 [0,1]。 */
    fun worldX(lng: Double): Double = (lng + 180.0) / 360.0

    /** 纬度 → 世界坐标 [0,1]（上北下南）。 */
    fun worldY(lat: Double): Double {
        val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
    }

    fun lngOf(worldX: Double): Double = worldX * 360.0 - 180.0

    fun latOf(worldY: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * worldY))))

    /** 以某点为中心、边长约 2×[radiusDp] 的经纬度盒（命中测试用）。 */
    fun boxAround(lat: Double, lng: Double, cameraZoom: Double, radiusDp: Double): MapViewport {
        // 每 dp 对应多少经度：256dp 覆盖 360/2^z 度。
        val degreesPerDp = 360.0 / (WORLD_TILE_SIZE * 2.0.pow(cameraZoom))
        val lngHalf = radiusDp * degreesPerDp
        val latHalf = lngHalf * cos(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return MapViewport(
            minLat = lat - latHalf,
            maxLat = lat + latHalf,
            minLng = lng - lngHalf,
            maxLng = lng + lngHalf,
        )
    }

    /**
     * 「世界坐标 → 屏幕像素」的仿射变换。倾斜手势是关的（`tiltGesturesEnabled = false`），
     * 所以没有透视，只有平移、缩放、旋转三样。
     *
     * **原点必须由调用方用 `map.projection.toScreenLocation(相机目标)` 取**，不要自己
     * 用「视图中心」推：`GoogleMap` 的 `contentPadding` 会把相机目标推离视图中心，
     * 而且它随 sheet 弹簧逐帧变。每帧一次跨界调用换来「与 GMS 当前帧同一个变换」，
     * 这是对抗叠加层与地图差一帧的主要手段。
     *
     * @param originWorldX 相机目标的世界坐标
     * @param originScreenX 相机目标在屏幕上的像素位置
     * @param zoom 相机 zoom（可以是小数）
     * @param bearingDegrees 地图的方位角。GMS 的语义是「地图北向顺时针旋转多少度」，
     *   所以点要**反向**旋转同样的角度。
     * @param density 每 dp 多少像素
     */
    class Affine(
        private val originWorldX: Double,
        private val originWorldY: Double,
        private val originScreenX: Float,
        private val originScreenY: Float,
        zoom: Double,
        bearingDegrees: Float,
        density: Float,
    ) {
        /** 世界坐标 [0,1] 跨越整个屏幕时对应多少像素。 */
        private val pxPerWorld: Double = WORLD_TILE_SIZE * 2.0.pow(zoom) * density

        private val cosB: Double
        private val sinB: Double

        init {
            val radians = Math.toRadians(-bearingDegrees.toDouble())
            cosB = cos(radians)
            sinB = sin(radians)
        }

        /** @return 屏幕 X（像素）。与 [projectY] 成对使用，两者共享同一次三角函数。 */
        fun projectX(lat: Double, lng: Double): Float = projectXOf(worldX(lng), worldY(lat))

        fun projectY(lat: Double, lng: Double): Float = projectYOf(worldX(lng), worldY(lat))

        /** 世界坐标版（气球等已经存了世界坐标的调用方免去一次投影换算）。 */
        fun projectXOf(worldX: Double, worldY: Double): Float {
            val dx = (worldX - originWorldX) * pxPerWorld
            val dy = (worldY - originWorldY) * pxPerWorld
            return (originScreenX + dx * cosB - dy * sinB).toFloat()
        }

        fun projectYOf(worldX: Double, worldY: Double): Float {
            val dx = (worldX - originWorldX) * pxPerWorld
            val dy = (worldY - originWorldY) * pxPerWorld
            return (originScreenY + dx * sinB + dy * cosB).toFloat()
        }

        /**
         * 把一条平铺的世界坐标数组（`[wx0, wy0, wx1, wy1, …]`）整体变换进 [out]。
         *
         * 走数组而不是逐点调用，是为了让叠加层每帧零分配 —— 变换结果直接喂给
         * `Canvas.drawPoints(FloatArray, …)`。
         *
         * **入参必须是 [DoubleArray]**：世界坐标是 [0,1] 区间的小数，用 Float 存的话
         * 东京一带的精度约 2.3e-9，而 z20 的 `pxPerWorld` 约 1e9 —— 乘出来是 2.3 像素的
         * 位置误差（z18 约 0.6 像素）。对一个 8dp 的圆点是看得见的。
         */
        fun projectAll(world: DoubleArray, count: Int, out: FloatArray) {
            var i = 0
            while (i < count * 2) {
                val dx = (world[i] - originWorldX) * pxPerWorld
                val dy = (world[i + 1] - originWorldY) * pxPerWorld
                out[i] = (originScreenX + dx * cosB - dy * sinB).toFloat()
                out[i + 1] = (originScreenY + dx * sinB + dy * cosB).toFloat()
                i += 2
            }
        }
    }
}

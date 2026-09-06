package cn.anitabi.map.map.engine

/**
 * 标注在屏幕上占据的矩形，单位 dp，**相对锚点**（锚点在原点，x 右为正、y 下为正）。
 *
 * 一处定义、两处使用：
 * - [MarkerCollision] 用它判剧照牌之间的重叠（此前气球的 32×42 是写死在那里的魔数）；
 * - [MapEngine.scene] 用它把被标注压住的圆点整点剔除 —— 自绘的圆点层画在所有 Marker
 *   之上，不剔除的话圆点会画到剧照卡和气球身上（真机反馈：气球被圆点盖住）。
 */
data class FootprintDp(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    /** 四周各外扩 [by] dp。剔除圆点时用来把「白描边压在卡片边缘上」也一并挡掉。 */
    fun inflated(by: Double): FootprintDp =
        FootprintDp(minX - by, minY - by, maxX + by, maxY + by)
}

object MarkerFootprint {

    /**
     * 选中气球。头 32dp、尾 10dp，**尾尖即坐标**，本体在其正上方
     * （与 `MarkerIconFactory.renderBalloon` 的 32×42 同值 —— 改一处必须改另一处，
     * `MarkerFootprintTest` 会把两边钉在一起）。
     */
    val BALLOON = FootprintDp(minX = -16.0, minY = -42.0, maxX = 16.0, maxY = 0.0)

    /**
     * 作品标。30dp 的封面 ＋ 最多 92dp 的标签，含留白，**以坐标为中心**。
     * 与 `MapEngine.declutter` 的 100×54 同值。
     */
    val WORK = FootprintDp(minX = -50.0, minY = -27.0, maxX = 50.0, maxY = 27.0)

    /** 剧照牌。尾尖即坐标，本体在其上方。 */
    fun photo(plate: MapMarkerMetrics.PhotoPlate): FootprintDp = FootprintDp(
        minX = -plate.totalWidth / 2,
        minY = -plate.totalHeight,
        maxX = plate.totalWidth / 2,
        maxY = 0.0,
    )

    /** 这一档 zoom 下剧照牌用哪种意匠（与 `MarkerIconFactory.photoStyle` 同一条边界）。 */
    fun photoPlate(zoom: Double): MapMarkerMetrics.PhotoPlate =
        if (zoom > MapRevealLadder.PHOTO_CARD_ZOOM) {
            MapMarkerMetrics.PhotoPlate.CARD
        } else {
            MapMarkerMetrics.PhotoPlate.BUBBLE
        }
}

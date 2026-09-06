package cn.anitabi.map.map.engine

import cn.anitabi.map.data.model.LatLon
import cn.anitabi.map.data.model.ScenePoint

/**
 * 这一帧地图上应当存在的**全部**东西。不可变，由 [MapEngine.scene] 在 worker 上一次算出。
 *
 * ## 为什么是「一个场景」而不是两份
 *
 * 此前标注（剧照牌/气球/作品标）与圆点由两条互不知情的 `snapshotFlow` 各自决定，
 * 圆点靠向 `MarkerFieldController` 打听「哪些点已经有 Marker 了」来避让。两条循环的
 * 时序没有任何约束，于是真机上出现：点了之后气球出现、圆点却还在；气球被邻近的圆点盖住。
 *
 * 现在**一个点在一帧里只有一种形态**，这条不变式由本类的构造过程保证，并有单测钉住。
 */
class MapScene(
    val zoom: Double,
    /** 需要 Marker 的点：剧照牌。已过 [MarkerCollision]。 */
    val annotations: List<MapPointPlan>,
    /**
     * 选中的点。**不是 Marker、也不是圆点** —— 由 `PointDotOverlay` 画在圆点之上
     * （层序与网页版一致：选中标顶层），命中由 `MapSceneHitTest` 按气球实际形状判定。
     *
     * 以前它是 GMS Marker：位图 44×54dp 的命中框加上外扩足迹的扫除,选中后周围
     * ~49×59dp 内的圆点全部不画不可点,tap 一律回到已选中的点 ——
     * 「选中一个点后再点它旁边的点没反应」（真机反馈）就是这片锁定区。
     */
    val balloon: ScenePoint?,
    val works: List<MapWorkSeed>,
    /** 圆点。已经排除掉 [annotations]／[works] 占据的区域，并已分趟。 */
    val dots: DotField,
    /** 作品模式时该作品的全部点（用于压暗层的凸包）。 */
    val focusedWorkCoordinates: List<LatLon>?,
    val selectedPointId: String?,
    /** 作品模式 / chips。为真时圆点忽略 zoom 规则，任何 zoom 都全画。 */
    val filtered: Boolean,
    val diagnostics: SceneDiagnostics,
) {

    /** 一行摘要。debug HUD 与真机 harness 都打这一行。 */
    fun debugLine(): String {
        var card = 0
        var bubble = 0
        val isCard = MarkerFootprint.photoPlate(zoom) === MapMarkerMetrics.PhotoPlate.CARD
        for (annotation in annotations) {
            if (isCard) card++ else bubble++
        }
        return "z=%.2f dots=%d pass=%d(hid%d) K=%d ann=%d(card%d/bub%d/bal%d) work=%d occl=%d build=%.1fms"
            .format(
                zoom, dots.count, dots.passes.size, dots.hiddenCount, dots.colorGroupCount,
                annotations.size, card, bubble, if (balloon != null) 1 else 0, works.size,
                diagnostics.occludedDots, diagnostics.buildNanos / 1_000_000.0,
            )
    }

    companion object {
        fun empty(zoom: Double) = MapScene(
            zoom = zoom,
            annotations = emptyList(),
            balloon = null,
            works = emptyList(),
            dots = DotField.EMPTY,
            focusedWorkCoordinates = null,
            selectedPointId = null,
            filtered = false,
            diagnostics = SceneDiagnostics(0, 0, 0),
        )
    }
}

/**
 * 场景的可观测量。全部是**计数**，不含引用 —— HUD 与 harness 打印它，
 * 单测也靠它断言「遮挡确实生效了」而不是只看最终点数。
 */
class SceneDiagnostics(
    /** 按 zoom 规则筛出来、进入遮挡判定之前的圆点候选数。 */
    val dotCandidates: Int,
    /** 因为被标注压住而没画的圆点数。 */
    val occludedDots: Int,
    val buildNanos: Long,
)

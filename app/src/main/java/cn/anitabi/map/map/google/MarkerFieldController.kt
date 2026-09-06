package cn.anitabi.map.map.google

import android.os.Trace
import android.view.Choreographer
import androidx.compose.ui.graphics.Color
import cn.anitabi.map.map.engine.MapScene
import cn.anitabi.map.map.engine.MapPointPlan
import cn.anitabi.map.map.engine.MapWorkSeed
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一帧内允许用于 addMarker 的时间。 */
private const val ADD_BUDGET_NANOS = 3_000_000L

/**
 * 取引擎输出的「这一帧应当存在的集合」与实际 Marker 群的差分
 * （iOS MapCanvas.Coordinator 标注管理的移植）。
 *
 * - 只剩剧照牌一种点形态（card・bubble 两档意匠）：圆点归自绘层、
 *   选中气球也归自绘层（画在圆点之上，见 `PointDotOverlay`）。
 * - 消失的标注先淡出 0.18s 再 remove，出现的标注带随机延迟淡入
 *   （打散「齐刷刷落地」的观感 — 与 iOS 相同）。淡出途中「复活」则取消淡出。
 *   淡入淡出统一由 [MarkerFadeDriver] 一根驱动（每个标注一个 ValueAnimator 是掉帧之源）。
 * - 图的决定（[describe]）由 apply 与 [iconSpecs] 共享，把后者在 worker 上
 *   交给 [MarkerIconFactory.prewarm]，main 上就不会发生绘制。
 */
class MarkerFieldController(
    private val map: GoogleMap,
    private val iconFactory: MarkerIconFactory,
    private val imageCache: MarkerImageCache,
    private val sprite: BangumiIconSprite,
    private val scope: CoroutineScope,
    private val renderDispatcher: CoroutineDispatcher,
    private val fades: MarkerFadeDriver = MarkerFadeDriver(),
) {
    private class PointLive(
        val id: String,
        val marker: Marker,
        var form: String, // "photo-card" | "photo-bubble"
        var iconKey: String,
        var imageUrl: String?,
        var fade: MarkerFadeDriver.Handle? = null,
    )

    private class WorkLive(
        val marker: Marker,
        var iconKey: String,
        var seed: MapWorkSeed,
        var fade: MarkerFadeDriver.Handle? = null,
    )

    /** 某个点在这一帧怎么画（纯数据）。 */
    internal class PointDesc(
        val form: String,
        val iconKey: String,
        val imageUrl: String?,
        val spec: MarkerIconFactory.IconSpec,
    )

    /**
     * 一帧的绘制计划:worker 侧 [plan] 产出(供 prewarm),main 侧 [apply] 直接复用,
     * 避免同一帧对 ≤600 点各跑两遍 describe(其中一遍在主线程)。
     */
    class FramePlan internal constructor(
        internal val pointDescs: Map<String, PointDesc>,
        val specs: List<MarkerIconFactory.IconSpec>,
    )

    /** 等待 addMarker（在帧预算内一点点上）。 */
    private class PendingPoint(val plan: MapPointPlan, val desc: PointDesc, val color: Color)

    private val livePoints = HashMap<String, PointLive>()
    private val liveWorks = HashMap<Int, WorkLive>()
    private val pendingPoints = LinkedHashMap<String, PendingPoint>()
    private var drainScheduled = false
    private val choreographer = Choreographer.getInstance()
    /** 淡出中（已预约 remove）。复活时从这里捞出并取消。 */
    private val removingPoints = HashMap<String, PointLive>()
    private val removingWorks = HashMap<Int, WorkLive>()

    var onPointTap: ((String) -> Unit)? = null
    var onWorkTap: ((Int) -> Unit)? = null
    /** 对标注以外（落针 pin 等）的点击。导向与地图点击相同的「取消选中」。 */
    var onOtherMarkerTap: (() -> Unit)? = null

    init {
        map.setOnMarkerClickListener { marker ->
            when (val tag = marker.tag) {
                is String -> when {
                    tag.startsWith("p:") -> onPointTap?.invoke(tag.removePrefix("p:"))
                    tag.startsWith("w:") -> onWorkTap?.invoke(tag.removePrefix("w:").toIntOrNull() ?: return@setOnMarkerClickListener true)
                    else -> onOtherMarkerTap?.invoke() // "pin" 等 —— 不默默吞掉
                }
                else -> onOtherMarkerTap?.invoke()
            }
            true // 不用默认的相机移动、InfoWindow
        }
    }

    // MARK: 图的决定（apply / prewarm 共用）

    /**
     * iconKey 只由**该形态真正依赖的东西**组成：photo/balloon 都不依赖 zoom。
     * 此前所有形态都混入了 zoom 档，导致每 0.5 zoom 就把全部 photo 标注作废、跑一遍 setIcon。
     *
     * 圆点曾是唯一依赖 zoom 档的形态，于是跨档时会对每个存活圆点同步跑一遍 setIcon
     * （600 点约 144ms 压在一帧，且**没有预算**）。圆点搬去自绘层之后，那条路径不复存在。
     */
    private fun describe(plan: MapPointPlan, zoom: Double, color: Color): PointDesc {
        val point = plan.point
        // 圆点与选中气球都归自绘层（PointDotOverlay），marker 场只剩剧照牌。
        check(plan.usesPhoto) { "非剧照的点不该进入 marker 场：${point.id}" }
        val style = iconFactory.photoStyle(zoom)
        val form = "photo-${style.name.lowercase()}"
        val url = point.thumbnailUrl
        return PointDesc(
            form = form,
            iconKey = "$form|${color.value}|${url != null}",
            imageUrl = url,
            spec = MarkerIconFactory.IconSpec.Photo(color, style, url?.let(imageCache::cached), url),
        )
    }

    private fun workSpec(seed: MapWorkSeed, color: Color): MarkerIconFactory.IconSpec.Work =
        MarkerIconFactory.IconSpec.Work(seed.name, color, sprite.icon(seed.id), seed.id)

    private fun workKey(color: Color, hasSprite: Boolean, name: String) = "w|${color.value}|$hasSprite|$name"

    /**
     * 这一帧的绘制计划(worker 线程调用;specs 交给 [MarkerIconFactory.prewarm])。
     * 与 apply 走同一个 [describe],且结果原样传给 apply 复用 —— 两侧结构性一致。
     */
    fun plan(scene: MapScene, colorFor: (Int) -> Color): FramePlan {
        val descs = HashMap<String, PointDesc>(scene.annotations.size)
        val specs = ArrayList<MarkerIconFactory.IconSpec>(scene.annotations.size + scene.works.size)
        for (plan in scene.annotations) {
            val color = colorFor(plan.point.bangumiId)
            val desc = describe(plan, scene.zoom, color)
            descs[plan.point.id] = desc
            specs.add(desc.spec)
        }
        for (seed in scene.works) specs.add(workSpec(seed, colorFor(seed.id)))
        return FramePlan(descs, specs)
    }

    // MARK: 帧应用

    fun apply(scene: MapScene, colorFor: (Int) -> Color, framePlan: FramePlan? = null) {
        Trace.beginSection("MarkerField.apply")
        try {
            applyPoints(scene, colorFor, framePlan)
            applyWorks(scene.works, colorFor)
        } finally {
            Trace.endSection()
        }
    }

    private fun applyPoints(scene: MapScene, colorFor: (Int) -> Color, framePlan: FramePlan?) {
        val wanted = scene.annotations.associateBy { it.point.id }

        // remove（淡出）。
        for ((id, live) in livePoints.entries.toList()) {
            if (wanted.containsKey(id)) continue
            livePoints.remove(id)
            fadeOutPoint(id, live)
        }
        // 还没上图的如果不再需要，就干脆不上。
        pendingPoints.keys.retainAll { wanted.containsKey(it) }

        for ((id, plan) in wanted) {
            val point = plan.point
            val color = colorFor(point.bangumiId)
            // 复用 worker 侧算好的 desc。
            val desc = framePlan?.pointDescs?.get(id) ?: describe(plan, scene.zoom, color)

            // 淡出途中复活了？ → 取消淡出并复用（iOS cancelFade）。
            removingPoints.remove(id)?.let { revived ->
                revived.fade?.cancel()
                revived.marker.alpha = 1f
                livePoints[id] = revived
            }

            val existing = livePoints[id]
            if (existing == null) {
                // addMarker 一次 ~80µs（跨界调用）。几百个不在一帧内上完，按预算分批。
                pendingPoints[id] = PendingPoint(plan, desc, color)
            } else if (existing.iconKey != desc.iconKey) {
                val icon = iconFactory.icon(desc.spec)
                existing.marker.setIcon(icon.descriptor)
                existing.marker.setAnchor(icon.anchorU, icon.anchorV)
                existing.marker.zIndex = zIndexFor(desc.form)
                existing.form = desc.form
                existing.iconKey = desc.iconKey
                existing.imageUrl = desc.imageUrl
                requestImageIfNeeded(existing, color)
            }
        }
        if (pendingPoints.isNotEmpty()) scheduleDrain()
    }

    private fun scheduleDrain() {
        if (drainScheduled) return
        drainScheduled = true
        choreographer.postFrameCallback {
            drainScheduled = false
            Trace.beginSection("MarkerField.drain")
            try {
                drainPendingAdds()
            } finally {
                Trace.endSection()
            }
            if (pendingPoints.isNotEmpty()) scheduleDrain()
        }
    }

    /** 在预算内执行 addMarker。与淡入的随机延迟叠加，形成 iOS 那种「散落的落地」。 */
    private fun drainPendingAdds() {
        val deadline = System.nanoTime() + ADD_BUDGET_NANOS
        val iterator = pendingPoints.entries.iterator()
        while (iterator.hasNext()) {
            val (id, pending) = iterator.next()
            iterator.remove()
            if (livePoints.containsKey(id)) continue
            val point = pending.plan.point
            val desc = pending.desc
            val icon = iconFactory.icon(desc.spec)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(point.lat, point.lng))
                    .icon(icon.descriptor)
                    .anchor(icon.anchorU, icon.anchorV)
                    .alpha(0f)
                    .zIndex(zIndexFor(desc.form))
            ) ?: continue
            marker.tag = "p:$id"
            val live = PointLive(id, marker, desc.form, desc.iconKey, desc.imageUrl)
            livePoints[id] = live
            fadeInPoint(live)
            requestImageIfNeeded(live, pending.color)
            if (System.nanoTime() > deadline) break
        }
    }

    private fun zIndexFor(@Suppress("UNUSED_PARAMETER") form: String): Float = 1f

    /**
     * 剧照/气球的图片未到就去取，到货后**仅当还是同一张图时**才替换。
     * 替换用的图先在 worker 上画好，再回 main 做 setIcon。
     */
    private fun requestImageIfNeeded(live: PointLive, color: Color) {
        val url = live.imageUrl ?: return
        if (imageCache.cached(url) != null) return
        val expectedKey = live.iconKey
        imageCache.load(url) { bitmap ->
            if (bitmap == null) return@load
            if (livePoints[live.id] !== live || live.iconKey != expectedKey) return@load
            if (!live.form.startsWith("photo")) return@load
            val spec: MarkerIconFactory.IconSpec = MarkerIconFactory.IconSpec.Photo(
                color,
                if (live.form.endsWith("card")) {
                    MarkerIconFactory.PhotoStyle.Card
                } else {
                    MarkerIconFactory.PhotoStyle.Bubble
                },
                bitmap, url,
            )
            scope.launch {
                withContext(renderDispatcher) { iconFactory.prewarm(listOf(spec)) }
                if (livePoints[live.id] !== live || live.iconKey != expectedKey) return@launch
                val icon = iconFactory.icon(spec)
                live.marker.setIcon(icon.descriptor)
                live.marker.setAnchor(icon.anchorU, icon.anchorV)
            }
        }
    }

    // MARK: 作品标

    private fun applyWorks(seeds: List<MapWorkSeed>, colorFor: (Int) -> Color) {
        val wanted = seeds.associateBy { it.id }

        for ((id, live) in liveWorks.entries.toList()) {
            if (wanted.containsKey(id)) continue
            liveWorks.remove(id)
            fadeOutWork(id, live)
        }

        for ((id, seed) in wanted) {
            val color = colorFor(id)
            val spec = workSpec(seed, color)
            val iconKey = workKey(color, spec.spriteIcon != null, seed.name)

            removingWorks.remove(id)?.let { revived ->
                revived.fade?.cancel()
                revived.marker.alpha = 1f
                liveWorks[id] = revived
            }

            val existing = liveWorks[id]
            if (existing == null) {
                val icon = iconFactory.icon(spec)
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(LatLng(seed.coordinate.lat, seed.coordinate.lng))
                        .icon(icon.descriptor)
                        .anchor(icon.anchorU, icon.anchorV)
                        .alpha(0f)
                        .zIndex(2f)
                ) ?: continue
                marker.tag = "w:$id"
                val live = WorkLive(marker, iconKey, seed)
                liveWorks[id] = live
                fadeInWork(live)
            } else if (existing.iconKey != iconKey) {
                val icon = iconFactory.icon(spec)
                existing.marker.setIcon(icon.descriptor)
                existing.marker.setAnchor(icon.anchorU, icon.anchorV)
                existing.iconKey = iconKey
                existing.seed = seed
            }
        }
    }

    /**
     * 精灵图后到时，只替换已上图的作品标的图（iOS refreshIcon）。
     * 最多 32 张带白晕的绘制先在 worker 上一并画完，再回 main 粘贴。
     */
    fun refreshWorkIcons(colorFor: (Int) -> Color) {
        val jobs = ArrayList<Triple<WorkLive, String, MarkerIconFactory.IconSpec.Work>>()
        for ((id, live) in liveWorks) {
            val spriteIcon = sprite.icon(id) ?: continue
            val color = colorFor(id)
            val iconKey = workKey(color, true, live.seed.name)
            if (live.iconKey == iconKey) continue
            jobs.add(Triple(live, iconKey, MarkerIconFactory.IconSpec.Work(live.seed.name, color, spriteIcon, id)))
        }
        if (jobs.isEmpty()) return
        scope.launch {
            withContext(renderDispatcher) { iconFactory.prewarm(jobs.map { it.third }) }
            for ((live, iconKey, spec) in jobs) {
                if (liveWorks[spec.bangumiId] !== live || live.iconKey == iconKey) continue
                val icon = iconFactory.icon(spec)
                live.marker.setIcon(icon.descriptor)
                live.marker.setAnchor(icon.anchorU, icon.anchorV)
                live.iconKey = iconKey
            }
        }
    }

    // MARK: 淡入淡出

    private fun fadeInPoint(live: PointLive) {
        live.fade?.cancel()
        live.fade = fades.fade(live.marker, from = 0f, to = 1f, durationMs = 220, delayMs = Random.nextLong(0, 80))
    }

    private fun fadeOutPoint(id: String, live: PointLive) {
        removingPoints[id] = live
        // 起始 alpha 不从 Marker 读（getter 也是跨界调用）。淡入途中就从其当前值开始。
        val from = live.fade?.takeUnless { it.cancelled }?.currentAlpha ?: 1f
        live.fade?.cancel()
        live.fade = fades.fade(live.marker, from = from, to = 0f, durationMs = 180, delayMs = 0) {
            live.marker.remove()
            removingPoints.remove(id)
        }
    }

    private fun fadeInWork(live: WorkLive) {
        live.fade?.cancel()
        live.fade = fades.fade(live.marker, from = 0f, to = 1f, durationMs = 220, delayMs = Random.nextLong(0, 80))
    }

    private fun fadeOutWork(id: Int, live: WorkLive) {
        removingWorks[id] = live
        val from = live.fade?.takeUnless { it.cancelled }?.currentAlpha ?: 1f
        live.fade?.cancel()
        live.fade = fades.fade(live.marker, from = from, to = 0f, durationMs = 180, delayMs = 0) {
            live.marker.remove()
            removingWorks.remove(id)
        }
    }

    fun clear() {
        fades.cancelAll()
        pendingPoints.clear()
        for (live in livePoints.values) live.marker.remove()
        for (live in liveWorks.values) live.marker.remove()
        for (live in removingPoints.values) live.marker.remove()
        for (live in removingWorks.values) live.marker.remove()
        livePoints.clear()
        liveWorks.clear()
        removingPoints.clear()
        removingWorks.clear()
    }
}

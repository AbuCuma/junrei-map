package cn.anitabi.map.map.google

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cn.anitabi.map.map.engine.MapMarkerMetrics
import cn.anitabi.map.map.engine.MapRevealLadder
import cn.anitabi.map.theme.ColorUtilities
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import cn.anitabi.map.support.drawAspectFill

/**
 * 生成并缓存 marker 的 BitmapDescriptor（把 iOS PointMarkerView/
 * WorkMarkerView 的绘制 bitmap 化）。Google Marker 不能持有 view，所以三态
 * （dot / photo / balloon）和作品标都用 Canvas 画。zoom 离散为 0.5 一档。
 *
 * **绘制（render）与包装（BitmapDescriptor 化）分离**：绘制是纯 android.graphics，
 * 可以在任意线程完成（[prewarm]），main 只在 [icon] 里从缓存／pending 中捞出
 * 再 fromBitmap（针对主线程光栅化掉帧的对策）。
 */
class MarkerIconFactory(private val density: Float) {

    /** 产物与锚点。Google Marker 分开接收 icon 和 anchor。 */
    data class Icon(val descriptor: BitmapDescriptor, val anchorU: Float, val anchorV: Float, val bytes: Int)

    /** 已绘制、未包装的中间物（可在任意线程生成）。 */
    class Rendered(val bitmap: Bitmap, val anchorU: Float, val anchorV: Float)

    /** 描述画什么的纯数据。key 由它唯一决定。 */
    sealed interface IconSpec {
        data class Photo(val color: Color, val style: PhotoStyle, val image: Bitmap?, val imageKey: String?) : IconSpec
        data class Balloon(val color: Color, val image: Bitmap?, val imageKey: String?) : IconSpec
        data class Work(val name: String, val color: Color, val spriteIcon: Bitmap?, val bangumiId: Int) : IconSpec
    }

    private fun byteCache(maxBytes: Int) = object : LruCache<String, Icon>(maxBytes) {
        override fun sizeOf(key: String, value: Icon): Int = value.bytes
    }

    // 上限不按条数而按**字节数**切（photo 一张 ~190KB，256 条曾膨胀到 ~48MB）。
    private val photoCache = byteCache(24 * 1024 * 1024)
    private val balloonCache = LruCache<String, Icon>(32)
    private val workCache = byteCache(8 * 1024 * 1024)

    /** 已预绘、尚未包装的。会被任意线程触碰，所以加同步。 */
    private val pending = HashMap<String, Rendered>()

    private fun dp(value: Double): Float = (value * density).toFloat()
    private fun dp(value: Float): Float = value * density

    // MARK: spec → key / cache / render

    fun keyFor(spec: IconSpec): String = when (spec) {
        // 占位（image=null）与实际图片用**不同的 key** —— 同 key 会在图片到货后仍一直返回占位。
        is IconSpec.Photo -> "${spec.color.toArgb()}|${spec.style}|${if (spec.image != null) spec.imageKey else "ph"}"
        is IconSpec.Balloon -> "${spec.color.toArgb()}|${if (spec.image != null) spec.imageKey else "ph"}"
        is IconSpec.Work -> "${spec.bangumiId}|${spec.color.toArgb()}|${spec.spriteIcon != null}|${spec.name}"
    }

    private fun cacheFor(spec: IconSpec): LruCache<String, Icon> = when (spec) {
        is IconSpec.Photo -> photoCache
        is IconSpec.Balloon -> balloonCache
        is IconSpec.Work -> workCache
    }

    /** 纯绘制（任意线程可）。 */
    fun render(spec: IconSpec): Rendered = when (spec) {
        is IconSpec.Photo -> renderPhoto(spec.color, spec.style, spec.image)
        is IconSpec.Balloon -> renderBalloon(spec.color, spec.image)
        is IconSpec.Work -> renderWork(spec.name, spec.color, spec.spriteIcon)
    }

    /**
     * 在 worker 线程调用：只画缓存和 pending 里都没有的，画完堆进 pending。
     * 之后 main 的 [icon] 无需绘制即可捞取。
     */
    fun prewarm(specs: Iterable<IconSpec>, isFrame: Boolean = false) {
        val wanted = HashSet<String>()
        for (spec in specs) {
            val key = keyFor(spec)
            wanted += key
            if (cacheFor(spec).get(key) != null) continue
            val alreadyPending = synchronized(pending) { pending.containsKey(key) }
            if (alreadyPending) continue
            val rendered = render(spec)
            synchronized(pending) { pending[key] = rendered }
        }
        // 整帧预绘时把这一帧不再需要的位图丢掉。否则被 3ms 预算截断后又从场景里消失的标注、
        // 缩略图缓存被逐出后不再来取 icon() 的活 Marker,都会把 pending 留成永久堆积
        //(每张 ~100-200KB,寿命等于地图)。下一帧再要就再画,icon() 本来就有就地绘制的兜底。
        // 单张补画(缩略图到货)不清:它与帧循环并发,清了会把整帧的预绘扔掉。
        if (isFrame) synchronized(pending) { pending.keys.retainAll(wanted) }
    }

    /** 在主线程调用：按缓存 → pending → 就地绘制的顺序解析并返回 Icon。 */
    fun icon(spec: IconSpec): Icon {
        val key = keyFor(spec)
        val cache = cacheFor(spec)
        cache.get(key)?.let { return it }
        val rendered = synchronized(pending) { pending.remove(key) } ?: render(spec)
        val icon = Icon(
            BitmapDescriptorFactory.fromBitmap(rendered.bitmap),
            rendered.anchorU,
            rendered.anchorV,
            rendered.bitmap.byteCount,
        )
        cache.put(key, icon)
        return icon
    }

    /** 已绘制的气球（供叠加层用，不包 BitmapDescriptor）。小 LruCache：一次只有一个选中点。 */
    private val balloonRenderedCache = LruCache<String, Rendered>(4)

    /**
     * 叠加层版的气球：返回 [Rendered]（位图 + 锚点），**不是** [Icon] ——
     * 选中气球由 `PointDotOverlay` 画在圆点之上，不再是 GMS Marker，
     * 用不着 BitmapDescriptor。任意线程可调用（绘制是纯 android.graphics）。
     */
    fun balloonRendered(spec: IconSpec.Balloon): Rendered {
        val key = keyFor(spec)
        balloonRenderedCache.get(key)?.let { return it }
        val rendered = render(spec)
        balloonRenderedCache.put(key, rendered)
        return rendered
    }

    // 旧 API（调用兼容）
    fun photo(themeColor: Color, style: PhotoStyle, image: Bitmap?, imageKey: String?): Icon =
        icon(IconSpec.Photo(themeColor, style, image, imageKey))
    fun balloon(themeColor: Color, image: Bitmap?, imageKey: String?): Icon =
        icon(IconSpec.Balloon(themeColor, image, imageKey))
    fun work(name: String, themeColor: Color, spriteIcon: Bitmap?, bangumiId: Int): Icon =
        icon(IconSpec.Work(name, themeColor, spriteIcon, bangumiId))

    // MARK: dot

    // MARK: photo

    enum class PhotoStyle { Card, Bubble }

    /**
     * 剧照缩略图标（Web 剧照 2 层的等价物）。两档意匠相同：
     * 「作品色边框 ＋ 滴 ＋ 图片内三角」，不同的只有尺寸。尾尖＝坐标。
     */
    private fun renderPhoto(themeColor: Color, style: PhotoStyle, image: Bitmap?): Rendered {
        val plate = when (style) {
            PhotoStyle.Card -> MapMarkerMetrics.PhotoPlate.CARD
            PhotoStyle.Bubble -> MapMarkerMetrics.PhotoPlate.BUBBLE
        }
        val width = ceil(dp(plate.totalWidth)).toInt()
        val height = ceil(dp(plate.totalHeight)).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val body = RectF(0f, 0f, dp(plate.bodyWidth), dp(plate.bodyHeight))

        // 作品色边框 + 滴（本体与尾是同一条 path —— 分开的话接缝处会出现线）
        paint.color = themeColor.toArgb()
        canvas.drawPath(
            platePath(
                body = body,
                cornerRadius = dp(plate.cornerRadius),
                tailWidth = dp(plate.dripWidth),
                tailHeight = dp(plate.dripHeight),
                tailTopOverlap = dp(0.5f),
            ),
            paint,
        )

        // 内侧：图片（没有则用主题色渐变）按内 path 裁剪
        val inset = dp(plate.borderWidth)
        val inner = RectF(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset)
        val innerPath = platePath(
            body = inner,
            cornerRadius = (dp(plate.cornerRadius) - inset).coerceAtLeast(0f),
            tailWidth = dp(plate.innerTailWidth),
            tailHeight = dp(plate.innerTailHeight),
            tailTopOverlap = dp(plate.innerTailOverlap) - inset,
        )
        canvas.save()
        canvas.clipPath(innerPath)
        if (image != null) {
            drawAspectFill(canvas, image, inner, paint)
        } else {
            paint.shader = LinearGradient(
                inner.left, inner.top, inner.right, inner.bottom,
                themeColor.toArgb(),
                ColorUtilities.mixed(themeColor, Color.Black, 0.28f).toArgb(),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
        canvas.restore()

        // 尾尖即坐标（anchorLift 1dp 在误差范围内，省略）
        val tipY = dp(plate.bodyHeight) + dp(plate.dripHeight)
        return Rendered(
            bitmap,
            anchorU = (dp(plate.bodyWidth) / 2) / width,
            anchorV = (tipY / height).coerceAtMost(1f),
        )
    }

    /** photo 的样式。与 Web 上两层交替的边界（z18）一致。 */
    fun photoStyle(zoom: Double): PhotoStyle =
        if (zoom > MapRevealLadder.PHOTO_CARD_ZOOM) {
            PhotoStyle.Card
        } else {
            PhotoStyle.Bubble
        }

    // MARK: balloon（选中气球）

    /** 头 32 ＋ 尾 10（dp）。尾尖＝坐标。 */
    private fun renderBalloon(themeColor: Color, image: Bitmap?): Rendered {
        val w = ceil(dp(32.0)).toInt()
        val h = ceil(dp(42.0)).toInt()
        // 描边（居中 0.75dp 外溢）+ 阴影的留白。iOS 靠 clipsToBounds=false 无留白地
        // 任其外溢，但 bitmap 一定会被裁掉，所以**四周**都留 padding（上边也要！）。
        val pad = ceil(dp(6.0)).toInt()
        val bitmap = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(pad.toFloat(), pad.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val radius = dp(16f)
        val cx = dp(16f)
        val cy = dp(16f)
        val tipY = dp(42f)
        // 从尾尖向圆所引切线的切点。偏离它，头与尾的接缝处就会出现台阶。
        val beta = acos((radius / (tipY - cy)).toDouble())
        val path = Path()
        val rightAngle = Math.PI / 2 - beta
        val leftAngle = Math.PI / 2 + beta
        // 从右切点经上方绕到左切点
        val steps = 40
        for (s in 0..steps) {
            // 从 rightAngle 逆时针（经画面上侧）到 leftAngle
            val angle = rightAngle - (2 * Math.PI - (leftAngle - rightAngle)) * s / steps
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.lineTo(cx, tipY)
        path.close()

        paint.setShadowLayer(dp(4f), 0f, dp(2f), android.graphics.Color.argb(56, 0, 0, 0))
        paint.color = themeColor.toArgb()
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = android.graphics.Color.WHITE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL

        // 24dp 的圆形缩略图（没有则用白点占位。主题色接近白色时改用暗色）
        val thumb = RectF(dp(4f), dp(4f), dp(28f), dp(28f))
        if (image != null) {
            canvas.save()
            val clip = Path().apply { addOval(thumb, Path.Direction.CW) }
            canvas.clipPath(clip)
            drawAspectFill(canvas, image, thumb, paint)
            canvas.restore()
        } else {
            paint.color = if (ColorUtilities.needsDarkInnerOutline(themeColor)) {
                android.graphics.Color.argb(255, 71, 71, 71)
            } else {
                android.graphics.Color.WHITE
            }
            canvas.drawCircle(thumb.centerX(), thumb.centerY(), dp(4.5f), paint)
        }

        return Rendered(
            bitmap,
            anchorU = (pad + cx) / (w + pad * 2),
            anchorV = ((pad + tipY) / (h + pad * 2)).coerceAtMost(1f),
        )
    }

    // MARK: work（作品标）

    /**
     * 作品标（z4–13）。圆形封面 ＋ 作品名。没有图标的作品用 12dp 的主题点 ——
     * **不做首字母占位**（不承载含义的单个字符只是地图的噪声）。
     * 标签按 10 字截断，用白 halo（描边 + 白影）从底图上托起。
     */
    private fun renderWork(name: String, themeColor: Color, spriteIcon: Bitmap?): Rendered {
        val badgeSide = dp(if (spriteIcon == null) 12.0 else MapMarkerMetrics.WorkMarker.ICON_SIZE)
        val maxLabelWidth = dp(92f)
        val labelGap = dp(2f)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(MapMarkerMetrics.WorkMarker.TEXT_SIZE.toFloat())
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = ColorUtilities.labelColor(themeColor).toArgb()
            setShadowLayer(dp(2f), 0f, 0f, android.graphics.Color.WHITE)
        }
        val strokePaint = Paint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.6f)
            color = android.graphics.Color.WHITE
            clearShadowLayer()
        }

        // 按**像素宽**截断（按字数截 CJK 10 字 ≫ 92dp，居中绘制会在画布左右
        // 对称地被裁掉 —— 真机反馈「左边缺／两边缺」的根因）。
        val text = TextUtils.ellipsize(
            name,
            TextPaint(fillPaint),
            maxLabelWidth - dp(2f),
            TextUtils.TruncateAt.END,
        ).toString()
        val measured = min(fillPaint.measureText(text) + dp(2f), maxLabelWidth)
        val fm = fillPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        // 四周留余量以免阴影、白晕出界（iOS 是无留白 + 不裁剪）
        val edgePad = ceil(dp(2f)).toInt()
        val width = ceil(maxOf(badgeSide, measured) + dp(4f)).toInt() + edgePad * 2
        val height = ceil(badgeSide + labelGap + textHeight + dp(2f)).toInt() + edgePad * 2

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, edgePad.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = width / 2f
        val badgeCy = badgeSide / 2 + dp(1f)

        // 白边 + 薄影的圆徽章
        paint.setShadowLayer(dp(1.5f), 0f, dp(0.5f), android.graphics.Color.argb(46, 0, 0, 0))
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(cx, badgeCy, badgeSide / 2, paint)
        paint.clearShadowLayer()

        val innerR = badgeSide / 2 - dp(1.5f)
        if (spriteIcon != null) {
            canvas.save()
            val clip = Path().apply {
                addOval(
                    RectF(cx - innerR, badgeCy - innerR, cx + innerR, badgeCy + innerR),
                    Path.Direction.CW,
                )
            }
            canvas.clipPath(clip)
            drawAspectFill(
                canvas, spriteIcon,
                RectF(cx - innerR, badgeCy - innerR, cx + innerR, badgeCy + innerR), paint,
            )
            canvas.restore()
        } else {
            paint.color = themeColor.toArgb()
            canvas.drawCircle(cx, badgeCy, innerR, paint)
        }

        // 标签（描边 → 填充 两遍绘制，复现 Web 的 text-halo）
        val textY = badgeSide + dp(1f) + labelGap - fm.ascent
        canvas.drawText(text, cx, textY, strokePaint)
        canvas.drawText(text, cx, textY, fillPaint)

        // 图标中心落在坐标上
        return Rendered(
            bitmap,
            anchorU = 0.5f,
            anchorV = (edgePad + badgeCy) / height,
        )
    }

    // MARK: 通用

    /** 把本体（圆角矩形）与下缘的尾合成一条 path。 */
    private fun platePath(
        body: RectF,
        cornerRadius: Float,
        tailWidth: Float,
        tailHeight: Float,
        tailTopOverlap: Float,
    ): Path {
        val path = Path()
        path.addRoundRect(body, cornerRadius.coerceAtLeast(0f), cornerRadius.coerceAtLeast(0f), Path.Direction.CW)
        val top = body.bottom - tailTopOverlap
        path.moveTo(body.centerX() - tailWidth / 2, top)
        path.lineTo(body.centerX() + tailWidth / 2, top)
        path.lineTo(body.centerX(), body.bottom + tailHeight)
        path.close()
        return path
    }

}

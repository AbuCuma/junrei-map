package cn.anitabi.map.ui.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cn.anitabi.map.theme.ColorUtilities
import cn.anitabi.map.support.drawAspectFill

/**
 * 成片合成(移植自 iOS ComparisonImageGenerator)。
 * - 上下对比卡:参考剧照与实拍按同比率叠成上下两段,右下角烧印坐标。
 * - 合体图:把抠出的角色以取景时相同的变换(scale → rotation → offset)叠加到实拍上。
 */
object ComparisonImageGenerator {

    // iOS DesignConstants 的逐字移植
    private const val CORNER_RADIUS = 12f
    private const val HORIZONTAL_PADDING = 20f
    private const val TOP_PADDING = 24f
    private const val BOTTOM_PADDING = 24f
    private const val IMAGE_SPACING = 12f
    private const val ICON_SIZE = 28f
    private const val TITLE_FONT_SIZE = 24f
    private const val LOCATION_FONT_SIZE = 16f

    /** 标准内容宽度。 */
    private const val BASE_CONTENT_WIDTH = 680f

    /** 单栏高度上限。超出时收窄宽度以保持比率(防止竖图过高)。 */
    private const val PANEL_HEIGHT_CAP = 760f

    fun comparisonCard(
        animeImage: Bitmap,
        userImage: Bitmap?,
        sceneName: String,
        sceneColor: Color,
        sceneLocation: String,
        panelAspect: Float,
    ): Bitmap {
        val aspect = if (panelAspect.isFinite() && panelAspect > 0) panelAspect else 16f / 9f

        var contentWidth = BASE_CONTENT_WIDTH
        if (contentWidth / aspect > PANEL_HEIGHT_CAP) {
            contentWidth = PANEL_HEIGHT_CAP * aspect
        }
        val panelHeight = contentWidth / aspect
        val canvasWidth = contentWidth + HORIZONTAL_PADDING * 2

        val topElementHeight = maxOf(ICON_SIZE, TITLE_FONT_SIZE + 6) + 6
        val bottomElementHeight = maxOf(ICON_SIZE, LOCATION_FONT_SIZE + 6) + 10
        val panels = if (userImage != null) 2 else 1
        val totalHeight = TOP_PADDING + topElementHeight + panelHeight * panels +
            (if (panels == 2) IMAGE_SPACING else 0f) + bottomElementHeight + BOTTOM_PADDING

        val bitmap = Bitmap.createBitmap(
            canvasWidth.toInt(), totalHeight.toInt(), Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 背景(用单色 + 圆角近似「作品色 → 略暗作品色」的纵向渐变)
        paint.color = sceneColor.toArgb()
        canvas.drawRoundRect(
            RectF(0f, 0f, canvasWidth, totalHeight), CORNER_RADIUS, CORNER_RADIUS, paint,
        )

        val labelColor = if (ColorUtilities.needsDarkInnerOutline(sceneColor)) {
            android.graphics.Color.argb(255, 30, 40, 30)
        } else {
            android.graphics.Color.WHITE
        }

        // 标题
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = TITLE_FONT_SIZE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText(
            sceneName,
            HORIZONTAL_PADDING,
            TOP_PADDING + TITLE_FONT_SIZE,
            titlePaint,
        )

        // 参考剧照
        var y = TOP_PADDING + topElementHeight + 8
        drawAspectFill(
            canvas, animeImage,
            RectF(HORIZONTAL_PADDING, y, HORIZONTAL_PADDING + contentWidth, y + panelHeight),
            paint,
            cornerRadius = 8f,
        )
        y += panelHeight

        // 实拍
        if (userImage != null) {
            y += IMAGE_SPACING
            drawAspectFill(
                canvas, userImage,
                RectF(HORIZONTAL_PADDING, y, HORIZONTAL_PADDING + contentWidth, y + panelHeight),
                paint,
                cornerRadius = 8f,
            )
            y += panelHeight
        }

        // 坐标(右下·等宽字体风格)
        val locationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = LOCATION_FONT_SIZE
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            sceneLocation,
            canvasWidth - HORIZONTAL_PADDING,
            y + bottomElementHeight - 6,
            locationPaint,
        )
        return bitmap
    }

    /**
     * 合体图:**精确复刻**取景框里看到的位置。屏幕上按 scaleEffect → rotation → offset 的
     * 顺序生效,故按相同顺序拼矩阵。位移以归一化比率传入,与分辨率无关。
     */
    fun composite(
        userImage: Bitmap,
        cutout: Bitmap,
        scale: Float,
        rotationDegrees: Float,
        offsetRatioX: Float,
        offsetRatioY: Float,
    ): Bitmap {
        val out = Bitmap.createBitmap(userImage.width, userImage.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(userImage, 0f, 0f, paint)

        // 前提:剧照(cutout)在取景时是 aspect-fit 装进与实拍相同的框里的,先求基准缩放
        val baseScale = minOf(
            userImage.width.toFloat() / cutout.width,
            userImage.height.toFloat() / cutout.height,
        )
        val matrix = Matrix()
        matrix.postTranslate(-cutout.width / 2f, -cutout.height / 2f)
        matrix.postScale(baseScale * scale, baseScale * scale)
        matrix.postRotate(rotationDegrees)
        matrix.postTranslate(
            userImage.width / 2f + offsetRatioX * userImage.width,
            userImage.height / 2f + offsetRatioY * userImage.height,
        )
        canvas.drawBitmap(cutout, matrix, paint)
        return out
    }

}

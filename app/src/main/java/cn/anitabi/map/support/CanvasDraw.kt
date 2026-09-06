package cn.anitabi.map.support

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * 把 [image] 以 aspect-fill(等比铺满、居中裁切)画进 [into];[cornerRadius] > 0 时圆角。
 * MarkerIconFactory(标注贴图)与 ComparisonImageGenerator(对比卡)共用 ——
 * 此前是两份仅差圆角的近似实现。
 * 用 BitmapShader + drawRoundRect 而非 clipPath:圆角边缘吃到 paint 的抗锯齿。
 */
fun drawAspectFill(
    canvas: Canvas,
    image: Bitmap,
    into: RectF,
    paint: Paint,
    cornerRadius: Float = 0f,
) {
    val scale = maxOf(into.width() / image.width, into.height() / image.height)
    val shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val matrix = Matrix()
    matrix.setScale(scale, scale)
    matrix.postTranslate(
        into.left + (into.width() - image.width * scale) / 2,
        into.top + (into.height() - image.height * scale) / 2,
    )
    shader.setLocalMatrix(matrix)
    paint.shader = shader
    if (cornerRadius > 0f) {
        canvas.drawRoundRect(into, cornerRadius, cornerRadius, paint)
    } else {
        canvas.drawRect(into, paint)
    }
    paint.shader = null
}

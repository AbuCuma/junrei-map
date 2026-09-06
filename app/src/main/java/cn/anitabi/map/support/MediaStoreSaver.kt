package cn.anitabi.map.support

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册保存(iOS PhotoLibrarySaver 的移植)。minSdk 29:MediaStore insert 无需权限。
 *
 * 图片查看器与对比拍摄各自抄过一份一模一样的实现。抽出来的直接理由是:
 * 「保存不能随组合被取消」这个修复必须在两处分别做一遍,重复本身就是缺陷载体。
 */
object MediaStoreSaver {

    private const val RELATIVE_PATH = "Pictures/Anitabi"
    private const val JPEG_QUALITY = 92

    /**
     * 写入 `Pictures/Anitabi`。
     *
     * `IS_PENDING=1` 让文件在写入期间对其他相册应用不可见,避免被索引到半写的 JPEG;
     * 压缩失败时删掉占位条目,不留下 0 字节的空图。
     */
    suspend fun saveJpeg(context: Context, bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "anitabi_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            val ok = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            } ?: false
            if (ok) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null,
                )
            } else {
                resolver.delete(uri, null, null)
            }
            ok
        } catch (c: CancellationException) {
            // 仓库规则:宽 catch 必须先重抛取消(ARCHITECTURE §9-4)。
            throw c
        } catch (_: Exception) {
            false
        }
    }
}

package cn.anitabi.map.ui.image

import cn.anitabi.map.R
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.anitabi.map.app.AppGraph
import cn.anitabi.map.app.ToastCenter
import cn.anitabi.map.data.model.AnitabiImage
import cn.anitabi.map.support.MediaStoreSaver
import cn.anitabi.map.ui.components.ForceLightStatusBarIcons
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏图片查看器（真机反馈批次 4 重新设计 — 对照 Google Maps 的浏览体验）。
 * - 内联 overlay（不用 Dialog）：背景一直铺到通知栏后面，toast 也能显示在其上。
 * - 背景 = 图片平均色向黑色靠 40% 的实底 + （API 31+）图片自身的 60dp blur + 黑 35% scrim。
 * - 缩放：fit（任一边贴住屏幕）= 最小。手势中允许带阻尼沉到 1 以下，
 *   松手时弹簧回弹。上限 3×。双击在 1× ⇄ 2× 之间切换。
 * - 平移：仅限放大后的图片范围内（以 ±(fit 尺寸×scale − 屏幕)/2 实时夹紧）。不可自由漂移。
 */
@Composable
fun ImageViewer(
    url: String,
    toast: ToastCenter,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 保存动作要活过本组合(见按钮处的说明)。用 applicationContext:
    // 这段协程有意比本页面活得久,抓着 Activity 就是在已销毁的 Activity 上多留几秒引用。
    val appScope = remember(context) { AppGraph.get(context).appScope }
    val appContext = remember(context) { context.applicationContext }
    // 文案在组合期取好:回调里已经没有 composable 上下文,而在回调里用 context.getString
    // 会绕开 Compose 的语言/配置变更(lint LocalContextGetResourceValueCall)。
    val savedMessage = stringResource(R.string.saved_to_photos)
    val failedMessage = stringResource(R.string.save_failed)

    // 深色满屏背景铺到状态栏后面 → 图标须转浅色,否则浅色主题下时钟/电量不可读。
    ForceLightStatusBarIcons()

    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    // 平均色(1×1 全图滤波缩放)与解码同在 IO:此前放在 remember{} 里,
    // 会在图像到达的那一帧于主线程组合期做全图降采样。
    var averageColor by remember(url) { mutableStateOf(Color.Black) }
    // 源站抖动时(实测 Cloudflare 525)重试即成功,故自动重试;全败后给可点重试的
    // 失败态。次数压到 2:host/尺寸的候选已由 AppGraph 的拦截器逐请求兜底,
    // 再乘 3 次就是对源站的无谓压力。
    var loadFailed by remember(url) { mutableStateOf(false) }
    var loadAttempt by remember(url) { mutableIntStateOf(0) }
    LaunchedEffect(url, loadAttempt) {
        loadFailed = false
        val (decoded, avg) = withContext(Dispatchers.IO) {
            var b: Bitmap? = null
            for (attempt in 0 until 2) {
                if (attempt > 0) delay(600L * attempt)
                b = runCatching {
                    context.imageLoader.execute(
                        ImageRequest.Builder(context).data(url).allowHardware(false).build()
                    ).image?.toBitmap()
                }.getOrNull()
                if (b != null) break
            }
            val color = b?.let {
                val one = Bitmap.createScaledBitmap(it, 1, 1, true)
                Color(one.getPixel(0, 0)).also { _ -> one.recycle() }
            } ?: Color.Black
            b to color
        }
        bitmap = decoded
        averageColor = avg
        loadFailed = decoded == null
    }
    val backdrop = lerp(averageColor, Color.Black, 0.4f)

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // fit 态（scale=1）下的显示尺寸（与 ContentScale.Fit 相同的计算）
    fun fitSize(): Offset {
        val b = bitmap ?: return Offset.Zero
        if (containerSize == IntSize.Zero) return Offset.Zero
        val s = min(
            containerSize.width.toFloat() / b.width,
            containerSize.height.toFloat() / b.height,
        )
        return Offset(b.width * s, b.height * s)
    }

    fun maxOffset(atScale: Float): Offset {
        val fit = fitSize()
        return Offset(
            max((fit.x * atScale - containerSize.width) / 2f, 0f),
            max((fit.y * atScale - containerSize.height) / 2f, 0f),
        )
    }

    fun animateTo(targetScale: Float, targetX: Float, targetY: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            val fromS = scale
            val fromX = offsetX
            val fromY = offsetY
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.85f, stiffness = 350f)) { t, _ ->
                scale = fromS + (targetScale - fromS) * t
                offsetX = fromX + (targetX - fromX) * t
                offsetY = fromY + (targetY - fromY) * t
            }
        }
    }

    // 容器尺寸变了(旋转、分屏、折叠)要重新夹紧:manifest 声明了 configChanges,
    // 缩放/平移量会原样留下来,不重夹的话放大后旋转会把图片留在屏幕外,
    // 直到用户再次触摸才被手势末尾的吸附纠正。
    LaunchedEffect(containerSize, bitmap) {
        if (containerSize == IntSize.Zero || bitmap == null) return@LaunchedEffect
        val limit = maxOffset(scale)
        offsetX = offsetX.coerceIn(-limit.x, limit.x)
        offsetY = offsetY.coerceIn(-limit.y, limit.y)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdrop)
            .onSizeChanged { containerSize = it },
    ) {
        // 同色调的模糊背景（Google Maps 风格）：把图片自身模糊后铺在底层
        bitmap?.let { b ->
            if (Build.VERSION.SDK_INT >= 31) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(60.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        // 手势面（比图片更大，全屏接收）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        settleJob?.cancel()
                        // 本次手势是否真的缩放/平移过。纯点按(含双击)绝不能走下面的回弹:
                        // Main 阶段自内向外派发,内层 detectTapGestures 会先收到 UP 并启动
                        // 双击动画,此时 scale 还没被动画写新值;外层若无条件回弹,就会用旧的
                        // scale 计算目标并 cancel 掉刚起步的双击动画 —— 双击缩放因此完全失效。
                        var transformed = false
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                transformed = true
                                val raw = scale * zoom
                                // 小于 1 时加阻尼（只沉一半，0.6 触底）；上方在 3 处硬性止住
                                scale = when {
                                    raw < 1f -> (1f - (1f - raw) * 0.5f).coerceAtLeast(0.6f)
                                    else -> raw.coerceAtMost(3f)
                                }
                                val limit = maxOffset(scale)
                                offsetX = (offsetX * zoom + pan.x).coerceIn(-limit.x, limit.x)
                                offsetY = (offsetY * zoom + pan.y).coerceIn(-limit.y, limit.y)
                                event.changes.forEach { it.consume() }
                            }
                            event = awaitPointerEvent()
                        }
                        // 松手：回弹到最小 1× + 平移吸附到边界。
                        // `scale < 1f` 这一支是必须的:捏合到 1× 以下松手会起一段回弹动画,
                        // 期间单击会在 :212 把它取消掉 —— 只看 transformed 的话图片就永远
                        // 停在缩小态了。而双击时 settleJob 已经被内层的动画占住(Main 阶段
                        // 自内向外,内层先跑),所以这一支不会把双击动画顶掉。
                        if (transformed || (scale < 1f && settleJob?.isActive != true)) {
                            val settleScale = scale.coerceIn(1f, 3f)
                            val limit = maxOffset(settleScale)
                            animateTo(
                                settleScale,
                                offsetX.coerceIn(-limit.x, limit.x),
                                offsetY.coerceIn(-limit.y, limit.y),
                            )
                        }
                    }
                }
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.01f) {
                                animateTo(1f, 0f, 0f)
                            } else {
                                animateTo(2f, 0f, 0f)
                            }
                        },
                    )
                },
        ) {
            val b = bitmap
            if (b == null) {
                if (loadFailed) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text(
                            text = stringResource(R.string.image_load_failed),
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.18f))
                                .clickable { loadAttempt += 1 }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text(text = stringResource(R.string.retry), color = Color.White)
                        }
                    }
                } else {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            } else {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            ViewerIconButton(Icons.Outlined.Download, stringResource(R.string.save)) {
                // 用户已经按下的动作要做完 —— 挂在组合的作用域上时,紧接着关掉查看器
                // 会把下载/编码中途取消,既没有文件也没有任何提示。
                appScope.launch {
                    val ok = saveToPhotos(appContext, url)
                    toast.show(if (ok) savedMessage else failedMessage)
                }
            }
            ViewerIconButton(Icons.Outlined.Share, stringResource(R.string.share)) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(send, null))
            }
            ViewerIconButton(Icons.Rounded.Close, stringResource(R.string.close), onClick = onDismiss)
        }
    }
}

@Composable
private fun ViewerIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * 相册保存(iOS PhotoLibrarySaver 的移植)。minSdk 29:MediaStore insert 无需权限。
 * IS_PENDING:写入期间对其他相册应用隐藏,避免被索引到半写的 JPEG。
 */
suspend fun saveToPhotos(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
    // 保存是用户主动的低频动作,画质优先:先取完整尺寸,取不到再退回展示用的档位
    // (完整尺寸未被 CDN 缓存的比例高,源站不稳时经常只有小档能出)。
    val result = listOf(AnitabiImage.withPlan(url, null), url)
        .distinct()
        .firstNotNullOfOrNull { candidate ->
            // hardware bitmap 无法 compress,以 software 拿取。
            val request = ImageRequest.Builder(context).data(candidate).allowHardware(false).build()
            try {
                context.imageLoader.execute(request).image?.toBitmap()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                null
            }
        } ?: return@withContext false

    MediaStoreSaver.saveJpeg(context, result)
}

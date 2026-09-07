package cn.anitabi.map.ui.scene

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Portrait
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cn.anitabi.map.R
import cn.anitabi.map.app.AppGraph
import cn.anitabi.map.app.ToastCenter
import cn.anitabi.map.data.model.AnitabiImage
import cn.anitabi.map.support.MediaStoreSaver
import cn.anitabi.map.theme.ColorUtilities
import cn.anitabi.map.ui.scene.cutout.CutoutEngine
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 对比拍摄(对照 iOS SceneComparisonView,B3/O5 修正版)。
 * - 取景为两级 aspectFit(iOS L110-146):feed = 4:3 适配屏幕,frame = 参考比率适配 feed 内。
 *   相机只显示在 feed 区域(不铺满全屏),frame 外压暗黑色 45%。
 * - 成片按 frame(＝参考比率)**中心裁剪**(iOS cropped(toAspect:))。合体图同比率。
 * - 成果页:并列卡 ⇄ 合体图往返切换(iOS 没有的增强)+ 返回直达 spot 页。
 * - 图标化:peek=eye(按住)、抠像=portrait、透明度=水滴图标 + 竖滑杆 + 百分比。
 */
data class CameraSessionParams(
    val photoUrl: String,
    val name: String,
    val themeHex: String?,
    /** "lat,lng"。烧印在成片右下角。 */
    val location: String,
)

private const val CAMERA_ASPECT = 4f / 3f

@Composable
fun SceneComparisonScreen(
    params: CameraSessionParams,
    toast: ToastCenter,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 文案在组合期取好:回调里没有 composable 上下文,且 context.getString 会绕开
    // Compose 的配置变更(lint LocalContextGetResourceValueCall)。
    val captureFailedMessage = stringResource(R.string.capture_failed)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // 从系统设置授权后返回,remember 的种子不会自己刷新 → ON_RESUME 重查。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 参考剧照(原尺寸·software bitmap)与抠图
    var reference by remember { mutableStateOf<Bitmap?>(null) }
    var cutout by remember { mutableStateOf<SubjectExtractor.Cutout?>(null) }
    var useCutout by remember { mutableStateOf(false) }
    var extracting by remember { mutableStateOf(false) }
    // 是否正在获取参考图(为在 UI 上区分下载耗时与抠图耗时。null=加载中, false=完成/失败)
    var referenceLoading by remember { mutableStateOf(true) }
    val cutoutEngine = remember { AppGraph.get(context).cutoutEngine }
    val cutoutState by cutoutEngine.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { cutoutEngine.warmUp() }
    LaunchedEffect(params.photoUrl) {
        referenceLoading = true
        val bitmap = withContext(Dispatchers.IO) {
            // 抠图质量依赖原始分辨率,所以完整尺寸优先;取不到时退 h360
            // (源站不稳时未缓存的完整尺寸经常回源失败,退档总比抠不出来强)。
            listOf(params.photoUrl, AnitabiImage.withPlan(params.photoUrl, "h360"))
                .distinct()
                .firstNotNullOfOrNull { candidate ->
                    try {
                        context.imageLoader.execute(
                            ImageRequest.Builder(context).data(candidate)
                                .allowHardware(false)
                                .build()
                        ).image?.toBitmap()
                    } catch (c: CancellationException) {
                        // runCatching 会把取消也吞掉,导致已取消的协程继续去试下一个候选。
                        throw c
                    } catch (_: Exception) {
                        null
                    }
                }
        }
        reference = bitmap
        referenceLoading = false
        if (bitmap != null) {
            extracting = true
            // 抠图会同时持有五份全分辨率缓冲(源图/前景/两个 IntArray/结果图),
            // 完整尺寸剧照下 OOM 是真实可能。**不能让 Error 逃进 recomposer**——
            // 那会直接掀掉进程;抠不出来只是回到纯幽灵叠加,功能仍然可用。
            cutout = try {
                cutoutEngine.extract(bitmap)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // 留一条日志:release 里 ML Kit 初始化曾同步抛异常,被这里吞掉后现象只是「没有抠图开关」,
                // 没有任何线索。不含图片内容与用户数据。
                Log.w("SceneComparison", "cutout failed: $t")
                null
            }
            extracting = false
        }
    }

    // 幽灵叠加的变换
    var ghostAlpha by remember { mutableFloatStateOf(0.55f) }
    var peeking by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }

    // 拍摄结果(已裁剪到 refAspect)
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    /** 快门在途:防止连点并发跑多条全分辨率管线。 */
    var capturing by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
        }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) controller.bindToLifecycle(lifecycleOwner)
    }
    // 绑定的是 Activity 生命周期(关闭本页后 Activity 仍 STARTED),必须显式解绑,
    // 否则相机在返回地图后保持打开:Android 12+ 绿色隐私指示灯常亮、耗电发热。
    DisposableEffect(controller) {
        onDispose { controller.unbind() }
    }

    val refAspect = reference?.let { it.width.toFloat() / it.height } ?: (16f / 9f)

    // 为防空白处的触摸穿透到下层常驻 sheet(搜索框等),在根节点消费所有点按(真机反馈)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val capturedBitmap = captured
        if (capturedBitmap != null) {
            ResultPage(
                params = params,
                reference = reference,
                captured = capturedBitmap,
                cutout = cutout,
                refAspect = refAspect,
                ghostTransform = GhostTransform(
                    scale, rotation,
                    if (frameSize.width > 0) offsetX / frameSize.width else 0f,
                    if (frameSize.height > 0) offsetY / frameSize.height else 0f,
                ),
                toast = toast,
                onRetake = { captured = null },
                onClose = onClose,
            )
            return@Box
        }

        // ===== 取景(两级 aspectFit — iOS L110-146)=====
        var screenSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().onSizeChanged { screenSize = it },
        ) {
            if (screenSize != IntSize.Zero) {
                val sw = screenSize.width.toFloat()
                val sh = screenSize.height.toFloat()
                val feedH = min(sw / CAMERA_ASPECT, sh)
                val feedW = feedH * CAMERA_ASPECT
                val frameH = min(feedW / refAspect, feedH)
                val frameW = frameH * refAspect
                val feedWDp = with(density) { feedW.toDp() }
                val feedHDp = with(density) { feedH.toDp() }
                val frameWDp = with(density) { frameW.toDp() }
                val frameHDp = with(density) { frameH.toDp() }

                // 相机只占 feed 区域(不铺满全屏 — 真机反馈)
                Box(modifier = Modifier.size(feedWDp, feedHDp)) {
                    if (hasPermission) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).also {
                                    it.controller = controller
                                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // 点击直达系统设置:永久拒绝后 launcher 是静默 no-op,
                        // 没有这个入口用户会永远停在黑屏。
                        Text(
                            stringResource(R.string.camera_permission_needed),
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clickable {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}"),
                                            )
                                        )
                                    }
                                }
                                .padding(16.dp),
                        )
                    }

                    // 对比取景框(feed 中心)+ 幽灵 + 框外压暗 45%(iOS L130-133)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 框外压暗(上下/左右的条带)— 位于幽灵之下、取景框之外
                        val dim = Color.Black.copy(alpha = 0.45f)
                        val padW = with(density) { ((feedW - frameW) / 2).toDp() }
                        val padH = with(density) { ((feedH - frameH) / 2).toDp() }
                        if (padH.value > 0f) {
                            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(padH).background(dim))
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(padH).background(dim))
                        }
                        if (padW.value > 0f) {
                            Box(Modifier.align(Alignment.CenterStart).width(padW).fillMaxSize().background(dim))
                            Box(Modifier.align(Alignment.CenterEnd).width(padW).fillMaxSize().background(dim))
                        }

                        Box(
                            modifier = Modifier
                                .size(frameWDp, frameHDp)
                                .onSizeChanged { frameSize = it }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, twist ->
                                        scale = (scale * zoom).coerceIn(0.3f, 4f)
                                        rotation += twist
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = {
                                        scale = 1f; rotation = 0f; offsetX = 0f; offsetY = 0f
                                    })
                                },
                        ) {
                            val ghost = if (useCutout) cutout?.bitmap else reference
                            ghost?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop, // scaledToFill(iOS L249)
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RectangleShape)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            rotationZ = rotation
                                            translationX = offsetX
                                            translationY = offsetY
                                            // alpha 并入同一 graphicsLayer 块(绘制相位读):
                                            // 值形式的 .alpha() 会让透明度滑轨的每个触摸帧
                                            // 重组整个相机屏(含 PreviewView 的 AndroidView)。
                                            alpha = if (peeking) 0f else ghostAlpha
                                        },
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.dp, Color.White.copy(alpha = 0.7f), RectangleShape),
                            )

                            // 阶段提示:下载参考图 → 抠图中。在取景框中央显示当前卡在哪一步。
                            // 参考图取不到时必须**说出来**:没有它就拼不出对比图,
                            // 此前是让用户照常按快门,然后在成果页上无限转圈且无路可退。
                            val loadingLabel = when {
                                referenceLoading -> stringResource(R.string.loading_reference)
                                reference == null -> stringResource(R.string.reference_load_failed)
                                extracting -> stringResource(R.string.extracting_subject)
                                else -> null
                            }
                            loadingLabel?.let { label ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                ) {
                                    // 失败态不转圈:转圈意味着「还在进行」,会让用户一直等下去。
                                    if (referenceLoading || extracting) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Text(label, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ===== 左侧控制列:返回 / 透明度 rail / peek / 抠像 =====
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .safeDrawingPadding()
                .padding(start = 10.dp),
        ) {
            CameraIconButton(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), onClick = onClose)

            // 透明度 rail(iOS opacityRail L557-581:图标 + 竖滑杆 + 百分比)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(vertical = 12.dp, horizontal = 7.dp),
            ) {
                Icon(
                    Icons.Outlined.Opacity, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(15.dp),
                )
                // 自绘竖滑杆:M3 Slider 的 graphicsLayer 旋转只影响绘制,实际尺寸被 36dp 宽的
                // 父级压扁,轨道变短,thumb 在黑底上也看不见(真机反馈)。
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 160.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun update(y: Float) {
                                    ghostAlpha = (1f - y / size.height).coerceIn(0f, 1f)
                                }
                                update(down.position.y)
                                down.consume()
                                drag(down.id) { change ->
                                    update(change.position.y)
                                    change.consume()
                                }
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val trackW = 4.dp.toPx()
                        val thumbR = 7.dp.toPx()
                        val cx = size.width / 2
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.35f),
                            topLeft = Offset(cx - trackW / 2, 0f),
                            size = Size(trackW, size.height),
                            cornerRadius = CornerRadius(trackW / 2),
                        )
                        val fillTop = size.height * (1f - ghostAlpha)
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(cx - trackW / 2, fillTop),
                            size = Size(trackW, size.height - fillTop),
                            cornerRadius = CornerRadius(trackW / 2),
                        )
                        drawCircle(
                            color = Color.White,
                            radius = thumbR,
                            center = Offset(cx, fillTop.coerceIn(thumbR, size.height - thumbR)),
                        )
                    }
                }
                // 独立小组件:拖轨时百分比文本的重组只失效它自己,不牵动整屏。
                AlphaPercentLabel(alpha = { ghostAlpha })
            }

            // peek:**按住**隐藏参考图(iOS eye/eye.slash)
            CameraIconButton(
                if (peeking) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = stringResource(R.string.a11y_peek),
                pressAndHold = true,
                onPress = { peeking = it },
                onClick = {},
            )

            // 抠像模式切换(iOS person.and.background.dotted)。进行中的提示交给取景框中央的阶段标签。
            if (cutout != null) {
                CameraIconButton(
                    Icons.Outlined.Portrait,
                    contentDescription = stringResource(R.string.character_only),
                    active = useCutout,
                ) { useCutout = !useCutout }
            }
            // ISNet 运行时下载期间附上小字进度(取得后下次起即用高品质抠图)。
            (cutoutState as? CutoutEngine.State.Downloading)?.let { dl ->
                Text(
                    stringResource(R.string.cutout_model_downloading, (dl.progress * 100).toInt()),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // ===== 右侧控制:焦段列 + 快门放**两个独立列**,双方都对齐画面中线(真机反馈 C1)=====
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .safeDrawingPadding()
                .padding(end = 10.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (ratio in listOf(1f, 2f, 3f)) {
                    val selected = zoomRatio == ratio
                    Text(
                        "${ratio.toInt()}×",
                        color = if (selected) Color.Yellow else Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                            .clickable {
                                // clamp 到设备实际支持的最大变焦:超界的 setZoomRatio 会静默失败,
                                // UI 却把 3× 涂成选中。
                                val max = controller.cameraInfo?.zoomState?.value?.maxZoomRatio ?: ratio
                                val applied = ratio.coerceAtMost(max)
                                zoomRatio = applied
                                controller.setZoomRatio(applied)
                            }
                            // 选中态给语义(不只靠黄色)。
                            .semantics { if (selected) this.selected = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            // 快门(纯白圆 Box,无任何子内容 —— 必须显式给语义,否则 TalkBack 读不出任何东西)
            val shutterLabel = stringResource(R.string.a11y_shutter)
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .semantics {
                        contentDescription = shutterLabel
                        role = Role.Button
                    }
                    // 连点会并发跑多条「拍照 + 全分辨率中心裁剪」管线,各自持有整张大图,
                    // 最后一条胜出,前面的全是白烧内存 —— 在大尺寸参考图上足以推向 OOM。
                    // 没有参考图就拼不出对比图,此时按快门只会把用户送进死胡同。
                    .clickable(enabled = hasPermission && !capturing && reference != null) {
                        scope.launch {
                            capturing = true
                            try {
                                val photo = takePicture(controller, context) ?: run {
                                    toast.show(captureFailedMessage)
                                    return@launch
                                }
                                // 成片按参考比率中心裁剪(iOS cropped(toAspect:) L919-923)
                                captured = withContext(Dispatchers.Default) {
                                    centerCrop(photo, refAspect)
                                }
                            } finally {
                                capturing = false
                            }
                        }
                    },
            )
        }
    }
}

// MARK: - 成果页(并列 ⇄ 合体往返切换 — 增强)

data class GhostTransform(
    val scale: Float,
    val rotationDegrees: Float,
    val offsetRatioX: Float,
    val offsetRatioY: Float,
)

@Composable
private fun ResultPage(
    params: CameraSessionParams,
    reference: Bitmap?,
    captured: Bitmap,
    cutout: SubjectExtractor.Cutout?,
    refAspect: Float,
    ghostTransform: GhostTransform,
    toast: ToastCenter,
    onRetake: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 保存动作要活过本页(见 onSave 的说明)。用 applicationContext:见 ImageViewer 同处说明。
    val appScope = remember(context) { AppGraph.get(context).appScope }
    val appContext = remember(context) { context.applicationContext }
    val savedMessage = stringResource(R.string.saved_to_photos)
    val saveFailedMessage = stringResource(R.string.save_failed)
    var compositeMode by remember { mutableStateOf(false) }
    // 两种形态懒生成 + 缓存(往返切换即时)
    var pairCard by remember { mutableStateOf<Bitmap?>(null) }
    var compositeCard by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(compositeMode) {
        withContext(Dispatchers.Default) {
            if (compositeMode && compositeCard == null && cutout != null) {
                compositeCard = ComparisonImageGenerator.composite(
                    userImage = captured,
                    cutout = cutout.bitmap,
                    scale = ghostTransform.scale,
                    rotationDegrees = ghostTransform.rotationDegrees,
                    offsetRatioX = ghostTransform.offsetRatioX,
                    offsetRatioY = ghostTransform.offsetRatioY,
                )
            }
            if (!compositeMode && pairCard == null && reference != null) {
                pairCard = ComparisonImageGenerator.comparisonCard(
                    animeImage = reference,
                    userImage = captured,
                    sceneName = params.name,
                    sceneColor = ColorUtilities.themeColor(params.themeHex),
                    sceneLocation = params.location,
                    panelAspect = refAspect,
                )
            }
        }
    }

    val shown = if (compositeMode) compositeCard else pairCard
    val onSave: () -> Unit = {
        shown?.let { bitmap ->
            // 与 ImageViewer 同理:保存是用户已经按下的动作,不能随本页被销毁而取消。
            appScope.launch {
                val ok = MediaStoreSaver.saveJpeg(appContext, bitmap)
                toast.show(if (ok) savedMessage else saveFailedMessage)
            }
        }
    }

    val resultImage: @Composable (Modifier) -> Unit = { modifier ->
        Box(contentAlignment = Alignment.Center, modifier = modifier) {
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }

    // 布局按实际窗口纵横比决定,而不是假设横屏:targetSdk 36 起,sw≥600dp 设备
    // (平板/展开折叠屏)会忽略 requestedOrientation,本页可能以竖屏渲染。
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        val portrait = maxHeight > maxWidth
        if (portrait) {
            // 竖屏:返回在顶部,操作收进底部一行,图像占满中段。
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp)) {
                    CameraIconButton(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), onClick = onClose)
                }
                resultImage(Modifier.weight(1f).fillMaxWidth())
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    if (cutout != null) {
                        ResultModeToggle(
                            compositeMode = compositeMode,
                            vertical = false,
                            onSelect = { compositeMode = it },
                        )
                    }
                    CameraIconButton(Icons.Outlined.Download, contentDescription = stringResource(R.string.save), onClick = onSave)
                    CameraIconButton(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.retake), onClick = onRetake)
                }
            }
        } else {
            // 横屏:操作列在左右侧栏,图像用满高度(真机反馈)。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 左栏:返回(直接回 spot 页 — 真机要求)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxHeight().padding(start = 10.dp, top = 10.dp),
                ) {
                    CameraIconButton(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), onClick = onClose)
                }

                resultImage(Modifier.weight(1f).fillMaxHeight())

                // 右栏:并列 ⇄ 合成(纵向分段)/ 保存 / 重拍
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxHeight().padding(end = 10.dp),
                ) {
                    if (cutout != null) {
                        ResultModeToggle(
                            compositeMode = compositeMode,
                            vertical = true,
                            onSelect = { compositeMode = it },
                        )
                    }
                    CameraIconButton(Icons.Outlined.Download, contentDescription = stringResource(R.string.save), onClick = onSave)
                    CameraIconButton(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.retake), onClick = onRetake)
                }
            }
        }
    }
}

/** 透明度百分比标签。provider 形式:滑轨拖动帧只重组这一个 Text。 */
@Composable
private fun AlphaPercentLabel(alpha: () -> Float) {
    Text(
        "${(alpha() * 100).roundToInt()}%",
        color = Color.White, fontSize = 10.sp,
    )
}

/** 并列 ⇄ 合成切换(胶囊分段;横屏纵排、竖屏横排)。 */
@Composable
private fun ResultModeToggle(
    compositeMode: Boolean,
    vertical: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val entries = listOf(
        false to Icons.Outlined.ViewAgenda,
        true to Icons.Outlined.Portrait,
    )
    val labels = mapOf(
        false to stringResource(R.string.full_image_mode),
        true to stringResource(R.string.cutout_mode),
    )
    val item: @Composable (Boolean, ImageVector) -> Unit =
        { mode, icon ->
            val selected = compositeMode == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable(role = Role.Button) { onSelect(mode) }
                    .semantics { if (selected) this.selected = true },
            ) {
                Icon(
                    icon, contentDescription = labels[mode],
                    tint = if (selected) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    val chrome = Modifier
        .clip(CircleShape)
        .background(Color.White.copy(alpha = 0.15f))
        .padding(3.dp)
    if (vertical) {
        Column(modifier = chrome) { entries.forEach { (m, i) -> item(m, i) } }
    } else {
        Row(modifier = chrome) { entries.forEach { (m, i) -> item(m, i) } }
    }
}

// MARK: - 部件

@Composable
private fun CameraIconButton(
    icon: ImageVector,
    contentDescription: String? = null,
    active: Boolean = false,
    pressAndHold: Boolean = false,
    onPress: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (active) Color.White else Color.Black.copy(alpha = 0.45f))
            .let { base ->
                if (pressAndHold) {
                    base.pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            onPress(true)
                            tryAwaitRelease()
                            onPress(false)
                        })
                    }
                } else {
                    base.clickable(onClick = onClick)
                }
            },
    ) {
        Icon(
            icon, contentDescription = contentDescription,
            tint = if (active) Color.Black else Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 中心裁剪到目标比率(相当于 iOS UIImage.cropped(toAspect:))。 */
private fun centerCrop(source: Bitmap, aspect: Float): Bitmap {
    val srcAspect = source.width.toFloat() / source.height
    return if (srcAspect > aspect) {
        val w = (source.height * aspect).roundToInt().coerceAtMost(source.width)
        Bitmap.createBitmap(source, (source.width - w) / 2, 0, w, source.height)
    } else {
        val h = (source.width / aspect).roundToInt().coerceAtMost(source.height)
        Bitmap.createBitmap(source, 0, (source.height - h) / 2, source.width, h)
    }
}

private suspend fun takePicture(
    controller: LifecycleCameraController,
    @Suppress("UNUSED_PARAMETER") context: android.content.Context,
): Bitmap? = suspendCancellableCoroutine { continuation ->
    controller.takePicture(
        // 回调在后台执行:12MP 的 JPEG 解码 + 旋转拷贝约 ~48MB 分配,
        // 放主线程会掉几百 ms 的帧。resume 线程安全,调用方协程照常恢复。
        Dispatchers.Default.asExecutor(),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // EXIF 旋转转正(相当于 iOS normalizedUp,真机确认过正立)。
                val bitmap = image.toBitmap()
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                val upright = if (rotation != 0) {
                    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }
                continuation.resume(upright)
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resume(null)
            }
        },
    )
}

package cn.anitabi.map.ui.home

import cn.anitabi.map.R
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.anitabi.map.app.AppGraph
import cn.anitabi.map.data.update.UpdateChecker
import cn.anitabi.map.support.AnitabiWebLink
import cn.anitabi.map.support.ExternalLinks
import cn.anitabi.map.theme.LocalAnitabiPalette
import cn.anitabi.map.ui.scene.cutout.CutoutEngine
import cn.anitabi.map.ui.scene.cutout.DeviceTier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.anitabi.map.ui.components.ForceLightStatusBarIcons
import cn.anitabi.map.ui.sheet.sheetBottomInset
import cn.anitabi.map.ui.work.ActionChip

/**
 * 与常驻 sheet 相互独立的模态弹层(iOS AboutSheet / EtiquetteSheet / WelcomeView 的初版)。
 *
 * 三个弹层的内容都是**固有高度**,横屏时会超过可用高度,所以都必须能滚 ——
 * (常驻 sheet 的内容由 `DetentSheet` 给定高度,这里要自己做。)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(versionName: String, onDismiss: () -> Unit) {
    val palette = LocalAnitabiPalette.current
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.opaqueSheetSurface,
        // 信息型弹层没有分档的意义,而横屏的半展开档只剩约 165dp,一开就得往上拖。
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = modalSheetMaxContentHeight())
                .modalSheetScroll(),
        ) {
            Text(stringResource(R.string.app_name), color = palette.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            // 版本 + 检查更新。状态与启动时的自动检查共用(UpdateChecker 只有一份)。
            val graph = remember { AppGraph.get(context) }
            val updateState by graph.updateChecker.state.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    versionName,
                    color = palette.inkTertiary, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.update_check),
                    color = palette.accentFill, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = updateState !is UpdateChecker.State.Checking) { graph.updateChecker.checkNow() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            UpdateStatusRow(
                state = updateState,
                onOpen = { url -> ExternalLinks.openInCustomTab(context, url) },
                onRetry = { graph.updateChecker.checkNow() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.about_license_body),
                color = palette.inkSecondary, fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionChip("CC BY-NC-SA 4.0") {
                    ExternalLinks.openInCustomTab(
                        context,
                        "https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans",
                    )
                }
                ActionChip(stringResource(R.string.pilgrimage_data_feedback)) {
                    ExternalLinks.openInCustomTab(context, AnitabiWebLink.FEEDBACK_ISSUES)
                }
            }
            Spacer(Modifier.height(14.dp))
            // 第三方开源组件声明(仓库 NOTICE 的应用内版本;条目名与许可证名为专有名词,不翻译)
            Text(
                stringResource(R.string.oss_licenses_title),
                color = palette.inkSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            OSS_COMPONENTS.forEach { (label, url) ->
                Text(
                    label,
                    color = palette.inkTertiary, fontSize = 12.sp, lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ExternalLinks.openInCustomTab(context, url) }
                        .padding(vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            // 自动检查更新(默认开;≤ 每 24 小时向 GitHub 查一次)
            var autoCheckOn by remember { mutableStateOf(graph.prefs.updateAutoCheckEnabled) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.update_auto_check_title),
                        color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.update_auto_check_detail),
                        color = palette.inkTertiary, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = autoCheckOn,
                    onCheckedChange = { on ->
                        autoCheckOn = on
                        graph.prefs.updateAutoCheckEnabled = on
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = palette.accentFill),
                )
            }
            Spacer(Modifier.height(12.dp))
            // 实验性 AI 抠图开关(默认关;开启后进入对比拍摄页才会下载模型/原生库)
            var isnetOn by remember { mutableStateOf(graph.prefs.isnetExperimentEnabled) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.isnet_experiment_title),
                        color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.isnet_experiment_detail),
                        color = palette.inkTertiary, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = isnetOn,
                    onCheckedChange = { on ->
                        isnetOn = on
                        graph.prefs.isnetExperimentEnabled = on
                        graph.cutoutEngine.userEnabled = on
                        // 打开即开始准备(此前要等进拍摄页才下载,开关打开后毫无反馈)。
                        if (on) graph.cutoutEngine.warmUp() else graph.cutoutEngine.reset()
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = palette.accentFill,
                    ),
                )
            }
            if (isnetOn) {
                val cutoutState by graph.cutoutEngine.state.collectAsStateWithLifecycle()
                IsnetStatusRow(
                    state = cutoutState,
                    onRetry = { graph.cutoutEngine.warmUp() },
                    onAllowMetered = { graph.cutoutEngine.downloadOnMeteredNetwork() },
                )
            }
            Spacer(Modifier.height(8.dp))
            // 因为还没有部署 assetlinks,Android 12+ 需要用户手动启用应用链接
            Text(
                stringResource(R.string.set_default_links),
                color = palette.inkTertiary, fontSize = 12.sp,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                },
            )
            Spacer(Modifier.height(24.dp + sheetBottomInset()))
        }
    }
}

/** About 页展示的第三方组件(名称 · 许可证 → 上游许可页)。与仓库 NOTICE 保持一致。 */
private val OSS_COMPONENTS = listOf(
    "ISNet-Anime (anime-segmentation) · Apache-2.0" to
        "https://github.com/SkyTNT/anime-segmentation/blob/main/LICENSE",
    "ONNX Runtime · MIT" to
        "https://github.com/microsoft/onnxruntime/blob/main/LICENSE",
    "Google ML Kit · ML Kit Terms" to
        "https://developers.google.com/ml-kit/terms",
    "Google Maps SDK for Android · Google Maps Platform Terms" to
        "https://cloud.google.com/maps-platform/terms",
    // 搜索用的两张生成表随 APK 分发:Unicode License 要求副本附带声明,这里就是那份声明的入口。
    "OpenCC (汉字异体折叠表) · Apache-2.0" to
        "https://github.com/BYVoid/OpenCC/blob/master/LICENSE",
    "Unicode Unihan (拼音表) · Unicode License v3" to
        "https://www.unicode.org/license.txt",
    "Jetpack Compose / AndroidX · Apache-2.0" to
        "https://developer.android.com/license",
    "Coil · Apache-2.0" to
        "https://github.com/coil-kt/coil/blob/main/LICENSE.txt",
    "OkHttp · Apache-2.0" to
        "https://github.com/square/okhttp/blob/master/LICENSE.txt",
    "maps-compose · Apache-2.0" to
        "https://github.com/googlemaps/android-maps-compose/blob/main/LICENSE",
)

/**
 * 模态弹层里可滚内容的高度上限:屏高的 85%。
 *
 * 内容一旦长到接近整屏(小屏 + ISNet 状态行 + 更新行的关于页),M3 的 `ModalBottomSheet` 会在两个测量高度之间
 * 来回跳 —— 真机录屏是 sheet 整体上下抖 ~40px 的闪烁(120Hz 的 S25 上肉眼可见)。
 * 把内容高度封在整屏之下,sheet 的锚点就稳定了,内容靠自身的 verticalScroll 滚。
 */
@Composable
private fun modalSheetMaxContentHeight(): Dp =
    (LocalConfiguration.current.screenHeightDp * 0.85f).dp

/**
 * 模态弹层里的可滚内容:**不带过度滚动效果**,并把滚到底之后继续向上的余量吃掉。
 *
 * 真机(S25,120Hz)录屏:内容滚到底后手指继续上推,画面以 ~150ms 为周期上下抖 —— 内层
 * `verticalScroll` 的拉伸过度滚动与 M3 sheet 自己的过度滚动叠在一起互相拉扯。内层不做过度滚动、
 * 向上的余量也不再交给 sheet(sheet 已在最高档,给它只会触发它的过度滚动),向下的余量照常交出去
 *(那是「拉到顶再拉就收起」的正常路径)。
 */
@Composable
private fun Modifier.modalSheetScroll(): Modifier {
    val swallowUpwardLeftover = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f) Offset(0f, available.y) else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
        }
    }
    return this
        .nestedScroll(swallowUpwardLeftover)
        .verticalScroll(rememberScrollState(), overscrollEffect = null)
        .padding(horizontal = 24.dp, vertical = 16.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtiquetteSheet(onDismiss: () -> Unit) {
    val palette = LocalAnitabiPalette.current
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.opaqueSheetSurface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = modalSheetMaxContentHeight())
                .modalSheetScroll(),
        ) {
            Text(stringResource(R.string.pilgrimage_etiquette), color = palette.warnInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.etiquette_body),
                color = palette.inkSecondary, fontSize = 14.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(14.dp))
            ActionChip(stringResource(R.string.view_etiquette_guide)) {
                ExternalLinks.openInCustomTab(context, AnitabiWebLink.ETIQUETTE_ISSUE)
            }
            Spacer(Modifier.height(24.dp + sheetBottomInset()))
        }
    }
}

/** 首启欢迎页(iOS WelcomeView 的初版)。全屏遮罩 + 大卡。 */
@Composable
fun WelcomeOverlay(onStart: () -> Unit) {
    val palette = LocalAnitabiPalette.current
    val context = LocalContext.current
    // 黑色遮罩铺到状态栏后面 → 图标转浅色(离场自动恢复)。
    ForceLightStatusBarIcons()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            // 遮罩必须参与命中测试:否则首启引导期间点暗区会穿透到下面的地图/sheet。
            .pointerInput(Unit) { detectTapGestures { } }
            // 顺序要紧:遮罩先铺满全屏,再把**内容**从系统栏/刘海里推出来。
            // 原先只有 navigationBarsPadding(),横屏时状态栏与刘海会压住卡片顶部。
            .safeDrawingPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                // widthIn 必须在 fillMaxWidth **之前**:约束自外向内传递,
                // 反过来写的话 fillMaxWidth 已经把宽度定死成父级最大值,上限就没人理了。
                .widthIn(max = 420.dp) // 横屏不拉成整条
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(palette.opaqueSheetSurface)
                .padding(24.dp),
        ) {
            // 只有说明区滚动,「开始使用」常驻底部 ——
            // 卡片是 BottomCenter 对齐的,整卡滚动会把按钮推到屏幕外。
            // fill = false:内容不足时按内容收缩,不足以撑开时也不会强行占满。
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.app_welcome_title),
                    color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                WelcomeFeature("🗺", stringResource(R.string.feature_map_title), stringResource(R.string.feature_map_detail))
                WelcomeFeature("🔍", stringResource(R.string.feature_search_title), stringResource(R.string.feature_search_detail))
                WelcomeFeature("🤝", stringResource(R.string.feature_community_title), stringResource(R.string.feature_community_detail))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.welcome_cc_note),
                    color = palette.inkTertiary, fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        ExternalLinks.openInCustomTab(
                            context,
                            "https://creativecommons.org/licenses/by-nc-sa/4.0/deed.zh-hans",
                        )
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.get_started),
                color = palette.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.accentFill)
                    .clickable(onClick = onStart)
                    .padding(vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun WelcomeFeature(emoji: String, title: String, detail: String) {
    val palette = LocalAnitabiPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 22.sp, modifier = Modifier.size(36.dp))
        Column {
            Text(title, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = palette.inkTertiary, fontSize = 12.sp)
        }
    }
}

/**
 * ISNet 模型下载的状态行(About 页开关下方)。每个状态都有可见反馈;
 * 等待 Wi-Fi 与失败两态附带动作按钮。
 */
@Composable
private fun IsnetStatusRow(
    state: CutoutEngine.State,
    onRetry: () -> Unit,
    onAllowMetered: () -> Unit,
) {
    val palette = LocalAnitabiPalette.current
    Spacer(Modifier.height(6.dp))
    when (state) {
        is CutoutEngine.State.Downloading -> {
            val mb = { bytes: Long -> bytes / 1_048_576.0 }
            Text(
                stringResource(
                    R.string.isnet_status_downloading,
                    (state.progress * 100).toInt(),
                    mb(state.downloadedBytes),
                    mb(state.totalBytes),
                ),
                color = palette.inkSecondary, fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = palette.accentFill,
                trackColor = palette.track,
            )
        }
        is CutoutEngine.State.Ready -> Text(
            stringResource(
                R.string.isnet_status_ready,
                if (state.tier is DeviceTier.Htp) "NPU" else "CPU",
            ),
            color = palette.inkSecondary, fontSize = 12.sp,
        )
        CutoutEngine.State.WaitingForUnmetered -> StatusWithAction(
            text = stringResource(R.string.isnet_status_waiting_wifi),
            action = stringResource(R.string.isnet_action_use_mobile_data),
            onAction = onAllowMetered,
        )
        is CutoutEngine.State.Failed -> StatusWithAction(
            text = stringResource(R.string.isnet_status_failed, state.reason),
            action = stringResource(R.string.retry),
            onAction = onRetry,
        )
        CutoutEngine.State.Unavailable -> Text(
            stringResource(R.string.isnet_status_unavailable),
            color = palette.inkTertiary, fontSize = 12.sp,
        )
        CutoutEngine.State.Idle -> Text(
            stringResource(R.string.isnet_status_preparing),
            color = palette.inkTertiary, fontSize = 12.sp,
        )
    }
}

/** 检查更新的状态行(版本号下方)。Idle 不占位;其余每态都有可见反馈。 */
@Composable
private fun UpdateStatusRow(
    state: UpdateChecker.State,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val palette = LocalAnitabiPalette.current
    when (state) {
        UpdateChecker.State.Idle -> Unit
        UpdateChecker.State.Checking -> Text(
            stringResource(R.string.update_checking),
            color = palette.inkTertiary, fontSize = 12.sp,
        )
        UpdateChecker.State.UpToDate -> Text(
            stringResource(R.string.update_up_to_date),
            color = palette.inkTertiary, fontSize = 12.sp,
        )
        is UpdateChecker.State.Available -> StatusWithAction(
            text = stringResource(R.string.update_available, state.release.tag),
            action = stringResource(R.string.update_open_releases),
            onAction = { onOpen(state.release.htmlUrl) },
        )
        is UpdateChecker.State.Failed -> StatusWithAction(
            text = if (state.rateLimited) {
                stringResource(R.string.update_rate_limited)
            } else {
                stringResource(R.string.update_failed, state.reason)
            },
            action = stringResource(R.string.retry),
            onAction = onRetry,
        )
    }
}

@Composable
private fun StatusWithAction(text: String, action: String, onAction: () -> Unit) {
    val palette = LocalAnitabiPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = palette.inkSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            action,
            color = palette.accentFill, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

package cn.anitabi.map.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.anitabi.map.theme.LocalAnitabiPalette
import kotlinx.coroutines.delay

/**
 * 屏幕顶部的临时通知（iOS ToastCenter 的移植）。2 秒后自动消失，连续触发会重置计时器。
 * 系统 Toast 观感不合，因此用 Compose 自绘。
 */
class ToastCenter {
    /**
     * 最后一条消息文本。dismiss 时**不清空**:退场动画期间仍要渲染文本,
     * 由 [visible] 决定显隐 —— 这样 ToastOverlay 是纯读取者,
     * 不需要在组合期回写「上一条消息」(禁组合期快照写)。
     */
    var message: String by mutableStateOf("")
        private set

    var visible: Boolean by mutableStateOf(false)
        private set

    /** 用于在连续触发时重置计时器的世代号。 */
    var generation by mutableIntStateOf(0)
        private set

    fun show(message: String) {
        this.message = message
        visible = true
        generation += 1
    }

    fun dismiss() {
        visible = false
    }
}

@Composable
fun ToastOverlay(center: ToastCenter, modifier: Modifier = Modifier) {
    val palette = LocalAnitabiPalette.current

    LaunchedEffect(center.generation) {
        if (!center.visible) return@LaunchedEffect
        delay(2000)
        center.dismiss()
    }

    AnimatedVisibility(
        visible = center.visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        Text(
            text = center.message,
            color = palette.ink,
            fontSize = 13.sp,
            modifier = Modifier
                .background(palette.cardGlass.copy(alpha = 0.95f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

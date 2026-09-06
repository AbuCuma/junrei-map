package cn.anitabi.map.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.anitabi.map.support.MapDeepLink
import cn.anitabi.map.theme.AnitabiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // savedInstanceState != null ⇒ 系统在重建一个被回收掉的实例,不是用户新点了链接。
        handleDeepLink(intent, allowPersistedFallback = true, isRecreate = savedInstanceState != null)
        setContent {
            AnitabiTheme {
                RootScreen()
            }
        }
    }

    // launchMode=singleTask:运行中再入(deep link、桌面/最近任务)走这里。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 平台契约:singleTask 必须 setIntent,否则 getIntent() 永远是首次启动的 intent。
        setIntent(intent)
        // 再入不回放持久化状态:从最近任务回来的 ACTION_MAIN(data=null)若走持久化兜底,
        // 会把旧相机位重新注入 pendingDeepLink,偶发「地图自己跳了/卡片被重置」。
        handleDeepLink(intent, allowPersistedFallback = false, isRecreate = false)
    }

    /**
     * @param isRecreate 本次 onCreate 是系统重建(进程被回收后从最近任务返回),
     *   而不是一次真正的新启动。此时 **`intent` 里的深链是任务记录里那条旧的**,
     *   重放它会把用户扔回当初分享链接的点位,而不是他离开时的位置 ——
     *   离开时的位置已经由 `RootScreen` 在 ON_STOP 写进了 `prefs.restoredDeepLink`。
     *
     * 判据用 `savedInstanceState != null` 是**实测**定下来的:
     * 在 Android 16 上,进程被杀后从最近任务返回时,`intent.flags` 与首次深链启动**完全相同**
     * (都是 `0x10000000`),`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` 从未出现;唯一有区别的
     * 就是 savedInstanceState。同一次实测还发现:任务仍在、进程已死时收到的新深链,
     * 系统压根不会投递新 intent(`START_TASK_TO_FRONT`,`getIntent()` 仍是旧的那条),
     * 所以这两种情形下"忽略 intent 改用持久化状态"都是更贴近用户意图的选择。
     */
    private fun handleDeepLink(intent: Intent?, allowPersistedFallback: Boolean, isRecreate: Boolean) {
        val graph = AppGraph.get(this)
        // 只有「确实有持久化状态可回放」时才压制 intent 里的深链。
        // savedInstanceState != null 也涵盖 configChanges 没吸收的重建(语言切换、折叠/分屏),
        // 那些情形下若还没写过 restoredDeepLink,压制就等于把用户点的链接直接丢掉。
        val suppressIntentUri = isRecreate && graph.prefs.restoredDeepLink != null
        val uri = if (suppressIntentUri) null else intent?.data?.toString()
        if (uri != null) {
            MapDeepLink.parse(uri)?.let { link ->
                graph.pendingDeepLink.value = link
                return
            }
        }
        // 仅冷启动且无 intent 数据时,回放持久化状态
        // (优先级:深链 > 持久化 > 默认日本俯瞰 — 对应 iOS resolveInitialState)。
        if (allowPersistedFallback && graph.pendingDeepLink.value == null) {
            graph.prefs.restoredDeepLink?.let { persisted ->
                MapDeepLink.parse(persisted)?.let { graph.pendingDeepLink.value = it }
            }
        }
    }
}

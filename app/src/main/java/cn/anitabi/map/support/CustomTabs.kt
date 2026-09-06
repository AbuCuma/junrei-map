package cn.anitabi.map.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * 外部链接的打开入口（iOS SafariSheet / openExternally 的移植）。
 * - 贡献流程、GitHub 等页面 → Custom Tabs（与 Chrome 共享 Cookie＝登录态相通）。
 * - Google 地图／街景等想交给应用处理的链接 → 普通 ACTION_VIEW
 *   （从 Custom Tabs 内部有时无法跳转到应用）。
 */
object ExternalLinks {

    /**
     * 用 Custom Tabs 打开页面。不支持 CCT 的环境回落到普通浏览器 Intent。
     *
     * 必须把 CCT 固定到浏览器包上:launchUrl 底层就是 ACTION_VIEW,若用户在系统
     * 设置里把 anitabi.cn 链接默认交给本 App 处理,「在网页版打开」会被链接解析
     * 劫持回 App 自身(真机反馈)。CustomTabsClient.getPackageName 需要 manifest
     * 的 <queries> 声明(https VIEW)才能在 API 30+ 看到浏览器。
     */
    fun openInCustomTab(context: Context, url: String) {
        val uri = url.toUri()
        try {
            val intent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            CustomTabsClient.getPackageName(context, null)?.let { intent.intent.setPackage(it) }
            intent.launchUrl(context, uri)
            return
        } catch (_: ActivityNotFoundException) {
            // 继续走下方兜底
        }
        // 没有 CCT 浏览器:对我们自己声明的 host 直启默认浏览器(普通 VIEW 会解析回
        // App 自身)。注意不能回落到 openExternally —— 它的防御分支会再转回本函数。
        if (uri.host in MapDeepLink.ALLOWED_HOSTS) {
            try {
                val browser = Intent.makeMainSelectorActivity(
                    Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER
                ).setData(uri)
                context.startActivity(browser)
            } catch (_: ActivityNotFoundException) {
                // 设备连浏览器都没有:放弃(普通 VIEW 只会回到 App 自身,无意义)
            }
            return
        }
        openExternally(context, url)
    }

    /** 想交给应用（Google 地图等）处理的链接。 */
    fun openExternally(context: Context, url: String) {
        val uri = url.toUri()
        // 防御:自家声明的 /map 链接不该从这里走(会解析回 App 自身),改走 CCT 入口。
        if (uri.host in MapDeepLink.ALLOWED_HOSTS && uri.path == "/map") {
            openInCustomTab(context, url)
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // 没有可打开的目标就什么都不做（给调用侧留出弹 toast 的余地）
        }
    }
}

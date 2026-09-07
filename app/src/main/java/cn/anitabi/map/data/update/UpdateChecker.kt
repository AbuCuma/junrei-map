package cn.anitabi.map.data.update

import cn.anitabi.map.data.AnitabiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * 向 GitHub Releases 查有没有更新的版本。**只检查、只提示**:下载与安装交给浏览器
 * (同签名覆盖安装),App 不申请安装权限、不碰 APK。
 *
 * - 启动时 [checkIfDue] 最多每 24 小时发一次(匿名 API 60 次/小时/IP,远够);关于页 [checkNow] 不受限。
 * - 两条入口共用一个 [state] 与一个在飞 Job,关于页打开时看到的就是启动那次的进度。
 * - 失败([State.Failed],含 403 限流)**不写时间戳**,下次启动再试;也不清缓存 —— 上次查到的新版本仍然显示。
 * - 「忽略此版本」只是首页不再出卡片,关于页照常告诉用户有新版本。
 *
 * 不持有 Context,不碰 Compose 与字符串资源:失败原因是给关于页看的短英文/状态码,由 UI 决定怎么措辞。
 */
class UpdateChecker(
    client: OkHttpClient,
    private val prefs: AnitabiPrefs,
    installedVersionName: String,
    private val apiUrl: String = ReleaseInfo.LATEST_API_URL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        /** [skipped]:用户点过「忽略此版本」—— 首页不出卡片,关于页仍显示。 */
        data class Available(val release: ReleaseInfo, val skipped: Boolean) : State
        /** [reason] 是给关于页的短说明;[rateLimited] 时 UI 可换成「GitHub 限流」的文案。 */
        data class Failed(val reason: String, val rateLimited: Boolean = false) : State
    }

    private val client = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installed: AppVersion? = AppVersion.parse(installedVersionName)
    private val _state = MutableStateFlow<State>(seedFromCache())
    val state: StateFlow<State> = _state
    private var checkJob: Job? = null

    /** 上次查到的结果先摆出来:24 小时内重启不会再查,没有这一步卡片就要等到明天。 */
    private fun seedFromCache(): State {
        val tag = prefs.updateLatestTag ?: return State.Idle
        val cached = ReleaseInfo(
            tag = tag,
            version = AppVersion.parse(tag) ?: return State.Idle,
            htmlUrl = prefs.updateLatestUrl ?: ReleaseInfo.RELEASES_URL,
            name = null,
            prerelease = false,
        )
        return evaluate(cached) ?: State.Idle
    }

    /** 与本机版本比较;不比本机新(含本机版本号解析失败、预发布)→ [State.UpToDate]。 */
    private fun evaluate(release: ReleaseInfo): State? {
        val local = installed ?: return State.UpToDate
        if (release.prerelease || release.version <= local) return State.UpToDate
        return State.Available(release, skipped = release.tag == prefs.updateSkippedTag)
    }

    /** 启动时调用:距上次成功检查不足 24 小时就什么也不做。时钟被拨回去也算到期(用绝对差)。 */
    fun checkIfDue() {
        val last = prefs.updateLastCheckedAt
        if (last != 0L && abs(clock() - last) < CHECK_INTERVAL_MS) return
        checkNow()
    }

    fun checkNow() {
        if (checkJob?.isActive == true) return
        // 同步进入 Checking:点「检查更新」立刻有反馈,也让调用方能可靠地等它结束。
        _state.value = State.Checking
        checkJob = scope.launch {
            try {
                _state.value = fetch()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun skip(release: ReleaseInfo) {
        prefs.updateSkippedTag = release.tag
        val current = _state.value
        if (current is State.Available && current.release.tag == release.tag) {
            _state.value = current.copy(skipped = true)
        }
    }

    private fun fetch(): State {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 403 / 429:匿名配额用完。按失败处理,绝不当成「已是最新」。
                    val limited = response.code == 403 || response.code == 429
                    return State.Failed("HTTP ${response.code}", rateLimited = limited)
                }
                response.body.string()
            }
        } catch (e: IOException) {
            // 给关于页看的短原因(异常类名):完整 message 是整句 DNS/超时说明,一行放不下也没人需要。
            return State.Failed(e.javaClass.simpleName)
        }
        val release = ReleaseInfo.parse(body) ?: return State.Failed("unexpected response")
        prefs.updateLastCheckedAt = clock()
        prefs.updateLatestTag = release.tag
        prefs.updateLatestUrl = release.htmlUrl
        return evaluate(release) ?: State.UpToDate
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

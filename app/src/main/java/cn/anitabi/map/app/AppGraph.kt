package cn.anitabi.map.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import cn.anitabi.map.R
import cn.anitabi.map.data.AnitabiDataLoader
import cn.anitabi.map.data.AnitabiPrefs
import cn.anitabi.map.data.AnitabiStore
import cn.anitabi.map.data.LocationProvider
import cn.anitabi.map.data.PilgrimageLog
import cn.anitabi.map.data.update.UpdateChecker
import cn.anitabi.map.data.model.ScenePoint
import cn.anitabi.map.support.ImageHostFallbackInterceptor
import cn.anitabi.map.support.MapDeepLink
import cn.anitabi.map.ui.scene.cutout.CutoutEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 手写 DI 容器（不用 Hilt/Koin —— 与 iOS 版零依赖精神一致的移植决策）。
 * 单一 Activity + 个位数的单例，这样就足够了。
 */
class AppGraph(context: Context) {

    /** 全量数据与图标雪碧图共用的存放位置（相当于 iOS Caches/anitabi-data）。 */
    val dataCacheDir: File = File(context.cacheDir, "anitabi-data")

    /**
     * 全 App 唯一的 HTTP client(dataLoader/Coil/marker/雪碧图/cutout 都复用它)。
     * 带标识性 User-Agent:官方文档仓库 issue #86 的策略是「正确配置 UA + 人类
     * 频率很难超限」,okhttp 默认 UA 属匿名客户端,更易被 Cloudflare 标记。
     */
    /** 安装的 versionName(UA、关于页、检查更新共用;BuildConfig 已关,只能问 PackageManager)。 */
    val versionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    val okHttpClient: OkHttpClient = run {
        val userAgent = "AnitabiMap-Android/$versionName (Android ${android.os.Build.VERSION.SDK_INT})"
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .addInterceptor(ImageHostFallbackInterceptor())
            .build()
    }

    val prefs: AnitabiPrefs = SharedPrefsAnitabiPrefs(
        context.getSharedPreferences("anitabi", Context.MODE_PRIVATE)
    )

    val dataLoader: AnitabiDataLoader = AnitabiDataLoader(
        cacheDir = dataCacheDir,
        client = okHttpClient,
    )

    val store: AnitabiStore = AnitabiStore(loader = dataLoader, prefs = prefs)

    /** 检查更新(GitHub Releases)。懒建:关于页与启动检查都经它,共享同一份状态。 */
    val updateChecker: UpdateChecker by lazy { UpdateChecker(okHttpClient, prefs, versionName) }

    /** 巡礼记录（用户标为「已完成」的地标）。`RootScreen` 启动时 load,之后只经 [toggleVisited] 改。 */
    val pilgrimageLog: PilgrimageLog = PilgrimageLog(File(context.filesDir, PilgrimageLog.FILE_NAME))

    /**
     * 切换一个地标的完成状态并落盘。落盘挂在 [appScope] 上 —— 用户已经按下,关掉卡片也得写完
     * (见 appScope 的 KDoc,这正是它存在的理由)。
     */
    fun toggleVisited(point: ScenePoint): Boolean {
        val visited = pilgrimageLog.toggle(point)
        appScope.launch { pilgrimageLog.save() }
        return visited
    }

    val router = AppRouter()

    val locationProvider = LocationProvider(context.applicationContext)

    /** 抠图引擎（ISNet-Anime → ML Kit 的失败回退链）。manifest URL 经 resValue 从 local.properties 注入。 */
    val cutoutEngine: CutoutEngine by lazy {
        CutoutEngine(
            context = context.applicationContext,
            client = okHttpClient,
            manifestUrl = context.getString(R.string.cutout_manifest_url),
        ).also { it.userEnabled = prefs.isnetExperimentEnabled }
    }

    /** 未消费的深链（MainActivity 写入，地图消费后置回 null）。 */
    val pendingDeepLink: MutableState<MapDeepLink?> = mutableStateOf(null)

    /**
     * **只给「用户已经按下、必须做完」的动作用**(目前:保存到相册)。
     *
     * 这类动作不能挂在 `rememberCoroutineScope()` 上:点了保存再立刻关掉查看器,
     * 下载/编码会被中途取消,既没有文件也没有任何提示。
     * 进程级作用域是这里的正解,但**不要**把它当成通用的"随手 launch"入口 ——
     * 与界面共存亡的工作仍然应该用界面自己的作用域。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 相当于 UserDefaults。键名与 iOS 版对齐（如 home.dataModified）。
 * 因为需要同步读写（apply() 时即可恢复），所以用 SharedPreferences 而非 DataStore。
 */
private class SharedPrefsAnitabiPrefs(private val sp: SharedPreferences) : AnitabiPrefs {

    override var cachedModified: Double
        get() = Double.fromBits(sp.getLong("home.dataModified", 0.0.toRawBits()))
        set(value) {
            sp.edit().putLong("home.dataModified", value.toRawBits()).apply()
        }

    override var lastVisitedBangumiId: Int
        get() = sp.getInt("home.lastVisitedBangumiID", 0)
        set(value) {
            sp.edit().putInt("home.lastVisitedBangumiID", value).apply()
        }

    override var recentVisitIds: List<Int>
        get() = sp.getString("home.recentVisitBangumiIDs", null)
            ?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        set(value) {
            sp.edit().putString("home.recentVisitBangumiIDs", value.joinToString(",")).apply()
        }

    override var mapBaseStyle: String
        get() = sp.getString("map.baseStyle", "standard") ?: "standard"
        set(value) {
            sp.edit().putString("map.baseStyle", value).apply()
        }

    override var isPhotoLayerVisible: Boolean
        get() = sp.getBoolean("map.isPhotoLayerVisible", true)
        set(value) {
            sp.edit().putBoolean("map.isPhotoLayerVisible", value).apply()
        }

    override var pointVisitFilter: String
        get() = sp.getString("map.pointVisitFilter", "All") ?: "All"
        set(value) {
            sp.edit().putString("map.pointVisitFilter", value).apply()
        }

    override var restoredDeepLink: String?
        get() = sp.getString("map.restoredDeepLink", null)
        set(value) {
            sp.edit().putString("map.restoredDeepLink", value).apply()
        }

    override var hasCompletedOnboarding: Boolean
        get() = sp.getBoolean("hasCompletedOnboarding", false)
        set(value) {
            sp.edit().putBoolean("hasCompletedOnboarding", value).apply()
        }

    override var isnetExperimentEnabled: Boolean
        get() = sp.getBoolean("cutout.isnetExperimentEnabled", false)
        set(value) {
            sp.edit().putBoolean("cutout.isnetExperimentEnabled", value).apply()
        }

    override var updateAutoCheckEnabled: Boolean
        get() = sp.getBoolean("update.autoCheckEnabled", true)
        set(value) {
            sp.edit().putBoolean("update.autoCheckEnabled", value).apply()
        }

    override var updateLastCheckedAt: Long
        get() = sp.getLong("update.lastCheckedAt", 0L)
        set(value) {
            sp.edit().putLong("update.lastCheckedAt", value).apply()
        }

    override var updateLatestTag: String?
        get() = sp.getString("update.latestTag", null)
        set(value) {
            sp.edit().putString("update.latestTag", value).apply()
        }

    override var updateLatestUrl: String?
        get() = sp.getString("update.latestUrl", null)
        set(value) {
            sp.edit().putString("update.latestUrl", value).apply()
        }

    override var updateSkippedTag: String?
        get() = sp.getString("update.skippedTag", null)
        set(value) {
            sp.edit().putString("update.skippedTag", value).apply()
        }
}

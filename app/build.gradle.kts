import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// 本机私有配置:local.properties(不进仓库)。缺省时一律降级而不是报错,别人 clone 下来就能 assembleDebug。
val localProps = Properties().apply {
    val lp = rootProject.file("local.properties")
    if (lp.exists()) lp.inputStream().use { load(it) }
}

/** local.properties 优先,其次环境变量(CI 用)。 */
fun secret(name: String): String? =
    localProps.getProperty(name)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "cn.anitabi.map"
    compileSdk = 37

    // Release 签名。只在 GitHub Release 分发,这把 key 就是最终签名。
    // 四个值放 local.properties(或同名环境变量):
    //   RELEASE_STORE_FILE=~/.junreimap_release.jks  RELEASE_STORE_PASSWORD=…
    //   RELEASE_KEY_ALIAS=junreimap                  RELEASE_KEY_PASSWORD=…
    // 缺任何一个则 release 不签名(产物是 app-release-unsigned.apk),不阻断别人的 assembleRelease。
    val releaseStoreFile = secret("RELEASE_STORE_FILE")
        ?.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))
        ?.let(::File)
        ?.takeIf { it.isFile }
    val releaseSigning = if (releaseStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = releaseStoreFile
            storePassword = secret("RELEASE_STORE_PASSWORD")
            keyAlias = secret("RELEASE_KEY_ALIAS")
            keyPassword = secret("RELEASE_KEY_PASSWORD")
        }
    } else {
        null
    }

    defaultConfig {
        // 第三方客户端,不占用社区的 cn.anitabi 域;按 GitHub 托管项目的反写惯例命名。
        // namespace(Kotlin 包名)仍是 cn.anitabi.map —— 迁移包名是纯搬家、无收益,
        // 两者不一致是 AGP 明确支持的。发布之后 applicationId 不可再改(改了就是另一个应用)。
        applicationId = "io.github.abucuma.junreimap"
        // 29(Android 10)起点:MediaStore 保存无需 WRITE_EXTERNAL_STORAGE,
        // 消除了 26-28 上「保存必失败」的整类问题。
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Maps API key 从 local.properties 的 MAPS_API_KEY 注入(绝不写进仓库)。
        manifestPlaceholders["MAPS_API_KEY"] = secret("MAPS_API_KEY") ?: ""
        // ISNet-Anime 抠图运行时(ORT 本体 + 模型)的下发 manifest。
        // 缺省用官方托管的 CPU 档(见 docs/cutout-runtime.md);local.properties 填了就覆盖它,
        // 本地调试用 adb reverse 起 http 服务器时走的就是覆盖这条路。
        // 注意这个默认值意味着:用户在「关于」页打开实验开关后,下载模型时 IP 会流向 GitHub
        // (开关默认关闭,不打开则永远不会发生)—— README 的隐私段已写明。
        val cutoutManifestUrl = secret("CUTOUT_MANIFEST_URL").orEmpty().ifBlank {
            "https://github.com/AbuCuma/anitabi-cutout-runtime/releases/download/v1/manifest.json"
        }
        resValue("string", "cutout_manifest_url", cutoutManifestUrl)
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            // 资源收缩保持开启:未被引用的资源由 R8 从产物里剔除。
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        resValues = true // cutout_manifest_url(local.properties → 字符串资源)
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // 显式写出 CI 依赖的契约:error 必须阻断构建,warning 不升级为 error。
        abortOnError = true
        warningsAsErrors = false
    }
}

androidComponents {
    onVariants { variant ->
        // 产物 APK 里不打包 ORT 本体(改为运行时下载 + 校验)。androidTest 组件是另一套 packaging,不受影响。
        variant.packaging.jniLibs.excludes.add("**/libonnxruntime.so")
    }
}

kotlin {
    jvmToolchain(17)
}

composeCompiler {
    // 稳定性/可跳过性诊断:build/compose_reports 下的 *-composables.txt 用于核实
    // 热路径组件(MapControlStack/HomeSheet 等)是否 skippable。
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 常驻 sheet 的内容栈(Home → 作品卡 → 地标卡)。AppRouter 手写的层栈正是它解决的问题:
    // back stack 即事实源,NavDisplay 负责呈现与预测式返回。体积 <300KB。
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // 地图(Google Maps SDK。maps-compose 只做容器/相机胶水,marker 由命令式代码管理)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)

    // 定位(粗精度 + 50m 节流)
    implementation(libs.play.services.location)

    // 抠图(unbundled,模型由 GMS 下发)。ISNet-Anime 不可用或未下载时的回退路径。
    implementation(libs.play.services.mlkit.subject.segmentation)
    // ISNet-Anime 推理:ORT(内含 QNN EP 的构建)。打进 APK 的只有 83KB 的 JNI 垫片与 Java 类 ——
    // libonnxruntime.so(24MB)与 Qualcomm QNN 库一律从 filesDir 用 System.load 加载
    //(上面的 androidComponents 已把它从 APK 排除;qnn-runtime 那 67MB 的传递依赖也一并排除)。
    implementation(libs.onnxruntime.android.qnn) {
        exclude(group = "com.qualcomm.qti")
    }

    // 对比拍摄用的相机
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // 图片管线
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    // 相当于 iOS SafariSheet(Custom Tabs)
    implementation(libs.androidx.browser)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // data/ 走 JVM 单测(org.json 在 Android 运行时自带,测试里用同 API 的公开版)
    testImplementation(libs.json)
    testImplementation(libs.mockwebserver)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // ISNet-Anime 的真机基准工具(仅 androidTest,不会进产物 APK)
    androidTestImplementation(libs.onnxruntime.android.qnn)
    androidTestImplementation(libs.qnn.runtime)
}

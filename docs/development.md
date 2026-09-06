# 开发环境与构建

面向想自己编译、调试或贡献代码的人。只想使用 App 的话看 [README](../README.md) 就够了。

## 环境要求

| 项 | 版本 | 依据 |
|---|---|---|
| JDK | 17 | `app/build.gradle.kts` 的 `jvmToolchain(17)` |
| Android Gradle Plugin | 9.3.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.3.20(Compose 编译器随 Kotlin 版本) | 同上 |
| Gradle | 9.5.0(用仓库自带 wrapper,发行包 SHA-256 已固定) | `gradle/wrapper/gradle-wrapper.properties` |
| Android SDK | compileSdk 37 · targetSdk 36 · minSdk 29 | `app/build.gradle.kts` |
| Android Studio | 能打开 AGP 9.3 的版本(以 [AGP 与 Studio 兼容表](https://developer.android.com/build/releases/gradle-plugin#compatibility) 为准) | —— |

外部服务:

- **Google Maps SDK for Android** —— 必需,需要自备 API key(不填也能编译运行,只是地图空白)
- **Google Play 服务** —— 定位与 ML Kit 抠图模型下发依赖它
- **anitabi.cn 的数据 CDN** —— 巡礼数据来源,见 [data-sources.md](data-sources.md)
- **抠图运行时下载服务器** —— 可选,缺省指向官方托管的 CPU 档,见 [cutout-runtime.md](cutout-runtime.md)

## 配置

配置只有一个文件:`local.properties`(**不提交**,已被 `.gitignore` 排除)。

```bash
cp local.properties.example local.properties
# 填 sdk.dir(Android Studio 通常会自动生成)和 MAPS_API_KEY
```

| 键 | 必填 | 作用 |
|---|---|---|
| `sdk.dir` | 是 | Android SDK 路径 |
| `MAPS_API_KEY` | 建议 | Google Maps key。不填地图是空白的,其余功能仍可用。**务必在 Cloud Console 加「Android 应用」限制(包名 + SHA-1)与 Maps SDK API 限制** —— key 必然会打进 APK,限制才是真正的防线。包名是 `io.github.abucuma.junreimap`,debug 与 release 各自的 SHA-1 都要加 |
| `CUTOUT_MANIFEST_URL` | 否 | AI 抠图运行时的下载清单 URL。留空 = 用官方托管的 CPU 档 manifest;想自建服务器或本地调试时才填 |
| `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` | 否(仅维护者) | release 签名。四个齐全且 keystore 文件存在才签名,否则 `assembleRelease` 产出 `app-release-unsigned.apk`。也接受同名环境变量。应用只在 GitHub Release 分发,**这把 keystore 就是应用的永久身份**,务必多处备份 |

取签名 SHA-1(填进 Maps key 的限制):

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1   # debug
keytool -list -v -keystore <你的 release keystore> -alias <alias> | grep SHA1                              # release
```

## 构建与运行

```bash
./gradlew assembleDebug                 # debug APK → app/build/outputs/apk/debug/
./gradlew installDebug                  # 装到已连接设备
./gradlew assembleRelease               # release(R8 + 资源收缩;有 RELEASE_* 配置时签名,否则未签名)
```

macOS 上若默认 JDK 不是 17:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

## 测试与静态检查

```bash
./gradlew :app:testDebugUnitTest        # JVM 单测(当前 248 个,3 个按环境跳过)
./gradlew :app:lintDebug                # Android Lint(0 error 是门禁,abortOnError = true)
./gradlew :app:testDebugUnitTest --tests "*MapEngineTest*"   # 只跑一个类
```

关于测试目录,有几条**必须知道**的事实:

- `app/src/test/` 是真正的 JVM 单测,覆盖地图引擎、搜索、JSON 容错、网络故障转移、巡礼记录、
  导航状态机、抠图下载校验链等。`RealDataSnapshotTest` 与 `SearchBenchmarkTest` 需要环境变量
  `ANITABI_SNAPSHOT_DIR` 指向真实数据快照,否则自动跳过 —— 外部贡献者看到它们被跳过是正常的。
- `app/src/androidTest/` 里只有 `DotRenderFidelityTest` 是真正的仪器测试(圆点分趟绘制的逐像素比对,
  需要真 Skia,连上设备后 `./gradlew :app:connectedDebugAndroidTest --tests "*DotRenderFidelityTest*"`)。
  其余五个 `*Harness` 文件**不是测试**:它们是需要在设备上预置文件的基准/调试工具,没有断言,
  已标 `@Ignore`。**不要把 `connectedCheck` 当作整体验证手段**。
- 没有 ktlint / detekt / spotless;风格靠 [`.editorconfig`](../.editorconfig)(4 空格、120 列、LF)。

界面文案全部**手写**:`strings_app.xml`(应用自有的段落与提示)与 `strings_anitabi.xml`
(通用短标签)都由本项目独立撰写,三语(默认英文 / zh / ja)的 `name` 集合与占位符必须一致
(lint 的 `MissingTranslation` 会拦不一致的键)。改文案不要改 `name`。

## 真机验证的要求

模拟器适合快速调试,但以下区域的改动**默认需要真机验证**,模拟器结论不充分:

| 区域 | 真机上要看什么 |
|---|---|
| `ui/sheet/`、`RootScreen` 的 sheet 段 | 三档吸附(快甩跨档 / 慢拖跟手 / 微动回原档)、列表联动(顶部下拉带走 sheet)、横竖屏往返、IME 升起、返回链 |
| `map/` | marker 出现/消失无闪烁,平移缩放不掉帧,选中气球与圆点同帧切换 |
| `ui/scene/`、`ui/scene/cutout/` | 相机进出后隐私指示灯熄灭、抠图三档降级、下载中断后可续传 |

## CI 与发布

[`.github/workflows/build.yml`](../.github/workflows/build.yml) 有两个作业:

- **check**:PR 与 `main` 推送跑单测 + lint + `assembleDebug`。**不使用任何 secret**(写入空的
  `MAPS_API_KEY`,地图空白但能编译),fork 的 PR 也能跑完整检查。
- **release**:只在 `v*` tag 上跑。用仓库 Secrets 还原 keystore、写入 `local.properties`,`assembleRelease`
  出签名 APK,校验签名后连同 `.sha256` 一起创建 GitHub Release。需要的 Secrets(维护者在
  *Settings → Secrets and variables → Actions* 配置):

  | Secret | 内容 |
  |---|---|
  | `MAPS_API_KEY` | 受限的 Maps key(包名 + release SHA-1) |
  | `RELEASE_KEYSTORE_BASE64` | `base64 < release.jks \| tr -d '\n'` |
  | `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` | 与 `local.properties` 里的同名值一致 |

  发布 = 改 `versionCode` / `versionName` → 提交 → `git tag v<versionName> && git push origin v<versionName>`。

## 调试开关

```bash
# 强制抠图档位(绕过 SoC 探测)
adb shell setprop debug.anitabi.cutout_tier cpu    # 或 mlkit

# 深链
adb shell am start -a android.intent.action.VIEW -d "anitabi://map?bangumiId=98330" \
  -n io.github.abucuma.junreimap/cn.anitabi.map.app.MainActivity
```

`debug` 变体允许明文流量,仅用于 `adb reverse` 本地调试抠图下载链路(见 [cutout-runtime.md](cutout-runtime.md));
release 不受影响。

## 离线脚本(`tools/`)

| 脚本 | 用途 |
|---|---|
| `gen_launcher_icon.py` | 由 `store/icon-source.png` 生成启动图标全部资源(adaptive 前景层五档、Android 13 单色层、`store/icon-512.png`);纯标准库,改源图后重跑即可 |
| `build_cutout_manifest.py` | 为抠图运行时 dist 目录生成 `manifest.json`(见 [cutout-runtime.md](cutout-runtime.md)) |
| `build_han_fold_table.py` · `build_pinyin_table.py` | 从 OpenCC / Unihan 重新生成搜索用的两张字符表(`data/search/HanFoldTable.kt`、`PinyinTable.kt`);**生成物禁止手改** |

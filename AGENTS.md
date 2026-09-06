# AGENTS.md —— 给 AI 编码代理的仓库规则

本文件的规则全部由**本仓库的实际代码**归纳,不是通用最佳实践清单。与
[docs/architecture.md](docs/architecture.md) 保持一致;两者冲突时以源码为准,并请顺手修正文档。

## 仓库速览

```
app/src/main/java/cn/anitabi/map/
  app/        MainActivity(唯一 Activity)、AppGraph(服务定位器)、AppRouter(导航状态)、RootScreen(组合根)
  data/       AnitabiStore(内存数据+索引)、AnitabiDataLoader(网络+缓存)、PilgrimageLog(巡礼记录)、
              LocationProvider、model/
              search/ 搜索归一化与索引(纯 Kotlin,可 JVM 单测;两张生成表禁止手改)
  map/engine/ 纯 Kotlin 地图引擎 —— 单测主战场,禁止引入 Android/GMS
  map/google/ Google Maps SDK 适配、marker 渲染与淡入
  ui/         Compose 界面:sheet(三档底部卡)/ home / point / work / log(巡礼记录)/ image / scene(对比拍摄)
  ui/scene/cutout/  实为服务层:运行时下载原生库 + ONNX 推理(位置有历史包袱)
  support/    深链、外链、图片兜底拦截器等无状态工具
  theme/      自绘调色板
app/src/test/         JVM 单测(248 个)
app/src/androidTest/  ⚠ 只有 DotRenderFidelityTest 是测试,五个 *Harness 是手动基准工具
tools/                离线脚本
```

## 修改代码之前

1. **先搜索既有实现**。这个仓库已经有:图片 URL 规则(`AnitabiImage`)、外链打开
   (`ExternalLinks`)、深链解析(`MapDeepLink`)、按比例填充绘制(`support/CanvasDraw.kt`)、
   flood fill(`MatteMath.floodComponent`)、共享 HTTP client(`AppGraph.okHttpClient`)、
   相册保存(`support/MediaStoreSaver.kt`)、原子落盘(`support/AtomicWrite.kt` 的 `writeAtomically`)。
   **不要新建平行实现** —— 仓库里已有的重复(见 docs/architecture.md 技术债)就是这么来的。
2. **读懂调用路径**再动手。特别是 marker 渲染链
   (`AnitabiMap` → `MapEngine` → `MarkerFieldController` → `MarkerIconFactory`)和
   sheet 的栈(`AppRouter.backStack` → `SheetNavHost` → 各卡片;尺寸只在 `SheetPeek`)。
3. **找出受影响的测试**:`app/src/test/` 下与被改文件同包的测试。改 `map/engine/`
   几乎一定会碰到 `MapEngineTest.kt`(内含 3 个测试类)。
4. **保留注释**。带真机结论的注释("真机反馈"、"踩坑"、"实测")是改动的前提条件,
   不要在重构中顺手删掉。

## 架构规则

- **`map/engine/` 必须保持纯 Kotlin**。不得 import `android.*` / `androidx.*` / `com.google.*`。
  违反会让它无法在 JVM 上单测。
- **由 `points`/`bangumis` 派生的东西必须与它们同批发布**(`AnitabiStore.Indexes`,
  写在同一个 `Snapshot.withMutableSnapshot` 里)。普通字段的写入不参与 snapshot,
  会先于数据可见 —— 历史上正是这样把搜索打崩的。派生索引也不要按下标引用另一个容器。
- **`MapEngine` 的所有调用必须在同一个单线程 dispatcher 上**
  (`Dispatchers.Default.limitedParallelism(1)`)。它的内部状态是普通 HashMap,没有锁。
- **不要引入 ViewModel / DI 框架**。本项目有意手写 `AppGraph` + `AppRouter`,
  UI 直接读 `AnitabiStore` 的 Compose 状态。这不是待修复的缺陷。
  导航用的是 Navigation 3(只取 back stack + `NavDisplay`),不要再引 NavController 那一套。
- **导航状态只属于 `AppRouter`**。不要在别处复制一份卡片栈。相机指令是例外
  (由 `RootScreen` 持有,因为行为随入口路径不同)。
- **新的持久化不要直接写 SharedPreferences**,加到 `AnitabiPrefs` 接口(`data/AnitabiStore.kt`)
  并在 `AppGraph` 的实现里落地 —— 现有实现全部集中在那里,测试用内存假实现。
  会长大的用户数据(如巡礼记录)走自己的文件 + `support/AtomicWrite.kt`,不塞进 SharedPreferences。
- **用户状态(巡礼记录)不走 `MapDataset`/`version`**:那会在每次打卡时重建 50k 点的索引。
  它以 `MapEngineRequest.visitFilter/visitedPointIds` 逐帧传入,`SceneKey` 只带 `visitedGeneration`。
- **网络请求走 `AppGraph.okHttpClient`**,不要新建 client(`MarkerImageCache` 的独立
  `Dispatcher` 是有意的例外,并且它仍是从共享 client `newBuilder()` 出来的)。
- **一帧要画什么只有一个决定处:`MapEngine.scene()`**。小圆点与**选中气球**由
  `PointDotOverlay` 自绘(气球画在圆点之上),剧照/作品标由 `MarkerFieldController` 上 Marker,
  但**所有内容都来自同一个 `MapScene`**。
  「一个点在一帧里只有一种形态」是不变式,`MapSceneTest` 钉着 —— 不要再开第二条循环去决定
  「哪些点画成圆点」。自绘层画在**所有 Marker 之上**,新增 Marker 形态时要在
  `MarkerFootprint` 里给它一个足迹,否则圆点会画到它身上。
- **不要把 UI 逻辑放进 `data/` 或 `map/engine/`**,也不要在 UI 里新增持久化写入
- **`data/search/` 必须保持纯 Kotlin**(不 import `android.*` / `org.json`)。搜索的
  正确性完全靠 JVM 单测保证,一旦引入 android 依赖就再也测不到了
  (已有两处 MediaStore 直写是历史债,不要照抄扩散)。
- **Compose 性能**:高频变化的值(拖拽偏移、方位角、透明度、padding)必须用 provider lambda
  或 `graphicsLayer` 块在布局/绘制相位读,不要在组合期读值。

## 依赖规则

- **默认不加依赖**。先在仓库里搜有没有现成能力。
- 确需新增时,在 PR/说明里回答:为什么现有依赖做不到、体积影响、许可证是否与
  [NOTICE](NOTICE) 兼容。
- 版本统一走 `gradle/libs.versions.toml`(ORT 与 QNN 也已收编,不要再往构建脚本里写字面量;
  ORT 的 Java 版本必须与运行时下载的 `libonnxruntime.so` 一致)。
- **不要顺手做大版本升级**。ML Kit 目前是 `16.0.0-beta1`,没有稳定版可换,保持现状。
- **不要把 `libonnxruntime.so` 打进 APK** —— `androidComponents` 里的排除是有意的,
  抠图原生库一律运行时下载 + 校验。

## 兼容性规则

改动以下内容属于破坏性变更,必须显式说明并给迁移方案:

- **SharedPreferences 键名**(`home.dataModified`、`map.baseStyle`、`map.isPhotoLayerVisible`、
  `map.pointVisitFilter`、`map.restoredDeepLink`、`hasCompletedOnboarding`、`cutout.isnetExperimentEnabled` 等)。
  **没有迁移机制**,改名即静默丢用户设置。
- **巡礼记录文件** `filesDir/pilgrimage_log.json` 的 JSON 契约(`version` / `visits[].{id,bangumiId,at}`)——
  这是用户自己的数据,解析要保持向前兼容。
- **磁盘缓存文件名与格式**(`cacheDir/anitabi-data/*`、`filesDir/cutout/*`)。
- **cutout `manifest.json` 的 JSON 契约**(`version` / `artifacts[name].{url,sha256,size}`)以及
  文件命名约定 —— 与 `tools/build_cutout_manifest.py` 和已托管的服务端强耦合。
- **深链格式**(`https://{4 个 host}/map?bangumiId=&pid=&c=lng,lat&z=&bids=` 与 `anitabi://map`)
  与 manifest 的 intent-filter,以及 `MapDeepLink.ALLOWED_HOSTS`。
- **字符串资源**:全部手写、三语(默认/zh/ja)`name` 集合与占位符必须一致;
  文案由本项目独立撰写,**不要从 anitabi 官方 iOS 版/网页版抄句子**(许可证前提)。
- **`AnitabiPrefs` 接口**:测试里有假实现,增删成员要同步
  `app/src/test/java/cn/anitabi/map/data/AnitabiStoreTest.kt`。

## Bug 修复规则

1. **先定位根因**,在改动说明里写清楚。本仓库历史上多个 bug 的根因都反直觉
   (例:sheet 横屏消失的根因是"故意留下的 ≤6dp 偏移让 settled 判定永远为假")。
2. **不接受只治症状的补丁**。加空判断、加 try/catch 吞掉、加延时"躲过去"都属于此类。
3. **能在 JVM 复现的逻辑必须补回归测试**到 `app/src/test/`。纯 UI/设备问题无法单测时,
   在说明里写明真机验证步骤与结果(设备型号 + Android 版本 + 测过哪些流程)。
4. **验证相邻行为**。改 sheet 宿主要过一遍拖拽/旋转/IME/返回链的既有回归;改 marker 链路
   要看有没有掉帧或闪烁;改网络层要确认数据与图片两条路都还通。

## 测试与验证命令

```bash
# 单测(唯一真正的自动化验证,当前 248 个)
./gradlew :app:testDebugUnitTest

# 只跑某个类
./gradlew :app:testDebugUnitTest --tests "*MapEngineTest*"

# 构建
./gradlew assembleDebug
./gradlew assembleRelease          # R8 + 资源收缩

# Android Lint(0 error 是门禁)
./gradlew :app:lintDebug
```

macOS 上若默认 JDK 非 17,前面加 `JAVA_HOME=/opt/homebrew/opt/openjdk@17`。

**已知状态**(不要误报为你引入的问题):

- `./gradlew :app:lintDebug` **当前 0 error**(约 70 个 warning),并且 `abortOnError = true`
  已写进 `app/build.gradle.kts`,CI 会跑它 —— **不要引入新的 error**。
- `RealDataSnapshotTest` 与 `SearchBenchmarkTest` 需要环境变量 `ANITABI_SNAPSHOT_DIR`,否则自动跳过 —— 正常。
- **`app/src/androidTest/` 里只有 `DotRenderFidelityTest` 是真正的测试**(逐像素比对,需设备);
  五个 `*Harness` 文件是手动基准/调试工具,无断言且需要设备上预置文件,已标 `@Ignore`。
  **不要把 `connectedCheck` 当作整体验证手段**,也不要为了让它们"跑起来"而去掉 `@Ignore` 或补断言。
- 没有 ktlint / detekt / spotless;风格靠 `.editorconfig`(4 空格、120 列)。

## 安全规则

- **绝不提交密钥**。`MAPS_API_KEY`、`CUTOUT_MANIFEST_URL` 与 `RELEASE_*` 签名配置只存在于
  `local.properties`(已被 `.gitignore` 排除),经 `manifestPlaceholders` / `resValue` / `signingConfigs` 注入。
  改动构建脚本时不要把它们写进任何会被提交的文件,也不要打印到日志。
- **不要放宽 TLS**:不得关闭证书校验、不得给 release 打开 cleartext。
  `app/src/debug/AndroidManifest.xml` 的 `usesCleartextTraffic` 仅限 debug,是为
  `adb reverse` 调试抠图下载;`CutoutEngine` 里的 https 强制只在
  `FLAG_DEBUGGABLE` 时豁免 —— 这个条件不要改成别的。
- **保留下载校验链**:大小 → SHA-256 → `renameTo` → `setReadOnly()` → 写 `.ok`。
  这是运行时 `System.load` 原生库的唯一防线,任何一环都不能省。
- **校验外部输入**:深链必须经 `MapDeepLink.parse`(scheme + host 白名单 + path 判定),
  不要新增绕过白名单的入口;外部 JSON 走 `AnitabiJsonParser` 的容错路径。
- **不要记录敏感信息**。当前全库只有 4 条日志且不含坐标、URL 参数或用户数据,保持这个水位。
  尤其不要打印精确定位、完整图片 URL 或 manifest 内容。
- **保持权限最小**:仅 `INTERNET` / `CAMERA` / `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`,
  定位仅前台。新增权限需要在说明里论证。
- 应用**没有登录与鉴权**,不要引入需要账号体系的功能而不先讨论。

## 改动范围纪律

- **做最小必要改动**。不要在修 bug 的 PR 里顺带重排 import、改格式、重命名变量。
- **不要重写能工作的组件**。marker 渲染链、`MapEngine` 都经过真机逐帧验证,
  重写的风险远大于收益;docs/architecture.md 的技术债清单是**记录**,不是待办。
- **不要"顺手"清理死代码**,除非任务本身就是清理。死代码清单已在 docs/architecture.md 记录。
- **保持既有行为**,除非任务明确要求改变它。改变用户可见行为时在说明里写清楚。
- 涉及 `ui/sheet/`、`RootScreen` 的 sheet 段、`map/`、`ui/scene/cutout/` 的改动,**默认需要真机验证**,
  模拟器结论不充分。

# 代码地图 —— 第一次打开这个仓库时看这份

按「我要改 X,该去哪」组织。所有路径都是真实存在的文件。
包根:`app/src/main/java/cn/anitabi/map/`。

## 应用从哪里启动?

| 顺序 | 文件 | 做什么 |
|---|---|---|
| 1 | [`app/AnitabiApplication.kt`](../app/src/main/java/cn/anitabi/map/app/AnitabiApplication.kt) | `Application`,构造 Coil `ImageLoader`(复用共享 OkHttpClient) |
| 2 | [`app/MainActivity.kt`](../app/src/main/java/cn/anitabi/map/app/MainActivity.kt) | 唯一 Activity:edge-to-edge → 解析深链 → `setContent { AnitabiTheme { RootScreen() } }` |
| 3 | [`app/AppGraph.kt`](../app/src/main/java/cn/anitabi/map/app/AppGraph.kt) | `AppGraph.get(context)` 首次调用时构造整张对象图 |
| 4 | [`app/RootScreen.kt`](../app/src/main/java/cn/anitabi/map/app/RootScreen.kt) | 组合根:拉起数据、装配地图 + sheet + 浮动控件 + 模态层 |

## 我要改 UI

| 想改什么 | 去哪 |
|---|---|
| 整体布局、浮动控件位置、模态层顺序、返回键优先级 | [`app/RootScreen.kt`](../app/src/main/java/cn/anitabi/map/app/RootScreen.kt) |
| 底部卡的拖拽/吸附/列表联动 | [`ui/sheet/DetentSheet.kt`](../app/src/main/java/cn/anitabi/map/ui/sheet/DetentSheet.kt) |
| 横屏侧栏、地图让位换算、层切换落档 | [`app/RootScreen.kt`](../app/src/main/java/cn/anitabi/map/app/RootScreen.kt) |
| 卡片栈的元素类型、各层初始档/展开档比例、横屏宽度、把手 | [`ui/sheet/SheetNav.kt`](../app/src/main/java/cn/anitabi/map/ui/sheet/SheetNav.kt) · [`ui/sheet/SheetNavHost.kt`](../app/src/main/java/cn/anitabi/map/ui/sheet/SheetNavHost.kt) |
| 卡片栈的操作(开卡、关卡、返回) | [`app/AppRouter.kt`](../app/src/main/java/cn/anitabi/map/app/AppRouter.kt) |
| 首页(搜索 / 附近 / 作品浏览) | [`ui/home/HomeSheet.kt`](../app/src/main/java/cn/anitabi/map/ui/home/HomeSheet.kt) |
| 巡礼记录(已完成地标的存储 / 打卡按钮 / 行内打勾 / 图层过滤) | [`data/PilgrimageLog.kt`](../app/src/main/java/cn/anitabi/map/data/PilgrimageLog.kt) · 地标卡 `PointHeaderRow` · 作品卡行 · `MapControlStack` 菜单第三段 · 引擎 `MapEngine.visitPredicate` |
| 巡礼记录列表(按作品聚类、Home 入口) | [`ui/log/PilgrimageLogSheet.kt`](../app/src/main/java/cn/anitabi/map/ui/log/PilgrimageLogSheet.kt) · 分组纯函数 [`data/PilgrimageLogGroups.kt`](../app/src/main/java/cn/anitabi/map/data/PilgrimageLogGroups.kt) · `SheetKey.PilgrimageLog` |
| 关于页、欢迎页、礼仪提示 | [`ui/home/ModalSheets.kt`](../app/src/main/java/cn/anitabi/map/ui/home/ModalSheets.kt) |
| 作品卡片 / 地标卡片 / 长按图钉卡片 | [`ui/work/WorkCardSheet.kt`](../app/src/main/java/cn/anitabi/map/ui/work/WorkCardSheet.kt) · [`ui/point/PointCardSheet.kt`](../app/src/main/java/cn/anitabi/map/ui/point/PointCardSheet.kt) · [`ui/point/DroppedPinSheet.kt`](../app/src/main/java/cn/anitabi/map/ui/point/DroppedPinSheet.kt) |
| 全屏图片查看器 | [`ui/image/ImageViewer.kt`](../app/src/main/java/cn/anitabi/map/ui/image/ImageViewer.kt) |
| 对比拍摄页与结果页 | [`ui/scene/SceneComparisonScreen.kt`](../app/src/main/java/cn/anitabi/map/ui/scene/SceneComparisonScreen.kt) |
| 地图控件柱(定位/图层/指南针/随机) | [`ui/components/MapControlStack.kt`](../app/src/main/java/cn/anitabi/map/ui/components/MapControlStack.kt) |
| 颜色 | [`theme/AnitabiPalette.kt`](../app/src/main/java/cn/anitabi/map/theme/AnitabiPalette.kt)(自绘调色板,**不是** `MaterialTheme.colorScheme`) |
| 作品名 / 地标名按语言取哪一份 | `NameLocale.displayName` 在 [`data/model/AnitabiModels.kt`](../app/src/main/java/cn/anitabi/map/data/model/AnitabiModels.kt);UI 用 `LocalNameLocale.current`([`theme/Theme.kt`](../app/src/main/java/cn/anitabi/map/theme/Theme.kt)) |

## 业务逻辑在哪?

本项目**没有** UseCase / Interactor 层。业务逻辑分布在两处:

- **数据侧**:[`data/AnitabiStore.kt`](../app/src/main/java/cn/anitabi/map/data/AnitabiStore.kt) ——
  搜索(`search`)、分组(`groupedPoints`)、视口内作品(`worksInViewport`)、附近
  (`recomputeNearby`)、浏览分页(`works(tab)`)、贡献者汇总。
- **地图侧**:[`map/engine/`](../app/src/main/java/cn/anitabi/map/map/engine/) —— 纯 Kotlin,
  决定"当前相机该显示哪些 marker"。这是单测覆盖最好的部分。

| 文件 | 负责 |
|---|---|
| `map/engine/MapEngine.kt` | 每帧的全部内容(`scene()`):标注 + 作品标 + 圆点 |
| `map/engine/MapScene.kt` | 一帧的不可变快照 + `debugLine()` |
| `map/engine/DotField.kt` · `DotPasses.kt` | 圆点的分趟/分色/命中网格 |
| `map/engine/MapRevealLadder.kt` | 随 zoom 的显示阶梯与尺寸 |
| `map/engine/MarkerFootprint.kt` | 各形态相对锚点的 dp 矩形(碰撞与遮挡共用) |
| `map/engine/MarkerCollision.kt` | 屏幕空间避让 |
| `map/engine/SheetRevealPolicy.kt` | 选中一个点之后相机要不要让位 |
| `map/engine/MapCameraPlanner.kt` | 相机算术(zoom↔经度跨度、飞行时长、fitBounds) |
| `map/engine/GeoMath.kt` | ⚠ 文件名与内容不符,内容是 `WorkRegionGeometry`(凸包/缓冲/点在多边形内) |

## API 调用在哪?

| 层 | 文件 |
|---|---|
| 唯一的 HTTP client + 拦截器(UA、图片 host/尺寸兜底) | [`app/AppGraph.kt`](../app/src/main/java/cn/anitabi/map/app/AppGraph.kt) · [`support/ImageHostFallback.kt`](../app/src/main/java/cn/anitabi/map/support/ImageHostFallback.kt) |
| 巡礼数据抓取 + 节点故障转移 + 磁盘缓存 | [`data/AnitabiDataLoader.kt`](../app/src/main/java/cn/anitabi/map/data/AnitabiDataLoader.kt) |
| JSON 容错解析 | [`data/AnitabiJsonParser.kt`](../app/src/main/java/cn/anitabi/map/data/AnitabiJsonParser.kt) |
| 图片 URL 规则(host、`plan` 档位、换档) | `AnitabiImage`,在 [`data/model/AnitabiModels.kt`](../app/src/main/java/cn/anitabi/map/data/model/AnitabiModels.kt) |
| marker 缩略图抓取(独立 Dispatcher) | [`map/google/MarkerImageCache.kt`](../app/src/main/java/cn/anitabi/map/map/google/MarkerImageCache.kt) |
| 作品图标雪碧图 | [`map/google/BangumiIconSprite.kt`](../app/src/main/java/cn/anitabi/map/map/google/BangumiIconSprite.kt) |
| 抠图运行时下载(断点续传 + SHA-256) | [`ui/scene/cutout/CutoutRuntimeStore.kt`](../app/src/main/java/cn/anitabi/map/ui/scene/cutout/CutoutRuntimeStore.kt) |

## 持久化在哪?

| 载体 | 代码位置 |
|---|---|
| SharedPreferences(接口 `AnitabiPrefs` + 实现) | 接口在 [`data/AnitabiStore.kt`](../app/src/main/java/cn/anitabi/map/data/AnitabiStore.kt),实现 `SharedPrefsAnitabiPrefs` 在 [`app/AppGraph.kt`](../app/src/main/java/cn/anitabi/map/app/AppGraph.kt) |
| 数据磁盘缓存 `cacheDir/anitabi-data/` | [`data/AnitabiDataLoader.kt`](../app/src/main/java/cn/anitabi/map/data/AnitabiDataLoader.kt) |
| 抠图运行时 `filesDir/cutout/` | [`ui/scene/cutout/CutoutRuntimeStore.kt`](../app/src/main/java/cn/anitabi/map/ui/scene/cutout/CutoutRuntimeStore.kt) |
| 相册 `Pictures/Anitabi`(MediaStore) | [`support/MediaStoreSaver.kt`](../app/src/main/java/cn/anitabi/map/support/MediaStoreSaver.kt)(`ImageViewer` 与 `SceneComparisonScreen` 共用) |
| 巡礼记录 `filesDir/pilgrimage_log.json`(原子写) | [`data/PilgrimageLog.kt`](../app/src/main/java/cn/anitabi/map/data/PilgrimageLog.kt) · [`support/AtomicWrite.kt`](../app/src/main/java/cn/anitabi/map/support/AtomicWrite.kt) |
| 备份规则 | [`res/xml/backup_rules.xml`](../app/src/main/res/xml/backup_rules.xml) · [`res/xml/data_extraction_rules.xml`](../app/src/main/res/xml/data_extraction_rules.xml) |

**没有数据库,也没有迁移机制。**

## 模型在哪?

[`data/model/AnitabiModels.kt`](../app/src/main/java/cn/anitabi/map/data/model/AnitabiModels.kt) ——
`BangumiLite`、`ScenePoint`、`LatLon`、`PointGroup`、`AnitabiImage` 等。
`AnitabiStore.kt` 顶部另有 `NearbyPoint`、`CityTile`、`SearchResult`、`DataLoadPhase`、
`LatLonRegion`;地图引擎自己的类型(`MapDataset` / `MapViewport` / `MapScene` 等)
在 `map/engine/MapEngine.kt`。

## 配置值在哪?

| 配置 | 位置 |
|---|---|
| `MAPS_API_KEY` / `CUTOUT_MANIFEST_URL` / `RELEASE_*` | `local.properties`(不提交)→ `app/build.gradle.kts` 注入 |
| SDK 版本、R8、打包排除 | [`app/build.gradle.kts`](../app/build.gradle.kts) |
| 依赖版本 | [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) |
| 权限、intent-filter、深链 host、`configChanges` | [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) |
| 语言列表 | [`res/xml/locales_config.xml`](../app/src/main/res/xml/locales_config.xml) |
| 暗色地图样式 | [`res/raw/map_style_dark.json`](../app/src/main/res/raw/map_style_dark.json) |
| 数据节点、缓存参数公式 | `AnitabiDataLoader.ORIGINS` / `cacheBuster()` |
| 抠图设备分档表(SoC → HTP 架构、内存门限) | [`ui/scene/cutout/DeviceTier.kt`](../app/src/main/java/cn/anitabi/map/ui/scene/cutout/DeviceTier.kt) |

用户可见文本:`res/values{,-zh,-ja}/strings_app.xml`(段落与提示)与 `strings_anitabi.xml`
(短标签)。两者都是**手写**的,三语 `name` 集合与占位符必须一致。

## 测试在哪?

```
app/src/test/java/cn/anitabi/map/
  app/AppRouterTest.kt                   导航状态机(开卡/关卡/返回/巡礼记录页)
  data/AnitabiDataLoaderTest.kt          节点故障转移、缓存
  data/AnitabiJsonParserTest.kt          容错解析、图片 URL 规则
  data/AnitabiStoreTest.kt               搜索/分组/索引(含 AnitabiPrefs 的内存假实现)
  data/PilgrimageLogTest.kt              巡礼记录:toggle / 落盘往返 / 坏文件改名 / 先点后载合并
  data/PilgrimageLogGroupsTest.kt        巡礼记录列表的分组与排序
  data/RealDataSnapshotTest.kt           需 ANITABI_SNAPSHOT_DIR,否则跳过
  data/search/*.kt                       归一化(TextFold)、索引与打分、罗马字/拼音、基准(需快照)
  map/engine/MapEngineTest.kt            ⚠ 内含 3 个测试类(MapEngineTest / GeoMathTest / MapCameraPlannerTest)
  map/engine/*.kt                        圆点分趟、圆点快照、命中、投影、足迹、显示阶梯、让位决策
  map/google/FadeCurveTest.kt            淡入曲线与量化
  map/google/MarkerImageCacheTest.kt     采样率计算
  support/DeeplinkTest.kt                深链解析与白名单
  support/ImageHostFallbackInterceptorTest.kt   图片兜底候选顺序(MockWebServer)
  theme/AnitabiPaletteContrastTest.kt    调色板对比度
  ui/sheet/SheetPeekTest.kt              各层各档的露出高度表
  ui/scene/MatteMathTest.kt              抠图后处理
  ui/scene/cutout/*.kt                   manifest 解析、下载校验链
```

⚠ [`app/src/androidTest/`](../app/src/androidTest/) 里只有 `DotRenderFidelityTest` 是真正的仪器测试;
五个 `*Harness` 文件**不是测试**,是需要手工在设备上预置文件的基准/调试工具,没有断言,已标 `@Ignore`。

跑:`./gradlew :app:testDebugUnitTest`。

## 常见「我该从哪读起」

- **想了解整体**:先 [architecture.md](architecture.md),再 `RootScreen.kt`。
- **想改地图显示密度**:`MapRevealLadder.kt` + `MapEngineTest.kt` 里的阶梯断言。
- **想加一个卡片按钮**:`PointCardSheet.kt` / `WorkCardSheet.kt`,字符串加进
  `strings_app.xml` 三个 locale。
- **想查某个网络请求为什么失败**:`AppGraph` 的两个拦截器 → `AnitabiDataLoader.ORIGINS`
  → `ImageHostFallback`。
- **想动抠图**:先读 [cutout-runtime.md](cutout-runtime.md) 与 `CutoutEngine.kt` 顶部注释;
  默认开关是**关**的(About 页实验性开关)。

# 主要流程

最重要的几条用户流程,用真实的文件/函数名标注。行号会随改动漂移,故只标文件与函数。

---

## 1. 冷启动 → 地图出图

```
系统启动进程
→ AnitabiApplication.newImageLoader()            构造 Coil,复用 AppGraph.okHttpClient
→ MainActivity.onCreate()
    enableEdgeToEdge()
    handleDeepLink(intent, allowPersistedFallback = true)
    setContent { AnitabiTheme { RootScreen() } }
→ RootScreen 首次组合
    AppGraph.get(context)                        构造服务图(主线程)
    LaunchedEffect { AnitabiStore.bootstrap() }(与 PilgrimageLog.load() 并行)
      → AnitabiDataLoader.loadFromCache()        先用磁盘缓存,让地图立刻有东西画
      → AnitabiStore.apply()                     Snapshot 原子发布,末尾 dataGeneration++
      → AnitabiDataLoader.refresh()              再拉网络(/d/g.json + g0…g6)
      → AnitabiStore.apply()                     再次发布,dataGeneration++
    AnitabiStore.loadAuxiliary()                 users.csv、bangumi-icons.json
→ AnitabiMap 的 MapEffect(Unit) 更新循环
    snapshotFlow{SceneKey}.sample(48)
    → MapEngine.load(MapDataset.from(store, generation))   generation 变化时才重建
    → withContext(engineDispatcher):MapEngine.scene() → MarkerFieldController.plan()
                                     → MarkerIconFactory.prewarm()
    → dotState.publish(scene);MarkerFieldController.apply()   圆点与 marker 同帧落地(3ms/帧预算)
```

失败路径:`bootstrap()` 抛错 → `AnitabiStore.phase = DataLoadPhase.Failed` →
`HomeSheet` 显示错误行与重试。

---

## 2. 点选地图上的地标

```
用户点 marker
→ MarkerFieldController.onPointTap(id)           GMS marker 点击回调
→ AnitabiMap 的 onPointTap 参数
→ RootScreen:store.point(id) → router.selectPoint(point)
→ AppRouter.backStack 压入 SheetKey.Point(pointCard 是投影)
   ├→ NavDisplay 淡入 PointCardSheet;RootScreen 的 LaunchedEffect(topKey, restDetent) 把 sheet 动画到该层的落档
   └→ RootScreen 递增 commandSeq,下发 MapCommand.FlyToPoint
      → AnitabiMap 的 LaunchedEffect(command?.seq) 执行相机动画
      → 相机动画后的 placementJob:先等 obstruction.target 变化(≤150ms),
        再等 obstruction.settled ＝ current==target(≤900ms)。
        判据是「弹簧到位了没有」而不是「48ms 内有没有新值」——
        后者在动画起步前就会到期,于是拿初始档的 padding 判「不用避让」
        → SheetRevealPolicy.shiftFor(纯函数) 给出位移,再做一次 300ms 的 scrollBy
```

---

## 2b. 地标点的展示

**一条循环、一个场景。** 一帧要画什么由 `MapEngine.scene()` 一次决定 ——
标注与圆点不再由两条互不知情的循环各自决定（那正是「气球出现了圆点还在」的来源）。

```
snapshotFlow{SceneKey(相机, 世代, 选中, 作品模式, chips, 剧照层)}.sample(48)
  ← sample 而非 debounce：手势中相机每帧都变，debounce 会一次都不发
→ worker：MapEngine.scene(request, dotColors, minHitRadiusDp)
     ① 标注：photoPromotions（只产出要 Marker 的点）→ 选中点必留 → MarkerCollision
     ② 作品标：ladderWorks（z<13、屏幕上 ≤32 个）
     ③ 圆点：MapPointIndex.query(视口×1.6, 作品过滤)
              − 已经是标注的点（按 id）
              − 落在标注足迹里的点（MarkerFootprint，外扩一个圆点白描边半径）
        → DotPasses 分趟（min−1 规则:重叠对按 priority 排序,缩放不翻面;
          分不开的点不画、不可点中）
        → 按趟、按作品颜色分桶
        → 世界坐标平铺成一条 DoubleArray
          （Double 而非 Float —— Float 在 z18+ 有亚像素到数像素的位置误差）
     ④ controller.plan + iconFactory.prewarm（同一个 worker 上把图先画好）
→ main：dotState.publish(scene) 然后 controller.apply(scene)
     两者在同一个续体里落地 ⇒「气球出现」与「圆点消失」必定同帧

绘制阶段（每帧）：向 GMS 取 cameraPosition + projection.toScreenLocation(target) 锚定原点
     （contentPadding 会把相机目标推离视图中心，且随 sheet 弹簧逐帧变，不能自己按中心推）
     失效源＝场景换代 ∪ 相机变化(invalidateToken) ∪ **绘制期读 padding** ——
     setPadding 平移底图但不改 CameraPosition,只靠相机链会在 sheet 动时冻住圆点
     (真机 perfetto 证实:弹簧期间 GL-Map 在渲染、主线程在出帧、叠加层 120ms+ 不重画)
     → MapProjection.Affine 变换
     → DotRenderer：逐趟「一次白描边 + 每色一次填充」（趟内不重叠 ⇒ 等价于逐点画）
  z<12 整层不画（聚合交给作品标）；z11.7→12.0 用 saveLayerAlpha 整层渐入

点击：onMarkerClick（剧照/作品标）优先 → 未命中才 onMapClick / onPoiClick
      → MapSceneHitTest.at(已发布的场景)：所见即可点，不另查索引
        气球形状（头圆+尾，视觉顶层）→ 圆盘内取趟号最高 → 22dp 热区内取最近
        → 兜底查剧照外扩足迹（死区对策）
      气球不是 Marker —— 由 PointDotOverlay 画在圆点之上（Marker 版的 44×54dp 位图
      命中框曾把选中点周围的邻点全部锁死）

场景的一行摘要在 MapScene.debugLine()（真机 harness 与 RealDataSnapshotTest 打印用；
屏幕 HUD 已按用户要求移除）：
  z=15.00 dots=871 pass=32(hid9) K=370 ann=1(card0/bub0/bal1) work=0 occl=9 build=8.8ms

## 3. 搜索

```
用户输入
→ HomeSheet 的 query 状态
→ produceState(key = trimmed + store.dataGeneration)
     delay(120ms)        ← key 变化会取消上一个协程,所以这句 delay 就是防抖
     withContext(Dispatchers.Default) {
         AnitabiStore.search(trimmed, near = 定位, isActive = ...)
         + AnitabiStore.matchingCities(trimmed)     ← 一趟算完三节,只触发一次重组
     }
     ⚠ 定位经 rememberUpdatedState 读取,有意不进 key ——
       否则后台定位每 50m 更新会重排用户正在看的结果
     ⚠ 被抢占时抛 CancellationException 而非返回空 —— 返回空会闪一下空态
→ SearchResults 渲染(作品 / 地标 / 城市三节)
→ 点结果:作品 → router.focusWork(id) + MapCommand.FitBounds
          地标 → router.selectPoint(point) + MapCommand.FlyToPoint
          城市 → MapCommand.FitBounds
```

匹配本身在 `data/search/`(纯 Kotlin,可 JVM 单测):

```
查询串 / 索引字段
→ TextFold.fold   NFKC → lowercase → 叠字号展开 / 剥标点与长音符
                  / 片假名→平假名 / 汉字异体→简体
                  两侧走同一条管线,折叠后的域是「小写拉丁+数字+平假名+简体汉字」
→ SearchIndex     字段以 U+0001 拼接成单条 key(空槽保留,下标即权重下标);
                  末尾追加转写字段(日文假名的罗马字、中文的拼音全拼与首字母)
→ query()         64 位字符指纹预筛 → indexOf 打分 → 有界最小堆取 top-K
                  档位 整字段相等/字段前缀/字段内子串,乘字段权重与长度归一;
                  作品加 ln(1+地标数),地标在有定位时加距离项(**只影响选谁,不影响排序**)
```

---

## 4. 深链进入

```
外部点开 https://{anitabi.cn|www|ww|w.junreimap.com}/map?... 或 anitabi://map?...
→ MainActivity.onCreate 或 onNewIntent
    onNewIntent 必须先 setIntent(intent)         singleTask 平台契约
    handleDeepLink(intent, allowPersistedFallback = <冷启动才 true>)
→ MapDeepLink.parse(uri)                         scheme + host 白名单 + path=="/map"
→ AppGraph.pendingDeepLink.value = link
→ RootScreen 观察到 pendingDeepLink
    bangumiId → router.focusWork;pid → router.selectPoint;bids → router.setWorkFilter
    c=lng,lat & z → 传给 AnitabiMap 的 deepLink 参数
→ AnitabiMap 的 LaunchedEffect(deepLink):cameraPositionState.move(...) → onDeepLinkConsumed()
```

从桌面/最近任务重进时 `allowPersistedFallback = false`,**有意不回放**持久化深链,
否则会出现"地图自己跳了/卡片被重置"。

---

## 5. 对比拍摄(含 AI 抠图)

```
地标卡片点「对比拍摄」
→ PointCardSheet:AnitabiImage.url(point.image, plan = null)  完整尺寸(抠图要分辨率)
→ router.openCamera(CameraSessionParams(photoUrl, ...))
→ RootScreen 渲染 SceneComparisonScreen
    LaunchedEffect(Unit) { CutoutEngine.warmUp() }
      ⚠ 默认 userEnabled=false(About 页实验开关),关闭时直接返回、不下载任何东西
    LaunchedEffect(params.photoUrl):Coil 取参考图
      候选:完整尺寸 → AnitabiImage.withPlan(url,"h360")
    → CutoutEngine.extract(bitmap)
        ISNet 可用 → IsnetOrtExtractor.alphaMask()(每次推理开关 ORT session)
                    → SubjectExtractor.fromAlpha() → MatteMath.refine(strongMatte=true)
        否则/失败 → SubjectExtractor.extract()(ML Kit,GMS 下发模型)
    CameraX:LifecycleCameraController + PreviewView
      ⚠ DisposableEffect { onDispose { controller.unbind() } } —— 不 unbind 隐私指示灯常亮
→ 按快门:takePicture(Dispatchers.Default.asExecutor())     解码/旋转不在主线程
→ centerCrop 到参考图比例 → ResultPage
→ 保存:ComparisonImageGenerator.comparisonCard() 或 .composite()
        → MediaStoreSaver(support/):MediaStore + IS_PENDING=1 → 写 JPEG(q=92) → IS_PENDING=0
```

抠图运行时首次下载(仅当实验开关打开且非计费网络):

```
CutoutEngine.prepare()
→ fetchManifest()                 24h TTL 磁盘缓存;release 强制 https
→ CutoutManifest.requiredArtifacts(tier)   按 HTP/CPU 档给出文件清单
→ CutoutRuntimeStore.ensure()
     断点续传(Range)→ 大小校验 → SHA-256 → renameTo → setReadOnly() → 写 .ok
→ IsnetOrtExtractor 构造 → State.Ready
```

---

## 6. 保存/查看大图

```
地标卡片点剧照
→ AnitabiImage.url(point.image, plan = "h360")   官方文档:展示界面不用完整尺寸
→ RootScreen.imageViewerUrl = url → ImageViewer
→ LaunchedEffect(url, loadAttempt):Coil 取图,自动重试 2 次
   每次请求都经 ImageHostFallbackInterceptor:
     ①原样 → ②换另一个 host → ③换另一档尺寸(h160⇄h360;404 不走这步)
   全败 → 显示「图片加载失败 + 重试」
→ 点保存:saveToPhotos()
   候选:AnitabiImage.withPlan(url, null) 完整尺寸 → 退回展示用的 h360
   → MediaStore(Pictures/Anitabi,IS_PENDING 协议)
```

---

## 7. 定位

```
点定位按钮
→ RootScreen.requestLocation()
   已授权 → LocationProvider.startUpdates() + MapCommand.UserLocation
   未授权 → rememberLauncherForActivityResult 申请
   被拒 → isDenied=true + toast;永久拒绝 → 引导到应用设置页
→ LocationProvider(FusedLocation,粗精度优先、仅前台、50m 节流)
→ location 变化 → AnitabiStore.recomputeNearby() → HomeSheet「附近的圣地」
→ 生命周期:ON_STOP → stopUpdates();ON_START → 若 locationActive 则恢复
```

---

## 8. 巡礼记录(标为已完成)

```
地标卡头部 ✓
→ AppGraph.toggleVisited(point)
   ├→ PilgrimageLog.toggle():records 整体替换(Compose 状态)、generation += 1
   │    ├→ WorkCardSheet:行内 isVisited(id) 重组可见行;副标题「已完成 n/m」按 generation 重算
   │    └→ AnitabiMap:SceneKey(visitFilter, visitedGeneration) 变 → 下一帧 engine.scene()
   │         按 request.visitFilter 过滤圆点 / 剧照牌 / 作品标(MapEngine.visitPredicate / admittedWorkIds)
   └→ appScope.launch { PilgrimageLog.save() }   files/pilgrimage_log.json,writeAtomically + Mutex
图层菜单「全部 / 只看已完成 / 只看未完成」
→ RootScreen.visitFilter(prefs.pointVisitFilter)→ 同上进入 SceneKey
Home「巡礼记录」卡(有记录才出现)
→ router.openPilgrimageLog():栈 [Home, PilgrimageLog]
→ PilgrimageLogSheet:PilgrimageLogGroups.build(按当前数据集聚类,组内/组间按打卡时间倒序,孤儿只计数)
   组头 → focusWork;行 → selectPoint + flyToPoint(与 Home 同路径);取消完成只在地标卡的 ✓
```

## 9. 在网页版打开(外链)

```
菜单点「在网页版打开」
→ AnitabiWebLink.canonical(...)  构造 ww.anitabi.cn/map?... (自家声明的 host)
→ ExternalLinks.openInCustomTab()
   CustomTabsClient.getPackageName() 解析 CCT 浏览器包并 setPackage()
   ⚠ 不固定包的话,用户若把 anitabi.cn 链接默认交给本 App,会被解析劫回 App 自身
   解析不到 → 对自家 host 用 CATEGORY_APP_BROWSER 直启默认浏览器
→ Google 地图/街景/出典链接走 ExternalLinks.openExternally()(有意交给对应 App)
```

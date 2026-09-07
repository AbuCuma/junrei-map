# 维护与发布

面向发布之后的日常维护。所有命令与仓库现状一致。

## 依赖维护

采取**保守**策略。依赖版本全部集中在 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)。

| 类型 | 处理 |
|---|---|
| 安全更新 | **优先处理**,单独一个 PR,写明 CVE/公告 |
| 补丁版本(x.y.**z**) | 低风险,可批量;跑单测 + debug 构建即可 |
| 次版本(x.**y**.z) | 正常评审,跑全量验证 + 一次真机冒烟 |
| 大版本(**x**.y.z) | 手工评审,单独 PR,附迁移说明与真机验证记录 |

需要额外小心的几项:

- **ONNX Runtime**:`libonnxruntime.so` 由服务端下发,APK 里只有 Java 层与 JNI 壳。
  **升级 ORT 依赖必须同步更新已托管的 dist 与 manifest**,两者版本不一致会在运行时崩。
- **ML Kit `16.0.0-beta1`**:目前没有稳定版可换,保持现状,不要为了"去 beta"而换实现。
- **Google Maps SDK / maps-compose**:marker 渲染链对 SDK 行为有隐式依赖
  (碰撞、镶嵌、marker 不能放 View),升级后需真机看 marker 显示与淡入。
- **Compose BOM / Foundation**:`DetentSheet` 直接用 Foundation 的 `AnchoredDraggableState`
  与 `NestedScrollConnection`,升级后真机过一遍三档吸附与列表联动;并查看
  `app/build/compose_reports/*-composables.txt`,确认热路径组件仍是 `restartable skippable`。

`./gradlew :app:lintDebug` 会报 `GradleDependency` / `NewerVersionAvailable` 提示可用新版,
可作为例行检查的输入,但不要盲从。

## 发布检查清单

应用只在 GitHub Release 分发。打 `v*` tag 后 CI 的 release 作业会签名、校验并创建 Release
(见 [development.md](development.md) 的「CI 与发布」);下面是发布前在本机做的核对。

```bash
# 1. 测试 + 静态检查(0 error 是门禁)
./gradlew :app:testDebugUnitTest :app:lintDebug

# 2. 构建(release 走 R8 + 资源收缩;local.properties 里 RELEASE_* 齐全时产物已签名)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew clean assembleDebug assembleRelease

# 3. 核对签名与校验值
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
shasum -a 256 app/build/outputs/apk/release/app-release.apk
```

- [ ] **版本号**:`app/build.gradle.kts` 的 `versionCode`(单调递增)与 `versionName`
- [ ] **测试**:单测全绿(`RealDataSnapshotTest` / `SearchBenchmarkTest` 按环境跳过是正常的)
- [ ] **静态检查**:`lintDebug` 0 error
- [ ] **构建**:debug 与 release 均通过(release 容易暴露 keep 规则问题)
- [ ] **真机冒烟**(模拟器不充分):冷启动出图 → 搜索 → 点地标出卡片 → 三档拖拽/甩动 sheet →
      横竖屏往返 → 深链进入 → 定位开关 → 标记一处已完成并重启确认仍在 → 对比拍摄进出
      (确认隐私指示灯熄灭)→ 保存图片到相册
- [ ] **配置**:`MAPS_API_KEY` 是**受限**的 key(包名 + release SHA-1 + API 限制);
      若改过 `CUTOUT_MANIFEST_URL`,确认指向 https 且 dist 与 APK 内 ORT 版本一致
- [ ] **依赖变更**:本次是否动过依赖?动过就补一次完整真机回归
- [ ] **兼容性**:改过 SharedPreferences 键名、`pilgrimage_log.json` 格式或磁盘缓存文件名的话,
      视为破坏性变更并在 release notes 里说明(没有迁移机制)
- [ ] **打 tag**:`git tag v<versionName> && git push origin v<versionName>`,等 Action 创建 Release,
      再核对 Release 页上的 SHA-256 与签名证书指纹。tag **必须**是 `v` + `versionName`(`vX.Y.Z`),
      客户端的检查更新按它与本机 `versionName` 比大小;不要用预发布(prerelease)Release 发正式版本,客户端会忽略它

## Bug 维护流程

```
Issue
  ↓
复现(记录设备型号 + Android 版本 + 步骤)
  ↓
定位根因(不接受只治症状)
  ↓
写回归测试(JVM 能覆盖的必须写;纯 UI 问题记录真机验证步骤)
  ↓
实施修复(最小改动)
  ↓
全量验证(单测 + debug/release 构建 + 真机冒烟)
  ↓
发布
```

按子系统的额外要求见 [development.md](development.md) 的「真机验证的要求」。

## 技术债处理原则

[architecture.md](architecture.md) 的「已确认的架构债」是**记录**,不是待办清单。处理原则:

1. **顺路修,不专程修**:改到某个文件时,顺手清理该文件里已记录的债。
2. **不做投机式大重写**。marker 渲染链、`MapEngine` 都经过真机逐帧验证,重写风险远大于收益。
   (sheet 是例外:前两版都有明确根因 —— 实测锚点的反馈环、借 M3 Hidden 伪造第三档与其手势
   管线冲突;现在的 `DetentSheet` 锚点静态、手势自持。)
3. **先补测试再动结构**。`map/engine/`、`data/search/` 已有较好覆盖,可以放心重构;
   `ui/` 几乎没有测试,动之前要么补测试,要么接受真机验证成本。
4. **一次一项**。不要把死代码清理、重命名、行为修复混在一个 PR 里。
5. **债务变成 bug 才升优先级**。

## 已知局限

这些是有意接受、或需要外部条件才能解决的事项,不是 bug 清单:

- **cutout manifest 自身没有签名**。服务端失陷时攻击者可同时替换 hash 与二进制;
  `cutout-dist/manifest.json` 是可审计的基线副本,属缓解而非解决。加固手段是给 manifest 签名或把
  已知 hash 固定进仓库。
- **进程死亡会丢已拍未保存的照片**。卡片栈与相机位由 `prefs.restoredDeepLink` 恢复,
  照片需要持久化 bitmap,成本与收益不成比例,有意接受。
- **横屏下搜索结果在 IME 升起时空间局促**(360dp 侧栏)。
- **`ui/` 层没有自动化测试**,以真机验证为准(有意)。
- **文字对比度低于 WCAG AA**(`inkSecondary` / `inkTertiary` / `inkFaint`),移植自 iOS 设计,
  作为设计取舍保留。
- **`MarkerFieldController.apply()` 是否确实在主线程执行**依赖 maps-compose 的
  `MapEffect` 上下文实现细节,代码注释假设是主线程,未在本仓库中断言。

## 定期检查(建议)

- **每次发布前**:上面的检查清单。
- **每季度**:依赖新版盘点(`lintDebug` 的 `GradleDependency` 提示)+ 一次 `UnusedResources` 复查
  (文案全部手写,未用串可直接删)。
- **服务端相关**:本应用依赖 anitabi.cn 的数据 CDN 与 magiconch.com 的图片 CDN。历史上出现过
  图片 host DNS 整段失效、源站对 CDN 不可达等情况(见 [data-sources.md](data-sources.md))。
  收到"大面积加载失败"报告时,**先确认服务端状态**再怀疑客户端。
- **图片 host 的顺序有依据,别凭直觉改回去**:主 host 之所以不是开放 API 文档里的
  `image.anitabi.cn`,是因为 web 客户端对本 App 所用的 origin 就解析到
  `image-anitabi.magiconch.com`,且文档 host 只收录 5 个命名空间里的 2 个。若要调整,
  先重新核对 web 客户端当前的规则,并同步 `AnitabiImage` 的 KDoc 与 data-sources.md。
- **公开仓库后**:在 GitHub *Settings → Advanced Security* 打开 *Private vulnerability reporting*,
  [SECURITY.md](../SECURITY.md) 描述的报告渠道才会出现。

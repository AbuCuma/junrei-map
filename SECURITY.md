# 安全策略

## 报告渠道

请通过 **GitHub 私密漏洞报告**提交:仓库页面 → **Security** 标签 → **Report a vulnerability**。
该渠道只有仓库维护者可见,在修复发布之前不会公开。

请**不要**在公开 issue、PR 或讨论区中披露漏洞细节 —— 那等同于直接公开。
报告时请附:受影响版本(或 commit)、复现步骤、影响范围评估;如有 PoC 请一并附上。

> **给维护者**:仓库转为公开后,需要在 *Settings → Advanced Security →
> Private vulnerability reporting* 里把该功能打开,上面的入口才会出现。
> 在打开之前,本文件描述的渠道是不可用的。

## 支持的版本

只支持 [Releases](../../releases) 页面上的**最新版本**(当前 v0.1)与 `main` 分支最新提交。

## 什么算安全问题

属于:

- 通过 deep link、外部 Intent 或外部数据触发的任意代码执行、路径穿越、越权
- 绕过抠图运行时的下载校验链(大小 → SHA-256 → `renameTo` → `setReadOnly` → `.ok`),
  从而让应用 `System.load` 到未经校验的原生库
- 中间人攻击可行(TLS 校验被绕过、生产环境出现明文传输)
- 用户数据(精确定位、相册内容、已保存图片)被非预期地外泄或写入
- 仓库或构建产物中泄露了密钥
- 权限提升,或让应用获得超出清单声明的能力

不属于(可以直接开公开 issue):

- 上游服务(anitabi.cn / Google Maps / ML Kit)不可用或返回错误
- 需要用户已 root 或已开启开发者选项才能利用的问题
- 仅影响 debug 变体的行为(见下)
- UI 层的对比度、可访问性等非安全缺陷

## 本仓库的安全敏感区域

供审计者优先关注(不含任何可利用的密钥):

| 区域 | 位置 | 要点 |
|---|---|---|
| **运行时加载原生库** | `ui/scene/cutout/CutoutRuntimeStore.kt`、`IsnetOrtExtractor.kt` | 应用会下载 `.so` 并 `System.load`。防线是完整的校验链 + `setReadOnly()`;**任何削弱都属高危**。目前 manifest 本身**没有签名**,若服务端失陷,攻击者可同时替换 hash 与二进制 —— 已知局限 |
| **https 强制** | `ui/scene/cutout/CutoutEngine.kt` | manifest 与产物 URL 都要求 https,仅当 `FLAG_DEBUGGABLE` 时豁免 |
| **debug 明文** | `app/src/debug/AndroidManifest.xml` | `usesCleartextTraffic="true"` **仅 debug**,用于 `adb reverse` 本地调试下载链路;release 不受影响 |
| **深链入口** | `support/Deeplink.kt`、`AndroidManifest.xml` | 唯一 exported 组件是 `MainActivity`。解析走 scheme + host 白名单(`ALLOWED_HOSTS`)+ `path == "/map"`;无 intent 转发,不接收 Parcelable extra |
| **外链打开** | `support/CustomTabs.kt` | CCT 固定浏览器包以免自家链接被劫回;`openExternally` 有意交给对应 App |
| **API key** | `local.properties` → `manifestPlaceholders` | key 必然进入 APK,**真正的防线是 Cloud Console 的包名 + SHA-1 + API 限制** |
| **相册写入** | `support/MediaStoreSaver.kt` | MediaStore + `IS_PENDING` 协议,固定写入 `Pictures/Anitabi`;minSdk 29 起无需存储权限 |
| **用户数据文件** | `data/PilgrimageLog.kt` | 巡礼记录 `files/pilgrimage_log.json`:只含地标 id / 作品 id / 时间戳,不含坐标;原子写;损坏时改名保留 |
| **定位** | `data/LocationProvider.kt` | 仅前台、粗精度优先、无后台定位、不上传坐标 |
| **日志** | 全库 4 条 | 不含坐标、URL 参数或用户数据。请保持 |

已核实的干净项:无分析/崩溃 SDK、无设备标识符采集、剪贴板只写不读、仓库内无任何二进制
(唯一的 jar 是 gradle-wrapper)、无账号体系与鉴权逻辑。

## 密钥处理

- `local.properties`(含 `MAPS_API_KEY` 与 `RELEASE_*` 签名配置)已被 `.gitignore` 排除,**不得提交**。
- 提交前可自查:`git ls-files | grep -iE "local\.properties$|keystore|\.jks|\.p12|\.pem"`
  应为空(`local.properties.example` 是模板,不含真实值)。
- 若曾泄露 key:**先在 Cloud Console 轮换**,再处理仓库历史。
- release 签名 keystore 与口令**永远不进仓库**;CI 的 release 作业经 GitHub Secrets 取用,只在 `v*` tag 上运行,
  PR(含 fork)跑的 check 作业接触不到任何 secret。

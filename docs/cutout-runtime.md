# AI 抠图运行时的托管

对比拍摄的「角色抠图」有三档实现,失败时逐级降档:

| 档 | 实现 | 速度 | 需要的成果物 |
|---|---|---|---|
| HTP | ISNet + ONNX Runtime + Qualcomm NPU | 20〜30ms | ORT + QNN 运行库 + 该 HTP 世代的 context binary |
| CPU | ISNet + ONNX Runtime CPU EP | 约 0.7s | ORT + `isnet_w8a8.onnx` |
| ML Kit | Google ML Kit Subject Segmentation | —— | 无(随 Play 服务下发) |

**ISNet 档默认关闭**,需要用户在应用内「关于」页手动打开实验开关,进入对比拍摄时才会
下载模型(且仅在非计费网络下载)。关掉开关或没有配 `CUTOUT_MANIFEST_URL` 时,抠图走 ML Kit,
其余功能完全不受影响。

APK 里**不打包**任何 ORT 本体或 Qualcomm 二进制:`app/build.gradle.kts` 把
`libonnxruntime.so` 从 jniLibs 排除,QNN 只作为 `androidTest` 依赖存在。运行时由
`CutoutRuntimeStore` 下载到应用私有目录,经**大小 + SHA-256 + `setReadOnly` + `.ok` 标记**
校验后用绝对路径 `System.load`。

## 官方托管的内容

只托管两个许可证干净的文件:

| 文件 | 许可 | 来源 |
|---|---|---|
| `libonnxruntime.so` | MIT | `onnxruntime-android-qnn-<ver>.aar` 的 `jni/arm64-v8a/` |
| `isnet_w8a8.onnx` | Apache-2.0 | [SkyTNT/anime-segmentation](https://github.com/SkyTNT/anime-segmentation) 的量化产物 |

**HTP 档所需的 `libQnn*.so` 与各世代 context binary 不在官方托管范围内**(原因见下节)。
`CutoutManifest.requiredArtifacts` 在成果物缺失时返回 null,`CutoutEngine.prepare()` 会
自动降到 CPU 档 —— 因此官方 manifest 只描述 CPU 档,HTP 设备上也会走 CPU 档。

## Qualcomm QNN / QAIRT 的再分发边界

自建服务器提供 HTP 档之前,**必须先核对你实际下载的那个 QAIRT/QNN SDK 目录里的
`LICENSE.pdf`** —— 不同下载渠道与版本的条款可能不同,网上流传的旧 EULA 不作数。

按当前公开的 Qualcomm AI Stack License,授权是「以 object code 形式、**且在集成进你的
软件应用的前提下**分发」,**没有**授予 standalone 分发或再许可的权利。落到实践上:

| 做法 | 一般是否允许 |
|---|---|
| 把 `libQnnHtp.so` / `libQnnSystem.so` / `Stub` / `Skel` 放进 APK/AAB 的 `jniLibs/arm64-v8a` | ✅ 属于「集成进应用」 |
| 在 GitHub Releases 发布**包含**这些 `.so` 的 APK | ✅ 同上 |
| 商业收费 App 中包含这些运行库 | ✅ 条款是免版税的 |
| 把 `libQnn*.so` 单独打包成下载包供人下载 | ❌ standalone redistribution |
| 建一个仓库**仅**托管 Qualcomm `.so` | ❌ 同上 |
| 上传整个 SDK / `$QNN_SDK_ROOT/lib` | ❌ |
| 把 Qualcomm 二进制当作本项目开源许可证的一部分重新授权 | ❌ |

**本 App 的架构是运行时单独下载,而不是随 APK 内嵌** —— 也就是说,把 QNN 运行库挂成
Release 资产落在的是上表的 ❌ 那一行,不是 ✅ 那一行。这就是官方托管不提供 HTP 档的原因。

License 还明确禁止对 Qualcomm 提供的 object code 做反编译/反汇编,禁止删改其版权与
专有权利声明。Qualcomm 软件**不构成对本项目的贡献**,不会因为放进一个开源仓库就变成
该仓库的许可证。

若你确实需要 HTP 档,可行的做法是把 QNN 运行库**内嵌进你自己构建的 APK**(需要相应改动
`build.gradle.kts` 的 jniLibs 排除规则与 `CutoutRuntimeStore` 的加载路径),并在你的
`THIRD_PARTY_NOTICES` 里声明它们受 QAIRT SDK License 管辖、被排除在你的开源许可证之外。

## 自建服务器

```bash
python3 tools/build_cutout_manifest.py <dist_dir> --base-url https://your.host/path/
```

`<dist_dir>` 里的文件名是**固定契约**(与 `CutoutManifest.kt` 一致):

```
libonnxruntime.so            必需
isnet_w8a8.onnx              CPU 档
libQnnSystem.so, libQnnHtp.so, libQnnHtpV<arch>Skel.so, libQnnHtpV<arch>Stub.so
isnet_w8a16_ctx_<arch>.onnx  HTP 档(按 HTP 世代各一份:v73/v75/v79/v81)
```

脚本会算出每个文件的 SHA-256 与大小并写进 `manifest.json`。`url` 可以是绝对地址,
也可以相对于 manifest 本身。

**版本一致性是硬约束**:应用侧 ORT 的 Java 版本(`gradle/libs.versions.toml` 的
`onnxruntime`)与你下发的 `libonnxruntime.so` 版本**必须严格一致**,否则运行时崩溃。
升级 ORT 依赖时必须同步重新生成并上传 dist。

生产环境必须用 **https**(`CutoutEngine` 显式强制;release 下 targetSdk 36 的明文拦截只是兜底)。
本地调试可以:

```bash
adb reverse tcp:8765 tcp:8765
python3 -m http.server 8765     # 在 dist 目录里
# local.properties: CUTOUT_MANIFEST_URL=http://127.0.0.1:8765/manifest.json
```

debug 变体的 `usesCleartextTraffic="true"` 就是为这条链路准备的。

## 已知局限

**manifest 自身没有签名。** 服务端失陷时,攻击者可以同时替换 hash 与二进制,
校验链无法察觉。仓库里提交了一份当前 dist 的 `manifest.json` 作为可审计基线,
但这只是缓解,不是解决。加固手段是给 manifest 签名,或把已知 hash 固定进仓库(见
[maintenance.md](maintenance.md) 的「已知局限」)。

# 贡献指南 / Contributing

Thanks for your interest. The project's technical documentation is written in Chinese; the short
version in English: build instructions live in [docs/development.md](docs/development.md), run
`./gradlew :app:testDebugUnitTest :app:lintDebug` before opening a PR, keep the three locales of
every string in sync, and please open an issue first for larger changes or new dependencies.
Issues and PRs in English or Chinese are both welcome.

本文件描述**当前仓库实际存在的**流程与约定,不虚构任何组织流程。

## 开发环境、构建与测试

见 [docs/development.md](docs/development.md) —— 环境要求、`local.properties` 配置、
构建与测试命令、lint 门禁、CI、调试开关都在那里,本文件不再重复一份以免两处漂移。

提交前请至少跑:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

`lintDebug` 配了 `abortOnError = true`,CI 会在每个 PR 上跑它 —— 请确认你的改动**没有新增 error**。

代码风格由 [.editorconfig](.editorconfig) 约束(4 空格、120 列、LF)。仓库**没有**自动格式化
或静态检查工具,请在提交前自行对齐周边代码风格。

## 仓库结构

详见 [docs/codebase-guide.md](docs/codebase-guide.md)。速记:

```
app/src/main/java/cn/anitabi/map/
  app/        入口、服务定位器、导航状态、根 UI
  data/       数据加载、内存 store、巡礼记录、定位、模型、搜索(纯 Kotlin)
  map/engine/ 纯 Kotlin 地图引擎(可 JVM 单测)
  map/google/ Google Maps SDK 适配与 marker 渲染
  ui/         Compose 界面(sheet / home / work / point / log / image / scene)
  support/    深链、外链、图片兜底、原子写等无状态工具
  theme/      自绘调色板与主题
tools/        离线脚本(启动图标、抠图 manifest、搜索字符表)
```

## 编码约定(从现有代码归纳)

- **注释用中文**,解释"为什么"而不是"做了什么";涉及真机验证结论的地方保留了踩坑记录 ——
  这些注释是改动的前提条件,**不要在重构时顺手删掉**。
- 用户可见文本一律走 `stringResource`,三个 locale(`values` / `values-zh` / `values-ja`)
  的键集必须保持一致。所有文案都是本项目**自己写的**:成句的段落与提示放 `strings_app.xml`,
  通用短标签放 `strings_anitabi.xml`;**不要从 anitabi 官方 iOS 版 / 网页版抄句子**(许可证前提)。
- 颜色取自 `LocalAnitabiPalette`(自绘调色板),不用 `MaterialTheme.colorScheme` —— 这是
  与 iOS 版视觉对齐的有意选择。
- 协程里捕获宽异常时**必须先重抛 `CancellationException`**(仓库里已有两处因此产生过 bug)。
- Compose 中高频变化的值(拖拽偏移、方位角、透明度)用 lambda / `graphicsLayer` 在布局或绘制
  相位读取,不要在组合期读值。
- `map/engine/` 与 `data/search/` 必须保持纯 Kotlin(不 import `android.*`),它们的正确性
  完全靠 JVM 单测保证。

## 提交与 PR

- 标题:`区域: 一句话`(中英皆可),正文说明**根因与取舍**,而不是罗列改了哪些行。
- 从 `main` 拉分支,PR 回 `main`。
- PR 请包含:改动动机、验证方式(命令 + 设备/系统版本)、以及受影响的既有行为。
  改动 `ui/sheet/`、`map/` 或抠图链路时,请附真机验证说明 —— 这几处的模拟器结论不充分。

## 提交 Bug 修复

```
Issue → 复现 → 定位根因 → 补回归测试 → 实施修复 → 跑相关测试 → 提 PR
```

- 只改症状不查根因的补丁不会被接受(例:某个值偶尔为空就加个空判断,而不解释它为何为空)。
- 能在 JVM 层复现的逻辑,请在 `app/src/test/` 补测试。纯 UI/设备相关的问题无法单测时,
  请在 PR 里写明真机验证步骤与结果。

## 提交新功能

- 先开 issue 说明用途与范围,尤其涉及新增依赖时(见 [AGENTS.md](AGENTS.md) 的依赖规则)。
- 本项目**有意不使用** ViewModel、DI 框架和 NavController,详见 [docs/architecture.md](docs/architecture.md)
  的「架构约束」。以"标准架构"为由的重构 PR 请先讨论。
- 新增用户可见文本时,三个 locale 一并补齐。

## 许可

提交的代码默认以本仓库的 [Apache License 2.0](LICENSE) 授权。

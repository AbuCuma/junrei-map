# Developer Documentation / 开发者文档

This directory is for developers, contributors and future maintainers. If you only want to use the
app, the [README](../README_en.md) (English) is all you need.

The technical documents below are written in **Chinese** (the language of the code comments). The
essentials in English: JDK 17, Android Gradle Plugin 9.3, `cp local.properties.example local.properties`
and fill in `MAPS_API_KEY`, then `./gradlew assembleDebug`. Tests: `./gradlew :app:testDebugUnitTest :app:lintDebug`.

| Document | What it covers |
|---|---|
| [development.md](development.md) | Environment, `local.properties`, build / test / lint commands, CI, debug switches, real-device expectations |
| [architecture.md](architecture.md) | Overall shape, component responsibilities, data flow, state ownership, persistence, networking, constraints, known debt |
| [codebase-guide.md](codebase-guide.md) | "I want to change X — where is it?" map of the source tree |
| [main-flows.md](main-flows.md) | Call chains of the main user flows (startup, tap a point, search, deep link, camera, pilgrimage log …) |
| [data-sources.md](data-sources.md) | How the anitabi.cn data and image endpoints are used (hosts, size plans, fallback, User-Agent) |
| [cutout-runtime.md](cutout-runtime.md) | The downloadable AI segmentation runtime: hosting, verification chain, Qualcomm redistribution boundary |
| [maintenance.md](maintenance.md) | Dependency policy, release checklist, bug workflow, known limitations |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | Coding conventions and PR expectations |
| [../SECURITY.md](../SECURITY.md) | Reporting channel and the security-sensitive areas of the code |
| [../AGENTS.md](../AGENTS.md) | Repository rules for AI coding agents (derived from the actual code) |

---

面向开发者、贡献者与未来的维护者。只想使用 App 的话看 [README.md](../README.md) 就够了。

推荐阅读顺序:**development.md**(把项目跑起来)→ **codebase-guide.md**(找到要改的地方)→
**architecture.md**(理解为什么是这样)→ 需要时再看其余专题。

| 文档 | 内容 |
|---|---|
| [development.md](development.md) | 环境要求、`local.properties` 配置、构建 / 测试 / lint 命令、CI、调试开关、真机验证要求 |
| [architecture.md](architecture.md) | 总体形态、组件职责、数据流、状态归属、持久化、网络、架构约束与已确认的技术债 |
| [codebase-guide.md](codebase-guide.md) | 「我要改 X 该去哪」的代码地图 |
| [main-flows.md](main-flows.md) | 主要用户流程的调用链(启动、点选地标、搜索、深链、对比拍摄、巡礼记录 …) |
| [data-sources.md](data-sources.md) | 数据与图片接口的使用方式(host、尺寸档位、兜底、User-Agent) |
| [cutout-runtime.md](cutout-runtime.md) | AI 抠图运行时:托管、校验链、Qualcomm 再分发边界 |
| [maintenance.md](maintenance.md) | 依赖策略、发布检查清单、bug 流程、已知局限 |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 编码约定与 PR 期望 |
| [../SECURITY.md](../SECURITY.md) | 漏洞报告渠道与安全敏感区域 |
| [../AGENTS.md](../AGENTS.md) | 给 AI 编码代理的仓库规则(由实际代码归纳) |

另有 [`../cutout-dist/`](../cutout-dist/README.md):官方托管的抠图运行时 manifest 的审计基线副本。

# 数据与图片接口的使用方式

本 App 与 anitabi.cn 的 web / iOS 客户端使用同一套数据管线。本页记录用了哪些端点、怎么用、以及为什么这么用。

## 数据

- 数据来自 web 版同款管线:`/d/g.json` + `g0…g6` 分片
  (`ww.anitabi.cn` / `w.junreimap.com`,带与 web 端 `HS()` 相同的 `?d=` 缓存参数)。
- 官方[开放 API](https://github.com/anitabi/anitabi.cn-document/blob/main/api.md)
  只有单作品粒度,无法支撑全量地图,故与 web/iOS 客户端一致使用这套管线。
- **从不请求主域 `anitabi.cn`**(官方 API 文档明令)。
- 全量数据落盘缓存,冷启动直接读本地;服务端 `modified` 没变就跳过详情重下。

## 图片

- web 客户端按自身 origin 选图片 host —— `www.anitabi.cn` 一组用 `img-tc.anitabi.cn`,
  而 `ww.anitabi.cn` / `w.junreimap.com` 一组(**正是本 App 的数据 origin**)用 `image-anitabi.magiconch.com`。
  本 App 跟随后者。该 host 由 magiconch.com(anitabi 页脚链接的运营方、同一维护者)提供。
- 官方文档指定的 `image.anitabi.cn` 保留为兜底(2026-08 实测其源站对 CDN 不可达,全部 525)。
- **尺寸纪律**:缩略图 `?plan=h160`;卡片剧照与全屏查看 `?plan=h360`;
  **完整尺寸只用于非展示用途**(保存到相册、对比拍摄的抠图参考图),遵循文档
  「不建议在任何展示界面使用完整尺寸截图」。
- 取不到时逐请求兜底(`support/ImageHostFallback.kt`):换另一个 host → 换另一档尺寸,
  最多 3 次;**404 只换 host 不换尺寸**(同路径换尺寸对不存在的对象必然同样失败)。
  兜底成功的响应由 Coil 以最初请求的 URL 为键缓存,因此是每张图一次性成本而非常态。
- 主 host 对 `points/`、`user/`、`ptheme/`、`bangumi/`、`icon/` 五个命名空间实测可用;
  文档 host `image.anitabi.cn` 只收录 `points/` 与 `bangumi/`,所以两者的顺序不要对调
  (见 [maintenance.md](maintenance.md))。

## User-Agent

请求带标识性 User-Agent:`AnitabiMap-Android/<versionName> (Android <SDK_INT>)`。

依据是官方文档仓库 [issue #86](https://github.com/anitabi/anitabi.cn-document/issues/86)
里维护者的说明:「正确配置 User-Agent、人类访问频率的情况很难触发超限」。
若被限制,应用内数据/图片会大面积失败并显示重试入口 —— 请勿高频重试。

## 归属与许可

- 地标卡片展示 `origin` 出典并支持 `originLink` 跳转。
- 应用内展示 CC BY-NC-SA 4.0 与权利方声明。
- 巡礼数据与截图归 anitabi.cn 社区及原作品权利方所有,见 README 的「许可」一节。

# cutout-dist

官方托管的抠图运行时清单**基线副本**,用于审计:任何人都可以拿它和
[实际下发的 manifest](https://github.com/AbuCuma/anitabi-cutout-runtime/releases/download/v1/manifest.json)
逐字比对,确认下发内容没有被悄悄替换。

manifest 自身没有签名(已知局限,见 [../docs/maintenance.md](../docs/maintenance.md)),
所以这份 git 里的副本就是那条"已知 hash"的记录。

二进制本身不在仓库里 —— 它们是
[AbuCuma/anitabi-cutout-runtime](https://github.com/AbuCuma/anitabi-cutout-runtime) 的 release 资产。
托管方式与 Qualcomm 再分发边界见 [../docs/cutout-runtime.md](../docs/cutout-runtime.md)。

#!/usr/bin/env python3
"""汉字异体折叠表的生成器（日文新字体 / 繁体 → 简体）。

搜索需要把「東京駅」和「东京站」折到同一个字形域上，否则简体输入永远命中不了
日文地标名。实测（数据分片 g0，22350 个地标）：地标日文名含汉字的有 19405 条，
其中 12712 条（65%）至少含一个简体写法不同的字，今天用简体输入逐字命中不了。

为什么不用 android.icu.text.Transliterator：
  1. 它是 android.*，本仓库的 100+ 个 JVM 单测一行都覆盖不到它；
  2. ICU 根本没有「新字体 → 简体」这条变换 —— 満 / 滿 / 满 是三个独立码位，
     任何串接都要么漏掉新字体那一跳，要么落在繁体上；
  3. Android 官方文档明确警告可用 ID 集合在不同版本 / 厂商 ROM 上不稳定。
生成表在所有机型上行为完全一致，且可以写单测。

用法：
    # OpenCC 的字典目录（Apache-2.0）。脚本只读本地文件，不联网。
    git clone --depth 1 https://github.com/BYVoid/OpenCC /tmp/opencc
    python3 tools/build_han_fold_table.py /tmp/opencc/data/dictionary

输出覆盖 app/src/main/java/cn/anitabi/map/data/search/HanFoldTable.kt。
该文件是生成物，**不要手改**。
"""

import sys
import subprocess
from pathlib import Path

# 新字体 → 繁体。**方向就是这个,不要反转** —— 文件里写的是 `満\t滿`。
# 反转过一次,结果 満/総/鉄/沢/広/発 一个都没折叠,而繁体那半看着是好的,很容易蒙混过关。
DENSE = range(0x4E00, 0xA000)  # 汉字统一表意文字基本区

SHINJITAI = "JPShinjitaiCharacters.txt"
# 繁体 → 简体
TRAD_SIMP = "TSCharacters.txt"

OUT = Path(__file__).resolve().parent.parent / (
    "app/src/main/java/cn/anitabi/map/data/search/HanFoldTable.kt"
)


def load(path: Path) -> dict[str, str]:
    """OpenCC 字典：`key\tvalue [value...]`，取第一个**不等于 key** 的值。

    `䀋 → 䀋 鹽` 这类自指排在最前的条目若直接取第一个值就等于没映射。
    """
    table: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, _, rest = line.partition("\t")
        if not is_bmp_char(key):
            continue  # 词组条目，以及 BMP 外的扩展区字符
        pick = next((v for v in rest.split() if is_bmp_char(v) and v != key), None)
        if pick:
            table[key] = pick
    return table


def is_bmp_char(s: str) -> bool:
    """单个基本多文种平面字符。

    **BMP 外的字符必须排除**：它们在 Python 里是 1 个码点，在 Kotlin 的 `String`
    里却占 2 个 `Char`（代理对）。混进去的话生成的两条平行字面量长度对不上，
    整张表从那一条起全部错位 —— 表现为 `東` 折成 `恽` 这种看似随机的乱码。
    """
    return len(s) == 1 and ord(s) <= 0xFFFF


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    shin2trad = load(src / SHINJITAI)
    trad2simp = load(src / TRAD_SIMP)

    def fold(ch: str) -> str:
        """复合到不动点。新字体先跳繁体，再跳简体。"""
        seen: set[str] = set()
        while ch not in seen:
            seen.add(ch)
            nxt = shin2trad.get(ch) or trad2simp.get(ch)
            if not nxt or nxt == ch:
                break
            ch = nxt
        return ch

    table = {c: fold(c) for c in set(shin2trad) | set(trad2simp)}
    table = {k: v for k, v in table.items() if k != v}

    # 幂等是 TextFold 依赖的性质：折叠只跑一遍，不动点必须已经在表里。
    broken = [c for c in table if fold(fold(c)) != fold(c)]
    if broken:
        print(f"表不幂等：{broken[:10]}", file=sys.stderr)
        return 1

    # 汉字统一表意文字基本区走**直接下标**：真机上折叠是索引构建里最重的一步，
    # 而绝大多数字都落在这一区，省掉每字一次二分查找（约 12 次比较）很值。
    # 区外（扩展 A、兼容表意文字）仍走二分查找的稀疏表 —— 它们在数据里几乎不出现。
    dense = "".join(table.get(chr(cp), "\0") for cp in DENSE)
    assert len(dense) == len(DENSE), "稠密表与码位区间不对齐"

    sparse = {k: v for k, v in table.items() if ord(k) not in DENSE}
    keys = sorted(sparse)
    values = [sparse[c] for c in keys]

    # 两条字面量必须逐 Char 对应。BMP 过滤之后这本该恒真，但错位一旦发生就是静默的
    # （表看着是满的，映射全是错的），所以在这里硬断言。
    assert len("".join(keys)) == len(keys) == len("".join(values)), "字面量与条目数不对齐"

    try:
        rev = subprocess.run(
            ["git", "-C", str(src), "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        rev = "unknown"

    OUT.write_text(render(dense, keys, values, rev, len(table)), encoding="utf-8")
    print(f"{OUT.relative_to(Path.cwd())}: {len(table)} 条（稠密 {len(DENSE)} 项 + 稀疏 {len(keys)} 条，OpenCC {rev}）")
    return 0


def chunk(s: str, width: int = 72) -> str:
    """按 .editorconfig 的 120 列拆成多行字面量拼接。"""
    lines = [s[i:i + width] for i in range(0, len(s), width)]
    return " +\n".join(f'        "{escape(line)}"' for line in lines)


def escape(s: str) -> str:
    """稠密表里的空位是 U+0000，必须转义才能落进 Kotlin 源码。"""
    return "".join(
        c if c.isprintable() and c not in '"\\$' and ord(c) >= 0x20
        else f"\\u{ord(c):04x}"
        for c in s
    )


def render(dense: str, keys: list[str], values: list[str], rev: str, total: int) -> str:
    return f'''package cn.anitabi.map.data.search

/**
 * 汉字异体折叠表：日文新字体 / 繁体 → 简体。共 {total} 条。
 *
 * **生成物，不要手改。** 由 `tools/build_han_fold_table.py` 从 OpenCC
 * （Apache-2.0，commit `{rev}`）的 `JPShinjitaiCharacters.txt` + `TSCharacters.txt`
 * 复合而来，重新生成见该脚本的文档字符串。
 *
 * 折叠是有损的（干 / 乾 / 幹 都折到干），只增召回；精度由 [SearchIndex] 的相关度排序吸收。
 * 这也是为什么打分必须先于折叠落地 —— 没有排序的折叠会让搜索肉眼可见地变差。
 *
 * 日文独有、汉语里没有对应的字（峠 辻 込 榊）不在表里，原样通过 —— 这是对的，
 * 简体输入本来也打不出它们。
 */
internal object HanFoldTable {{

    /** 基本区的稠密表，下标即 `码位 − 0x4E00`；U+0000 表示不折叠。 */
    private val dense: CharArray = DENSE.toCharArray()
    private val keys: CharArray = KEYS.toCharArray()
    private val values: CharArray = VALUES.toCharArray()

    /** 表外字符原样返回。 */
    fun fold(ch: Char): Char {{
        // 汉字统一表意文字之前的码位(拉丁/数字/假名/标点)直接短路 ——
        // 折叠调用里九成以上走这一支。
        if (ch < '\\u3400') return ch
        // **下界不能省**:扩展 A(U+3400..U+4DBF)比基本区还低,漏掉它会让下标变成负数。
        if (ch >= '\\u4e00' && ch < '\\ua000') {{
            // 基本区:一次数组读,不做二分查找。折叠是索引构建里最重的一步。
            val mapped = dense[ch - '\\u4e00']
            return if (mapped == '\\u0000') ch else mapped
        }}
        val i = keys.binarySearch(ch)
        return if (i >= 0) values[i] else ch
    }}

    /** 供单测遍历整表用。 */
    internal val entries: Sequence<Pair<Char, Char>>
        get() = dense.indices.asSequence()
            .filter {{ dense[it] != '\\u0000' }}
            .map {{ ('\\u4e00' + it) to dense[it] }} +
            keys.indices.asSequence().map {{ keys[it] to values[it] }}

    /** 供单测校验 binarySearch 的前提（只有稀疏表用二分查找）。 */
    internal val sparseKeys: CharArray get() = keys

    // 稠密表，覆盖 U+4E00..U+9FFF。
    private const val DENSE: String =
{chunk(dense)}

    // 区外条目，按 key 升序（binarySearch 依赖）。两串等长且逐字对应。
    private const val KEYS: String =
{chunk("".join(keys))}

    private const val VALUES: String =
{chunk("".join(values))}
}}
'''


if __name__ == "__main__":
    raise SystemExit(main())

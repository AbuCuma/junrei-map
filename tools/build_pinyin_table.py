#!/usr/bin/env python3
"""汉字 → 无声调拼音表的生成器。

让 `gudu yaogun` 和首字母 `gdyg` 都能找到《孤独摇滚》—— 中文用户在拉丁键盘上的
常见输入习惯。上游数据自己也印证了这一点：作品别名字段 idx14 里就有 `qlhw`
（千恋万花的拼音首字母）这种人工填的条目。

为什么不用 TinyPinyin：它的正统坐标 com.github.promeg:tinypinyin **不在 Maven
Central 上**（只发过已关停的 jcenter 与 JitPack），Maven Central 上只有一个
2019-12 停更的 fork。给一个即将开源的仓库挂 JitPack 源换一个七年没动的库不划算，
而这张表本来就和汉字异体折叠表同源同做法。

数据源是 Unicode 官方 Unihan 的 kMandarin 字段（Unicode License v3，宽松且
GPL 兼容）。多音字取 kMandarin 的首选读音 —— 搜索场景下召回比精确重要，
`重庆` 会被索引成 `zhongqing` 而不是 `chongqing`，这是已知取舍。

用法：
    curl -O https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
    unzip -o Unihan.zip Unihan_Readings.txt
    python3 tools/build_pinyin_table.py Unihan_Readings.txt

输出覆盖 app/src/main/java/cn/anitabi/map/data/search/PinyinTable.kt。
该文件是生成物，**不要手改**。
"""

import sys
import unicodedata
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / (
    "app/src/main/java/cn/anitabi/map/data/search/PinyinTable.kt"
)

# 汉字统一表意文字基本区。扩展区的字在巡礼数据里不会出现，收进来只是白涨体积。
BMP_CJK = range(0x4E00, 0xA000)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    readings: dict[str, str] = {}
    for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
        if not line.startswith("U+"):
            continue
        code, field, value = line.split("\t", 2)
        if field != "kMandarin":
            continue
        cp = int(code[2:], 16)
        if cp not in BMP_CJK:
            continue
        # 首选读音，去声调（NFD 拆出组合音调符再滤掉 Mn），ü 写作 v。
        syllable = value.split()[0]
        syllable = "".join(
            c for c in unicodedata.normalize("NFD", syllable)
            if unicodedata.category(c) != "Mn"
        ).replace("ü", "v")
        if syllable.isascii() and syllable.isalpha():
            readings[chr(cp)] = syllable.lower()

    syllables = sorted(set(readings.values()))
    slot = {s: i + 1 for i, s in enumerate(syllables)}  # 0 留给「无读音」
    assert len(syllables) + 1 < 0x10000, "音节数超出 Char 下标"

    # **直接下标而非二分查找。** 基本区 0x4E00..0x9FFF 共 20992 个码位，表里有
    # 20924 个 —— 密度 99.7%，稀疏表的那点内存收益换不来每字一次二分查找的代价。
    # 索引构建要跑 5 万条记录，这一处实测差了几十毫秒。
    slots = "".join(chr(slot.get(readings.get(chr(cp)), 0)) for cp in BMP_CJK)
    assert len(slots) == len(BMP_CJK), "下标串与码位区间不对齐"

    OUT.write_text(render(len(readings), syllables, slots), encoding="utf-8")
    print(
        f"{OUT.relative_to(Path.cwd())}: {len(readings)} 字 / {len(syllables)} 音节"
        f"（直接下标表 {len(slots)} 项）"
    )
    return 0


def chunk(s: str, width: int = 72) -> str:
    lines = [s[i:i + width] for i in range(0, len(s), width)]
    return " +\n".join(f'        "{escape(line)}"' for line in lines)


def escape(s: str) -> str:
    """SLOTS 串里全是低位码位，必须转义成 \\uXXXX 才能安全落进 Kotlin 源码。"""
    out = []
    for c in s:
        if c.isprintable() and c not in '"\\$' and ord(c) >= 0x20:
            out.append(c)
        else:
            out.append(f"\\u{ord(c):04x}")
    return "".join(out)


def render(count: int, syllables: list[str], slots: str) -> str:
    return f'''package cn.anitabi.map.data.search

/**
 * 汉字 → 无声调拼音。共 {count} 字 / {len(syllables)} 个音节。
 *
 * **生成物，不要手改。** 由 `tools/build_pinyin_table.py` 从 Unicode 官方 Unihan 的
 * `kMandarin` 字段生成（Unicode License v3），重新生成见该脚本的文档字符串。
 *
 * 多音字取首选读音 —— 搜索场景下召回比精确重要，`重庆` 会被索引成 `zhongqing`。
 * 这是已知取舍，代价是少数多音字要按首选读音才搜得到。
 *
 * 作用在 [TextFold.fold] 的产物上，所以日文汉字经异体折叠后也会被查到读音；
 * 但**只有语种确定为中文的字段**才生成拼音（见 [SearchIndex]）—— 给日文地标名
 * 生成中文读音（`東京駅` → `dongjingyi`）既没意义又让索引凭空翻倍。
 */
internal object PinyinTable {{

    /** 下标即 `码位 − 0x4E00`；0 表示该字没有读音。 */
    private val slots: CharArray = SLOTS.toCharArray()
    // 首项是空位哨兵，对应 slot 0。
    private val syllables: List<String> = listOf("") + SYLLABLES.split(' ')

    /** 表外字符返回 null。 */
    fun of(ch: Char): String? {{
        if (ch < FIRST || ch > LAST) return null
        val slot = slots[ch - FIRST].code
        return if (slot == 0) null else syllables[slot]
    }}

    private const val FIRST = '\\u4e00'
    private const val LAST = '\\u9fff'

    private const val SLOTS: String =
{chunk(slots)}

    private const val SYLLABLES: String =
{chunk(" ".join(syllables))}
}}
'''


if __name__ == "__main__":
    raise SystemExit(main())

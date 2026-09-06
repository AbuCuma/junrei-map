package cn.anitabi.map.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 归一化管线。表驱动，一条规则一个用例。 */
class TextFoldTest {

    private fun assertFolds(vararg cases: Pair<String, String>) {
        for ((raw, expected) in cases) {
            assertEquals("fold(\"$raw\")", expected, TextFold.fold(raw))
        }
    }

    @Test
    fun widthAndCaseAreNormalized() {
        assertFolds(
            "ＢＯＣＣＨＩ" to "bocchi",
            "BOCCHI" to "bocchi",
            "１２３" to "123",
            "ﬁle" to "file",
        )
    }

    @Test
    fun punctuationAndWhitespaceAreStripped() {
        assertFolds(
            "K-ON!!" to "kon",
            "ぼっち・ざ・ろっく！" to "ぼっちざろっく",
            "千恋＊万花" to "千恋万花",
            "Re:ゼロ" to "reぜろ",
            // 片假名与长音符一并按各自的规则处理:ホーム → ほむ
            "JR赤羽駅 7番線ホーム" to "jr赤羽驿7番线ほむ",
        )
    }

    /** 真实数据里的名称含零宽空格（`知​々​夫`），必须一并剥掉。 */
    @Test
    fun zeroWidthSpaceIsStripped() {
        assertEquals("知知夫", TextFold.fold("知​々​夫"))
    }

    @Test
    fun katakanaFoldsToHiragana() {
        assertFolds(
            "アマカノ" to "あまかの",
            "ｱﾏｶﾉ" to "あまかの", // 半角:证明 NFKC 跑在假名折叠之前
            "ヴ" to "ゔ",
            "ヽ" to "ゝ",
            "霞ヶ関" to "霞ゖ关",
        )
    }

    /** 长音符是 Lm（修饰字母），isLetterOrDigit 会放行，必须显式剥掉。 */
    @Test
    fun prolongedSoundMarkIsDropped() {
        assertFolds("ラーメン" to "らめん", "ケーキ" to "けき")
    }

    /** 叠字号展开：简体输入者写的是 `代代木`，不是 `代々木`。 */
    @Test
    fun iterationMarkIsExpanded() {
        assertFolds(
            "代々木公園" to "代代木公园",
            "等々力" to "等等力",
            "々" to "", // 开头没有可重复的字,丢弃而非崩溃
        )
    }

    /**
     * 中日互搜的核心：新字体与繁体必须折到**同一个**简体目标。
     *
     * 満 / 滿 / 满 是三个独立码位。任何基于 ICU 的串接方案都会在这里挂 ——
     * ICU 没有「新字体 → 简体」这条变换。
     */
    @Test
    fun shinjitaiAndTraditionalFoldToTheSameSimplifiedForm() {
        for ((a, b, expected) in listOf(
            Triple("満", "滿", "满"),
            Triple("総", "總", "总"),
            Triple("鉄", "鐵", "铁"),
            Triple("沢", "澤", "泽"),
            Triple("広", "廣", "广"),
            Triple("発", "發", "发"),
        )) {
            assertEquals("新字体 $a", expected, TextFold.fold(a))
            assertEquals("繁体 $b", expected, TextFold.fold(b))
        }
    }

    @Test
    fun japaneseNamesFoldIntoTheSimplifiedDomain() {
        assertFolds(
            "東京駅" to "东京驿",
            "UR都市機構" to "ur都市机构",
            "嵐山渡月橋" to "岚山渡月桥",
            "廣島" to "广岛",
            "金沢" to "金泽",
        )
    }

    /** 日文独有的字（峠 辻 込 榊）没有汉语对应形，原样通过。 */
    @Test
    fun japaneseOnlyKanjiPassThrough() {
        assertFolds("峠" to "峠", "辻" to "辻", "込" to "込", "榊" to "榊")
    }

    /** [TextFold] 只折一遍，所以表本身必须已经在不动点上。 */
    @Test
    fun foldIsIdempotentOverTheWholeTable() {
        for ((key, value) in HanFoldTable.entries) {
            assertEquals("fold($key)=$value 不是不动点", value, HanFoldTable.fold(value))
        }
    }

    /** 自映射条目是生成器的 bug（等于没折叠，却白占一个槽）。 */
    @Test
    fun generatedTableHasNoSelfMappings() {
        for ((key, value) in HanFoldTable.entries) {
            assertTrue("自映射条目：$key", key != value)
        }
    }

    /** 只有区外的稀疏表走 binarySearch，它必须有序；基本区是直接下标，无此要求。 */
    @Test
    fun sparseTableIsSorted() {
        val keys = HanFoldTable.sparseKeys
        for (i in 1 until keys.size) {
            assertTrue("稀疏表未按 key 升序", keys[i - 1] < keys[i])
        }
    }

    /**
     * 扩展 A（U+3400..U+4DBF）的码位**比基本区还低**，直接下标会算出负数。
     * 把折叠表改成稠密表那次就漏了这个下界，这条钉住它。
     */
    @Test
    fun extensionAIdeographsDoNotCrashTheDenseLookup() {
        for (code in intArrayOf(0x3400, 0x3500, 0x4DBF, 0xA000, 0xF900, 0xFA2D)) {
            TextFold.fold(code.toChar().toString()) // 不抛即通过
        }
    }

    @Test
    fun blankAndPunctuationOnlyInputsFoldToEmpty() {
        assertFolds("" to "", "   " to "", "!!!" to "", "・" to "", "ー" to "")
        assertEquals("", TextFold.fold(null))
    }

    /** 让跨字段伪命中在结构上不可能发生的那条安全性质。 */
    @Test
    fun fieldSeparatorCanNeverSurviveFolding() {
        assertFalse(TextFold.fold("x${TextFold.FIELD_SEP}y").contains(TextFold.FIELD_SEP))
    }

    /** 空槽必须占位，否则字段下标漂移、打分会取错权重。 */
    @Test
    fun foldFieldsKeepsEmptySlots() {
        val key = TextFold.foldFields(listOf("a", null, "b"))
        assertEquals("a${TextFold.FIELD_SEP}${TextFold.FIELD_SEP}b", key)
    }
}

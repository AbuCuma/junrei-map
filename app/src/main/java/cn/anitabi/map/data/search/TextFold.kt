package cn.anitabi.map.data.search

import java.text.Normalizer

/**
 * 索引侧与查询侧**唯一**的文本归一化入口。
 *
 * 两侧必须走同一条管线 —— 分开写的话迟早有人只改一边，而这类不对称的 bug
 * 在搜索里表现为「有的词能搜到有的搜不到」，极难定位。
 *
 * 管线顺序不可换：
 * ```
 * 1. NFKC        全角→半角、半角片假名→全角、㈱①ﬁ 等兼容字符归位
 * 2. lowercase   拉丁大小写
 * 3. 逐字符      叠字号展开 / 分隔符剥除 / 片假名→平假名 / 汉字异体→简体
 * ```
 * NFKC 必须在最前：半角片假名 `ｱ` 要先变成 `ア`，第 3 步的片假名折叠才认得它。
 *
 * 折叠后的域是「小写拉丁 + 数字 + 平假名 + 简体汉字」。中英日三种写法
 * 落到同一个域上，这就是跨语种互搜成立的全部原理。
 */
object TextFold {

    /**
     * 字段分隔符。
     *
     * [fold] 会剥掉一切标点与空白，所以折叠后的串里**唯一**可能出现的边界就是它，
     * 打分时据此判断「命中落在哪个字段」「是不是字段前缀」「是不是整字段相等」，
     * 不需要任何额外的偏移数组。
     *
     * 它自身也会被 [fold] 丢弃（不是字母也不是数字），因此查询串不可能含有它 ——
     * 跨字段的伪命中（把上一字段末尾和下一字段开头连起来当成一次命中）
     * 在结构上不可能发生。
     */
    const val FIELD_SEP = '\u0001'

    /** 汉字叠字号。`代々木` → `代代木`，简体输入者写的就是后者。 */
    private const val ITERATION_MARK = '々'

    /**
     * 片假名 → 平假名的偏移。两个区块逐字对齐，所以是纯算术，不需要表。
     * `ア`(U+30A2) − 0x60 = `あ`(U+3042)。
     */
    private const val KANA_OFFSET = 0x60

    fun fold(raw: String?): String {
        val s = raw ?: return ""
        if (s.isEmpty()) return ""

        // isNormalized 是廉价快检 —— 数据里九成以上的字段本来就是 NFKC,
        // 跳过一次全串重建。5 万条量级上这是几十毫秒的差别,不是可有可无的微优化。
        val nfkc = if (Normalizer.isNormalized(s, Normalizer.Form.NFKC)) {
            s
        } else {
            Normalizer.normalize(s, Normalizer.Form.NFKC)
        }
        // Kotlin 的无参 lowercase() 已是 ROOT 语义(土耳其语的 I 不会出问题)。
        // **不要**"顺手修"成 lowercase(Locale.getDefault())。
        val lower = nfkc.lowercase()

        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            when {
                ch == ITERATION_MARK -> sb.lastOrNull()?.let(sb::append)
                isSeparator(ch) -> Unit
                // ァ..ヶ 与 ヽヾ 两段与平假名逐字对齐
                ch in 'ァ'..'ヶ' -> sb.append(ch - KANA_OFFSET)
                ch == 'ヽ' || ch == 'ヾ' -> sb.append(ch - KANA_OFFSET)
                else -> sb.append(HanFoldTable.fold(ch))
            }
        }
        return sb.toString()
    }

    /**
     * 把若干字段折叠后拼成一条 key。
     *
     * **空槽必须保留**（`null` 也占一个位置），否则字段下标会随数据内容漂移，
     * 打分时按下标取权重就会取错。
     */
    fun foldFields(fields: List<String?>): String = joinFields(fields.map(::fold))

    /** 把**已折叠**的字段拼成 key。转写字段是从别处算出来的，不能再折一遍。 */
    fun joinFields(folded: List<String>): String = folded.joinToString(FIELD_SEP.toString())

    /**
     * 空白、标点、以及长音符。
     *
     * 剥掉它们的理由是用户输入时几乎必然省略或写错：`K-ON!!` 会被打成 `kon`，
     * `ぼっち・ざ・ろっく` 会被打成 `ぼっちざろっく`，`ラーメン` 会被打成 `らめん`。
     * 代价是 `K-` 这种带标点的前缀不再能命中，以及 `ー` 本身不可搜 —— 都可接受。
     */
    private fun isSeparator(ch: Char): Boolean = when {
        // 长音符必须显式列出:它在 Unicode 里是 Lm(修饰字母),isLetterOrDigit 会放行。
        ch == 'ー' -> true
        else -> !ch.isLetterOrDigit()
    }
}

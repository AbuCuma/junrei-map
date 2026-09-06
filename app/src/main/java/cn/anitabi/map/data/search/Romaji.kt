package cn.anitabi.map.data.search

/**
 * 平假名 → 罗马字。作用在 [TextFold.fold] 的产物上（片假名此前已折成平假名）。
 *
 * 假名是封闭集合，所以这是一张写死的表，不需要任何依赖，也不需要词典。
 *
 * **能力边界（重要）**：它只转假名。汉字没有词典就读不出音 —— `渋谷` 转不出
 * `shibuya`，那需要 MeCab 一类的形态素解析器和 10MB 级词典，不在范围内。
 * 而上游数据里**地标完全没有英文名**，所以只会英文的用户仍然搜不到纯汉字地标。
 * 这是已知且不打算修的局限。
 *
 * 覆盖面：实测地标名里 9041 条含假名（约 41%），作品的日文简称 `tAbbr` 有 631 部
 * （41%）—— 罗马字索引让这些条目能从拉丁键盘直接命中。
 *
 * 采用训令式与平文式的交集偏平文（`し`→`shi`、`つ`→`tsu`、`ふ`→`fu`），因为
 * 用户凭印象打出来的更接近平文式。长音一律不写（`ラーメン` 在折叠阶段就已经丢了 `ー`）。
 */
internal object Romaji {

    /** 转不出任何音（没有假名）时返回空串，调用方据此跳过这个字段。 */
    fun of(folded: String): String {
        if (folded.none { it in 'ぁ'..'ゖ' }) return ""

        val sb = StringBuilder(folded.length * 2)
        var i = 0
        while (i < folded.length) {
            val ch = folded[i]

            // 促音「っ」:重复下一个音节的首辅音(きっぷ → kippu)。
            if (ch == 'っ') {
                val next = romajiAt(folded, i + 1)
                val head = next.second.firstOrNull()
                if (head != null && head !in "aiueo") sb.append(head)
                i++
                continue
            }
            // 拨音「ん」:唇音前作 m(しんぶん → shinbun,但 さんぽ → sampo 这类差异
            // 对搜索无意义,统一用 n —— 用户两种都可能打,n 是更常见的写法)。
            val (consumed, roman) = romajiAt(folded, i)
            if (consumed == 0) {
                // 非假名(汉字/拉丁/数字)原样带过,这样「新宿3丁目」不会断成两截。
                sb.append(ch)
                i++
            } else {
                sb.append(roman)
                i += consumed
            }
        }
        return sb.toString()
    }

    /** @return 消耗的字符数（0＝该位置不是假名）与对应罗马字。 */
    private fun romajiAt(s: String, at: Int): Pair<Int, String> {
        if (at >= s.length) return 0 to ""
        // 拗音优先:きゃ 必须先于 き 匹配,否则会转成 kiya。
        // **不要**用 s.substring(at, at + 2) 去查 Map —— 那会给每个字符位置都分配一个
        // 两字的 String,真机上光这一处就占了索引构建的四分之一。
        if (at + 1 < s.length) {
            val small = when (s[at + 1]) {
                'ゃ' -> 0; 'ゅ' -> 1; 'ょ' -> 2
                else -> -1
            }
            if (small >= 0) {
                yoonStem(s[at])?.let { return 2 to it + YOON_VOWELS[small] }
            }
        }
        base(s[at])?.let { return 1 to it }
        return 0 to ""
    }

    private val YOON_VOWELS = arrayOf("a", "u", "o")

    /** 拗音的词干。词干本身已把差异吃掉：き→ky 得 kya，し→sh 得 sha，じ→j 得 ja。 */
    private fun yoonStem(ch: Char): String? = when (ch) {
        'き' -> "ky"; 'ぎ' -> "gy"; 'し' -> "sh"; 'じ' -> "j"; 'ち' -> "ch"; 'ぢ' -> "j"
        'に' -> "ny"; 'ひ' -> "hy"; 'び' -> "by"; 'ぴ' -> "py"; 'み' -> "my"; 'り' -> "ry"
        else -> null
    }

    /** 直接下标，避免 Char 装箱与 HashMap 查找。 */
    private fun base(ch: Char): String? =
        if (ch in FIRST_KANA..LAST_KANA) BASE_BY_CODE[ch - FIRST_KANA] else null

    private const val FIRST_KANA = 'ぁ'
    private const val LAST_KANA = 'ゖ'

    /** 单假名。ゐゑ 等历史假名按现代读音处理。 */
    private val BASE: Map<Char, String> = buildMap {
        val rows = listOf(
            "あa いi うu えe おo",
            "かka きki くku けke こko", "がga ぎgi ぐgu げge ごgo",
            "さsa しshi すsu せse そso", "ざza じji ずzu ぜze ぞzo",
            "たta ちchi つtsu てte とto", "だda ぢji づzu でde どdo",
            "なna にni ぬnu ねne のno",
            "はha ひhi ふfu へhe ほho", "ばba びbi ぶbu べbe ぼbo",
            "ぱpa ぴpi ぷpu ぺpe ぽpo",
            "まma みmi むmu めme もmo",
            "やya ゆyu よyo",
            "らra りri るru れre ろro",
            "わwa ゐi ゑe をo んn",
            "ぁa ぃi ぅu ぇe ぉo ゃya ゅyu ょyo ゎwa ゕka ゖke",
            "ゔvu",
        )
        for (row in rows) {
            for (token in row.split(' ')) {
                put(token[0], token.substring(1))
            }
        }
    }

    private val BASE_BY_CODE: Array<String?> =
        arrayOfNulls<String>(LAST_KANA - FIRST_KANA + 1).also { table ->
            for ((ch, roman) in BASE) table[ch - FIRST_KANA] = roman
        }
}

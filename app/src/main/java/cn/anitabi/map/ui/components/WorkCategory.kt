package cn.anitabi.map.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cn.anitabi.map.R

/**
 * 作品分类(`g.json` 的 `cat`,Bangumi 平台分类)的显示文案。数据里是中文,已知的几种按界面语言翻译;
 * `TV` / `OVA` / `WEB` 之类本就是通用写法,原样显示;没见过的值也原样显示(不吞数据)。
 * 偶有脏数据混进来(带 HTML 的署名串),含标签的一律不显示。
 */
@Composable
fun workCategoryLabel(cat: String?): String? {
    val raw = cat?.trim()?.takeIf { it.isNotEmpty() && '<' !in it } ?: return null
    val res = when (raw) {
        "游戏" -> R.string.cat_game
        "剧场版" -> R.string.cat_theatrical
        "电影" -> R.string.cat_film
        "漫画系列" -> R.string.cat_manga_series
        "漫画" -> R.string.cat_manga
        "小说系列" -> R.string.cat_novel_series
        "小说" -> R.string.cat_novel
        "日剧" -> R.string.cat_jdrama
        "华语剧" -> R.string.cat_cdrama
        "欧美剧" -> R.string.cat_western_drama
        "电视剧" -> R.string.cat_tv_drama
        "音乐" -> R.string.cat_music
        "演出" -> R.string.cat_stage
        "画集" -> R.string.cat_artbook
        "扩展包" -> R.string.cat_expansion
        "其他" -> R.string.cat_other
        else -> return raw
    }
    return stringResource(res)
}

package cn.anitabi.map.data.update

import org.json.JSONObject

/** GitHub Release 里本 App 关心的几个字段(`GET /repos/{owner}/{repo}/releases/latest`)。 */
data class ReleaseInfo(
    /** 原始 tag(如 `v0.1.1`),也是「忽略此版本」的键。 */
    val tag: String,
    val version: AppVersion,
    /** Release 页,交给浏览器 / Custom Tab 打开。 */
    val htmlUrl: String,
    val name: String?,
    val prerelease: Boolean,
) {
    companion object {
        const val REPO = "AbuCuma/junrei-map"
        const val LATEST_API_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_URL = "https://github.com/$REPO/releases/latest"

        /** `tag_name` 缺失或不是版本号 → null(调用方视为检查失败,不是「已是最新」)。 */
        fun parse(json: String): ReleaseInfo? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val tag = root.optString("tag_name", "").trim()
            val version = AppVersion.parse(tag) ?: return null
            return ReleaseInfo(
                tag = tag,
                version = version,
                htmlUrl = root.optString("html_url", "").trim().ifEmpty { RELEASES_URL },
                name = root.optString("name", "").trim().ifEmpty { null },
                prerelease = root.optBoolean("prerelease", false),
            )
        }
    }
}

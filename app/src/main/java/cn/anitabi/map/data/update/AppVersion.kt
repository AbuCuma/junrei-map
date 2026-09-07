package cn.anitabi.map.data.update

/**
 * 应用版本号(`versionName` / Release tag)的可比较形式。纯 Kotlin,JVM 可测。
 *
 * 规则刻意简单:`v` 前缀可有可无;主体是点分非负整数,缺段补 0(`0.1` == `0.1.0`);
 * `-`/`+` 之后是预发布后缀,**同主体下带后缀者较旧**(`0.2.0-beta < 0.2.0`)。
 * 解析不了的一律 null —— 调用方按「不算新版本」处理,宁可少提示也不误报。
 */
data class AppVersion(val parts: List<Int>, val preRelease: String?) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val n = maxOf(parts.size, other.parts.size)
        for (i in 0 until n) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun toString(): String =
        parts.joinToString(".") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        fun parse(raw: String): AppVersion? {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            if (trimmed.isEmpty()) return null
            val cut = trimmed.indexOfFirst { it == '-' || it == '+' }
            val body = if (cut >= 0) trimmed.substring(0, cut) else trimmed
            val suffix = if (cut >= 0) trimmed.substring(cut + 1).takeIf { it.isNotEmpty() } ?: return null else null
            val parts = body.split('.').map { seg ->
                if (seg.isEmpty() || !seg.all { it.isDigit() }) return null
                seg.toIntOrNull() ?: return null
            }
            return AppVersion(parts, suffix)
        }
    }
}

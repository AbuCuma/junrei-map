package cn.anitabi.map.ui.scene.cutout

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * 抠图运行时成果物(.so/.onnx)的本地保管库:`filesDir/cutout/`。
 * 下载追加写入 `.part`(Range 断点续传)→ sha256 校验 → rename 为正式名。
 * 已校验的文件不会再次下载(大小一致 + 完成标记)。
 */
class CutoutRuntimeStore(
    private val root: File,
    private val client: OkHttpClient,
) {
    init {
        root.mkdirs()
    }

    val dir: File get() = root

    fun file(name: String): File = File(root, name)

    /** 是否已通过 sha256 校验(校验结果记录在 `<name>.ok`,内容为 sha256)。 */
    fun isReady(artifact: CutoutManifest.Artifact): Boolean {
        val f = file(artifact.name)
        val ok = File(root, artifact.name + ".ok")
        val ready = f.exists() && f.length() == artifact.size && ok.exists() && ok.readText().trim() == artifact.sha256
        if (ready && f.canWrite()) f.setReadOnly()
        return ready
    }

    fun allReady(artifacts: List<CutoutManifest.Artifact>): Boolean = artifacts.all(::isReady)

    /**
     * 依次下载缺失项。onProgress 为全部成果物合计的字节进度。
     * 失败以异常返回(调用方下次续传)。
     */
    suspend fun ensure(
        artifacts: List<CutoutManifest.Artifact>,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val total = artifacts.sumOf { it.size }
        var done = artifacts.filter(::isReady).sumOf { it.size }
        onProgress(done, total)
        for (a in artifacts) {
            if (isReady(a)) continue
            download(a) { bytes -> onProgress(done + bytes, total) }
            done += a.size
            onProgress(done, total)
        }
    }

    private suspend fun download(a: CutoutManifest.Artifact, onBytes: (Long) -> Unit) {
        val target = file(a.name)
        val part = File(root, a.name + ".part")
        File(root, a.name + ".ok").delete()
        part.setWritable(true)
        var have = if (part.exists()) part.length() else 0L
        if (have > a.size) { part.delete(); have = 0L }
        val req = Request.Builder().url(a.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for ${a.name}")
            val append = resp.code == 206 && have > 0
            if (!append) have = 0L
            val body = resp.body
            val buf = ByteArray(256 * 1024)
            java.io.FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        onBytes(have)
                    }
                }
            }
        }
        if (part.length() != a.size) { part.delete(); error("size mismatch for ${a.name}: ${part.length()} != ${a.size}") }
        val digest = sha256(part)
        if (digest != a.sha256) { part.delete(); error("sha256 mismatch for ${a.name}") }
        target.delete()
        if (!part.renameTo(target)) error("rename failed for ${a.name}")
        // 可写文件的 dlopen 在将来的 Android 会被拒绝(目前只是警告)→ 设为只读。
        target.setReadOnly()
        File(root, a.name + ".ok").writeText(digest)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

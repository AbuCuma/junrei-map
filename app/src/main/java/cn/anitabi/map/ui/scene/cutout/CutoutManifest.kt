package cn.anitabi.map.ui.scene.cutout

import org.json.JSONObject

/**
 * 分发用 manifest(由 `tools/build_cutout_manifest.py` 生成)。
 *
 * ```json
 * { "version": 1,
 *   "artifacts": {
 *     "libonnxruntime.so":        {"url": "...", "sha256": "...", "size": 23990744},
 *     "libQnnSystem.so":          {...}, "libQnnHtp.so": {...},
 *     "libQnnHtpV81Skel.so":      {...}, "libQnnHtpV81Stub.so": {...},
 *     "isnet_w8a16_ctx_v81.onnx": {...},
 *     "isnet_w8a8.onnx":          {...}
 *   } }
 * ```
 * 文件名是固定契约(由 [requiredArtifacts] 组装)。URL 可为绝对地址,也可相对于 manifest。
 */
class CutoutManifest(val version: Int, val artifacts: Map<String, Artifact>) {

    data class Artifact(val name: String, val url: String, val sha256: String, val size: Long)

    companion object {
        const val ORT_LIB = "libonnxruntime.so"
        const val QNN_SYSTEM_LIB = "libQnnSystem.so"
        const val QNN_HTP_LIB = "libQnnHtp.so"
        const val CPU_MODEL = "isnet_w8a8.onnx"
        fun skelLib(arch: String) = "libQnnHtp${arch.uppercase()}Skel.so"
        fun stubLib(arch: String) = "libQnnHtp${arch.uppercase()}Stub.so"
        fun htpModel(arch: String) = "isnet_w8a16_ctx_$arch.onnx"

        fun parse(json: String, manifestUrl: String): CutoutManifest {
            val root = JSONObject(json)
            val base = manifestUrl.substringBeforeLast('/') + "/"
            val arts = LinkedHashMap<String, Artifact>()
            val obj = root.getJSONObject("artifacts")
            for (name in obj.keys()) {
                val a = obj.getJSONObject(name)
                val url = a.getString("url").let { if (it.startsWith("http://") || it.startsWith("https://")) it else base + it }
                arts[name] = Artifact(name, url, a.getString("sha256").lowercase(), a.getLong("size"))
            }
            return CutoutManifest(root.optInt("version", 1), arts)
        }
    }

    /** 该档位所需的成果物。manifest 有缺失则返回 null(调用方落到下一档)。 */
    fun requiredArtifacts(tier: DeviceTier): List<Artifact>? {
        val names = when (tier) {
            is DeviceTier.Htp -> listOf(ORT_LIB, QNN_SYSTEM_LIB, QNN_HTP_LIB, skelLib(tier.htpArch), stubLib(tier.htpArch), htpModel(tier.htpArch))
            DeviceTier.Cpu -> listOf(ORT_LIB, CPU_MODEL)
            DeviceTier.MlKitOnly -> return emptyList()
        }
        return names.map { artifacts[it] ?: return null }
    }
}

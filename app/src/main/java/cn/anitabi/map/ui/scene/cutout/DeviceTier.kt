package cn.anitabi.map.ui.scene.cutout

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 抠图引擎的设备分档(基于 2026-08 spike 的实测)。
 *
 * - [Htp]  Qualcomm Hexagon NPU(ORT QNN EP + 预编译 context binary)。1024² 约 20〜30ms。
 * - [Cpu]  ORT CPU EP + w8a8 量化模型。约 0.7s(8 Elite 世代),峰值 +1.1GB。
 * - [MlKitOnly] 非 arm64/RAM 不足 → 只用现有 ML Kit。
 *
 * 不设 GPU 档(ORT 没有实用的 Android GPU EP)。
 */
sealed interface DeviceTier {
    /** @param htpArch QNN 的 HTP 架构(v73/v75/v79/v81)。context binary 按这个粒度各是一份。 */
    data class Htp(val socModel: String, val htpArch: String) : DeviceTier
    data object Cpu : DeviceTier
    data object MlKitOnly : DeviceTier

    companion object {
        /** Build.SOC_MODEL → HTP 架构(LiteRT supported_soc.csv / ExecuTorch 的对照表)。 */
        val HTP_ARCH_BY_SOC: Map<String, String> = mapOf(
            "SM8550" to "v73", // Snapdragon 8 Gen 2
            "SM8635" to "v73", // 8s Gen 3
            "SM8650" to "v75", // 8 Gen 3
            "SM8750" to "v79", // 8 Elite
            "SM8850" to "v81", // 8 Elite Gen 5(S948B 实证)
        )

        /** CPU 档所需最低 RAM(为让峰值 +1.1GB 能与其他应用共存)。 */
        private const val MIN_RAM_CPU_BYTES = 6L * 1024 * 1024 * 1024
        /** HTP 档(峰值 +150MB 左右)。 */
        private const val MIN_RAM_HTP_BYTES = 4L * 1024 * 1024 * 1024

        fun detect(context: Context): DeviceTier {
            // 验证用覆盖:`adb shell setprop debug.anitabi.cutout_tier cpu|mlkit`。
            when (debugOverride()) {
                "cpu" -> return Cpu
                "mlkit" -> return MlKitOnly
            }
            if (!Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) return MlKitOnly
            val totalRam = totalRamBytes(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true)) {
                val arch = HTP_ARCH_BY_SOC[Build.SOC_MODEL.uppercase()]
                if (arch != null && totalRam >= MIN_RAM_HTP_BYTES) return Htp(Build.SOC_MODEL.uppercase(), arch)
            }
            return if (totalRam >= MIN_RAM_CPU_BYTES) Cpu else MlKitOnly
        }

        private fun debugOverride(): String? = runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            cls.getMethod("get", String::class.java).invoke(null, "debug.anitabi.cutout_tier") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() }

        private fun totalRamBytes(context: Context): Long {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return info.totalMem
        }
    }
}

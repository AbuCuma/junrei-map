package cn.anitabi.map.ui.scene.cutout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutManifestTest {
    private val json = """
        {"version":1,"artifacts":{
          "libonnxruntime.so":{"url":"libonnxruntime.so","sha256":"AA","size":10},
          "libQnnSystem.so":{"url":"libQnnSystem.so","sha256":"bb","size":1},
          "libQnnHtp.so":{"url":"https://cdn.example/libQnnHtp.so","sha256":"cc","size":1},
          "libQnnHtpV81Skel.so":{"url":"libQnnHtpV81Skel.so","sha256":"dd","size":1},
          "libQnnHtpV81Stub.so":{"url":"libQnnHtpV81Stub.so","sha256":"ee","size":1},
          "isnet_w8a16_ctx_v81.onnx":{"url":"isnet_w8a16_ctx_v81.onnx","sha256":"ff","size":1},
          "isnet_w8a8.onnx":{"url":"isnet_w8a8.onnx","sha256":"11","size":1}
        }}
    """.trimIndent()

    @Test
    fun parsesRelativeAndAbsoluteUrls() {
        val m = CutoutManifest.parse(json, "https://host/cutout/manifest.json")
        assertEquals("https://host/cutout/libonnxruntime.so", m.artifacts["libonnxruntime.so"]!!.url)
        assertEquals("https://cdn.example/libQnnHtp.so", m.artifacts["libQnnHtp.so"]!!.url)
        assertEquals("aa", m.artifacts["libonnxruntime.so"]!!.sha256) // 小文字に正規化
        assertEquals(10L, m.artifacts["libonnxruntime.so"]!!.size)
    }

    @Test
    fun requiredArtifactsPerTier() {
        val m = CutoutManifest.parse(json, "https://host/m.json")
        val htp = m.requiredArtifacts(DeviceTier.Htp("SM8850", "v81"))!!.map { it.name }
        assertEquals(
            listOf("libonnxruntime.so", "libQnnSystem.so", "libQnnHtp.so", "libQnnHtpV81Skel.so", "libQnnHtpV81Stub.so", "isnet_w8a16_ctx_v81.onnx"),
            htp,
        )
        assertEquals(listOf("libonnxruntime.so", "isnet_w8a8.onnx"), m.requiredArtifacts(DeviceTier.Cpu)!!.map { it.name })
        assertTrue(m.requiredArtifacts(DeviceTier.MlKitOnly)!!.isEmpty())
        // 未配信の HTP 世代 → null（呼び出し側が CPU 档へ落とす）
        assertNull(m.requiredArtifacts(DeviceTier.Htp("SM8650", "v75")))
    }

    @Test
    fun socTableCoversKnownFlagships() {
        assertEquals("v81", DeviceTier.HTP_ARCH_BY_SOC["SM8850"])
        assertEquals("v79", DeviceTier.HTP_ARCH_BY_SOC["SM8750"])
        assertEquals("v75", DeviceTier.HTP_ARCH_BY_SOC["SM8650"])
        assertNull(DeviceTier.HTP_ARCH_BY_SOC["SM8450"])
    }
}

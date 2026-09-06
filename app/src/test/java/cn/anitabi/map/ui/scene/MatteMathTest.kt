package cn.anitabi.map.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MatteMathTest {

    private fun argb(a: Int, rgb: Int = 0x123456) = (a shl 24) or rgb

    @Test
    fun stretchAlphaClampsFloorAndCeiling() {
        // floor=25.5、ceiling=76.5：下は 0、上は 255、中間は線形
        val pixels = intArrayOf(argb(10), argb(200), argb(51))
        MatteMath.stretchAlpha(pixels)
        assertEquals(0, (pixels[0] ushr 24) and 0xFF)
        assertEquals(255, (pixels[1] ushr 24) and 0xFF)
        val mid = (pixels[2] ushr 24) and 0xFF
        // (51-25.5)/51*255 ≈ 127
        assertEquals(127, mid, )
        // RGB は不変（非予乗前提）
        assertEquals(0x123456, pixels[0] and 0xFFFFFF)
    }

    @Test
    fun removeFaintRegionsKillsHazeKeepsSubject() {
        // 6x4：左に核 255 の被写体（縁 150 付き）、右に峰値 120 の霧の島。
        val w = 6; val h = 4
        val px = IntArray(w * h)
        px[1 * w + 1] = argb(150); px[1 * w + 2] = argb(255); px[2 * w + 1] = argb(255)
        px[1 * w + 4] = argb(120); px[2 * w + 4] = argb(90)
        MatteMath.removeFaintRegions(px, w, h)
        assertEquals(150, (px[1 * w + 1] ushr 24) and 0xFF) // 被写体の縁は無傷
        assertEquals(255, (px[1 * w + 2] ushr 24) and 0xFF)
        assertEquals(0, (px[1 * w + 4] ushr 24) and 0xFF)   // 霧は全消し
        assertEquals(0, (px[2 * w + 4] ushr 24) and 0xFF)
        assertEquals(0x123456, px[1 * w + 4] and 0xFFFFFF)  // RGB は保持
    }

    @Test
    fun strongRefineRemovesMidAlphaHaze() {
        // ISNet 相当：raw 90/255 の霧（強拉伸後も中間 alpha が残る帯）は成分除去で消え、
        // 被写体（255 核）は残る。
        val w = 6; val h = 4
        val px = IntArray(w * h)
        val src = IntArray(w * h) { 0x654321 }
        px[1 * w + 1] = argb(255); px[2 * w + 1] = argb(230)
        px[1 * w + 4] = argb(110); px[2 * w + 4] = argb(110) // 拉伸後 ~163 < 204
        MatteMath.refine(px, src, w, h, strongMatte = true)
        assertEquals(255, (px[1 * w + 1] ushr 24) and 0xFF)
        assertEquals(0, (px[1 * w + 4] ushr 24) and 0xFF)
        assertEquals(0, (px[2 * w + 4] ushr 24) and 0xFF)
    }

    private fun assertEquals(expected: Int, actual: Int) =
        org.junit.Assert.assertEquals(expected.toLong(), actual.toLong())

    @Test
    fun interiorHoleIsFilledButBorderConnectedGapIsNot() {
        // 5×5：外周は透明（背景）、中は不透明、中心 1 画素だけ透明＝内部の洞
        val w = 5
        val h = 5
        val opaque = argb(255, 0x00FF00)
        val clear = argb(0, 0)
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) clear else opaque
        }
        pixels[2 * w + 2] = clear // 内部の洞

        val holes = MatteMath.interiorHoles(pixels, w, h)
        assertNotNull(holes)
        assertEquals(1, holes!!.size)
        assertEquals(2 * w + 2, holes[0])

        // 填洞：原図の色 + 完全不透明
        val source = IntArray(w * h) { argb(255, 0xAB_CDEF) }
        MatteMath.fillHoles(pixels, holes, source)
        assertEquals(argb(255, 0xABCDEF), pixels[2 * w + 2])
    }

    @Test
    fun openGapReachableFromBorderIsNotAHole() {
        // 3×3：中央列が全部透明（上辺に繋がる縦の溝）→ 洞なし
        val w = 3
        val h = 3
        val opaque = argb(255)
        val clear = argb(0)
        val pixels = IntArray(w * h) { i -> if (i % w == 1) clear else opaque }
        assertNull(MatteMath.interiorHoles(pixels, w, h))
    }

    @Test
    fun oversizedHoleIsSkipped() {
        // 20×20 の外周 1px だけ不透明、内側 18×18=324/400 > 25% → 補わない
        val w = 20
        val h = 20
        val opaque = argb(255)
        val clear = argb(0)
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) opaque else clear
        }
        assertNull(MatteMath.interiorHoles(pixels, w, h))
    }
}

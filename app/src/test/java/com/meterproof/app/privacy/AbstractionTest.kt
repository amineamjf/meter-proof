package com.meterproof.app.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AbstractionTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun uniformImageGivesUniformBlocks() {
        val px = IntArray(8 * 6) { rgb(10, 20, 30) }
        val out = Abstraction.blockMeans(px, 8, 6, 4, 3)
        assertEquals(12, out.size)
        assertTrue(out.all { it == rgb(10, 20, 30) })
    }

    @Test
    fun blockMeanIsAverageOfItsPixels() {
        // صورة 4x2: الكتلة اليسرى (0 و 100) والكتلة اليمنى (200 و 40) بتدرج رمادي
        val px = intArrayOf(
            rgb(0, 0, 0), rgb(100, 100, 100), rgb(200, 200, 200), rgb(40, 40, 40),
            rgb(0, 0, 0), rgb(100, 100, 100), rgb(200, 200, 200), rgb(40, 40, 40)
        )
        val out = Abstraction.blockMeans(px, 4, 2, 2, 1)
        assertEquals(rgb(50, 50, 50), out[0])
        assertEquals(rgb(120, 120, 120), out[1])
    }

    /** جوهر الخصوصية: صورتان لهما نفس متوسطات الكتل تعطيان ناتجًا واحدًا مهما اختلفت تفاصيلهما. */
    @Test
    fun detailInsideABlockLeavesNoTrace() {
        val w = 16
        val h = 16
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        val rnd = Random(7)
        // كتل 8x8: في a نمط رقعة شطرنج حادة، وفي b ضجيج عشوائي بنفس المتوسط (128 ثابت)
        for (y in 0 until h) for (x in 0 until w) {
            a[y * w + x] = if ((x + y) % 2 == 0) rgb(255, 255, 255) else rgb(0, 0, 1)
        }
        // نبني b: لكل كتلة 8x8 نوزع نفس مجموع a بترتيب عشوائي
        for (by in 0 until 2) for (bx in 0 until 2) {
            val vals = ArrayList<Int>()
            for (y in 0 until 8) for (x in 0 until 8) vals.add(a[(by * 8 + y) * w + bx * 8 + x])
            vals.shuffle(rnd)
            var k = 0
            for (y in 0 until 8) for (x in 0 until 8) b[(by * 8 + y) * w + bx * 8 + x] = vals[k++]
        }
        val oa = Abstraction.blockMeans(a, w, h, 2, 2)
        val ob = Abstraction.blockMeans(b, w, h, 2, 2)
        assertArrayEquals(oa, ob)
        // وبعد التكبير أيضًا: داخل كل كتلة لون واحد فقط
        val big = Abstraction.upscale(oa, 2, 2, 64, 64)
        assertEquals(1, big.slice(0 until 32).toSet().size)
    }

    @Test
    fun nonDivisibleSizesStillCoverEveryPixelAndStayInRange() {
        val w = 5
        val h = 3
        val px = IntArray(w * h) { rgb(200, 100, 50) }
        val out = Abstraction.blockMeans(px, w, h, 2, 2)
        assertEquals(4, out.size)
        assertTrue(out.all { it == rgb(200, 100, 50) })
    }

    @Test
    fun gridLargerThanImageDoesNotCrash() {
        val px = intArrayOf(rgb(9, 8, 7))
        val out = Abstraction.blockMeans(px, 1, 1, 4, 4)
        assertEquals(16, out.size)
        assertTrue(out.all { it == rgb(9, 8, 7) })
    }

    @Test
    fun outputIsAlwaysOpaque() {
        val px = IntArray(4) { 0x00112233 }
        val out = Abstraction.blockMeans(px, 2, 2, 1, 1)
        assertEquals(0xFF, (out[0] ushr 24) and 0xFF)
    }

    @Test
    fun upscaleUsesNearestBlock() {
        val blocks = intArrayOf(1, 2, 3, 4)
        val out = Abstraction.upscale(blocks, 2, 2, 4, 4)
        assertEquals(16, out.size)
        assertEquals(1, out[0])
        assertEquals(2, out[3])
        assertEquals(3, out[12])
        assertEquals(4, out[15])
        assertEquals(1, out[1 * 4 + 1])
        assertEquals(4, out[2 * 4 + 2])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPixelArrayThatIsTooSmall() {
        Abstraction.blockMeans(IntArray(3), 2, 2, 1, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroGrid() {
        Abstraction.blockMeans(IntArray(4), 2, 2, 0, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroOutputSize() {
        Abstraction.upscale(IntArray(4), 2, 2, 0, 4)
    }
}

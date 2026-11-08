package com.meterproof.app.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meterproof.app.privacy.Region
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

    // ---- صورة الدليل (REVEAL)

    private fun randomImage(w: Int, h: Int, seed: Long): IntArray {
        val rnd = Random(seed)
        return IntArray(w * h) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
    }

    @Test
    fun revealCompositeCopiesOnlyTheRegionAndAbstractsTheRest() {
        val w = 80
        val h = 60
        val src = randomImage(w, h, 3)
        val region = Region(0.25f, 0.25f, 0.5f, 0.5f)
        val outW = 160
        val (out, outH) = Abstraction.revealComposite(src, w, h, 8, 6, outW, region, margin = 0f)
        assertEquals(120, outH)
        val r = Abstraction.revealRect(region, 0f, outW, outH)

        val outside = HashSet<Int>()
        for (y in 0 until outH) for (x in 0 until outW) {
            val inside = x >= r[0] && x < r[2] && y >= r[1] && y < r[3]
            if (!inside) outside.add(out[y * outW + x])
        }
        // خارج المنطقة لا يوجد إلا ألوان الكتل (8x6 = 48 لونًا على الأكثر): لا أي تفصيل
        assertTrue("distinct colours outside: ${outside.size}", outside.size <= 48)

        // داخل المنطقة: بكسلات الأصل نفسها
        var different = 0
        for (y in r[1] until r[3]) for (x in r[0] until r[2]) {
            val sx = x * w / outW
            val sy = y * h / outH
            if (out[y * outW + x] != (src[sy * w + sx] or (0xFF shl 24))) different++
        }
        assertEquals(0, different)
    }

    @Test
    fun revealRectIncludesMarginAndStaysInsideTheImage() {
        val r = Abstraction.revealRect(Region(0.4f, 0.4f, 0.6f, 0.6f), 0.5f, 100, 100)
        assertArrayEquals(intArrayOf(30, 30, 70, 70), r)
        val edge = Abstraction.revealRect(Region(0f, 0.9f, 0.2f, 1f), 0.5f, 100, 100)
        assertEquals(0, edge[0])
        assertEquals(100, edge[3])
    }

    @Test
    fun revealOutputOutsideTheRegionIgnoresDetailInsideTheSource() {
        // مصدران يختلفان بتفاصيل شديدة داخل كتلة واحدة فقط، لهما نفس متوسطات الكتل:
        // الناتج خارج المنطقة متطابق تمامًا
        val w = 16
        val h = 16
        val a = IntArray(w * h) { rgb(100, 100, 100) }
        val b = a.copyOf()
        for (y in 0 until 8) for (x in 8 until 16) {
            a[y * w + x] = if ((x + y) % 2 == 0) rgb(200, 200, 200) else rgb(0, 0, 0)
            b[y * w + x] = rgb(100, 100, 100)
        }
        val region = Region(0.0f, 0.5f, 0.5f, 1.0f) // الربع السفلي الأيسر فقط
        val (oa, _) = Abstraction.revealComposite(a, w, h, 2, 2, 64, region, margin = 0f)
        val (ob, _) = Abstraction.revealComposite(b, w, h, 2, 2, 64, region, margin = 0f)
        assertArrayEquals(oa, ob)
    }
}

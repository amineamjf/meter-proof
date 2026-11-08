package com.meterproof.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RevealRenderTest {

    private val w = 160
    private val h = 120
    private val cols = 16
    private val rows = 12
    private val region = Region(0.25f, 0.25f, 0.75f, 0.75f)

    /** إطار بتفاصيل عشوائية لكل بكسل، ليظهر أي تسرب بسهولة. */
    private fun noisyFrame(): IntArray {
        val r = Random(7)
        return IntArray(w * h) { (0xFF shl 24) or (r.nextInt(256) shl 16) or (r.nextInt(256) shl 8) or r.nextInt(256) }
    }

    private fun abstractOf(px: IntArray): IntArray =
        Abstraction.upscale(Abstraction.blockMeans(px, w, h, cols, rows), cols, rows, w, h)

    private fun insideRegion(x: Int, y: Int) = x in 40 until 120 && y in 30 until 90

    @Test
    fun fullKeepsRegionPixelsAndAbstractsEverythingElse() {
        val px = noisyFrame()
        val bg = abstractOf(px)
        val out = RevealRender.compose(px, w, h, cols, rows, region, InvoiceDetail.FULL)
        for (y in 0 until h) for (x in 0 until w) {
            val expected = if (insideRegion(x, y)) px[y * w + x] else bg[y * w + x]
            assertEquals("($x,$y)", expected, out[y * w + x])
        }
    }

    @Test
    fun outlineOnlyLeaksNoRegionContent() {
        val px = noisyFrame()
        val bg = abstractOf(px)
        for (detail in listOf(InvoiceDetail.OUTLINE_ONLY, InvoiceDetail.MASK_PII)) {
            val out = RevealRender.compose(px, w, h, cols, rows, region, detail)
            val distinct = HashSet<Int>()
            for (y in 30 until 90) for (x in 40 until 120) distinct.add(out[y * w + x])
            assertTrue("distinct=${distinct.size}", distinct.size <= 2)
            for (y in 0 until h) for (x in 0 until w) {
                if (!insideRegion(x, y)) assertEquals("($x,$y)", bg[y * w + x], out[y * w + x])
            }
        }
    }

    @Test
    fun inputIsNeverModified() {
        val px = noisyFrame()
        val copy = px.copyOf()
        RevealRender.compose(px, w, h, cols, rows, region, InvoiceDetail.FULL)
        RevealRender.compose(px, w, h, cols, rows, region, InvoiceDetail.OUTLINE_ONLY)
        assertTrue(px.contentEquals(copy))
    }

    @Test
    fun emptyRegionIsFullyAbstract() {
        val px = noisyFrame()
        val bg = abstractOf(px)
        val out = RevealRender.compose(px, w, h, cols, rows, Region(0.5f, 0.5f, 0.5f, 0.5f), InvoiceDetail.FULL)
        assertTrue(bg.contentEquals(out))
    }

    @Test
    fun regionBeyondTheFrameIsClamped() {
        val px = noisyFrame()
        val out = RevealRender.compose(px, w, h, cols, rows, Region(-0.5f, -0.5f, 2f, 2f), InvoiceDetail.FULL)
        assertTrue(px.contentEquals(out))
    }
}

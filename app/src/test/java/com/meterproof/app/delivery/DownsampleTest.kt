package com.meterproof.app.delivery

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class DownsampleTest {

    /** مصدر 4×2 بأن لكل بكسل لونًا فريدًا: R = x*10+1 ، G = y*10+1 ، B = 5. */
    private fun source(): ByteBuffer {
        val w = 4
        val h = 2
        val b = ByteBuffer.allocate(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            b.put(o, (x * 10 + 1).toByte())
            b.put(o + 1, (y * 10 + 1).toByte())
            b.put(o + 2, 5)
            b.put(o + 3, 255.toByte())
        }
        return b
    }

    private fun px(r: Int, g: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or 5

    @Test
    fun rotation0KeepsPixelsInPlace() {
        val out = Downsample.uprightRgba(source(), 4, 2, 16, 4, 0, 4, 2)
        assertEquals(px(1, 1), out[0])
        assertEquals(px(31, 1), out[3])
        assertEquals(px(1, 11), out[4])
        assertEquals(px(31, 11), out[7])
    }

    @Test
    fun rotation90TurnsClockwise() {
        // مصدر 4×2 يصبح 2×4: أعلى اليسار في الناتج = أسفل اليسار في المصدر
        val out = Downsample.uprightRgba(source(), 4, 2, 16, 4, 90, 2, 4)
        assertEquals(px(1, 11), out[0])   // (0,0) <- src(0,1)
        assertEquals(px(1, 1), out[1])    // (1,0) <- src(0,0)
        assertEquals(px(11, 11), out[2])  // (0,1) <- src(1,1)
        assertEquals(px(31, 1), out[7])   // (1,3) <- src(3,0)
    }

    @Test
    fun rotation180Flips() {
        val out = Downsample.uprightRgba(source(), 4, 2, 16, 4, 180, 4, 2)
        assertEquals(px(31, 11), out[0])
        assertEquals(px(1, 1), out[7])
    }

    @Test
    fun rotation270TurnsCounterClockwise() {
        val out = Downsample.uprightRgba(source(), 4, 2, 16, 4, 270, 2, 4)
        assertEquals(px(31, 1), out[0])   // (0,0) <- src(3,0)
        assertEquals(px(31, 11), out[1])  // (1,0) <- src(3,1)
        assertEquals(px(1, 11), out[7])   // (1,3) <- src(0,1)
    }

    @Test
    fun downscalingAveragesCells() {
        // مصدر 4×2 إلى 2×1: كل خلية متوسط بكسلين على الأقل
        val out = Downsample.uprightRgba(source(), 4, 2, 16, 4, 0, 2, 1)
        val left = out[0]
        val r = (left shr 16) and 255
        assertEquals(true, r in 1..11)
    }

    @Test
    fun targetSizeKeepsAspectAfterRotation() {
        assertEquals(Pair(80, 60), Downsample.targetSize(320, 240, 0))
        assertEquals(Pair(60, 80), Downsample.targetSize(320, 240, 90))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddRotation() {
        Downsample.uprightRgba(source(), 4, 2, 16, 4, 45, 4, 2)
    }
}

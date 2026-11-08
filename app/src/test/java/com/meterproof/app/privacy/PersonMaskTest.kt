package com.meterproof.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PersonMaskTest {

    private val w = 100
    private val h = 100

    private fun mask(fill: (Int, Int) -> Float) = FloatArray(w * h) { fill(it % w, it / w) }

    @Test
    fun emptyMaskHasNoHazard() {
        assertNull(PersonMask.analyze(mask { _, _ -> 0f }, w, h))
    }

    @Test
    fun isolatedNoisePixelsAreIgnored() {
        // 5 بكسلات من 10000 (0.05%) أقل من الحد الأدنى 0.2%
        val m = mask { x, y -> if (y == 10 && x < 5) 0.9f else 0f }
        assertNull(PersonMask.analyze(m, w, h))
    }

    @Test
    fun confidentBlobIsHazardWithBoundingBox() {
        val m = mask { x, y -> if (x in 20..39 && y in 50..69) 0.9f else 0f }
        val hz = PersonMask.analyze(m, w, h)
        assertNotNull(hz)
        assertEquals(HazardKind.PERSON, hz!!.kind)
        assertEquals(1f, hz.score, 0f)
        val r = hz.region!!
        assertEquals(0.20f, r.left, 1e-6f)
        assertEquals(0.40f, r.right, 1e-6f)
        assertEquals(0.50f, r.top, 1e-6f)
        assertEquals(0.70f, r.bottom, 1e-6f)
    }

    @Test
    fun faintBlobIsUncertainHazard() {
        val m = mask { x, y -> if (x in 0..19 && y in 0..19) 0.3f else 0f }
        val hz = PersonMask.analyze(m, w, h)
        assertNotNull(hz)
        assertEquals(0.3f, hz!!.score, 0f)
        // وتعني السياسة الإتلاف في الحالتين
        val d = PrivacyPolicy().decide(listOf(DetectorOutcome.Ok("PERSON", listOf(hz))))
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertEquals(listOf("UNCERTAIN_PERSON"), d.reasons)
    }

    @Test
    fun valuesBelowFaintThresholdAreIgnored() {
        assertNull(PersonMask.analyze(mask { _, _ -> 0.2f }, w, h))
    }

    @Test(expected = IllegalStateException::class)
    fun nanInMaskIsTreatedAsCorrupt() {
        val m = mask { _, _ -> 0f }
        m[123] = Float.NaN
        PersonMask.analyze(m, w, h)
    }

    @Test(expected = IllegalArgumentException::class)
    fun maskSmallerThanDeclaredIsRejected() {
        PersonMask.analyze(FloatArray(10), w, h)
    }

    @Test
    fun smallestReportedBlobMatchesMinFraction() {
        // 20 بكسلًا من 10000 = 0.2% بالضبط: يُعدّ خطرًا
        val m = mask { x, y -> if (y == 0 && x < 20) 1f else 0f }
        assertNotNull(PersonMask.analyze(m, w, h))
    }
}

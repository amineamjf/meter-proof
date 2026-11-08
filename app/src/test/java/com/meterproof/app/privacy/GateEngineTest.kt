package com.meterproof.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات حقن الأعطال: تثبت أن أي فشل في أي كاشف ينتهي بإتلاف الصورة. */
class GateEngineTest {

    private class FakeFrame : Frame {
        override val width = 4
        override val height = 4
        override fun argb() = IntArray(16)
    }

    private class FakeDetector(
        override val name: String,
        private val action: () -> List<Hazard>
    ) : HazardDetector {
        override fun detect(frame: Frame): List<Hazard> = action()
    }

    private fun engine(vararg d: HazardDetector, timeoutMs: Long = 5_000L) =
        GateEngine(d.toList(), PrivacyPolicy(), timeoutMs)

    @Test
    fun cleanDetectorsGiveAbstract() {
        val r = engine(FakeDetector("FACE") { emptyList() }, FakeDetector("PERSON") { emptyList() })
            .evaluate(FakeFrame())
        assertEquals(PrivacyTier.ABSTRACT, r.decision.tier)
        assertFalse(r.anyTimedOut)
        assertEquals(listOf("FACE", "PERSON"), r.latencyMs.keys.toList())
    }

    @Test
    fun hazardFromAnyDetectorDiscards() {
        val r = engine(
            FakeDetector("FACE") { emptyList() },
            FakeDetector("PERSON") { listOf(Hazard(HazardKind.PERSON, 0.9f)) }
        ).evaluate(FakeFrame())
        assertEquals(PrivacyTier.DISCARD, r.decision.tier)
        assertEquals(listOf("PERSON"), r.decision.reasons)
    }

    @Test
    fun thrownExceptionBecomesFailureAndDiscards() {
        val r = engine(FakeDetector("FACE") { throw IllegalStateException("boom") })
            .evaluate(FakeFrame())
        assertEquals(PrivacyTier.DISCARD, r.decision.tier)
        assertEquals(listOf("FAILED:FACE"), r.decision.reasons)
    }

    @Test
    fun throwableOtherThanExceptionAlsoDiscards() {
        val r = engine(FakeDetector("FACE") { throw OutOfMemoryError("simulated") })
            .evaluate(FakeFrame())
        assertEquals(PrivacyTier.DISCARD, r.decision.tier)
    }

    @Test
    fun slowDetectorTimesOutAndDiscardsPromptly() {
        val t0 = System.nanoTime()
        val r = engine(
            FakeDetector("PERSON") { Thread.sleep(3_000); emptyList() },
            timeoutMs = 100L
        ).evaluate(FakeFrame())
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        assertEquals(PrivacyTier.DISCARD, r.decision.tier)
        assertEquals(listOf("TIMEOUT:PERSON"), r.decision.reasons)
        assertTrue(r.anyTimedOut)
        assertTrue("took too long: $elapsedMs ms", elapsedMs < 2_000L)
    }

    @Test
    fun failureOfOneDetectorDoesNotSkipTheOthers() {
        val r = engine(
            FakeDetector("FACE") { throw RuntimeException("x") },
            FakeDetector("PERSON") { listOf(Hazard(HazardKind.PERSON, 1f)) }
        ).evaluate(FakeFrame())
        assertEquals(2, r.outcomes.size)
        assertEquals(PrivacyTier.DISCARD, r.decision.tier)
        assertEquals(listOf("FAILED:FACE", "PERSON"), r.decision.reasons)
    }

    @Test
    fun noDetectorsDiscards() {
        val r = GateEngine(emptyList()).evaluate(FakeFrame())
        assertEquals(PrivacyTier.DISCARD, r.decision.tier)
        assertEquals(listOf("NO_DETECTORS"), r.decision.reasons)
    }

    @Test
    fun resultsKeepDetectorOrder() {
        val r = engine(
            FakeDetector("B") { emptyList() },
            FakeDetector("A") { emptyList() }
        ).evaluate(FakeFrame())
        assertEquals(listOf("B", "A"), r.outcomes.map { it.detector })
    }

    @Test
    fun revealRequestIsHonouredOnlyWhenEverythingIsClear() {
        val region = Region(0.2f, 0.2f, 0.7f, 0.7f)
        val clear = engine(FakeDetector("FACE") { emptyList() }).evaluate(FakeFrame(), region)
        assertEquals(PrivacyTier.REVEAL, clear.decision.tier)
        val risky = engine(FakeDetector("FACE") { throw RuntimeException() }).evaluate(FakeFrame(), region)
        assertEquals(PrivacyTier.DISCARD, risky.decision.tier)
    }
}

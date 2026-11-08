package com.meterproof.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PrivacyPolicyTest {

    private val policy = PrivacyPolicy()

    private fun ok(name: String, vararg h: Hazard) = DetectorOutcome.Ok(name, h.toList())
    private fun face(score: Float = 1f) = Hazard(HazardKind.FACE, score)
    private fun person(score: Float = 1f) = Hazard(HazardKind.PERSON, score)

    @Test
    fun noDetectorsMeansDiscard() {
        val d = policy.decide(emptyList())
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertEquals(listOf("NO_DETECTORS"), d.reasons)
    }

    @Test
    fun allClearGivesAbstractNotReveal() {
        val d = policy.decide(listOf(ok("FACE"), ok("PERSON")))
        assertEquals(PrivacyTier.ABSTRACT, d.tier)
        assertTrue(d.reasons.isEmpty())
        assertNull(d.revealRegion)
    }

    @Test
    fun faceAnywhereDiscards() {
        val d = policy.decide(listOf(ok("FACE", face()), ok("PERSON")))
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertEquals(listOf("FACE"), d.reasons)
    }

    @Test
    fun personAnywhereDiscards() {
        val d = policy.decide(listOf(ok("FACE"), ok("PERSON", person())))
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertEquals(listOf("PERSON"), d.reasons)
    }

    @Test
    fun veryLowScoreIsIgnoredAsNoise() {
        val d = policy.decide(listOf(ok("PERSON", person(0.05f))))
        assertEquals(PrivacyTier.ABSTRACT, d.tier)
    }

    @Test
    fun midScoreIsUncertainAndStillDiscards() {
        val d = policy.decide(listOf(ok("PERSON", person(0.3f))))
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertEquals(listOf("UNCERTAIN_PERSON"), d.reasons)
    }

    @Test
    fun boundariesAreInclusive() {
        val c = PrivacyConfig()
        val atMin = policy.decide(listOf(ok("P", person(c.minHazardScore))))
        assertEquals(listOf("UNCERTAIN_PERSON"), atMin.reasons)
        val atCertain = policy.decide(listOf(ok("P", person(c.certainHazardScore))))
        assertEquals(listOf("PERSON"), atCertain.reasons)
    }

    @Test
    fun detectorFailureDiscards() {
        val d = policy.decide(listOf(DetectorOutcome.Failed("PERSON", FailReason.EXCEPTION)))
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertEquals(listOf("FAILED:PERSON"), d.reasons)
    }

    @Test
    fun detectorTimeoutDiscards() {
        val d = policy.decide(listOf(DetectorOutcome.Failed("FACE", FailReason.TIMEOUT)))
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertEquals(listOf("TIMEOUT:FACE"), d.reasons)
    }

    @Test
    fun oneFailureOutweighsOtherDetectorsBeingClear() {
        val d = policy.decide(
            listOf(ok("FACE"), DetectorOutcome.Failed("PERSON", FailReason.EXCEPTION))
        )
        assertEquals(PrivacyTier.DISCARD, d.tier)
    }

    @Test
    fun reasonsAreDeduplicatedAndKeepOrder() {
        val d = policy.decide(
            listOf(ok("FACE", face(), face()), ok("PERSON", person()))
        )
        assertEquals(listOf("FACE", "PERSON"), d.reasons)
    }

    @Test
    fun cleanFrameWithRevealRequestIsRevealed() {
        val r = Region(0.2f, 0.3f, 0.7f, 0.9f)
        val d = policy.decide(listOf(ok("FACE"), ok("PERSON")), r)
        assertEquals(PrivacyTier.REVEAL, d.tier)
        assertEquals(r, d.revealRegion)
    }

    @Test
    fun hazardBeatsRevealRequest() {
        val r = Region(0.2f, 0.3f, 0.8f, 0.9f)
        val d = policy.decide(listOf(ok("PERSON", person())), r)
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertNull(d.revealRegion)
    }

    @Test
    fun emptyOrOutOfRangeRevealFallsBackToAbstract() {
        val empty = policy.decide(listOf(ok("FACE")), Region(0.5f, 0.5f, 0.5f, 0.9f))
        assertEquals(PrivacyTier.ABSTRACT, empty.tier)
        val outside = policy.decide(listOf(ok("FACE")), Region(1.5f, 1.5f, 2f, 2f))
        assertEquals(PrivacyTier.ABSTRACT, outside.tier)
    }

    @Test
    fun revealRegionIsClampedToFrame() {
        val d = policy.decide(listOf(ok("FACE")), Region(-0.1f, 0.5f, 0.3f, 1.2f))
        assertEquals(PrivacyTier.REVEAL, d.tier)
        assertEquals(Region(0f, 0.5f, 0.3f, 1f), d.revealRegion)
    }

    /** إضافة أي خطر أو فشل إلى النتائج لا يمكن أن تنتج مستوى أقل شدة. */
    @Test
    fun addingHazardsOrFailuresNeverLowersSeverity() {
        val rnd = Random(42)
        val names = listOf("FACE", "PERSON", "OTHER")

        fun randomOutcome(): DetectorOutcome {
            val name = names[rnd.nextInt(names.size)]
            return when (rnd.nextInt(3)) {
                0 -> DetectorOutcome.Failed(name, if (rnd.nextBoolean()) FailReason.TIMEOUT else FailReason.EXCEPTION)
                1 -> DetectorOutcome.Ok(name, emptyList())
                else -> DetectorOutcome.Ok(
                    name,
                    listOf(Hazard(if (rnd.nextBoolean()) HazardKind.FACE else HazardKind.PERSON, rnd.nextFloat()))
                )
            }
        }

        repeat(500) {
            val base = List(1 + rnd.nextInt(4)) { randomOutcome() }
            val reveal = if (rnd.nextBoolean()) Region(0.2f, 0.2f, 0.7f, 0.7f) else null
            val before = policy.decide(base, reveal).tier.severity
            val extended = base + randomOutcome()
            val after = policy.decide(extended, reveal).tier.severity
            assertTrue("severity dropped after adding an outcome", after >= before)
        }
    }

    @Test
    fun tooLargeRevealRegionIsNotCertifiedAsPaper() {
        // مساحة 0.64 > الحد 0.40: ليست ورقة بل مشهد، فيبقى الناتج مجرّدًا
        val d = policy.decide(listOf(ok("FACE")), Region(0.1f, 0.1f, 0.9f, 0.9f))
        assertEquals(PrivacyTier.ABSTRACT, d.tier)
        assertNull(d.revealRegion)
    }

    @Test
    fun defaultInvoiceDetailIsFull() {
        assertEquals(InvoiceDetail.FULL, PrivacyConfig().invoiceDetail)
    }
}

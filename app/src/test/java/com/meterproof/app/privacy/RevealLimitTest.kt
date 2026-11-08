package com.meterproof.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RevealLimitTest {

    private val ok = listOf<DetectorOutcome>(
        DetectorOutcome.Ok("FACE", emptyList()),
        DetectorOutcome.Ok("PERSON", emptyList())
    )

    @Test
    fun regionWithinTheLimitIsRevealed() {
        val policy = PrivacyPolicy(PrivacyConfig(maxRevealArea = 0.5f))
        val d = policy.decide(ok, Region(0.1f, 0.1f, 0.6f, 0.6f))
        assertEquals(PrivacyTier.REVEAL, d.tier)
    }

    @Test
    fun regionAboveTheLimitStaysAbstract() {
        val policy = PrivacyPolicy(PrivacyConfig(maxRevealArea = 0.5f))
        val d = policy.decide(ok, Region(0f, 0f, 0.9f, 0.9f))
        assertEquals(PrivacyTier.ABSTRACT, d.tier)
        assertNull(d.revealRegion)
        assertEquals(listOf("REVEAL_TOO_LARGE"), d.reasons)
    }

    @Test
    fun defaultConfigKeepsPhase1Behaviour() {
        val d = PrivacyPolicy().decide(ok, Region(0f, 0f, 0.95f, 0.95f))
        assertEquals(PrivacyTier.REVEAL, d.tier)
    }

    @Test
    fun hazardStillBeatsAnyRevealRequest() {
        val policy = PrivacyPolicy(PrivacyConfig(maxRevealArea = 0.5f))
        val bad = ok + DetectorOutcome.Ok("X", listOf(Hazard(HazardKind.FACE, 0.9f)))
        val d = policy.decide(bad, Region(0.1f, 0.1f, 0.3f, 0.3f))
        assertEquals(PrivacyTier.DISCARD, d.tier)
        assertNull(d.revealRegion)
    }
}

package com.meterproof.app.delivery

import com.meterproof.app.privacy.GateEngine
import com.meterproof.app.privacy.Hazard
import com.meterproof.app.privacy.HazardDetector
import com.meterproof.app.privacy.HazardKind
import com.meterproof.app.privacy.InvoiceDetail
import com.meterproof.app.privacy.PrivacyTier
import com.meterproof.app.privacy.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryDetectorTest {

    private val w = 160
    private val h = 120

    /** نموذج يدوي: الدرجة تتبع كثافة الحواف فقط (sigmoid(20*textiness - 2)). */
    private val model = InvoiceModel(
        mean = List(6) { 0f }, std = List(6) { 1f },
        weights = listOf(0f, 0f, 0f, 0f, 0f, 20f), bias = -2f,
        acceptThreshold = 0.5f, uncertainThreshold = 0.2f,
        trainedAt = 1L, positives = 20, negatives = 20,
        metrics = ModelMetrics(1f, 1f, 1f, 4)
    )

    private fun cand(textiness: Float) = InvoiceCandidate(
        InvoiceFeatures(0.2f, 0.9f, 0.3f, 0.5f, 0.0f, textiness),
        Region(0.2f, 0.2f, 0.7f, 0.7f)
    )

    @Test
    fun thresholdsMapToStates() {
        assertEquals(DeliveryState.DETECTED, DeliveryDetector.classify(cand(0.15f), model).state)
        assertEquals(DeliveryState.UNCERTAIN, DeliveryDetector.classify(cand(0.08f), model).state)
        assertEquals(DeliveryState.NONE, DeliveryDetector.classify(cand(0.0f), model).state)
        assertEquals(model.id, DeliveryDetector.classify(cand(0.15f), model).modelId)
    }

    @Test
    fun printedPaperIsDetectedEndToEnd() {
        val frame = TestFrames.Fake(TestFrames.paperScene(textured = true), w, h)
        val r = DeliveryDetector(model).detect(frame)
        assertEquals("score=${r.score}", DeliveryState.DETECTED, r.state)
        assertNotNull(r.region)
    }

    @Test
    fun blankPaperIsNotDetected() {
        val frame = TestFrames.Fake(TestFrames.paperScene(textured = false), w, h)
        val r = DeliveryDetector(model).detect(frame)
        assertEquals("score=${r.score}", DeliveryState.NONE, r.state)
    }

    @Test
    fun emptyWallHasNoCandidateAndNoRegion() {
        val frame = TestFrames.Fake(TestFrames.solid(w, h, TestFrames.rgb(235, 235, 235)), w, h)
        val r = DeliveryDetector(model).detect(frame)
        assertEquals(DeliveryState.NONE, r.state)
        assertNull(r.region)
    }

    @Test
    fun probeProposesOnlyWhenDetected() {
        val detected = DeliveryRevealProbe(DeliveryDetector(model), InvoiceDetail.OUTLINE_ONLY)
        val p = detected.propose(TestFrames.Fake(TestFrames.paperScene(textured = true), w, h))
        assertNotNull(p)
        assertEquals(InvoiceDetail.OUTLINE_ONLY, p!!.detail)
        assertEquals(DeliveryState.DETECTED, detected.last!!.state)

        val none = DeliveryRevealProbe(DeliveryDetector(model), InvoiceDetail.FULL)
        assertNull(none.propose(TestFrames.Fake(TestFrames.paperScene(textured = false), w, h)))
        assertEquals(DeliveryState.NONE, none.last!!.state)
    }

    @Test
    fun calibrationProbeNeverProposesReveal() {
        val probe = CalibrationProbe()
        assertNull(probe.propose(TestFrames.Fake(TestFrames.paperScene(textured = true), w, h)))
        assertNotNull(probe.candidate)
    }

    @Test
    fun hazardAnywhereCancelsTheProposedReveal() {
        val probe = DeliveryRevealProbe(DeliveryDetector(model), InvoiceDetail.FULL)
        val frame = TestFrames.Fake(TestFrames.paperScene(textured = true), w, h)
        val proposal = probe.propose(frame)
        assertNotNull(proposal)

        val person = object : HazardDetector {
            override val name = "PERSON"
            override fun detect(frame: com.meterproof.app.privacy.Frame) =
                listOf(Hazard(HazardKind.PERSON, 0.9f))
        }
        val clear = object : HazardDetector {
            override val name = "FACE"
            override fun detect(frame: com.meterproof.app.privacy.Frame) = emptyList<Hazard>()
        }

        val blocked = GateEngine(listOf(clear, person)).evaluate(frame, proposal!!.region)
        assertEquals(PrivacyTier.DISCARD, blocked.decision.tier)
        assertNull(blocked.decision.revealRegion)

        val allowed = GateEngine(listOf(clear)).evaluate(frame, proposal.region)
        assertEquals(PrivacyTier.REVEAL, allowed.decision.tier)
        assertTrue(allowed.decision.revealRegion!!.area > 0f)
    }
}

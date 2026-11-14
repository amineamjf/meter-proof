package com.meterproof.app.delivery

import com.meterproof.app.EventRec
import com.meterproof.app.PhotoLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewHoldoutTest {

    private fun feat(pos: Boolean, i: Int): InvoiceFeatures {
        val j = (i % 7) * 0.01f
        return if (pos) InvoiceFeatures.fromList(listOf(0.05f + j, 0.9f, 0.1f, 0.6f, 0.05f, 0.3f + j))!!
        else InvoiceFeatures.fromList(listOf(0.3f + j, 0.4f, 1.2f, 0.1f, 0.5f, 0.0f))!!
    }

    private fun samples(n: Int) =
        (0 until n).map { Sample(feat(true, it), true, it * 2L) } +
            (0 until n).map { Sample(feat(false, it), false, it * 2L + 1) }

    @Test fun holdoutNeedsEnoughSamples() {
        assertNull(InvoiceTrainer.holdoutEvaluate(samples(2), 1L))
    }

    @Test fun holdoutNotTrainableWhenTooFewForTraining() {
        val r = InvoiceTrainer.holdoutEvaluate(samples(12), 1L)
        assertNotNull(r)
        assertFalse(r!!.trainable)
    }

    @Test fun holdoutOnSeparableDataIsGood() {
        val r = InvoiceTrainer.holdoutEvaluate(samples(40), 1L)!!
        assertTrue(r.trainable)
        assertEquals(10, r.heldPositives)
        assertEquals(10, r.heldNegatives)
        assertEquals(60, r.trainedOn)
        assertEquals(0, r.falseAccepts)
        assertTrue(r.recall >= 0.8f)
    }

    private fun ev(type: String, rec: String, score: Int, t: Long) =
        EventRec(type, t, null, null, "mode=auto;score=$score;rec=$rec;gate=1")

    @Test fun reviewQueueOrdersAndExcludesLabeled() {
        val a = "aaaaaaaa-0001"; val b = "aaaaaaaa-0002"; val c = "aaaaaaaa-0003"; val d = "aaaaaaaa-0004"
        val events = listOf(
            ev(DeliveryEventLog.EVENT_DETECTED, a, 97, 1),
            ev(DeliveryEventLog.EVENT_UNCERTAIN, b, 40, 2),
            ev(DeliveryEventLog.EVENT_UNCERTAIN, c, 70, 3),
            ev(DeliveryEventLog.EVENT_DETECTED, d, 99, 4),
            EventRec("STOP_START", 5, null, null, "x")
        )
        val q = ReviewQueue.build(events, mapOf(d to PhotoLabels.Label.INVOICE))
        assertEquals(listOf(c, b, a), q.map { it.recordId })
    }

    @Test fun reviewQueueKeepsLatestEventPerRecordAndIgnoresBadRows() {
        val a = "aaaaaaaa-0001"
        val events = listOf(
            ev(DeliveryEventLog.EVENT_UNCERTAIN, a, 50, 1),
            ev(DeliveryEventLog.EVENT_DETECTED, a, 90, 2),
            EventRec(DeliveryEventLog.EVENT_DETECTED, 3, null, null, "score=abc;rec=bbbbbbbb-1")
        )
        val q = ReviewQueue.build(events, emptyMap())
        assertEquals(1, q.size)
        assertTrue(q[0].detected)
        assertEquals(90, q[0].score)
    }

    @Test fun explainerNamesDrivingFeatures() {
        val model = (InvoiceTrainer.train(samples(30), 1L) as InvoiceTrainer.Result.Ready).model
        val text = DecisionExplainer.explain(feat(true, 0), model)
        assertTrue(text, text.contains("رفعها"))
        val c = DecisionExplainer.contributions(feat(true, 0), model)
        assertEquals(InvoiceFeatures.SIZE, c.size)
        assertTrue(c.sumOf { it.value.toDouble() } > 0.0)
        assertTrue(DecisionExplainer.contributions(feat(false, 0), model).sumOf { it.value.toDouble() } < 0.0)
    }
}

class EvidenceStatusTest {
    @Test fun statusMapping() {
        val t = com.meterproof.app.privacy.PrivacyTier.REVEAL
        val a = com.meterproof.app.privacy.PrivacyTier.ABSTRACT
        assertEquals(DeliveryEventLog.EvidenceStatus.EVIDENCE_ACCEPTED, DeliveryEventLog.evidenceStatus(t, DeliveryState.DETECTED))
        assertEquals(DeliveryEventLog.EvidenceStatus.EVIDENCE_UNCERTAIN, DeliveryEventLog.evidenceStatus(a, DeliveryState.UNCERTAIN))
        assertEquals(DeliveryEventLog.EvidenceStatus.NO_USABLE_EVIDENCE, DeliveryEventLog.evidenceStatus(t, DeliveryState.NONE))
        assertEquals(DeliveryEventLog.EvidenceStatus.PRIVACY_REJECTED, DeliveryEventLog.evidenceStatus(t, DeliveryState.DETECTED, true))
    }

    @Test fun detailCarriesStatusAndStaysParseable() {
        val d = DeliveryEventLog.detail("auto", 0.93f, "w1a2b3c", com.meterproof.app.privacy.InvoiceDetail.FULL,
            "aaaaaaaa-0001", DeliveryEventLog.EvidenceStatus.EVIDENCE_ACCEPTED)
        val kv = DeliveryEventLog.parse(d)
        assertEquals("EVIDENCE_ACCEPTED", kv["ev"])
        assertEquals("93", kv["score"])
    }

    @Test fun similarPaperLabelIsCounted() {
        val c = PhotoLabels.counts(mapOf("a" to PhotoLabels.Label.SIMILAR, "b" to PhotoLabels.Label.NONE))
        assertEquals(1, c.similar)
        assertEquals(2, c.total)
        assertEquals(PhotoLabels.Label.SIMILAR, PhotoLabels.Label.fromCode("SIMILAR"))
    }
}

class AugmentAndDiversifyTest {
    private fun px(w: Int, h: Int) = IntArray(w * h) { (0xFF shl 24) or ((it % w) * 3 and 0xFF) }

    @Test fun flipMirrorsPixelsAndBoxes() {
        val w = 8; val h = 4
        val src = px(w, h)
        val f = ImageAugment.flipH(src, w, h)
        assertEquals(src[0], f[w - 1])
        assertEquals(src[w - 1], f[0])
        assertTrue(ImageAugment.flipH(f, w, h).contentEquals(src))
        val r = ImageAugment.mirror(com.meterproof.app.privacy.Region(0.1f, 0.2f, 0.4f, 0.5f))
        assertEquals(0.6f, r.left, 1e-6f); assertEquals(0.9f, r.right, 1e-6f)
        assertEquals(0.2f, r.top, 1e-6f)
    }

    @Test fun relightClampsAndKeepsAlpha() {
        val out = ImageAugment.relight(intArrayOf((0xFF shl 24) or (250 shl 16) or (10 shl 8) or 0), 1.5f, 10)
        assertEquals(255, (out[0] shr 16) and 0xFF)
        assertEquals(25, (out[0] shr 8) and 0xFF)
        assertEquals(0xFF, (out[0] ushr 24))
    }

    @Test fun onlyInvoiceImagesAreAugmented() {
        val src = px(8, 8)
        assertTrue(ImageAugment.variants(src, 8, 8, emptyList(), emptyList(), 0).isEmpty())
        val v = ImageAugment.variants(src, 8, 8, listOf(com.meterproof.app.privacy.Region(0.1f, 0.1f, 0.3f, 0.3f)), emptyList(), 1)
        assertEquals(2, v.size)
        assertEquals(0.7f, v[0].recognize[0].left, 1e-6f)
    }

    @Test fun diversifyPushesSameStopDuplicatesBack() {
        val a = ReviewQueue.Item("a", 60, false, 1_000L, 33.5731, -7.5898)
        val b = ReviewQueue.Item("b", 59, false, 2_000L, 33.5731, -7.5898) // الوقفة نفسها
        val c = ReviewQueue.Item("c", 40, false, 3_000_000L, 33.6000, -7.6000)
        val out = ReviewQueue.diversify(listOf(a, b, c)).map { it.recordId }
        assertEquals(listOf("a", "c", "b"), out)
    }
}

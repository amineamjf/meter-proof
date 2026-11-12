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
}

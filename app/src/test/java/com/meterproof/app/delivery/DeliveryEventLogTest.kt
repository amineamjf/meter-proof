package com.meterproof.app.delivery

import com.meterproof.app.privacy.InvoiceDetail
import com.meterproof.app.privacy.PrivacyTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryEventLogTest {

    @Test
    fun eventTypeFollowsGateDecisionAndDetectorState() {
        assertEquals(
            DeliveryEventLog.EVENT_DETECTED,
            DeliveryEventLog.eventType(PrivacyTier.REVEAL, DeliveryState.DETECTED)
        )
        // الكشف لم يتحول إلى كشف فعلي (مثلًا منطقة كبيرة): غير مؤكد فقط
        assertEquals(
            DeliveryEventLog.EVENT_UNCERTAIN,
            DeliveryEventLog.eventType(PrivacyTier.ABSTRACT, DeliveryState.DETECTED)
        )
        assertEquals(
            DeliveryEventLog.EVENT_UNCERTAIN,
            DeliveryEventLog.eventType(PrivacyTier.ABSTRACT, DeliveryState.UNCERTAIN)
        )
    }

    @Test
    fun absenceOfDetectionIsNeverRecorded() {
        for (tier in PrivacyTier.values()) {
            assertNull(DeliveryEventLog.eventType(tier, DeliveryState.NONE))
        }
    }

    @Test
    fun nothingIsRecordedWhenThePhotoWasDiscarded() {
        for (state in DeliveryState.values()) {
            assertNull(DeliveryEventLog.eventType(PrivacyTier.DISCARD, state))
        }
    }

    @Test
    fun flagsOnlyForRevealedImages() {
        assertEquals(setOf("DELIVERY"), DeliveryEventLog.flags(PrivacyTier.REVEAL, InvoiceDetail.FULL))
        assertEquals(
            setOf("DELIVERY", "DELIVERY_OUTLINE"),
            DeliveryEventLog.flags(PrivacyTier.REVEAL, InvoiceDetail.OUTLINE_ONLY)
        )
        assertEquals(
            setOf("DELIVERY", "DELIVERY_OUTLINE"),
            DeliveryEventLog.flags(PrivacyTier.REVEAL, InvoiceDetail.MASK_PII)
        )
        assertTrue(DeliveryEventLog.flags(PrivacyTier.ABSTRACT, InvoiceDetail.FULL).isEmpty())
        assertTrue(DeliveryEventLog.flags(PrivacyTier.DISCARD, InvoiceDetail.FULL).isEmpty())
    }

    @Test
    fun detailHasFixedTokensOnly() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val d = DeliveryEventLog.detail("stop", 0.874f, "ab12cd34", InvoiceDetail.FULL, id)
        assertEquals("mode=stop;score=87;cal=ab12cd34;detail=FULL;rec=$id;gate=1", d)
        val parsed = DeliveryEventLog.parse(d)
        assertEquals(listOf("mode", "score", "cal", "detail", "rec", "gate"), parsed.keys.toList())
        assertEquals("87", parsed["score"])
    }

    @Test
    fun scoreIsClampedToPercentRange() {
        assertTrue(DeliveryEventLog.detail("auto", 1.7f, "x", InvoiceDetail.FULL, null).contains("score=100"))
        assertTrue(DeliveryEventLog.detail("auto", -0.4f, "x", InvoiceDetail.FULL, null).contains("score=0;"))
    }

    @Test
    fun detailCannotBeInjected() {
        val d = DeliveryEventLog.detail("a;score=1\nb=c", 0.5f, "m;x=y", InvoiceDetail.OUTLINE_ONLY, "bad;id=1")
        val parsed = DeliveryEventLog.parse(d)
        assertEquals(setOf("mode", "score", "cal", "detail", "gate"), parsed.keys)
        assertFalse(d.contains("\n"))
        assertEquals("50", parsed["score"])
        assertFalse(parsed.containsKey("rec"))
    }
}

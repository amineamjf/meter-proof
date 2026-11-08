package com.meterproof.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateEventLogTest {

    @Test
    fun flagNames() {
        assertEquals("PRIVACY_T0", GateEventLog.flag(PrivacyTier.REVEAL))
        assertEquals("PRIVACY_T2", GateEventLog.flag(PrivacyTier.ABSTRACT))
        assertEquals("PRIVACY_T3", GateEventLog.flag(PrivacyTier.DISCARD))
    }

    @Test
    fun discardDetailFormat() {
        val d = GateEventLog.discardDetail("auto", listOf("PERSON", "FAILED:FACE"), 120)
        assertEquals("mode=auto;reason=PERSON,FAILED:FACE;gate=${GateEventLog.GATE_VERSION};ms=120", d)
    }

    @Test
    fun detailCanBeParsedBack() {
        val d = GateEventLog.discardDetail("stop", listOf("UNCERTAIN_PERSON"), 7)
        val m = GateEventLog.parse(d)
        assertEquals("stop", m["mode"])
        assertEquals("UNCERTAIN_PERSON", m["reason"])
        assertEquals("7", m["ms"])
    }

    @Test
    fun separatorsCannotBeInjectedIntoDetail() {
        val d = GateEventLog.discardDetail("a;b|c", listOf("X;Y=Z", "عربي"), 1)
        // لا فاصلة منقوطة إضافية ولا شرطة عمودية: عدد الحقول ثابت (4)
        assertEquals(4, d.split(';').size)
        assertFalse(d.contains('|'))
        val m = GateEventLog.parse(d)
        assertEquals("a_b_c", m["mode"])
        assertEquals("X_Y_Z,____", m["reason"])
    }

    @Test
    fun failureClassification() {
        assertTrue(GateEventLog.isFailure(listOf("FAILED:PERSON")))
        assertTrue(GateEventLog.isFailure(listOf("PERSON", "TIMEOUT:FACE")))
        assertTrue(GateEventLog.isFailure(listOf("NO_DETECTORS")))
        assertTrue(GateEventLog.isFailure(listOf("RENDER_FAILED")))
        assertFalse(GateEventLog.isFailure(listOf("PERSON")))
        assertFalse(GateEventLog.isFailure(listOf("UNCERTAIN_FACE")))
        assertFalse(GateEventLog.isFailure(emptyList()))
    }
}

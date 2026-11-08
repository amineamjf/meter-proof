package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * بصمات مرجعية محسوبة خارج التطبيق بصيغة السجلات الحالية.
 * إن تغيّرت هذه القيم فمعناه أن صيغة الأدلة تغيّرت، وستفشل السجلات القديمة في التحقق.
 * لا يجوز تعديل القيم هنا؛ يجب إصلاح الكود.
 */
class RecordHashCompatTest {

    private fun rec(flags: String = "", meter: String = "", mode: String = "auto") =
        EvidenceStore.recordHash(
            "id1", "1700000000000", "33.5", "-7.6", "12.0", mode, "abc", "GENESIS", flags, meter
        )

    @Test
    fun legacyRecordWithoutOptionalFields() {
        assertEquals("0201bbd973b7b195dd5f90fb606be00efb9b378c286e118b525a54e69382f69b", rec())
    }

    @Test
    fun recordWithFlags() {
        assertEquals(
            "4ba7de409ccb7f816ac3dbba1516b8ec5043e182ac85c42ab98392c5ceeec1ab",
            rec(flags = "CLOCK_JUMP")
        )
    }

    @Test
    fun recordWithMeterOnly() {
        assertEquals(
            "61a47b298324d0e10edc488e1aa3e44a3f1e623fc7005518fea0c5f4acc2cdaf",
            rec(meter = "M1", mode = "meter")
        )
    }

    @Test
    fun recordWithFlagsAndMeter() {
        assertEquals(
            "5db7bd1005fbbdd2855efd02167fdd8bcda93d71a0d7e2b4d2f3c33f33f9786c",
            rec(flags = "MOCK_LOCATION", meter = "M1", mode = "meter")
        )
    }

    @Test
    fun legacyEvent() {
        val h = EvidenceStore.eventHash(
            "e1", "STOP_START", "1700000000000", "33.5", "-7.6", "12.0", "since=5", "GENESIS"
        )
        assertEquals("a7225b140dbe984535ba439aadee9694ed976b03cf602daf19e8a8bf7d7ab415", h)
    }

    @Test
    fun newDiscardEventUsesTheSameFormat() {
        val h = EvidenceStore.eventHash(
            "e2", "CAPTURE_DISCARDED", "1700000000000", "", "", "",
            "mode=auto;reason=PERSON;gate=1;ms=120", "HASHPREV"
        )
        assertEquals("998c5df280fe0dc44b6208b18d4535285991c9689fdf0337f8d51ef3ff3210c1", h)
    }
}

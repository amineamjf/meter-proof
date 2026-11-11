package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionLogTest {
    private val id = "3f2c1a9e-1111-4222-8333-444455556666"
    private val sha = "a".repeat(64)

    private fun ev(type: String, detail: String) = EventRec(type, 1L, null, null, detail)

    @Test fun detailRoundTrips() {
        val d = DeletionLog.detail(id, sha)!!
        assertEquals(id to sha, DeletionLog.parse(d))
    }

    @Test fun invalidInputsWriteNothing() {
        assertNull(DeletionLog.detail("x;y", sha))
        assertNull(DeletionLog.detail(id, "abc"))
        assertNull(DeletionLog.detail(id, "A".repeat(64)))
    }

    @Test fun malformedDetailIsIgnored() {
        assertNull(DeletionLog.parse(""))
        assertNull(DeletionLog.parse("rec=$id"))
        assertNull(DeletionLog.parse("rec=$id;sha=zz"))
        assertNull(DeletionLog.parse("garbage"))
    }

    @Test fun deletedCollectsOnlyDeletionEvents() {
        val events = listOf(
            ev("PHOTO_LABEL", "rec=$id;label=INVOICE"),
            ev(DeletionLog.EVENT, DeletionLog.detail(id, sha)!!),
            ev(DeletionLog.EVENT, "junk")
        )
        assertEquals(mapOf(id to sha), DeletionLog.deleted(events))
    }

    @Test fun deletionIsAcceptedOnlyWhenShaMatchesTheRecord() {
        val deleted = DeletionLog.deleted(listOf(ev(DeletionLog.EVENT, DeletionLog.detail(id, sha)!!)))
        assertTrue(DeletionLog.isRecordedDeletion(deleted, id, sha))
        assertFalse(DeletionLog.isRecordedDeletion(deleted, id, "b".repeat(64)))
        assertFalse(DeletionLog.isRecordedDeletion(deleted, "other-id-0000", sha))
        assertFalse(DeletionLog.isRecordedDeletion(emptyMap(), id, sha))
    }
}

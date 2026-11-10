package com.meterproof.app

import com.meterproof.app.PhotoLabels.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoLabelsTest {
    private val id1 = "11111111-2222-3333-4444-555555555555"
    private val id2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun ev(type: String, detail: String, t: Long = 0) = EventRec(type, t, null, null, detail)
    private fun lab(id: String, l: Label?) = ev(PhotoLabels.EVENT, PhotoLabels.detail(id, l)!!)

    @Test fun detailFormat() {
        assertEquals("rec=$id1;label=INVOICE", PhotoLabels.detail(id1, Label.INVOICE))
        assertEquals("rec=$id1;label=CLEARED", PhotoLabels.detail(id1, null))
    }

    @Test fun badIdIsRejected() {
        assertNull(PhotoLabels.detail("x;label=INVOICE", Label.NONE))
        assertNull(PhotoLabels.detail("", Label.NONE))
    }

    @Test fun latestLabelWins() {
        val m = PhotoLabels.latest(listOf(lab(id1, Label.INVOICE), lab(id1, Label.NONE)))
        assertEquals(Label.NONE, m[id1])
    }

    @Test fun clearingRemovesLabel() {
        val m = PhotoLabels.latest(listOf(lab(id1, Label.INVOICE), lab(id1, null), lab(id2, Label.UNCLEAR)))
        assertFalse(m.containsKey(id1))
        assertEquals(Label.UNCLEAR, m[id2])
    }

    @Test fun relabelAfterClearingWorks() {
        val m = PhotoLabels.latest(listOf(lab(id1, Label.INVOICE), lab(id1, null), lab(id1, Label.NONE)))
        assertEquals(Label.NONE, m[id1])
    }

    @Test fun otherEventsAndGarbageAreIgnored() {
        val m = PhotoLabels.latest(
            listOf(
                ev("STOP_START", "since=5"),
                ev(PhotoLabels.EVENT, "label=INVOICE"),
                ev(PhotoLabels.EVENT, "rec=$id1;label=WHATEVER"),
                ev(PhotoLabels.EVENT, "rec=$id1"),
                lab(id2, Label.INVOICE)
            )
        )
        assertEquals(setOf(id2), m.keys)
    }

    @Test fun countsAndReadiness() {
        val labels = HashMap<String, Label>()
        repeat(15) { labels["i$it"] = Label.INVOICE }
        repeat(14) { labels["n$it"] = Label.NONE }
        repeat(5) { labels["u$it"] = Label.UNCLEAR }
        val c = PhotoLabels.counts(labels)
        assertEquals(PhotoLabels.Counts(15, 14, 5), c)
        assertEquals(34, c.total)
        assertFalse(PhotoLabels.readyForTraining(c))
        labels["n99"] = Label.NONE
        assertTrue(PhotoLabels.readyForTraining(PhotoLabels.counts(labels)))
    }

    @Test fun unclearDoesNotCountTowardReadiness() {
        val c = PhotoLabels.Counts(invoice = 20, none = 10, unclear = 50)
        assertFalse(PhotoLabels.readyForTraining(c))
    }

    @Test fun abstractedPhotosAreNotUsable() {
        assertFalse(PhotoLabels.usableForTraining("PRIVACY_T2"))
        assertFalse(PhotoLabels.usableForTraining("MOCK_LOCATION,PRIVACY_T2"))
    }

    @Test fun clearOrLegacyPhotosAreUsable() {
        assertTrue(PhotoLabels.usableForTraining(""))
        assertTrue(PhotoLabels.usableForTraining("PRIVACY_LEGACY"))
        assertTrue(PhotoLabels.usableForTraining("PRIVACY_T0,DELIVERY"))
    }
}

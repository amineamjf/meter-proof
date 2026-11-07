package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryTextTest {

    @Test
    fun duration_formats() {
        assertEquals("45 ث", SummaryText.duration(45_000L))
        assertEquals("9 د 20 ث", SummaryText.duration(560_000L))
        assertEquals("1 س 12 د", SummaryText.duration(4_320_000L))
        assertEquals("0 ث", SummaryText.duration(-5L))
    }

    @Test
    fun distance_formats() {
        assertEquals("850 م", SummaryText.distance(850.4))
        assertEquals("3.20 كم", SummaryText.distance(3200.0))
    }

    @Test
    fun empty_day_message() {
        val s = DaySummaryBuilder.build(0L, 1000L, 500L, emptyList(), emptyList(), emptyList())
        assertTrue(SummaryText.statLines(s).first().contains("لا يوجد نشاط"))
        assertTrue(SummaryText.stopLines(s, 500L).isEmpty())
    }
}

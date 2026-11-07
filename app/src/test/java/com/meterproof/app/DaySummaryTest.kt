package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaySummaryTest {

    private fun ev(type: String, t: Long, detail: String = "") =
        EventRec(type, t, 33.0, -6.0, detail)

    @Test
    fun pairs_start_and_end() {
        val stops = StopPairing.pair(
            listOf(
                ev("STOP_START", 40_000L, "since=10000"),
                ev("STOP_END", 100_000L, "start=10000;dur=90")
            )
        )
        assertEquals(1, stops.size)
        assertEquals(10_000L, stops[0].startMs)
        assertEquals(100_000L, stops[0].endMs)
        assertEquals("end", stops[0].closedBy)
    }

    @Test
    fun unmatched_last_start_is_open() {
        val stops = StopPairing.pair(listOf(ev("STOP_START", 40_000L, "since=10000")))
        assertEquals(1, stops.size)
        assertNull(stops[0].endMs)
        assertEquals("open", stops[0].closedBy)
    }

    @Test
    fun new_session_closes_dangling_stop_as_interrupted() {
        val stops = StopPairing.pair(
            listOf(
                ev("STOP_START", 40_000L, "since=10000"),
                ev("SESSION_START", 500_000L)
            )
        )
        assertEquals(1, stops.size)
        assertEquals("interrupted", stops[0].closedBy)
        assertEquals(500_000L, stops[0].endMs)
    }

    @Test
    fun summary_counts_only_the_requested_day() {
        val day = 86_400_000L
        val track = listOf(
            TimedPoint(day + 1_000L, 33.0, -6.0),
            TimedPoint(day + 61_000L, 33.0 + 100.0 / 111_320.0, -6.0), // نحو 100 م خلال دقيقة
            TimedPoint(5_000L, 10.0, 10.0) // يوم آخر
        )
        val events = listOf(
            ev("SESSION_START", day + 500L),
            ev("STOP_START", day + 100_000L, "since=${day + 80_000L}"),
            ev("STOP_END", day + 200_000L, "start=${day + 80_000L};dur=120"),
            ev("TAMPER", day + 300_000L, "MOCK_LOCATION"),
            ev("STOP_START", 50_000L, "since=40000") // يوم آخر
        )
        val s = DaySummaryBuilder.build(
            dayStartMs = day,
            dayEndMs = 2 * day,
            nowMs = day + 400_000L,
            track = track,
            events = events,
            photoTimes = listOf(day + 10_000L, 3_000L)
        )
        assertEquals(1, s.photos)
        assertEquals(1, s.stops.size)
        assertEquals(120_000L, s.stopTotalMs)
        assertEquals(1, s.tamperCount)
        assertEquals(1, s.sessions)
        assertTrue("المسافة نحو 100 م", s.distanceM in 95.0..105.0)
        assertEquals(day + 500L, s.firstMs)
        assertEquals(day + 300_000L, s.lastMs)
    }

    @Test
    fun gps_jumps_and_long_gaps_are_not_counted_as_distance() {
        val track = listOf(
            TimedPoint(0L, 33.0, -6.0),
            TimedPoint(1_000L, 34.0, -6.0), // 111 كم في ثانية: قفزة
            TimedPoint(1_000L + 3_600_000L, 34.0, -6.01) // فجوة ساعة
        )
        val s = DaySummaryBuilder.build(0L, 86_400_000L, 86_400_000L, track, emptyList(), emptyList())
        assertEquals(0.0, s.distanceM, 0.001)
    }

    @Test
    fun open_stop_counts_until_now() {
        val s = DaySummaryBuilder.build(
            0L, 86_400_000L, 100_000L, emptyList(),
            listOf(ev("STOP_START", 40_000L, "since=10000")), emptyList()
        )
        assertEquals(90_000L, s.stopTotalMs)
    }
}

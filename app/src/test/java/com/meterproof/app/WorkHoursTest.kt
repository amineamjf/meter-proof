package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WorkHoursTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        val c = Calendar.getInstance(utc)
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    @Test
    fun dayShiftHours() {
        assertTrue(WorkHours.inHours(7, 7, 18))
        assertTrue(WorkHours.inHours(17, 7, 18))
        assertFalse(WorkHours.inHours(18, 7, 18))
        assertFalse(WorkHours.inHours(6, 7, 18))
        assertTrue(WorkHours.inHours(23, 0, 24))
    }

    @Test
    fun nightShiftHours() {
        assertTrue(WorkHours.inHours(22, 20, 6))
        assertTrue(WorkHours.inHours(3, 20, 6))
        assertFalse(WorkHours.inHours(6, 20, 6))
        assertFalse(WorkHours.inHours(12, 20, 6))
    }

    @Test
    fun endsOnlyAfterWorkingInsideHours() {
        // انتهى الدوام بعد أن عملت فيه: تُغلق
        assertTrue(WorkHours.shouldEnd(workedThisSession = true, inHoursNow = false))
        // ما زال الدوام قائمًا: لا
        assertFalse(WorkHours.shouldEnd(workedThisSession = true, inHoursNow = true))
        // بدأت قبل بداية الدوام ولم تعمل بعد: تنتظر ولا تُغلق (العيب المعاكس الذي نتجنبه)
        assertFalse(WorkHours.shouldEnd(workedThisSession = false, inHoursNow = false))
        assertFalse(WorkHours.shouldEnd(workedThisSession = false, inHoursNow = true))
    }

    @Test
    fun nextEndIsTodayWhenBeforeEnd() {
        assertEquals(at(2026, 11, 8, 18), WorkHours.nextEndMillis(at(2026, 11, 8, 10), 18, utc))
        assertEquals(at(2026, 11, 8, 18), WorkHours.nextEndMillis(at(2026, 11, 8, 17, 59), 18, utc))
    }

    @Test
    fun nextEndIsTomorrowWhenAtOrAfterEnd() {
        assertEquals(at(2026, 11, 9, 18), WorkHours.nextEndMillis(at(2026, 11, 8, 18), 18, utc))
        assertEquals(at(2026, 11, 9, 18), WorkHours.nextEndMillis(at(2026, 11, 8, 19), 18, utc))
    }

    @Test
    fun endHour24IsNextMidnight() {
        assertEquals(at(2026, 11, 9, 0), WorkHours.nextEndMillis(at(2026, 11, 8, 10), 24, utc))
        assertEquals(at(2026, 11, 10, 0), WorkHours.nextEndMillis(at(2026, 11, 9, 0), 24, utc))
    }

    @Test
    fun nextEndIsAlwaysInTheFutureWithinADay() {
        for (hour in 0..23) for (end in 1..24) {
            val now = at(2026, 3, 29, hour, 30)
            val next = WorkHours.nextEndMillis(now, end, utc)
            assertTrue("hour=$hour end=$end", next > now)
            assertTrue("hour=$hour end=$end", next - now <= 24 * 3_600_000L)
        }
    }
}

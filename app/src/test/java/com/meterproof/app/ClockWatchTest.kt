package com.meterproof.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockWatchTest {

    @Test
    fun normal_time_passage_is_not_a_jump() {
        val w = ClockWatch()
        assertFalse(w.check(1_000_000L, 5_000L))
        assertFalse(w.check(1_030_000L, 35_000L))
        assertFalse(w.check(1_090_500L, 95_000L))
    }

    @Test
    fun setting_clock_forward_is_detected() {
        val w = ClockWatch()
        w.check(1_000_000L, 5_000L)
        assertTrue(w.check(1_000_000L + 30_000L + 3_600_000L, 35_000L))
    }

    @Test
    fun setting_clock_back_is_detected() {
        val w = ClockWatch()
        w.check(10_000_000L, 5_000L)
        assertTrue(w.check(10_000_000L - 600_000L, 35_000L))
    }
}

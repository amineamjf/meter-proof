package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    @Test
    fun still_phone_never_reports_motion() {
        val d = MotionDetector()
        var changes = 0
        for (i in 0 until 100) {
            if (d.onSample(i * 200L, 0f, 0f, 9.81f) != null) changes++
        }
        assertEquals(0, changes)
        assertFalse(d.isMoving)
    }

    @Test
    fun walking_then_standing_is_detected_in_order() {
        val d = MotionDetector()
        val events = ArrayList<Boolean>()
        var t = 0L
        // مشي: اهتزاز بقوة ±3 م/ث² لمدة 4 ثوانٍ
        for (i in 0 until 20) {
            val z = if (i % 2 == 0) 12.8f else 6.8f
            d.onSample(t, 0f, 0f, z)?.let { events.add(it) }
            t += 200
        }
        assertEquals(listOf(true), events)
        assertTrue(d.isMoving)
        // وقوف لمدة 6 ثوانٍ
        for (i in 0 until 30) {
            d.onSample(t, 0f, 0f, 9.81f)?.let { events.add(it) }
            t += 200
        }
        assertEquals(listOf(true, false), events)
        assertFalse(d.isMoving)
    }

    @Test
    fun short_bump_does_not_count_as_walking() {
        val d = MotionDetector()
        var changes = 0
        var t = 0L
        for (i in 0 until 3) {
            if (d.onSample(t, 0f, 0f, if (i % 2 == 0) 12.8f else 6.8f) != null) changes++
            t += 200
        }
        for (i in 0 until 30) {
            if (d.onSample(t, 0f, 0f, 9.81f) != null) changes++
            t += 200
        }
        assertEquals(0, changes)
    }
}

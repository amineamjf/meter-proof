package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTrackerTest {

    private class Rec : MotionTracker.Sink {
        val tracks = ArrayList<Pair<MotionTracker.Fix, String>>()
        val starts = ArrayList<Pair<MotionTracker.Fix, Long>>()
        val ends = ArrayList<Triple<Long, Long, MotionTracker.Fix>>()
        val gps = ArrayList<Boolean>()

        override fun onTrackPoint(fix: MotionTracker.Fix, reason: String) {
            tracks.add(fix to reason)
        }

        override fun onStopStart(fix: MotionTracker.Fix, sinceMs: Long) {
            starts.add(fix to sinceMs)
        }

        override fun onStopEnd(startMs: Long, endMs: Long, fix: MotionTracker.Fix) {
            ends.add(Triple(startMs, endMs, fix))
        }

        override fun onGpsFast(fast: Boolean) {
            gps.add(fast)
        }
    }

    // درجة عرض واحدة تقارب 111320 مترًا
    private val metersPerDegree = 111_320.0
    private val baseLat = 33.0
    private val baseLon = -6.0

    private fun fix(tSec: Long, northM: Double = 0.0, acc: Float = 8f, speed: Float? = null) =
        MotionTracker.Fix(tSec * 1000, baseLat + northM / metersPerDegree, baseLon, acc, speed)

    @Test
    fun walking_records_points_and_never_declares_stop() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        for (i in 0..20) tr.onFix(fix(i * 5L, northM = i * 7.0, speed = 1.4f))
        assertTrue("يجب تسجيل نقاط كثيرة أثناء المشي", rec.tracks.size >= 8)
        assertTrue(rec.starts.isEmpty())
        assertFalse(tr.stopped)
    }

    @Test
    fun staying_in_place_declares_one_stop_and_stops_recording() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        for (i in 0..12) tr.onFix(fix(i * 5L))
        assertEquals(1, rec.starts.size)
        assertEquals(0L, rec.starts[0].second)
        assertTrue(tr.stopped)
        // نقطتان فقط قبل إعلان التوقف (عند 0 و20 ثانية)، ولا شيء بعده
        assertEquals(2, rec.tracks.size)
        assertEquals(false, rec.gps.last())
    }

    @Test
    fun stop_ends_when_leaving_radius_and_tracking_resumes() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        for (i in 0..12) tr.onFix(fix(i * 5L))
        tr.onFix(fix(70, northM = 100.0, speed = 1.5f))
        assertFalse(tr.stopped)
        assertEquals(1, rec.ends.size)
        assertEquals(0L, rec.ends[0].first)
        assertEquals(70_000L, rec.ends[0].second)
        assertEquals("resume", rec.tracks.last().second)
        assertEquals(true, rec.gps.last())
    }

    @Test
    fun motion_signal_gives_earlier_stop_end_time() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        for (i in 0..12) tr.onFix(fix(i * 5L))
        tr.onMotion(62_000L, true)
        tr.onFix(fix(70, northM = 100.0, speed = 1.5f))
        assertEquals(62_000L, rec.ends[0].second)
    }

    @Test
    fun small_jitter_inside_radius_does_not_end_stop() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        for (i in 0..12) tr.onFix(fix(i * 5L))
        tr.onFix(fix(65, northM = 10.0, acc = 12f))
        assertTrue(tr.stopped)
        assertTrue(rec.ends.isEmpty())
    }

    @Test
    fun brief_stop_records_last_fix_once() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        tr.onFix(fix(1, northM = 0.0, speed = 1.4f))
        tr.onFix(fix(6, northM = 5.0, speed = 0.2f))
        val before = rec.tracks.size
        tr.onMotion(8_000L, false)
        assertEquals(before + 1, rec.tracks.size)
        assertEquals("brief", rec.tracks.last().second)
        tr.onMotion(9_000L, false)
        assertEquals(before + 1, rec.tracks.size)
    }

    @Test
    fun brief_stop_ignores_stale_fix() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        tr.onFix(fix(1))
        tr.onFix(fix(5, northM = 3.0))
        val before = rec.tracks.size
        tr.onMotion(60_000L, false)
        assertEquals(before, rec.tracks.size)
    }

    @Test
    fun poor_accuracy_fixes_are_ignored() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        tr.onFix(fix(0, acc = 200f))
        assertTrue(rec.tracks.isEmpty())
    }

    @Test
    fun finish_closes_open_stop() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(), rec)
        for (i in 0..12) tr.onFix(fix(i * 5L))
        tr.finish(90_000L)
        assertEquals(1, rec.ends.size)
        assertEquals(90_000L, rec.ends[0].second)
        assertFalse(tr.stopped)
    }

    @Test
    fun custom_stop_threshold_is_respected() {
        val rec = Rec()
        val tr = MotionTracker(MotionTracker.Config(stopAfterMs = 10_000L), rec)
        for (i in 0..3) tr.onFix(fix(i * 5L))
        assertEquals(1, rec.starts.size)
    }
}

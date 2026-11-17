package com.meterproof.app

import com.meterproof.app.PassTracker.Fix
import com.meterproof.app.PassTracker.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassTrackerTest {
    private val lat0 = 33.0
    private fun north(m: Double) = lat0 + m / 111_195.0
    private fun fix(t: Long, n: Double, acc: Float = 8f) = Fix(t, north(n), -7.0, acc)
    private fun tg(key: String, n: Double, radius: Double = 25.0) = Target(key, north(n), -7.0, radius)

    /** يمرّ العامل على خط شمالي من الأهداف عند الصفر بسرعة 1.5 م/ث وقراءة كل ثانيتين. */
    private fun walkBy(tr: PassTracker, targets: List<Target>, t0: Long = 0): List<PassTracker.Pass> {
        val out = ArrayList<PassTracker.Pass>()
        var n = -50.0
        var t = t0
        while (n <= 50.0) {
            out.addAll(tr.onFix(fix(t, n), targets))
            n += 3.0
            t += 2_000
        }
        return out
    }

    @Test fun passingBySecondsAwayIsRecordedAtClosestApproach() {
        val r = walkBy(PassTracker(), listOf(tg("m:1", 0.0)))
        assertEquals(1, r.size)
        assertEquals("m:1", r[0].key)
        assertTrue("d=${r[0].distanceM}", r[0].distanceM <= 2.0)
        assertFalse(r[0].ambiguous)
    }

    @Test fun farawayTargetIsNotRecorded() {
        assertTrue(walkBy(PassTracker(), listOf(tg("m:1", 0.0).copy(lon = -7.01))).isEmpty())
    }

    @Test fun permanentTargetIsNeverRecordedAgain() {
        val tr = PassTracker()
        tr.markPermanent(listOf("m:1"))
        assertTrue(walkBy(tr, listOf(tg("m:1", 0.0))).isEmpty())
    }

    @Test fun pointsAreRecordedAgainAfterTheCooldownOnly() {
        val tr = PassTracker()
        val t = listOf(tg("pt:a", 0.0))
        assertEquals(1, walkBy(tr, t, 0).size)
        assertTrue(walkBy(tr, t, 60_000).isEmpty())
        assertEquals(1, walkBy(tr, t, 11 * 60_000L).size)
    }

    @Test fun twoCloseTargetsAreMarkedAmbiguous() {
        val r = walkBy(PassTracker(), listOf(tg("m:1", 0.0), tg("m:2", 6.0)))
        assertEquals(2, r.size)
        assertTrue(r.all { it.ambiguous })
    }

    @Test fun wellSeparatedTargetsAreNotAmbiguous() {
        val r = walkBy(PassTracker(), listOf(tg("m:1", 0.0), tg("m:2", 40.0)))
        assertEquals(2, r.size)
        assertTrue(r.none { it.ambiguous })
    }

    @Test fun markVisitedSuppressesADuplicatePass() {
        val tr = PassTracker()
        tr.markVisited("pt:a", 0)
        assertTrue(walkBy(tr, listOf(tg("pt:a", 0.0))).isEmpty())
    }

    @Test fun lingeringInsideTheRadiusEventuallyCommits() {
        val tr = PassTracker()
        val t = listOf(tg("m:1", 0.0))
        var out = emptyList<PassTracker.Pass>()
        var time = 0L
        while (time <= 40_000 && out.isEmpty()) {
            out = tr.onFix(fix(time, if (time == 0L) 10.0 else 5.0 + (time % 3)), t)
            time += 2_000
        }
        assertEquals(1, out.size)
    }

    @Test fun flushCommitsPendingCandidates() {
        val tr = PassTracker()
        assertTrue(tr.onFix(fix(0, 5.0), listOf(tg("m:1", 0.0))).isEmpty())
        assertEquals(1, tr.flush().size)
        assertTrue(tr.flush().isEmpty())
    }

    @Test fun wideRadiusForPoorAccuracyStillCommitsTheBestFix() {
        val tr = PassTracker()
        val t = listOf(tg("m:1", 0.0, 45.0))
        val out = ArrayList<PassTracker.Pass>()
        for ((i, n) in listOf(-40.0, -20.0, -6.0, 15.0, 40.0, 60.0).withIndex()) {
            out.addAll(tr.onFix(fix(i * 2_000L, n, 35f), t))
        }
        assertEquals(1, out.size)
        assertTrue(out[0].distanceM <= 6.5)
    }

    // ---- سجل المرور على العدادات

    private fun ev(id: Long, no: String, t: Long, amb: Boolean = false) =
        EventRec(MeterPassLog.EVENT, t, 33.0, -7.0, MeterPassLog.detail(id, no, 4.0, amb))

    private fun marker(id: Long, state: MeterMarkState, visited: Long = 0) = MeterPoint(
        id, "N$id", "العداد N$id", "لم يُسجَّل التسليم بعد", 33.0, -7.0, state, null, visited
    )

    @Test fun passesAreParsedWithLastOneWinning() {
        val p = MeterPassLog.passes(listOf(ev(5, "A-1", 10), ev(5, "A-1", 20, true), ev(6, "B", 30)))
        assertEquals(setOf(5L, 6L), p.keys)
        assertEquals(20L, p[5]!!.t)
        assertTrue(p[5]!!.ambiguous)
    }

    @Test fun detailStripsUnsafeCharactersFromMeterNo() {
        val d = MeterPassLog.detail(1, "A;1=x b", 3.0, false)
        assertEquals(1L, MeterPassLog.passes(listOf(EventRec(MeterPassLog.EVENT, 1, null, null, d)))[1L]!!.meterId)
        assertFalse(d.substringAfter("no=").substringBefore(";").contains("="))
    }

    @Test fun overlayMarksOnlyPendingMetersAsVisited() {
        val ms = listOf(
            marker(1, MeterMarkState.PENDING),
            marker(2, MeterMarkState.DONE, 99),
            marker(3, MeterMarkState.PENDING)
        )
        val out = MeterPassLog.overlay(ms, listOf(ev(1, "N1", 500), ev(2, "N2", 600)))
        assertEquals(MeterMarkState.ARRIVED, out[0].state)
        assertEquals(500L, out[0].visitedAt)
        assertTrue(out[0].detail.contains("مرّ به العامل بلا صورة"))
        assertEquals(MeterMarkState.DONE, out[1].state)
        assertEquals(99L, out[1].visitedAt)
        assertEquals(MeterMarkState.PENDING, out[2].state)
    }

    @Test fun overlayWithoutPassesReturnsTheSameList() {
        val ms = listOf(marker(1, MeterMarkState.PENDING))
        assertTrue(MeterPassLog.overlay(ms, emptyList()) === ms)
    }
}

package com.meterproof.app

import com.meterproof.app.StopPointRecorder.Action
import com.meterproof.app.StopPointRecorder.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StopPointsTest {
    private var n = 0
    private fun rec(cfg: StopPointRecorder.Config = StopPointRecorder.Config()) =
        StopPointRecorder(cfg) { "p${++n}" }

    private fun fix(t: Long, dLatM: Double = 0.0, acc: Float = 8f) =
        Fix(t, 33.0 + dLatM / 111_195.0, -7.0, acc)

    /** مشي من t0 ثم توقف عند t0+walk مع قراءة حديثة. */
    private fun walkThenStop(r: StopPointRecorder, t0: Long, dLatM: Double = 0.0, walk: Long = 6_000L): List<Action> {
        r.onMotion(t0, true)
        r.onFix(fix(t0 + walk - 1_000, dLatM))
        return r.onMotion(t0 + walk, false)
    }

    @Test fun newPointAfterWalkingThenStopping() {
        val a = walkThenStop(rec(), 0)
        assertEquals(1, a.size)
        assertTrue(a[0] is Action.NewPoint)
    }

    @Test fun noPointWithoutPriorWalking() {
        val r = rec()
        r.onFix(fix(1_000))
        assertTrue(r.onMotion(2_000, false).isEmpty())
    }

    @Test fun shortWalkingSpellIsIgnored() {
        val r = rec()
        r.onMotion(0, true)
        r.onFix(fix(1_000))
        assertTrue(r.onMotion(2_000, false).isEmpty())
    }

    @Test fun badAccuracyFixIsIgnored() {
        val r = rec()
        r.onMotion(0, true)
        r.onFix(fix(5_000, acc = 80f))
        assertTrue(r.onMotion(6_000, false).isEmpty())
    }

    @Test fun waitsForAFreshFixWhenNoneIsRecent() {
        val r = rec()
        r.onMotion(0, true)
        assertTrue(r.onMotion(6_000, false).isEmpty())
        val a = r.onFix(fix(8_000))
        assertTrue(a.single() is Action.NewPoint)
    }

    @Test fun pendingExpiresIfNoFixArrivesInTime() {
        val r = rec()
        r.onMotion(0, true)
        r.onMotion(6_000, false)
        assertTrue(r.onFix(fix(12_000)).isEmpty())
    }

    @Test fun secondStopNearTheSamePointIsNotRecordedAgain() {
        val r = rec()
        walkThenStop(r, 0)
        val a = walkThenStop(r, 20_000, dLatM = 10.0)
        assertTrue(a.single() is Action.Visit)
        assertEquals(1, r.points().size)
    }

    @Test fun farStopIsANewPoint() {
        val r = rec()
        walkThenStop(r, 0)
        val a = walkThenStop(r, 20_000, dLatM = 60.0)
        assertTrue(a.single() is Action.NewPoint)
        assertEquals(2, r.points().size)
    }

    @Test fun poorAccuracyWidensTheDedupRadius() {
        val r = rec()
        walkThenStop(r, 0)
        r.onMotion(20_000, true)
        r.onFix(Fix(25_000, 33.0 + 27.0 / 111_195.0, -7.0, 28f))
        val a = r.onMotion(26_000, false)
        assertTrue(a.single() is Action.Visit)
    }

    @Test fun knownPointsFromEarlierSessionsAreRecognisedNotRecorded() {
        val r = rec()
        r.load(listOf(StopPointLog.Known("old", 33.0, -7.0, 6f, 5)))
        val a = walkThenStop(r, 0, dLatM = 8.0)
        val v = a.single() as Action.Visit
        assertEquals("old", v.id)
        assertEquals(0, n)
    }

    @Test fun visitIsNotRepeatedWithinCooldown() {
        val r = rec()
        r.load(listOf(StopPointLog.Known("old", 33.0, -7.0, 6f, 5)))
        walkThenStop(r, 0)
        assertTrue(walkThenStop(r, 30_000).isEmpty())
        assertTrue(walkThenStop(r, 11 * 60_000L).single() is Action.Visit)
    }

    @Test fun longStopRefinesOnceByAccuracyWeightedAverage() {
        val r = rec()
        val first = walkThenStop(r, 0, dLatM = 0.0)
        assertTrue(first.single() is Action.NewPoint)
        // الوقوف مستمر منذ t=6000
        assertTrue(r.onFix(fix(8_000, dLatM = 4.0, acc = 4f)).isEmpty())
        assertTrue(r.onFix(fix(10_000, dLatM = 4.0, acc = 4f)).isEmpty())
        val a = r.onFix(fix(12_500, dLatM = 4.0, acc = 4f))
        val ref = a.single() as Action.Refined
        assertTrue(ref.samples >= 3)
        // القراءات الدقيقة (4 م) ترجّح على القراءة الأولى (8 م): النتيجة أقرب إلى 4 م منها إلى 0
        val d = Geo.distanceM(33.0, -7.0, ref.lat, ref.lon)
        assertTrue("d=$d", d > 2.5)
        // لا تحسين ثانٍ
        assertTrue(r.onFix(fix(14_000, dLatM = 4.0, acc = 4f)).isEmpty())
        assertEquals(1, r.points().size)
        assertEquals(ref.samples, r.points()[0].samples)
    }

    @Test fun resumingWalkingBeforeRefineTimeCancelsRefinement() {
        val r = rec()
        walkThenStop(r, 0)
        r.onFix(fix(8_000, acc = 5f))
        r.onMotion(9_000, true)
        assertTrue(r.onFix(fix(13_000, acc = 5f)).isEmpty())
    }

    @Test fun duplicateStopIsNeverRefined() {
        val r = rec()
        r.load(listOf(StopPointLog.Known("old", 33.0, -7.0, 6f, 5)))
        walkThenStop(r, 0)
        assertTrue(r.onFix(fix(8_000)).isEmpty())
        assertTrue(r.onFix(fix(13_000)).isEmpty())
    }

    // ---- سجل الأحداث

    private fun ev(type: String, lat: Double?, lon: Double?, detail: String) = EventRec(type, 1L, lat, lon, detail)

    @Test fun knownPointsAreRebuiltFromEventsWithRefinementOverriding() {
        val events = listOf(
            ev(StopPointLog.EVENT_POINT, 33.0, -7.0, StopPointLog.pointDetail("a", 9f)),
            ev(StopPointLog.EVENT_POINT, 33.1, -7.1, StopPointLog.pointDetail("b", 12f)),
            ev(StopPointLog.EVENT_REFINED, 33.0002, -7.0002, StopPointLog.refinedDetail("a", 4, 1.5, 3.2f)),
            ev(StopPointLog.EVENT_REFINED, 1.0, 1.0, StopPointLog.refinedDetail("ghost", 3, 1.0, 3f)),
            ev("PHOTO_LABEL", 33.0, -7.0, "pt=zzz")
        )
        val k = StopPointLog.known(events)
        assertEquals(listOf("a", "b"), k.map { it.id })
        assertEquals(33.0002, k[0].lat, 1e-9)
        assertEquals(4, k[0].samples)
        assertEquals(33.1, k[1].lat, 1e-9)
    }

    @Test fun eventsWithoutLocationAreIgnored() {
        assertTrue(StopPointLog.known(listOf(ev(StopPointLog.EVENT_POINT, null, null, "pt=a;acc=5"))).isEmpty())
    }

    @Test fun visitsAreCountedPerPoint() {
        val events = listOf(
            ev(StopPointLog.EVENT_VISIT, 33.0, -7.0, StopPointLog.visitDetail("a", 6.0)),
            ev(StopPointLog.EVENT_VISIT, 33.0, -7.0, StopPointLog.visitDetail("a", 3.0)),
            ev(StopPointLog.EVENT_VISIT, 33.0, -7.0, StopPointLog.visitDetail("b", 3.0))
        )
        assertEquals(mapOf("a" to 2, "b" to 1), StopPointLog.visitCounts(events))
    }
}

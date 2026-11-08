package com.meterproof.app.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryMachineTest {

    private val p = DeliveryParams()

    private fun outcome(frames: List<Feat>, params: DeliveryParams = p): DeliveryMachine.Outcome? {
        val m = DeliveryMachine(params)
        for (f in frames) m.onFeat(f)?.let { return it }
        return m.finish()
    }

    @Test
    fun realDeliveryOnTheDoorIsConfirmed() {
        for (seed in 1L..40L) {
            val o = outcome(SyntheticScenes.deliver(seed))
            assertNotNull("seed $seed", o)
            assertEquals("seed $seed", DeliveryMachine.Kind.CONFIRMED, o!!.kind)
            assertEquals("seed $seed", "ON", o.place)
            assertNotNull(o.region)
        }
    }

    @Test
    fun deliveryBesideTheDoorIsConfirmedAsBeside() {
        for (seed in 1L..20L) {
            val left = outcome(SyntheticScenes.deliver(seed, besideLeft = true))
            val right = outcome(SyntheticScenes.deliver(seed, besideLeft = false))
            assertEquals(DeliveryMachine.Kind.CONFIRMED, left!!.kind)
            assertEquals("BESIDE", left.place)
            assertEquals(DeliveryMachine.Kind.CONFIRMED, right!!.kind)
            assertEquals("BESIDE", right.place)
        }
    }

    @Test
    fun confirmedRegionSurroundsTheObject() {
        val o = outcome(SyntheticScenes.deliver(3))!!
        val r = o.region!!
        assertTrue(r.left < r.right && r.top < r.bottom)
        assertTrue(r.right - r.left < 0.3f)
        assertTrue(o.score in 0.35f..1f)
    }

    @Test
    fun underTheDoorIsUncertainUntilCalibrationProvesItReliable() {
        for (seed in 1L..20L) {
            val o = outcome(SyntheticScenes.deliverUnder(seed))
            assertNotNull("seed $seed", o)
            assertEquals("seed $seed", DeliveryMachine.Kind.UNCERTAIN, o!!.kind)
            assertEquals("UNDER", o.place)
            assertEquals(listOf("UNDER_PATTERN"), o.reasons)
        }
    }

    @Test
    fun underPatternIsConfirmedOnlyWhenTrusted() {
        val trusted = p.copy(underTrusted = true)
        val o = outcome(SyntheticScenes.deliverUnder(5), trusted)!!
        assertEquals(DeliveryMachine.Kind.CONFIRMED, o.kind)
        assertEquals("UNDER", o.place)
        assertNull("no image region for under-door", o.region)
    }

    @Test
    fun ordinaryMovementsNeverProduceAnOutcome() {
        for (seed in 1L..30L) {
            assertNull("walk $seed", outcome(SyntheticScenes.walk(seed)))
            assertNull("bend $seed", outcome(SyntheticScenes.bend(seed)))
            assertNull("touch $seed", outcome(SyntheticScenes.touchDoor(seed)))
            assertNull("hand $seed", outcome(SyntheticScenes.handOnly(seed)))
            assertNull("wall $seed", outcome(SyntheticScenes.brightWall(seed)))
            assertNull("object $seed", outcome(SyntheticScenes.objectWithoutHand(seed)))
        }
    }

    @Test
    fun movingAPaperAroundIsNeverConfirmedOnlyUncertain() {
        for (seed in 1L..30L) {
            val o = outcome(SyntheticScenes.movePaper(seed))
            assertNotNull("seed $seed", o)
            assertEquals("seed $seed", DeliveryMachine.Kind.UNCERTAIN, o!!.kind)
            assertEquals(listOf("NO_OBJECT"), o.reasons)
        }
    }

    @Test
    fun shortGlimpseOfHandWithPaperIsNotEnoughToStartASequence() {
        val rnd = SyntheticScenes.deliver(9).take(12) // وقوف ثم لحظة قصيرة فقط
        assertNull(outcome(rnd))
    }

    @Test
    fun cameraShakeDuringHoldDoesNotCountAsHolding() {
        val shaky = SyntheticScenes.deliver(11).map { if (it.skin > 0.03f) it.copy(motion = 0.30f) else it }
        // اليد تظهر لكن الكاميرا تهتز بعنف طوال الوقت: لا إمساك معتبر، فلا تسليم
        assertNull(outcome(shaky))
    }

    @Test
    fun objectOnlyCountsAfterTheHandHasLeft() {
        // اليد باقية طوال الوقت (لا انسحاب): لا جسم موضوع مؤكد
        val stay = SyntheticScenes.deliver(4).map { f ->
            if (f.obj > 0f) f.copy(skin = 0.05f, skinX = 0.5f, skinY = 0.7f) else f
        }
        val o = outcome(stay)
        assertTrue(o == null || o.kind == DeliveryMachine.Kind.UNCERTAIN)
    }

    @Test
    fun movingObjectIsNotTreatedAsPlaced() {
        val moving = SyntheticScenes.deliver(6).mapIndexed { i, f ->
            if (f.obj > 0f) f.copy(objX = 0.2f + (i % 7) * 0.08f) else f
        }
        val o = outcome(moving)
        assertTrue(o == null || o.kind == DeliveryMachine.Kind.UNCERTAIN)
    }

    @Test
    fun resultIsReportedOnlyOnce() {
        val m = DeliveryMachine(p)
        var count = 0
        for (f in SyntheticScenes.deliver(2) + SyntheticScenes.deliver(2)) {
            if (m.onFeat(f) != null) count++
        }
        assertEquals(1, count)
    }

    @Test
    fun resetAllowsANewSequence() {
        val m = DeliveryMachine(p)
        for (f in SyntheticScenes.deliver(2)) m.onFeat(f)
        m.reset()
        var got: DeliveryMachine.Outcome? = null
        for (f in SyntheticScenes.deliver(3)) got = m.onFeat(f) ?: got
        assertNotNull(got)
    }

    @Test
    fun zoneNames() {
        assertEquals("LOWER_CENTER", DeliveryMachine.zoneOf(0.5f, 0.8f))
        assertEquals("UPPER_LEFT", DeliveryMachine.zoneOf(0.1f, 0.2f))
        assertEquals("MIDDLE_RIGHT", DeliveryMachine.zoneOf(0.9f, 0.5f))
    }

    @Test
    fun windowEndMidSequenceIsUncertain() {
        val frames = SyntheticScenes.deliver(8)
        val cut = frames.takeWhile { it.t < 1000L + 1500L + 2500L } // قبل الانسحاب
        val o = outcome(cut)
        assertNotNull(o)
        assertEquals(DeliveryMachine.Kind.UNCERTAIN, o!!.kind)
        assertEquals(listOf("WINDOW_END"), o.reasons)
    }
}

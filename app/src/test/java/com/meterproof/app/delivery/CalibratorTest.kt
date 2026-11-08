package com.meterproof.app.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratorTest {

    private fun trainingSet(from: Long, n: Int): List<Sample> {
        val to = from + n - 1
        return SyntheticScenes.asSamples(Label.POS_ON, { SyntheticScenes.deliver(it) }, from..to) +
            SyntheticScenes.asSamples(Label.POS_BESIDE, { SyntheticScenes.deliver(it, besideLeft = it % 2L == 0L) }, from..to) +
            SyntheticScenes.asSamples(Label.NEG_WALK, { SyntheticScenes.walk(it) }, from..to) +
            SyntheticScenes.asSamples(Label.NEG_BEND, { SyntheticScenes.bend(it) }, from..to) +
            SyntheticScenes.asSamples(Label.NEG_TOUCH, { SyntheticScenes.touchDoor(it) }, from..to) +
            SyntheticScenes.asSamples(Label.NEG_PAPER, { SyntheticScenes.movePaper(it) }, from..to) +
            SyntheticScenes.asSamples(Label.NEG_HAND, { SyntheticScenes.handOnly(it) }, from..to)
    }

    @Test
    fun defaultsAlreadySeparateTheSyntheticClasses() {
        val c = Calibrator.count(DeliveryParams(), trainingSet(1, 10))
        assertEquals(0, c.fp)
        assertEquals(0, c.fn)
        assertEquals(20, c.tp)
    }

    @Test
    fun tuningRecoversFromBadStartingParameters() {
        val bad = DeliveryParams(skinMin = 0.2f, objMin = 0.05f)
        val train = trainingSet(1, 8)
        val before = Calibrator.count(bad, train)
        assertEquals("bad params miss everything", 0, before.tp)

        val tuned = Calibrator.tune(train, bad)
        val after = Calibrator.count(tuned, train)
        assertTrue("recall after tuning ${after.recall()}", after.recall() >= 0.8f)
        assertEquals("no false alarms", 0, after.fp)
        assertTrue(after.objective() > before.objective())
    }

    @Test
    fun tunedParametersGeneralizeToUnseenSamples() {
        val bad = DeliveryParams(skinMin = 0.2f, objMin = 0.05f)
        val tuned = Calibrator.tune(trainingSet(1, 8), bad)
        val unseen = trainingSet(100, 12)
        val c = Calibrator.count(tuned, unseen)
        assertTrue("recall on unseen ${c.recall()}", c.recall() >= 0.8f)
        assertEquals("false alarms on unseen", 0, c.fp)
    }

    @Test
    fun falseAlarmsAreCostlierThanMisses() {
        val onlyFp = Calibrator.Counts(fp = 1)
        val onlyFn = Calibrator.Counts(fn = 1)
        assertTrue(onlyFp.objective() < onlyFn.objective())
    }

    @Test
    fun reportContainsCrossValidationWhenSamplesAreEnough() {
        val set = trainingSet(1, 4) // 28 عينة
        val r = Calibrator.report(set)
        assertNotNull(r.crossValidated)
        val cv = r.crossValidated!!
        assertEquals(set.size, cv.tp + cv.fn + cv.fp + cv.tn + cv.uncertainPos + cv.uncertainNeg)
        assertEquals(0, cv.fp)
        assertEquals(1, r.params.version)
    }

    @Test
    fun reportWarnsWhenSamplesAreTooFew() {
        val few = SyntheticScenes.asSamples(Label.POS_ON, { SyntheticScenes.deliver(it) }, 1L..2L)
        val r = Calibrator.report(few)
        assertTrue(r.warnings.isNotEmpty())
        assertEquals(null, r.crossValidated)
    }

    @Test
    fun underDoorStaysUntrustedWithoutEnoughEvidence() {
        val set = trainingSet(1, 4) +
            SyntheticScenes.asSamples(Label.POS_UNDER, { SyntheticScenes.deliverUnder(it) }, 1L..2L)
        assertEquals(false, Calibrator.tune(set).underTrusted)
    }

    @Test
    fun underDoorBecomesTrustedWhenSamplesProveIt() {
        val set = trainingSet(1, 4) +
            SyntheticScenes.asSamples(Label.POS_UNDER, { SyntheticScenes.deliverUnder(it) }, 1L..4L)
        assertEquals(true, Calibrator.tune(set).underTrusted)
    }

    @Test
    fun underDoorStaysUntrustedIfItWouldCauseFalseAlarms() {
        // سلبيات تُنتج نمط "الورقة اختفت واليد ظاهرة" (يد أمسكت ورقة ثم أخفتها في الجيب أسفل الإطار)
        val lowPocket = SyntheticScenes.asSamples(Label.NEG_PAPER, { SyntheticScenes.deliverUnder(it) }, 50L..60L)
        val set = trainingSet(1, 4) +
            SyntheticScenes.asSamples(Label.POS_UNDER, { SyntheticScenes.deliverUnder(it) }, 1L..3L) +
            lowPocket
        assertEquals(false, Calibrator.tune(set).underTrusted)
    }

    @Test
    fun emptySamplesKeepStartingParameters() {
        val p = DeliveryParams(skinMin = 0.03f)
        assertEquals(p, Calibrator.tune(emptyList(), p))
    }
}

package com.meterproof.app.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingStatusTest {

    private fun feats() = InvoiceFeatures.fromList(List(InvoiceFeatures.SIZE) { 0.5f })!!

    private fun samples(pos: Int, neg: Int, t0: Long = 1000L): List<Sample> =
        (0 until pos).map { Sample(feats(), true, t0 + it) } +
            (0 until neg).map { Sample(feats(), false, t0 + 500 + it) }

    private fun model(precision: Float, trainedAt: Long = 5000L) = InvoiceModel(
        mean = List(InvoiceFeatures.SIZE) { 0f },
        std = List(InvoiceFeatures.SIZE) { 1f },
        weights = List(InvoiceFeatures.SIZE) { 0f },
        bias = 0f,
        acceptThreshold = 0.8f,
        uncertainThreshold = 0.5f,
        trainedAt = trainedAt,
        positives = 15,
        negatives = 15,
        metrics = ModelMetrics(precision, 0.9f, 0.95f, 4)
    )

    @Test
    fun noSamplesMeansNotStarted() {
        val st = TrainingStatus.compute(emptyList(), emptyList(), null, false, true)
        assertTrue(st.headline.contains("لم يبدأ"))
        assertFalse(st.ready)
        assertEquals(2, st.missing.size)
    }

    @Test
    fun missingCountsAreExact() {
        val st = TrainingStatus.compute(samples(15, 8), emptyList(), null, false, true)
        assertTrue(st.missing.any { it.contains("ينقصك 7 عينات") && it.contains("بلا فاتورة") })
        assertFalse(st.missing.any { it.contains("فاتورة ظاهرة") })
        assertTrue(st.headline.contains("منتصف الطريق"))
        assertTrue(st.headline.contains("23 من 30"))
    }

    @Test
    fun earlyStageIsBeginning() {
        val st = TrainingStatus.compute(samples(3, 2), emptyList(), null, false, true)
        assertTrue(st.headline.contains("في بدايته"))
    }

    @Test
    fun enoughButNeverTrainedAsksToTrain() {
        val st = TrainingStatus.compute(samples(15, 15), emptyList(), null, false, true)
        assertTrue(st.headline.contains("لم تُقَس"))
        assertTrue(st.missing.any { it.contains("تدريب ومعايرة") })
    }

    @Test
    fun lowPrecisionShowsMidWayHeadlineWithNumbers() {
        val hist = listOf(TrainRound(5000L, 15, 15, 0.82f, 0.9f, "LOW_PRECISION"))
        val st = TrainingStatus.compute(samples(15, 15), hist, null, false, true)
        assertEquals("التدريب في منتصف الطريق: الدقة 82% والهدف 95%", st.headline)
        assertTrue(st.missing.any { it.contains("أقل من الهدف") })
        assertFalse(st.ready)
        assertEquals(listOf(0.82f), st.precisionHistory)
    }

    @Test
    fun precisionJustBelowTargetIsNotShownAsTarget() {
        val hist = listOf(TrainRound(5000L, 15, 15, 0.946f, 0.9f, "LOW_PRECISION"))
        val st = TrainingStatus.compute(samples(15, 15), hist, null, false, true)
        assertTrue(st.headline.contains("94%"))
    }

    @Test
    fun readyAndOffExplainsNotEnabledYet() {
        val m = model(0.96f)
        val hist = listOf(TrainRound(5000L, 15, 15, 0.96f, 0.9f, "READY"))
        val st = TrainingStatus.compute(samples(15, 15), hist, m, false, true)
        assertTrue(st.ready)
        assertFalse(st.effective)
        assertTrue(st.headline.contains("جاهز"))
        assertTrue(st.disabledReason!!.contains("لم تفعّله"))
    }

    @Test
    fun readyOnAndFullPrivacyIsEffective() {
        val m = model(0.96f)
        val hist = listOf(TrainRound(5000L, 15, 15, 0.96f, 0.9f, "READY"))
        val st = TrainingStatus.compute(samples(15, 15), hist, m, true, true)
        assertTrue(st.effective)
        assertNull(st.disabledReason)
        assertEquals("الكاشف جاهز ومفعّل", st.headline)
        assertFalse(st.facesOnlyWarning)
    }

    @Test
    fun readyOnButFacesOnlyIsNotEffectiveAndWarns() {
        val m = model(0.96f)
        val hist = listOf(TrainRound(5000L, 15, 15, 0.96f, 0.9f, "READY"))
        val st = TrainingStatus.compute(samples(15, 15), hist, m, true, false)
        assertFalse(st.effective)
        assertTrue(st.facesOnlyWarning)
        assertTrue(st.headline.contains("وجوه فقط"))
        assertTrue(st.disabledReason!!.contains("وجوه فقط"))
    }

    @Test
    fun enabledWithoutModelIsStopped() {
        val st = TrainingStatus.compute(samples(15, 15), emptyList(), null, true, true)
        assertFalse(st.effective)
        assertTrue(st.disabledReason!!.contains("متوقف"))
    }

    @Test
    fun newSamplesSinceLastTrainingAreCounted() {
        val hist = listOf(TrainRound(1200L, 15, 15, 0.82f, 0.9f, "LOW_PRECISION"))
        // الإيجابية t=1000..1014 قبل التدريب، والسلبية t=1500..1514 بعده
        val s = samples(15, 15, 1000L)
        val st = TrainingStatus.compute(s, hist, null, false, true)
        assertEquals(15, st.newSamplesSinceTrain)
        assertTrue(st.missing.any { it.contains("منذ آخر تدريب") })
    }

    @Test
    fun arabicSamplesWord() {
        assertEquals("عينة واحدة", TrainingStatus.samplesWord(1))
        assertEquals("عينتان", TrainingStatus.samplesWord(2))
        assertEquals("7 عينات", TrainingStatus.samplesWord(7))
        assertEquals("12 عينة", TrainingStatus.samplesWord(12))
    }

    @Test
    fun historyCodecRoundTripAndSkipsBadLines() {
        val rounds = listOf(
            TrainRound(1L, 15, 15, 0.8f, 0.7f, "LOW_PRECISION"),
            TrainRound(2L, 3, 4, null, null, "NEED_MORE"),
            TrainRound(3L, 20, 18, 0.97f, 0.9f, "READY")
        )
        val text = TrainHistoryCodec.encode(rounds) + "\nجزء تالف\nR;x;y"
        assertEquals(rounds, TrainHistoryCodec.decode(text))
    }
}

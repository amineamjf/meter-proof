package com.meterproof.app.delivery

import com.meterproof.app.PhotoLabels.Label
import com.meterproof.app.delivery.GalleryTraining.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GalleryTrainingTest {

    private fun feat(r: Random, positive: Boolean): InvoiceFeatures {
        fun j(base: Float, spread: Float) = base + (r.nextFloat() - 0.5f) * spread
        return if (positive) {
            InvoiceFeatures(j(0.15f, 0.04f), j(0.92f, 0.04f), j(0.45f, 0.10f), j(0.30f, 0.06f), j(0.05f, 0.03f), j(0.30f, 0.06f))
        } else {
            InvoiceFeatures(j(0.06f, 0.04f), j(0.70f, 0.10f), j(0.15f, 0.10f), j(0.08f, 0.06f), j(0.15f, 0.06f), j(0.05f, 0.04f))
        }
    }

    private fun samples(nPos: Int, nNeg: Int, seed: Long = 1): List<Sample> {
        val r = Random(seed)
        val out = ArrayList<Sample>()
        for (i in 0 until nPos) out.add(Sample(feat(r, true), true, 1000L + i))
        for (i in 0 until nNeg) out.add(Sample(feat(r, false), false, 5000L + i))
        return out
    }

    private fun entries(nPos: Int, nNeg: Int, seed: Long = 1): List<GalleryTraining.Entry> =
        samples(nPos, nNeg, seed).mapIndexed { i, s ->
            GalleryTraining.Entry("id$i", if (s.positive) Label.INVOICE else Label.NONE, s.features, s.t)
        }

    // ------------------------------------------------------------ outOfFoldScores

    @Test fun oofNeedsTwoOfEachClass() {
        assertNull(InvoiceTrainer.outOfFoldScores(samples(1, 10)))
        assertNull(InvoiceTrainer.outOfFoldScores(samples(10, 1)))
        assertNotNull(InvoiceTrainer.outOfFoldScores(samples(2, 2)))
    }

    @Test fun oofIsAlignedToInputOrder() {
        val s = samples(20, 20)
        val a = InvoiceTrainer.outOfFoldScores(s)!!
        val rev = s.reversed()
        val b = InvoiceTrainer.outOfFoldScores(rev)!!
        for (i in s.indices) {
            assertEquals("sample $i", a[i], b[s.size - 1 - i], 1e-12)
        }
    }

    @Test fun oofAgreesWithTrainMetrics() {
        val s = samples(25, 25, seed = 7)
        val r = InvoiceTrainer.train(s, 0L) as InvoiceTrainer.Result.Ready
        val oof = InvoiceTrainer.outOfFoldScores(s)!!
        val th = r.model.acceptThreshold.toDouble()
        val predicted = oof.count { it >= th }
        val tp = s.indices.count { s[it].positive && oof[it] >= th }
        assertEquals(r.model.metrics.precision.toDouble(), tp.toDouble() / predicted, 1e-5)
        assertEquals(r.model.metrics.recall.toDouble(), tp.toDouble() / 25, 1e-5)
    }

    // ------------------------------------------------------------ run

    @Test fun separableDataIsReadyAndMostlyCorrect() {
        val rep = GalleryTraining.run(entries(20, 20), 0L)
        assertTrue(rep.result is InvoiceTrainer.Result.Ready)
        assertTrue(rep.thresholdsFromModel)
        assertEquals(40, rep.trainedOn)
        assertEquals(0, rep.outcomes.count { it.verdict == Verdict.FALSE_ACCEPT })
        assertTrue("correct=${rep.correct}", rep.correct >= 36)
        assertTrue(rep.outcomes.all { it.score != null && !it.noPaper })
    }

    @Test fun tooFewSamplesIsNotReadyAndUnknown() {
        val rep = GalleryTraining.run(entries(5, 5), 0L)
        val nr = rep.result as InvoiceTrainer.Result.NotReady
        assertTrue("NEED_MORE_POSITIVES" in nr.reasons)
        assertTrue("NEED_MORE_NEGATIVES" in nr.reasons)
        assertFalse(rep.thresholdsFromModel)
        // مع 5 و5 يوجد تقدير بالتحقق المتقاطع (للعرض فقط)، فلا تكون النتيجة UNKNOWN
        assertTrue(rep.outcomes.all { it.score != null })
    }

    @Test fun veryFewSamplesGiveUnknown() {
        val rep = GalleryTraining.run(entries(1, 1), 0L)
        assertTrue(rep.result is InvoiceTrainer.Result.NotReady)
        assertTrue(rep.outcomes.all { it.verdict == Verdict.UNKNOWN })
    }

    @Test fun photosWithoutPaperAreHandledHonestly() {
        val e = ArrayList(entries(20, 20))
        e.add(GalleryTraining.Entry("np-inv", Label.INVOICE, null, 9000))
        e.add(GalleryTraining.Entry("np-none", Label.NONE, null, 9001))
        val rep = GalleryTraining.run(e, 0L)
        assertEquals(40, rep.trainedOn) // لا تدخل التدريب
        val inv = rep.outcomes.first { it.id == "np-inv" }
        val none = rep.outcomes.first { it.id == "np-none" }
        assertTrue(inv.noPaper)
        assertEquals(Verdict.MISSED, inv.verdict)
        assertEquals(Verdict.CORRECT, none.verdict)
        assertNull(inv.score)
        assertEquals(21, rep.invoiceCount)
        assertEquals(21, rep.noneCount)
    }

    @Test fun unclearIsCountedButNotJudgedOrTrained() {
        val e = ArrayList(entries(20, 20))
        repeat(3) { e.add(GalleryTraining.Entry("u$it", Label.UNCLEAR, feat(Random(it.toLong()), true), 7000L + it)) }
        val rep = GalleryTraining.run(e, 0L)
        assertEquals(3, rep.unclearCount)
        assertEquals(40, rep.trainedOn)
        assertEquals(40, rep.outcomes.size)
        assertTrue(rep.outcomes.none { it.id.startsWith("u") })
    }

    @Test fun overlappingClassesAreNotReadyAndDoNotCrash() {
        val r = Random(3)
        val e = (0 until 40).map { i ->
            val pos = i < 20
            GalleryTraining.Entry("o$i", if (pos) Label.INVOICE else Label.NONE, feat(r, true), 100L + i)
        }
        val rep = GalleryTraining.run(e, 0L)
        assertTrue(rep.result is InvoiceTrainer.Result.NotReady)
        assertFalse(rep.thresholdsFromModel)
        assertEquals(40, rep.outcomes.size)
    }

    @Test fun detectedRegionIsCarriedToTheOutcome() {
        val region = com.meterproof.app.privacy.Region(0.1f, 0.2f, 0.6f, 0.7f)
        val e = ArrayList(entries(20, 20))
        e.add(GalleryTraining.Entry("withRegion", Label.INVOICE, feat(Random(9), true), 9500, region))
        e.add(GalleryTraining.Entry("noRegion", Label.NONE, null, 9501))
        val rep = GalleryTraining.run(e, 0L)
        assertEquals(region, rep.outcomes.first { it.id == "withRegion" }.region)
        assertNull(rep.outcomes.first { it.id == "noRegion" }.region)
        // الصور المولَّدة في الاختبار بلا إطار
        assertTrue(rep.outcomes.filter { it.id.startsWith("id") }.all { it.region == null })
    }

    @Test fun reasonsHaveArabicText() {
        for (c in listOf("NEED_MORE_POSITIVES", "NEED_MORE_NEGATIVES", "LOW_PRECISION", "LOW_RECALL")) {
            assertTrue(GalleryTraining.reasonArabic(c) != c)
        }
        assertEquals("X", GalleryTraining.reasonArabic("X"))
    }
}

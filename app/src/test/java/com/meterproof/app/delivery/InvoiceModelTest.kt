package com.meterproof.app.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class InvoiceModelTest {

    private fun rnd(r: Random, lo: Float, hi: Float) = lo + r.nextFloat() * (hi - lo)

    /** عينات اصطناعية قابلة للفصل: الفاتورة مستطيلة ومكتظة بالحواف، وغيرها ليست كذلك. */
    private fun synthetic(nPos: Int, nNeg: Int, seed: Long = 42L): List<Sample> {
        val r = Random(seed)
        val out = ArrayList<Sample>()
        for (i in 0 until nPos) {
            out.add(
                Sample(
                    InvoiceFeatures(
                        rnd(r, 0.05f, 0.35f), rnd(r, 0.80f, 0.98f), rnd(r, 0.2f, 0.6f),
                        rnd(r, 0.3f, 0.7f), rnd(r, 0.0f, 0.1f), rnd(r, 0.12f, 0.35f)
                    ),
                    true, 1000L + i
                )
            )
        }
        for (i in 0 until nNeg) {
            out.add(
                Sample(
                    InvoiceFeatures(
                        rnd(r, 0.05f, 0.35f), rnd(r, 0.50f, 0.80f), rnd(r, 0.2f, 1.2f),
                        rnd(r, 0.1f, 0.6f), rnd(r, 0.05f, 0.25f), rnd(r, 0.0f, 0.04f)
                    ),
                    false, 5000L + i
                )
            )
        }
        return out
    }

    private fun ready(r: InvoiceTrainer.Result): InvoiceModel {
        assertTrue("got $r", r is InvoiceTrainer.Result.Ready)
        return (r as InvoiceTrainer.Result.Ready).model
    }

    @Test
    fun trainsOnSeparableData() {
        val m = ready(InvoiceTrainer.train(synthetic(20, 20), now = 123L))
        assertTrue(m.metrics.precision >= 0.95f)
        assertTrue(m.metrics.recall >= 0.8f)
        assertTrue(m.uncertainThreshold <= m.acceptThreshold)
        // حماية من عيب سابق: المرور التصاعدي كان يعطي عتبة قرابة الصفر فيصير كل شيء «غير مؤكد»
        assertTrue("unc=${m.uncertainThreshold}", m.uncertainThreshold > 0.05f)
        assertTrue(m.metrics.uncertainRecall >= 0.9f)
        assertEquals(20, m.positives)
        assertEquals(20, m.negatives)

        val pos = InvoiceFeatures(0.2f, 0.92f, 0.35f, 0.5f, 0.03f, 0.25f)
        val neg = InvoiceFeatures(0.2f, 0.65f, 0.8f, 0.3f, 0.15f, 0.01f)
        assertTrue("pos=${m.score(pos)} accept=${m.acceptThreshold}", m.score(pos) >= m.acceptThreshold)
        assertTrue("neg=${m.score(neg)} unc=${m.uncertainThreshold}", m.score(neg) < m.uncertainThreshold)
    }

    @Test
    fun trainingIsDeterministic() {
        val a = ready(InvoiceTrainer.train(synthetic(20, 20), now = 9L))
        val b = ready(InvoiceTrainer.train(synthetic(20, 20), now = 9L))
        assertEquals(ModelCodec.encode(a), ModelCodec.encode(b))
        assertEquals(a.id, b.id)
    }

    @Test
    fun tooFewSamplesAreNotReady() {
        val both = InvoiceTrainer.train(synthetic(5, 5), now = 1L)
        assertTrue(both is InvoiceTrainer.Result.NotReady)
        assertEquals(
            listOf("NEED_MORE_POSITIVES", "NEED_MORE_NEGATIVES"),
            (both as InvoiceTrainer.Result.NotReady).reasons
        )

        val one = InvoiceTrainer.train(synthetic(20, 3), now = 1L) as InvoiceTrainer.Result.NotReady
        assertEquals(listOf("NEED_MORE_NEGATIVES"), one.reasons)
    }

    @Test
    fun indistinguishableClassesAreNotReady() {
        // نفس السمات بالضبط للفئتين: لا طريق للتمييز، فيجب ألا يُعلن النموذج جاهزًا
        val base = synthetic(20, 0)
        val twins = base + base.map { Sample(it.features, false, it.t + 10_000L) }
        val r = InvoiceTrainer.train(twins, now = 1L)
        assertTrue("got $r", r is InvoiceTrainer.Result.NotReady)
        assertTrue((r as InvoiceTrainer.Result.NotReady).reasons.contains("LOW_PRECISION"))
    }

    @Test
    fun codecRoundTripKeepsEverything() {
        val m = ready(InvoiceTrainer.train(synthetic(20, 20), now = 77L))
        val back = ModelCodec.decode(ModelCodec.encode(m))
        assertNotNull(back)
        assertEquals(m.id, back!!.id)
        assertEquals(m.trainedAt, back.trainedAt)
        assertEquals(m.metrics, back.metrics)
        val f = InvoiceFeatures(0.2f, 0.9f, 0.3f, 0.5f, 0.02f, 0.2f)
        assertEquals(m.score(f), back.score(f), 1e-6f)
    }

    @Test
    fun corruptModelsAreRejected() {
        assertNull(ModelCodec.decode(""))
        assertNull(ModelCodec.decode("garbage"))
        val good = ModelCodec.encode(ready(InvoiceTrainer.train(synthetic(20, 20), now = 1L)))
        assertNull(ModelCodec.decode(good.replace("IM1", "IM9")))
        assertNull(ModelCodec.decode(good.substringBeforeLast(";")))
        val parts = good.split(';').toMutableList()
        parts[4] = "NaN"
        assertNull(ModelCodec.decode(parts.joinToString(";")))
    }

    @Test
    fun scoreStaysInRangeForExtremeInputs() {
        val m = ready(InvoiceTrainer.train(synthetic(20, 20), now = 1L))
        for (v in listOf(0f, 1e9f, -1e9f)) {
            val s = m.score(InvoiceFeatures(v, v, v, v, v, v))
            assertTrue("s=$s", s in 0f..1f && s.isFinite())
        }
    }

    @Test
    fun sampleCodecRoundTripAndSkipsBadLines() {
        val samples = synthetic(3, 2)
        val text = SampleCodec.encode(samples) + "\nnot a sample\nS;1;1,2,3;5\nS;1;NaN,0,0,0,0,0;7\n"
        val back = SampleCodec.decode(text)
        assertEquals(5, back.size)
        assertEquals(3, back.count { it.positive })
        assertEquals(samples[0].features, back[0].features)
        assertEquals(samples[4].t, back[4].t)
    }

    @Test
    fun featuresRejectWrongSizeAndNonFinite() {
        assertNull(InvoiceFeatures.fromList(listOf(1f, 2f)))
        assertNull(InvoiceFeatures.fromList(listOf(1f, 2f, 3f, 4f, 5f, Float.NaN)))
        assertNotNull(InvoiceFeatures.fromList(listOf(1f, 2f, 3f, 4f, 5f, 6f)))
    }

    @Test
    fun percentHelperClamps() {
        assertEquals(0, SampleCodec.pct(-0.3f))
        assertEquals(100, SampleCodec.pct(1.7f))
        assertEquals(95, SampleCodec.pct(0.95f))
    }
}

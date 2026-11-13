package com.meterproof.app.delivery

import com.meterproof.app.privacy.Region
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoStageTest {
    private val W = 640
    private val H = 480

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private class Sc(val px: IntArray, val invoice: Region?)

    private fun wall(rnd: Random): IntArray {
        val base = 120 + rnd.nextInt(90)
        return IntArray(W * H) { val n = rnd.nextInt(9) - 4; rgb(base + n, base + n, base + n) }
    }

    private fun rect(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, f: (Int, Int) -> Int) {
        for (y in y0 until y1) for (x in x0 until x1) px[y * W + x] = f(x, y)
    }

    private fun scene(rnd: Random, invoice: Boolean, i: Int): Sc {
        val px = wall(rnd)
        rect(px, 220, 60, 390, H) { _, _ -> rgb(70, 50, 35) }
        if (invoice) {
            val w = 60 + rnd.nextInt(40)
            val h = (w * 1.3).toInt()
            val x0 = 40 + rnd.nextInt(W - 80 - w)
            val y0 = 150 + rnd.nextInt(H - 160 - h)
            val (r, g, b) = if (i % 2 == 0) Triple(235, 235, 232) else Triple(238, 220, 150)
            rect(px, x0, y0, x0 + w, y0 + h) { _, y -> if ((y / 3) % 2 == 0) rgb(r, g, b) else rgb(r - 90, g - 90, b - 90) }
            return Sc(px, Region(x0.toFloat() / W, y0.toFloat() / H, (x0 + w).toFloat() / W, (y0 + h).toFloat() / H))
        }
        when (i % 3) {
            1 -> rect(px, 40, 200, 130, 330) { _, _ -> rgb(240, 240, 238) }
            2 -> rect(px, 450, 120, 550, 220) { x, y -> if (((x + y) / 4) % 2 == 0) rgb(60, 80, 120) else rgb(90, 110, 150) }
        }
        return Sc(px, null)
    }

    /** وصف دلالي مصطنع من بكسلات القص: متوسط السطوع وكثافة خطوط النص والتشبع. */
    private val provider = SemanticProvider { px, w, h, r ->
        val x0 = (r.left * w).toInt().coerceIn(0, w - 1)
        val x1 = (r.right * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (r.top * h).toInt().coerceIn(0, h - 1)
        val y1 = (r.bottom * h).toInt().coerceIn(y0 + 1, h)
        var lum = 0.0; var stripes = 0.0; var n = 0; var m = 0
        for (y in y0 until y1 - 1) for (x in x0 until x1) {
            val a = (px[y * w + x] shr 8) and 0xFF
            val b = (px[(y + 1) * w + x] shr 8) and 0xFF
            lum += a; n++
            if (Math.abs(a - b) > 40) stripes++
            m++
        }
        FloatArray(Semantic.DIM).also {
            it[0] = (lum / n / 255.0).toFloat()
            it[1] = (stripes / m).toFloat() * 4f
        }
    }

    private fun trainImages(rnd: Random, nInv: Int, nNone: Int): Pair<List<TrainImage>, List<Sc>> {
        val imgs = ArrayList<TrainImage>()
        val scs = ArrayList<Sc>()
        for (i in 0 until nInv) {
            val s = scene(rnd, true, i); scs.add(s)
            imgs.add(TrainImage.build("i$i", WindowScene.from(s.px, W, H)!!, listOf(s.invoice!!), emptyList(), false))
        }
        for (i in 0 until nNone) {
            val s = scene(rnd, false, i); scs.add(s)
            imgs.add(TrainImage.build("n$i", WindowScene.from(s.px, W, H)!!, emptyList(), emptyList(), true))
        }
        return imgs to scs
    }

    private fun vimages(images: List<TrainImage>, scs: List<Sc>, plans: List<Plan>): List<VImage> {
        val fold = TwoStageTrainer.folds(images)
        return images.indices.mapNotNull { i ->
            if (fold[i] < 0) null
            else {
                val s = scs[i]
                fun v(c: Cand) = VCand(c.stage1, c.hit, provider.vector(s.px, W, H, c.region)!!)
                VImage(images[i].id, images[i].hasInvoice, fold[i], plans[i].cands.map(::v), plans[i].extra.map(::v))
            }
        }
    }

    @Test fun planIsCrossFittedAndLabelsHits() {
        val (imgs, _) = trainImages(Random(7), 16, 16)
        val plans = TwoStageTrainer.plan(imgs, 1L)!!
        assertEquals(imgs.size, plans.size)
        // لكل صورة فاتورة إيجابيات إضافية (المربع الدقيق)، ولكل صورة بلا فاتورة لا توجد إصابات
        for ((i, im) in imgs.withIndex()) {
            assertTrue(plans[i].cands.size in 1..TwoStageTrainer.CANDIDATES)
            if (im.fullyNegative) assertTrue(plans[i].cands.none { it.hit })
            else assertEquals(1, plans[i].extra.size)
        }
    }

    @Test fun verifierTrainsDetectsAndCodecRoundTrips() {
        val rnd = Random(8)
        val (imgs, scs) = trainImages(rnd, 20, 20)
        val plans = TwoStageTrainer.plan(imgs, 1L)!!
        val stage1 = WindowTrainer.fitAll(imgs, 1L, WindowTrainer.Config())!!.first
        val r = TwoStageTrainer.train(vimages(imgs, scs, plans), 1L)
        assertTrue(r.toString().take(200), r is TwoStageTrainer.Result.Ready)
        val v = (r as TwoStageTrainer.Result.Ready).model
        assertTrue(r.report.falseAccepts <= 1)

        val back = VerifierCodec.decode(VerifierCodec.encode(v))
        assertNotNull(back)
        assertEquals(v.id, back!!.id)
        assertNull(VerifierCodec.decode("junk"))

        // حيًا: تظهر الفاتورة المعلَّمة، وتُرفض المشاهد بلا فاتورة
        val m1 = WindowModel(stage1.mean, stage1.std, stage1.weights, stage1.bias, 0.5f, 0.3f, 1L, 20, 20,
            WindowMetrics(1f, 1f, 1f))
        val det = WindowDetector(m1, verifier = v, semantic = provider)
        val live = Random(77)
        var found = 0
        var fa = 0
        for (i in 0 until 10) {
            val s = scene(live, true, i)
            val res = det.detect(s.px, W, H)
            if (res.state == DeliveryState.DETECTED && TrainImage.iou(res.region!!, s.invoice!!) > 0.3f) found++
        }
        for (i in 0 until 10) {
            if (det.detect(scene(live, false, i).px, W, H).state == DeliveryState.DETECTED) fa++
        }
        assertTrue("found $found", found >= 7)
        assertEquals(0, fa)
    }

    @Test fun neverAcceptsWhenDescriptionFails() {
        val rnd = Random(9)
        val (imgs, scs) = trainImages(rnd, 18, 18)
        val plans = TwoStageTrainer.plan(imgs, 1L)!!
        val stage1 = WindowTrainer.fitAll(imgs, 1L, WindowTrainer.Config())!!.first
        val v = (TwoStageTrainer.train(vimages(imgs, scs, plans), 1L) as? TwoStageTrainer.Result.Ready)?.model ?: return
        val m1 = WindowModel(stage1.mean, stage1.std, stage1.weights, stage1.bias, 0.5f, 0.3f, 1L, 18, 18,
            WindowMetrics(1f, 1f, 1f))
        val det = WindowDetector(m1, verifier = v, semantic = SemanticProvider { _, _, _, _ -> null })
        val s = scene(Random(5), true, 0)
        assertTrue(det.detect(s.px, W, H).state != DeliveryState.DETECTED)
    }

    @Test fun notReadyWithFewImages() {
        val (imgs, scs) = trainImages(Random(10), 4, 4)
        val fold = TwoStageTrainer.folds(imgs)
        val vi = imgs.indices.map { VImage(imgs[it].id, imgs[it].hasInvoice, fold[it], emptyList(), emptyList()) }
        val r = TwoStageTrainer.train(vi, 1L)
        assertTrue(r is TwoStageTrainer.Result.NotReady)
        assertEquals(scs.size, imgs.size)
    }
}

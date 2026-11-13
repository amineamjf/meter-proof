package com.meterproof.app.delivery

import com.meterproof.app.privacy.Region
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowDetectorTest {
    private val W = 640
    private val H = 480

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** مشهد: جدار بلون عشوائي مع ضجيج، باب داكن، وعناصر إضافية. */
    private class Scene(val px: IntArray, val invoice: Region?)

    private fun wall(rnd: Random): IntArray {
        val base = 120 + rnd.nextInt(90)
        val tint = rnd.nextInt(30)
        val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            val n = rnd.nextInt(9) - 4
            px[y * W + x] = rgb(base + n + tint, base + n, base + n - tint / 2)
        }
        return px
    }

    private fun rect(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, f: (Int, Int) -> Int) {
        for (y in y0 until y1) for (x in x0 until x1) px[y * W + x] = f(x, y)
    }

    private fun door(px: IntArray, rnd: Random) {
        val x0 = 200 + rnd.nextInt(80)
        rect(px, x0, 60, x0 + 170, H, { _, _ -> rgb(70, 50, 35) })
    }

    /** فاتورة: ورقة بخطوط نص أفقية، لونها يتغير (بيضاء، رمادية، صفراء شاحبة). */
    private fun invoiceScene(rnd: Random, tintKind: Int): Scene {
        val px = wall(rnd)
        door(px, rnd)
        val w = 60 + rnd.nextInt(50)
        val h = (w * (1.2 + rnd.nextDouble() * 0.4)).toInt()
        val x0 = 40 + rnd.nextInt(W - 80 - w)
        val y0 = 150 + rnd.nextInt(H - 160 - h)
        val (pr, pg, pb) = when (tintKind % 3) {
            0 -> Triple(235, 235, 232)
            1 -> Triple(175, 175, 172)
            else -> Triple(238, 220, 150) // صفراء: سبب فشل الكاشف القديم
        }
        rect(px, x0, y0, x0 + w, y0 + h) { _, y -> if ((y / 3) % 2 == 0) rgb(pr, pg, pb) else rgb(pr - 90, pg - 90, pb - 90) }
        return Scene(px, Region(x0.toFloat() / W, y0.toFloat() / H, (x0 + w).toFloat() / W, (y0 + h).toFloat() / H))
    }

    /** مشاهد بلا فاتورة: جدار وباب، وأحيانًا لوحة فاتحة ملساء أو نافذة أو ورقة بلا نص (ليست فاتورة). */
    private fun noneScene(rnd: Random, kind: Int): Scene {
        val px = wall(rnd)
        door(px, rnd)
        when (kind % 4) {
            1 -> { val x0 = 40 + rnd.nextInt(100); rect(px, x0, 200, x0 + 90, 330) { _, _ -> rgb(240, 240, 238) } }
            2 -> { val x0 = 450 + rnd.nextInt(60); rect(px, x0, 120, x0 + 100, 220) { x, y -> if (((x + y) / 4) % 2 == 0) rgb(60, 80, 120) else rgb(90, 110, 150) } }
            3 -> { rect(px, 30, 330, 160, 440) { _, y -> if ((y / 14) % 2 == 0) rgb(40, 40, 45) else rgb(200, 200, 205) } }
        }
        return Scene(px, null)
    }

    private fun train(rnd: Random, nInv: Int, nNone: Int): WindowTrainer.Result {
        val imgs = ArrayList<TrainImage>()
        for (i in 0 until nInv) {
            val s = invoiceScene(rnd, i)
            val sc = WindowScene.from(s.px, W, H)!!
            imgs.add(TrainImage.build("inv$i", sc, listOf(s.invoice!!), emptyList(), false))
        }
        for (i in 0 until nNone) {
            val s = noneScene(rnd, i)
            val sc = WindowScene.from(s.px, W, H)!!
            imgs.add(TrainImage.build("none$i", sc, emptyList(), emptyList(), true))
        }
        return WindowTrainer.train(imgs, 1L)
    }

    @Test fun describeIsDeterministicAndSized() {
        val rnd = Random(1)
        val s = invoiceScene(rnd, 0)
        val sc = WindowScene.from(s.px, W, H)!!
        val a = sc.describe(s.invoice!!)!!
        val b = sc.describe(s.invoice!!)!!
        assertEquals(WindowFeatures.SIZE, a.size)
        assertTrue(a.contentEquals(b))
        assertTrue(a.all { it.isFinite() })
        assertNull(sc.describe(Region(0.1f, 0.1f, 0.102f, 0.102f)))
    }

    @Test fun windowsCoverManySizes() {
        val sc = WindowScene.from(invoiceScene(Random(2), 0).px, W, H)!!
        val set = sc.windows()
        assertTrue(set.n > 500)
        assertEquals(set.n * WindowFeatures.SIZE, set.vecs.size)
    }

    @Test fun notReadyWithFewImages() {
        val r = train(Random(3), 3, 3)
        assertTrue(r is WindowTrainer.Result.NotReady)
        assertTrue((r as WindowTrainer.Result.NotReady).reasons.contains("NEED_MORE_INVOICE_IMAGES"))
    }

    @Test fun learnsInvoicesIncludingPaleYellowAndRejectsLookalikes() {
        val r = train(Random(4), 18, 20)
        assertTrue(r.toString().take(300), r is WindowTrainer.Result.Ready)
        val model = (r as WindowTrainer.Result.Ready).model
        val rep = r.report
        assertEquals(0, rep.falseAccepts)
        assertTrue("hit ${rep.hit}/${rep.invoiceImages}", rep.hit >= rep.invoiceImages * 8 / 10)

        // صور جديدة لم يرها التدريب
        val det = WindowDetector(model)
        val rnd = Random(99)
        var found = 0
        var located = 0
        for (i in 0 until 12) {
            val s = invoiceScene(rnd, i)
            val res = det.detect(s.px, W, H)
            if (res.state == DeliveryState.DETECTED) {
                found++
                if (TrainImage.iou(res.region!!, s.invoice!!) > 0.3f) located++
            }
        }
        var falseAcc = 0
        for (i in 0 until 12) {
            if (det.detect(noneScene(rnd, i).px, W, H).state == DeliveryState.DETECTED) falseAcc++
        }
        assertTrue("found $found located $located", found >= 9 && located >= 9)
        assertEquals(0, falseAcc)
    }

    @Test fun modelCodecRoundTrips() {
        val r = train(Random(5), 12, 12) as? WindowTrainer.Result.Ready ?: return
        val m = r.model
        val back = WindowModelCodec.decode(WindowModelCodec.encode(m))
        assertNotNull(back)
        assertEquals(m.id, back!!.id)
        assertNull(WindowModelCodec.decode("garbage"))
    }
}

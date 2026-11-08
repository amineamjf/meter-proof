package com.meterproof.app.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** اختبارات على صور اصطناعية مرسومة بالكود (مستطيلات ملوّنة)، لا صور حقيقية. */
class FrameAnalyzerTest {

    private val w = 60
    private val h = 80

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val wall = rgb(110, 110, 110)
    private val skinColor = rgb(224, 172, 105)
    private val paperColor = rgb(240, 240, 235)

    private fun image(bg: Int = wall) = IntArray(w * h) { bg }

    private fun IntArray.rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int): IntArray {
        for (y in y0 until y1) for (x in x0 until x1) this[y * w + x] = c
        return this
    }

    @Test
    fun handHoldingPaperGivesSkinAndHeld() {
        val a = FrameAnalyzer(w, h)
        val img = image().rect(20, 40, 40, 60, skinColor).rect(38, 38, 52, 52, paperColor)
        val f = a.analyze(0, img)
        assertTrue("skin ${f.skin}", f.skin > 0.05f)
        assertTrue("held ${f.held}", f.held > 0.01f)
        assertEquals(0f, f.obj, 0f)
    }

    @Test
    fun handWithoutPaperHasNoHeld() {
        val a = FrameAnalyzer(w, h)
        val f = a.analyze(0, image().rect(20, 40, 40, 60, skinColor))
        assertTrue(f.skin > 0.05f)
        assertEquals(0f, f.held, 0f)
    }

    @Test
    fun paperPlacedAfterBaselineIsDetectedAsObjectAtItsPosition() {
        val a = FrameAnalyzer(w, h)
        a.analyze(0, image())
        a.analyze(143, image())
        val f = a.analyze(286, image().rect(10, 50, 24, 62, paperColor))
        assertTrue("obj ${f.obj}", f.obj > 0.01f)
        assertEquals(17f / w, f.objX, 0.02f)
        assertEquals(56f / h, f.objY, 0.02f)
        assertEquals(0f, f.skin, 0f)
    }

    @Test
    fun whiteWallAloneIsNeverAnObject() {
        val a = FrameAnalyzer(w, h)
        val bright = rgb(235, 235, 230)
        for (i in 0 until 10) {
            val f = a.analyze(i * 143L, image(bright))
            assertEquals("frame $i", 0f, f.obj, 0f)
        }
    }

    @Test
    fun uniformBrightnessChangeDoesNotCreateAnObject() {
        val a = FrameAnalyzer(w, h)
        val base = image(rgb(180, 180, 180))
        val brighter = image(rgb(215, 215, 215))
        a.analyze(0, base)
        val f = a.analyze(143, brighter)
        assertEquals(0f, f.obj, 0f)
    }

    @Test
    fun smallCameraShiftDoesNotCreateAnObject() {
        val a = FrameAnalyzer(w, h)
        val first = image().rect(30, 20, 40, 60, paperColor)
        val shifted = image().rect(32, 20, 42, 60, paperColor)
        a.analyze(0, first)
        val f = a.analyze(143, shifted)
        assertTrue("obj ${f.obj}", f.obj < 0.002f)
    }

    @Test
    fun largeMotionIsReportedAsMotion() {
        val a = FrameAnalyzer(w, h)
        a.analyze(0, image())
        val f = a.analyze(143, image(rgb(30, 30, 30)))
        assertTrue("motion ${f.motion}", f.motion > 0.2f)
    }

    @Test
    fun handHoldingPaperDoesNotCountAsPlacedObject() {
        val a = FrameAnalyzer(w, h)
        a.analyze(0, image())
        val f = a.analyze(143, image().rect(20, 40, 40, 60, skinColor).rect(38, 38, 52, 52, paperColor))
        assertEquals("paper next to the hand is 'held', not 'object'", 0f, f.obj, 0f)
    }

    @Test
    fun placedObjectSurvivesBaselineUpdatesInsteadOfMeltingAway() {
        val a = FrameAnalyzer(w, h)
        a.analyze(0, image())
        var last = 0f
        for (i in 1..30) last = a.analyze(i * 143L, image().rect(10, 50, 24, 62, paperColor)).obj
        assertTrue("object melted into baseline: $last", last > 0.01f)
    }

    @Test
    fun resetForgetsTheBaseline() {
        val a = FrameAnalyzer(w, h)
        a.analyze(0, image())
        a.reset()
        // بعد التصفير أول إطار يصبح المرجع، فلا جسم فيه حتى لو احتوى ورقة
        val f = a.analyze(143, image().rect(10, 50, 24, 62, paperColor))
        assertEquals(0f, f.obj, 0f)
    }

    // ---- نموذج الألوان

    private fun tone(base: Int, rnd: Random): Int {
        fun ch(v: Int) = (v + rnd.nextInt(7) - 3).coerceIn(0, 255)
        val r = ch((base shr 16) and 255)
        val g = ch((base shr 8) and 255)
        val b = ch(base and 255)
        return rgb(r, g, b)
    }

    @Test
    fun fittedSkinModelAcceptsTheWorkersTonesAndRejectsTheWall() {
        val rnd = Random(1)
        for (t in listOf(rgb(240, 200, 170), rgb(200, 150, 110), rgb(120, 80, 55))) {
            val samples = IntArray(120) { tone(t, rnd) }
            val m = ColorModel.fitSkin(ColorModel(), samples)
            assertNotNull(m)
            val y = ColorModel.yOf(t)
            val cb = ColorModel.cbOf(t)
            val cr = ColorModel.crOf(t)
            assertTrue("tone ${Integer.toHexString(t)} accepted", m!!.isSkin(y, cb, cr))
            assertFalse(m.isSkin(ColorModel.yOf(wall), ColorModel.cbOf(wall), ColorModel.crOf(wall)))
            assertEquals(1, m.version)
        }
    }

    @Test
    fun fittedPaperModelAcceptsWarmPaperThatDefaultsReject() {
        val warm = rgb(245, 235, 205)
        val y = ColorModel.yOf(warm)
        val cb = ColorModel.cbOf(warm)
        val cr = ColorModel.crOf(warm)
        assertFalse("default model rejects warm paper", ColorModel().isPaper(y, cb, cr))
        val rnd = Random(2)
        val m = ColorModel.fitPaper(ColorModel(), IntArray(120) { tone(warm, rnd) })
        assertNotNull(m)
        assertTrue(m!!.isPaper(y, cb, cr))
    }

    @Test
    fun fitNeedsEnoughPixels() {
        assertNull(ColorModel.fitSkin(ColorModel(), IntArray(10) { skinColor }))
        assertNull(ColorModel.fitPaper(ColorModel(), IntArray(10) { paperColor }))
    }

    @Test
    fun colorModelSurvivesSerialization() {
        val m = ColorModel(version = 4, cbMin = 70, cbMax = 130, crMin = 140, crMax = 170,
            skinYMin = 33, paperYMin = 160, paperChroma = 20)
        assertEquals(m, ColorModel.parse(m.serialize()))
    }

    @Test
    fun skinTakesPriorityWhenRangesOverlap() {
        val overlapping = ColorModel(cbMin = 100, cbMax = 140, crMin = 120, crMax = 150, paperChroma = 30)
        val a = FrameAnalyzer(w, h, overlapping)
        val f = a.analyze(0, image(rgb(200, 190, 185)))
        // البكسل يطابق الجلد والورقة معًا: يُعدّ جلدًا فقط، فلا يظهر جسم ولا ورقة في اليد
        assertTrue(f.skin > 0.9f)
        assertEquals(0f, f.held, 0f)
    }
}

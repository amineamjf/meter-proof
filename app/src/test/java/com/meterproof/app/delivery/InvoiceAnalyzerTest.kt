package com.meterproof.app.delivery

import com.meterproof.app.delivery.TestFrames.rect
import com.meterproof.app.delivery.TestFrames.rgb
import com.meterproof.app.delivery.TestFrames.solid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InvoiceAnalyzerTest {

    private val w = 160
    private val h = 120

    @Test
    fun paperOnDarkBackgroundIsFound() {
        val c = InvoiceAnalyzer.analyze(TestFrames.paperScene(textured = false), w, h)
        assertNotNull(c)
        val f = c!!.features
        assertTrue("areaFrac=${f.areaFrac}", f.areaFrac in 0.20f..0.32f)
        assertTrue("fill=${f.fill}", f.fill > 0.85f)
        assertTrue("logAspect=${f.logAspect}", f.logAspect in 0.15f..0.5f)
        assertTrue("saturation=${f.saturation}", f.saturation < 0.05f)
        assertTrue("contrast=${f.contrast}", f.contrast > 0.4f)
        assertTrue("region=${c.region}", c.region.left < 0.26f && c.region.right > 0.74f)
        assertTrue(c.region.area in 0.2f..0.5f)
    }

    @Test
    fun printedTextRaisesTextiness() {
        val plain = InvoiceAnalyzer.analyze(TestFrames.paperScene(textured = false), w, h)!!
        val text = InvoiceAnalyzer.analyze(TestFrames.paperScene(textured = true), w, h)!!
        assertTrue("plain=${plain.features.textiness}", plain.features.textiness < 0.01f)
        assertTrue("text=${text.features.textiness}", text.features.textiness > 0.1f)
    }

    @Test
    fun blankBrightWallIsRejected() {
        assertNull(InvoiceAnalyzer.analyze(solid(w, h, rgb(235, 235, 235)), w, h))
    }

    @Test
    fun darkSceneHasNoCandidate() {
        assertNull(InvoiceAnalyzer.analyze(solid(w, h, rgb(50, 50, 50)), w, h))
    }

    @Test
    fun saturatedBrightColourIsNotPaper() {
        val px = solid(w, h, rgb(40, 40, 40))
        rect(px, w, 40, 30, 120, 90, rgb(250, 250, 60))
        assertNull(InvoiceAnalyzer.analyze(px, w, h))
    }

    @Test
    fun tinyPaperIsRejected() {
        val px = solid(w, h, rgb(40, 40, 40))
        rect(px, w, 70, 55, 76, 59, rgb(235, 235, 230))
        assertNull(InvoiceAnalyzer.analyze(px, w, h))
    }

    @Test
    fun largestOfTwoPapersWins() {
        val px = solid(w, h, rgb(40, 40, 40))
        rect(px, w, 10, 30, 80, 80, rgb(235, 235, 230))
        rect(px, w, 120, 10, 140, 25, rgb(235, 235, 230))
        val c = InvoiceAnalyzer.analyze(px, w, h)
        assertNotNull(c)
        assertTrue("areaFrac=${c!!.features.areaFrac}", c.features.areaFrac in 0.14f..0.24f)
        assertTrue("region=${c.region}", c.region.right < 0.6f)
    }

    @Test
    fun orientationDoesNotChangeAspect() {
        val land = solid(w, h, rgb(40, 40, 40))
        rect(land, w, 40, 30, 120, 90, rgb(235, 235, 230))
        val port = solid(w, h, rgb(40, 40, 40))
        rect(port, w, 50, 20, 110, 100, rgb(235, 235, 230))
        val a = InvoiceAnalyzer.analyze(land, w, h)!!.features.logAspect
        val b = InvoiceAnalyzer.analyze(port, w, h)!!.features.logAspect
        assertTrue("land=$a port=$b", abs(a - b) < 0.1f)
    }

    @Test
    fun analysisIsDeterministic() {
        val px = TestFrames.paperScene(textured = true)
        val a = InvoiceAnalyzer.analyze(px, w, h)
        val b = InvoiceAnalyzer.analyze(px.copyOf(), w, h)
        assertEquals(a, b)
    }

    @Test
    fun degenerateInputsGiveNull() {
        assertNull(InvoiceAnalyzer.analyze(IntArray(4), 2, 2))
        assertNull(InvoiceAnalyzer.analyze(IntArray(10), 160, 120))
    }

    @Test
    fun otsuSeparatesTwoLevels() {
        val v = IntArray(100) { if (it < 50) 40 else 200 }
        val t = InvoiceAnalyzer.otsu(v)
        assertTrue("t=$t", t in 40..199)
        assertEquals(0, InvoiceAnalyzer.otsu(IntArray(10) { 128 }))
    }

    @Test
    fun analyzerDoesNotModifyItsInput() {
        val px = TestFrames.paperScene(textured = true)
        val copy = px.copyOf()
        InvoiceAnalyzer.analyze(px, w, h)
        assertTrue(px.contentEquals(copy))
    }
}

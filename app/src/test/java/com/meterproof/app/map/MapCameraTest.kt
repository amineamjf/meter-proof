package com.meterproof.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class MapCameraTest {
    private val w = 400f
    private val h = 600f
    private val pad = 28f

    private val pts = listOf(
        33.5731 to -7.5898, 33.5760 to -7.5850, 33.5702 to -7.5921,
        33.5745 to -7.5880, 33.5719 to -7.5862
    )

    /** نسخة حرفية من الإسقاط القديم في MapRenderer للمقارنة. */
    private fun legacy(points: List<Pair<Double, Double>>): Pair<(Double) -> Float, (Double) -> Float> {
        val minLat = points.minOf { it.first }; val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }; val maxLon = points.maxOf { it.second }
        val lonFactor = cos(Math.toRadians((minLat + maxLat) / 2))
        val minSpan = 0.0005
        val spanX = max((maxLon - minLon) * lonFactor, minSpan)
        val spanY = max(maxLat - minLat, minSpan)
        val availW = max(w - 2 * pad, 1f); val availH = max(h - 2 * pad, 1f)
        val scale = min(availW / spanX, availH / spanY).toFloat()
        val offX = (w - (spanX * scale).toFloat()) / 2
        val offY = (h - (spanY * scale).toFloat()) / 2
        return Pair(
            { lon -> offX + (((lon - minLon) * lonFactor) * scale).toFloat() },
            { lat -> offY + ((maxLat - lat) * scale).toFloat() }
        )
    }

    @Test fun emptyInputGivesNoCamera() {
        assertNull(MapCamera.fit(emptyList(), w, h, pad))
    }

    /** الدوائر والمسار يجب ألا يتحركا: نفس المواضع على الشاشة كالمُرسِم القديم (فرق أقل من 0.6 بكسل). */
    @Test fun positionsMatchTheLegacyRendererWithinSubPixel() {
        val cam = MapCamera.fit(pts, w, h, pad)!!
        val (lx, ly) = legacy(pts)
        for ((lat, lon) in pts) {
            assertEquals("x for $lat,$lon", lx(lon), cam.x(lon), 0.6f)
            assertEquals("y for $lat,$lon", ly(lat), cam.y(lat), 0.6f)
        }
    }

    @Test fun allPointsStayInsidePaddedArea() {
        val cam = MapCamera.fit(pts, w, h, pad)!!
        for ((lat, lon) in pts) {
            assertTrue(cam.x(lon) >= pad - 0.5f && cam.x(lon) <= w - pad + 0.5f)
            assertTrue(cam.y(lat) >= pad - 0.5f && cam.y(lat) <= h - pad + 0.5f)
        }
    }

    @Test fun singlePointIsCenteredAndZoomIsBounded() {
        val cam = MapCamera.fit(listOf(33.5731 to -7.5898), w, h, pad)!!
        assertEquals(w / 2, cam.x(-7.5898), 0.01f)
        assertEquals(h / 2, cam.y(33.5731), 0.01f)
        // مدى أدنى ~55 م على أصغر بعد متاح: لا تكبير مجنون
        assertTrue("zoom ${cam.zoom}", cam.zoom < 20.5)
    }

    @Test fun scaleBarMetersPerPixelMatchesGroundDistance() {
        val cam = MapCamera.fit(pts, w, h, pad)!!
        val lat = 33.5731
        val dLon = 0.004
        val metersX = dLon * 111320.0 * cos(Math.toRadians(lat))
        val px = abs(cam.x(-7.5898 + dLon) - cam.x(-7.5898))
        assertEquals(metersX, px * cam.metersPerPixel, metersX * 0.01)
    }

    @Test fun tileZoomIsClampedToWhatIsDownloaded() {
        val cam = MapCamera.fit(pts, w, h, pad)!!
        assertTrue("zoom ${cam.zoom}", cam.zoom in 15.0..19.0)
        assertEquals(12, cam.tileZoom(8, 12))     // لم يُنزَّل أعلى من 12: نكبّر الموجود
        assertEquals(20, cam.tileZoom(20, 22))    // لم يُنزَّل أدنى من 20: أقرب متاح
        assertTrue(abs(cam.tileZoom(0, 22) - cam.zoom) <= 0.5)
    }

    @Test fun slotsCoverTheWholeViewportWithoutGaps() {
        val cam = MapCamera.fit(pts, w, h, pad)!!
        val z = cam.tileZoom(0, 19)
        val slots = cam.slots(z)
        assertTrue(slots.isNotEmpty())
        assertTrue(slots.minOf { it.left } <= 0f); assertTrue(slots.maxOf { it.left + it.size } >= w)
        assertTrue(slots.minOf { it.top } <= 0f); assertTrue(slots.maxOf { it.top + it.size } >= h)
        // البلاطة التي تحوي نقطة معروفة تقع مركزها على شاشتها
        val x = cam.x(-7.5898); val y = cam.y(33.5731)
        val hit = slots.first { x >= it.left && x < it.left + it.size && y >= it.top && y < it.top + it.size }
        assertEquals(TileMath.tileX(-7.5898, z), hit.x)
        assertEquals(TileMath.tileY(33.5731, z), hit.y)
    }

    @Test fun slotsRespectTheLimit() {
        val cam = MapCamera.fit(pts, w, h, pad)!!
        assertTrue(cam.slots(cam.tileZoom(0, 19) + 3, limit = 10).size <= 10)
    }
}

package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    private val tile = 256.0

    @Test
    fun longitudeMapsLinearlyAcrossTheWorld() {
        assertEquals(0.0, TileMath.lonToX(-180.0, 0.0, tile), 1e-9)
        assertEquals(128.0, TileMath.lonToX(0.0, 0.0, tile), 1e-9)
        assertEquals(256.0, TileMath.lonToX(180.0, 0.0, tile), 1e-9)
        // كل مستوى تكبير يضاعف حجم العالم
        assertEquals(1024.0, TileMath.lonToX(180.0, 2.0, tile), 1e-9)
    }

    @Test
    fun latitudeEquatorIsMiddleAndPolesAreClamped() {
        assertEquals(128.0, TileMath.latToY(0.0, 0.0, tile), 1e-9)
        assertEquals(0.0, TileMath.latToY(TileMath.MAX_LAT, 0.0, tile), 1e-6)
        assertEquals(256.0, TileMath.latToY(-TileMath.MAX_LAT, 0.0, tile), 1e-6)
        // ما بعد الحد يُقصّ ولا ينتج قيمة لا نهائية
        assertEquals(0.0, TileMath.latToY(90.0, 0.0, tile), 1e-6)
    }

    @Test
    fun roundTripKeepsCoordinatesAtDifferentZooms() {
        // سيدي قاسم تقريبًا
        val lat = 34.2254
        val lon = -5.7081
        for (zoom in listOf(3.0, 10.5, 16.0, 19.25)) {
            val x = TileMath.lonToX(lon, zoom, tile)
            val y = TileMath.latToY(lat, zoom, tile)
            assertEquals(lon, TileMath.xToLon(x, zoom, tile), 1e-8)
            assertEquals(lat, TileMath.yToLat(y, zoom, tile), 1e-8)
        }
    }

    @Test
    fun metersPerPixelShrinksWithZoomAndLatitude() {
        // عند خط الاستواء والتكبير 0 وبلاطة 256 نقطة: المحيط / 256
        assertEquals(156543.03392, TileMath.metersPerPixel(0.0, 0.0, tile), 0.01)
        assertEquals(78271.51696, TileMath.metersPerPixel(60.0, 0.0, tile), 0.01)
        assertEquals(
            TileMath.metersPerPixel(34.0, 10.0, tile) / 2,
            TileMath.metersPerPixel(34.0, 11.0, tile), 1e-6
        )
    }

    @Test
    fun fitZoomWholeWorldIsZero() {
        val z = TileMath.fitZoom(-85.0, 85.0, -180.0, 180.0, 256.0, 256.0, tile, 0.0, 20.0)
        assertEquals(0.0, z, 1e-9)
    }

    @Test
    fun fitZoomMakesBoundsFitButNotMuchMore() {
        val minLat = 34.20; val maxLat = 34.23
        val minLon = -5.73; val maxLon = -5.69
        val w = 1000.0
        val h = 700.0
        val pad = 40.0
        val z = TileMath.fitZoom(minLat, maxLat, minLon, maxLon, w, h, tile, pad, 20.0)

        fun spanAt(zoom: Double): Pair<Double, Double> = Pair(
            TileMath.lonToX(maxLon, zoom, tile) - TileMath.lonToX(minLon, zoom, tile),
            TileMath.latToY(minLat, zoom, tile) - TileMath.latToY(maxLat, zoom, tile)
        )

        val (dx, dy) = spanAt(z)
        assertTrue("dx=$dx", dx <= w - 2 * pad + 1e-6)
        assertTrue("dy=$dy", dy <= h - 2 * pad + 1e-6)
        // وأكبر بقليل لا يتسع (أحد المحورين يلامس الحد)
        val (dx2, dy2) = spanAt(z + 0.05)
        assertTrue(dx2 > w - 2 * pad || dy2 > h - 2 * pad)
    }

    @Test
    fun fitZoomIsCappedForASinglePoint() {
        val z = TileMath.fitZoom(34.2, 34.2, -5.7, -5.7, 800.0, 600.0, tile, 20.0, 18.0)
        assertEquals(18.0, z, 1e-9)
    }

    @Test
    fun tileRangeCoversTheScreenAndClamps() {
        // التكبير 2: أربع بلاطات على كل محور، حجم المعروضة 256
        assertEquals(IntRange(1, 2), TileMath.tileRange(512.0, 300.0, 256.0, 2))
        // عند حافة العالم يُقصّ المدى بدل أن يخرج عن الحدود
        assertEquals(IntRange(0, 0), TileMath.tileRange(0.0, 300.0, 256.0, 2))
        assertEquals(IntRange(3, 3), TileMath.tileRange(1024.0, 300.0, 256.0, 2))
    }

    @Test
    fun tileRangeIsEmptyWhenNothingIsVisible() {
        assertTrue(TileMath.tileRange(-1000.0, 300.0, 256.0, 2).isEmpty())
        assertTrue(TileMath.tileRange(5000.0, 300.0, 256.0, 2).isEmpty())
    }
}

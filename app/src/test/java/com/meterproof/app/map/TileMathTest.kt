package com.meterproof.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** القيم المرجعية حُسبت بصيغة مستقلة (asinh) خارج الكود. */
class TileMathTest {
    private val casaLat = 33.5731
    private val casaLon = -7.5898

    @Test fun tileIndicesMatchIndependentReference() {
        assertEquals(0, TileMath.tileX(casaLon, 0)); assertEquals(0, TileMath.tileY(casaLat, 0))
        assertEquals(490, TileMath.tileX(casaLon, 10)); assertEquals(410, TileMath.tileY(casaLat, 10))
        assertEquals(62772, TileMath.tileX(casaLon, 17)); assertEquals(52546, TileMath.tileY(casaLat, 17))
        assertEquals(251090, TileMath.tileX(casaLon, 19)); assertEquals(210184, TileMath.tileY(casaLat, 19))
        assertEquals(1970, TileMath.tileX(-6.8416, 12)); assertEquals(1635, TileMath.tileY(34.0209, 12))
    }

    @Test fun worldCoordinatesMatchReference() {
        assertEquals(0.4789172222222222, TileMath.lonToWx(casaLon), 1e-12)
        assertEquals(0.4008952921996226, TileMath.latToWy(casaLat), 1e-12)
    }

    @Test fun projectionRoundTrips() {
        for ((lat, lon) in listOf(33.5731 to -7.5898, -34.6 to 150.9, 0.0 to 0.0, 60.0 to 25.0)) {
            assertEquals(lat, TileMath.wyToLat(TileMath.latToWy(lat)), 1e-9)
            assertEquals(lon, TileMath.wxToLon(TileMath.lonToWx(lon)), 1e-9)
        }
    }

    @Test fun polesAreClampedNotInfinite() {
        assertTrue(TileMath.latToWy(90.0).isFinite())
        assertEquals(0, TileMath.tileY(90.0, 5))
        assertEquals(31, TileMath.tileY(-90.0, 5))
    }

    @Test fun tmsRowFlipsAxis() {
        assertEquals(1023, TileMath.tmsRow(0, 10))
        assertEquals(0, TileMath.tmsRow(1023, 10))
        assertEquals(613, TileMath.tmsRow(410, 10))
    }

    @Test fun boundsAroundIsAboutTheRequestedRadius() {
        val b = TileMath.boundsAround(casaLat, casaLon, 1000.0)
        assertEquals(33.56410679636275, b.minLat, 1e-9)
        assertEquals(-7.60059381795806, b.minLon, 1e-9)
        assertEquals(33.58209320363724, b.maxLat, 1e-9)
        assertEquals(-7.57900618204194, b.maxLon, 1e-9)
    }

    @Test fun tileCountMatchesIndependentReference() {
        val b = TileMath.boundsAround(casaLat, casaLon, 1000.0)
        assertEquals(9L, TileMath.range(b, 15).count)
        assertEquals(25L, TileMath.range(b, 16).count)
        assertEquals(81L, TileMath.range(b, 17).count)
        assertEquals(115L, TileMath.countTiles(b, 15, 17))
        val r = TileMath.range(b, 17)
        assertEquals(62768, r.x0); assertEquals(62776, r.x1)
        assertEquals(52542, r.y0); assertEquals(52550, r.y1)
    }

    @Test fun metersPerPixelMatchesStandardTable() {
        assertEquals(156543.03392804097, TileMath.metersPerPixel(0.0, 0.0), 1e-6)
        assertEquals(0.9950918251676788, TileMath.metersPerPixel(casaLat, 17.0), 1e-9)
    }
}

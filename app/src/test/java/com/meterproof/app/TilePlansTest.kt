package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** القيم المرجعية حُسبت بصيغة مستقلة (بايثون) بخوارزمية smart-remplacement نفسها. */
class TilePlansTest {

    private val pts = listOf(33.5731 to -7.5898, 33.5760 to -7.5850, 33.5702 to -7.5921)

    private fun countAt(p: TilePlan, z: Int) = p.tiles.count { it.z == z }

    @Test
    fun aroundAddsTheMargin() {
        val b = TilePlans.around(pts)!!
        assertEquals(33.5662, b.minLat, 1e-9)
        assertEquals(33.58, b.maxLat, 1e-9)
        assertEquals(-7.5961, b.minLon, 1e-9)
        assertEquals(-7.581, b.maxLon, 1e-9)
        assertNull(TilePlans.around(emptyList()))
    }

    @Test
    fun smallAreaMatchesIndependentReference() {
        val p = TilePlans.plan(TilePlans.around(pts)!!)
        assertEquals(64, p.total)
        assertEquals(13, p.zMin); assertEquals(17, p.zMax)
        assertEquals(listOf(2, 2, 6, 12, 42), (13..17).map { countAt(p, it) })
        // مدى التقريب 17: x 62770..62775 و y 52543..52549
        val z17 = p.tiles.filter { it.z == 17 }
        assertEquals(62770, z17.minOf { it.x }); assertEquals(62775, z17.maxOf { it.x })
        assertEquals(52543, z17.minOf { it.y }); assertEquals(52549, z17.maxOf { it.y })
        // أول بلاطة في التقريب 13 هي الوحيدة في x=3923
        assertTrue(p.tiles.filter { it.z == 13 }.all { it.x == 3923 && it.y in 3283..3284 })
    }

    @Test
    fun cityAreaMatchesIndependentReference() {
        // مدينة سيدي قاسم كما في smart-remplacement: [34.185,34.255,-5.775,-5.64]
        val p = TilePlans.plan(GeoBox(34.185, 34.255, -5.775, -5.64))
        assertEquals(2180, p.total)
        assertEquals(listOf(12, 35, 117, 416, 1600), (13..17).map { countAt(p, it) })
        assertEquals(17, p.zMax)
    }

    @Test
    fun estimateRoundsLikeTheWebApp() {
        val city = TilePlans.plan(GeoBox(34.185, 34.255, -5.775, -5.64))
        assertEquals(32, city.estimateMb(TileLayers.avgKb(TileLayers.PLAN)))   // 2180*15/1024 = 31.9
        assertEquals(75, city.estimateMb(TileLayers.avgKb(TileLayers.SAT)))    // 2180*35/1024 = 74.5
        val small = TilePlans.plan(TilePlans.around(pts)!!)
        assertEquals(1, small.estimateMb(15))
        assertEquals(2, small.estimateMb(35))
    }

    @Test
    fun capStopsAtTheFirstLevelThatWouldExceedIt() {
        val b = TilePlans.around(pts)!!
        val p = TilePlans.plan(b, cap = 50)       // 2+2+6+12 = 22 ثم +42 يتجاوز 50
        assertEquals(22, p.total)
        assertEquals(16, p.zMax)
        assertEquals(0, countAt(p, 17))
    }

    @Test
    fun hugeAreaStopsAtLevel13OrIsEmpty() {
        val huge = GeoBox(33.0, 35.0, -7.0, -5.0)
        val p = TilePlans.plan(huge)
        assertEquals(2632, p.total)
        assertEquals(13, p.zMax)
        assertTrue(TilePlans.plan(huge, cap = 100).isEmpty)
    }

    @Test
    fun neverExceedsTheDefaultCap() {
        for (box in listOf(GeoBox(34.185, 34.255, -5.775, -5.64), GeoBox(33.0, 35.0, -7.0, -5.0))) {
            assertTrue(TilePlans.plan(box).total <= TilePlans.MAX_TILES)
        }
    }

    @Test
    fun noDuplicateTiles() {
        val p = TilePlans.plan(GeoBox(34.185, 34.255, -5.775, -5.64))
        assertEquals(p.total, p.tiles.toSet().size)
    }
}

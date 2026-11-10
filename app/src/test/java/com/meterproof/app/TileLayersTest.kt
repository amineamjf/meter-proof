package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileLayersTest {

    @Test
    fun planUrlOrderIsZXY() {
        assertEquals(
            "https://basemaps.cartocdn.com/rastertiles/voyager/17/62772/52546.png",
            TileLayers.build(TileLayers.PLAN, 17, 62772, 52546)
        )
    }

    @Test
    fun satelliteUrlOrderIsZYX() {
        // Esri يطلب {z}/{y}/{x}: عكس المخطط
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/17/52546/62772",
            TileLayers.build(TileLayers.SAT, 17, 62772, 52546)
        )
    }

    @Test
    fun emptyCustomUrlMeansPlanOrSatellite() {
        assertEquals(TileLayers.PLAN, TileLayers.resolve(""))
        assertEquals(TileLayers.PLAN, TileLayers.resolve("   "))
        assertEquals(TileLayers.SAT, TileLayers.resolve("", satellite = true))
    }

    @Test
    fun customUrlWinsOverTheSatelliteSwitch() {
        val custom = "https://t.example.com/{z}/{x}/{y}.png?key=K"
        assertEquals(custom, TileLayers.resolve(" $custom ", satellite = true))
        assertEquals(custom, TileLayers.resolve(custom, satellite = false))
    }

    @Test
    fun attributionFollowsTheProvider() {
        assertEquals("© OpenStreetMap © CARTO", TileLayers.attribution(TileLayers.PLAN))
        assertEquals("© Esri", TileLayers.attribution(TileLayers.SAT))
        assertEquals("© OpenStreetMap © CARTO", TileLayers.attribution("https://x.example/{z}/{x}/{y}.png"))
    }

    @Test
    fun validation() {
        assertNull(TileLayers.validate(""))
        assertNull(TileLayers.validate(TileLayers.PLAN))
        assertNull(TileLayers.validate("https://t.example.com/{z}/{x}/{y}.png?key=K"))
        assertNotNull(TileLayers.validate("http://t.example.com/{z}/{x}/{y}.png"))
        assertNotNull(TileLayers.validate("https://t.example.com/{z}/{x}.png"))
        assertNotNull(TileLayers.validate("https://t.example.com/{z}/{x}/{y} .png"))
        assertNotNull(TileLayers.validate("https://t.example.com/" + "a".repeat(500) + "/{z}/{x}/{y}"))
    }

    @Test
    fun cacheKeysSeparateLayersAndAreStable() {
        val a = TileLayers.key(TileLayers.PLAN, 15, 1, 2)
        val b = TileLayers.key(TileLayers.SAT, 15, 1, 2)
        assertNotEquals(a, b)
        assertEquals(a, TileLayers.key(TileLayers.PLAN, 15, 1, 2))
        assertEquals(a, TileLayers.key("  " + TileLayers.PLAN + " ", 15, 1, 2))
        assertTrue(a.endsWith("|15/1/2"))
        assertEquals(12, TileLayers.id(TileLayers.PLAN).length)
    }

    @Test
    fun averageSizeEstimate() {
        assertEquals(15, TileLayers.avgKb(TileLayers.PLAN))
        assertEquals(35, TileLayers.avgKb(TileLayers.SAT))
        assertFalse(TileLayers.isSatellite(TileLayers.PLAN))
    }
}

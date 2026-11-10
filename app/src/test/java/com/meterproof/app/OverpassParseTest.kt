package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OverpassParseTest {

    private val sample = """
    {"version":0.6,"elements":[
      {"type":"way","id":11,"tags":{"highway":"residential","name":"Rue 1"},
       "geometry":[{"lat":34.22,"lon":-5.71},{"lat":34.2201,"lon":-5.7099}]},
      {"type":"way","id":12,"tags":{"building":"yes"},
       "geometry":[{"lat":34.2,"lon":-5.7},{"lat":34.2,"lon":-5.69},{"lat":34.21,"lon":-5.69},{"lat":34.2,"lon":-5.7}]},
      {"type":"way","id":13,"tags":{"highway":"construction"},
       "geometry":[{"lat":34.2,"lon":-5.7},{"lat":34.2,"lon":-5.69}]},
      {"type":"way","id":14,"tags":{"highway":"footway"},
       "geometry":[{"lat":34.2,"lon":-5.7}]},
      {"type":"node","id":15,"lat":34.2,"lon":-5.7,"tags":{"highway":"crossing"}},
      {"type":"way","id":16,"tags":{"waterway":"canal"},
       "geometry":[{"lat":34.2,"lon":-5.7},{"lat":34.201,"lon":-5.701}]}
    ]}
    """.trimIndent()

    @Test
    fun parsesKindsAndSkipsUselessElements() {
        val ways = OverpassClient.parse(sample)
        assertEquals(listOf(11L, 12L, 16L), ways.map { it.id })
        assertEquals(listOf(WayKind.MINOR, WayKind.BUILDING, WayKind.WATER_LINE), ways.map { it.kind })
        assertEquals(34220000, ways[0].lat[0])
        assertEquals(-5710000, ways[0].lon[0])
        assertEquals(4, ways[1].size)
    }

    @Test
    fun queryTimeoutRemarkIsRejectedNotAcceptedAsPartialData() {
        val bad = """{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1"}"""
        try {
            OverpassClient.parse(bad)
            assertTrue("كان يجب رفض الرد الجزئي", false)
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("لم يكمل"))
        }
    }

    @Test
    fun garbageIsRejected() {
        try {
            OverpassClient.parse("<html>busy</html>")
            assertTrue(false)
        } catch (e: IOException) {
            // المتوقع
        }
    }

    @Test
    fun queryContainsBoxAndAllFourKinds() {
        val q = OverpassClient.query(BBox(34.2, -5.72, 34.21, -5.71))
        assertTrue(q.contains("34.2,-5.72,34.21,-5.71"))
        assertTrue(q.contains("highway") && q.contains("building") && q.contains("waterway") && q.contains("natural"))
        assertTrue(q.contains("out geom"))
    }
}

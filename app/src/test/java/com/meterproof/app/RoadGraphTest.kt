package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadGraphTest {

    // إحداثيات بالدرجات × 1e6. الشبكة قرب سيدي قاسم (خط عرض 34.22)
    private fun way(id: Long, kind: Int, vararg pts: Pair<Double, Double>): OsmWay =
        OsmWay(
            id, kind,
            IntArray(pts.size) { Math.round(pts[it].first * 1e6).toInt() },
            IntArray(pts.size) { Math.round(pts[it].second * 1e6).toInt() }
        )

    private val a = Pair(34.2200, -5.7100)
    private val b = Pair(34.2200, -5.7050) // شرق a (نحو 460 م)
    private val c = Pair(34.2240, -5.7050) // شمال b (نحو 445 م)

    private fun lShape(): RoadGraph = RoadGraph.build(
        listOf(
            way(1, WayKind.MINOR, a, b),
            way(2, WayKind.MINOR, b, c)
        )
    )

    @Test
    fun routeFollowsTheCornerNotTheStraightLine() {
        val g = lShape()
        val r = g.route(a.first, a.second, c.first, c.second)
        assertNotNull(r)
        val straight = Geo.distanceM(a.first, a.second, c.first, c.second)
        // المسار الفعلي أطول من الخط المستقيم لأنه يمر عبر الزاوية b
        assertTrue(r!!.lengthM > straight * 1.2)
        val viaB = Geo.distanceM(a.first, a.second, b.first, b.second) +
            Geo.distanceM(b.first, b.second, c.first, c.second)
        assertEquals(viaB, r.lengthM, 3.0)
        assertTrue(r.points.any { Geo.distanceM(it.lat, it.lon, b.first, b.second) < 1.0 })
    }

    @Test
    fun startAndEndOffTheRoadAreSnappedAndConnected() {
        val g = lShape()
        // نقطتان على جانبي الطريق بعيدتان نحو 20 م
        val r = g.route(a.first + 0.0002, a.second + 0.001, c.first, c.second - 0.0002)
        assertNotNull(r)
        assertEquals(a.first + 0.0002, r!!.points.first().lat, 1e-9)
        assertEquals(c.second - 0.0002, r.points.last().lon, 1e-9)
        assertTrue(r.lengthM > 400.0)
    }

    @Test
    fun pointsOnTheSameSegmentGoDirectly() {
        val g = lShape()
        val r = g.route(a.first, a.second + 0.001, a.first, a.second + 0.003)
        assertNotNull(r)
        assertEquals(
            Geo.distanceM(a.first, a.second + 0.001, a.first, a.second + 0.003),
            r!!.lengthM, 3.0
        )
    }

    @Test
    fun farFromAnyRoadHasNoRoute() {
        val g = lShape()
        assertNull(g.route(34.30, -5.60, c.first, c.second))
    }

    @Test
    fun disconnectedIslandsHaveNoRoute() {
        val far1 = Pair(34.2210, -5.6900)
        val far2 = Pair(34.2210, -5.6850)
        val g = RoadGraph.build(
            listOf(
                way(1, WayKind.MINOR, a, b),
                way(2, WayKind.MINOR, far1, far2)
            )
        )
        // a و far1 كلاهما قريب من طريقه (ضمن 400 م) لكن الطريقين غير متصلين
        assertNull(g.route(a.first, a.second, far1.first, far1.second))
    }

    @Test
    fun buildingsAndMotorwaysAreNotRoutable() {
        val g = RoadGraph.build(
            listOf(
                way(1, WayKind.BUILDING, a, b, c, a),
                way(2, WayKind.MOTORWAY, a, b)
            )
        )
        assertEquals(0, g.segmentCount)
        assertNull(g.route(a.first, a.second, c.first, c.second))
    }

    @Test
    fun duplicateWayFromNeighbouringCellIsIgnored() {
        val one = RoadGraph.build(listOf(way(1, WayKind.MINOR, a, b)))
        val two = RoadGraph.build(listOf(way(1, WayKind.MINOR, a, b), way(1, WayKind.MINOR, a, b)))
        assertEquals(one.segmentCount, two.segmentCount)
    }

    @Test
    fun guideSaysTurnLeftAtTheCorner() {
        // مشي شرقًا ثم شمالًا: الانعطاف إلى اليسار (من الشرق إلى الشمال)
        val pts = listOf(
            GeoPoint(a.first, a.second), GeoPoint(b.first, b.second), GeoPoint(c.first, c.second)
        )
        val s = RouteGuide.instruction(pts, a.first, a.second + 0.0005)
        assertTrue(s, s.contains("يسارًا"))
        assertTrue(s, s.startsWith("بعد"))
    }

    @Test
    fun guideSaysArrivedNearTheEnd() {
        val pts = listOf(GeoPoint(a.first, a.second), GeoPoint(b.first, b.second))
        assertEquals("وصلت", RouteGuide.instruction(pts, b.first, b.second - 0.00005))
    }

    @Test
    fun offRouteDistanceIsMeasuredToTheNearestSegment() {
        val pts = listOf(GeoPoint(a.first, a.second), GeoPoint(b.first, b.second))
        val d = RouteGuide.offRouteM(pts, a.first + 0.0004, a.second + 0.002)
        assertEquals(44.0, d, 3.0)
    }

    @Test
    fun wayKindClassification() {
        assertEquals(WayKind.MAJOR, WayKind.classify("secondary", null, null, null))
        assertEquals(WayKind.MINOR, WayKind.classify("residential", null, null, null))
        assertEquals(WayKind.PATH, WayKind.classify("footway", null, null, null))
        assertEquals(0, WayKind.classify("construction", null, null, null))
        assertEquals(WayKind.BUILDING, WayKind.classify(null, "yes", null, null))
        assertEquals(0, WayKind.classify(null, "no", null, null))
        assertEquals(WayKind.WATER_LINE, WayKind.classify(null, null, "canal", null))
        assertEquals(WayKind.WATER_AREA, WayKind.classify(null, null, null, "water"))
        // الطريق يغلب المبنى
        assertEquals(WayKind.MINOR, WayKind.classify("residential", "yes", null, null))
    }

    @Test
    fun cellCodecRoundTripAndRejectsTruncatedFile() {
        val ways = listOf(way(7, WayKind.MINOR, a, b), way(8, WayKind.BUILDING, a, b, c, a))
        val out = java.io.ByteArrayOutputStream()
        OsmCellCodec.write(out, ways)
        val bytes = out.toByteArray()
        val back = OsmCellCodec.read(java.io.ByteArrayInputStream(bytes))
        assertEquals(2, back.size)
        assertEquals(7L, back[0].id)
        assertEquals(4, back[1].size)
        assertEquals(ways[1].lat.toList(), back[1].lat.toList())
        try {
            OsmCellCodec.read(java.io.ByteArrayInputStream(bytes.copyOf(bytes.size - 9)))
            assertTrue("كان يجب أن يُرفض الملف المقطوع", false)
        } catch (e: java.io.IOException) {
            // المتوقع
        }
    }
}

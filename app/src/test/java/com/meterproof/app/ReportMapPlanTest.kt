package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportMapPlanTest {

    private val W = 515.0
    private val H = 340.0
    private val DH = 300.0

    // سيدي قاسم تقريبًا
    private val lat0 = 34.22
    private val lon0 = -5.71

    private fun photo(i: Int, lat: Double, lon: Double) = PhotoPoint(i, "id$i", 0L, lat, lon, 5f)
    private fun stop(lat: Double, lon: Double) = Stop(0L, 1L, lat, lon, "end")
    private fun meter(lat: Double, lon: Double) =
        MeterPoint(1L, "M1", "t", "d", lat, lon, MeterMarkState.DONE, null, 0L)

    private fun track(vararg p: Pair<Double, Double>) = p.map { GeoPoint(it.first, it.second) }

    // ------------------------------------------------------------ التخطيط العام

    @Test fun noValidPointsGivesNoMaps() {
        val p = ReportMapPlanner.plan(emptyList(), emptyList(), listOf(stop(0.0, 0.0)), emptyList(), W, H, DH)
        assertNull(p.overview)
        assertTrue(p.details.isEmpty())
        assertTrue(p.allTiles().isEmpty())
    }

    @Test fun overviewContainsEveryPoint() {
        val ph = listOf(photo(0, lat0, lon0), photo(1, lat0 + 0.01, lon0 + 0.012))
        val p = ReportMapPlanner.plan(
            track(lat0 - 0.005 to lon0 - 0.004, lat0 + 0.012 to lon0 + 0.015), ph, emptyList(), emptyList(), W, H, DH
        )
        val v = p.overview!!
        val cx = TileMath.lonToX(v.centerLon, v.zoom, ReportMapPlanner.TILE_PTS)
        val cy = TileMath.latToY(v.centerLat, v.zoom, ReportMapPlanner.TILE_PTS)
        for ((la, lo) in listOf(lat0 - 0.005 to lon0 - 0.004, lat0 + 0.012 to lon0 + 0.015)) {
            val sx = v.w / 2 + TileMath.lonToX(lo, v.zoom, ReportMapPlanner.TILE_PTS) - cx
            val sy = v.h / 2 + TileMath.latToY(la, v.zoom, ReportMapPlanner.TILE_PTS) - cy
            assertTrue("x=$sx", sx in 0.0..v.w)
            assertTrue("y=$sy", sy in 0.0..v.h)
        }
    }

    @Test fun tileLevelIsSharperThanFitAndCapped() {
        val v = ReportMapPlanner.fit(GeoBox(lat0, lat0 + 0.02, lon0, lon0 + 0.02), W, H)
        assertTrue(v.tileLevel <= ReportMapPlanner.MAX_LEVEL)
        assertTrue("level=${v.tileLevel} zoom=${v.zoom}", v.tileLevel >= Math.ceil(v.zoom).toInt().coerceAtMost(ReportMapPlanner.MAX_LEVEL))
    }

    @Test fun singlePointDoesNotCollapse() {
        val v = ReportMapPlanner.fit(GeoBox(lat0, lat0, lon0, lon0), W, H)
        assertTrue(v.zoom <= ReportMapPlanner.MAX_FIT_ZOOM)
        assertEquals(ReportMapPlanner.MAX_LEVEL, v.tileLevel)
        assertTrue(ReportMapPlanner.tilesFor(v).isNotEmpty())
    }

    @Test fun tileCountIsCappedEvenForHugeAreas() {
        val v = ReportMapPlanner.fit(GeoBox(30.0, 36.0, -9.0, -2.0), W, H)
        assertTrue("n=${ReportMapPlanner.tilesFor(v).size}", ReportMapPlanner.tilesFor(v).size <= ReportMapPlanner.MAX_TILES_PER_MAP)
    }

    @Test fun tilesAreAllAtTheViewLevelAndCoverTheCenter() {
        val v = ReportMapPlanner.fit(GeoBox(lat0, lat0 + 0.01, lon0, lon0 + 0.01), W, H)
        val tiles = ReportMapPlanner.tilesFor(v)
        assertTrue(tiles.all { it.z == v.tileLevel })
        val n = (1 shl v.tileLevel).toDouble()
        val tx = Math.floor((v.centerLon + 180.0) / 360.0 * n).toInt()
        assertTrue(tiles.any { it.x == tx })
        assertEquals(tiles.size, tiles.distinct().size)
    }

    @Test fun tilesCoverTheWholePanel() {
        val v = ReportMapPlanner.fit(GeoBox(lat0, lat0 + 0.01, lon0, lon0 + 0.01), W, H)
        val cx = TileMath.lonToX(v.centerLon, v.zoom, ReportMapPlanner.TILE_PTS)
        val cy = TileMath.latToY(v.centerLat, v.zoom, ReportMapPlanner.TILE_PTS)
        val dst = ReportMapPlanner.TILE_PTS * Math.pow(2.0, v.zoom - v.tileLevel)
        val tiles = ReportMapPlanner.tilesFor(v)
        val left = tiles.minOf { it.x } * dst - cx + v.w / 2
        val right = (tiles.maxOf { it.x } + 1) * dst - cx + v.w / 2
        val top = tiles.minOf { it.y } * dst - cy + v.h / 2
        val bottom = (tiles.maxOf { it.y } + 1) * dst - cy + v.h / 2
        assertTrue("left=$left", left <= 0.001)
        assertTrue("right=$right", right >= v.w - 0.001)
        assertTrue("top=$top", top <= 0.001)
        assertTrue("bottom=$bottom", bottom >= v.h - 0.001)
    }

    // ------------------------------------------------------------ التجمعات والتفاصيل (D3)

    @Test fun nearbyPointsFormOneClusterAndFarOnesDoNot() {
        val pts = listOf(
            ReportPoint(lat0, lon0, ReportPointKind.PHOTO),
            ReportPoint(lat0 + 0.0001, lon0, ReportPointKind.STOP),    // ~11 م
            ReportPoint(lat0 + 0.05, lon0, ReportPointKind.PHOTO)      // بعيدة
        )
        val c = ReportMapPlanner.clusters(pts, 30.0)
        assertEquals(2, c.size)
        assertEquals(2, c[0].count)
        assertEquals(1, c[0].photos)
        assertEquals(1, c[0].stops)
        assertEquals(1, c[1].count)
    }

    @Test fun invalidPointsAreIgnoredInClusters() {
        val pts = listOf(
            ReportPoint(0.0, 0.0, ReportPointKind.PHOTO),
            ReportPoint(Double.NaN, lon0, ReportPointKind.PHOTO),
            ReportPoint(lat0, lon0, ReportPointKind.METER)
        )
        val c = ReportMapPlanner.clusters(pts, 30.0)
        assertEquals(1, c.size)
        assertEquals(1, c[0].meters)
    }

    @Test fun clusteringIsDeterministic() {
        val pts = (0 until 30).map { ReportPoint(lat0 + (it % 5) * 0.00005, lon0 + (it % 7) * 0.00005, ReportPointKind.PHOTO) }
        assertEquals(ReportMapPlanner.clusters(pts, 25.0), ReportMapPlanner.clusters(pts, 25.0))
    }

    @Test fun overlappingMarkersGetDetailMapsBiggestFirst() {
        // مجموعة كبيرة (5) ومجموعة صغيرة (2) متباعدتان، وكلتاهما متراكبة على الخارطة العامة
        val ph = ArrayList<PhotoPoint>()
        for (i in 0 until 5) ph.add(photo(i, lat0 + i * 0.00002, lon0))
        for (i in 5 until 7) ph.add(photo(i, lat0 + 0.03 + (i - 5) * 0.00002, lon0 + 0.03))
        val p = ReportMapPlanner.plan(emptyList(), ph, emptyList(), emptyList(), W, H, DH)
        assertEquals(2, p.clusters.size)
        assertEquals(5, p.clusters[0].count)
        assertEquals(2, p.clusters[1].count)
        assertEquals(p.clusters.size, p.details.size)
        // الخارطة التفصيلية أقرب من العامة
        assertTrue(p.details.all { it.zoom > p.overview!!.zoom })
    }

    @Test fun isolatedPointsGetNoDetailMaps() {
        val ph = listOf(photo(0, lat0, lon0), photo(1, lat0 + 0.05, lon0), photo(2, lat0, lon0 + 0.05))
        val p = ReportMapPlanner.plan(emptyList(), ph, emptyList(), emptyList(), W, H, DH)
        assertTrue(p.details.isEmpty())
    }

    @Test fun detailMapsAreLimited() {
        val ph = ArrayList<PhotoPoint>()
        var idx = 0
        // 10 تجمعات متباعدة، في كل منها نقطتان متلاصقتان
        for (g in 0 until 10) for (k in 0 until 2) {
            ph.add(photo(idx++, lat0 + g * 0.02 + k * 0.00001, lon0))
        }
        val p = ReportMapPlanner.plan(emptyList(), ph, emptyList(), emptyList(), W, H, DH)
        assertTrue(p.details.size <= ReportMapPlanner.MAX_DETAILS)
        assertEquals(ReportMapPlanner.MAX_DETAILS, p.details.size)
    }

    @Test fun zoomedInOverviewNeedsFewerDetails() {
        // نقطتان تبعدان ~40 م: تتراكبان في خارطة عامة واسعة، لا في خارطة ضيقة جدًا
        val wide = ReportMapPlanner.plan(
            track(lat0 to lon0, lat0 + 0.05 to lon0 + 0.05),
            listOf(photo(0, lat0, lon0), photo(1, lat0 + 0.00036, lon0)), emptyList(), emptyList(), W, H, DH
        )
        assertEquals(1, wide.details.size)
        val tight = ReportMapPlanner.plan(
            emptyList(),
            listOf(photo(0, lat0, lon0), photo(1, lat0 + 0.00036, lon0)), emptyList(), emptyList(), W, H, DH
        )
        assertTrue(tight.details.isEmpty())
    }

    @Test fun allTilesHaveNoDuplicatesAndIncludeOverview() {
        val ph = listOf(photo(0, lat0, lon0), photo(1, lat0 + 0.00002, lon0))
        val p = ReportMapPlanner.plan(
            track(lat0 to lon0, lat0 + 0.03 to lon0 + 0.03), ph, emptyList(), emptyList(), W, H, DH
        )
        val all = p.allTiles()
        assertEquals(all.size, all.distinct().size)
        assertTrue(all.containsAll(ReportMapPlanner.tilesFor(p.overview!!)))
    }

    @Test fun metersAndStopsAreIncludedInBounds() {
        val p = ReportMapPlanner.plan(
            emptyList(), emptyList(), listOf(stop(lat0, lon0)), listOf(meter(lat0 + 0.02, lon0 + 0.02)), W, H, DH
        )
        assertNotNull(p.overview)
        val b = ReportMapPlanner.boxOf(listOf(lat0 to lon0, lat0 + 0.02 to lon0 + 0.02))!!
        assertEquals((b.minLon + b.maxLon) / 2, p.overview!!.centerLon, 1e-9)
    }

    // ------------------------------------------------------------ النسب (D4)

    @Test fun attributionNamesTheProvider() {
        assertTrue(TileLayers.reportAttribution(TileLayers.SAT).contains("Esri"))
        assertTrue(TileLayers.reportAttribution(TileLayers.SAT).contains("Maxar"))
        assertTrue(TileLayers.reportAttribution(TileLayers.PLAN).contains("OpenStreetMap"))
        assertTrue(TileLayers.reportAttribution(TileLayers.PLAN).contains("CARTO"))
    }

    @Test fun customProviderIsNotMisattributed() {
        val a = TileLayers.reportAttribution("https://tiles.example.com/{z}/{x}/{y}.png?key=SECRET")
        assertFalse(a.contains("Esri"))
        assertFalse(a.contains("CARTO"))
        assertFalse("المفتاح لا يُطبع في التقرير", a.contains("SECRET"))
    }
}

package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaPlanTest {

    @Test
    fun noPointsMeansNoArea() {
        assertNull(AreaPlan.bboxAround(emptyList()))
    }

    @Test
    fun bboxAddsTheMarginOnEverySide() {
        val b = AreaPlan.bboxAround(listOf(GeoPoint(34.22, -5.71), GeoPoint(34.23, -5.70)), 500.0)!!
        // 500 م ≈ 0.0045 درجة عرض
        assertEquals(34.22 - 0.0045, b.minLat, 0.0002)
        assertEquals(34.23 + 0.0045, b.maxLat, 0.0002)
        assertTrue(b.minLon < -5.71 && b.maxLon > -5.70)
        assertTrue(b.contains(34.225, -5.705))
    }

    @Test
    fun cellsCoverTheWholeBoxWithStableIds() {
        val b = BBox(34.2150, -5.7150, 34.2350, -5.6950)
        val cells = AreaPlan.cellsFor(b)
        // كل نقطة في المستطيل تقع في إحدى القطع
        for (lat in listOf(34.2150, 34.22, 34.2349)) {
            for (lon in listOf(-5.7150, -5.70, -5.6951)) {
                assertTrue(cells.contains(AreaPlan.cellOf(lat, lon)))
            }
        }
        // الرقم مطلق: نفس القطعة تحمل نفس الرقم مهما كان المستطيل
        val other = AreaPlan.cellsFor(BBox(34.2250, -5.7050, 34.2260, -5.7040))
        assertTrue(cells.containsAll(other))
        assertEquals("3422_-571", AreaPlan.cellOf(34.2250, -5.7050).id)
    }

    @Test
    fun cellsStartFromTheCenter() {
        val b = BBox(34.20, -5.73, 34.25, -5.68)
        val cells = AreaPlan.cellsFor(b)
        val first = AreaPlan.boundsOf(cells.first())
        val midLat = (b.minLat + b.maxLat) / 2
        val midLon = (b.minLon + b.maxLon) / 2
        assertTrue(first.minLat - 0.011 <= midLat && midLat <= first.maxLat + 0.011)
        assertTrue(first.minLon - 0.011 <= midLon && midLon <= first.maxLon + 0.011)
    }

    @Test
    fun estimatesGrowWithCellCountAndNeverReachZero() {
        assertEquals(1, AreaPlan.estimateDownloadMb(1))
        assertTrue(AreaPlan.estimateDownloadMb(100) > AreaPlan.estimateDownloadMb(10))
        assertTrue(AreaPlan.estimateStoredMb(100) <= AreaPlan.estimateDownloadMb(100))
    }

    @Test
    fun sizeKmIsRoughlyRight() {
        val (w, h) = AreaPlan.sizeKm(BBox(34.20, -5.70, 34.21, -5.69))
        assertEquals(1.11, h, 0.02)
        assertEquals(0.92, w, 0.03)
    }
}

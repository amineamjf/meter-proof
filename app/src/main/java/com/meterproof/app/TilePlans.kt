package com.meterproof.app

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class GeoBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

data class TileId(val z: Int, val x: Int, val y: Int)

class TilePlan(val tiles: List<TileId>, val zMin: Int, val zMax: Int) {
    val total: Int get() = tiles.size
    val isEmpty: Boolean get() = tiles.isEmpty()

    /** حجم تقريبي بالميغابايت (يُقرَّب كما في smart-remplacement). */
    fun estimateMb(kbPerTile: Int): Int = Math.round(total * kbPerTile / 1024.0).toInt()
}

/**
 * خطة تحميل مناطق البلاطات، بنفس خوارزمية smart-remplacement:
 * التقريب من 13 إلى 17، ويتوقف عند أول مستوى يتجاوز السقف (3500 بلاطة) فلا تتجاوزه الخطة.
 * نقية بلا أندرويد.
 */
object TilePlans {
    const val Z_MIN = 13
    const val Z_MAX = 17
    const val MAX_TILES = 3500

    /** هامش حول النقاط بالدرجات (≈ 440 م) كما في "حول عدادي". */
    const val AROUND_MARGIN_DEG = 0.004

    /** مستطيل يحيط بالنقاط (خط عرض، خط طول) مع هامش، أو null إن لم توجد نقاط. */
    fun around(points: List<Pair<Double, Double>>, margin: Double = AROUND_MARGIN_DEG): GeoBox? {
        if (points.isEmpty()) return null
        var minLat = points[0].first; var maxLat = minLat
        var minLon = points[0].second; var maxLon = minLon
        for ((la, lo) in points) {
            minLat = min(minLat, la); maxLat = max(maxLat, la)
            minLon = min(minLon, lo); maxLon = max(maxLon, lo)
        }
        return GeoBox(minLat - margin, maxLat + margin, minLon - margin, maxLon + margin)
    }

    fun plan(box: GeoBox, zMin: Int = Z_MIN, zMax: Int = Z_MAX, cap: Int = MAX_TILES): TilePlan {
        val tiles = ArrayList<TileId>()
        var lastZ = zMin - 1
        for (z in zMin..zMax) {
            val n = 1 shl z
            fun tx(lon: Double) = floor(TileMath.lonToX(lon, z.toDouble(), 256.0) / 256.0).toInt().coerceIn(0, n - 1)
            fun ty(lat: Double) = floor(TileMath.latToY(lat, z.toDouble(), 256.0) / 256.0).toInt().coerceIn(0, n - 1)
            val x0 = tx(box.minLon); val x1 = tx(box.maxLon)
            val y0 = ty(box.maxLat); val y1 = ty(box.minLat)
            val count = (x1 - x0 + 1) * (y1 - y0 + 1)
            if (tiles.size + count > cap) break
            for (x in x0..x1) for (y in y0..y1) tiles.add(TileId(z, x, y))
            lastZ = z
        }
        return TilePlan(tiles, zMin, lastZ)
    }
}

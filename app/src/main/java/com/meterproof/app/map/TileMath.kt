package com.meterproof.app.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** حدود جغرافية مستطيلة. */
data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    val centerLat get() = (minLat + maxLat) / 2
    val centerLon get() = (minLon + maxLon) / 2
}

/** مدى بلاطات على مستوى تقريب واحد (شاملة الأطراف). */
data class TileRange(val z: Int, val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
    val count: Long get() = (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
}

/**
 * رياضيات ويب ميركاتور (EPSG:3857) لبلاطات 256×256، منطق نقي يُختبر على JVM.
 * الإحداثيات "العالمية" مطبَّعة: x و y بين 0 و1 على كامل العالم (y=0 في الشمال).
 */
object TileMath {
    const val TILE = 256
    const val MAX_LAT = 85.0511287798066
    private const val EQUATOR_M = 40075016.68557849

    fun lonToWx(lon: Double): Double = (lon + 180.0) / 360.0

    fun latToWy(lat: Double): Double {
        val l = lat.coerceIn(-MAX_LAT, MAX_LAT)
        val r = Math.toRadians(l)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    fun wxToLon(wx: Double): Double = wx * 360.0 - 180.0

    fun wyToLat(wy: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * wy))))

    fun tileX(lon: Double, z: Int): Int =
        floor(lonToWx(lon) * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun tileY(lat: Double, z: Int): Int =
        floor(latToWy(lat) * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    /** صف MBTiles (TMS) يبدأ من الجنوب، عكس XYZ. */
    fun tmsRow(y: Int, z: Int): Int = (1 shl z) - 1 - y

    fun range(b: Bounds, z: Int): TileRange = TileRange(
        z,
        tileX(b.minLon, z), tileX(b.maxLon, z),
        tileY(b.maxLat, z), tileY(b.minLat, z)
    )

    fun countTiles(b: Bounds, zMin: Int, zMax: Int): Long {
        var n = 0L
        for (z in zMin..zMax) n += range(b, z).count
        return n
    }

    /** مربع حول نقطة بنصف ضلع [radiusM] متر. */
    fun boundsAround(lat: Double, lon: Double, radiusM: Double): Bounds {
        val dLat = Math.toDegrees(radiusM / 6371008.8)
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
        return Bounds(
            (lat - dLat).coerceAtLeast(-MAX_LAT), (lon - dLon).coerceAtLeast(-180.0),
            (lat + dLat).coerceAtMost(MAX_LAT), (lon + dLon).coerceAtMost(180.0)
        )
    }

    /** متر حقيقي لكل بكسل عند خط عرض وتقريب (بلاطات 256). */
    fun metersPerPixel(lat: Double, zoom: Double): Double =
        EQUATOR_M * cos(Math.toRadians(lat)) / (TILE * 2.0.pow(zoom))

    fun worldMetersPerNormalizedUnit(lat: Double): Double = EQUATOR_M * cos(Math.toRadians(lat))
}

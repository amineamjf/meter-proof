package com.meterproof.app.map

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.floor

/** خانة بلاطة على الشاشة. */
data class TileSlot(val z: Int, val x: Int, val y: Int, val left: Float, val top: Float, val size: Float)

/**
 * كاميرا تلائم النقاط داخل اللوحة (نفس سلوك المُرسِم القديم: هامش، مدى أدنى، توسيط)،
 * لكن بإسقاط ميركاتور كي تنطبق البلاطات على الدوائر والمسار تمامًا.
 * منطق نقي بلا أي صنف أندرويد.
 */
class MapCamera private constructor(
    val width: Float,
    val height: Float,
    /** بكسل لكل وحدة عالمية مطبَّعة (= 256 × 2^zoom). */
    val pxPerUnit: Double,
    private val cx: Double,
    private val cy: Double,
    val centerLat: Double
) {
    val zoom: Double get() = log2(pxPerUnit / TileMath.TILE)

    fun x(lon: Double): Float = (width / 2 + (TileMath.lonToWx(lon) - cx) * pxPerUnit).toFloat()
    fun y(lat: Double): Float = (height / 2 + (TileMath.latToWy(lat) - cy) * pxPerUnit).toFloat()

    /** متر حقيقي لكل بكسل عند مركز الكاميرا. */
    val metersPerPixel: Double
        get() = TileMath.worldMetersPerNormalizedUnit(centerLat) / pxPerUnit

    /** مستوى تقريب البلاطات الأنسب ضمن المتاح (التكبير الزائد أفضل من لا شيء). */
    fun tileZoom(minZ: Int, maxZ: Int): Int = round(zoom).toInt().coerceIn(minZ, maxZ)

    /** البلاطات المرئية على مستوى [z]، مع حد أقصى لحماية الذاكرة. */
    fun slots(z: Int, limit: Int = 120): List<TileSlot> {
        val n = 1 shl z
        val size = (pxPerUnit / n).toFloat()
        val wx0 = cx - (width / 2) / pxPerUnit
        val wx1 = cx + (width / 2) / pxPerUnit
        val wy0 = cy - (height / 2) / pxPerUnit
        val wy1 = cy + (height / 2) / pxPerUnit
        val tx0 = floor(wx0 * n).toInt()
        val tx1 = floor(wx1 * n).toInt()
        val ty0 = max(0, floor(wy0 * n).toInt())
        val ty1 = min(n - 1, floor(wy1 * n).toInt())
        val out = ArrayList<TileSlot>()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            if (out.size >= limit) return out
            val left = (width / 2 + (tx.toDouble() / n - cx) * pxPerUnit).toFloat()
            val top = (height / 2 + (ty.toDouble() / n - cy) * pxPerUnit).toFloat()
            out.add(TileSlot(z, ((tx % n) + n) % n, ty, left, top, size))
        }
        return out
    }

    companion object {
        /** نفس المدى الأدنى القديم (~55 م) كي لا ينهار الرسم عند نقطة واحدة. */
        const val MIN_SPAN_M = 55.0

        fun fit(points: List<Pair<Double, Double>>, w: Float, h: Float, pad: Float): MapCamera? {
            if (points.isEmpty()) return null
            var minLat = points[0].first; var maxLat = minLat
            var minLon = points[0].second; var maxLon = minLon
            for ((lat, lon) in points) {
                minLat = min(minLat, lat); maxLat = max(maxLat, lat)
                minLon = min(minLon, lon); maxLon = max(maxLon, lon)
            }
            val midLat = (minLat + maxLat) / 2
            val wx0 = TileMath.lonToWx(minLon); val wx1 = TileMath.lonToWx(maxLon)
            val wy0 = TileMath.latToWy(maxLat); val wy1 = TileMath.latToWy(minLat)
            val minSpan = MIN_SPAN_M / TileMath.worldMetersPerNormalizedUnit(midLat)
            val spanX = max(wx1 - wx0, minSpan)
            val spanY = max(wy1 - wy0, minSpan)
            val availW = max(w - 2 * pad, 1f)
            val availH = max(h - 2 * pad, 1f)
            val pxPerUnit = min(availW / spanX, availH / spanY)
            return MapCamera(w, h, pxPerUnit, (wx0 + wx1) / 2, (wy0 + wy1) / 2, midLat)
        }
    }
}

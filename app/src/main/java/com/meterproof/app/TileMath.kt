package com.meterproof.app

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * حسابات بلاطات الخارطة (إسقاط ويب ميركاتور)، نقيّة بلا أندرويد كي تُختبر على JVM.
 *
 * "tilePx" هو حجم البلاطة المعروضة على الشاشة بالبكسل عند التكبير الصحيح. العالم كله مربع
 * طول ضلعه tilePx * 2^zoom. التكبير عدد حقيقي (كسري) أثناء القرص بالأصابع.
 */
object TileMath {

    /** أقصى خط عرض يغطيه ويب ميركاتور. */
    const val MAX_LAT = 85.0511287798

    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    fun worldSize(zoom: Double, tilePx: Double): Double = tilePx * 2.0.pow(zoom)

    fun lonToX(lon: Double, zoom: Double, tilePx: Double): Double =
        (lon + 180.0) / 360.0 * worldSize(zoom, tilePx)

    fun latToY(lat: Double, zoom: Double, tilePx: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSize(zoom, tilePx)
    }

    fun xToLon(x: Double, zoom: Double, tilePx: Double): Double =
        x / worldSize(zoom, tilePx) * 360.0 - 180.0

    fun yToLat(y: Double, zoom: Double, tilePx: Double): Double {
        val n = PI - 2 * PI * y / worldSize(zoom, tilePx)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** المتر لكل بكسل شاشة عند خط العرض والتكبير المعطيين. */
    fun metersPerPixel(lat: Double, zoom: Double, tilePx: Double): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / worldSize(zoom, tilePx)

    /**
     * أكبر تكبير يتسع عنده المستطيل الجغرافي داخل w × h بكسل، بعد خصم هامش [pad] من كل جهة.
     * يُحدّ بـ [maxZoom] كي لا ينهار الأمر عند نقطة واحدة.
     */
    fun fitZoom(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        w: Double, h: Double, tilePx: Double, pad: Double, maxZoom: Double
    ): Double {
        val availW = max(w - 2 * pad, 1.0)
        val availH = max(h - 2 * pad, 1.0)
        val dx = max(lonToX(maxLon, 0.0, tilePx) - lonToX(minLon, 0.0, tilePx), 1e-12)
        val dy = max(latToY(minLat, 0.0, tilePx) - latToY(maxLat, 0.0, tilePx), 1e-12)
        val z = ln(min(availW / dx, availH / dy)) / ln(2.0)
        return min(z, maxZoom)
    }

    /**
     * مدى فهارس البلاطات المرئية على محور واحد عند مستوى z. [centerPx] مركز الشاشة في إحداثيات
     * العالم بحجم بلاطة معروضة [dst]، و[extentPx] طول الشاشة على هذا المحور. فارغ إن لم يظهر شيء.
     */
    fun tileRange(centerPx: Double, extentPx: Double, dst: Double, z: Int): IntRange {
        val n = 1 shl z
        val lo = max(0, floor((centerPx - extentPx / 2) / dst).toInt())
        val hi = min(n - 1, floor((centerPx + extentPx / 2) / dst).toInt())
        return if (hi < lo) IntRange(1, 0) else IntRange(lo, hi)
    }
}

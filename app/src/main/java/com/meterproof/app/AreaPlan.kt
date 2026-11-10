package com.meterproof.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** مستطيل جغرافي بالدرجات. */
data class BBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in minLat..maxLat && lon in minLon..maxLon
}

/** قطعة من شبكة ثابتة (كل قطعة 0.01 درجة). الرقم مطلق، فلا يتغير إن تغيّرت منطقة الجولة. */
data class AreaCell(val row: Int, val col: Int) {
    val id: String get() = "${row}_${col}"
}

/**
 * تخطيط تحميل خارطة المنطقة: مستطيل حول عدادات الجولة بهامش، يُقسَّم إلى قطع ثابتة تُحمَّل واحدة واحدة
 * (فيمكن الاستئناف بعد انقطاع الشبكة دون إعادة ما اكتمل). نقي بلا أندرويد.
 */
object AreaPlan {

    /** حجم القطعة بالدرجات (نحو 1.1 كم × 0.9 كم عند خط عرض 34). */
    const val CELL_DEG = 0.01

    /** أقصى عدد قطع في عملية واحدة، كي لا نرهق خوادم البيانات العامة. */
    const val MAX_CELLS = 400

    /** هامش افتراضي حول أبعد عدادات الجولة. */
    const val DEFAULT_MARGIN_M = 600.0

    // تقدير تقريبي لحجم تحميل قطعة حضرية (طرق ومبانٍ ومياه، مضغوطة). ليس قياسًا.
    private const val DOWNLOAD_BYTES_PER_CELL = 450_000L
    private const val STORED_BYTES_PER_CELL = 250_000L

    /** مستطيل حول النقاط بهامش بالمتر، أو null إن لم توجد نقاط. */
    fun bboxAround(points: List<GeoPoint>, marginM: Double = DEFAULT_MARGIN_M): BBox? {
        if (points.isEmpty()) return null
        var minLat = points[0].lat
        var maxLat = points[0].lat
        var minLon = points[0].lon
        var maxLon = points[0].lon
        for (p in points) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        val midLat = (minLat + maxLat) / 2
        val dLat = marginM / 111_320.0
        val dLon = marginM / (111_320.0 * max(cos(Math.toRadians(midLat)), 0.2))
        return BBox(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    fun cellOf(lat: Double, lon: Double): AreaCell =
        AreaCell(floor(lat / CELL_DEG).toInt(), floor(lon / CELL_DEG).toInt())

    fun boundsOf(c: AreaCell): BBox = BBox(
        c.row * CELL_DEG, c.col * CELL_DEG, (c.row + 1) * CELL_DEG, (c.col + 1) * CELL_DEG
    )

    /** كل القطع التي تغطي المستطيل، مرتبة من الأقرب إلى مركزه (فيتوفر الأهم أولًا إن توقف التحميل). */
    fun cellsFor(b: BBox): List<AreaCell> {
        val r0 = floor(b.minLat / CELL_DEG).toInt()
        val r1 = floor(b.maxLat / CELL_DEG).toInt()
        val c0 = floor(b.minLon / CELL_DEG).toInt()
        val c1 = floor(b.maxLon / CELL_DEG).toInt()
        val out = ArrayList<AreaCell>((r1 - r0 + 1) * (c1 - c0 + 1))
        for (r in r0..r1) for (c in c0..c1) out.add(AreaCell(r, c))
        val cr = (b.minLat + b.maxLat) / 2 / CELL_DEG - 0.5
        val cc = (b.minLon + b.maxLon) / 2 / CELL_DEG - 0.5
        out.sortBy { abs(it.row - cr) + abs(it.col - cc) }
        return out
    }

    /** حجم التحميل التقريبي بالميغابايت (تقدير فقط، يُعرض للمستخدم على أنه تقريبي). */
    fun estimateDownloadMb(cells: Int): Int =
        max(1, ceil(cells * DOWNLOAD_BYTES_PER_CELL / (1024.0 * 1024.0)).toInt())

    /** المساحة التقريبية على الهاتف بالميغابايت بعد التخزين. */
    fun estimateStoredMb(cells: Int): Int =
        max(1, ceil(cells * STORED_BYTES_PER_CELL / (1024.0 * 1024.0)).toInt())

    /** عرض وارتفاع المستطيل بالكيلومتر (للعرض). */
    fun sizeKm(b: BBox): Pair<Double, Double> {
        val h = (b.maxLat - b.minLat) * 111.32
        val w = (b.maxLon - b.minLon) * 111.32 * cos(Math.toRadians((b.minLat + b.maxLat) / 2))
        return Pair(w, h)
    }
}

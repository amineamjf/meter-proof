package com.meterproof.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * تخطيط خرائط تقرير PDF (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * يحدد لكل خارطة في التقرير: المركز والتكبير ومستوى البلاطات، وأي بلاطات تلزم، ويجمّع النقاط المتقاربة
 * (التي تتراكب دوائرها على الخارطة العامة) في تجمعات تُرسم لكل منها خارطة تفصيلية مكبّرة (D3).
 *
 * وحدة الطول هنا نقطة PDF، وبلاطة التكبير الصحيح تساوي [ReportMapPlanner.TILE_PTS] نقطة.
 */
enum class ReportPointKind { PHOTO, STOP, METER }

class ReportPoint(val lat: Double, val lon: Double, val kind: ReportPointKind)

/** خارطة واحدة: مركز وتكبير حقيقي ومستوى بلاطات صحيح وحجم اللوحة بنقاط PDF. */
data class ReportMapView(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double,
    val tileLevel: Int,
    val w: Double,
    val h: Double
)

/** تجمع نقاط متقاربة: صندوقه وعدد ما فيه من كل نوع. */
data class ReportCluster(val box: GeoBox, val photos: Int, val stops: Int, val meters: Int) {
    val count: Int get() = photos + stops + meters
}

/** details[i] هي الخارطة التفصيلية للتجمع clusters[i]. */
class ReportMapPlan(
    val overview: ReportMapView?,
    val details: List<ReportMapView>,
    val clusters: List<ReportCluster>
) {
    /** كل البلاطات التي يحتاجها التقرير (بلا تكرار)، لجلب الناقص منها عند التصدير. */
    fun allTiles(): List<TileId> =
        (listOfNotNull(overview) + details).flatMap { ReportMapPlanner.tilesFor(it) }.distinct()
}

object ReportMapPlanner {

    /** حجم بلاطة التكبير الصحيح بنقاط PDF. */
    const val TILE_PTS = 256.0

    /** أعلى مستوى بلاطات (مثل TilePlans.Z_MAX، فلا نطلب مستوى لم يعتمده التطبيق للتحميل). */
    const val MAX_LEVEL = 17

    /** نرفع مستوى البلاطات درجة فوق ما يلزم للعرض، فتتضاعف دقة الخلفية على كل محور (D3). */
    const val SHARPNESS_BOOST = 1

    /** سقف بلاطات الخارطة الواحدة، فلا يكبر الجلب ولا الذاكرة. */
    const val MAX_TILES_PER_MAP = 80

    const val PAD_PTS = 28.0
    const val MAX_FIT_ZOOM = 18.0

    /** أقصى عدد خرائط تفصيلية في تقرير واحد (لحجم الملف). */
    const val MAX_DETAILS = 6

    /** أقل نقاط في تجمع يستحق خارطة تفصيلية. */
    const val MIN_CLUSTER_POINTS = 2

    /** المسافة بنقاط PDF التي تتراكب عندها الدوائر على الخارطة العامة (قطر الدائرة تقريبًا). */
    const val OVERLAP_PTS = 28.0

    private const val METERS_PER_DEG = 111_320.0

    /** إحداثية صالحة للرسم: منتهية، داخل مدى ميركاتور، وليست (0,0) الافتراضية. */
    fun valid(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && abs(lat) <= TileMath.MAX_LAT && abs(lon) <= 180.0 &&
            !(lat == 0.0 && lon == 0.0)

    fun boxOf(coords: List<Pair<Double, Double>>): GeoBox? {
        val c = coords.filter { valid(it.first, it.second) }
        if (c.isEmpty()) return null
        var minLat = c[0].first; var maxLat = minLat
        var minLon = c[0].second; var maxLon = minLon
        for ((la, lo) in c) {
            minLat = min(minLat, la); maxLat = max(maxLat, la)
            minLon = min(minLon, lo); maxLon = max(maxLon, lo)
        }
        return GeoBox(minLat, maxLat, minLon, maxLon)
    }

    /** خارطة تحتوي الصندوق كله داخل w × h، بمستوى بلاطات لا يتجاوز السقف. */
    fun fit(box: GeoBox, w: Double, h: Double, maxFitZoom: Double = MAX_FIT_ZOOM): ReportMapView {
        val zoom = TileMath.fitZoom(
            box.minLat, box.maxLat, box.minLon, box.maxLon, w, h, TILE_PTS, PAD_PTS, maxFitZoom
        ).coerceAtLeast(0.0)
        val cLon = (box.minLon + box.maxLon) / 2
        // المنتصف في إسقاط ميركاتور (لا متوسط العرض) كي تتوسط النقاط اللوحة
        val midY = (TileMath.latToY(box.minLat, 0.0, TILE_PTS) + TileMath.latToY(box.maxLat, 0.0, TILE_PTS)) / 2
        val cLat = TileMath.yToLat(midY, 0.0, TILE_PTS)
        var level = min(ceil(zoom).toInt() + SHARPNESS_BOOST, MAX_LEVEL).coerceAtLeast(0)
        while (level > 0 && tileCount(cLat, cLon, zoom, level, w, h) > MAX_TILES_PER_MAP) level--
        return ReportMapView(cLat, cLon, zoom, level, w, h)
    }

    private fun ranges(cLat: Double, cLon: Double, zoom: Double, level: Int, w: Double, h: Double): Pair<IntRange, IntRange> {
        val cx = TileMath.lonToX(cLon, zoom, TILE_PTS)
        val cy = TileMath.latToY(cLat, zoom, TILE_PTS)
        val dst = TILE_PTS * 2.0.pow(zoom - level)
        return TileMath.tileRange(cx, w, dst, level) to TileMath.tileRange(cy, h, dst, level)
    }

    private fun tileCount(cLat: Double, cLon: Double, zoom: Double, level: Int, w: Double, h: Double): Int {
        val (xs, ys) = ranges(cLat, cLon, zoom, level, w, h)
        return (xs.last - xs.first + 1).coerceAtLeast(0) * (ys.last - ys.first + 1).coerceAtLeast(0)
    }

    /** البلاطات الظاهرة في الخارطة عند مستواها. */
    fun tilesFor(v: ReportMapView): List<TileId> {
        val (xs, ys) = ranges(v.centerLat, v.centerLon, v.zoom, v.tileLevel, v.w, v.h)
        val out = ArrayList<TileId>()
        for (y in ys) for (x in xs) out.add(TileId(v.tileLevel, x, y))
        return out
    }

    /** المتر لكل نقطة PDF في الخارطة. */
    fun metersPerPt(v: ReportMapView): Double = TileMath.metersPerPixel(v.centerLat, v.zoom, TILE_PTS)

    private fun distM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dy = (aLat - bLat) * METERS_PER_DEG
        val dx = (aLon - bLon) * METERS_PER_DEG * cos(Math.toRadians((aLat + bLat) / 2))
        return Math.sqrt(dx * dx + dy * dy)
    }

    private class Acc {
        var sumLat = 0.0; var sumLon = 0.0; var n = 0
        var minLat = 0.0; var maxLat = 0.0; var minLon = 0.0; var maxLon = 0.0
        var photos = 0; var stops = 0; var meters = 0

        fun lat() = sumLat / n
        fun lon() = sumLon / n

        fun add(p: ReportPoint) {
            if (n == 0) {
                minLat = p.lat; maxLat = p.lat; minLon = p.lon; maxLon = p.lon
            } else {
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
            }
            sumLat += p.lat; sumLon += p.lon; n++
            when (p.kind) {
                ReportPointKind.PHOTO -> photos++
                ReportPointKind.STOP -> stops++
                ReportPointKind.METER -> meters++
            }
        }
    }

    /**
     * يجمّع النقاط التي تبعد أقل من [radiusM] عن مركز تجمع قائم (حتمي: بترتيب الإدخال).
     * النقاط غير الصالحة تُهمل.
     */
    fun clusters(points: List<ReportPoint>, radiusM: Double): List<ReportCluster> {
        val accs = ArrayList<Acc>()
        for (p in points) {
            if (!valid(p.lat, p.lon)) continue
            var best: Acc? = null
            var bestD = Double.MAX_VALUE
            for (a in accs) {
                val d = distM(a.lat(), a.lon(), p.lat, p.lon)
                if (d <= radiusM && d < bestD) { best = a; bestD = d }
            }
            (best ?: Acc().also { accs.add(it) }).add(p)
        }
        return accs.map {
            ReportCluster(GeoBox(it.minLat, it.maxLat, it.minLon, it.maxLon), it.photos, it.stops, it.meters)
        }
    }

    /**
     * خطة التقرير: خارطة عامة تحوي كل شيء، وخرائط تفصيلية لأكبر التجمعات المتراكبة على الخارطة العامة.
     * لا نقاط صالحة: overview = null ولا تفاصيل.
     */
    fun plan(
        track: List<GeoPoint>,
        photos: List<PhotoPoint>,
        stops: List<Stop>,
        meters: List<MeterPoint>,
        mapW: Double,
        mapH: Double,
        detailH: Double
    ): ReportMapPlan {
        val markers = ArrayList<ReportPoint>()
        for (p in photos) markers.add(ReportPoint(p.lat, p.lon, ReportPointKind.PHOTO))
        for (s in stops) markers.add(ReportPoint(s.lat, s.lon, ReportPointKind.STOP))
        for (m in meters) markers.add(ReportPoint(m.lat, m.lon, ReportPointKind.METER))

        val coords = ArrayList<Pair<Double, Double>>()
        for (t in track) coords.add(t.lat to t.lon)
        for (m in markers) coords.add(m.lat to m.lon)
        val box = boxOf(coords) ?: return ReportMapPlan(null, emptyList(), emptyList())

        val overview = fit(box, mapW, mapH)
        val radius = OVERLAP_PTS * metersPerPt(overview)
        val chosen = clusters(markers, radius)
            .filter { it.count >= MIN_CLUSTER_POINTS }
            .sortedByDescending { it.count }   // مستقر: التعادل يبقى بترتيب الظهور
            .take(MAX_DETAILS)
        val details = chosen.map { fit(it.box, mapW, detailH) }
        return ReportMapPlan(overview, details, chosen)
    }
}

package com.meterproof.app

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** مسار بين نقطتين على الشوارع: نقاطه وطوله بالمتر. */
data class Route(val points: List<GeoPoint>, val lengthM: Double)

private class IntBuf(cap: Int = 1024) {
    var a = IntArray(cap)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun toArray(): IntArray = a.copyOf(n)
}

private class DoubleBuf(cap: Int = 1024) {
    var a = DoubleArray(cap)
    var n = 0
    fun add(v: Double) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun toArray(): DoubleArray = a.copyOf(n)
}

/**
 * رسم الطرق القابلة للمشي ومسار أقصر طريق (ديكسترا) بين نقطتين، دون إنترنت.
 * هذا هو «توجيه المسار داخل الشوارع» على نمط تطبيق Smart Remplacement.
 *
 * العقد تُدمج بتطابق الإحداثية تمامًا (كما تشترك طرق OSM في العقد). كل نقطة بداية أو نهاية تُسقَط
 * على أقرب قطعة طريق (لا أقرب عقدة)، فلا يطول المسار بسبب تباعد العقد في الأزقة الطويلة.
 * نقي بلا أندرويد.
 */
class RoadGraph private constructor(
    private val nLat: DoubleArray,
    private val nLon: DoubleArray,
    private val adjStart: IntArray,
    private val adjTo: IntArray,
    private val adjLen: DoubleArray,
    private val segA: IntArray,
    private val segB: IntArray,
    private val grid: HashMap<Long, IntArray>
) {

    val nodeCount: Int get() = nLat.size
    val segmentCount: Int get() = segA.size

    private class Snap(
        val seg: Int,
        val lat: Double,
        val lon: Double,
        val distM: Double,
        val lenA: Double,
        val lenB: Double
    )

    private class Entry(val node: Int, val d: Double)

    companion object {
        private const val SCALE = 1e-6
        private const val GRID_DEG = 0.0005
        /** متر لكل درجة (نفس نصف قطر الأرض في Geo، فتتسق الأطوال). */
        private const val M_PER_DEG = 111_195.0
        private const val KY = M_PER_DEG

        /** أقصى بعد عن أقرب طريق لاعتبار النقطة على الشبكة. */
        const val MAX_SNAP_M = 400.0

        /** سرعة المشي التقريبية لتقدير الزمن (متر في الدقيقة). */
        const val WALK_M_PER_MIN = 83.0

        private fun key(a: Int, b: Int): Long = (a.toLong() shl 32) xor (b.toLong() and 0xFFFFFFFFL)

        fun build(ways: List<OsmWay>): RoadGraph {
            val seen = HashSet<Long>()
            val ids = HashMap<Long, Int>()
            val latB = DoubleBuf()
            val lonB = DoubleBuf()
            val from = IntBuf()
            val to = IntBuf()

            fun node(la: Int, lo: Int): Int {
                val k = key(la, lo)
                val have = ids[k]
                if (have != null) return have
                val i = latB.n
                ids[k] = i
                latB.add(la * SCALE)
                lonB.add(lo * SCALE)
                return i
            }

            for (w in ways) {
                if (!WayKind.walkable(w.kind) || w.size < 2) continue
                if (!seen.add(w.id)) continue // الطريق نفسه قد يرد في قطعتين متجاورتين
                var prev = node(w.lat[0], w.lon[0])
                for (i in 1 until w.size) {
                    val cur = node(w.lat[i], w.lon[i])
                    if (cur != prev) {
                        from.add(prev)
                        to.add(cur)
                    }
                    prev = cur
                }
            }

            val nLat = latB.toArray()
            val nLon = lonB.toArray()
            val n = nLat.size
            val eFrom = from.toArray()
            val eTo = to.toArray()
            val m = eFrom.size

            val start = IntArray(n + 1)
            for (e in 0 until m) {
                start[eFrom[e] + 1]++
                start[eTo[e] + 1]++
            }
            for (i in 0 until n) start[i + 1] += start[i]
            val fill = start.copyOf(n)
            val adjTo = IntArray(start[n])
            val adjLen = DoubleArray(start[n])
            for (e in 0 until m) {
                val a = eFrom[e]
                val b = eTo[e]
                val d = Geo.distanceM(nLat[a], nLon[a], nLat[b], nLon[b])
                adjTo[fill[a]] = b; adjLen[fill[a]] = d; fill[a]++
                adjTo[fill[b]] = a; adjLen[fill[b]] = d; fill[b]++
            }

            // فهرس شبكي للقطع: كل قطعة تُسجَّل في كل خلية يمسها مستطيلها
            val tmp = HashMap<Long, IntBuf>()
            for (e in 0 until m) {
                val a = eFrom[e]
                val b = eTo[e]
                val r0 = floor(min(nLat[a], nLat[b]) / GRID_DEG).toInt()
                val r1 = floor(max(nLat[a], nLat[b]) / GRID_DEG).toInt()
                val c0 = floor(min(nLon[a], nLon[b]) / GRID_DEG).toInt()
                val c1 = floor(max(nLon[a], nLon[b]) / GRID_DEG).toInt()
                if ((r1 - r0 + 1).toLong() * (c1 - c0 + 1) > 400) continue // قطعة شاذة الطول
                for (r in r0..r1) for (c in c0..c1) {
                    tmp.getOrPut(key(r, c)) { IntBuf(4) }.add(e)
                }
            }
            val grid = HashMap<Long, IntArray>(tmp.size * 2)
            for ((k, v) in tmp) grid[k] = v.toArray()

            return RoadGraph(nLat, nLon, start, adjTo, adjLen, eFrom, eTo, grid)
        }
    }

    // ---------------------------------------------------------------- الإسقاط على أقرب قطعة

    private fun snap(lat: Double, lon: Double, maxM: Double): Snap? {
        if (segA.isEmpty()) return null
        val kx = cos(Math.toRadians(lat)) * M_PER_DEG
        val cellM = min(kx, KY) * GRID_DEG
        val r0 = floor(lat / GRID_DEG).toInt()
        val c0 = floor(lon / GRID_DEG).toInt()
        val maxRing = ceil(maxM / cellM).toInt() + 1

        var best: Snap? = null
        var bestD = Double.MAX_VALUE
        for (ring in 0..maxRing) {
            // ما لم نره بعد يقع في حلقات أبعد، فبعده لا يقل عن (ring-1) خلية
            if (best != null && bestD <= (ring - 1) * cellM) break
            for (dr in -ring..ring) {
                for (dc in -ring..ring) {
                    if (maxOf(abs(dr), abs(dc)) != ring) continue
                    val list = grid[key(r0 + dr, c0 + dc)] ?: continue
                    for (e in list) {
                        val a = segA[e]
                        val b = segB[e]
                        val ax = (nLon[a] - lon) * kx
                        val ay = (nLat[a] - lat) * KY
                        val bx = (nLon[b] - lon) * kx
                        val by = (nLat[b] - lat) * KY
                        val dx = bx - ax
                        val dy = by - ay
                        val l2 = dx * dx + dy * dy
                        val t = if (l2 > 0.0) (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                        val qx = ax + t * dx
                        val qy = ay + t * dy
                        val d = hypot(qx, qy)
                        if (d < bestD) {
                            bestD = d
                            val len = sqrt(l2)
                            best = Snap(e, lat + qy / KY, lon + qx / kx, d, t * len, (1 - t) * len)
                        }
                    }
                }
            }
        }
        val b = best ?: return null
        return if (b.distM <= maxM) b else null
    }

    /** المسافة بالمتر من النقطة إلى أقرب طريق، أو null إن لم يكن ضمن [maxM]. */
    fun distanceToNetworkM(lat: Double, lon: Double, maxM: Double = MAX_SNAP_M): Double? =
        snap(lat, lon, maxM)?.distM

    // ---------------------------------------------------------------- أقصر مسار

    /**
     * أقصر مسار مشيًا بين نقطتين، أو null إن كانت إحداهما أبعد من [maxSnapM] عن أي طريق
     * أو لا يوجد اتصال بين الطريقين (جزر منفصلة في البيانات).
     */
    fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        maxSnapM: Double = MAX_SNAP_M
    ): Route? {
        val s = snap(fromLat, fromLon, maxSnapM) ?: return null
        val e = snap(toLat, toLon, maxSnapM) ?: return null

        val pts = ArrayList<GeoPoint>()
        pts.add(GeoPoint(fromLat, fromLon))
        pts.add(GeoPoint(s.lat, s.lon))

        if (s.seg == e.seg) {
            // على القطعة المستقيمة نفسها: مباشرةً
            pts.add(GeoPoint(e.lat, e.lon))
        } else {
            val n = nLat.size
            val dist = DoubleArray(n) { Double.MAX_VALUE }
            val prev = IntArray(n) { -1 }
            val pq = PriorityQueue<Entry>(1024, Comparator<Entry> { x, y -> x.d.compareTo(y.d) })

            val sa = segA[s.seg]
            val sb = segB[s.seg]
            dist[sa] = s.lenA
            pq.add(Entry(sa, s.lenA))
            if (s.lenB < dist[sb]) {
                dist[sb] = s.lenB
                pq.add(Entry(sb, s.lenB))
            }

            val ea = segA[e.seg]
            val eb = segB[e.seg]
            var best = Double.MAX_VALUE
            var bestEnd = -1
            while (pq.isNotEmpty()) {
                val cur = pq.poll() ?: break
                val u = cur.node
                val d = cur.d
                if (d > dist[u]) continue
                if (d >= best) break
                if (u == ea && d + e.lenA < best) {
                    best = d + e.lenA
                    bestEnd = u
                }
                if (u == eb && d + e.lenB < best) {
                    best = d + e.lenB
                    bestEnd = u
                }
                for (k in adjStart[u] until adjStart[u + 1]) {
                    val v = adjTo[k]
                    val nd = d + adjLen[k]
                    if (nd < dist[v]) {
                        dist[v] = nd
                        prev[v] = u
                        pq.add(Entry(v, nd))
                    }
                }
            }
            if (bestEnd < 0) return null

            val order = ArrayList<Int>()
            var c = bestEnd
            while (c != -1) {
                order.add(c)
                c = prev[c]
            }
            order.reverse()
            for (id in order) pts.add(GeoPoint(nLat[id], nLon[id]))
            pts.add(GeoPoint(e.lat, e.lon))
        }
        pts.add(GeoPoint(toLat, toLon))

        var len = 0.0
        for (i in 1 until pts.size) {
            len += Geo.distanceM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        }
        return Route(pts, len)
    }
}

/** تعليمات الاتجاه من مسار ومكان العامل الحالي، نقية وقابلة للاختبار. */
object RouteGuide {

    private const val TURN_DEG = 35.0

    private fun local(points: List<GeoPoint>, lat: Double, lon: Double): Array<DoubleArray> {
        val kx = cos(Math.toRadians(lat)) * 111_195.0
        return Array(points.size) { i ->
            doubleArrayOf((points[i].lon - lon) * kx, (points[i].lat - lat) * 111_195.0)
        }
    }

    /** أقصر بعد بين الموقع والمسار بالمتر (لكشف الخروج عن المسار وإعادة الحساب). */
    fun offRouteM(points: List<GeoPoint>, lat: Double, lon: Double): Double {
        if (points.size < 2) return 0.0
        val q = local(points, lat, lon)
        var best = Double.MAX_VALUE
        for (i in 1 until q.size) best = min(best, distToSegment(q[i - 1], q[i]).first)
        return best
    }

    /** (البعد عن القطعة، موضع الإسقاط 0..1). الموقع هو نقطة الأصل. */
    private fun distToSegment(a: DoubleArray, b: DoubleArray): Pair<Double, Double> {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val l2 = dx * dx + dy * dy
        val t = if (l2 > 0.0) (-(a[0] * dx + a[1] * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        return Pair(hypot(a[0] + t * dx, a[1] + t * dy), t)
    }

    private fun bearing(a: DoubleArray, b: DoubleArray): Double =
        Math.toDegrees(atan2(b[0] - a[0], b[1] - a[1]))

    private fun normalize(d: Double): Double {
        var x = d
        while (x > 180) x -= 360
        while (x < -180) x += 360
        return x
    }

    private fun round5(v: Double): Int = (v / 5).roundToInt() * 5
    private fun round10(v: Double): Int = max(10, (v / 10).roundToInt() * 10)

    /** جملة قصيرة بالعربية: المنعطف القادم وبعده، أو «وصلت». */
    fun instruction(points: List<GeoPoint>, lat: Double, lon: Double): String {
        if (points.size < 2) return ""
        val q = local(points, lat, lon)
        var bi = 1
        var bd = Double.MAX_VALUE
        var bt = 0.0
        for (i in 1 until q.size) {
            val (d, t) = distToSegment(q[i - 1], q[i])
            if (d < bd) {
                bd = d
                bi = i
                bt = t
            }
        }
        fun seg(i: Int): Double = hypot(q[i][0] - q[i - 1][0], q[i][1] - q[i - 1][1])

        var dist = seg(bi) * (1 - bt)
        for (i in bi until q.size - 1) {
            val da = normalize(bearing(q[i], q[i + 1]) - bearing(q[i - 1], q[i]))
            if (abs(da) >= TURN_DEG) {
                val what = when {
                    da > 120 -> "استدر نحو اليمين"
                    da < -120 -> "استدر نحو اليسار"
                    da > 0 -> "انعطف يمينًا"
                    else -> "انعطف يسارًا"
                }
                return if (dist < 15) what else "بعد ${round5(dist)} م: $what"
            }
            dist += seg(i + 1)
        }
        return if (dist < 20) "وصلت" else "واصل مستقيمًا نحو ${round10(dist)} م"
    }

    fun remainingM(points: List<GeoPoint>, lat: Double, lon: Double): Double {
        if (points.size < 2) return 0.0
        val q = local(points, lat, lon)
        var bi = 1
        var bd = Double.MAX_VALUE
        var bt = 0.0
        for (i in 1 until q.size) {
            val (d, t) = distToSegment(q[i - 1], q[i])
            if (d < bd) {
                bd = d
                bi = i
                bt = t
            }
        }
        var rest = hypot(q[bi][0] - q[bi - 1][0], q[bi][1] - q[bi - 1][1]) * (1 - bt)
        for (i in bi + 1 until q.size) rest += hypot(q[i][0] - q[i - 1][0], q[i][1] - q[i - 1][1])
        return rest
    }
}

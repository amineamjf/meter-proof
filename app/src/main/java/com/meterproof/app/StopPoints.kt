package com.meterproof.app

import kotlin.math.max

/**
 * نقاط التوقف القصير (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * الفكرة: العامل يمشي ثم يتوقف عن المشي ثوانيَ قليلة (ليضع ورقة في باب مثلًا). عند «توقف عن المشي»
 * تُسجَّل نقطة GPS واحدة، من أفضل قراءة حديثة. شروطها:
 *  - سبقها مشي فعلي (مدة دنيا)، فلا نقطة لمن يقف أصلًا أو يتأرجح في مكانه.
 *  - إن وقعت داخل نصف قطر من نقطة معروفة (سُجّلت اليوم أو في جولات سابقة) فلا تُسجَّل ثانية،
 *    ويُسجَّل «مرور» بتلك النقطة بدل نقطة جديدة (وهذا هو التعرّف على الوصول إلى نقطة معروفة).
 *  - إن طال التوقف (قراءة متأنية) تُحسَّن النقطة الجديدة بمتوسط مرجَّح بالدقة لقراءات هذا التوقف،
 *    مرة واحدة، دون نقطة مكررة.
 *
 * الكتابة في السجل أحداث جديدة فقط (انظر [StopPointLog])، ولا يُعدَّل شيء سابق.
 */
class StopPointRecorder(
    private val cfg: Config = Config(),
    private val newId: () -> String = { java.util.UUID.randomUUID().toString().take(8) }
) {

    data class Config(
        /** نصف قطر اعتبار نقطتين نقطة واحدة (يُرفع تلقائيًا إلى دقة القراءة إن كانت أسوأ). */
        val dedupRadiusM: Double = 25.0,
        /** قراءات أسوأ من هذه الدقة تُهمل. */
        val maxAccuracyM: Float = 30f,
        /** أقدم قراءة تُقبل قبل لحظة التوقف. */
        val recentFixMs: Long = 4_000L,
        /** إن لم توجد قراءة حديثة ننتظر قراءة جديدة هذه المدة. */
        val waitFixMs: Long = 3_000L,
        /** أقل مدة مشي قبل التوقف كي يُعدّ توقفًا حقيقيًا. */
        val minWalkMs: Long = 4_000L,
        /** بعد كم من التوقف تُحسَّن النقطة بالمتوسط، وحتى متى تُجمع القراءات. */
        val refineAfterMs: Long = 6_000L,
        val refineUntilMs: Long = 10_000L,
        /** أقل فاصل بين تسجيلَي «مرور» لنقطة واحدة. */
        val visitCooldownMs: Long = 10 * 60_000L
    )

    data class Fix(val t: Long, val lat: Double, val lon: Double, val acc: Float)

    sealed class Action {
        data class NewPoint(val id: String, val fix: Fix) : Action()
        data class Refined(
            val id: String, val lat: Double, val lon: Double, val acc: Float,
            val samples: Int, val spreadM: Double
        ) : Action()
        data class Visit(val id: String, val t: Long, val distanceM: Double) : Action()
    }

    private class Current(val id: String, val isNew: Boolean, val stillSince: Long) {
        val samples = ArrayList<Fix>()
        var refined = false
    }

    private val known = ArrayList<StopPointLog.Known>()
    private val lastVisit = HashMap<String, Long>()
    private val recent = ArrayList<Fix>()

    private var walkSince: Long? = null
    private var pendingSince: Long? = null
    private var current: Current? = null

    /** نقاط معروفة من جولات سابقة (تُحمَّل عند بدء الخدمة). */
    fun load(points: List<StopPointLog.Known>) {
        known.clear()
        known.addAll(points)
    }

    /** النقاط المعروفة الآن (المحمَّلة والجديدة). */
    fun points(): List<StopPointLog.Known> = known.toList()

    /** نقطة زيرت من مسار آخر (الاقتراب): لا «مرور» مكرر منها خلال فترة الانتظار. */
    fun markVisited(id: String, t: Long) {
        lastVisit[id] = t
    }

    fun reset() {
        recent.clear()
        walkSince = null
        pendingSince = null
        current = null
    }

    /** moving=true بدأ المشي، false توقف عنه (من مقياس التسارع). */
    fun onMotion(t: Long, moving: Boolean): List<Action> {
        if (moving) {
            if (walkSince == null) walkSince = t
            pendingSince = null
            current = null
            return emptyList()
        }
        val w = walkSince ?: return emptyList() // لم يمشِ قبلها: لا شيء
        walkSince = null
        if (t - w < cfg.minWalkMs) return emptyList()
        pendingSince = t
        val best = recent
            .filter { it.t in (t - cfg.recentFixMs)..t }
            .minWithOrNull(compareBy<Fix> { it.acc }.thenByDescending { it.t })
        return if (best != null) resolve(best, t) else emptyList()
    }

    fun onFix(f: Fix): List<Action> {
        if (f.acc > cfg.maxAccuracyM) return emptyList()
        recent.add(f)
        while (recent.size > 30 || (recent.isNotEmpty() && f.t - recent.first().t > 20_000L)) recent.removeAt(0)

        val out = ArrayList<Action>()
        val p = pendingSince
        if (p != null) {
            if (f.t - p <= cfg.waitFixMs) out.addAll(resolve(f, p)) else pendingSince = null
        }
        val c = current
        if (c != null && c.isNew && !c.refined && f.t >= c.stillSince &&
            f.t - c.stillSince <= cfg.refineUntilMs
        ) {
            if (c.samples.none { it.t == f.t }) c.samples.add(f) // لا نكرر قراءة التسجيل نفسها
            if (f.t - c.stillSince >= cfg.refineAfterMs && c.samples.size >= 2) {
                c.refined = true
                out.add(refine(c))
            }
        }
        return out
    }

    private fun resolve(fix: Fix, stillAt: Long): List<Action> {
        pendingSince = null
        var nearest: StopPointLog.Known? = null
        var nearestD = Double.MAX_VALUE
        for (k in known) {
            val d = Geo.distanceM(k.lat, k.lon, fix.lat, fix.lon)
            if (d < nearestD) {
                nearest = k
                nearestD = d
            }
        }
        val radius = max(cfg.dedupRadiusM, fix.acc.toDouble())
        if (nearest != null && nearestD <= radius) {
            current = Current(nearest.id, false, stillAt)
            val last = lastVisit[nearest.id]
            if (last != null && fix.t - last < cfg.visitCooldownMs) return emptyList()
            lastVisit[nearest.id] = fix.t
            return listOf(Action.Visit(nearest.id, fix.t, nearestD))
        }
        val id = newId()
        known.add(StopPointLog.Known(id, fix.lat, fix.lon, fix.acc, 1))
        current = Current(id, true, stillAt).also { it.samples.add(fix) }
        return listOf(Action.NewPoint(id, fix))
    }

    private fun refine(c: Current): Action.Refined {
        var sw = 0.0
        var slat = 0.0
        var slon = 0.0
        for (s in c.samples) {
            val a = max(s.acc.toDouble(), 1.0)
            val w = 1.0 / (a * a)
            sw += w
            slat += w * s.lat
            slon += w * s.lon
        }
        val lat = slat / sw
        val lon = slon / sw
        val spread = c.samples.map { Geo.distanceM(lat, lon, it.lat, it.lon) }.average()
        val acc = (c.samples.minOf { it.acc } / Math.sqrt(c.samples.size.toDouble())).toFloat()
        val i = known.indexOfFirst { it.id == c.id }
        if (i >= 0) known[i] = StopPointLog.Known(c.id, lat, lon, acc, c.samples.size)
        return Action.Refined(c.id, lat, lon, acc, c.samples.size, spread)
    }
}

/** صيغة أحداث نقاط التوقف في السجل، وقراءة النقاط المعروفة منه. */
object StopPointLog {
    const val EVENT_POINT = "STOP_POINT"
    const val EVENT_REFINED = "STOP_POINT_REFINED"
    const val EVENT_VISIT = "STOP_POINT_VISIT"

    class Known(
        val id: String, val lat: Double, val lon: Double, val accuracyM: Float, val samples: Int,
        /** وقت أول تسجيل للنقطة (0 إن لم يُعرف). */
        val t: Long = 0L
    )

    fun pointDetail(id: String, acc: Float): String = "pt=$id;acc=${"%.0f".format(java.util.Locale.US, acc)}"

    fun refinedDetail(id: String, samples: Int, spreadM: Double, acc: Float): String =
        "pt=$id;n=$samples;sd=${"%.1f".format(java.util.Locale.US, spreadM)};acc=${"%.1f".format(java.util.Locale.US, acc)}"

    fun visitDetail(id: String, distanceM: Double): String =
        "pt=$id;d=${"%.0f".format(java.util.Locale.US, distanceM)}"

    private fun kv(detail: String): Map<String, String> =
        detail.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

    /** النقاط المعروفة من الأحداث (الأقدم أولًا): آخر تحسين لنقطة يحل محل قيمتها. */
    fun known(events: List<EventRec>): List<Known> {
        val out = LinkedHashMap<String, Known>()
        for (e in events) {
            val lat = e.lat ?: continue
            val lon = e.lon ?: continue
            val m = kv(e.detail)
            val id = m["pt"] ?: continue
            val acc = m["acc"]?.toFloatOrNull() ?: 0f
            when (e.type) {
                EVENT_POINT -> out[id] = Known(id, lat, lon, acc, 1, e.t)
                EVENT_REFINED -> if (out.containsKey(id)) {
                    out[id] = Known(id, lat, lon, acc, m["n"]?.toIntOrNull() ?: 1, out[id]?.t ?: e.t)
                }
            }
        }
        return out.values.toList()
    }

    /** نقاط التوقف القصير كنقاط للخارطة (closedBy = "point")، لا كتوقفات طويلة: لا تدخل إحصاءات اليوم. */
    fun asStops(events: List<EventRec>): List<Stop> =
        known(events).map { Stop(it.t, it.t, it.lat, it.lon, POINT_KIND) }

    /** توقفات طويلة + نقاط توقف قصير، لعرض الخارطة والتصدير فقط. */
    fun mapStops(events: List<EventRec>): List<Stop> = StopPairing.pair(events) + asStops(events)

    const val POINT_KIND = "point"

    /** عدد مرات المرور بكل نقطة معروفة. */
    fun visitCounts(events: List<EventRec>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (e in events) {
            if (e.type != EVENT_VISIT) continue
            val id = kv(e.detail)["pt"] ?: continue
            out[id] = (out[id] ?: 0) + 1
        }
        return out
    }
}

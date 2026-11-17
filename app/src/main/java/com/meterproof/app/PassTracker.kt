package com.meterproof.app

/**
 * التعرّف على «المرور» بنقطة معروفة (عداد مستورد أو نقطة توقف سابقة) دون الحاجة إلى التوقف عندها
 * (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * كل قراءة GPS تُقارَن بالأهداف القريبة. حين تدخل قراءة نصف قطر هدف نبدأ «مرشّحًا» ونتتبّع أقرب مسافة
 * بلغها العامل؛ ونُثبّت المرور عند أقرب اقتراب، حين يبتعد (مسافة أكبر من الأقرب بهامش) أو يخرج من النطاق
 * أو يطول مكوثه، فتُحفظ أفضل قراءة (وليس أول قراءة عند حافة النطاق). المرور بثانية أو ثانيتين يكفي
 * ما دامت قراءات GPS تصل كل ثانيتين تقريبًا.
 *
 * ambiguous: يوجد هدف آخر مسافته قريبة جدًا من مسافة الهدف (مبنى كثيف)، فلا نجزم بأيهما مرّ به.
 */
class PassTracker(private val cfg: Config = Config()) {

    data class Config(
        /** نثبّت المرور حين تزيد المسافة عن أقرب اقتراب بهذا الهامش (متر). */
        val leaveMarginM: Double = 8.0,
        /** أقصى مكوث داخل النطاق قبل التثبيت. */
        val maxLingerMs: Long = 30_000L,
        /** أقل فاصل بين مرورين بالهدف نفسه (لا يطبَّق على الأهداف الدائمة). */
        val cooldownMs: Long = 10 * 60_000L,
        /** هدفان فرق مسافتيهما أقل من هذا يُعدّان ملتبسين. */
        val ambiguousGapM: Double = 10.0
    )

    data class Target(val key: String, val lat: Double, val lon: Double, val radiusM: Double)
    data class Fix(val t: Long, val lat: Double, val lon: Double, val acc: Float)
    data class Pass(val key: String, val distanceM: Double, val fix: Fix, val ambiguous: Boolean)

    private class Cand(var minD: Double, var fix: Fix, val firstT: Long, var amb: Boolean)

    private val cands = HashMap<String, Cand>()
    private val last = HashMap<String, Long>()
    private val permanent = HashSet<String>()

    /** أهداف مرّ بها العامل سابقًا ولا يُسجَّل مرور ثانٍ بها (عدادات مرّ بها من قبل). */
    fun markPermanent(keys: Collection<String>) {
        permanent.addAll(keys)
    }

    /** يُعلَم أن هدفًا زير للتو (من مسار آخر)، فلا يُسجَّل مرور مكرر خلال فترة الانتظار. */
    fun markVisited(key: String, t: Long) {
        last[key] = t
        cands.remove(key)
    }

    fun reset() {
        cands.clear()
        last.clear()
    }

    private fun skip(key: String, t: Long): Boolean {
        if (key in permanent) return true
        val l = last[key] ?: return false
        return t - l < cfg.cooldownMs
    }

    fun onFix(fix: Fix, targets: List<Target>): List<Pass> {
        val inside = ArrayList<Pair<Target, Double>>()
        for (tg in targets) {
            if (skip(tg.key, fix.t)) continue
            val d = Geo.distanceM(fix.lat, fix.lon, tg.lat, tg.lon)
            if (d <= tg.radiusM) inside.add(tg to d)
        }
        val out = ArrayList<Pass>()
        val insideKeys = HashSet<String>()
        for ((tg, d) in inside) {
            insideKeys.add(tg.key)
            val amb = inside.any { it.first.key != tg.key && Math.abs(it.second - d) < cfg.ambiguousGapM }
            val c = cands[tg.key]
            if (c == null) {
                cands[tg.key] = Cand(d, fix, fix.t, amb)
            } else if (d < c.minD) {
                c.minD = d
                c.fix = fix
                c.amb = amb
            } else if (d >= c.minD + cfg.leaveMarginM || fix.t - c.firstT >= cfg.maxLingerMs) {
                commit(tg.key, out)
            }
        }
        for (key in cands.keys.toList()) if (key !in insideKeys) commit(key, out)
        return out
    }

    /** يثبّت كل المرشّحين الحاليين (نهاية الجلسة أو توقف التتبع). */
    fun flush(): List<Pass> {
        val out = ArrayList<Pass>()
        for (key in cands.keys.toList()) commit(key, out)
        return out
    }

    private fun commit(key: String, out: MutableList<Pass>) {
        val c = cands.remove(key) ?: return
        last[key] = c.fix.t
        out.add(Pass(key, c.minD, c.fix, c.amb))
    }
}

/** أحداث مرور العامل بعدادات الجولة بلا صورة، وقراءتها (أحداث جديدة في السجل المبصوم فقط). */
object MeterPassLog {
    const val EVENT = "METER_PASS"

    fun key(meterId: Long) = "m:$meterId"

    fun detail(meterId: Long, meterNo: String, distanceM: Double, ambiguous: Boolean): String =
        "m=$meterId;no=${meterNo.filter { it.isLetterOrDigit() || it == '-' || it == '_' }};" +
            "d=${"%.0f".format(java.util.Locale.US, distanceM)};amb=${if (ambiguous) 1 else 0}"

    class Rec(val meterId: Long, val meterNo: String, val t: Long, val ambiguous: Boolean)

    private fun kv(detail: String): Map<String, String> =
        detail.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

    /** آخر مرور مسجَّل لكل عداد (معرّف العداد ← السجل). */
    fun passes(events: List<EventRec>): Map<Long, Rec> {
        val out = LinkedHashMap<Long, Rec>()
        for (e in events) {
            if (e.type != EVENT) continue
            val m = kv(e.detail)
            val id = m["m"]?.toLongOrNull() ?: continue
            out[id] = Rec(id, m["no"] ?: "", e.t, m["amb"] == "1")
        }
        return out
    }

    /**
     * يعلّم على الخارطة العدادات التي مرّ بها العامل بلا صورة (لم تُسلَّم ولم يُوثَّق وصولها بصورة)
     * بحالة «وصل» ووقت المرور، فلا تبدو كأنها لم تُزَر. لا يمسّ العدادات الموثّقة أصلًا.
     */
    fun overlay(markers: List<MeterPoint>, events: List<EventRec>): List<MeterPoint> {
        val p = passes(events)
        if (p.isEmpty()) return markers
        return markers.map { m ->
            val r = p[m.id]
            if (r == null || m.state != MeterMarkState.PENDING) m
            else m.copy(
                state = MeterMarkState.ARRIVED,
                visitedAt = r.t,
                detail = m.detail.replace(
                    "لم يُسجَّل التسليم بعد",
                    "مرّ به العامل بلا صورة" + (if (r.ambiguous) " (التباس بعداد قريب)" else "") +
                        "، التسليم غير مؤكَّد"
                )
            )
        }
    }
}

package com.meterproof.app

import kotlin.math.max
import kotlin.math.min

object Days {
    /** حدود يوم محلي: offsetDays = 0 اليوم، -1 أمس. يعيد (بداية اليوم، بداية اليوم التالي). */
    fun range(offsetDays: Int, nowMs: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return start to cal.timeInMillis
    }
}

/** يطابق أحداث بداية ونهاية التوقف (منطق نقي قابل للاختبار). */
object StopPairing {

    private fun detailLong(detail: String, key: String): Long? =
        detail.split(';')
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.toLongOrNull()

    fun pair(events: List<EventRec>): List<Stop> {
        val out = ArrayList<Stop>()
        var openStart: Long? = null
        var openLat = 0.0
        var openLon = 0.0

        for (e in events) {
            when (e.type) {
                "STOP_START" -> {
                    val os = openStart
                    if (os != null) out.add(Stop(os, e.t, openLat, openLon, "interrupted"))
                    openStart = detailLong(e.detail, "since") ?: e.t
                    openLat = e.lat ?: 0.0
                    openLon = e.lon ?: 0.0
                }
                "STOP_END" -> {
                    val os = openStart
                    if (os != null) {
                        out.add(Stop(os, max(os, e.t), openLat, openLon, "end"))
                        openStart = null
                    }
                }
                "SESSION_START", "SESSION_STOP" -> {
                    val os = openStart
                    if (os != null) {
                        out.add(Stop(os, max(os, e.t), openLat, openLon, "interrupted"))
                        openStart = null
                    }
                }
            }
        }
        val os = openStart
        if (os != null) out.add(Stop(os, null, openLat, openLon, "open"))
        return out
    }
}

/** ملخص يوم عمل واحد. */
data class DaySummary(
    val dayStartMs: Long,
    val dayEndMs: Long,
    val firstMs: Long?,
    val lastMs: Long?,
    val distanceM: Double,
    val photos: Int,
    val stops: List<Stop>,
    val stopTotalMs: Long,
    val tamperCount: Int,
    val sessions: Int
) {
    val workSpanMs: Long get() = if (firstMs != null && lastMs != null) max(0L, lastMs - firstMs) else 0L
}

object DaySummaryBuilder {

    /** قفزة GPS غير معقولة (أكثر من 40 م/ث) لا تُحتسب في المسافة. */
    private const val MAX_SPEED_MPS = 40.0

    /** فجوة أطول من هذا بين نقطتين لا نخمّن المسافة خلالها. */
    private const val MAX_GAP_MS = 30 * 60_000L

    fun build(
        dayStartMs: Long,
        dayEndMs: Long,
        nowMs: Long,
        track: List<TimedPoint>,
        events: List<EventRec>,
        photoTimes: List<Long>
    ): DaySummary {
        val dayTrack = track.filter { it.t in dayStartMs until dayEndMs }.sortedBy { it.t }

        var dist = 0.0
        for (i in 1 until dayTrack.size) {
            val a = dayTrack[i - 1]
            val b = dayTrack[i]
            val dtMs = b.t - a.t
            if (dtMs <= 0 || dtMs > MAX_GAP_MS) continue
            val d = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
            if (d / (dtMs / 1000.0) <= MAX_SPEED_MPS) dist += d
        }

        val stops = StopPairing.pair(events).filter { it.startMs in dayStartMs until dayEndMs }
        val cap = min(nowMs, dayEndMs)
        var stopTotal = 0L
        for (s in stops) {
            val end = s.endMs ?: cap
            stopTotal += max(0L, end - s.startMs)
        }

        val dayEvents = events.filter { it.t in dayStartMs until dayEndMs }
        val dayPhotos = photoTimes.filter { it in dayStartMs until dayEndMs }

        val all = ArrayList<Long>()
        dayTrack.forEach { all.add(it.t) }
        dayEvents.forEach { all.add(it.t) }
        all.addAll(dayPhotos)

        return DaySummary(
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
            firstMs = all.minOrNull(),
            lastMs = all.maxOrNull(),
            distanceM = dist,
            photos = dayPhotos.size,
            stops = stops,
            stopTotalMs = stopTotal,
            tamperCount = dayEvents.count { it.type == "TAMPER" },
            sessions = dayEvents.count { it.type == "SESSION_START" }
        )
    }
}

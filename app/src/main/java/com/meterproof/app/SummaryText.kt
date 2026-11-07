package com.meterproof.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** نصوص ملخص اليوم (مشتركة بين الشاشة وتقرير PDF). منطق نقي قابل للاختبار. */
object SummaryText {

    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "$h س $m د"
            m > 0 -> "$m د $s ث"
            else -> "$s ث"
        }
    }

    fun distance(m: Double): String =
        if (m >= 1000) String.format(Locale.US, "%.2f كم", m / 1000.0) else "${m.toInt()} م"

    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    /** أسطر الإحصاءات (بدون قائمة التوقفات). */
    fun statLines(s: DaySummary): List<String> {
        val out = ArrayList<String>()
        val first = s.firstMs
        val last = s.lastMs
        if (first == null || last == null) {
            out.add("لا يوجد نشاط مسجّل في هذا اليوم")
        } else {
            out.add("بداية النشاط: ${clock(first)}   آخر نشاط: ${clock(last)}")
            out.add("المدة بين الأول والآخر: ${duration(s.workSpanMs)}")
        }
        out.add("المسافة (تقريبية): ${distance(s.distanceM)}")
        out.add("عدد الصور: ${s.photos}")
        out.add("عدد التوقفات الطويلة: ${s.stops.size}   (مجموعها: ${duration(s.stopTotalMs)})")
        if (s.sessions > 0) out.add("مرات بدء الجلسة: ${s.sessions}")
        if (s.tamperCount > 0) out.add("⚠ تنبيهات تلاعب مسجّلة: ${s.tamperCount}")
        return out
    }

    /** أسطر التوقفات، سطر لكل توقف. */
    fun stopLines(s: DaySummary, nowMs: Long): List<String> {
        val out = ArrayList<String>()
        for ((i, st) in s.stops.withIndex()) {
            val n = i + 1
            val end = st.endMs
            val line = when {
                end == null ->
                    "$n) ${clock(st.startMs)} ← جارٍ (${duration(nowMs - st.startMs)})"
                st.closedBy == "interrupted" ->
                    "$n) ${clock(st.startMs)} ← ${clock(end)} (${duration(end - st.startMs)}، انقطعت الخدمة فالنهاية تقريبية)"
                else ->
                    "$n) ${clock(st.startMs)} ← ${clock(end)} (${duration(end - st.startMs)})"
            }
            out.add(line)
        }
        return out
    }
}

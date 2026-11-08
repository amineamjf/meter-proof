package com.meterproof.app

import java.util.Calendar
import java.util.TimeZone

/**
 * منطق ساعات الدوام، نقيّ (بلا أندرويد) كي يُختبر على JVM.
 *
 * الجلسة تنتهي تلقائيًا عند نهاية الدوام، لكن فقط بعد أن عملت فيه فعلًا. فمن يبدأ الجلسة قبل
 * بداية الدوام تنتظر حتى يبدأ، ولا تُغلق لمجرد أن الساعة الحالية خارج الدوام.
 */
object WorkHours {

    /** بداية الدوام start (شاملة) ونهايته end (غير شاملة)، ويدعم الدوام الليلي (start > end). */
    fun inHours(hour: Int, start: Int, end: Int): Boolean =
        if (start <= end) hour in start until end else (hour >= start || hour < end)

    /** تُغلق الجلسة حين انتهى الدوام بعد أن عملت فيه. */
    fun shouldEnd(workedThisSession: Boolean, inHoursNow: Boolean): Boolean =
        workedThisSession && !inHoursNow

    /**
     * أقرب لحظة نهاية دوام بعد nowMs (بالساعة المدنية، فلا يفسدها تغيير التوقيت الصيفي).
     * endHour = 24 تعني منتصف الليل التالي. إن وافقت اللحظة nowMs بالضبط تُحسب نهاية اليوم التالي.
     */
    fun nextEndMillis(nowMs: Long, endHour: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = nowMs
        c.set(Calendar.HOUR_OF_DAY, endHour.coerceIn(0, 24))
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= nowMs) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }
}

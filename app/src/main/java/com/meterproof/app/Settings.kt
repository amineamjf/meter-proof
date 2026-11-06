package com.meterproof.app

import android.content.Context

/**
 * إعدادات الجلسة. في المرحلة 2 ستأتي هذه القيم من لوحة المدير عبر الخادم
 * وتُحفظ محليًا لتبقى سارية دون إنترنت.
 */
class Settings(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("meterproof", Context.MODE_PRIVATE)

    /** الحد الأدنى للفاصل بين صورتين (بالدقائق) */
    var minMinutes: Int
        get() = sp.getInt("min", 2)
        set(v) = sp.edit().putInt("min", v).apply()

    /** الحد الأقصى للفاصل بين صورتين (بالدقائق) */
    var maxMinutes: Int
        get() = sp.getInt("max", 30)
        set(v) = sp.edit().putInt("max", v).apply()

    /** بداية الدوام (ساعة من 0 إلى 23، شاملة) */
    var startHour: Int
        get() = sp.getInt("start", 7)
        set(v) = sp.edit().putInt("start", v).apply()

    /** نهاية الدوام (ساعة من 0 إلى 24، غير شاملة) */
    var endHour: Int
        get() = sp.getInt("end", 18)
        set(v) = sp.edit().putInt("end", v).apply()

    /** هل الجلسة مفعّلة (الخدمة يجب أن تكون حية) */
    var active: Boolean
        get() = sp.getBoolean("active", false)
        set(v) = sp.edit().putBoolean("active", v).apply()

    /** إيقاف مؤقت (استراحة/صلاة): لا تصوير ما دام مفعّلًا */
    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) = sp.edit().putBoolean("paused", v).apply()

    /** موعد الصورة التالية (للعرض فقط) */
    var nextCaptureAt: Long
        get() = sp.getLong("next", 0L)
        set(v) = sp.edit().putLong("next", v).apply()

    /** عدد الصور التي أُتلفت لأن التشويه فشل (الأمان افتراضيًا) */
    var discardedCount: Int
        get() = sp.getInt("discarded", 0)
        set(v) = sp.edit().putInt("discarded", v).apply()

    /** آخر خطأ في التصوير (للتشخيص أثناء التجربة) */
    var lastError: String
        get() = sp.getString("lastError", "") ?: ""
        set(v) = sp.edit().putString("lastError", v).apply()
}

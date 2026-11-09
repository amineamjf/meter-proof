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

    /** بعد كم ثانية من الثبات في المكان يُعدّ ذلك توقفًا طويلًا */
    var stopAfterSeconds: Int
        get() = sp.getInt("stopAfter", 30)
        set(v) = sp.edit().putInt("stopAfter", v).apply()

    /** التقاط صورة تلقائيًا عند بداية كل توقف طويل (مغلق افتراضيًا) */
    var photoOnStop: Boolean
        get() = sp.getBoolean("photoOnStop", false)
        set(v) = sp.edit().putBoolean("photoOnStop", v).apply()

    /** الحي المختار في شاشة الجولة (فارغ = كل الأحياء) */
    var roundQuartier: String
        get() = sp.getString("roundQuartier", "") ?: ""
        set(v) = sp.edit().putString("roundQuartier", v).apply()

    /** إظهار العدادات المنجزة في قائمة الجولة */
    var roundShowDone: Boolean
        get() = sp.getBoolean("roundShowDone", false)
        set(v) = sp.edit().putBoolean("roundShowDone", v).apply()

    /** حالة التتبع الحالية للعرض: moving / stopped / idle (خارج الدوام أو إيقاف مؤقت) */
    var motionState: String
        get() = sp.getString("motionState", "idle") ?: "idle"
        set(v) = sp.edit().putString("motionState", v).apply()

    /** منذ متى التوقف الحالي (للعرض) */
    var stopSince: Long
        get() = sp.getLong("stopSince", 0L)
        set(v) = sp.edit().putLong("stopSince", v).apply()

    /** آخر خطأ في التصوير (للتشخيص أثناء التجربة) */
    var lastError: String
        get() = sp.getString("lastError", "") ?: ""
        set(v) = sp.edit().putString("lastError", v).apply()

    // ---- خارطة البلاطات دون إنترنت (تُستعمل في شاشة التنزيل فقط)

    /** رابط البلاطات، مثل https://مزود/{z}/{x}/{y}.png?key=... (فارغ = لم يُضبط) */
    var tileUrl: String
        get() = sp.getString("tileUrl", "") ?: ""
        set(v) = sp.edit().putString("tileUrl", v.trim()).apply()

    /** نسب البيانات التي تُرسم على الخارطة */
    var tileAttribution: String
        get() = sp.getString("tileAttribution", "© OpenStreetMap contributors") ?: "© OpenStreetMap contributors"
        set(v) = sp.edit().putString("tileAttribution", v.trim()).apply()

    /** نصف قطر منطقة التنزيل بالكيلومتر */
    var tileRadiusKm: Int
        get() = sp.getInt("tileRadiusKm", 3)
        set(v) = sp.edit().putInt("tileRadiusKm", v).apply()

    var tileZoomMin: Int
        get() = sp.getInt("tileZoomMin", 13)
        set(v) = sp.edit().putInt("tileZoomMin", v).apply()

    var tileZoomMax: Int
        get() = sp.getInt("tileZoomMax", 18)
        set(v) = sp.edit().putInt("tileZoomMax", v).apply()
}

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

    /** رابط بلاطات مخصص (https وفيه {z} و{x} و{y}). فارغ = الطبقات الافتراضية (مخطط أو قمر صناعي) */
    var tileUrl: String
        get() = sp.getString("tileUrl", "") ?: ""
        set(v) = sp.edit().putString("tileUrl", v.trim()).apply()

    /** طبقة القمر الصناعي بدل المخطط (تُستعمل حين لا يوجد رابط مخصص) */
    var tileSat: Boolean
        get() = sp.getBoolean("tileSat", false)
        set(v) = sp.edit().putBoolean("tileSat", v).apply()

    /** هل عملت الجلسة الحالية داخل ساعات الدوام (يُحفظ كي يصمد عبر إعادة تشغيل الخدمة) */
    var workedThisSession: Boolean
        get() = sp.getBoolean("workedSession", false)
        set(v) = sp.edit().putBoolean("workedSession", v).apply()

    /** سبب آخر إيقاف للجلسة للعرض: فارغ = يدوي، AUTO_END_OF_HOURS = عند نهاية الدوام */
    var lastStopReason: String
        get() = sp.getString("lastStopReason", "") ?: ""
        set(v) = sp.edit().putString("lastStopReason", v).apply()

    var lastStopAt: Long
        get() = sp.getLong("lastStopAt", 0L)
        set(v) = sp.edit().putLong("lastStopAt", v).apply()

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

    /**
     * وضع الخصوصية للصور: FACES (الافتراضي) = طمس الوجوه فقط وباقي الصورة واضح،
     * FULL = بوابة الخصوصية الكاملة (تجريد المشهد وإتلاف الصورة عند ظهور أشخاص).
     */
    var privacyMode: String
        get() = sp.getString("privacyMode", "FACES") ?: "FACES"
        set(v) = sp.edit().putString("privacyMode", v).apply()

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

    /** كشف تسليم الفواتير تلقائيًا (مغلق افتراضيًا؛ لا يعمل أيضًا دون نموذج معايَر) */
    var deliveryDetection: Boolean
        get() = sp.getBoolean("deliveryDetection", false)
        set(v) = sp.edit().putBoolean("deliveryDetection", v).apply()

    /** إخفاء محتوى الفاتورة وإبقاء إطارها فقط (مغلق افتراضيًا، فالمحتوى ظاهر بقرار صاحب المشروع) */
    var deliveryOutlineOnly: Boolean
        get() = sp.getBoolean("deliveryOutlineOnly", false)
        set(v) = sp.edit().putBoolean("deliveryOutlineOnly", v).apply()

    /** نتيجة آخر التقاط عينة معايرة للعرض: رمز;وقت (مثل SAVED_POS;1731000000000) */
    var calibResult: String
        get() = sp.getString("calibResult", "") ?: ""
        set(v) = sp.edit().putString("calibResult", v).apply()
}

package com.meterproof.app

/** نقطة موقع (مسار GPS). */
data class GeoPoint(val lat: Double, val lon: Double)

/** نقطة مسار مع وقتها (بالميلي ثانية). */
data class TimedPoint(val t: Long, val lat: Double, val lon: Double)

/** حدث من سجل الأحداث (قراءة فقط): STOP_START وSTOP_END وTAMPER وSESSION_START وSESSION_STOP. */
data class EventRec(
    val type: String,
    val t: Long,
    val lat: Double?,
    val lon: Double?,
    val detail: String
)

/**
 * توقف طويل. endMs = null يعني أنه ما زال مفتوحًا.
 * closedBy: "end" أُغلق بحدث نهاية، "interrupted" أُغلق بحدث آخر (انقطاع الخدمة مثلًا) فنهايته تقريبية،
 * "open" ما زال جاريًا.
 */
data class Stop(
    val startMs: Long,
    val endMs: Long?,
    val lat: Double,
    val lon: Double,
    val closedBy: String
)

/** موقع صورة ملتقطة. index هو ترتيبها (يبدأ من 0) كما في معاينة التطبيق. */
data class PhotoPoint(
    val index: Int,
    val id: String,
    val timeMillis: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?
)

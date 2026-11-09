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

/** حالة عداد الجولة على الخارطة. */
enum class MeterMarkState { PENDING, ARRIVED, DONE, FLAGGED }

/**
 * عداد من الجولة له إحداثية، لرسمه على الخارطة.
 * photoId: معرّف سجل الصورة المرتبطة به (التسليم أو الوصول) إن وُجدت.
 * visitedAt: وقت آخر وصول أو تصوير (0 إن لم يُزَر)، لتصفية تقرير يوم معين.
 */
data class MeterPoint(
    val id: Long,
    val meterNo: String,
    val title: String,
    val detail: String,
    val lat: Double,
    val lon: Double,
    val state: MeterMarkState,
    val photoId: String?,
    val visitedAt: Long
)

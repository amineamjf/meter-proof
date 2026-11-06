package com.meterproof.app

/** نقطة موقع (مسار GPS). */
data class GeoPoint(val lat: Double, val lon: Double)

/** موقع صورة ملتقطة. index هو ترتيبها (يبدأ من 0) كما في معاينة التطبيق. */
data class PhotoPoint(
    val index: Int,
    val id: String,
    val timeMillis: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?
)

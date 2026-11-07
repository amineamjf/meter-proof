package com.meterproof.app

/** عداد في الجولة كما هو مخزن على الهاتف. */
data class MeterItem(
    val id: Long,
    val meterNo: String,
    val contract: String,
    val address: String,
    val quartier: String,
    val lat: Double?,
    val lon: Double?,
    val oldIndex: Long?,
    val learned: Boolean,
    val doneAt: Long,
    val recordId: String?,
    val flags: String
) {
    val done: Boolean get() = doneAt > 0
    val hasGps: Boolean get() = lat != null && lon != null
}

/** عداد مع مسافته عن العامل (null إن لم نعرف إحداثيته أو موقع العامل). */
data class RankedMeter(val item: MeterItem, val distanceM: Double?)

object RoundLogic {

    /** نطاق اعتبار الصورة قريبة من العداد: 50 مترًا على الأقل، أو أكثر عند ضعف دقة GPS. */
    fun nearLimitM(accuracyM: Float): Double = maxOf(50.0, accuracyM * 1.5)

    /**
     * الأقرب أولًا. العدادات بلا إحداثية تأتي بعد ذلك مرتبة بالعنوان.
     * إن لم نعرف موقع العامل تُرتَّب كلها بالعنوان.
     */
    fun rank(items: List<MeterItem>, lat: Double?, lon: Double?, limit: Int): List<RankedMeter> {
        val withDist = ArrayList<RankedMeter>(items.size)
        val without = ArrayList<RankedMeter>()
        for (m in items) {
            if (lat != null && lon != null && m.lat != null && m.lon != null) {
                withDist.add(RankedMeter(m, Geo.distanceM(lat, lon, m.lat, m.lon)))
            } else {
                without.add(RankedMeter(m, null))
            }
        }
        withDist.sortBy { it.distanceM }
        without.sortBy { it.item.address }
        return (withDist + without).take(limit)
    }
}

package com.meterproof.app

/**
 * مفتاح واحد لإظهار بيانات الزبون (الاسم) في نسخة التجربة.
 * اجعله false عندما ينضج التطبيق: لا يُقرأ الاسم من الملف ولا يُعرض في البطاقات.
 */
object CustomerInfo {
    const val SHOW = true
}

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
    val customerName: String,
    val newIndex: Long?,
    val learned: Boolean,
    val doneAt: Long,
    val recordId: String?,
    val flags: String,
    // وصول موثّق بصورة عند التوقف قرب إحداثية العداد. لا يعني أن الفاتورة سُلِّمت.
    val arrivalAt: Long = 0L,
    val arrivalRecord: String? = null,
    val arrivalFlags: String = ""
) {
    val done: Boolean get() = doneAt > 0
    val arrived: Boolean get() = arrivalAt > 0
    val hasGps: Boolean get() = lat != null && lon != null
}

/** نتيجة اختيار عداد الوصول: الأقرب، وهل يوجد عداد آخر قريب منه بما يسبب التباسًا. */
data class Arrival(val meter: MeterItem, val distanceM: Double, val ambiguous: Boolean)

/** عداد مع مسافته عن العامل (null إن لم نعرف إحداثيته أو موقع العامل). */
data class RankedMeter(val item: MeterItem, val distanceM: Double?)

object RoundLogic {

    /** تحويل عداد إلى نقطة خارطة (null إن لم تكن له إحداثية). نقية وقابلة للاختبار. */
    fun marker(m: MeterItem): MeterPoint? {
        val lat = m.lat ?: return null
        val lon = m.lon ?: return null
        val far = m.flags.contains("FAR_FROM_METER")
        val state = when {
            m.done && far -> MeterMarkState.FLAGGED
            m.done -> MeterMarkState.DONE
            m.arrived -> MeterMarkState.ARRIVED
            else -> MeterMarkState.PENDING
        }
        val stateText = when (state) {
            MeterMarkState.FLAGGED -> "صُوِّر لكن الموقع بعيد عن الإحداثية"
            MeterMarkState.DONE -> "صُوِّر"
            MeterMarkState.ARRIVED -> "وُثّق الوصول، التسليم غير مؤكَّد بعد"
            MeterMarkState.PENDING -> "لم يُسجَّل التسليم بعد"
        }
        val sb = StringBuilder()
        if (CustomerInfo.SHOW && m.customerName.isNotEmpty()) sb.append(m.customerName).append("\n")
        if (m.address.isNotEmpty()) sb.append(m.address).append("\n")
        if (m.quartier.isNotEmpty()) sb.append("الحي: ").append(m.quartier).append("\n")
        sb.append(stateText)
        return MeterPoint(
            id = m.id,
            meterNo = m.meterNo,
            title = "العداد " + m.meterNo,
            detail = sb.toString(),
            lat = lat,
            lon = lon,
            state = state,
            photoId = m.recordId ?: m.arrivalRecord,
            visitedAt = maxOf(m.doneAt, m.arrivalAt)
        )
    }

    /** نطاق الوصول: 25 مترًا، ويتسع حتى 45 مترًا عند ضعف دقة GPS. */
    fun arrivalRadiusM(accuracyM: Float): Double = maxOf(25.0, minOf(accuracyM * 1.5, 45.0))

    /**
     * أقرب عداد بإحداثية لم يُسلَّم ولم يُوثَّق وصوله، ضمن نطاق الوصول. null إن لم يوجد.
     * ambiguous: يوجد عداد آخر فرق مسافته عن الأول أقل من 10 أمتار (مبنى كثيف).
     */
    fun pickArrival(items: List<MeterItem>, lat: Double, lon: Double, accuracyM: Float): Arrival? {
        val radius = arrivalRadiusM(accuracyM)
        val near = items
            .filter { it.hasGps && !it.done && !it.arrived }
            .map { it to Geo.distanceM(lat, lon, it.lat!!, it.lon!!) }
            .filter { it.second <= radius }
            .sortedBy { it.second }
        if (near.isEmpty()) return null
        val ambiguous = near.size > 1 && near[1].second - near[0].second < 10.0
        return Arrival(near[0].first, near[0].second, ambiguous)
    }

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

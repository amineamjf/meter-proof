package com.meterproof.app.delivery

import com.meterproof.app.EventRec
import com.meterproof.app.PhotoLabels

/**
 * طابور مراجعة ما قرّره الكاشف في الميدان (منطق نقي بلا أندرويد).
 *
 * يُبنى من أحداث DELIVERY_DETECTED وDELIVERY_UNCERTAIN المسجَّلة أصلًا (فيها الدرجة ومعرّف السجل)،
 * فلا يُعاد تحليل أي صورة. يستبعد ما صُنّف مسبقًا. الأقرب إلى حدود الشك أولًا لأن تصنيفه أنفع للتعلم،
 * ثم غير المؤكد، ثم المقبول بدرجة عالية. تصنيف الصورة من المراجعة يتم بالأزرار الحالية في المعرض.
 */
object ReviewQueue {

    class Item(
        val recordId: String, val score: Int, val detected: Boolean, val t: Long,
        val lat: Double? = null, val lon: Double? = null
    )

    fun build(events: List<EventRec>, labels: Map<String, PhotoLabels.Label>): List<Item> {
        val byId = LinkedHashMap<String, Item>()
        for (e in events) {
            val detected = when (e.type) {
                DeliveryEventLog.EVENT_DETECTED -> true
                DeliveryEventLog.EVENT_UNCERTAIN -> false
                else -> continue
            }
            val kv = DeliveryEventLog.parse(e.detail)
            val id = kv["rec"] ?: continue
            if (id in labels) continue
            val score = kv["score"]?.toIntOrNull()?.coerceIn(0, 100) ?: continue
            byId[id] = Item(id, score, detected, e.t, e.lat, e.lon) // آخر حدث للسجل
        }
        // غير المؤكد أولًا (الأقرب للمنتصف)، ثم المقبول الأقل درجة، والأحدث عند التعادل
        val ranked = byId.values.sortedWith(
            compareBy<Item> { it.detected }.thenBy { if (it.detected) it.score else -it.score }.thenByDescending { it.t }
        )
        return diversify(ranked)
    }

    /**
     * تنويع: صور المكان نفسه في الدقائق نفسها (وقفة واحدة بعدة لقطات) متشابهة، فتصنيف إحداها يكفي غالبًا.
     * الدور الأول يأخذ أول عنصر من كل مجموعة (مكان ~30م × نافذة 10 دقائق) بترتيب الأولوية، ثم الباقي.
     */
    fun diversify(ranked: List<Item>): List<Item> {
        fun key(i: Item): String =
            if (i.lat == null || i.lon == null) "x${i.recordId}"
            else "${Math.round(i.lat / 0.0003)}:${Math.round(i.lon / 0.0003)}:${i.t / 600_000L}"
        val seen = HashSet<String>()
        val first = ArrayList<Item>()
        val rest = ArrayList<Item>()
        for (i in ranked) if (seen.add(key(i))) first.add(i) else rest.add(i)
        return first + rest
    }
}

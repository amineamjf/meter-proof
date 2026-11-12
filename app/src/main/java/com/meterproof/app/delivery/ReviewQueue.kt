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

    class Item(val recordId: String, val score: Int, val detected: Boolean, val t: Long)

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
            byId[id] = Item(id, score, detected, e.t) // آخر حدث للسجل
        }
        // غير المؤكد أولًا (الأقرب للمنتصف)، ثم المقبول الأقل درجة، والأحدث عند التعادل
        return byId.values.sortedWith(
            compareBy<Item> { it.detected }.thenBy { if (it.detected) it.score else -it.score }.thenByDescending { it.t }
        )
    }
}

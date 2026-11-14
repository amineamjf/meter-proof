package com.meterproof.app

/**
 * تصنيف الصور للتدريب (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * التصنيف يُكتب كحدث جديد PHOTO_LABEL في events.jsonl، ولا يُعدَّل سجل الصورة ولا الصورة نفسها،
 * فتبقى بصمات الصور القديمة سليمة. آخر حدث لصورة معيّنة هو الساري، ومسح التصنيف حدث بدوره (CLEARED).
 * detail رموز ثابتة فقط (معرّف السجل والتصنيف)، بلا نص حر.
 */
object PhotoLabels {

    const val EVENT = "PHOTO_LABEL"

    enum class Label(val code: String, val arabic: String) {
        INVOICE("INVOICE", "فاتورة موضوعة"),
        NONE("NONE", "بلا فاتورة"),
        UNCLEAR("UNCLEAR", "غير واضح"),
        /** ورقة تشبه الفاتورة وليست فاتورة (إعلان، إيصال، ورقة على الباب): أصعب سلبية، تُستعمل كمثال سلبي. */
        SIMILAR("SIMILAR", "ورقة مشابهة");

        companion object {
            fun fromCode(code: String): Label? = values().firstOrNull { it.code == code }
        }
    }

    private const val CLEARED = "CLEARED"
    private val ID_PATTERN = Regex("^[0-9a-fA-F-]{8,40}$")

    /** تفاصيل حدث التصنيف، أو null إن كان المعرّف غير صالح (فلا يُكتب شيء). label=null يعني مسح التصنيف. */
    fun detail(recordId: String, label: Label?): String? {
        if (!ID_PATTERN.matches(recordId)) return null
        return "rec=$recordId;label=${label?.code ?: CLEARED}"
    }

    /** آخر تصنيف ساري لكل صورة، بالترتيب الزمني للأحداث الممرَّرة (الأقدم أولًا). */
    fun latest(events: List<EventRec>): Map<String, Label> {
        val out = LinkedHashMap<String, Label>()
        for (e in events) {
            if (e.type != EVENT) continue
            val kv = parse(e.detail)
            val id = kv["rec"] ?: continue
            when (val code = kv["label"] ?: continue) {
                CLEARED -> out.remove(id)
                else -> Label.fromCode(code)?.let { out[id] = it }
            }
        }
        return out
    }

    /** عدد العينات لكل تصنيف (ما فيه ما يكفي للتدريب؟). */
    data class Counts(val invoice: Int, val none: Int, val unclear: Int, val similar: Int = 0) {
        val total get() = invoice + none + unclear + similar
    }

    fun counts(labels: Map<String, Label>): Counts = Counts(
        invoice = labels.values.count { it == Label.INVOICE },
        none = labels.values.count { it == Label.NONE },
        unclear = labels.values.count { it == Label.UNCLEAR },
        similar = labels.values.count { it == Label.SIMILAR }
    )

    /** أقل عدد من كل نوع (فاتورة / بلا فاتورة) قبل أن يصلح التدريب؛ يطابق شاشة المعايرة الحالية. */
    const val MIN_PER_CLASS = 15

    fun readyForTraining(c: Counts): Boolean = c.invoice >= MIN_PER_CLASS && c.none >= MIN_PER_CLASS

    /**
     * هل تصلح الصورة للتدريب؟ الصور المجرّدة (PRIVACY_T2) لا تصلح لأن المحتوى مخفي.
     * الصور الواضحة (وضع الوجوه، أو سجلات قديمة بلا علامة) تصلح.
     */
    fun usableForTraining(flags: String): Boolean {
        val set = flags.split(',', ';', ' ', '|').filter { it.isNotBlank() }
        return "PRIVACY_T2" !in set
    }

    private fun parse(detail: String): Map<String, String> =
        detail.split(';')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
}

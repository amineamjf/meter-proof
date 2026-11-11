package com.meterproof.app

/**
 * حذف الصور بيد المستخدم (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * الحذف يزيل ملف الصورة فقط، ويبقى سجلها في records.jsonl كما هو (فتبقى سلسلة البصمات سليمة)،
 * ويُكتب قبله حدث جديد PHOTO_DELETED في events.jsonl فيه معرّف السجل وبصمة الصورة (sha256).
 * التحقق يقبل غياب ملف الصورة فقط إن وُجد حدث حذف ببصمة تطابق سجلها، ويعرضه «حذف مسجّل».
 * أي غياب آخر يبقى تلاعبًا. حدث الحذف نفسه محمي بسلسلة الأحداث، فلا يُزوَّر دون كسرها.
 */
object DeletionLog {

    const val EVENT = "PHOTO_DELETED"

    private val ID_PATTERN = Regex("^[0-9a-fA-F-]{8,40}$")
    private val SHA_PATTERN = Regex("^[0-9a-f]{64}$")

    /** تفاصيل حدث الحذف، أو null إن كان المعرّف أو البصمة غير صالحين (فلا يُكتب شيء). */
    fun detail(recordId: String, sha: String): String? {
        if (!ID_PATTERN.matches(recordId) || !SHA_PATTERN.matches(sha)) return null
        return "rec=$recordId;sha=$sha"
    }

    /** المعرّف والبصمة من تفاصيل الحدث، أو null إن لم يكن بالصيغة. */
    fun parse(detail: String): Pair<String, String>? {
        val kv = detail.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val id = kv["rec"] ?: return null
        val sha = kv["sha"] ?: return null
        if (!ID_PATTERN.matches(id) || !SHA_PATTERN.matches(sha)) return null
        return id to sha
    }

    /** الصور المحذوفة بيد المستخدم: معرّف السجل ← بصمة الصورة المسجّلة في الحدث. */
    fun deleted(events: List<EventRec>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (e in events) {
            if (e.type != EVENT) continue
            val p = parse(e.detail) ?: continue
            out[p.first] = p.second
        }
        return out
    }

    /** هل غياب ملف الصورة هذه مبرَّر بحدث حذف يطابق بصمة سجلها؟ */
    fun isRecordedDeletion(deleted: Map<String, String>, recordId: String, recordSha: String): Boolean =
        deleted[recordId] == recordSha
}

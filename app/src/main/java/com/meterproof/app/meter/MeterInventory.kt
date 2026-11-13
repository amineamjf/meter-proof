package com.meterproof.app.meter

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * منطق الجرد الذكي للعدادات (نقي بلا أندرويد، يُختبر على JVM). نظام مستقل تمامًا عن الفواتير:
 * لا يشارك بياناتها ولا نماذجها ولا ملفاتها (مجلد meter خاص به).
 *
 * يغطي: المهام، تقييم نتيجة OCR مقابل الرقم المتوقع بحالات الوثيقة، المطابقة بمعرّف المهمة لا بترتيب الصفوف،
 * والتصحيح اليدوي مع حفظ القيمة الأصلية وسجل التعديل. قراءة الرقم من الصورة (OCR) ليست هنا بعد:
 * هذا الملف يستقبل نتيجتها فقط.
 */
enum class MeterStatus { METER_MATCH, METER_MISMATCH, OCR_UNCERTAIN, IMAGE_UNUSABLE, LOCATION_WARNING, MANUAL_REVIEW }

data class MeterTask(
    val taskId: String,
    val expectedSerial: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val label: String = ""
)

/** ناتج OCR كما يسلّمه المحرك: نص (أو null)، وثقة 0..1، وهل الصورة صالحة أصلًا (حدّة، إضاءة، خصوصية). */
data class OcrResult(val text: String?, val confidence: Float, val imageUsable: Boolean)

data class Fix(val lat: Double, val lon: Double, val accuracyM: Float)

object MeterMatcher {

    const val MIN_CONFIDENCE = 0.6f
    const val BASE_LOCATION_RADIUS_M = 100.0

    /** يوحّد الرقم للمقارنة: أحرف كبيرة، بلا مسافات ولا شرطات ولا نقاط. */
    fun normalize(s: String?): String =
        (s ?: "").uppercase().filter { it.isLetterOrDigit() }

    /** تحويل الأحرف المتشابهة بصريًا لشكل واحد (للتنبيه فقط، لا للحكم بالمطابقة). */
    private fun confusable(s: String): String = s.map {
        when (it) { 'O', 'Q', 'D' -> '0'; 'I', 'L' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> it }
    }.joinToString("")

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * القواعد بالترتيب: صورة غير صالحة ← IMAGE_UNUSABLE. نص غائب أو ثقة منخفضة ← OCR_UNCERTAIN (لا نحكم بعدم
     * المطابقة من قراءة مشكوك فيها). المطابقة التامة ← METER_MATCH، أو LOCATION_WARNING إن بعد الموقع عن موقع المهمة.
     * الاختلاف بحرف واحد أو بأحرف متشابهة بصريًا ← MANUAL_REVIEW (لا نجزم). غير ذلك ← METER_MISMATCH.
     * لا نجزم بأي حال بمخالفة العامل: كل هذه قرائن تحتاج مراجعة بشرية.
     */
    fun evaluate(task: MeterTask, ocr: OcrResult, fix: Fix? = null): MeterStatus {
        if (!ocr.imageUsable) return MeterStatus.IMAGE_UNUSABLE
        val read = normalize(ocr.text)
        if (read.isEmpty() || ocr.confidence < MIN_CONFIDENCE) return MeterStatus.OCR_UNCERTAIN
        val expected = normalize(task.expectedSerial)
        if (read == expected) {
            return if (locationFar(task, fix)) MeterStatus.LOCATION_WARNING else MeterStatus.METER_MATCH
        }
        if (confusable(read) == confusable(expected) || editDistance(read, expected) <= 1) return MeterStatus.MANUAL_REVIEW
        return MeterStatus.METER_MISMATCH
    }

    private fun locationFar(task: MeterTask, fix: Fix?): Boolean {
        if (fix == null || task.lat == null || task.lon == null) return false
        val limit = maxOf(BASE_LOCATION_RADIUS_M, fix.accuracyM * 2.0)
        return distanceM(task.lat, task.lon, fix.lat, fix.lon) > limit
    }
}

/** تعديل يدوي واحد: لا يُحذف ولا يُعدَّل بعد كتابته. */
data class Edit(val t: Long, val field: String, val old: String, val new: String, val reason: String)

/**
 * سجل جرد عداد واحد. [originalSerial] لا يتغير أبدًا؛ [correctedSerial] القيمة المصحَّحة يدويًا إن وُجدت،
 * و[history] سجل التعديلات الكامل.
 */
data class MeterRecord(
    val taskId: String,
    val originalSerial: String,
    val confidence: Float,
    val status: MeterStatus,
    val capturedAt: Long,
    val correctedSerial: String? = null,
    val manuallyReviewed: Boolean = false,
    val history: List<Edit> = emptyList()
) {
    /** القيمة المعتمدة: المصحَّحة إن وُجدت وإلا الأصلية. */
    val effectiveSerial: String get() = correctedSerial ?: originalSerial
}

object MeterInventory {

    /** ينشئ سجلًا من قراءة جديدة. */
    fun record(task: MeterTask, ocr: OcrResult, fix: Fix?, now: Long): MeterRecord =
        MeterRecord(
            task.taskId, MeterMatcher.normalize(ocr.text), ocr.confidence.coerceIn(0f, 1f),
            MeterMatcher.evaluate(task, ocr, fix), now
        )

    /**
     * تصحيح يدوي: تُحفظ القيمة الأصلية وتُضاف سطر في السجل بالسبب والوقت. تُقيَّم الحالة من جديد بالقيمة
     * المصحَّحة (تطابق ← METER_MATCH، وإلا METER_MISMATCH) مع علامة المراجعة اليدوية. السبب إلزامي.
     */
    fun correct(rec: MeterRecord, task: MeterTask, newSerial: String, reason: String, now: Long): MeterRecord? {
        val n = MeterMatcher.normalize(newSerial)
        if (n.isEmpty() || reason.isBlank() || rec.taskId != task.taskId) return null
        val status = if (n == MeterMatcher.normalize(task.expectedSerial)) MeterStatus.METER_MATCH else MeterStatus.METER_MISMATCH
        return rec.copy(
            correctedSerial = n, status = status, manuallyReviewed = true,
            history = rec.history + Edit(now, "serial", rec.effectiveSerial, n, reason.trim().take(120))
        )
    }

    class Joined(val matched: List<Pair<MeterTask, MeterRecord>>, val missingRecord: List<MeterTask>, val unknownRecord: List<MeterRecord>)

    /** يربط السجلات بالمهام بمعرّف المهمة فقط (لا بترتيب الصفوف). */
    fun join(tasks: List<MeterTask>, records: List<MeterRecord>): Joined {
        val byId = records.associateBy { it.taskId }
        val ids = tasks.map { it.taskId }.toSet()
        return Joined(
            tasks.mapNotNull { t -> byId[t.taskId]?.let { t to it } },
            tasks.filter { it.taskId !in byId },
            records.filter { it.taskId !in ids }
        )
    }

    /** ملخص حالات (للتقارير). */
    fun summary(records: List<MeterRecord>): Map<MeterStatus, Int> =
        MeterStatus.values().associateWith { s -> records.count { it.status == s } }.filterValues { it > 0 }
}

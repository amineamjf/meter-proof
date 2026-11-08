package com.meterproof.app.privacy

/** منطقة داخل الصورة بإحداثيات نسبية من 0 إلى 1 (لا تعتمد على دقة الصورة). */
data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val area: Float get() = maxOf(0f, right - left) * maxOf(0f, bottom - top)

    fun clamped(): Region = Region(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
    )
}

enum class HazardKind { FACE, PERSON }

/**
 * خطر خصوصية اكتشفه كاشف. score من 0 إلى 1: كلما ارتفع زادت الثقة بأنه خطر حقيقي.
 */
data class Hazard(val kind: HazardKind, val score: Float, val region: Region? = null)

enum class FailReason { EXCEPTION, TIMEOUT }

/** ما عاد به كاشف واحد: إما قائمة أخطار (قد تكون فارغة)، أو فشل. */
sealed class DetectorOutcome {
    abstract val detector: String

    data class Ok(override val detector: String, val hazards: List<Hazard>) : DetectorOutcome()
    data class Failed(override val detector: String, val reason: FailReason) : DetectorOutcome()
}

/**
 * مستوى الناتج. الترتيب من الأقل حماية إلى الأعلى:
 * REVEAL: منطقة معتمدة تُكشف بوضوح (لا يوجد مصدر لها قبل المرحلة 2).
 * ABSTRACT: صورة مجرّدة إلى كتل ألوان، لا يُعرف منها شيء.
 * DISCARD: لا صورة إطلاقًا، يُحفظ سجل الحدث فقط.
 */
enum class PrivacyTier(val code: String, val severity: Int) {
    REVEAL("T0", 0),
    ABSTRACT("T2", 1),
    DISCARD("T3", 2)
}

/** قرار البوابة. reasons رموز ثابتة بالإنجليزية (لا نص حر) تُكتب في سجل الحدث. */
data class GateDecision(
    val tier: PrivacyTier,
    val reasons: List<String>,
    val revealRegion: Region? = null
)

/**
 * مستوى تفصيل الفاتورة عند الكشف. حاليًا FULL (كل شيء ظاهر، بقرار صاحب المشروع).
 * القيمتان الأخريان محجوزتان: الإخفاء لاحقًا يضاف عند تطبيق الكشف دون تغيير بقية البوابة.
 */
enum class InvoiceDetail { FULL, MASK_PII, OUTLINE_ONLY }

data class PrivacyConfig(
    /** أي خطر بدرجة أقل من هذه يُهمل (ضجيج الكاشف). */
    val minHazardScore: Float = 0.2f,
    /** من هذه الدرجة فما فوق: خطر مؤكد. بين الحدين: غير مؤكد (والنتيجة نفسها: إتلاف). */
    val certainHazardScore: Float = 0.5f,
    val invoiceDetail: InvoiceDetail = InvoiceDetail.FULL,
    /**
     * أكبر مساحة (نسبة من الإطار) يجوز كشفها. منطقة أكبر لا تُعدّ ورقة بل مشهدًا، فلا تُكشف
     * (الناتج مجرّد). هذا يمنع أن يتحول "كشف الفاتورة" إلى كشف جزء كبير من محيط البيت.
     */
    val maxRevealArea: Float = 0.40f
)

/**
 * قاعدة القرار، دالة نقية بلا أي اعتماد على أندرويد.
 *
 * - وجود أي شخص أو وجه أو خطر غير مؤكد في أي مكان من الإطار: إتلاف (DISCARD).
 * - فشل أي كاشف أو انتهاء مهلته: إتلاف.
 * - لا كاشفات إطلاقًا: إتلاف.
 * - إن سلم كل شيء: صورة مجرّدة، أو منطقة معتمدة إن طُلب كشفها وكانت مساحتها ضمن الحد المسموح.
 *
 * لا يوجد مسار يخفّض الحماية: إضافة أي خطر أو فشل لا يمكن أن تنتج مستوى أقل شدة.
 */
class PrivacyPolicy(val config: PrivacyConfig = PrivacyConfig()) {

    fun decide(outcomes: List<DetectorOutcome>, reveal: Region? = null): GateDecision {
        if (outcomes.isEmpty()) return discard(listOf("NO_DETECTORS"))

        val reasons = LinkedHashSet<String>()
        for (o in outcomes) {
            when (o) {
                is DetectorOutcome.Failed ->
                    reasons.add(
                        (if (o.reason == FailReason.TIMEOUT) "TIMEOUT:" else "FAILED:") + o.detector
                    )
                is DetectorOutcome.Ok -> for (h in o.hazards) {
                    if (h.score >= config.certainHazardScore) {
                        reasons.add(h.kind.name)
                    } else if (h.score >= config.minHazardScore) {
                        reasons.add("UNCERTAIN_" + h.kind.name)
                    }
                }
            }
        }
        if (reasons.isNotEmpty()) return discard(reasons.toList())

        val region = reveal?.clamped()
        return if (region != null && region.area > 0f && region.area <= config.maxRevealArea) {
            GateDecision(PrivacyTier.REVEAL, emptyList(), region)
        } else {
            GateDecision(PrivacyTier.ABSTRACT, emptyList())
        }
    }

    private fun discard(reasons: List<String>) = GateDecision(PrivacyTier.DISCARD, reasons)
}

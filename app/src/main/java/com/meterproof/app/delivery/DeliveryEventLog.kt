package com.meterproof.app.delivery

import com.meterproof.app.privacy.GateEventLog
import com.meterproof.app.privacy.InvoiceDetail
import com.meterproof.app.privacy.PrivacyTier
import kotlin.math.roundToInt

/**
 * ما يكتبه كشف التسليم في سجل الأدلة المبصوم، دون تغيير صيغة السجلات:
 *
 * - صورة T0 محفوظة: علامات PRIVACY_T0 وDELIVERY (وDELIVERY_OUTLINE إن أُخفي المحتوى) داخل البصمة.
 * - حدث DELIVERY_DETECTED أو DELIVERY_UNCERTAIN في events.jsonl، وdetail رموز وأرقام فقط.
 *
 * لا يُسجَّل حدث «لم تُكتشف فاتورة» إطلاقًا: غياب الكشف ليس دليلًا على عدم التسليم
 * (الزاوية، الإضاءة، يد العامل)، ولا يجوز أن يتحول إلى أداة اتهام. الأدلة إيجابية فقط.
 */
object DeliveryEventLog {

    const val EVENT_DETECTED = "DELIVERY_DETECTED"
    const val EVENT_UNCERTAIN = "DELIVERY_UNCERTAIN"
    const val FLAG_DELIVERY = "DELIVERY"
    const val FLAG_OUTLINE = "DELIVERY_OUTLINE"

    /**
     * حالة الدليل بحسب الوثيقة. تُسجَّل مع الحدث (ev=)، و[NO_USABLE_EVIDENCE] لا يُكتب أبدًا في السجل
     * (غياب الكشف ليس دليلًا على عدم التسليم)، بل تحسبه التقارير حين لا يوجد حدث لنقطة ما.
     * وجود فاتورة في الصورة ليس إثباتًا لتسليمها: ACCEPTED تعني «قرينة مقبولة» لا «تسليمًا مثبتًا».
     */
    enum class EvidenceStatus { EVIDENCE_ACCEPTED, EVIDENCE_UNCERTAIN, NO_USABLE_EVIDENCE, PRIVACY_REJECTED }

    fun evidenceStatus(tier: PrivacyTier?, state: DeliveryState, privacyRejected: Boolean = false): EvidenceStatus = when {
        privacyRejected -> EvidenceStatus.PRIVACY_REJECTED
        tier == PrivacyTier.REVEAL && state == DeliveryState.DETECTED -> EvidenceStatus.EVIDENCE_ACCEPTED
        tier == PrivacyTier.ABSTRACT &&
            (state == DeliveryState.DETECTED || state == DeliveryState.UNCERTAIN) -> EvidenceStatus.EVIDENCE_UNCERTAIN
        else -> EvidenceStatus.NO_USABLE_EVIDENCE
    }

    private val ID_PATTERN = Regex("^[0-9a-fA-F-]{8,40}$")

    /** نوع الحدث، أو null إن لم يكن هناك ما يُسجَّل. */
    fun eventType(tier: PrivacyTier, state: DeliveryState): String? = when {
        tier == PrivacyTier.REVEAL && state == DeliveryState.DETECTED -> EVENT_DETECTED
        tier == PrivacyTier.ABSTRACT &&
            (state == DeliveryState.DETECTED || state == DeliveryState.UNCERTAIN) -> EVENT_UNCERTAIN
        else -> null
    }

    /** علامات السجل المبصوم لصورة محفوظة بهذا القرار. */
    fun flags(tier: PrivacyTier, detail: InvoiceDetail): Set<String> {
        if (tier != PrivacyTier.REVEAL) return emptySet()
        return if (detail == InvoiceDetail.FULL) setOf(FLAG_DELIVERY) else setOf(FLAG_DELIVERY, FLAG_OUTLINE)
    }

    fun detail(
        mode: String, score: Float, modelId: String, level: InvoiceDetail, recordId: String?,
        status: EvidenceStatus? = null
    ): String {
        val pct = (score * 100f).roundToInt().coerceIn(0, 100)
        val sb = StringBuilder()
        sb.append("mode=").append(clean(mode))
        sb.append(";score=").append(pct)
        sb.append(";cal=").append(clean(modelId))
        sb.append(";detail=").append(level.name)
        if (recordId != null && ID_PATTERN.matches(recordId)) sb.append(";rec=").append(recordId)
        if (status != null) sb.append(";ev=").append(status.name)
        sb.append(";gate=").append(GateEventLog.GATE_VERSION)
        return sb.toString()
    }

    fun parse(detail: String): Map<String, String> = GateEventLog.parse(detail)

    private fun clean(s: String): String =
        s.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }
            .joinToString("")
}

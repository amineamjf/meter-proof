package com.meterproof.app.privacy

/**
 * صياغة ما تكتبه البوابة في السجل المبصوم الحالي، دون تغيير صيغة الأدلة:
 *
 * - صورة محفوظة: علامة (flag) مثل PRIVACY_T2 داخل سجل الصورة.
 * - صورة أُتلفت: حدث CAPTURE_DISCARDED في events.jsonl، و detail رموز ثابتة فقط.
 *
 * لا يدخل أي نص حر ولا بيانات عن المشهد، فقط رموز الأسباب والزمن.
 */
object GateEventLog {

    const val EVENT_DISCARDED = "CAPTURE_DISCARDED"

    /** يزداد عند تغيير قواعد البوابة، فيُعرف أي إصدار أصدر القرار. */
    const val GATE_VERSION = 1

    /** علامة المرشح القديم (طمس الوجوه فقط)، تُوسم بها صور وضع الجرد. */
    const val FLAG_LEGACY = "PRIVACY_LEGACY"

    fun flag(tier: PrivacyTier): String = "PRIVACY_" + tier.code

    fun discardDetail(mode: String, reasons: List<String>, totalMs: Long): String =
        "mode=${clean(mode)};reason=${reasons.joinToString(",") { clean(it) }};" +
            "gate=$GATE_VERSION;ms=$totalMs"

    fun parse(detail: String): Map<String, String> =
        detail.split(';')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    /** هل بين الأسباب فشل تقني (وليس مجرد اكتشاف شخص)؟ */
    fun isFailure(reasons: List<String>): Boolean =
        reasons.any {
            it.startsWith("FAILED:") || it.startsWith("TIMEOUT:") ||
                it == "NO_DETECTORS" || it == "POLICY_FAILED" || it == "RENDER_FAILED"
        }

    /** يُبقي الأحرف الإنجليزية الكبيرة والأرقام و _ و : فقط، فلا يمكن كسر صيغة detail. */
    private fun clean(s: String): String =
        s.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == ':') it else '_' }
            .joinToString("")
}

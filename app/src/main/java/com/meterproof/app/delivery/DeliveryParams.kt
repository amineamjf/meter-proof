package com.meterproof.app.delivery

/**
 * عتبات آلة حالات التسليم. القيم الافتراضية تقديرية، ويضبطها [Calibrator] من عينات العامل نفسه.
 * (الأزمنة بالملي ثانية، والنسب من مساحة الإطار الصغير.)
 */
data class DeliveryParams(
    /** أقل نسبة جلد لاعتبار اليد ظاهرة. */
    val skinMin: Float = 0.02f,
    /** أقل نسبة "ورقة في اليد" لاعتبار الفاتورة في يد العامل. */
    val heldMin: Float = 0.004f,
    /** المدة التراكمية لظهور يد تحمل ورقة قبل اعتبارها بداية تسليم. */
    val holdMs: Long = 600L,
    /** مهلة التسامح في انقطاع اللقطة أثناء تراكم المدة. */
    val graceMs: Long = 1_500L,
    /** مدة اختفاء اليد لاعتبارها انسحبت. */
    val withdrawMs: Long = 600L,
    /** مدى مساحة الجسم الموضوع (ورقة). أكبر من الحد الأعلى ليس ورقة (جدار أو باب). */
    val objMin: Float = 0.004f,
    val objMax: Float = 0.30f,
    /** مدة ثبات الجسم لاعتباره موضوعًا. */
    val restMs: Long = 900L,
    /** أقصى تحرك لمركز الجسم أثناء الثبات (نسبة من الإطار). */
    val moveTol: Float = 0.05f,
    /** أقصى حركة عامة أثناء الثبات. */
    val motionMax: Float = 0.05f,
    /** فوق هذه الحركة لا يُحتسب إطار اليد (كاميرا تهتز أو انحناء). */
    val motionHold: Float = 0.12f,
    /** المهلة بعد انسحاب اليد لظهور جسم ثابت قبل إعلان عدم التأكد. */
    val settleMs: Long = 3_500L,
    /** أقصى مدة للتسليم من أول لحظة إمساك بالورقة. */
    val maxSeqMs: Long = 30_000L,
    /** الجزء السفلي من الإطار (من 0 إلى 1) الذي يُعدّ "تحت الباب" لاختفاء الورقة. */
    val underY: Float = 0.60f,
    /** مدة اختفاء الورقة واليد ما زالت ظاهرة (نمط الدفع تحت الباب). */
    val vanishMs: Long = 500L,
    /** هل ثبت بالمعايرة أن نمط "تحت الباب" موثوق؟ إن لم يثبت يبقى غير مؤكد دائمًا. */
    val underTrusted: Boolean = false,
    /** رقم إصدار المعايرة (للسجل). */
    val version: Int = 0
) {
    fun serialize(): String = listOf(
        "skinMin=$skinMin", "heldMin=$heldMin", "holdMs=$holdMs", "graceMs=$graceMs",
        "withdrawMs=$withdrawMs", "objMin=$objMin", "objMax=$objMax", "restMs=$restMs",
        "moveTol=$moveTol", "motionMax=$motionMax", "motionHold=$motionHold",
        "settleMs=$settleMs", "maxSeqMs=$maxSeqMs", "underY=$underY", "vanishMs=$vanishMs",
        "underTrusted=$underTrusted", "version=$version"
    ).joinToString(";")

    companion object {
        fun parse(s: String): DeliveryParams {
            val m = s.split(';').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val d = DeliveryParams()
            fun f(k: String, def: Float) = m[k]?.toFloatOrNull() ?: def
            fun l(k: String, def: Long) = m[k]?.toLongOrNull() ?: def
            return DeliveryParams(
                skinMin = f("skinMin", d.skinMin), heldMin = f("heldMin", d.heldMin),
                holdMs = l("holdMs", d.holdMs), graceMs = l("graceMs", d.graceMs),
                withdrawMs = l("withdrawMs", d.withdrawMs), objMin = f("objMin", d.objMin),
                objMax = f("objMax", d.objMax), restMs = l("restMs", d.restMs),
                moveTol = f("moveTol", d.moveTol), motionMax = f("motionMax", d.motionMax),
                motionHold = f("motionHold", d.motionHold), settleMs = l("settleMs", d.settleMs),
                maxSeqMs = l("maxSeqMs", d.maxSeqMs), underY = f("underY", d.underY),
                vanishMs = l("vanishMs", d.vanishMs),
                underTrusted = m["underTrusted"] == "true",
                version = m["version"]?.toIntOrNull() ?: d.version
            )
        }
    }
}

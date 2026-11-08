package com.meterproof.app.delivery

import java.util.Locale

/**
 * خصائص إطار واحد: أرقام فقط، لا بكسلات. هذا كل ما يُخزَّن للتدريب والمعايرة،
 * فلا يمكن أن تحتوي عينات التدريب وجهًا أو داخل بيت.
 *
 * كل القيم نسب من 0 إلى 1 (والمراكز نسبية داخل الإطار)، و -1 تعني "غير موجود".
 */
data class Feat(
    /** زمن الإطار بالملي ثانية (نسبي). */
    val t: Long,
    /** نسبة بكسلات لون الجلد (يد). */
    val skin: Float,
    val skinX: Float,
    val skinY: Float,
    /** نسبة بكسلات "الورقة" الملاصقة للجلد (ورقة في اليد). */
    val held: Float,
    val heldX: Float,
    val heldY: Float,
    /** نسبة بكسلات "جسم ساطع جديد" مقارنة بمشهد ما قبل ظهور اليد (ورقة موضوعة). */
    val obj: Float,
    /** مركز مربع الجسم وأبعاده. */
    val objX: Float,
    val objY: Float,
    val objW: Float,
    val objH: Float,
    /** متوسط فرق الإطار عن سابقه (حركة الكاميرا أو المشهد). */
    val motion: Float,
    /** متوسط السطوع. */
    val lum: Float
) {
    fun toLine(): String = listOf(
        t.toString(), f(skin), f(skinX), f(skinY), f(held), f(heldX), f(heldY),
        f(obj), f(objX), f(objY), f(objW), f(objH), f(motion), f(lum)
    ).joinToString(",")

    companion object {
        const val FIELDS = 14

        fun parse(line: String): Feat? {
            val p = line.split(',')
            if (p.size != FIELDS) return null
            val t = p[0].toLongOrNull() ?: return null
            val v = FloatArray(FIELDS - 1)
            for (i in 1 until FIELDS) v[i - 1] = p[i].toFloatOrNull() ?: return null
            return Feat(t, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12])
        }

        private fun f(x: Float): String = String.format(Locale.US, "%.4f", x)

        /** إطار فارغ (لا يد ولا ورقة ولا جسم). */
        fun empty(t: Long, motion: Float = 0.01f, lum: Float = 0.5f) =
            Feat(t, 0f, -1f, -1f, 0f, -1f, -1f, 0f, -1f, -1f, 0f, 0f, motion, lum)
    }
}

/** نوع العينة. الإيجابية = تسليم حقيقي، والسلبية = حركة عادية يجب ألا تُعدّ تسليمًا. */
enum class Label(val code: String, val positive: Boolean, val ar: String) {
    POS_ON("pos_on", true, "تسليم على الباب أو عتبته"),
    POS_BESIDE("pos_beside", true, "تسليم بجانب الباب"),
    POS_UNDER("pos_under", true, "تسليم تحت الباب"),
    NEG_WALK("neg_walk", false, "مشي عادي"),
    NEG_BEND("neg_bend", false, "انحناء"),
    NEG_TOUCH("neg_touch", false, "لمس الباب بلا ورقة"),
    NEG_PAPER("neg_paper", false, "تحريك ورقة دون وضعها"),
    NEG_HAND("neg_hand", false, "يد فقط"),
    NEG_OTHER("neg_other", false, "أخرى (لا تسليم)");

    companion object {
        fun fromCode(c: String): Label? = values().firstOrNull { it.code == c }
    }
}

/** عينة معايرة: تسلسل خصائص بلا صور. colorVersion إصدار نموذج الألوان وقت التسجيل. */
class Sample(val label: Label, val colorVersion: Int, val frames: List<Feat>)

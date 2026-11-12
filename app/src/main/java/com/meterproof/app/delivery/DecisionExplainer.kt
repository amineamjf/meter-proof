package com.meterproof.app.delivery

import kotlin.math.abs

/**
 * يشرح لماذا أعطى النموذج هذه الدرجة (منطق نقي): مساهمة كل سمة = الوزن × السمة الموحّدة.
 * الموجب يرفع احتمال «فاتورة» والسالب يخفضه. شرح للسمات فقط، لا يغيّر أي قرار.
 */
object DecisionExplainer {

    val NAMES = listOf("حجم الورقة", "امتلاء الشكل", "استطالة الشكل", "التباين مع الخلفية", "تشبع اللون", "كثافة النص")

    class Contribution(val index: Int, val value: Float)

    fun contributions(f: InvoiceFeatures, model: InvoiceModel): List<Contribution> {
        val x = f.toList()
        return (0 until InvoiceFeatures.SIZE).map { i ->
            val s = if (model.std[i] > 1e-6f) model.std[i] else 1f
            Contribution(i, model.weights[i] * ((x[i] - model.mean[i]) / s))
        }
    }

    /** جملة قصيرة: أقوى سمة رفعت وأقوى سمة خفّضت (تُهمل ما تحت 0.2 كأثر ضئيل). */
    fun explain(f: InvoiceFeatures, model: InvoiceModel): String {
        val c = contributions(f, model)
        val up = c.filter { it.value >= MIN_EFFECT }.maxByOrNull { it.value }
        val down = c.filter { it.value <= -MIN_EFFECT }.minByOrNull { it.value }
        val parts = ArrayList<String>()
        if (up != null) parts.add("رفعها: ${NAMES[up.index]}")
        if (down != null) parts.add("خفّضها: ${NAMES[down.index]}")
        if (parts.isEmpty()) parts.add("لا سمة حاسمة")
        return parts.joinToString("، ")
    }

    private const val MIN_EFFECT = 0.2f

    /** أكبر أثر مطلق (للاختبار). */
    fun strongest(f: InvoiceFeatures, model: InvoiceModel): Int =
        contributions(f, model).maxByOrNull { abs(it.value) }!!.index
}

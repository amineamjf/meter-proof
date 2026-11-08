package com.meterproof.app.privacy

import kotlin.math.ceil

/**
 * تحليل قناع الأشخاص (ناتج نموذج التقسيم): قيمة لكل بكسل من 0 إلى 1 (احتمال أن يكون جزءًا من شخص).
 * منطق نقي بلا أندرويد حتى يُختبر على JVM.
 *
 * العتبات متشددة عمدًا (الإفراط في الإتلاف هو الاتجاه الآمن) وتُضبط لاحقًا من بيانات التجربة.
 * حد معروف: شخص صغير جدًا في الإطار (أقل من نحو 0.2% من مساحته) لا يُكتشف.
 */
object PersonMask {

    /** من هذه القيمة: بكسل شخص مؤكد. */
    const val CONFIDENT = 0.5f

    /** من هذه القيمة: بكسل مشتبه فيه. */
    const val FAINT = 0.25f

    /** أقل نسبة من مساحة القناع تُعدّ خطرًا، كي لا تُطلق بكسلات معزولة الإنذار. */
    const val MIN_FRACTION = 0.002f

    /**
     * يعيد خطر PERSON إن وُجد، أو null إن كان القناع خاليًا.
     * قناع تالف (قيمة NaN أو حجم أصغر من المتوقع) يرمي استثناءً فيُعدّ الكاشف فاشلًا والصورة تُتلف.
     */
    fun analyze(mask: FloatArray, w: Int, h: Int): Hazard? {
        require(w >= 1 && h >= 1) { "bad mask size" }
        require(mask.size >= w.toLong() * h) { "mask too small" }

        val minCount = ceil(MIN_FRACTION * w * h).toInt().coerceAtLeast(1)
        var faint = 0
        var strong = 0
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = mask[y * w + x]
                if (v.isNaN()) throw IllegalStateException("corrupt mask")
                if (v >= FAINT) {
                    faint++
                    if (v >= CONFIDENT) strong++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (faint < minCount) return null

        val region = Region(
            minX / w.toFloat(), minY / h.toFloat(),
            (maxX + 1) / w.toFloat(), (maxY + 1) / h.toFloat()
        )
        // مؤكد (1.0) أو غير مؤكد (0.3): السياسة تُتلف الصورة في الحالتين، والفرق يظهر في سبب الإتلاف
        val score = if (strong >= minCount) 1f else 0.3f
        return Hazard(HazardKind.PERSON, score, region)
    }
}

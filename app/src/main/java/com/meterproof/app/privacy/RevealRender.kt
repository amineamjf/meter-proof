package com.meterproof.app.privacy

/**
 * رسم صورة T0: كل الإطار مجرّد إلى كتل ألوان، عدا منطقة معتمدة واحدة.
 *
 * - FULL: بكسلات المنطقة الأصلية كما هي.
 * - OUTLINE_ONLY: المنطقة تُملأ بلون ثابت مع إطار، فيبقى الدليل «هنا ورقة» بلا أي محتوى.
 * - MASK_PII: لم يُنفَّذ بعد (يتطلب فصل حقول الاسم والعنوان). يُعامل كـ OUTLINE_ONLY، أي الأشد حماية.
 *
 * دالة نقية على مصفوفة ARGB دون اعتماد على أندرويد (قابلة للاختبار على JVM).
 * الضمان المختبَر: كل بكسل خارج المنطقة يساوي ناتج التجريد بالضبط، فلا تتسرب تفاصيل من خارجها.
 */
object RevealRender {

    private const val FILL = 0xFFF2F2F2.toInt()
    private const val OUTLINE = 0xFF202020.toInt()
    private const val OUTLINE_PX = 3

    /** يعيد مصفوفة جديدة بحجم w*h؛ المصفوفة الأصلية لا تُعدَّل. */
    fun compose(
        argb: IntArray, w: Int, h: Int, cols: Int, rows: Int,
        region: Region, detail: InvoiceDetail
    ): IntArray {
        require(w >= 1 && h >= 1) { "bad size" }
        require(argb.size >= w.toLong() * h) { "pixel array too small" }

        val blocks = Abstraction.blockMeans(argb, w, h, cols, rows)
        val out = Abstraction.upscale(blocks, cols, rows, w, h)

        val r = region.clamped()
        val x0 = (r.left * w).toInt().coerceIn(0, w)
        val x1 = (r.right * w).toInt().coerceIn(0, w)
        val y0 = (r.top * h).toInt().coerceIn(0, h)
        val y1 = (r.bottom * h).toInt().coerceIn(0, h)
        if (x1 <= x0 || y1 <= y0) return out

        if (detail == InvoiceDetail.FULL) {
            for (y in y0 until y1) {
                System.arraycopy(argb, y * w + x0, out, y * w + x0, x1 - x0)
            }
        } else {
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val onEdge = x - x0 < OUTLINE_PX || x1 - 1 - x < OUTLINE_PX ||
                        y - y0 < OUTLINE_PX || y1 - 1 - y < OUTLINE_PX
                    out[y * w + x] = if (onEdge) OUTLINE else FILL
                }
            }
        }
        return out
    }
}

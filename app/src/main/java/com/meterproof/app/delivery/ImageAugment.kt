package com.meterproof.app.delivery

import com.meterproof.app.privacy.Region

/**
 * تضخيم أمثلة التدريب (منطق نقي): من صورة فاتورة واحدة نصنع نسخة مقلوبة أفقيًا ونسخة بإضاءة مختلفة، فيرى النموذج
 * الفاتورة بعدة إضاءات واتجاهات من صورة واحدة. النسخ للتدريب فقط ولا تدخل أي تقييم أو قياس، وتتبع طيّة أصلها
 * فلا تسرّب صورة محجوزة إلى التدريب. تُصنع في الذاكرة ولا تُحفظ.
 */
object ImageAugment {

    class Variant(
        val suffix: String,
        val px: IntArray,
        val recognize: List<Region>,
        val ignore: List<Region>
    )

    fun mirror(r: Region) = Region(1f - r.right, r.top, 1f - r.left, r.bottom)

    fun flipH(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = px[y * w + (w - 1 - x)]
        return out
    }

    /** تعديل السطوع: كل قناة × [gain] + [bias]، مقصوصة إلى 0..255. */
    fun relight(px: IntArray, gain: Float, bias: Int): IntArray {
        val out = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            fun ch(shift: Int) = (((p shr shift) and 0xFF) * gain + bias).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }

    /**
     * نسختان لكل صورة فيها فاتورة معلَّمة: قلب أفقي، وإضاءة (أفتح ثم أغمق بالتناوب حسب [index]).
     * الصور التي بلا مربع فاتورة لا تُضخَّم (السلبيات أصلًا وفيرة).
     */
    fun variants(
        px: IntArray, w: Int, h: Int, recognize: List<Region>, ignore: List<Region>, index: Int
    ): List<Variant> {
        if (recognize.isEmpty()) return emptyList()
        val light = if (index % 2 == 0) relight(px, 1.25f, 12) else relight(px, 0.72f, -8)
        return listOf(
            Variant("f", flipH(px, w, h), recognize.map { mirror(it) }, ignore.map { mirror(it) }),
            Variant("l", light, recognize, ignore)
        )
    }
}

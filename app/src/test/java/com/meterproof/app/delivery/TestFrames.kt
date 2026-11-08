package com.meterproof.app.delivery

import com.meterproof.app.privacy.Frame

/** إطارات اصطناعية للاختبار (ARGB). لا صور حقيقية ولا أشخاص. */
internal object TestFrames {

    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    fun solid(w: Int, h: Int, color: Int): IntArray = IntArray(w * h) { color }

    fun rect(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = color
    }

    /** خطوط أفقية تحاكي أسطر نص مطبوع. */
    fun stripes(
        px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int,
        color: Int, period: Int = 6, thick: Int = 2
    ) {
        for (y in y0 until y1) {
            if ((y - y0) % period < thick) for (x in x0 until x1) px[y * w + x] = color
        }
    }

    /** خلفية داكنة وورقة بيضاء (80×60) في الوسط، بنص (خطوط) أو بلا نص. */
    fun paperScene(textured: Boolean, w: Int = 160, h: Int = 120): IntArray {
        val px = solid(w, h, rgb(40, 40, 40))
        rect(px, w, 40, 30, 120, 90, rgb(235, 235, 230))
        if (textured) stripes(px, w, 44, 34, 116, 86, rgb(170, 170, 170), 6, 2)
        return px
    }

    /** إطار يعيد نسخة جديدة في كل argb() (الكاشف يصفّر ما يأخذه). */
    class Fake(private val px: IntArray, override val width: Int, override val height: Int) : Frame {
        override fun argb(): IntArray = px.copyOf()
    }
}

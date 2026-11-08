package com.meterproof.app.delivery

import kotlin.math.abs

/**
 * نموذج ألوان اليد والورقة في فضاء YCbCr. القيم الافتراضية تقديرية، ومعايرة العامل
 * (يضع يده ثم الفاتورة أمام الكاميرا) تضبطها على لون بشرته وفاتورته وإضاءته.
 * لا يُخزَّن من المعايرة إلا هذه الأرقام (لا صور).
 */
data class ColorModel(
    val version: Int = 0,
    val cbMin: Int = 77,
    val cbMax: Int = 127,
    val crMin: Int = 133,
    val crMax: Int = 173,
    val skinYMin: Int = 40,
    val paperYMin: Int = 150,
    val paperChroma: Int = 16
) {
    fun isSkin(y: Int, cb: Int, cr: Int): Boolean =
        y >= skinYMin && cb in cbMin..cbMax && cr in crMin..crMax

    /** ورقة: ساطعة ولونها قريب من الرمادي. يُفحص الجلد أولًا (له الأولوية عند التداخل). */
    fun isPaper(y: Int, cb: Int, cr: Int): Boolean =
        y >= paperYMin && abs(cb - 128) <= paperChroma && abs(cr - 128) <= paperChroma

    fun serialize(): String =
        "version=$version;cbMin=$cbMin;cbMax=$cbMax;crMin=$crMin;crMax=$crMax;" +
            "skinYMin=$skinYMin;paperYMin=$paperYMin;paperChroma=$paperChroma"

    companion object {
        fun parse(s: String): ColorModel {
            val m = s.split(';').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val d = ColorModel()
            fun i(k: String, def: Int) = m[k]?.toIntOrNull() ?: def
            return ColorModel(
                i("version", d.version), i("cbMin", d.cbMin), i("cbMax", d.cbMax),
                i("crMin", d.crMin), i("crMax", d.crMax), i("skinYMin", d.skinYMin),
                i("paperYMin", d.paperYMin), i("paperChroma", d.paperChroma)
            )
        }

        fun yOf(p: Int): Int {
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            return (299 * r + 587 * g + 114 * b) / 1000
        }

        fun cbOf(p: Int): Int {
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            return 128 + (-169 * r - 331 * g + 500 * b) / 1000
        }

        fun crOf(p: Int): Int {
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            return 128 + (500 * r - 419 * g - 81 * b) / 1000
        }

        /** أقل عدد بكسلات لمعايرة موثوقة. */
        const val MIN_SAMPLES = 60

        /**
         * يضبط نطاق الجلد من بكسلات يد العامل (منطقة إرشادية داخل الإطار).
         * يستعمل النسبتين 3% و97% بدل الحدين، فلا تفسده بكسلات شاردة خارج اليد.
         * يعيد null إن كانت العينة أصغر من اللازم.
         */
        fun fitSkin(base: ColorModel, pixels: IntArray): ColorModel? {
            if (pixels.size < MIN_SAMPLES) return null
            val y = IntArray(pixels.size) { yOf(pixels[it]) }.also { it.sort() }
            val cb = IntArray(pixels.size) { cbOf(pixels[it]) }.also { it.sort() }
            val cr = IntArray(pixels.size) { crOf(pixels[it]) }.also { it.sort() }
            return base.copy(
                version = base.version + 1,
                cbMin = pct(cb, 3) - 4,
                cbMax = pct(cb, 97) + 4,
                crMin = pct(cr, 3) - 4,
                crMax = pct(cr, 97) + 4,
                skinYMin = (pct(y, 3) - 15).coerceAtLeast(20)
            )
        }

        /** يضبط حد سطوع الورقة ومدى لونها من بكسلات فاتورة العامل. */
        fun fitPaper(base: ColorModel, pixels: IntArray): ColorModel? {
            if (pixels.size < MIN_SAMPLES) return null
            val y = IntArray(pixels.size) { yOf(pixels[it]) }.also { it.sort() }
            val dev = IntArray(pixels.size) {
                maxOf(abs(cbOf(pixels[it]) - 128), abs(crOf(pixels[it]) - 128))
            }.also { it.sort() }
            return base.copy(
                version = base.version + 1,
                paperYMin = (pct(y, 5) - 12).coerceAtLeast(100),
                paperChroma = (pct(dev, 95) + 6).coerceIn(8, 40)
            )
        }

        private fun pct(sorted: IntArray, p: Int): Int =
            sorted[((sorted.size - 1) * p / 100).coerceIn(0, sorted.size - 1)]
    }
}

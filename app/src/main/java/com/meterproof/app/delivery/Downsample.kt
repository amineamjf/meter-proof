package com.meterproof.app.delivery

import java.nio.ByteBuffer

/**
 * يختزل إطار الكاميرا (RGBA) إلى صورة صغيرة معتدلة الاتجاه (upright) في الذاكرة فقط،
 * فتتطابق الإحداثيات النسبية مع الصورة الكاملة المُلتقطة لاحقًا. منطق نقي قابل للاختبار.
 */
object Downsample {

    /** أبعاد الناتج: الضلع الأطول longSide والنسبة محفوظة بعد التدوير. */
    fun targetSize(srcW: Int, srcH: Int, rotation: Int, longSide: Int = 80): Pair<Int, Int> {
        val upW = if (rotation == 90 || rotation == 270) srcH else srcW
        val upH = if (rotation == 90 || rotation == 270) srcW else srcH
        return if (upW >= upH) {
            Pair(longSide, (longSide * upH / upW.toFloat()).toInt().coerceAtLeast(1))
        } else {
            Pair((longSide * upW / upH.toFloat()).toInt().coerceAtLeast(1), longSide)
        }
    }

    /**
     * buf: بايتات RGBA (R أولًا). rotation: 0 أو 90 أو 180 أو 270 درجة (اتجاه تدوير الإطار ليصير معتدلًا
     * باتجاه عقارب الساعة). الناتج ARGB بحجم outW*outH، متوسط عينات موزعة داخل كل خلية.
     */
    fun uprightRgba(
        buf: ByteBuffer, srcW: Int, srcH: Int, rowStride: Int, pixelStride: Int,
        rotation: Int, outW: Int, outH: Int
    ): IntArray {
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) { "bad rotation" }
        require(srcW >= 1 && srcH >= 1 && outW >= 1 && outH >= 1) { "bad size" }
        val swap = rotation == 90 || rotation == 270
        val upW = if (swap) srcH else srcW
        val upH = if (swap) srcW else srcH

        val out = IntArray(outW * outH)
        for (oy in 0 until outH) {
            val uy0 = oy * upH / outH
            val uy1 = ((oy + 1) * upH / outH).coerceAtLeast(uy0 + 1).coerceAtMost(upH)
            val stepY = ((uy1 - uy0) / 4).coerceAtLeast(1)
            for (ox in 0 until outW) {
                val ux0 = ox * upW / outW
                val ux1 = ((ox + 1) * upW / outW).coerceAtLeast(ux0 + 1).coerceAtMost(upW)
                val stepX = ((ux1 - ux0) / 4).coerceAtLeast(1)
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var uy = uy0
                while (uy < uy1) {
                    var ux = ux0
                    while (ux < ux1) {
                        val sx: Int
                        val sy: Int
                        when (rotation) {
                            90 -> { sx = uy; sy = srcH - 1 - ux }
                            180 -> { sx = srcW - 1 - ux; sy = srcH - 1 - uy }
                            270 -> { sx = srcW - 1 - uy; sy = ux }
                            else -> { sx = ux; sy = uy }
                        }
                        val o = sy * rowStride + sx * pixelStride
                        r += buf.get(o).toInt() and 255
                        g += buf.get(o + 1).toInt() and 255
                        b += buf.get(o + 2).toInt() and 255
                        n++
                        ux += stepX
                    }
                    uy += stepY
                }
                out[oy * outW + ox] =
                    (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }
}

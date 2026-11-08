package com.meterproof.app.privacy

/**
 * التجريد: يختزل الصورة إلى شبكة صغيرة من كتل الألوان ويرمي كل تفصيل داخل الكتلة.
 *
 * هذا ليس طمسًا (Blur) ولا فسيفساء خفيفة، فهما يُبقيان بيانات قد يمكن استرجاعها جزئيًا.
 * هنا لا يبقى إلا لون واحد لكل كتلة، فلا طريق لاسترجاع وجه أو نص أو أثاث.
 *
 * دوال نقية على مصفوفة ARGB، دون أي اعتماد على أندرويد (قابلة للاختبار على JVM).
 */
object Abstraction {

    /**
     * متوسط لون كل كتلة. الناتج cols*rows عنصرًا (صف بعد صف) بشفافية كاملة.
     * أبعاد الصورة غير القابلة للقسمة تُوزَّع بالتساوي التقريبي، وكل بكسل يدخل في كتلة واحدة.
     */
    fun blockMeans(argb: IntArray, w: Int, h: Int, cols: Int, rows: Int): IntArray {
        require(w >= 1 && h >= 1) { "bad size" }
        require(cols >= 1 && rows >= 1) { "bad grid" }
        require(argb.size >= w.toLong() * h) { "pixel array too small" }

        val out = IntArray(cols * rows)
        for (j in 0 until rows) {
            val y0 = start(j, h, rows)
            val y1 = end(j, h, rows, y0)
            for (i in 0 until cols) {
                val x0 = start(i, w, cols)
                val x1 = end(i, w, cols, x0)
                var r = 0L
                var g = 0L
                var b = 0L
                for (y in y0 until y1) {
                    val base = y * w
                    for (x in x0 until x1) {
                        val p = argb[base + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val n = ((y1 - y0).toLong() * (x1 - x0)).coerceAtLeast(1L)
                out[j * cols + i] =
                    (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return out
    }

    /** يكبّر الشبكة إلى outW×outH بأخذ أقرب كتلة (حواف حادة، دون استيفاء). */
    fun upscale(blocks: IntArray, cols: Int, rows: Int, outW: Int, outH: Int): IntArray {
        require(cols >= 1 && rows >= 1 && outW >= 1 && outH >= 1) { "bad size" }
        require(blocks.size >= cols * rows) { "blocks array too small" }

        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            val j = (y.toLong() * rows / outH).toInt().coerceIn(0, rows - 1)
            for (x in 0 until outW) {
                val i = (x.toLong() * cols / outW).toInt().coerceIn(0, cols - 1)
                out[y * outW + x] = blocks[j * cols + i]
            }
        }
        return out
    }

    private fun start(k: Int, size: Int, parts: Int): Int =
        (k.toLong() * size / parts).toInt().coerceIn(0, size - 1)

    private fun end(k: Int, size: Int, parts: Int, start: Int): Int =
        ((k + 1).toLong() * size / parts).toInt().coerceIn(start + 1, size)
}

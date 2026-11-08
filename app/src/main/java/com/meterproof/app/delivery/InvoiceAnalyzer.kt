package com.meterproof.app.delivery

import com.meterproof.app.privacy.Abstraction
import com.meterproof.app.privacy.Region
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * سمات رقمية لمرشح ورقة (فاتورة) داخل الإطار. أرقام فقط، لا تحتوي أي بكسلات ولا يمكن استرجاع صورة منها،
 * ولذلك هي الشيء الوحيد الذي يُحفظ للتدريب والمعايرة.
 */
data class InvoiceFeatures(
    /** مساحة الورقة المكتشفة كنسبة من الإطار (0..1). */
    val areaFrac: Float,
    /** مساحة الورقة ÷ مساحة المستطيل المحيط بها (1 = مستطيل تام). */
    val fill: Float,
    /** ln(الضلع الأطول ÷ الأقصر) للمستطيل المحيط، بغض النظر عن اتجاه الورقة (>= 0). */
    val logAspect: Float,
    /** (متوسط سطوع الورقة − متوسط سطوع الباقي) ÷ 255. */
    val contrast: Float,
    /** متوسط تشبع ألوان الورقة (0..1)؛ الورق الأبيض منخفض التشبع. */
    val saturation: Float,
    /** كثافة الحواف داخل الورقة (0..1)؛ النص المطبوع يرفعها، والجدار الأبيض لا. */
    val textiness: Float
) {
    fun toList(): List<Float> = listOf(areaFrac, fill, logAspect, contrast, saturation, textiness)

    companion object {
        const val SIZE = 6

        fun fromList(v: List<Float>): InvoiceFeatures? {
            if (v.size != SIZE || v.any { !it.isFinite() }) return null
            return InvoiceFeatures(v[0], v[1], v[2], v[3], v[4], v[5])
        }
    }
}

data class InvoiceCandidate(val features: InvoiceFeatures, val region: Region)

/**
 * محلّل الورقة: رؤية حاسوبية كلاسيكية خفيفة (لا شبكة عصبية ولا مكتبات خارجية) على إطار في الذاكرة.
 *
 * الخطوات: تصغير إلى شبكة نحو 96 عمودًا ← عتبة أوتسو على السطوع ← قناع (فاتح وقليل التشبع)
 * ← أكبر مكوّن متصل ← سمات الشكل واللون ← كثافة الحواف داخل المكوّن.
 *
 * يعيد null إن لم يوجد مرشح معقول (لا ورق، أو صغير جدًا، أو الإطار كله فاتح).
 * دالة نقية دون اعتماد على أندرويد، وحتمية (نفس الإطار ينتج نفس السمات).
 * لا تكتب شيئًا على التخزين، ويبقى الإطار الأصلي في ذاكرة المستدعي.
 */
object InvoiceAnalyzer {

    data class Config(
        val gridCols: Int = 96,
        /** أدنى سطوع للورقة (0..255)، حتى لا تُعدّ الأسطح المظلمة ورقًا. */
        val minLuma: Int = 110,
        val maxSaturation: Float = 0.30f,
        val minAreaFrac: Float = 0.02f,
        /** أكبر من هذا غالبًا جدار أو سماء أو إضاءة قوية، لا ورقة. */
        val maxAreaFrac: Float = 0.40f,
        /** فرق السطوع بين بكسلين متجاورين الذي يُعدّ حافة. */
        val edgeThreshold: Int = 20,
        val regionMargin: Float = 0.02f
    )

    fun analyze(argb: IntArray, w: Int, h: Int, cfg: Config = Config()): InvoiceCandidate? {
        if (w < 8 || h < 8 || argb.size < w.toLong() * h) return null

        val gw = min(cfg.gridCols, w).coerceAtLeast(4)
        val gh = (gw.toLong() * h / w).toInt().coerceIn(4, 160)
        val cells = gw * gh
        val means = Abstraction.blockMeans(argb, w, h, gw, gh)

        val luma = IntArray(cells)
        val sat = FloatArray(cells)
        for (i in 0 until cells) {
            val p = means[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = (299 * r + 587 * g + 114 * b) / 1000
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            sat[i] = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
        }

        val thr = max(otsu(luma), cfg.minLuma)
        val mask = BooleanArray(cells) { luma[it] > thr && sat[it] <= cfg.maxSaturation }

        // أكبر مكوّن متصل (4 جيران) بطابور صريح، دون تكرار ذاتي
        val comp = IntArray(cells) { -1 }
        val queue = IntArray(cells)
        var bestId = -1
        var bestArea = 0
        var bMinX = 0
        var bMaxX = 0
        var bMinY = 0
        var bMaxY = 0
        var nextId = 0
        for (start in 0 until cells) {
            if (!mask[start] || comp[start] != -1) continue
            val id = nextId++
            var head = 0
            var tail = 0
            queue[tail++] = start
            comp[start] = id
            var area = 0
            var minX = gw
            var maxX = -1
            var minY = gh
            var maxY = -1
            while (head < tail) {
                val c = queue[head++]
                area++
                val cx = c % gw
                val cy = c / gw
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                if (cx > 0) tail = push(c - 1, mask, comp, id, queue, tail)
                if (cx < gw - 1) tail = push(c + 1, mask, comp, id, queue, tail)
                if (cy > 0) tail = push(c - gw, mask, comp, id, queue, tail)
                if (cy < gh - 1) tail = push(c + gw, mask, comp, id, queue, tail)
            }
            if (area > bestArea) {
                bestArea = area
                bestId = id
                bMinX = minX
                bMaxX = maxX
                bMinY = minY
                bMaxY = maxY
            }
        }
        if (bestId < 0) return null

        val areaFrac = bestArea / cells.toFloat()
        if (areaFrac < cfg.minAreaFrac || areaFrac > cfg.maxAreaFrac) return null

        val bw = bMaxX - bMinX + 1
        val bh = bMaxY - bMinY + 1
        val fill = bestArea / (bw.toFloat() * bh)

        val cellW = w / gw.toFloat()
        val cellH = h / gh.toFloat()
        val longSide = max(bw * cellW, bh * cellH)
        val shortSide = min(bw * cellW, bh * cellH)
        val logAspect = ln(longSide / shortSide)

        var sumIn = 0L
        var sumOut = 0L
        var sumSat = 0.0
        for (i in 0 until cells) {
            if (comp[i] == bestId) {
                sumIn += luma[i]
                sumSat += sat[i]
            } else {
                sumOut += luma[i]
            }
        }
        val outCount = cells - bestArea
        val meanIn = sumIn / bestArea.toFloat()
        val contrast = if (outCount > 0) (meanIn - sumOut / outCount.toFloat()) / 255f else 0f
        val saturation = (sumSat / bestArea).toFloat()

        val textiness = edgeDensity(argb, w, h, gw, gh, comp, bestId, cfg.edgeThreshold)

        val m = cfg.regionMargin
        val region = Region(
            bMinX / gw.toFloat() - m, bMinY / gh.toFloat() - m,
            (bMaxX + 1) / gw.toFloat() + m, (bMaxY + 1) / gh.toFloat() + m
        ).clamped()

        return InvoiceCandidate(
            InvoiceFeatures(areaFrac, fill, logAspect, contrast, saturation, textiness),
            region
        )
    }

    private fun push(
        n: Int, mask: BooleanArray, comp: IntArray, id: Int, queue: IntArray, tail: Int
    ): Int {
        if (!mask[n] || comp[n] != -1) return tail
        comp[n] = id
        queue[tail] = n
        return tail + 1
    }

    /**
     * نسبة البكسلات ذات الحواف داخل المكوّن، من خلايا داخلية فقط (كل جيرانها من المكوّن)،
     * فلا تُحسب حواف الورقة نفسها مقابل الخلفية. تؤخذ العينات بخطوة 2 على الإطار بدقته الكاملة.
     */
    private fun edgeDensity(
        argb: IntArray, w: Int, h: Int, gw: Int, gh: Int,
        comp: IntArray, id: Int, edgeThreshold: Int
    ): Float {
        var samples = 0L
        var edges = 0L
        for (cy in 1 until gh - 1) {
            for (cx in 1 until gw - 1) {
                val c = cy * gw + cx
                if (comp[c] != id || comp[c - 1] != id || comp[c + 1] != id ||
                    comp[c - gw] != id || comp[c + gw] != id
                ) continue
                val x0 = (cx.toLong() * w / gw).toInt()
                val x1 = ((cx + 1).toLong() * w / gw).toInt()
                val y0 = (cy.toLong() * h / gh).toInt()
                val y1 = ((cy + 1).toLong() * h / gh).toInt()
                var y = y0
                while (y < y1 && y + 1 < h) {
                    var x = x0
                    while (x < x1 && x + 1 < w) {
                        val l = lumaAt(argb[y * w + x])
                        val dx = kotlin.math.abs(lumaAt(argb[y * w + x + 1]) - l)
                        val dy = kotlin.math.abs(lumaAt(argb[(y + 1) * w + x]) - l)
                        samples++
                        if (dx + dy > edgeThreshold) edges++
                        x += 2
                    }
                    y += 2
                }
            }
        }
        return if (samples == 0L) 0f else edges / samples.toFloat()
    }

    private fun lumaAt(p: Int): Int =
        (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000

    /** عتبة أوتسو (0..255) على قيم السطوع. إطار موحد اللون يعطي 0. */
    internal fun otsu(values: IntArray): Int {
        val hist = IntArray(256)
        for (v in values) hist[v.coerceIn(0, 255)]++
        val total = values.size.toLong()
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var wB = 0L
        var sumB = 0L
        var best = 0
        var maxVar = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB / wB.toDouble()
            val mF = (sumAll - sumB) / wF.toDouble()
            val v = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (v > maxVar) {
                maxVar = v
                best = t
            }
        }
        return best
    }
}

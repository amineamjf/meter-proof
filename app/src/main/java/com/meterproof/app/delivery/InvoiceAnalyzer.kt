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

    /** مكوّن متصل واحد من قناع الورق (إحداثيات خلايا الشبكة). */
    private class Seg(
        val id: Int, val area: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int
    )

    /** تقسيم الإطار: شبكة السطوع والتشبع وتسمية المكوّنات. [segs] بترتيب الاكتشاف (رقم المكوّن). */
    private class Scene(
        val gw: Int, val gh: Int, val luma: IntArray, val sat: FloatArray,
        val comp: IntArray, val segs: List<Seg>
    )

    /**
     * يقسّم الإطار إلى مكوّنات «فاتحة قليلة التشبع». إن مُرِّرت [within] لا تُحسب إلا الخلايا التي يقع
     * مركزها داخل المنطقة (للتعليم اليدوي: «الفاتورة هنا»)، أما عتبة أوتسو فتبقى على الإطار كله
     * ليتطابق التعليم مع الكشف الحي.
     */
    private fun segment(
        argb: IntArray, w: Int, h: Int, cfg: Config, within: Region?
    ): Scene? {
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
        if (within != null) {
            val r = within.clamped()
            for (i in 0 until cells) {
                if (!mask[i]) continue
                val cx = ((i % gw) + 0.5f) / gw
                val cy = ((i / gw) + 0.5f) / gh
                if (cx < r.left || cx > r.right || cy < r.top || cy > r.bottom) mask[i] = false
            }
        }

        // المكوّنات المتصلة (4 جيران) بطابور صريح، دون تكرار ذاتي
        val comp = IntArray(cells) { -1 }
        val queue = IntArray(cells)
        val segs = ArrayList<Seg>()
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
            segs.add(Seg(id, area, minX, maxX, minY, maxY))
        }
        return Scene(gw, gh, luma, sat, comp, segs)
    }

    /** سمات وإطار مكوّن واحد (نفس الحساب الأصلي حرفيًا). */
    private fun candidateOf(
        argb: IntArray, w: Int, h: Int, cfg: Config, sc: Scene, seg: Seg
    ): InvoiceCandidate {
        val gw = sc.gw
        val gh = sc.gh
        val cells = gw * gh
        val bw = seg.maxX - seg.minX + 1
        val bh = seg.maxY - seg.minY + 1
        val areaFrac = seg.area / cells.toFloat()
        val fill = seg.area / (bw.toFloat() * bh)

        val cellW = w / gw.toFloat()
        val cellH = h / gh.toFloat()
        val longSide = max(bw * cellW, bh * cellH)
        val shortSide = min(bw * cellW, bh * cellH)
        val logAspect = ln(longSide / shortSide)

        var sumIn = 0L
        var sumOut = 0L
        var sumSat = 0.0
        for (i in 0 until cells) {
            if (sc.comp[i] == seg.id) {
                sumIn += sc.luma[i]
                sumSat += sc.sat[i]
            } else {
                sumOut += sc.luma[i]
            }
        }
        val outCount = cells - seg.area
        val meanIn = sumIn / seg.area.toFloat()
        val contrast = if (outCount > 0) (meanIn - sumOut / outCount.toFloat()) / 255f else 0f
        val saturation = (sumSat / seg.area).toFloat()

        val textiness = edgeDensity(argb, w, h, gw, gh, sc.comp, seg.id, cfg.edgeThreshold)

        val m = cfg.regionMargin
        val region = Region(
            seg.minX / gw.toFloat() - m, seg.minY / gh.toFloat() - m,
            (seg.maxX + 1) / gw.toFloat() + m, (seg.maxY + 1) / gh.toFloat() + m
        ).clamped()

        return InvoiceCandidate(
            InvoiceFeatures(areaFrac, fill, logAspect, contrast, saturation, textiness),
            region
        )
    }

    /** أكبر مرشح ورقة فقط (السلوك الأصلي). null إن لم يوجد أو كان حجمه خارج المدى. */
    fun analyze(argb: IntArray, w: Int, h: Int, cfg: Config = Config()): InvoiceCandidate? {
        val sc = segment(argb, w, h, cfg, null) ?: return null
        var best: Seg? = null
        for (s in sc.segs) if (best == null || s.area > best.area) best = s
        val seg = best ?: return null
        val areaFrac = seg.area / (sc.gw * sc.gh).toFloat()
        if (areaFrac < cfg.minAreaFrac || areaFrac > cfg.maxAreaFrac) return null
        return candidateOf(argb, w, h, cfg, sc, seg)
    }

    /**
     * كل مرشحي الورق المعقولين (الأكبر أولًا، حتى [limit])، لا الأكبر فقط. الأكبر قد يكون جدارًا أو
     * سيارة بينما الفاتورة الأصغر بجانبه؛ فيقيّم الكاشف كلًّا منهم ويختار الأعلى درجة، ويتعلّم التدريب
     * من الباقي كأمثلة «ليست فاتورة».
     */
    fun analyzeAll(
        argb: IntArray, w: Int, h: Int, cfg: Config = Config(), limit: Int = 5
    ): List<InvoiceCandidate> {
        val sc = segment(argb, w, h, cfg, null) ?: return emptyList()
        val cells = (sc.gw * sc.gh).toFloat()
        return sc.segs
            .filter { it.area / cells >= cfg.minAreaFrac && it.area / cells <= cfg.maxAreaFrac }
            .sortedWith(compareByDescending<Seg> { it.area }.thenBy { it.id })
            .take(limit)
            .map { candidateOf(argb, w, h, cfg, sc, it) }
    }

    /**
     * أكبر مرشح ورقة داخل [region] فقط (منطقة رسمها المستخدم). أدنى مساحة نصف الحد العادي لأن
     * المستخدم هو من أكّد أن هنا شيئًا. null إن لم يوجد شيء فاتح داخلها.
     */
    fun analyzeWithin(
        argb: IntArray, w: Int, h: Int, region: Region, cfg: Config = Config()
    ): InvoiceCandidate? {
        val sc = segment(argb, w, h, cfg, region) ?: return null
        var best: Seg? = null
        for (s in sc.segs) if (best == null || s.area > best.area) best = s
        val seg = best ?: return null
        val areaFrac = seg.area / (sc.gw * sc.gh).toFloat()
        if (areaFrac < cfg.minAreaFrac / 2f || areaFrac > cfg.maxAreaFrac) return null
        return candidateOf(argb, w, h, cfg, sc, seg)
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

package com.meterproof.app.delivery

import kotlin.math.abs

/**
 * محلل الإطار: يحوّل صورة صغيرة (مثلًا 60×80) إلى [Feat] أرقام فقط. منطق نقي بلا أندرويد.
 *
 * - يد: بكسلات لون الجلد وفق [ColorModel].
 * - ورقة في اليد: بكسلات "ورقة" (ساطعة قليلة الصبغة) ملاصقة لليد.
 * - جسم موضوع: بكسلات ورقة أسطع من المشهد المرجعي (baseline) الذي سُجّل قبل ظهور اليد.
 *   المقارنة بالمرجع (لا بلون الورقة وحده) هي ما يمنع اعتبار باب أبيض أو جدار ساطع ورقة،
 *   وتُعوَّض بها اهتزازة الكاميرا (إزاحة حتى بكسلين) وتغيّر التعريض (إزاحة السطوع).
 * - حركة: متوسط فرق السطوع عن الإطار السابق.
 *
 * الحالة (المرجع والإطار السابق) تُصفَّر بـ [reset] عند بدء كل نافذة مراقبة.
 */
class FrameAnalyzer(
    val w: Int,
    val h: Int,
    var model: ColorModel = ColorModel(),
    private val cfg: Config = Config()
) {
    data class Config(
        val objDelta: Int = 40,
        val nearRadius: Int = 3,
        val maxShift: Int = 2,
        val baselineRate: Float = 0.15f
    )

    private val n = w * h
    private val yv = IntArray(n)
    private val skin = BooleanArray(n)
    private val paper = BooleanArray(n)
    private val near = BooleanArray(n)
    private val tmp = BooleanArray(n)
    private var prevY: IntArray? = null
    private var baseY: FloatArray? = null

    /** قناع للعرض فقط: 0 لا شيء، 1 جلد، 2 ورقة، 3 ورقة في اليد، 4 جسم جديد. لا يحتوي صورة المشهد. */
    val mask = ByteArray(n)

    fun reset() {
        prevY = null
        baseY = null
    }

    fun analyze(t: Long, argb: IntArray): Feat {
        require(argb.size >= n) { "frame too small" }

        var lumSum = 0L
        var skinN = 0
        var skinSx = 0L
        var skinSy = 0L
        for (i in 0 until n) {
            val p = argb[i]
            val y = ColorModel.yOf(p)
            val cb = ColorModel.cbOf(p)
            val cr = ColorModel.crOf(p)
            yv[i] = y
            lumSum += y
            val s = model.isSkin(y, cb, cr)
            skin[i] = s
            paper[i] = !s && model.isPaper(y, cb, cr)
            if (s) {
                skinN++
                skinSx += i % w
                skinSy += i / w
            }
        }
        dilate(skin, near, cfg.nearRadius)

        var heldN = 0
        var heldSx = 0L
        var heldSy = 0L
        for (i in 0 until n) {
            if (paper[i] && near[i]) {
                heldN++
                heldSx += i % w
                heldSy += i / w
            }
        }

        // الحركة
        var motion = 0f
        val prev = prevY
        if (prev != null) {
            var s = 0L
            for (i in 0 until n) s += abs(yv[i] - prev[i])
            motion = s / n.toFloat() / 255f
        }

        // الجسم الجديد مقارنة بالمرجع
        var objN = 0
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var shifted = false
        val base = baseY
        if (base == null) {
            baseY = FloatArray(n) { yv[it].toFloat() }
        } else {
            val s = cfg.maxShift
            var bestDx = 0
            var bestDy = 0
            var bestSad = sad(base, 0, 0, s)
            for (dy in -s..s) {
                for (dx in -s..s) {
                    if (dx == 0 && dy == 0) continue
                    val v = sad(base, dx, dy, s)
                    // لا نعتمد إزاحة إلا إن حسّنت التطابق بشكل واضح (10%)
                    if (v < bestSad - bestSad / 10) {
                        bestSad = v
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
            shifted = bestDx != 0 || bestDy != 0

            // إزاحة السطوع العامة (تغيّر التعريض) من البكسلات البعيدة عن اليد
            var dSum = 0L
            var dCnt = 0
            var yy = 0
            while (yy < h) {
                var xx = 0
                while (xx < w) {
                    val i = yy * w + xx
                    if (!near[i] && !skin[i]) {
                        dSum += yv[i] - refY(base, xx + bestDx, yy + bestDy).toInt()
                        dCnt++
                    }
                    xx += 2
                }
                yy += 2
            }
            val lumDelta = if (dCnt > 0) dSum / dCnt else 0L

            for (yy2 in 0 until h) {
                for (xx2 in 0 until w) {
                    val i = yy2 * w + xx2
                    if (paper[i] && !near[i]) {
                        val d = yv[i] - refY(base, xx2 + bestDx, yy2 + bestDy) - lumDelta
                        if (d >= cfg.objDelta) {
                            objN++
                            if (xx2 < minX) minX = xx2
                            if (xx2 > maxX) maxX = xx2
                            if (yy2 < minY) minY = yy2
                            if (yy2 > maxY) maxY = yy2
                            mask[i] = 4
                            continue
                        }
                    }
                    mask[i] = when {
                        skin[i] -> 1
                        paper[i] && near[i] -> 3
                        paper[i] -> 2
                        else -> 0
                    }
                }
            }

            // تحديث المرجع فقط حين يكون المشهد ساكنًا بلا يد ولا جسم (فلا يذوب الجسم الموضوع في المرجع)
            val skinFrac = skinN / n.toFloat()
            if (skinFrac < 0.005f && objN < n * 0.002f && !shifted && motion < 0.08f) {
                val a = cfg.baselineRate
                for (i in 0 until n) base[i] = base[i] * (1 - a) + yv[i] * a
            }
        }
        if (base == null) fillMask()

        prevY = yv.copyOf()

        val nf = n.toFloat()
        val wf = w.toFloat()
        val hf = h.toFloat()
        return Feat(
            t = t,
            skin = skinN / nf,
            skinX = if (skinN > 0) (skinSx / skinN.toFloat() + 0.5f) / wf else -1f,
            skinY = if (skinN > 0) (skinSy / skinN.toFloat() + 0.5f) / hf else -1f,
            held = heldN / nf,
            heldX = if (heldN > 0) (heldSx / heldN.toFloat() + 0.5f) / wf else -1f,
            heldY = if (heldN > 0) (heldSy / heldN.toFloat() + 0.5f) / hf else -1f,
            obj = objN / nf,
            objX = if (objN > 0) (minX + maxX + 1) / 2f / wf else -1f,
            objY = if (objN > 0) (minY + maxY + 1) / 2f / hf else -1f,
            objW = if (objN > 0) (maxX - minX + 1) / wf else 0f,
            objH = if (objN > 0) (maxY - minY + 1) / hf else 0f,
            motion = motion,
            lum = lumSum / nf / 255f
        )
    }

    private fun fillMask() {
        for (i in 0 until n) {
            mask[i] = when {
                skin[i] -> 1
                paper[i] && near[i] -> 3
                paper[i] -> 2
                else -> 0
            }
        }
    }

    private fun refY(base: FloatArray, x: Int, y: Int): Float =
        base[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]

    /** مجموع فروق السطوع (مقيسًا بالألف) بين الإطار والمرجع المُزاح، خارج منطقة اليد. */
    private fun sad(base: FloatArray, dx: Int, dy: Int, margin: Int): Long {
        var sum = 0L
        var cnt = 0
        var y = margin
        while (y < h - margin) {
            var x = margin
            while (x < w - margin) {
                val i = y * w + x
                if (!near[i] && !skin[i]) {
                    sum += abs(yv[i] - base[(y + dy) * w + (x + dx)]).toLong()
                    cnt++
                }
                x += 2
            }
            y += 2
        }
        return if (cnt > 0) sum * 1000 / cnt else Long.MAX_VALUE / 4
    }

    /** تمديد القناع بنصف قطر r (نافذة منزلقة أفقيًا ثم عموديًا). */
    private fun dilate(src: BooleanArray, dst: BooleanArray, r: Int) {
        for (y in 0 until h) {
            val row = y * w
            var cnt = 0
            for (x in 0..minOf(r, w - 1)) if (src[row + x]) cnt++
            for (x in 0 until w) {
                tmp[row + x] = cnt > 0
                val add = x + r + 1
                if (add < w && src[row + add]) cnt++
                val rem = x - r
                if (rem >= 0 && src[row + rem]) cnt--
            }
        }
        for (x in 0 until w) {
            var cnt = 0
            for (y in 0..minOf(r, h - 1)) if (tmp[y * w + x]) cnt++
            for (y in 0 until h) {
                dst[y * w + x] = cnt > 0
                val add = y + r + 1
                if (add < h && tmp[add * w + x]) cnt++
                val rem = y - r
                if (rem >= 0 && tmp[rem * w + x]) cnt--
            }
        }
    }
}

package com.meterproof.app.delivery

import com.meterproof.app.privacy.Abstraction
import com.meterproof.app.privacy.Frame
import com.meterproof.app.privacy.Region
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * كاشف الفاتورة المتعلَّم بالنوافذ (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * لماذا: الكاشف القديم يقترح «بقعة فاتحة قليلة التشبع» ثم يقيّمها بست سمات، فإن لم يجد البقعة
 * (فاتورة مائلة للصفرة، مظلَّلة، صغيرة، ملتصقة بجدار فاتح) فلا مرشح ولا تدريب. هنا لا اقتراح مسبقًا:
 * تُفحص نوافذ بأحجام وأشكال متعددة على الصورة كلها بوصف أغنى (سطوع، ملمس، تباين مع المحيط، توزيع داخلي،
 * هندسة)، ويتعلّم النموذج من المربعات التي رسمها المالك: داخل المربع = فاتورة، وخارجه = ليس فاتورة،
 * مع تعدين الأخطاء الصعبة (أعلى النوافذ درجة خارج المربع) وإعادة التدريب.
 *
 * الخصوصية: لا تُحفظ بكسلات. يُحفظ النموذج النهائي فقط (أوزان)، والصور تُقرأ أثناء التدريب من
 * ملفاتها الأصلية ثم تُرمى.
 */
object WindowFeatures {
    const val SIZE = 27
}

/** نوافذ صورة واحدة: مصفوفات مضغوطة (n×SIZE وصف، n×4 إحداثيات نسبية). */
class WindowSet(val n: Int, val vecs: FloatArray, val rects: FloatArray) {
    fun region(i: Int) = Region(rects[i * 4], rects[i * 4 + 1], rects[i * 4 + 2], rects[i * 4 + 3])
    fun vec(i: Int): FloatArray = vecs.copyOfRange(i * WindowFeatures.SIZE, (i + 1) * WindowFeatures.SIZE)
}

class WindowScene private constructor(
    val gw: Int, val gh: Int, private val cellW: Float, private val cellH: Float,
    private val iL: DoubleArray, private val iL2: DoubleArray, private val iS: DoubleArray,
    private val iE: DoubleArray, private val iX: DoubleArray, private val iY: DoubleArray,
    private val iB: DoubleArray
) {
    companion object {
        private const val EDGE_THR = 20
        private val WIDTHS = floatArrayOf(0.07f, 0.10f, 0.14f, 0.20f, 0.28f, 0.38f)
        private val ASPECTS = floatArrayOf(0.7f, 1.0f, 1.4f) // ارتفاع ÷ عرض بالبكسل

        fun from(argb: IntArray, w: Int, h: Int): WindowScene? {
            if (w < 16 || h < 16 || argb.size < w.toLong() * h) return null
            val gw = min(256, w)
            val gh = (gw.toLong() * h / w).toInt().coerceIn(8, 384)
            val means = Abstraction.blockMeans(argb, w, h, gw, gh)
            val luma = IntArray(gw * gh)
            val sat = FloatArray(gw * gh)
            for (i in luma.indices) {
                val p = means[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                luma[i] = (299 * r + 587 * g + 114 * b) / 1000
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                sat[i] = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
            }
            val st = gw + 1
            val size = st * (gh + 1)
            val l = DoubleArray(size)
            val l2 = DoubleArray(size)
            val s = DoubleArray(size)
            val e = DoubleArray(size)
            val x = DoubleArray(size)
            val y = DoubleArray(size)
            val bb = DoubleArray(size)
            for (cy in 0 until gh) {
                var rl = 0.0; var rl2 = 0.0; var rs = 0.0; var re = 0.0; var rx = 0.0; var ry = 0.0; var rb = 0.0
                for (cx in 0 until gw) {
                    val c = cy * gw + cx
                    val lv = luma[c]
                    val gx = if (cx < gw - 1) abs(luma[c + 1] - lv) else 0
                    val gy = if (cy < gh - 1) abs(luma[c + gw] - lv) else 0
                    rl += lv; rl2 += lv.toDouble() * lv; rs += sat[c]
                    re += if (gx + gy > EDGE_THR) 1.0 else 0.0
                    rx += gx; ry += gy
                    rb += if (lv > 140 && sat[c] < 0.30f) 1.0 else 0.0
                    val k = (cy + 1) * st + (cx + 1)
                    val up = cy * st + (cx + 1)
                    l[k] = l[up] + rl; l2[k] = l2[up] + rl2; s[k] = s[up] + rs
                    e[k] = e[up] + re; x[k] = x[up] + rx; y[k] = y[up] + ry; bb[k] = bb[up] + rb
                }
            }
            return WindowScene(gw, gh, w / gw.toFloat(), h / gh.toFloat(), l, l2, s, e, x, y, bb)
        }
    }

    private fun sum(a: DoubleArray, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val st = gw + 1
        return a[y1 * st + x1] - a[y0 * st + x1] - a[y1 * st + x0] + a[y0 * st + x0]
    }

    /** وصف منطقة (إحداثيات نسبية). null إن كانت أصغر من 3 خلايا في أي بُعد. */
    fun describe(r: Region): FloatArray? {
        val x0 = (r.left * gw).toInt().coerceIn(0, gw)
        val y0 = (r.top * gh).toInt().coerceIn(0, gh)
        val x1 = Math.ceil((r.right * gw).toDouble()).toInt().coerceIn(0, gw)
        val y1 = Math.ceil((r.bottom * gh).toDouble()).toInt().coerceIn(0, gh)
        return describeCells(x0, y0, x1, y1)
    }

    private fun describeCells(x0: Int, y0: Int, x1: Int, y1: Int): FloatArray? {
        val cw = x1 - x0
        val ch = y1 - y0
        if (cw < 3 || ch < 3) return null
        val area = (cw * ch).toDouble()
        val sL = sum(iL, x0, y0, x1, y1)
        val sL2 = sum(iL2, x0, y0, x1, y1)
        val sS = sum(iS, x0, y0, x1, y1)
        val sE = sum(iE, x0, y0, x1, y1)
        val sX = sum(iX, x0, y0, x1, y1)
        val sY = sum(iY, x0, y0, x1, y1)
        val sB = sum(iB, x0, y0, x1, y1)
        val mL = sL / area
        val sd = sqrt(max(0.0, sL2 / area - mL * mL))
        val edge = sE / area

        // الحلقة المحيطة: النافذة موسَّعة نصف أبعادها من كل جهة، مطروحًا منها النافذة نفسها
        val ex = max(1, cw / 2)
        val ey = max(1, ch / 2)
        val ox0 = max(0, x0 - ex); val oy0 = max(0, y0 - ey)
        val ox1 = min(gw, x1 + ex); val oy1 = min(gh, y1 + ey)
        val ringArea = ((ox1 - ox0) * (oy1 - oy0)).toDouble() - area
        val ringL: Double
        val ringS: Double
        val ringE: Double
        if (ringArea > 0) {
            ringL = (sum(iL, ox0, oy0, ox1, oy1) - sL) / ringArea
            ringS = (sum(iS, ox0, oy0, ox1, oy1) - sS) / ringArea
            ringE = (sum(iE, ox0, oy0, ox1, oy1) - sE) / ringArea
        } else {
            ringL = mL; ringS = sS / area; ringE = edge
        }

        val v = FloatArray(WindowFeatures.SIZE)
        var k = 0
        val wf = cw / gw.toFloat()
        val hf = ch / gh.toFloat()
        v[k++] = wf
        v[k++] = hf
        v[k++] = wf * hf
        v[k++] = ln((cw * cellW) / (ch * cellH))
        v[k++] = (x0 + x1) / 2f / gw
        v[k++] = (y0 + y1) / 2f / gh
        v[k++] = (mL / 255.0).toFloat()
        v[k++] = (sd / 128.0).toFloat()
        v[k++] = (sS / area).toFloat()
        v[k++] = edge.toFloat()
        v[k++] = (sX / (sX + sY + 1e-6)).toFloat()
        v[k++] = ((sX + sY) / area / 510.0).toFloat()
        v[k++] = (sB / area).toFloat()
        v[k++] = (ringL / 255.0).toFloat()
        v[k++] = ringS.toFloat()
        v[k++] = ringE.toFloat()
        v[k++] = ((mL - ringL) / 255.0).toFloat()
        v[k++] = ((sS / area) - ringS).toFloat()
        v[k++] = (edge - ringE).toFloat()
        // أرباع النافذة: السطوع ثم كثافة الحواف (توزيع داخلي: ورقة نصها في الوسط مثلًا)
        val mx = (x0 + x1) / 2
        val my = (y0 + y1) / 2
        val quads = arrayOf(intArrayOf(x0, y0, mx, my), intArrayOf(mx, y0, x1, my),
            intArrayOf(x0, my, mx, y1), intArrayOf(mx, my, x1, y1))
        for (q in quads) {
            val a = max(1, (q[2] - q[0]) * (q[3] - q[1])).toDouble()
            v[k++] = (sum(iL, q[0], q[1], q[2], q[3]) / a / 255.0).toFloat()
        }
        for (q in quads) {
            val a = max(1, (q[2] - q[0]) * (q[3] - q[1])).toDouble()
            v[k++] = (sum(iE, q[0], q[1], q[2], q[3]) / a).toFloat()
        }
        return v
    }

    /** كل نوافذ الشبكة متعددة الأحجام والأشكال (خطوة 60% من البُعد). */
    fun windows(): WindowSet {
        val vecs = ArrayList<FloatArray>()
        val rects = ArrayList<Float>()
        for (wf in WIDTHS) {
            for (asp in ASPECTS) {
                val cw = max(3, Math.round(wf * gw))
                val ch = max(3, Math.round(cw * cellW * asp / cellH))
                if (ch > gh * 0.9f || cw > gw * 0.9f) continue
                val sx = max(1, (cw * 0.6f).toInt())
                val sy = max(1, (ch * 0.6f).toInt())
                var y = 0
                while (y + ch <= gh) {
                    var x = 0
                    while (x + cw <= gw) {
                        val d = describeCells(x, y, x + cw, y + ch)
                        if (d != null) {
                            vecs.add(d)
                            rects.add(x / gw.toFloat()); rects.add(y / gh.toFloat())
                            rects.add((x + cw) / gw.toFloat()); rects.add((y + ch) / gh.toFloat())
                        }
                        x += sx
                    }
                    y += sy
                }
            }
        }
        val flat = FloatArray(vecs.size * WindowFeatures.SIZE)
        for (i in vecs.indices) System.arraycopy(vecs[i], 0, flat, i * WindowFeatures.SIZE, WindowFeatures.SIZE)
        return WindowSet(vecs.size, flat, rects.toFloatArray())
    }
}

/** درجات الأداء على الصور المعلَّمة (بالصور لا بالنوافذ). */
data class WindowMetrics(val precision: Float, val recall: Float, val uncertainRecall: Float)

/** انحدار لوجستي على السمات الموحَّدة ومربعاتها (قدرة غير خطية بسيطة). */
class WindowModel(
    val mean: FloatArray,
    val std: FloatArray,
    val weights: FloatArray, // 2*SIZE
    val bias: Float,
    val acceptThreshold: Float,
    val uncertainThreshold: Float,
    val trainedAt: Long,
    val invoiceImages: Int,
    val noneImages: Int,
    val metrics: WindowMetrics
) {
    init {
        require(mean.size == WindowFeatures.SIZE && std.size == WindowFeatures.SIZE &&
            weights.size == 2 * WindowFeatures.SIZE) { "bad window model size" }
    }

    fun score(vec: FloatArray, off: Int = 0): Float {
        var z = bias.toDouble()
        val n = WindowFeatures.SIZE
        for (i in 0 until n) {
            val s = if (std[i] > 1e-6f) std[i] else 1f
            val x = ((vec[off + i] - mean[i]) / s).coerceIn(-6f, 6f)
            z += weights[i] * x + weights[n + i] * (x * x * 0.25f)
        }
        val p = InvoiceTrainer.sigmoid(z).toFloat()
        return if (p.isFinite()) p else 0f
    }

    val id: String by lazy { WindowModelCodec.fingerprint(this) }
}

object WindowModelCodec {
    private const val VERSION = "WM1"

    fun encode(m: WindowModel): String = listOf(
        VERSION, join(m.mean), join(m.std), join(m.weights), m.bias.toString(),
        m.acceptThreshold.toString(), m.uncertainThreshold.toString(), m.trainedAt.toString(),
        m.invoiceImages.toString(), m.noneImages.toString(),
        m.metrics.precision.toString(), m.metrics.recall.toString(), m.metrics.uncertainRecall.toString()
    ).joinToString(";")

    fun decode(s: String): WindowModel? = try {
        val p = s.trim().split(';')
        if (p.size != 13 || p[0] != VERSION) null
        else {
            val m = WindowModel(
                floats(p[1]), floats(p[2]), floats(p[3]), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(),
                p[7].toLong(), p[8].toInt(), p[9].toInt(),
                WindowMetrics(p[10].toFloat(), p[11].toFloat(), p[12].toFloat())
            )
            val all = (m.mean + m.std + m.weights).toList() + listOf(m.bias, m.acceptThreshold, m.uncertainThreshold)
            if (all.any { !it.isFinite() }) null else m
        }
    } catch (e: Exception) {
        null
    }

    fun fingerprint(m: WindowModel): String {
        val canonical = listOf(join(m.mean), join(m.std), join(m.weights), m.bias.toString(),
            m.acceptThreshold.toString(), m.uncertainThreshold.toString()).joinToString(";")
        val d = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "w" + d.take(3).joinToString("") { "%02x".format(it) }
    }

    private fun join(v: FloatArray) = v.joinToString(",") { it.toString() }
    private fun floats(s: String): FloatArray = s.split(',').map { it.toFloat() }.toFloatArray()
}

/** صورة تدريب جاهزة: نوافذها مع وسم كل نافذة (1 فاتورة، 0 ليست، -1 غامضة فتُهمل). */
class TrainImage(
    val id: String,
    val set: WindowSet,
    val label: ByteArray,
    val exactPos: List<FloatArray>,
    val exactNeg: List<FloatArray>,
    val boxes: List<Region>,
    val fullyNegative: Boolean
) {
    val hasInvoice get() = boxes.isNotEmpty()
    /** هل تدخل في التقييم بالصور: صورة فاتورة بمربع، أو صورة «بلا فاتورة» كاملة. */
    val evaluable get() = hasInvoice || fullyNegative

    companion object {
        fun iou(a: Region, b: Region): Float {
            val l = max(a.left, b.left); val t = max(a.top, b.top)
            val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
            val inter = max(0f, r - l) * max(0f, bt - t)
            val u = a.area + b.area - inter
            return if (u <= 0f) 0f else inter / u
        }

        /** نسبة مساحة [a] الواقعة داخل [b]. */
        fun inside(a: Region, b: Region): Float {
            val l = max(a.left, b.left); val t = max(a.top, b.top)
            val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
            val inter = max(0f, r - l) * max(0f, bt - t)
            return if (a.area <= 0f) 0f else inter / a.area
        }

        /**
         * [recognize] مربعات «فاتورة» رسمها المالك، [ignore] مربعات «تجاهل»، [fullyNegative] الصورة كلها بلا فاتورة.
         * ما يخرج عن المربعين في صورة فيها فاتورة معلَّمة يُعدّ خلفية.
         */
        fun build(
            id: String, scene: WindowScene, recognize: List<Region>, ignore: List<Region>, fullyNegative: Boolean
        ): TrainImage {
            val set = scene.windows()
            val label = ByteArray(set.n)
            for (i in 0 until set.n) {
                val w = set.region(i)
                label[i] = when {
                    fullyNegative -> 0
                    else -> {
                        val bestIou = recognize.maxOfOrNull { iou(w, it) } ?: 0f
                        val bestIn = recognize.maxOfOrNull { inside(w, it) } ?: 0f
                        val ign = ignore.any { iou(w, it) >= 0.4f || inside(w, it) > 0.5f }
                        when {
                            bestIou >= 0.6f -> 1
                            bestIou > 0.15f || bestIn > 0.5f -> -1
                            ign -> 0
                            recognize.isNotEmpty() -> 0
                            else -> -1
                        }
                    }
                }
            }
            val ep = if (fullyNegative) emptyList() else recognize.mapNotNull { scene.describe(it) }
            val en = ignore.mapNotNull { scene.describe(it) }
            return TrainImage(id, set, label, ep, en, if (fullyNegative) emptyList() else recognize, fullyNegative)
        }
    }
}

object WindowTrainer {

    data class Config(
        val minInvoiceImages: Int = 8,
        val minNoneImages: Int = 8,
        val randomNegPerImage: Int = 50,
        val minedPerRound: Int = 30,
        val rounds: Int = 3,
        val epochs: Int = 250,
        val learningRate: Double = 0.5,
        val l2: Double = 0.01,
        val exactWeight: Double = 3.0,
        val targetPrecision: Double = 0.95,
        val targetRecall: Double = 0.90,
        val minRecallAtAccept: Double = 0.80,
        val hitIou: Float = 0.3f,
        val holdoutEvery: Int = 4
    )

    class Problem(val imageId: String, val kind: Kind, val score: Float) {
        enum class Kind { MISSED, FALSE_ACCEPT }
    }

    /** مقاييس الصور المحجوزة: لم يرها التدريب ولا اختيار العتبة. */
    data class Holdout(val invoiceImages: Int, val noneImages: Int, val detected: Int, val falseAccepts: Int)

    class Report(
        val invoiceImages: Int,
        val noneImages: Int,
        /** على صور التدريب نفسها (تفاؤلي): كم فاتورة أُصيبت وكم صورة بلا فاتورة قُبلت خطأً. */
        val hit: Int,
        val falseAccepts: Int,
        val holdout: Holdout?,
        val problems: List<Problem>
    )

    sealed class Result {
        data class Ready(val model: WindowModel, val report: Report) : Result()
        data class NotReady(val reasons: List<String>, val report: Report?) : Result()
    }

    private class Sample(val x: FloatArray, val y: Int, val w: Double)

    private class ImageEval(val imageId: String, val top: Float, val hit: Boolean, val isInvoice: Boolean)

    fun train(images: List<TrainImage>, now: Long, cfg: Config = Config()): Result {
        val evalImgs = images.filter { it.evaluable }
        val inv = evalImgs.count { it.hasInvoice }
        val none = evalImgs.count { it.fullyNegative }
        val reasons = ArrayList<String>()
        if (inv < cfg.minInvoiceImages) reasons.add("NEED_MORE_INVOICE_IMAGES")
        if (none < cfg.minNoneImages) reasons.add("NEED_MORE_NONE_IMAGES")
        if (reasons.isNotEmpty()) return Result.NotReady(reasons, null)

        // قياس على صور محجوزة (كل صورة رابعة من كل فئة)، قبل النموذج النهائي
        val holdout = holdout(images, now, cfg)

        val fitted = fitAll(images, now, cfg) ?: return Result.NotReady(listOf("TRAIN_FAILED"), null)
        val (model0, evals) = fitted
        val th = chooseThresholds(evals, cfg)
        val problems = ArrayList<Problem>()
        var hit = 0
        var fa = 0
        val accept = th?.accept ?: 0.5f
        for (e in evals) {
            if (e.isInvoice) {
                if (e.hit && e.top >= accept) hit++ else problems.add(Problem(e.imageId, Problem.Kind.MISSED, e.top))
            } else if (e.top >= accept) {
                fa++; problems.add(Problem(e.imageId, Problem.Kind.FALSE_ACCEPT, e.top))
            }
        }
        val report = Report(inv, none, hit, fa, holdout, problems)
        if (th == null) return Result.NotReady(listOf("LOW_PRECISION"), report)

        val (acc, unc, prec, rec, urec) = th
        if (rec < cfg.minRecallAtAccept) return Result.NotReady(listOf("LOW_RECALL"), report)
        if (holdout != null && holdout.falseAccepts > holdout.noneImages / 8) {
            return Result.NotReady(listOf("HOLDOUT_FALSE_ACCEPT"), report)
        }
        val model = WindowModel(
            model0.mean, model0.std, model0.weights, model0.bias, acc, unc, now, inv, none,
            WindowMetrics(prec, rec, urec)
        )
        return Result.Ready(model, report)
    }

    private data class Th(val accept: Float, val unc: Float, val prec: Float, val rec: Float, val urec: Float)

    /** (أدنى عتبة تحقق الدقة المستهدفة) مع عتبة الشك؛ null إن لم تتحقق دقة الهدف. */
    private fun chooseThresholds(evals: List<ImageEval>, cfg: Config): Th? {
        val invTotal = evals.count { it.isInvoice }
        if (invTotal == 0) return null
        fun events(t: Float) = evals.count { it.top >= t }
        fun correct(t: Float) = evals.count { it.isInvoice && it.hit && it.top >= t }
        fun prec(t: Float): Double = events(t).let { if (it == 0) 0.0 else correct(t).toDouble() / it }
        fun rec(t: Float): Double = correct(t).toDouble() / invTotal
        val cands = evals.map { it.top }.distinct().sorted()
        var accept: Float? = null
        for (t in cands) {
            if (events(t) < max(3, invTotal / 2)) break
            if (prec(t) >= cfg.targetPrecision) { accept = t; break }
        }
        val a = accept ?: return null
        var unc = a
        for (t in cands.asReversed()) {
            if (rec(t) >= cfg.targetRecall) { unc = min(t, a); break }
        }
        return Th(a, unc, prec(a).toFloat(), rec(a).toFloat(), rec(unc).toFloat())
    }

    private fun holdout(images: List<TrainImage>, now: Long, cfg: Config): Holdout? {
        val inv = images.filter { it.hasInvoice }
        val neg = images.filter { it.fullyNegative }
        fun split(l: List<TrainImage>) = l.withIndex().partition { (i, _) -> i % cfg.holdoutEvery == cfg.holdoutEvery - 1 }
        val (hi, ti) = split(inv)
        val (hn, tn) = split(neg)
        if (hi.isEmpty() || hn.isEmpty()) return null
        val trainSet = (ti + tn).map { it.value } + images.filter { !it.evaluable }
        if (ti.size < cfg.minInvoiceImages || tn.size < cfg.minNoneImages) return null
        val fitted = fitAll(trainSet, now, cfg) ?: return null
        val th = chooseThresholds(fitted.second, cfg) ?: return Holdout(hi.size, hn.size, 0, 0)
        val model = fitted.first
        val accept = th.accept
        val ev = (hi + hn).map { evalImage(model, it.value) }
        val detected = ev.count { it.isInvoice && it.hit && it.top >= accept }
        val fa = ev.count { (!it.isInvoice && it.top >= accept) || (it.isInvoice && !it.hit && it.top >= accept) }
        return Holdout(hi.size, hn.size, detected, fa)
    }

    private fun evalImage(model: WindowModel, img: TrainImage): ImageEval {
        val n = img.set.n
        var best = -1f
        var bi = -1
        for (i in 0 until n) {
            val s = model.score(img.set.vecs, i * WindowFeatures.SIZE)
            if (s > best) { best = s; bi = i }
        }
        val hit = bi >= 0 && img.boxes.any { TrainImage.iou(img.set.region(bi), it) >= 0.3f }
        return ImageEval(img.id, max(best, 0f), hit, img.hasInvoice)
    }

    /** يدرّب بالتعدين الصعب ويعيد النموذج (بعتبات مؤقتة) وتقييم كل صورة قابلة للتقييم. */
    private fun fitAll(images: List<TrainImage>, now: Long, cfg: Config): Pair<WindowModel, List<ImageEval>>? {
        val d = WindowFeatures.SIZE
        val samples = ArrayList<Sample>()
        val inSet = HashSet<Long>() // (صورة، نافذة) مضافة سلفًا
        for ((ii, img) in images.withIndex()) {
            val negIdx = (0 until img.set.n).filter { img.label[it] == 0.toByte() }
            val step = max(1, negIdx.size / cfg.randomNegPerImage)
            var j = 0
            while (j < negIdx.size) {
                val w = negIdx[j]
                samples.add(Sample(img.set.vec(w), 0, 1.0)); inSet.add(key(ii, w)); j += step
            }
            for (i in 0 until img.set.n) if (img.label[i] == 1.toByte()) {
                samples.add(Sample(img.set.vec(i), 1, 1.0)); inSet.add(key(ii, i))
            }
            for (v in img.exactPos) samples.add(Sample(v, 1, cfg.exactWeight))
            for (v in img.exactNeg) samples.add(Sample(v, 0, cfg.exactWeight))
        }
        if (samples.none { it.y == 1 } || samples.none { it.y == 0 }) return null

        // التوحيد من العينات الأولية ويبقى ثابتًا في كل الجولات
        val mean = FloatArray(d)
        val std = FloatArray(d)
        for (k in 0 until d) {
            var s = 0.0
            for (x in samples) s += x.x[k]
            val m = s / samples.size
            var v = 0.0
            for (x in samples) v += (x.x[k] - m) * (x.x[k] - m)
            mean[k] = m.toFloat()
            std[k] = max(sqrt(v / samples.size), 1e-3).toFloat()
        }

        var model = fit(samples, mean, std, cfg)
        repeat(cfg.rounds) {
            var added = 0
            for ((ii, img) in images.withIndex()) {
                val cand = ArrayList<Pair<Float, Int>>()
                for (i in 0 until img.set.n) {
                    if (img.label[i] != 0.toByte() || key(ii, i) in inSet) continue
                    val s = model.score(img.set.vecs, i * d)
                    if (s > 0.1f) cand.add(s to i) // الصعبة فقط: ما يخدع النموذج فعلًا
                }
                cand.sortByDescending { it.first }
                for ((_, i) in cand.take(cfg.minedPerRound)) {
                    samples.add(Sample(img.set.vec(i), 0, 1.0)); inSet.add(key(ii, i)); added++
                }
            }
            if (added == 0) return@repeat
            model = fit(samples, mean, std, cfg)
        }
        val evals = images.filter { it.evaluable }.map { evalImage(model, it) }
        return model to evals
    }

    private fun key(img: Int, win: Int) = img.toLong() * 1_000_000L + win

    private fun fit(samples: List<Sample>, mean: FloatArray, std: FloatArray, cfg: Config): WindowModel {
        val d = WindowFeatures.SIZE
        val dim = 2 * d
        val n = samples.size
        val z = Array(n) { i ->
            val x = samples[i].x
            DoubleArray(dim).also { out ->
                for (k in 0 until d) {
                    val v = ((x[k] - mean[k]) / std[k]).toDouble().coerceIn(-6.0, 6.0)
                    out[k] = v
                    out[d + k] = v * v * 0.25
                }
            }
        }
        var wPos = 0.0
        var wNeg = 0.0
        for (s in samples) if (s.y == 1) wPos += s.w else wNeg += s.w
        val cPos = n / (2.0 * wPos)
        val cNeg = n / (2.0 * wNeg)
        val w = DoubleArray(dim)
        var b = 0.0
        val g = DoubleArray(dim)
        repeat(cfg.epochs) {
            java.util.Arrays.fill(g, 0.0)
            var gb = 0.0
            for (i in 0 until n) {
                var s = b
                val zi = z[i]
                for (k in 0 until dim) s += w[k] * zi[k]
                val p = InvoiceTrainer.sigmoid(s)
                val sw = samples[i].w * (if (samples[i].y == 1) cPos else cNeg)
                val e = (p - samples[i].y) * sw
                for (k in 0 until dim) g[k] += e * zi[k]
                gb += e
            }
            for (k in 0 until dim) w[k] -= cfg.learningRate * (g[k] / n + cfg.l2 * w[k])
            b -= cfg.learningRate * (gb / n)
        }
        return WindowModel(mean, std, FloatArray(dim) { w[it].toFloat() }, b.toFloat(), 0.5f, 0.5f, 0L, 0, 0,
            WindowMetrics(0f, 0f, 0f))
    }
}

/** كاشف الفاتورة بالنوافذ لإطار حي (نفس واجهة [DeliveryResult] التي تستعملها البوابة). */
class WindowDetector(
    private val model: WindowModel,
    private val prior: PlacementPrior = PlacementPrior.NONE
) {
    fun detect(frame: Frame): DeliveryResult {
        val px = frame.argb()
        try {
            return detect(px, frame.width, frame.height)
        } finally {
            px.fill(0)
        }
    }

    fun detect(px: IntArray, w: Int, h: Int): DeliveryResult {
        val scene = WindowScene.from(px, w, h) ?: return DeliveryResult(DeliveryState.NONE, 0f, null, model.id)
        val set = scene.windows()
        if (set.n == 0) return DeliveryResult(DeliveryState.NONE, 0f, null, model.id)
        var best = -1f
        var bi = 0
        for (i in 0 until set.n) {
            val s = model.score(set.vecs, i * WindowFeatures.SIZE)
            if (s > best) { best = s; bi = i }
        }
        var region = set.region(bi)
        // تحسين محلي: إزاحات وتكبير/تصغير حول أفضل نافذة لإطار أضيق
        var score = best
        val rw = region.right - region.left
        val rh = region.bottom - region.top
        for (sc in floatArrayOf(0.8f, 0.9f, 1.0f, 1.12f, 1.25f)) {
            for (dx in floatArrayOf(-0.25f, 0f, 0.25f)) {
                for (dy in floatArrayOf(-0.25f, 0f, 0.25f)) {
                    val cx = (region.left + region.right) / 2 + dx * rw
                    val cy = (region.top + region.bottom) / 2 + dy * rh
                    val cand = Region(cx - rw * sc / 2, cy - rh * sc / 2, cx + rw * sc / 2, cy + rh * sc / 2).clamped()
                    val v = scene.describe(cand) ?: continue
                    val s = model.score(v)
                    if (s > score) { score = s; region = cand }
                }
            }
        }
        var state = when {
            score >= model.acceptThreshold -> DeliveryState.DETECTED
            score >= model.uncertainThreshold -> DeliveryState.UNCERTAIN
            else -> DeliveryState.NONE
        }
        if (state == DeliveryState.DETECTED && !prior.allowsDetected(region)) state = DeliveryState.UNCERTAIN
        val m = 0.02f
        val out = Region(region.left - m, region.top - m, region.right + m, region.bottom + m).clamped()
        return DeliveryResult(state, score, out, model.id, prior.placementOf(out))
    }
}

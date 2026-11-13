package com.meterproof.app.delivery

import com.meterproof.app.privacy.Region
import java.security.MessageDigest
import kotlin.math.max

/**
 * المرحلة الثانية (منطق نقي): مدقِّق دلالي فوق كاشف النوافذ.
 *
 * كاشف النوافذ سريع ويقترح أفضل 4 أماكن؛ ثم نموذج رؤية جاهز على الجهاز (ML Kit، مضمَّن، دون إنترنت)
 * يصف محتوى كل مكان بدرجات تصنيفات عامة (ورقة، نص، باب، جدار، سيارة، …) فيتعلم المدقِّق من مربعاتك
 * أي توليفة دلالية تعني «فاتورة». هذا يقلل القبول الخاطئ ويقلل الأمثلة اللازمة.
 *
 * - الترشيح عند التدريب بتقاطع الطيّات (كل صورة تُرشَّح بنموذج لم يرها)، فيطابق ما يحدث حيًا
 *   وتكون المقاييس صادقة (خارج العينة، على مستوى الصورة).
 * - لا يُعتمد المدقِّق إلا إن بلغ الدقة المطلوبة بتلك المقاييس. وإن فشل وصف المرشح حيًا لا يُرفع شيء:
 *   أقصى ما يُعطى «غير مؤكد».
 * - لا بكسلات تُحفظ: أوزان فقط.
 */
object Semantic {
    /** أكبر رقم تصنيف في نموذج ML Kit المضمَّن أقل من هذا (447 تصنيفًا). */
    const val DIM = 512
}

/** يصف منطقة من إطار بمتجه دلالي بطول [Semantic.DIM] (أو null عند الفشل). تنفيذه في طبقة أندرويد. */
fun interface SemanticProvider {
    fun vector(argb: IntArray, w: Int, h: Int, region: Region): FloatArray?
}

/** مرشح من المرحلة الأولى: منطقته ودرجته، وهل أصاب فاتورة معلَّمة (للتدريب فقط). */
class Cand(val region: Region, val stage1: Float, val hit: Boolean)

/** خطة تدريب صورة واحدة: مرشحوها بالتقاطع، ومربعاتها الدقيقة كأمثلة إيجابية إضافية للمدقِّق فقط. */
class Plan(val cands: List<Cand>, val extra: List<Cand>)

object Proposals {
    /** أعلى [k] نوافذ درجةً لا يتداخل بعضها فوق [nmsIou]. */
    fun top(model: WindowModel, set: WindowSet, k: Int, nmsIou: Float = 0.3f): List<Cand> {
        val d = WindowFeatures.SIZE
        val scores = FloatArray(set.n) { model.score(set.vecs, it * d) }
        val order = (0 until set.n).sortedByDescending { scores[it] }
        val out = ArrayList<Cand>()
        for (i in order) {
            if (out.size >= k) break
            val r = set.region(i)
            if (out.any { TrainImage.iou(it.region, r) >= nmsIou }) continue
            out.add(Cand(r, scores[i], false))
        }
        return out
    }
}

class VerifierModel(
    val weights: FloatArray, // Semantic.DIM
    val stageWeight: Float,
    val bias: Float,
    val acceptThreshold: Float,
    val uncertainThreshold: Float,
    val trainedAt: Long,
    val invoiceImages: Int,
    val noneImages: Int,
    val metrics: WindowMetrics
) {
    init { require(weights.size == Semantic.DIM) { "bad verifier size" } }

    fun score(sem: FloatArray, stage1: Float): Float {
        var z = bias + stageWeight * stage1.toDouble()
        for (i in 0 until Semantic.DIM) z += weights[i] * sem[i]
        val p = InvoiceTrainer.sigmoid(z).toFloat()
        return if (p.isFinite()) p else 0f
    }

    val id: String by lazy { VerifierCodec.fingerprint(this) }
}

object VerifierCodec {
    private const val VERSION = "VM1"

    fun encode(m: VerifierModel): String = listOf(
        VERSION, m.weights.joinToString(",") { it.toString() }, m.stageWeight.toString(), m.bias.toString(),
        m.acceptThreshold.toString(), m.uncertainThreshold.toString(), m.trainedAt.toString(),
        m.invoiceImages.toString(), m.noneImages.toString(),
        m.metrics.precision.toString(), m.metrics.recall.toString(), m.metrics.uncertainRecall.toString()
    ).joinToString(";")

    fun decode(s: String): VerifierModel? = try {
        val p = s.trim().split(';')
        if (p.size != 12 || p[0] != VERSION) null
        else {
            val w = p[1].split(',').map { it.toFloat() }.toFloatArray()
            val m = VerifierModel(
                w, p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toLong(),
                p[7].toInt(), p[8].toInt(), WindowMetrics(p[9].toFloat(), p[10].toFloat(), p[11].toFloat())
            )
            val all = m.weights.toList() + listOf(m.stageWeight, m.bias, m.acceptThreshold, m.uncertainThreshold)
            if (all.any { !it.isFinite() }) null else m
        }
    } catch (e: Exception) {
        null
    }

    fun fingerprint(m: VerifierModel): String {
        val c = listOf(m.weights.joinToString(",") { it.toString() }, m.stageWeight.toString(), m.bias.toString(),
            m.acceptThreshold.toString(), m.uncertainThreshold.toString()).joinToString(";")
        val d = MessageDigest.getInstance("SHA-256").digest(c.toByteArray(Charsets.UTF_8))
        return "v" + d.take(3).joinToString("") { "%02x".format(it) }
    }
}

/** مرشح مع متجهه الدلالي، جاهز للتدريب. */
class VCand(val stage1: Float, val hit: Boolean, val sem: FloatArray)

class VImage(
    val id: String,
    val hasInvoice: Boolean,
    val fold: Int,
    val cands: List<VCand>,
    val extraPos: List<VCand>
)

object TwoStageTrainer {

    const val FOLDS = 4
    const val CANDIDATES = 4

    sealed class Result {
        data class Ready(val model: VerifierModel, val report: WindowTrainer.Report) : Result()
        data class NotReady(val reasons: List<String>, val report: WindowTrainer.Report?) : Result()
    }

    /** طيّة كل صورة قابلة للتقييم: عدّاد مستقل لكل فئة فيتوازن التوزيع. */
    fun folds(images: List<TrainImage>): IntArray {
        val out = IntArray(images.size) { -1 }
        var ci = 0
        var cn = 0
        for ((i, img) in images.withIndex()) {
            when {
                img.hasInvoice -> out[i] = ci++ % FOLDS
                img.fullyNegative -> out[i] = cn++ % FOLDS
            }
        }
        return out
    }

    /**
     * ترشيح بتقاطع الطيّات: مرشحو كل صورة من نموذج نوافذ لم يرها. تعيد null إن تعذّر تدريب أي طيّة
     * (صور قليلة). الصور غير القابلة للتقييم تأخذ خطة فارغة.
     */
    fun plan(images: List<TrainImage>, now: Long, cfg: WindowTrainer.Config = WindowTrainer.Config()): List<Plan>? {
        val fold = folds(images)
        val plans = MutableList(images.size) { Plan(emptyList(), emptyList()) }
        for (f in 0 until FOLDS) {
            val trainSet = images.filterIndexed { i, _ -> fold[i] != f }
            val fitted = WindowTrainer.fitAll(trainSet, now, cfg) ?: return null
            val model = fitted.first
            for ((i, img) in images.withIndex()) {
                if (fold[i] != f) continue
                val cands = Proposals.top(model, img.set, CANDIDATES).map { c ->
                    Cand(c.region, c.stage1, img.boxes.any { TrainImage.iou(c.region, it) >= cfg.hitIou })
                }
                val extra = img.boxes.mapIndexed { bi, box ->
                    Cand(box, model.score(img.exactPos[bi]), true)
                }
                plans[i] = Plan(cands, extra)
            }
        }
        return plans
    }

    private class Row(val z: FloatArray, val y: Int, val w: Double)

    private fun rowsOf(images: List<VImage>, exactWeight: Double): List<Row> {
        val rows = ArrayList<Row>()
        for (im in images) {
            for (c in im.cands) rows.add(Row(feature(c), if (c.hit) 1 else 0, 1.0))
            for (c in im.extraPos) rows.add(Row(feature(c), 1, exactWeight))
        }
        return rows
    }

    private fun feature(c: VCand): FloatArray {
        val z = FloatArray(Semantic.DIM + 1)
        System.arraycopy(c.sem, 0, z, 0, Semantic.DIM)
        z[Semantic.DIM] = c.stage1
        return z
    }

    /** انحدار لوجستي متوازن الفئتين مع L2؛ null إن غابت فئة. */
    private fun fit(rows: List<Row>, cfg: WindowTrainer.Config): FloatArray? {
        var wp = 0.0
        var wn = 0.0
        for (r in rows) if (r.y == 1) wp += r.w else wn += r.w
        if (wp == 0.0 || wn == 0.0) return null
        val n = rows.size
        val cp = n / (2.0 * wp)
        val cn = n / (2.0 * wn)
        val dim = Semantic.DIM + 1
        val w = DoubleArray(dim)
        var b = 0.0
        val g = DoubleArray(dim)
        repeat(300) {
            java.util.Arrays.fill(g, 0.0)
            var gb = 0.0
            for (r in rows) {
                var s = b
                for (k in 0 until dim) s += w[k] * r.z[k]
                val e = (InvoiceTrainer.sigmoid(s) - r.y) * r.w * (if (r.y == 1) cp else cn)
                for (k in 0 until dim) if (r.z[k] != 0f) g[k] += e * r.z[k]
                gb += e
            }
            for (k in 0 until dim) w[k] -= 0.5 * (g[k] / n + 0.02 * w[k])
            b -= 0.5 * (gb / n)
        }
        return FloatArray(dim + 1) { if (it < dim) w[it].toFloat() else b.toFloat() }
    }

    private fun scoreRow(m: FloatArray, c: VCand): Float {
        var z = m[Semantic.DIM + 1].toDouble() + m[Semantic.DIM] * c.stage1
        for (i in 0 until Semantic.DIM) z += m[i] * c.sem[i]
        return InvoiceTrainer.sigmoid(z).toFloat()
    }

    fun train(
        images: List<VImage>, now: Long, cfg: WindowTrainer.Config = WindowTrainer.Config()
    ): Result {
        val inv = images.count { it.hasInvoice }
        val none = images.count { !it.hasInvoice }
        val reasons = ArrayList<String>()
        if (inv < cfg.minInvoiceImages) reasons.add("NEED_MORE_INVOICE_IMAGES")
        if (none < cfg.minNoneImages) reasons.add("NEED_MORE_NONE_IMAGES")
        if (reasons.isNotEmpty()) return Result.NotReady(reasons, null)

        // تقييم خارج العينة على مستوى الصورة: الأعلى درجة من المرشحين يقرّر
        val evals = ArrayList<WindowTrainer.ImageEval>()
        for (f in 0 until FOLDS) {
            val m = fit(rowsOf(images.filter { it.fold != f }, cfg.exactWeight), cfg)
                ?: return Result.NotReady(listOf("TRAIN_FAILED"), null)
            for (im in images.filter { it.fold == f }) {
                var best = -1f
                var hit = false
                for (c in im.cands) {
                    val s = scoreRow(m, c)
                    if (s > best) { best = s; hit = c.hit }
                }
                evals.add(WindowTrainer.ImageEval(im.id, max(best, 0f), hit && im.hasInvoice, im.hasInvoice))
            }
        }
        val th = WindowTrainer.chooseThresholds(evals, cfg)
        val problems = ArrayList<WindowTrainer.Problem>()
        val accept = th?.accept ?: 0.5f
        var hits = 0
        var fa = 0
        for (e in evals) {
            if (e.isInvoice) {
                if (e.hit && e.top >= accept) hits++
                else problems.add(WindowTrainer.Problem(e.imageId, WindowTrainer.Problem.Kind.MISSED, e.top))
            } else if (e.top >= accept) {
                fa++
                problems.add(WindowTrainer.Problem(e.imageId, WindowTrainer.Problem.Kind.FALSE_ACCEPT, e.top))
            }
        }
        val report = WindowTrainer.Report(inv, none, hits, fa, null, problems)
        if (th == null) return Result.NotReady(listOf("LOW_PRECISION"), report)
        if (th.rec < cfg.minRecallAtAccept) return Result.NotReady(listOf("LOW_RECALL"), report)

        val all = fit(rowsOf(images, cfg.exactWeight), cfg) ?: return Result.NotReady(listOf("TRAIN_FAILED"), report)
        val model = VerifierModel(
            all.copyOfRange(0, Semantic.DIM), all[Semantic.DIM], all[Semantic.DIM + 1],
            th.accept, th.unc, now, inv, none, WindowMetrics(th.prec, th.rec, th.urec)
        )
        return Result.Ready(model, report)
    }
}

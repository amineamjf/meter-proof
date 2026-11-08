package com.meterproof.app.delivery

import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** عينة معايرة: سمات رقمية وتصنيف (ورقة فاتورة ظاهرة أو لا). لا صورة. */
class Sample(val features: InvoiceFeatures, val positive: Boolean, val t: Long)

/**
 * مقاييس مقاسة بالتحقق المتقاطع على عينات المستخدم نفسه (وليست على بيانات ميدانية مستقلة):
 * precision/recall عند عتبة القبول، وuncertainRecall عند عتبة عدم اليقين.
 */
data class ModelMetrics(
    val precision: Float,
    val recall: Float,
    val uncertainRecall: Float,
    val folds: Int
)

/**
 * نموذج الانحدار اللوجستي على 6 سمات موحّدة. يخرج درجة من 0 إلى 1.
 * score >= acceptThreshold: مقبول (DETECTED)، بين العتبتين: غير مؤكد، أقل: لا شيء.
 */
class InvoiceModel(
    val mean: List<Float>,
    val std: List<Float>,
    val weights: List<Float>,
    val bias: Float,
    val acceptThreshold: Float,
    val uncertainThreshold: Float,
    val trainedAt: Long,
    val positives: Int,
    val negatives: Int,
    val metrics: ModelMetrics
) {
    init {
        require(mean.size == InvoiceFeatures.SIZE && std.size == InvoiceFeatures.SIZE &&
            weights.size == InvoiceFeatures.SIZE) { "bad model size" }
    }

    fun score(f: InvoiceFeatures): Float {
        val x = f.toList()
        var z = bias.toDouble()
        for (i in 0 until InvoiceFeatures.SIZE) {
            val s = if (std[i] > 1e-6f) std[i] else 1f
            z += weights[i].toDouble() * ((x[i] - mean[i]) / s)
        }
        val p = InvoiceTrainer.sigmoid(z).toFloat()
        return if (p.isFinite()) p else 0f
    }

    /** معرّف قصير للمعايرة يُكتب في سجل الأدلة، فيُعرف أي نموذج أصدر القرار. */
    val id: String by lazy { ModelCodec.fingerprint(this) }
}

/** تدريب ومعايرة على الجهاز: انحدار لوجستي بتحقق متقاطع حتمي، بلا إنترنت وبلا صور. */
object InvoiceTrainer {

    data class Config(
        val minPositives: Int = 15,
        val minNegatives: Int = 15,
        val folds: Int = 4,
        val epochs: Int = 600,
        val learningRate: Double = 0.3,
        val l2: Double = 0.01,
        /** الدقة المطلوبة عند عتبة القبول: القبول الخاطئ (دليل تسليم وهمي) أسوأ من الفوات. */
        val targetPrecision: Double = 0.95,
        val targetRecall: Double = 0.90,
        val minRecallAtAccept: Double = 0.80
    )

    sealed class Result {
        data class Ready(val model: InvoiceModel) : Result()

        /** reasons رموز ثابتة: NEED_MORE_POSITIVES، NEED_MORE_NEGATIVES، LOW_PRECISION، LOW_RECALL. */
        data class NotReady(val reasons: List<String>, val metrics: ModelMetrics?) : Result()
    }

    fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))

    fun train(samples: List<Sample>, now: Long, cfg: Config = Config()): Result {
        val pos = samples.filter { it.positive }.sortedBy { it.t }
        val neg = samples.filter { !it.positive }.sortedBy { it.t }

        val reasons = ArrayList<String>()
        if (pos.size < cfg.minPositives) reasons.add("NEED_MORE_POSITIVES")
        if (neg.size < cfg.minNegatives) reasons.add("NEED_MORE_NEGATIVES")
        if (reasons.isNotEmpty()) return Result.NotReady(reasons, null)

        val all = pos + neg
        val n = all.size
        val d = InvoiceFeatures.SIZE
        val raw = Array(n) { i -> DoubleArray(d) { k -> all[i].features.toList()[k].toDouble() } }
        val y = DoubleArray(n) { if (it < pos.size) 1.0 else 0.0 }

        // توحيد السمات (متوسط صفر وانحراف 1)
        val mean = DoubleArray(d)
        val std = DoubleArray(d)
        for (k in 0 until d) {
            var s = 0.0
            for (i in 0 until n) s += raw[i][k]
            mean[k] = s / n
            var v = 0.0
            for (i in 0 until n) v += (raw[i][k] - mean[k]) * (raw[i][k] - mean[k])
            std[k] = max(sqrt(v / n), 1e-3)
        }
        val z = Array(n) { i -> DoubleArray(d) { k -> (raw[i][k] - mean[k]) / std[k] } }

        // تحقق متقاطع حتمي ومتوازن بين الفئتين: العيّنة رقم i في فئتها تذهب إلى الطيّة i % folds
        val folds = min(cfg.folds, min(pos.size, neg.size)).coerceAtLeast(2)
        val fold = IntArray(n) { if (it < pos.size) it % folds else (it - pos.size) % folds }
        val oof = DoubleArray(n)
        for (f in 0 until folds) {
            val train = (0 until n).filter { fold[it] != f }
            val (w, b) = fit(z, y, train, cfg)
            for (i in 0 until n) if (fold[i] == f) oof[i] = sigmoid(dot(w, z[i]) + b)
        }

        val posCount = pos.size

        fun predicted(th: Double): Int = oof.count { it >= th }
        fun truePositives(th: Double): Int = (0 until n).count { y[it] == 1.0 && oof[it] >= th }
        fun precisionAt(th: Double): Double {
            val p = predicted(th)
            return if (p == 0) 0.0 else truePositives(th).toDouble() / p
        }
        fun recallAt(th: Double): Double = truePositives(th).toDouble() / posCount

        val candidates = oof.distinct().sorted()
        val minPredicted = max(5, posCount / 2)

        var found: Double? = null
        for (th in candidates) {
            if (predicted(th) < minPredicted) break
            if (precisionAt(th) >= cfg.targetPrecision) {
                found = th
                break
            }
        }

        if (found == null) {
            val m = ModelMetrics(precisionAt(0.5).toFloat(), recallAt(0.5).toFloat(), recallAt(0.5).toFloat(), folds)
            return Result.NotReady(listOf("LOW_PRECISION"), m)
        }
        val accept: Double = found

        // الاستدعاء يتناقص مع ارتفاع العتبة، فأعلى عتبة تحقق الهدف هي المقصودة. المرور التصاعدي
        // كان يتوقف عند أدنى درجة (استدعاء 100% دائمًا) فيصبح كل شيء «غير مؤكد».
        var uncertain: Double = accept
        for (th in candidates.asReversed()) {
            if (recallAt(th) >= cfg.targetRecall) {
                uncertain = min(th, accept)
                break
            }
        }

        val metrics = ModelMetrics(
            precisionAt(accept).toFloat(), recallAt(accept).toFloat(),
            recallAt(uncertain).toFloat(), folds
        )
        if (recallAt(accept) < cfg.minRecallAtAccept) {
            return Result.NotReady(listOf("LOW_RECALL"), metrics)
        }

        // النموذج النهائي على كل العينات، بالعتبات المقاسة على التنبؤات خارج العينة
        val (wAll, bAll) = fit(z, y, (0 until n).toList(), cfg)
        val model = InvoiceModel(
            mean = mean.map { it.toFloat() },
            std = std.map { it.toFloat() },
            weights = wAll.map { it.toFloat() },
            bias = bAll.toFloat(),
            acceptThreshold = accept.toFloat(),
            uncertainThreshold = uncertain.toFloat(),
            trainedAt = now,
            positives = pos.size,
            negatives = neg.size,
            metrics = metrics
        )
        return Result.Ready(model)
    }

    private fun dot(w: DoubleArray, x: DoubleArray): Double {
        var s = 0.0
        for (k in w.indices) s += w[k] * x[k]
        return s
    }

    /** انحدار لوجستي بالنزول التدرجي الكامل، بوزن يوازن الفئتين، وL2 على الأوزان. حتمي تمامًا. */
    private fun fit(
        z: Array<DoubleArray>, y: DoubleArray, idx: List<Int>, cfg: Config
    ): Pair<DoubleArray, Double> {
        val d = z[0].size
        val nPos = idx.count { y[it] == 1.0 }
        val nNeg = idx.size - nPos
        check(nPos > 0 && nNeg > 0) { "each class needs samples" }
        val wPos = idx.size / (2.0 * nPos)
        val wNeg = idx.size / (2.0 * nNeg)

        val w = DoubleArray(d)
        var b = 0.0
        val g = DoubleArray(d)
        val m = idx.size.toDouble()
        repeat(cfg.epochs) {
            java.util.Arrays.fill(g, 0.0)
            var gb = 0.0
            for (i in idx) {
                val p = sigmoid(dot(w, z[i]) + b)
                val e = (p - y[i]) * (if (y[i] == 1.0) wPos else wNeg)
                for (k in 0 until d) g[k] += e * z[i][k]
                gb += e
            }
            for (k in 0 until d) w[k] -= cfg.learningRate * (g[k] / m + cfg.l2 * w[k])
            b -= cfg.learningRate * (gb / m)
        }
        return Pair(w, b)
    }
}

/** ترميز نصي بسيط للنموذج والعينات (بلا JSON حتى يُختبر على JVM بلا أندرويد). */
object ModelCodec {
    private const val VERSION = "IM1"

    fun encode(m: InvoiceModel): String = listOf(
        VERSION, join(m.mean), join(m.std), join(m.weights), m.bias.toString(),
        m.acceptThreshold.toString(), m.uncertainThreshold.toString(),
        m.trainedAt.toString(), m.positives.toString(), m.negatives.toString(),
        m.metrics.precision.toString(), m.metrics.recall.toString(),
        m.metrics.uncertainRecall.toString(), m.metrics.folds.toString()
    ).joinToString(";")

    /** null عند أي خلل في النص أو قيمة غير منتهية، فلا يُستعمل نموذج تالف. */
    fun decode(s: String): InvoiceModel? {
        return try {
            val p = s.trim().split(';')
            if (p.size != 14 || p[0] != VERSION) return null
            val model = InvoiceModel(
                mean = floats(p[1]), std = floats(p[2]), weights = floats(p[3]),
                bias = p[4].toFloat(),
                acceptThreshold = p[5].toFloat(), uncertainThreshold = p[6].toFloat(),
                trainedAt = p[7].toLong(), positives = p[8].toInt(), negatives = p[9].toInt(),
                metrics = ModelMetrics(p[10].toFloat(), p[11].toFloat(), p[12].toFloat(), p[13].toInt())
            )
            val all = model.mean + model.std + model.weights +
                listOf(model.bias, model.acceptThreshold, model.uncertainThreshold)
            if (all.any { !it.isFinite() }) null else model
        } catch (e: Exception) {
            null
        }
    }

    /** بصمة قصيرة (8 خانات) لمعاملات النموذج (دون وقت التدريب). */
    fun fingerprint(m: InvoiceModel): String {
        val canonical = listOf(
            join(m.mean), join(m.std), join(m.weights), m.bias.toString(),
            m.acceptThreshold.toString(), m.uncertainThreshold.toString()
        ).joinToString(";")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    private fun join(v: List<Float>) = v.joinToString(",") { it.toString() }

    private fun floats(s: String): List<Float> = s.split(',').map { it.toFloat() }
}

object SampleCodec {
    fun encode(samples: List<Sample>): String =
        samples.joinToString("\n") { s ->
            "S;" + (if (s.positive) "1" else "0") + ";" +
                s.features.toList().joinToString(",") { it.toString() } + ";" + s.t
        }

    /** يتجاهل أي سطر تالف أو غير منتهٍ بدل إفشال القراءة كلها. */
    fun decode(text: String): List<Sample> {
        val out = ArrayList<Sample>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            try {
                val p = line.trim().split(';')
                if (p.size != 4 || p[0] != "S") continue
                val f = InvoiceFeatures.fromList(p[2].split(',').map { it.toFloat() }) ?: continue
                out.add(Sample(f, p[1] == "1", p[3].toLong()))
            } catch (e: Exception) {
                // سطر تالف: يُتجاهل
            }
        }
        return out
    }

    /** نسبة مئوية صحيحة لعرض المقاييس. */
    fun pct(v: Float): Int = (v * 100f).roundToInt().coerceIn(0, 100)
}

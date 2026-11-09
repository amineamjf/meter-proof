package com.meterproof.app.delivery

/**
 * سبب نتيجة جولة تدريب: READY جاهز، LOW_PRECISION الدقة دون الهدف، LOW_RECALL يفوّت فواتير،
 * NEED_MORE العينات أقل من الحد الأدنى (لم يُقس شيء).
 */
data class TrainRound(
    val t: Long,
    val positives: Int,
    val negatives: Int,
    val precision: Float?,
    val recall: Float?,
    val reason: String
) {
    val ready: Boolean get() = reason == "READY"
}

/** حفظ سجل جولات التدريب نصًا (سطر لكل جولة). لا صور ولا سمات، أرقام فقط. */
object TrainHistoryCodec {
    fun encode(rounds: List<TrainRound>): String =
        rounds.joinToString("\n") { r ->
            "R;${r.t};${r.positives};${r.negatives};${r.precision ?: "-"};${r.recall ?: "-"};${r.reason}"
        }

    /** يتجاهل أي سطر تالف بدل إفشال القراءة كلها. */
    fun decode(text: String): List<TrainRound> {
        val out = ArrayList<TrainRound>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            try {
                val p = line.trim().split(';')
                if (p.size != 7 || p[0] != "R") continue
                out.add(
                    TrainRound(
                        t = p[1].toLong(),
                        positives = p[2].toInt(),
                        negatives = p[3].toInt(),
                        precision = if (p[4] == "-") null else p[4].toFloat(),
                        recall = if (p[5] == "-") null else p[5].toFloat(),
                        reason = p[6]
                    )
                )
            } catch (e: Exception) {
                // سطر تالف
            }
        }
        return out
    }
}

/** ما تعرضه شاشة «حالة التدريب». كله محسوب من العينات والسجل والنموذج، بلا اعتماد على أندرويد. */
data class TrainingState(
    val headline: String,
    val positives: Int,
    val negatives: Int,
    val minEach: Int,
    /** الدقة الحالية (0..1) من آخر جولة قيست فيها، أو null إن لم تُقس بعد. */
    val precision: Float?,
    val targetPrecision: Float,
    val lastTrainAt: Long?,
    val newSamplesSinceTrain: Int,
    /** ما ينقصك بالتحديد، جملة لكل بند. */
    val missing: List<String>,
    /** الشرطان محققان: عينات كافية ونموذج بدقة بلغت الهدف. */
    val ready: Boolean,
    val detectionOn: Boolean,
    /** هل يعمل الكاشف فعلًا الآن (مفعّل + نموذج جاهز + الخصوصية الكاملة). */
    val effective: Boolean,
    /** سبب عدم عمله، أو null إن كان يعمل. */
    val disabledReason: String?,
    /** تنبيه ثابت: الكشف التلقائي لا يعمل في وضع «وجوه فقط». */
    val facesOnlyWarning: Boolean,
    /** دقة كل جولة قيست (0..1) بالترتيب، للرسم. */
    val precisionHistory: List<Float>
)

object TrainingStatus {

    /** تقريب لأسفل كي لا تُعرض 94.6% على أنها 95%. */
    fun pctFloor(v: Float): Int = (v * 100f).toInt().coerceIn(0, 100)

    /** «N عينات» بصياغة عربية سليمة. */
    fun samplesWord(n: Int): String = when {
        n == 1 -> "عينة واحدة"
        n == 2 -> "عينتان"
        n in 3..10 -> "$n عينات"
        else -> "$n عينة"
    }

    fun compute(
        samples: List<Sample>,
        history: List<TrainRound>,
        model: InvoiceModel?,
        detectionOn: Boolean,
        privacyFull: Boolean,
        cfg: InvoiceTrainer.Config = InvoiceTrainer.Config()
    ): TrainingState {
        val pos = samples.count { it.positive }
        val neg = samples.size - pos
        val min = cfg.minPositives // الحدان متساويان (15 + 15)
        val target = cfg.targetPrecision.toFloat()
        val enough = pos >= cfg.minPositives && neg >= cfg.minNegatives

        val lastRound = history.lastOrNull()
        val lastTrainAt: Long? = lastRound?.t ?: model?.trainedAt
        val newSince = if (lastTrainAt == null) samples.size else samples.count { it.t > lastTrainAt }

        val measured = history.mapNotNull { it.precision }
        val precision: Float? = model?.metrics?.precision ?: measured.lastOrNull()

        val ready = model != null && enough && (precision ?: 0f) >= target
        val effective = ready && detectionOn && privacyFull

        val have = minOf(pos, cfg.minPositives) + minOf(neg, cfg.minNegatives)
        val need = cfg.minPositives + cfg.minNegatives

        val headline = when {
            ready && detectionOn && privacyFull -> "الكاشف جاهز ومفعّل"
            ready && detectionOn ->
                "الكاشف جاهز ومفعّل، لكنه لا يعمل الآن: وضع الخصوصية «وجوه فقط»"
            ready ->
                "الكاشف جاهز: الدقة ${pctFloor(precision ?: 0f)}% بلغت الهدف ${pctFloor(target)}%، ولم يُفعَّل بعد"
            samples.isEmpty() -> "لم يبدأ التدريب بعد: لا توجد عينات"
            !enough -> {
                val stage = if (have * 2 >= need) "التدريب في منتصف الطريق" else "التدريب في بدايته"
                "$stage: جمعت $have من $need عينة مطلوبة"
            }
            precision == null -> "العينات كافية، لكن الدقة لم تُقَس بعد: اضغط «تدريب ومعايرة»"
            else -> "التدريب في منتصف الطريق: الدقة ${pctFloor(precision)}% والهدف ${pctFloor(target)}%"
        }

        val missing = ArrayList<String>()
        if (pos < cfg.minPositives) {
            missing.add("ينقصك ${samplesWord(cfg.minPositives - pos)} «فاتورة ظاهرة»")
        }
        if (neg < cfg.minNegatives) {
            missing.add("ينقصك ${samplesWord(cfg.minNegatives - neg)} «بلا فاتورة»")
        }
        if (enough && !ready) {
            val lastReason: String? = lastRound?.reason
            when {
                lastReason == null || lastReason == "NEED_MORE" ->
                    missing.add("اضغط «تدريب ومعايرة» لقياس الدقة")
                lastReason == "LOW_RECALL" ->
                    missing.add("الدقة بلغت الهدف لكن الكاشف يفوّت فواتير كثيرة: أضف عينات «فاتورة ظاهرة» بإضاءات وأبواب مختلفة ثم أعد التدريب")
                else -> {
                    val gap = if (precision != null) pctFloor(target) - pctFloor(precision) else 0
                    missing.add(
                        "الدقة أقل من الهدف" + (if (gap > 0) " بنحو $gap نقطة" else "") +
                            ": أضف عينات أوضح وأكثر تنوعًا (إضاءة، أبواب، أوراق أخرى) ثم أعد التدريب"
                    )
                }
            }
        }
        if (lastRound != null && newSince > 0 && (enough || lastRound.reason != "NEED_MORE")) {
            missing.add("أضفت ${samplesWord(newSince)} منذ آخر تدريب: أعد التدريب لتدخل في الدقة")
        }

        val disabledReason: String? = when {
            effective -> null
            !ready && detectionOn -> "مفعّل لكن بلا نموذج جاهز، فهو متوقف (لا كشف قبل المعايرة)"
            !ready -> "معطّل: لا نموذج جاهز بعد (لا كشف قبل المعايرة)"
            !detectionOn -> "معطّل: لم تفعّله بعد"
            else -> "معطّل فعليًا: وضع الخصوصية «وجوه فقط»، والكشف التلقائي يعتمد على بوابة الخصوصية الكاملة"
        }

        return TrainingState(
            headline = headline,
            positives = pos,
            negatives = neg,
            minEach = min,
            precision = precision,
            targetPrecision = target,
            lastTrainAt = lastTrainAt,
            newSamplesSinceTrain = newSince,
            missing = missing,
            ready = ready,
            detectionOn = detectionOn,
            effective = effective,
            disabledReason = disabledReason,
            facesOnlyWarning = !privacyFull,
            precisionHistory = measured
        )
    }
}

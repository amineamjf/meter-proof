package com.meterproof.app.delivery

import com.meterproof.app.PhotoLabels.Label
import com.meterproof.app.privacy.Region

/**
 * تدريب مرئي من الصور المصنَّفة في المعرض (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * هذا تقرير فقط: لا يحفظ نموذجًا، ولا يفعّل الكشف، ولا يغيّر عينات المعايرة في [DeliveryStore].
 * أي اعتماد للنموذج قرار منفصل بيد المالك.
 *
 * - الصورة بلا ورقة مكتشفة (features = null) لا تدخل التدريب، لكنها تُعرض: إن كانت «فاتورة»
 *   فهي فوات حقيقي (الكاشف لا يرى ورقة أصلًا)، وإن كانت «بلا فاتورة» فهي صحيحة.
 * - درجة كل صورة بالتحقق المتقاطع (خارج العينة)، لا بنموذج رآها.
 */
object GalleryTraining {

    /** صورة مصنَّفة بعد تحليلها. features = null: لم يُعثر على مرشح ورقة. */
    class Entry(
        val id: String,
        val label: Label,
        val features: InvoiceFeatures?,
        val t: Long,
        /** إطار الورقة المكتشفة (نسبيّ 0..1) لرسمه على الصورة، أو null. */
        val region: Region? = null
    )

    enum class Verdict {
        /** الكاشف أصاب: فاتورة مقبولة، أو بلا فاتورة ورُفضت. */
        CORRECT,
        /** فاتورة لم تُكتشف (درجة منخفضة أو لا ورقة). */
        MISSED,
        /** «بلا فاتورة» لكن الكاشف قبلها: القبول الخاطئ، وهو الأخطر لأنه دليل تسليم وهمي. */
        FALSE_ACCEPT,
        /** بين عتبة عدم اليقين والقبول: لا يُحسب خطأ ولا صوابًا. */
        UNCERTAIN,
        /** لا تقدير ممكن (عينات غير كافية للتدريب). */
        UNKNOWN
    }

    class PhotoOutcome(
        val id: String,
        val label: Label,
        /** null إن لم تُوجد ورقة أو لم يتوفر تقدير. */
        val score: Float?,
        val noPaper: Boolean,
        val verdict: Verdict,
        /** ما رآه الكاشف: إطار الورقة المكتشفة، يُرسم على الصورة. null إن لم تُوجد ورقة. */
        val region: Region? = null,
        /** سبب الدرجة بالسمات (من النموذج النهائي)، أو null إن لم يوجد نموذج جاهز أو ورقة. */
        val why: String? = null
    )

    class Report(
        val result: InvoiceTrainer.Result,
        val outcomes: List<PhotoOutcome>,
        val invoiceCount: Int,
        val noneCount: Int,
        val unclearCount: Int,
        /** عدد الصور التي دخلت التدريب فعلًا (لها مرشح ورقة). */
        val trainedOn: Int,
        /** هل العتبات المستعملة للحكم هي عتبات نموذج جاهز (وإلا فهي 0.5 للتقدير فقط)؟ */
        val thresholdsFromModel: Boolean
    ) {
        val correct get() = outcomes.count { it.verdict == Verdict.CORRECT }
        val errors get() = outcomes.count { it.verdict == Verdict.MISSED || it.verdict == Verdict.FALSE_ACCEPT }
    }

    /** عتبة التقدير حين لا يوجد نموذج جاهز (للعرض فقط، ليست عتبة قبول معتمدة). */
    const val PREVIEW_THRESHOLD = 0.5f

    fun run(entries: List<Entry>, now: Long, cfg: InvoiceTrainer.Config = InvoiceTrainer.Config()): Report {
        val labeled = entries.filter { it.label == Label.INVOICE || it.label == Label.NONE }
        val withFeatures = labeled.filter { it.features != null }
        val samples = withFeatures.map { Sample(it.features!!, it.label == Label.INVOICE, it.t) }

        val result = InvoiceTrainer.train(samples, now, cfg)
        val oof = InvoiceTrainer.outOfFoldScores(samples, cfg)

        val model = (result as? InvoiceTrainer.Result.Ready)?.model
        val accept = model?.acceptThreshold ?: PREVIEW_THRESHOLD
        val uncertain = model?.uncertainThreshold ?: PREVIEW_THRESHOLD

        val scoreById = HashMap<String, Float>()
        if (oof != null) withFeatures.forEachIndexed { i, e -> scoreById[e.id] = oof[i].toFloat() }

        val outcomes = labeled.map { e ->
            val invoice = e.label == Label.INVOICE
            val noPaper = e.features == null
            val score = scoreById[e.id]
            val verdict = when {
                noPaper -> if (invoice) Verdict.MISSED else Verdict.CORRECT
                score == null -> Verdict.UNKNOWN
                score >= accept -> if (invoice) Verdict.CORRECT else Verdict.FALSE_ACCEPT
                score >= uncertain -> Verdict.UNCERTAIN
                else -> if (invoice) Verdict.MISSED else Verdict.CORRECT
            }
            val why = if (model != null && e.features != null) DecisionExplainer.explain(e.features, model) else null
            PhotoOutcome(e.id, e.label, score, noPaper, verdict, e.region, why)
        }

        return Report(
            result = result,
            outcomes = outcomes,
            invoiceCount = entries.count { it.label == Label.INVOICE },
            noneCount = entries.count { it.label == Label.NONE },
            unclearCount = entries.count { it.label == Label.UNCLEAR },
            trainedOn = samples.size,
            thresholdsFromModel = model != null
        )
    }

    /** وصف عربي لسبب عدم الجاهزية (الرموز ثابتة من [InvoiceTrainer]). */
    fun reasonArabic(code: String): String = when (code) {
        "NEED_MORE_POSITIVES" -> "عدد صور «فاتورة» التي وُجدت فيها ورقة غير كافٍ"
        "NEED_MORE_NEGATIVES" -> "عدد صور «بلا فاتورة» التي وُجدت فيها ورقة غير كافٍ"
        "LOW_PRECISION" -> "الدقة دون الهدف (القبول الخاطئ كثير)"
        "LOW_RECALL" -> "يفوّت كثيرًا من الفواتير الحقيقية"
        "NEED_MORE_INVOICE_IMAGES" -> "صور فيها مربع «فاتورة» غير كافية (يلزم 8 على الأقل)"
        "NEED_MORE_NONE_IMAGES" -> "صور مصنَّفة «بلا فاتورة» غير كافية (يلزم 8 على الأقل)"
        "HOLDOUT_FALSE_ACCEPT" -> "قَبِل أماكن بلا فاتورة في الصور المحجوزة (أضف صور «بلا فاتورة» أكثر وبتنوع)"
        "TRAIN_FAILED" -> "تعذّر التدريب على هذه الأمثلة"
        else -> code
    }
}

/** نصوص عربية لأسباب قرار سجل الإصدارات. */
object ModelLabels {
    fun reasonArabic(code: String): String = when (code) {
        "PRECISION_REGRESSED" -> "تراجعت دقته عن المستقر"
        "RECALL_REGRESSED" -> "تراجع استدعاؤه عن المستقر"
        "HOLDOUT_FALSE_ACCEPT_WORSE" -> "قبل أماكن بلا فاتورة أكثر في القياس المحجوز"
        "FIRST_MODEL" -> "أول نموذج"
        "NOT_WORSE" -> "لم يتراجع عن المستقر"
        "IMPORTED" -> "مستورد من نسخة سابقة"
        else -> code
    }
}

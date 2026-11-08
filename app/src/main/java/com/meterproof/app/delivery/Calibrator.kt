package com.meterproof.app.delivery

/**
 * "التدريب" على الهاتف: ضبط عتبات آلة الحالات من عينات العامل نفسه (أرقام فقط، بلا صور).
 *
 * لماذا ضبط عتبات وليس شبكة عصبية؟ العينات التي يستطيع عامل تسجيلها قليلة (عشرات)،
 * وهذا يكفي لضبط بضع عتبات بأمان، ولا يكفي لتدريب نموذج عميق دون إفراط في التخصيص.
 * طريقة الضبط: هبوط إحداثي على شبكة قيم لكل عتبة، والهدف رفع التسليمات المؤكدة
 * وعقاب الإنذار الكاذب (3 أضعاف) والتسليم الفائت.
 * التقييم الصادق: "اترك واحدة جانبًا" (Leave-One-Out): كل عينة تُختبر بعتبات لم ترها.
 */
object Calibrator {

    enum class Result { CONFIRMED, UNCERTAIN, NONE }

    data class Counts(
        val tp: Int = 0,
        val fn: Int = 0,
        val fp: Int = 0,
        val tn: Int = 0,
        val uncertainPos: Int = 0,
        val uncertainNeg: Int = 0
    ) {
        val positives get() = tp + fn + uncertainPos
        val negatives get() = fp + tn + uncertainNeg
        fun recall(): Float = if (positives == 0) 0f else tp / positives.toFloat()
        fun precision(): Float = if (tp + fp == 0) 0f else tp / (tp + fp).toFloat()
        fun objective(): Double =
            tp - 3.0 * fp - 1.0 * fn - 0.3 * uncertainPos - 0.1 * uncertainNeg
    }

    data class Report(
        val params: DeliveryParams,
        /** نتائج العتبات المضبوطة على نفس عينات التدريب (متفائلة). */
        val training: Counts,
        /** نتائج اترك-واحدة-جانبًا (الأقرب للواقع)، أو null إن قلّت العينات. */
        val crossValidated: Counts?,
        val warnings: List<String>
    )

    const val MIN_POSITIVES = 5
    const val MIN_NEGATIVES = 6
    const val MIN_FOR_CV = 8

    fun run(p: DeliveryParams, frames: List<Feat>): Result {
        val m = DeliveryMachine(p)
        var out: DeliveryMachine.Outcome? = null
        for (f in frames) {
            out = m.onFeat(f)
            if (out != null) break
        }
        if (out == null) out = m.finish()
        return when {
            out == null -> Result.NONE
            out.kind == DeliveryMachine.Kind.CONFIRMED -> Result.CONFIRMED
            else -> Result.UNCERTAIN
        }
    }

    fun count(p: DeliveryParams, samples: List<Sample>): Counts {
        var tp = 0
        var fn = 0
        var fp = 0
        var tn = 0
        var up = 0
        var un = 0
        for (s in samples) {
            val r = run(p, s.frames)
            if (s.label.positive) {
                when (r) {
                    Result.CONFIRMED -> tp++
                    Result.UNCERTAIN -> up++
                    Result.NONE -> fn++
                }
            } else {
                when (r) {
                    Result.CONFIRMED -> fp++
                    Result.UNCERTAIN -> un++
                    Result.NONE -> tn++
                }
            }
        }
        return Counts(tp, fn, fp, tn, up, un)
    }

    private class Knob(
        val lo: Double,
        val hi: Double,
        val get: (DeliveryParams) -> Double,
        val set: (DeliveryParams, Double) -> DeliveryParams
    )

    private val knobs = listOf(
        Knob(0.005, 0.15, { it.skinMin.toDouble() }, { p, v -> p.copy(skinMin = v.toFloat()) }),
        Knob(0.001, 0.05, { it.heldMin.toDouble() }, { p, v -> p.copy(heldMin = v.toFloat()) }),
        Knob(200.0, 3000.0, { it.holdMs.toDouble() }, { p, v -> p.copy(holdMs = v.toLong()) }),
        Knob(200.0, 2500.0, { it.withdrawMs.toDouble() }, { p, v -> p.copy(withdrawMs = v.toLong()) }),
        Knob(0.001, 0.05, { it.objMin.toDouble() }, { p, v -> p.copy(objMin = v.toFloat()) }),
        Knob(400.0, 3000.0, { it.restMs.toDouble() }, { p, v -> p.copy(restMs = v.toLong()) }),
        Knob(0.02, 0.15, { it.moveTol.toDouble() }, { p, v -> p.copy(moveTol = v.toFloat()) }),
        Knob(0.02, 0.20, { it.motionMax.toDouble() }, { p, v -> p.copy(motionMax = v.toFloat()) })
    )

    private val multipliers = doubleArrayOf(0.2, 0.35, 0.5, 0.7, 1.0, 1.4, 2.0, 3.0)

    /** هبوط إحداثي: 3 جولات على العتبات الثماني. عند التعادل تبقى القيمة الحالية (استقرار). */
    fun tune(samples: List<Sample>, start: DeliveryParams = DeliveryParams()): DeliveryParams {
        var cur = start.copy(underTrusted = false)
        if (samples.isEmpty()) return cur
        var best = count(cur, samples).objective()
        repeat(3) {
            var improved = false
            for (k in knobs) {
                val base = k.get(cur)
                for (m in multipliers) {
                    if (m == 1.0) continue
                    val cand = k.set(cur, (base * m).coerceIn(k.lo, k.hi))
                    val v = count(cand, samples).objective()
                    if (v > best + 1e-9) {
                        best = v
                        cur = cand
                        improved = true
                    }
                }
            }
            if (!improved) return finalize(cur, samples)
        }
        return finalize(cur, samples)
    }

    /**
     * نمط "تحت الباب" لا يُعتمد إلا بدليل: 3 عينات إيجابية منه على الأقل، يؤكَّد ثلثاها،
     * ولا يزيد معه أي إنذار كاذب على العينات السلبية.
     */
    private fun finalize(p: DeliveryParams, samples: List<Sample>): DeliveryParams {
        val under = samples.filter { it.label == Label.POS_UNDER }
        if (under.size < 3) return p
        val trusted = p.copy(underTrusted = true)
        val confirmed = under.count { run(trusted, it.frames) == Result.CONFIRMED }
        val fpBefore = count(p, samples).fp
        val fpAfter = count(trusted, samples).fp
        return if (confirmed * 3 >= under.size * 2 && fpAfter <= fpBefore) trusted else p
    }

    fun report(samples: List<Sample>, start: DeliveryParams = DeliveryParams()): Report {
        val warnings = ArrayList<String>()
        val pos = samples.count { it.label.positive }
        val neg = samples.size - pos
        if (pos < MIN_POSITIVES) warnings.add("عينات التسليم الحقيقية قليلة ($pos). المطلوب $MIN_POSITIVES على الأقل.")
        if (neg < MIN_NEGATIVES) warnings.add("العينات السلبية قليلة ($neg). المطلوب $MIN_NEGATIVES على الأقل.")
        for (l in listOf(Label.POS_ON, Label.POS_BESIDE, Label.POS_UNDER)) {
            if (samples.count { it.label == l } < 2) warnings.add("سجّل عينتين على الأقل من: ${l.ar}.")
        }

        val tuned = tune(samples, start)
        val training = count(tuned, samples)

        var cv: Counts? = null
        if (samples.size >= MIN_FOR_CV && pos > 0 && neg > 0) {
            var c = Counts()
            for (i in samples.indices) {
                val train = samples.filterIndexed { j, _ -> j != i }
                val p = tune(train, start)
                val one = count(p, listOf(samples[i]))
                c = Counts(
                    c.tp + one.tp, c.fn + one.fn, c.fp + one.fp, c.tn + one.tn,
                    c.uncertainPos + one.uncertainPos, c.uncertainNeg + one.uncertainNeg
                )
            }
            cv = c
        } else {
            warnings.add("عينات قليلة لتقييم اترك-واحدة-جانبًا (المطلوب $MIN_FOR_CV على الأقل).")
        }
        return Report(tuned.copy(version = start.version + 1), training, cv, warnings)
    }
}

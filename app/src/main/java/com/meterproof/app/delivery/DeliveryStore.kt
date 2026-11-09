package com.meterproof.app.delivery

import android.content.Context
import java.io.File

/**
 * مخزن المعايرة المحلي. يحفظ سمات رقمية وتصنيفها فقط، ولا يحفظ أي صورة أو بكسل.
 *
 * - مجلد filesDir/delivery منفصل عن مجلد الأدلة، فلا يدخل التصدير ولا سلسلة البصمات.
 * - لا يغادر الجهاز، ويُمسح كله بـ clear().
 * - الحد الأقصى MAX_SAMPLES عينة، والأقدم يُحذف أولًا.
 */
class DeliveryStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "delivery").apply { mkdirs() }
    private val samplesFile = File(dir, "samples.txt")
    private val modelFile = File(dir, "model.txt")
    private val historyFile = File(dir, "history.txt")

    companion object {
        const val MAX_SAMPLES = 400
        const val MAX_HISTORY = 40
        private val LOCK = Any()
    }

    fun samples(): List<Sample> = synchronized(LOCK) {
        if (!samplesFile.exists()) emptyList() else SampleCodec.decode(samplesFile.readText())
    }

    fun addSample(s: Sample) {
        synchronized(LOCK) {
            val list = (samples() + s).takeLast(MAX_SAMPLES)
            samplesFile.writeText(SampleCodec.encode(list))
        }
    }

    /** النموذج المعايَر الحالي، أو null إن لم يوجد نموذج صالح. */
    fun model(): InvoiceModel? = synchronized(LOCK) {
        if (!modelFile.exists()) null else ModelCodec.decode(modelFile.readText())
    }

    /**
     * يدرّب من العينات المحفوظة. عند النجاح يُحفظ النموذج، وعند الفشل يُحذف النموذج السابق:
     * إن قالت العينات الحالية إن الكاشف غير موثوق فالأمان أن يتوقف.
     */
    fun train(now: Long = System.currentTimeMillis()): InvoiceTrainer.Result = synchronized(LOCK) {
        val all = samples()
        val r = InvoiceTrainer.train(all, now)
        val pos = all.count { it.positive }
        val round = when (r) {
            is InvoiceTrainer.Result.Ready -> {
                modelFile.writeText(ModelCodec.encode(r.model))
                TrainRound(now, pos, all.size - pos, r.model.metrics.precision, r.model.metrics.recall, "READY")
            }
            is InvoiceTrainer.Result.NotReady -> {
                modelFile.delete()
                val reason = when {
                    "LOW_PRECISION" in r.reasons -> "LOW_PRECISION"
                    "LOW_RECALL" in r.reasons -> "LOW_RECALL"
                    else -> "NEED_MORE"
                }
                TrainRound(now, pos, all.size - pos, r.metrics?.precision, r.metrics?.recall, reason)
            }
        }
        appendRound(round)
        r
    }

    /** سجل جولات التدريب (الأقدم أولًا). يُستعمل لشاشة «حالة التدريب» ورسم تغيّر الدقة. */
    fun history(): List<TrainRound> = synchronized(LOCK) {
        if (!historyFile.exists()) emptyList() else TrainHistoryCodec.decode(historyFile.readText())
    }

    private fun appendRound(round: TrainRound) {
        val list = (history() + round).takeLast(MAX_HISTORY)
        historyFile.writeText(TrainHistoryCodec.encode(list))
    }

    fun clear() {
        synchronized(LOCK) {
            samplesFile.delete()
            modelFile.delete()
            historyFile.delete()
        }
    }
}

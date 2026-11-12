package com.meterproof.app.delivery

import android.content.Context
import java.io.File

/**
 * مخزن المعايرة المحلي لمهمة الفواتير ([TrainingTask.INVOICE]). يحفظ سمات رقمية وتصنيفها فقط، ولا يحفظ أي صورة أو بكسل.
 *
 * - مجلد filesDir/delivery منفصل عن مجلد الأدلة، فلا يدخل التصدير ولا سلسلة البصمات.
 * - لا يغادر الجهاز، ويُمسح كله بـ clear().
 * - الحد الأقصى MAX_SAMPLES عينة، والأقدم يُحذف أولًا.
 */
class DeliveryStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "delivery").apply { mkdirs() }
    private val samplesFile = File(dir, "samples.txt")
    private val modelFile = File(dir, "model.txt")

    companion object {
        const val MAX_SAMPLES = 400
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
    fun train(
        now: Long = System.currentTimeMillis(),
        extra: List<Sample> = emptyList()
    ): InvoiceTrainer.Result = synchronized(LOCK) {
        // extra: عينات التعليمات المرسومة (InstructionStore)، تُضاف إلى عينات المعايرة دون أن تُخزَّن معها
        val r = InvoiceTrainer.train(samples() + extra, now)
        when (r) {
            is InvoiceTrainer.Result.Ready -> modelFile.writeText(ModelCodec.encode(r.model))
            is InvoiceTrainer.Result.NotReady -> modelFile.delete()
        }
        r
    }

    /** قياس على أمثلة محجوزة (لا يحفظ شيئًا ولا يغيّر النموذج المعتمد). */
    fun holdout(
        now: Long = System.currentTimeMillis(),
        extra: List<Sample> = emptyList()
    ): HoldoutReport? = InvoiceTrainer.holdoutEvaluate(samples() + extra, now)

    fun clear() {
        synchronized(LOCK) {
            samplesFile.delete()
            modelFile.delete()
        }
    }
}

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
    private val windowModelFile = File(dir, "window_model.txt")
    private val verifierFile = File(dir, "verifier.txt")

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

    /** نموذج الكاشف بالنوافذ (المتعلَّم من المربعات المرسومة)، أو null. */
    fun windowModel(): WindowModel? = synchronized(LOCK) {
        if (!windowModelFile.exists()) null else WindowModelCodec.decode(windowModelFile.readText())
    }

    /** عند النجاح يُحفظ، وعند الفشل يُحذف السابق (الأمان: عينات اليوم تقول إنه غير موثوق). */
    fun applyWindowResult(r: WindowTrainer.Result) {
        synchronized(LOCK) {
            when (r) {
                is WindowTrainer.Result.Ready -> { windowModelFile.writeText(WindowModelCodec.encode(r.model)); verifierFile.delete() }
                is WindowTrainer.Result.NotReady -> { windowModelFile.delete(); verifierFile.delete() }
            }
        }
    }

    /** مدقِّق المرحلة الثانية (دلالي)، أو null. */
    fun verifier(): VerifierModel? = synchronized(LOCK) {
        if (!verifierFile.exists()) null else VerifierCodec.decode(verifierFile.readText())
    }

    /** null يحذف المدقِّق (فيعمل كاشف النوافذ وحده). */
    fun saveVerifier(m: VerifierModel?) {
        synchronized(LOCK) {
            if (m == null) verifierFile.delete() else verifierFile.writeText(VerifierCodec.encode(m))
        }
    }

    /** هل يوجد أي كاشف جاهز (الجديد أو القديم)؟ */
    fun detectionReady(): Boolean = windowModel() != null || model() != null

    fun clear() {
        synchronized(LOCK) {
            samplesFile.delete()
            modelFile.delete()
            windowModelFile.delete()
            verifierFile.delete()
        }
    }
}

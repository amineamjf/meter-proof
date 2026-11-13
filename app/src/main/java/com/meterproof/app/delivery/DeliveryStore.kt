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

    /** سجل إصدارات نماذج الفواتير (المستقر، التاريخ، الرجوع). */
    val registry: ModelRegistry by lazy { ModelRegistry(dir) }

    /** نقل الملفات القديمة (قبل السجل) كإصدار أول معتمد مرة واحدة. */
    private fun migrateLegacy(device: String) {
        synchronized(LOCK) {
            if (registry.stable() != null || !windowModelFile.exists()) return
            val w = WindowModelCodec.decode(windowModelFile.readText())
            if (w != null) {
                val v = if (verifierFile.exists()) VerifierCodec.decode(verifierFile.readText()) else null
                registry.importLegacy(w, v, device)
            }
            windowModelFile.delete()
            verifierFile.delete()
            registry.clear()
        }
    }

    /** النموذج المستقر المعتمد لكاشف النوافذ، أو null. */
    fun windowModel(): WindowModel? = synchronized(LOCK) {
        migrateLegacy("legacy")
        registry.stable()?.window
    }

    /** مدقِّق المرحلة الثانية للإصدار المستقر (دلالي)، أو null. */
    fun verifier(): VerifierModel? = synchronized(LOCK) {
        migrateLegacy("legacy")
        registry.stable()?.verifier
    }

    /** هل يوجد أي كاشف جاهز (الجديد أو القديم)؟ */
    fun detectionReady(): Boolean = windowModel() != null || model() != null

    fun clear() {
        synchronized(LOCK) {
            samplesFile.delete()
            modelFile.delete()
            windowModelFile.delete()
            verifierFile.delete()
            registry.clear()
        }
    }
}

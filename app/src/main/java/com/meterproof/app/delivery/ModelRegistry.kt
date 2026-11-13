package com.meterproof.app.delivery

import java.io.File

/**
 * سجل إصدارات نماذج الفواتير (java.io فقط ليُختبر على JVM).
 *
 * كل تدريب ناجح يُحفظ إصدارًا مرقَّمًا فيه مواصفاته ومقاييسه وقرار الاعتماد. النموذج الجديد لا يحل محل
 * «المستقر» إلا إن لم يتراجع أداؤه (انظر [decide])، وإلا يُحفظ في السجل مرفوضًا ويبقى المستقر معتمدًا.
 * يمكن الرجوع إلى إصدار سابق معتمد في أي وقت. الأوزان فقط تُحفظ، ولا بكسلات ولا صور.
 * بيانات هذا السجل خاصة بنظام الفواتير (مجلد delivery)، ولا يشاركها أي نظام آخر.
 */
class ModelRegistry(private val dir: File) {

    data class Meta(
        val version: Int,
        val trainedAt: Long,
        val device: String,
        val invoiceImages: Int,
        val noneImages: Int,
        /** مصفوفة الالتباس على مستوى الصورة (خارج العينة للمدقِّق، على صور التدريب لكاشف النوافذ وحده). */
        val tp: Int,
        val fp: Int,
        val fn: Int,
        val tn: Int,
        val precision: Float,
        val recall: Float,
        val holdoutDetected: Int,
        val holdoutInvoice: Int,
        val holdoutFalse: Int,
        val holdoutNone: Int,
        val hasVerifier: Boolean,
        val settings: String,
        val decision: String,
        val reason: String
    ) {
        val f1: Float get() = if (precision + recall <= 0f) 0f else 2f * precision * recall / (precision + recall)
        val accepted: Boolean get() = decision == ACCEPTED
    }

    class Entry(val meta: Meta, val window: WindowModel?, val verifier: VerifierModel?)

    class Decision(val accepted: Boolean, val reason: String, val entry: Entry)

    companion object {
        const val ACCEPTED = "ACCEPTED"
        const val REJECTED = "REJECTED"
        const val MAX_ENTRIES = 12

        /** حدود التراجع المسموح بها مقارنة بالمستقر. */
        const val MAX_PRECISION_DROP = 0.02f
        const val MAX_RECALL_DROP = 0.05f

        /** قرار الاعتماد (نقي): لا يُعتمد نموذج تراجعت دقته أو استدعاؤه عن المستقر، أو ساء قياسه المحجوز. */
        fun decide(prev: Meta?, cand: Meta): Pair<Boolean, String> {
            if (prev == null) return true to "FIRST_MODEL"
            if (cand.precision < prev.precision - MAX_PRECISION_DROP) return false to "PRECISION_REGRESSED"
            if (cand.recall < prev.recall - MAX_RECALL_DROP) return false to "RECALL_REGRESSED"
            if (cand.holdoutNone > 0 && prev.holdoutNone > 0) {
                val cr = cand.holdoutFalse.toFloat() / cand.holdoutNone
                val pr = prev.holdoutFalse.toFloat() / prev.holdoutNone
                if (cr > pr + 0.10f) return false to "HOLDOUT_FALSE_ACCEPT_WORSE"
            }
            return true to "NOT_WORSE"
        }
    }

    private val modelsDir = File(dir, "models").apply { mkdirs() }
    private val stableFile = File(modelsDir, "stable.txt")

    private fun file(v: Int) = File(modelsDir, "v$v.txt")

    @Synchronized
    fun list(): List<Entry> =
        (modelsDir.listFiles() ?: emptyArray())
            .filter { it.name.matches(Regex("v[0-9]+\\.txt")) }
            .mapNotNull { read(it) }
            .sortedByDescending { it.meta.version }

    @Synchronized
    fun stable(): Entry? {
        val v = try { stableFile.readText().trim().toInt() } catch (e: Exception) { return null }
        return read(file(v))
    }

    /** يحفظ مرشحًا جديدًا ويقرر اعتماده. [meta] بلا رقم إصدار ولا قرار (يُملآن هنا). */
    @Synchronized
    fun submit(meta: Meta, window: WindowModel, verifier: VerifierModel?): Decision {
        val prev = stable()?.meta
        val (ok, reason) = decide(prev, meta)
        val next = ((list().maxOfOrNull { it.meta.version }) ?: 0) + 1
        val m = meta.copy(
            version = next, hasVerifier = verifier != null,
            decision = if (ok) ACCEPTED else REJECTED,
            reason = if (ok && meta.reason.isNotEmpty()) meta.reason else reason
        )
        write(m, window, verifier)
        if (ok) stableFile.writeText(next.toString())
        prune()
        return Decision(ok, reason, Entry(m, window, verifier))
    }

    /** يعتمد إصدارًا سابقًا معتمدًا قبل المستقر الحالي. null إن لم يوجد. */
    @Synchronized
    fun rollback(): Entry? {
        val cur = stable()?.meta?.version ?: return null
        val prev = list().firstOrNull { it.meta.version < cur && it.meta.accepted && it.window != null } ?: return null
        stableFile.writeText(prev.meta.version.toString())
        return prev
    }

    /** يعتمد إصدارًا بعينه (يجب أن يكون صالحًا وقد اعتُمد يومًا). */
    @Synchronized
    fun setStable(version: Int): Boolean {
        val e = read(file(version)) ?: return false
        if (e.window == null || !e.meta.accepted) return false
        stableFile.writeText(version.toString())
        return true
    }

    /** يحذف كل الإصدارات (إعادة ضبط بيانات التدريب). */
    @Synchronized
    fun clear() {
        (modelsDir.listFiles() ?: emptyArray()).forEach { it.delete() }
    }

    /** نقل نموذج قديم محفوظ بصيغة الإصدارات السابقة كإصدار أول معتمد. */
    @Synchronized
    fun importLegacy(window: WindowModel, verifier: VerifierModel?, device: String) {
        if (list().isNotEmpty()) return
        val m = Meta(
            version = 0, trainedAt = window.trainedAt, device = device,
            invoiceImages = window.invoiceImages, noneImages = window.noneImages,
            tp = 0, fp = 0, fn = 0, tn = 0,
            precision = window.metrics.precision, recall = window.metrics.recall,
            holdoutDetected = -1, holdoutInvoice = -1, holdoutFalse = -1, holdoutNone = -1,
            hasVerifier = false, settings = "legacy", decision = ACCEPTED, reason = "IMPORTED"
        )
        submit(m, window, verifier)
    }

    private fun prune() {
        val all = list()
        val stableV = stable()?.meta?.version
        for (e in all.drop(MAX_ENTRIES)) if (e.meta.version != stableV) file(e.meta.version).delete()
    }

    private fun write(m: Meta, window: WindowModel, verifier: VerifierModel?) {
        val sb = StringBuilder()
        fun kv(k: String, v: Any) { sb.append(k).append('=').append(v.toString().replace('\n', ' ')).append('\n') }
        kv("version", m.version); kv("trainedAt", m.trainedAt); kv("device", m.device)
        kv("invoiceImages", m.invoiceImages); kv("noneImages", m.noneImages)
        kv("tp", m.tp); kv("fp", m.fp); kv("fn", m.fn); kv("tn", m.tn)
        kv("precision", m.precision); kv("recall", m.recall)
        kv("holdoutDetected", m.holdoutDetected); kv("holdoutInvoice", m.holdoutInvoice)
        kv("holdoutFalse", m.holdoutFalse); kv("holdoutNone", m.holdoutNone)
        kv("hasVerifier", m.hasVerifier); kv("settings", m.settings)
        kv("decision", m.decision); kv("reason", m.reason)
        kv("window", WindowModelCodec.encode(window))
        if (verifier != null) kv("verifier", VerifierCodec.encode(verifier))
        file(m.version).writeText(sb.toString())
    }

    private fun read(f: File): Entry? = try {
        if (!f.exists()) null
        else {
            val kv = f.readLines().filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val meta = Meta(
                version = kv.getValue("version").toInt(), trainedAt = kv.getValue("trainedAt").toLong(),
                device = kv["device"] ?: "", invoiceImages = kv.getValue("invoiceImages").toInt(),
                noneImages = kv.getValue("noneImages").toInt(),
                tp = kv.getValue("tp").toInt(), fp = kv.getValue("fp").toInt(),
                fn = kv.getValue("fn").toInt(), tn = kv.getValue("tn").toInt(),
                precision = kv.getValue("precision").toFloat(), recall = kv.getValue("recall").toFloat(),
                holdoutDetected = kv.getValue("holdoutDetected").toInt(),
                holdoutInvoice = kv.getValue("holdoutInvoice").toInt(),
                holdoutFalse = kv.getValue("holdoutFalse").toInt(),
                holdoutNone = kv.getValue("holdoutNone").toInt(),
                hasVerifier = kv["hasVerifier"] == "true", settings = kv["settings"] ?: "",
                decision = kv.getValue("decision"), reason = kv["reason"] ?: ""
            )
            Entry(meta, kv["window"]?.let { WindowModelCodec.decode(it) }, kv["verifier"]?.let { VerifierCodec.decode(it) })
        }
    } catch (e: Exception) {
        null
    }
}

package com.meterproof.app.delivery

import com.meterproof.app.privacy.Frame
import com.meterproof.app.privacy.InvoiceDetail
import com.meterproof.app.privacy.Region
import com.meterproof.app.privacy.RevealProbe
import com.meterproof.app.privacy.RevealProposal

enum class DeliveryState { DETECTED, UNCERTAIN, NONE }

/** نتيجة فحص إطار واحد. region موجودة كلما وُجد مرشح ورقة، لكن لا تُكشف إلا عند DETECTED. */
data class DeliveryResult(
    val state: DeliveryState,
    val score: Float,
    val region: Region?,
    val modelId: String
)

/**
 * كاشف تسليم الفاتورة: يحلّل الإطار ثم يقيّم السمات بالنموذج المعايَر.
 * لا نموذج معايَر = لا كاشف (لا توجد قاعدة افتراضية تدّعي الكشف دون معايرة).
 */
class DeliveryDetector(
    private val model: InvoiceModel,
    private val cfg: InvoiceAnalyzer.Config = InvoiceAnalyzer.Config()
) {
    fun detect(frame: Frame): DeliveryResult {
        val px = frame.argb()
        try {
            val c = InvoiceAnalyzer.analyze(px, frame.width, frame.height, cfg)
                ?: return DeliveryResult(DeliveryState.NONE, 0f, null, model.id)
            return classify(c, model)
        } finally {
            px.fill(0)
        }
    }

    companion object {
        fun classify(c: InvoiceCandidate, model: InvoiceModel): DeliveryResult {
            val s = model.score(c.features)
            val state = when {
                s >= model.acceptThreshold -> DeliveryState.DETECTED
                s >= model.uncertainThreshold -> DeliveryState.UNCERTAIN
                else -> DeliveryState.NONE
            }
            return DeliveryResult(state, s, c.region, model.id)
        }
    }
}

/**
 * مقترِح الكشف أثناء التصوير العادي: يقترح منطقة الفاتورة فقط عند DETECTED،
 * وتبقى last للمستدعي ليسجّل الحدث. قرار الكشف النهائي للسياسة وليس لهذا الصنف.
 */
class DeliveryRevealProbe(
    private val detector: DeliveryDetector,
    val detail: InvoiceDetail
) : RevealProbe {
    @Volatile
    var last: DeliveryResult? = null
        private set

    override fun propose(frame: Frame): RevealProposal? {
        val r = detector.detect(frame)
        last = r
        val region = r.region
        return if (r.state == DeliveryState.DETECTED && region != null) {
            RevealProposal(region, detail)
        } else {
            null
        }
    }
}

/**
 * مقترِح المعايرة: يستخرج سمات الورقة من الإطار ولا يقترح كشفًا أبدًا.
 * لا تُحفظ السمات إلا إن سلم الإطار من الأشخاص والوجوه (يقرر المستدعي بعد البوابة).
 */
class CalibrationProbe(private val cfg: InvoiceAnalyzer.Config = InvoiceAnalyzer.Config()) : RevealProbe {
    @Volatile
    var candidate: InvoiceCandidate? = null
        private set

    override fun propose(frame: Frame): RevealProposal? {
        val px = frame.argb()
        try {
            candidate = InvoiceAnalyzer.analyze(px, frame.width, frame.height, cfg)
        } finally {
            px.fill(0)
        }
        return null
    }
}

package com.meterproof.app.privacy

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * بوابة الخصوصية: كل صورة مجدولة أو صورة توقف تمر هنا قبل أي حفظ.
 *
 * - تشغّل كواشف الوجوه والأشخاص على الجهاز دون إنترنت.
 * - إن وُجد خطر أو فشل كاشف أو انتهت مهلته: لا صورة (jpeg = null) ويبقى سجل الحدث فقط.
 * - إن سلم كل شيء: صورة مجرّدة إلى كتل ألوان لا يُعرف منها شيء.
 * - لا تكشف أي منطقة بوضوح في هذه المرحلة (لا مصدر لمنطقة الفاتورة قبل المرحلة 2).
 * - لا ترمي استثناءً أبدًا: أي خطأ غير متوقع ينتهي بالإتلاف.
 *
 * الصورة الأصلية لا تُكتب على التخزين، وتبقى في الذاكرة فقط.
 */
object PrivacyGate {

    private const val MAX_SIDE = 1280
    private const val GRID_COLS = 16
    private const val BLOCK_PX = 20
    private const val JPEG_QUALITY = 90

    class Output(
        val decision: GateDecision,
        /** null يعني: أتلف الصورة ولا تحفظ شيئًا. */
        val jpeg: ByteArray?,
        val totalMs: Long,
        val latencyMs: Map<String, Long>,
        /** هل يجوز للمستدعي تحرير ذاكرة الصورة الأصلية؟ لا، إن انتهت مهلة كاشف قد يقرؤها بعد. */
        val safeToRecycle: Boolean
    )

    private val engine by lazy {
        GateEngine(listOf(FaceHazardDetector(), PersonHazardDetector()))
    }

    fun process(src: Bitmap): Output {
        val t0 = System.nanoTime()
        var work: Bitmap? = null
        var safe = true
        try {
            work = prepare(src)
            val result = engine.evaluate(BitmapFrame(work))
            safe = !result.anyTimedOut

            val jpeg = if (result.decision.tier == PrivacyTier.ABSTRACT) renderAbstract(work) else null
            // قرار REVEAL غير ممكن هنا (لا طلب كشف)، وإن ظهر لأي سبب فالأمان بالإتلاف
            val decision = if (result.decision.tier == PrivacyTier.REVEAL) {
                GateDecision(PrivacyTier.DISCARD, listOf("RENDER_FAILED"))
            } else {
                result.decision
            }
            return Output(decision, jpeg, elapsedMs(t0), result.latencyMs, safe)
        } catch (e: Throwable) {
            safe = false
            return Output(
                GateDecision(PrivacyTier.DISCARD, listOf("RENDER_FAILED")),
                null, elapsedMs(t0), emptyMap(), false
            )
        } finally {
            val w = work
            if (safe && w != null && w !== src) w.recycle()
        }
    }

    private fun elapsedMs(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000L

    /** نسخة بدقة لا تتجاوز 1280 بكسلًا بصيغة ARGB_8888 (النسخة الأصلية لا تُعدَّل). */
    private fun prepare(src: Bitmap): Bitmap {
        var b = src
        val longest = maxOf(src.width, src.height)
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE / longest.toFloat()
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            b = Bitmap.createScaledBitmap(src, w, h, true)
        }
        if (b.config != Bitmap.Config.ARGB_8888) {
            val copy = b.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("bitmap copy failed")
            if (b !== src) b.recycle()
            b = copy
        }
        return b
    }

    /** يختزل الصورة إلى شبكة كتل ألوان ويرسمها JPEG صغير؛ المصفوفات الوسيطة تُصفَّر بعد الاستعمال. */
    private fun renderAbstract(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        var big: IntArray? = null
        try {
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val cols = GRID_COLS
            val rows = (cols * h / w.toFloat()).roundToInt().coerceIn(4, 32)
            val blocks = Abstraction.blockMeans(px, w, h, cols, rows)
            val outW = cols * BLOCK_PX
            val outH = rows * BLOCK_PX
            big = Abstraction.upscale(blocks, cols, rows, outW, outH)
            val out = Bitmap.createBitmap(big, outW, outH, Bitmap.Config.ARGB_8888)
            try {
                val bos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                return bos.toByteArray()
            } finally {
                out.recycle()
            }
        } finally {
            px.fill(0)
            big?.fill(0)
        }
    }
}

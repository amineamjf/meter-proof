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
 * - لا تكشف أي منطقة إلا إن اقترحها RevealProbe (كاشف الفواتير) وسلم الإطار كله من الأخطار
 *   وكانت المنطقة أصغر من الحد المسموح (MAX_REVEAL_AREA). بلا مقترِح يبقى السلوك كما في المرحلة 1 حرفيًا.
 * - لا ترمي استثناءً أبدًا: أي خطأ غير متوقع ينتهي بالإتلاف.
 *
 * الصورة الأصلية لا تُكتب على التخزين، وتبقى في الذاكرة فقط.
 */
object PrivacyGate {

    private const val MAX_SIDE = 1280
    private const val GRID_COLS = 16
    private const val BLOCK_PX = 20
    private const val JPEG_QUALITY = 90

    /** أكبر مساحة يجوز كشفها بوضوح (نسبة من الإطار). */
    const val MAX_REVEAL_AREA = 0.5f

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
        GateEngine(
            listOf(FaceHazardDetector(), PersonHazardDetector()),
            PrivacyPolicy(PrivacyConfig(maxRevealArea = MAX_REVEAL_AREA))
        )
    }

    /**
     * probe اختياري يقترح منطقة تُكشف. يُستدعى على الإطار نفسه في الذاكرة، والقرار النهائي للسياسة:
     * خطر أو فشل كاشف في أي مكان من الإطار يُلغي الاقتراح وتُتلف الصورة.
     */
    fun process(src: Bitmap, probe: RevealProbe? = null): Output {
        val t0 = System.nanoTime()
        var work: Bitmap? = null
        var safe = true
        try {
            work = prepare(src)
            val frame = BitmapFrame(work)

            val proposal: RevealProposal? = if (probe == null) null else {
                try { probe.propose(frame) } catch (e: Throwable) { null }
            }

            val result = engine.evaluate(frame, proposal?.region)
            safe = !result.anyTimedOut

            var decision = result.decision
            var jpeg: ByteArray? = null
            when (decision.tier) {
                PrivacyTier.ABSTRACT -> jpeg = renderAbstract(work)
                PrivacyTier.REVEAL -> {
                    val region = decision.revealRegion
                    if (region != null && proposal != null) {
                        jpeg = renderRevealed(work, region, proposal.detail)
                    }
                    // لا كشف بلا اقتراح ولا رسم ناجح: الأمان بالإتلاف
                    if (jpeg == null) decision = GateDecision(PrivacyTier.DISCARD, listOf("RENDER_FAILED"))
                }
                PrivacyTier.DISCARD -> {}
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

    /** صورة T0: كل الإطار مجرّد عدا المنطقة المعتمدة (انظر RevealRender). المصفوفات الوسيطة تُصفَّر. */
    private fun renderRevealed(bmp: Bitmap, region: Region, detail: InvoiceDetail): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        var out: IntArray? = null
        try {
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val rows = (GRID_COLS * h / w.toFloat()).roundToInt().coerceIn(4, 32)
            out = RevealRender.compose(px, w, h, GRID_COLS, rows, region, detail)
            val bitmap = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            try {
                val bos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                return bos.toByteArray()
            } finally {
                bitmap.recycle()
            }
        } finally {
            px.fill(0)
            out?.fill(0)
        }
    }
}

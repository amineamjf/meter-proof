package com.meterproof.app.delivery

import com.meterproof.app.privacy.Region
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * آلة حالات التسليم: تستهلك [Feat] بالترتيب وتُصدر نتيجة واحدة لكل نافذة مراقبة.
 *
 * التسلسل المطلوب لـ "مؤكد":
 *  1) يد تحمل ورقة (ورقة ملاصقة للجلد) لمدة تراكمية كافية.
 *  2) تنسحب اليد (يختفي الجلد).
 *  3) يظهر جسم ساطع جديد بحجم ورقة، ثابت ومكانه لا يتحرك، والكاميرا هادئة.
 *
 * الحالات "غير مؤكد" (لا تُعدّ نجاحًا أبدًا):
 *  - ورقة في اليد ثم انسحبت اليد دون أن يظهر جسم ثابت (ورقة حُرّكت أو أُعيدت إلى الجيب).
 *  - اختفت الورقة واليد ما زالت ظاهرة قرب أسفل الإطار (نمط الدفع تحت الباب)، ما لم تثبت المعايرة أنه موثوق.
 *  - انتهت النافذة أو مدة التسلسل قبل الاكتمال.
 *
 * ما لا يمرّ بالخطوة (1) لا يُصدر شيئًا إطلاقًا: مشي، انحناء، لمس الباب، يد بلا ورقة.
 * منطق نقي بلا أندرويد، كل الاستدعاءات من خيط واحد.
 */
class DeliveryMachine(private val p: DeliveryParams) {

    enum class Kind { CONFIRMED, UNCERTAIN }

    data class Outcome(
        val kind: Kind,
        /** ON (على الباب أو عتبته) أو BESIDE (بجانبه) أو UNDER (تحته) أو UNKNOWN. تقدير من موضع الجسم في الإطار. */
        val place: String,
        /** موضع الجسم النهائي في الإطار مثل LOWER_CENTER. */
        val zone: String,
        /** درجة بين 0 و1 (مؤشر تقريبي وليست احتمالًا). */
        val score: Float,
        val reasons: List<String>,
        /** مربع الجسم الموضوع (نسبي) إن وُجد: منطقة الكشف الوحيدة المسموحة في صورة الدليل. */
        val region: Region?,
        val holdMs: Long,
        val durMs: Long
    )

    private enum class Phase { WATCH, HOLD, WITHDRAWN, DONE }

    private var phase = Phase.WATCH
    private var lastT = -1L

    private var holdAcc = 0L
    private var holdStartT = -1L
    private var lastHoldT = -1L

    private var lastHeldY = -1f
    private var heldGoneSince = -1L
    private var underPattern = false

    private var skinGoneSince = -1L
    private var withdrawT = -1L

    private var stableStartT = -1L
    private var stableX = 0f
    private var stableY = 0f
    private var stableW = 0f
    private var stableH = 0f

    fun reset() {
        phase = Phase.WATCH
        lastT = -1L
        holdAcc = 0L
        holdStartT = -1L
        lastHoldT = -1L
        lastHeldY = -1f
        heldGoneSince = -1L
        underPattern = false
        skinGoneSince = -1L
        withdrawT = -1L
        stableStartT = -1L
    }

    /** هل بدأ تسلسل تسليم محتمل (أُمسكت ورقة)؟ تستعمله الواجهة لإبقاء النافذة مفتوحة. */
    val inSequence: Boolean get() = phase == Phase.HOLD || phase == Phase.WITHDRAWN

    fun onFeat(f: Feat): Outcome? {
        if (phase == Phase.DONE) return null
        val dt = if (lastT < 0) 0L else (f.t - lastT).coerceIn(0L, 500L)
        lastT = f.t

        val handOn = f.skin >= p.skinMin
        val holding = handOn && f.held >= p.heldMin && f.motion <= p.motionHold

        when (phase) {
            Phase.WATCH -> {
                if (holding) {
                    if (holdStartT < 0) holdStartT = f.t
                    holdAcc += dt
                    lastHoldT = f.t
                    lastHeldY = f.heldY
                } else if (holdAcc > 0 && f.t - lastHoldT > p.graceMs) {
                    holdAcc = 0L
                    holdStartT = -1L
                }
                if (holdAcc >= p.holdMs) {
                    phase = Phase.HOLD
                    skinGoneSince = -1L
                    heldGoneSince = -1L
                }
            }

            Phase.HOLD -> {
                if (holding) {
                    holdAcc += dt
                    lastHoldT = f.t
                    lastHeldY = f.heldY
                    heldGoneSince = -1L
                } else if (handOn && f.held < p.heldMin * 0.5f) {
                    // الورقة اختفت واليد ما زالت ظاهرة
                    if (heldGoneSince < 0) heldGoneSince = f.t
                    if (f.t - heldGoneSince >= p.vanishMs && lastHeldY >= p.underY) underPattern = true
                }
                if (f.skin < p.skinMin * 0.5f) {
                    if (skinGoneSince < 0) skinGoneSince = f.t
                    if (f.t - skinGoneSince >= p.withdrawMs) {
                        phase = Phase.WITHDRAWN
                        withdrawT = f.t
                        stableStartT = -1L
                    }
                } else {
                    skinGoneSince = -1L
                }
                if (f.t - holdStartT > p.maxSeqMs) return done(f.t, listOf("SEQUENCE_TIMEOUT"))
            }

            Phase.WITHDRAWN -> {
                if (handOn) {
                    // عادت اليد (تعديل الورقة مثلًا): نرجع إلى مرحلة الإمساك ونعيد حساب الثبات
                    phase = Phase.HOLD
                    skinGoneSince = -1L
                    stableStartT = -1L
                } else {
                    val objOk = f.obj >= p.objMin && f.obj <= p.objMax && f.motion <= p.motionMax
                    if (objOk) {
                        if (stableStartT < 0 ||
                            max(abs(f.objX - stableX), abs(f.objY - stableY)) > p.moveTol
                        ) {
                            stableStartT = f.t
                            stableX = f.objX
                            stableY = f.objY
                        }
                        stableW = f.objW
                        stableH = f.objH
                        if (f.t - stableStartT >= p.restMs) return confirm(f)
                    } else {
                        stableStartT = -1L
                    }
                    if (f.t - withdrawT >= p.settleMs) {
                        return if (underPattern) {
                            done(f.t, listOf("UNDER_PATTERN"), under = true)
                        } else {
                            done(f.t, listOf("NO_OBJECT"))
                        }
                    }
                }
            }

            Phase.DONE -> {}
        }
        return null
    }

    /** نهاية النافذة: إن كان تسلسل قد بدأ ولم يكتمل فهو غير مؤكد. وإلا لا شيء. */
    fun finish(): Outcome? {
        if (phase != Phase.HOLD && phase != Phase.WITHDRAWN) return null
        return done(lastT, listOf("WINDOW_END"), under = underPattern && phase == Phase.WITHDRAWN)
    }

    private fun confirm(f: Feat): Outcome {
        phase = Phase.DONE
        val hold = min(1f, holdAcc / (p.holdMs * 3f))
        val rest = min(1f, (f.t - stableStartT) / (p.restMs * 2f))
        val quiet = 1f - min(1f, f.motion / p.motionMax)
        val score = 0.35f + 0.25f * hold + 0.25f * rest + 0.15f * quiet
        val cx = f.objX
        val cy = f.objY
        val place = if (cx < 0.30f || cx > 0.70f) "BESIDE" else "ON"
        return Outcome(
            Kind.CONFIRMED, place, zoneOf(cx, cy), score.coerceIn(0f, 1f), emptyList(),
            Region(cx - stableW / 2, cy - stableH / 2, cx + stableW / 2, cy + stableH / 2).clamped(),
            holdAcc, max(0L, f.t - holdStartT)
        )
    }

    private fun done(t: Long, reasons: List<String>, under: Boolean = false): Outcome {
        phase = Phase.DONE
        val trusted = under && p.underTrusted
        return Outcome(
            if (trusted) Kind.CONFIRMED else Kind.UNCERTAIN,
            if (under) "UNDER" else "UNKNOWN",
            if (lastHeldY >= 0f) zoneOf(0.5f, lastHeldY) else "UNKNOWN",
            if (trusted) 0.55f else 0.3f,
            reasons, null, holdAcc, max(0L, t - holdStartT)
        )
    }

    companion object {
        fun zoneOf(x: Float, y: Float): String {
            val row = when {
                y < 0.40f -> "UPPER"
                y > 0.70f -> "LOWER"
                else -> "MIDDLE"
            }
            val col = when {
                x < 0.33f -> "LEFT"
                x > 0.67f -> "RIGHT"
                else -> "CENTER"
            }
            return "${row}_$col"
        }
    }
}

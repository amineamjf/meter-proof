package com.meterproof.app

/**
 * قرارات التصوير عند التوقف والإعدادات الجاهزة (منطق نقي بلا أندرويد، يُختبر على JVM).
 *
 * القاعدة: كل توقف يُسجَّل له نقطة موقع دائمًا (حدث STOP_START)، أما الصورة فاختيارية
 * وقد تفشل؛ وعند أي فشل أو تخطٍّ يُكتب حدث بسببه مع الموقع كي لا تضيع النقطة بصمت.
 */
object CapturePolicy {

    /** خيارات مدة الثبات الجاهزة (ثوانٍ). */
    val STOP_SECONDS = intArrayOf(10, 15, 20, 30)

    /** خيارات المجال العشوائي الجاهزة (دقائق: أدنى، أقصى). */
    val INTERVALS = arrayOf(1 to 2, 2 to 5, 5 to 30)

    /** أقل فاصل بين صورتي توقف (يمنع التكرار من اهتزاز GPS أو الإشارات والطوابير). */
    const val MIN_GAP_BETWEEN_STOP_PHOTOS_MS = 15_000L

    enum class StopAction { ARRIVAL, STOP, SKIP_TOO_SOON, SKIP_DISABLED }

    /**
     * @param hasArrival وُجد عداد قريب في قاعدة الجولة (صورة وصول مربوطة)
     * @param photoOnStop هل التصوير عند كل توقف مفعّل (يعمل حتى بلا قاعدة بيانات)
     * @param lastStopPhotoAtMs وقت آخر صورة توقف (0 = لا شيء)
     */
    fun decideStop(
        hasArrival: Boolean,
        photoOnStop: Boolean,
        nowMs: Long,
        lastStopPhotoAtMs: Long
    ): StopAction {
        if (!hasArrival && !photoOnStop) return StopAction.SKIP_DISABLED
        if (lastStopPhotoAtMs > 0 && nowMs - lastStopPhotoAtMs < MIN_GAP_BETWEEN_STOP_PHOTOS_MS) {
            return StopAction.SKIP_TOO_SOON
        }
        return if (hasArrival) StopAction.ARRIVAL else StopAction.STOP
    }

    /** بعد هذه المدة من التوقف تُعدّ الوقفة «طويلة» فلا تُلتقط صور عشوائية أثناءها. */
    const val PROLONGED_STOP_MS = 120_000L

    /**
     * هل تُتخطّى الصورة العشوائية المجدولة؟ نعم أثناء توقف حالي إذا (أ) التُقطت له صورة وصول/توقف أصلًا،
     * أو (ب) طال التوقف عن [PROLONGED_STOP_MS]. أثناء الحركة أو في أول التوقف بلا صورة: لا تخطّي.
     * الصورة التلقائية عند الوصول (decideStop) لا تتأثر بهذا.
     */
    fun skipRandomDuringStop(
        stopped: Boolean,
        stopSinceMs: Long,
        lastStopPhotoAtMs: Long,
        nowMs: Long
    ): Boolean {
        if (!stopped || stopSinceMs <= 0L) return false
        if (lastStopPhotoAtMs >= stopSinceMs) return true
        return nowMs - stopSinceMs >= PROLONGED_STOP_MS
    }

    /** رموز ثابتة فقط (لا نصوص حرة): تُحفظ في تفاصيل الحدث المبصوم. */
    object Reason {
        const val NO_CAMERA_PERMISSION = "NO_CAMERA_PERMISSION"
        const val CAMERA_OPEN_FAILED = "CAMERA_OPEN_FAILED"
        const val CAPTURE_ERROR = "CAPTURE_ERROR"
        const val PROCESS_FAILED = "PROCESS_FAILED"
        const val SAVE_FAILED = "SAVE_FAILED"
        const val GATE_DISCARDED = "GATE_DISCARDED"
        const val BUSY = "BUSY"
        const val TOO_SOON = "TOO_SOON"
    }

    const val EVENT_FAILED = "CAPTURE_FAILED"

    fun failureDetail(mode: String, reason: String): String =
        "mode=${clean(mode)};reason=${clean(reason)}"

    private fun clean(s: String): String =
        s.filter { it.isLetterOrDigit() || it == '_' }.take(40)

    /** رقم الخيار الجاهز المطابق للقيم الحالية، أو -1 إن كانت قيمة مخصصة. */
    fun intervalIndex(minM: Int, maxM: Int): Int = INTERVALS.indexOfFirst { it.first == minM && it.second == maxM }

    fun stopIndex(seconds: Int): Int = STOP_SECONDS.indexOf(seconds)
}

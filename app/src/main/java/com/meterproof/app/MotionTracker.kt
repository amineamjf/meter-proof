package com.meterproof.app

import kotlin.math.max
import kotlin.math.min

/**
 * آلة حالات التتبع الذكي (منطق نقي قابل للاختبار، بلا اعتماد على أندرويد).
 *
 * - متحرك: نسجل نقطة كل [Config.recordIntervalMs] أو كل [Config.recordDistanceM] متر.
 * - توقف قصير (ثوانٍ): عندما يعلن مقياس التسارع السكون نسجل آخر موقع معروف مرة واحدة (brief).
 * - توقف طويل: عندما نبقى داخل دائرة نصف قطرها [Config.radiusM] مدة [Config.stopAfterMs]
 *   نعلن بداية توقف (حدث واحد) ونتوقف عن تسجيل النقاط ونخفض وتيرة GPS،
 *   وعند الخروج من الدائرة نغلق الحدث بمدته ونعود للتسجيل.
 *
 * كل الاستدعاءات من خيط واحد (الخيط الرئيسي في الخدمة).
 */
class MotionTracker(private val cfg: Config, private val sink: Sink) {

    data class Config(
        val stopAfterMs: Long = 30_000L,
        val radiusM: Double = 25.0,
        val recordIntervalMs: Long = 20_000L,
        val recordDistanceM: Double = 12.0,
        val maxAccuracyM: Float = 40f,
        val maxFixAgeForBriefMs: Long = 8_000L,
        val exitSpeedMps: Float = 1.2f
    )

    data class Fix(
        val t: Long,
        val lat: Double,
        val lon: Double,
        val acc: Float,
        val speed: Float?
    )

    interface Sink {
        fun onTrackPoint(fix: Fix, reason: String)
        fun onStopStart(fix: Fix, sinceMs: Long)
        fun onStopEnd(startMs: Long, endMs: Long, fix: Fix)
        fun onGpsFast(fast: Boolean)
    }

    var stopped: Boolean = false
        private set

    private var anchor: Fix? = null
    private var best: Fix? = null
    private var lastRecorded: Fix? = null
    private var lastFix: Fix? = null
    private var stopStartMs = 0L
    private var motionSinceMs: Long? = null

    private fun dist(a: Fix, b: Fix): Double = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)

    fun onFix(f: Fix) {
        if (f.acc > cfg.maxAccuracyM) return
        lastFix = f

        if (!stopped) {
            val a = anchor
            if (a == null || dist(a, f) > max(cfg.radiusM, max(f.acc, a.acc).toDouble())) {
                anchor = f
                best = f
            } else if (f.acc < (best?.acc ?: Float.MAX_VALUE)) {
                best = f
            }

            val lr = lastRecorded
            val far = lr != null && dist(lr, f) >= max(cfg.recordDistanceM, f.acc.toDouble())
            val due = lr == null || f.t - lr.t >= cfg.recordIntervalMs || far
            if (due) {
                sink.onTrackPoint(f, "move")
                lastRecorded = f
            }

            val an = anchor ?: return
            if (f.t - an.t >= cfg.stopAfterMs) {
                stopped = true
                stopStartMs = an.t
                motionSinceMs = null
                sink.onStopStart(best ?: an, stopStartMs)
                sink.onGpsFast(false)
            }
        } else {
            val an = anchor ?: return
            val d = dist(an, f)
            val exited = d > max(cfg.radiusM, max(f.acc, an.acc).toDouble()) ||
                (f.speed != null && f.speed >= cfg.exitSpeedMps && d > cfg.radiusM / 2)
            if (exited) {
                val end = max(stopStartMs, min(f.t, motionSinceMs ?: f.t))
                sink.onStopEnd(stopStartMs, end, best ?: an)
                stopped = false
                anchor = f
                best = f
                motionSinceMs = null
                sink.onGpsFast(true)
                sink.onTrackPoint(f, "resume")
                lastRecorded = f
            }
        }
    }

    /** حدث من مقياس التسارع: moving=true بدأت حركة، false بدأ سكون. */
    fun onMotion(tMs: Long, moving: Boolean) {
        if (moving) {
            if (stopped) {
                if (motionSinceMs == null) motionSinceMs = tMs
                sink.onGpsFast(true)
            }
        } else {
            if (stopped) {
                motionSinceMs = null
                sink.onGpsFast(false)
            } else {
                val f = lastFix ?: return
                val lr = lastRecorded
                if (tMs - f.t <= cfg.maxFixAgeForBriefMs && (lr == null || f.t > lr.t)) {
                    sink.onTrackPoint(f, "brief")
                    lastRecorded = f
                }
            }
        }
    }

    /** نهاية الجلسة: إن كنا في توقف نغلقه كي لا يبقى حدث مفتوحًا. */
    fun finish(tMs: Long) {
        if (stopped) {
            val an = anchor
            val rep = best ?: an
            if (rep != null) sink.onStopEnd(stopStartMs, max(stopStartMs, tMs), rep)
        }
        reset()
    }

    fun reset() {
        stopped = false
        anchor = null
        best = null
        lastRecorded = null
        lastFix = null
        motionSinceMs = null
    }
}

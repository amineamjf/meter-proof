package com.meterproof.app

import kotlin.math.abs

/**
 * يكشف تغيير ساعة الجهاز: الساعة المدنية (currentTimeMillis) تتحرك مع التغيير اليدوي،
 * أما elapsedRealtime فلا تتأثر. إن اختلف تقدّم الساعتين بأكثر من التسامح فقد عُدّلت الساعة.
 */
class ClockWatch(private val toleranceMs: Long = 60_000L) {
    private var lastWall = -1L
    private var lastElapsed = -1L

    /** يعيد true إن اكتُشف قفز في الساعة منذ آخر استدعاء. */
    fun check(wallMs: Long, elapsedMs: Long): Boolean {
        if (lastWall < 0) {
            lastWall = wallMs
            lastElapsed = elapsedMs
            return false
        }
        val drift = (wallMs - lastWall) - (elapsedMs - lastElapsed)
        lastWall = wallMs
        lastElapsed = elapsedMs
        return abs(drift) > toleranceMs
    }
}

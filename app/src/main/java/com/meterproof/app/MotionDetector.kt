package com.meterproof.app

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * يكشف الحركة والسكون من قراءات مقياس التسارع (منطق نقي قابل للاختبار).
 *
 * الفكرة: نقيس مقدار شعاع التسارع، ونطرح منه متوسطه البطيء (الجاذبية)، فيبقى "اهتزاز الحركة".
 * أثناء المشي يكون الاهتزاز كبيرًا، وعند الوقوف يقارب الصفر. نستعمل عتبتين (تباطؤ) وزمن تثبيت
 * حتى لا تتأرجح الحالة. الأرقام الافتراضية تقديرية وتُضبط من التجربة الميدانية.
 */
class MotionDetector(
    private val moveThreshold: Double = 0.7,
    private val stillThreshold: Double = 0.25,
    private val moveHoldMs: Long = 1_500L,
    private val stillHoldMs: Long = 1_000L
) {
    private var baseline = 9.81
    private var energy = 0.0
    private var moving = false
    private var candidateSince = -1L

    val isMoving: Boolean get() = moving

    /**
     * يعيد true عند بدء الحركة، وfalse عند بدء السكون، وnull إن لم تتغير الحالة.
     * الحالة الابتدائية: ساكن.
     */
    fun onSample(tMs: Long, x: Float, y: Float, z: Float): Boolean? {
        val mag = sqrt((x * x + y * y + z * z).toDouble())
        baseline += 0.02 * (mag - baseline)
        energy += 0.2 * (abs(mag - baseline) - energy)

        if (!moving) {
            if (energy > moveThreshold) {
                if (candidateSince < 0) candidateSince = tMs
                if (tMs - candidateSince >= moveHoldMs) {
                    moving = true
                    candidateSince = -1
                    return true
                }
            } else {
                candidateSince = -1
            }
        } else {
            if (energy < stillThreshold) {
                if (candidateSince < 0) candidateSince = tMs
                if (tMs - candidateSince >= stillHoldMs) {
                    moving = false
                    candidateSince = -1
                    return false
                }
            } else {
                candidateSince = -1
            }
        }
        return null
    }
}

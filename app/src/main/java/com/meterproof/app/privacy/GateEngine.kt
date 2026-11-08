package com.meterproof.app.privacy

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * إطار صورة بأبسط شكل. على أندرويد تغلّفه BitmapFrame، وفي الاختبارات إطار وهمي.
 * argb() يعيد مصفوفة بكسلات ARGB بحجم width*height.
 */
interface Frame {
    val width: Int
    val height: Int
    fun argb(): IntArray
}

/** كاشف خطر واحد. أي استثناء يُعدّ فشلًا (والفشل يعني إتلاف الصورة). */
interface HazardDetector {
    /** اسم ثابت بأحرف إنجليزية كبيرة (يُكتب في سجل الحدث). */
    val name: String

    @Throws(Exception::class)
    fun detect(frame: Frame): List<Hazard>
}

/**
 * محرك البوابة: يشغّل كل الكواشف على الإطار، لكل منها مهلة، ثم يمرّر النتائج إلى السياسة.
 *
 * - أي استثناء أو انتهاء مهلة يتحول إلى DetectorOutcome.Failed، فالقرار النهائي إتلاف.
 * - evaluate لا ترمي استثناءً أبدًا.
 */
class GateEngine(
    private val detectors: List<HazardDetector>,
    private val policy: PrivacyPolicy = PrivacyPolicy(),
    private val timeoutMs: Long = 10_000L
) {
    data class Result(
        val decision: GateDecision,
        val outcomes: List<DetectorOutcome>,
        val latencyMs: Map<String, Long>
    ) {
        /** هل انتهت مهلة كاشف؟ إن حدث فقد يكون ما زال يقرأ الإطار، فلا يجوز تحرير ذاكرته. */
        val anyTimedOut: Boolean
            get() = outcomes.any { it is DetectorOutcome.Failed && it.reason == FailReason.TIMEOUT }
    }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "privacy-gate").apply { isDaemon = true }
    }

    fun evaluate(frame: Frame, reveal: Region? = null): Result {
        val outcomes = ArrayList<DetectorOutcome>(detectors.size)
        val latency = LinkedHashMap<String, Long>()
        for (d in detectors) {
            val t0 = System.nanoTime()
            outcomes.add(run(d, frame))
            latency[d.name] = (System.nanoTime() - t0) / 1_000_000L
        }
        val decision = try {
            policy.decide(outcomes, reveal)
        } catch (e: Throwable) {
            GateDecision(PrivacyTier.DISCARD, listOf("POLICY_FAILED"))
        }
        return Result(decision, outcomes, latency)
    }

    private fun run(d: HazardDetector, frame: Frame): DetectorOutcome {
        return try {
            val future = pool.submit(Callable<List<Hazard>> { d.detect(frame) })
            try {
                DetectorOutcome.Ok(d.name, future.get(timeoutMs, TimeUnit.MILLISECONDS))
            } catch (e: TimeoutException) {
                future.cancel(true)
                DetectorOutcome.Failed(d.name, FailReason.TIMEOUT)
            }
        } catch (e: ExecutionException) {
            DetectorOutcome.Failed(d.name, FailReason.EXCEPTION)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            DetectorOutcome.Failed(d.name, FailReason.EXCEPTION)
        } catch (e: Throwable) {
            DetectorOutcome.Failed(d.name, FailReason.EXCEPTION)
        }
    }
}

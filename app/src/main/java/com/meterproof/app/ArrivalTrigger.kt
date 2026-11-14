package com.meterproof.app

/**
 * قرار صورة «الوصول» فور الاقتراب من إحداثية عداد في الجولة، دون انتظار توقف طويل (منطق نقي يُختبر على JVM).
 *
 * قبل هذا كانت صورة الوصول لا تُلتقط إلا بعد توقف مدته stopAfterSeconds (30 ثانية افتراضيًا)، فيمر العامل بالعداد
 * ولا يقف طويلًا فتأتي الصور بعيدة عنه. الآن: إن صار العامل داخل نطاق الوصول (RoundLogic.arrivalRadiusM) من عداد
 * لم يُوثَّق وصوله، وهو ليس ماشيًا ولا مسرعًا، تُلتقط صورة وصول واحدة.
 *
 * صورة الوصول لا تعني تسليم الفاتورة، وغياب الكشف لا يعني عدم التسليم (القاعدتان كما هما).
 */
object ArrivalTrigger {

    /** أعلى سرعة GPS تُعدّ «شبه واقف». فوقها (دراجة، سيارة) ننتظر التباطؤ، فتأتي الصورة غير مهتزة. */
    const val MAX_SPEED_MPS = 1.5f

    /** فحص قاعدة العدادات كل هذه المدة على الأكثر (لا في كل قراءة GPS). */
    const val CHECK_EVERY_MS = 2_000L

    /** إعادة المحاولة على العداد نفسه (إن فشلت الصورة أو أُتلفت) لا قبل هذه المدة. */
    const val PER_METER_COOLDOWN_MS = 10 * 60_000L

    /** أقل فاصل بين صورتين من هذا النوع. */
    const val MIN_GAP_MS = 8_000L

    /** هل حركة العامل الآن تسمح بالتصوير؟ ماشٍ أو سرعة أعلى من الحد: لا. سرعة مجهولة: تُقبل. */
    fun motionOk(walking: Boolean, speedMps: Float?): Boolean {
        if (walking) return false
        if (speedMps != null && speedMps > MAX_SPEED_MPS) return false
        return true
    }

    /**
     * @param lastAttemptOnMeterMs وقت آخر محاولة على هذا العداد (0 = لا شيء)
     * @param lastTriggerMs وقت آخر صورة من هذا النوع على أي عداد (0 = لا شيء)
     */
    fun shouldFire(nowMs: Long, lastAttemptOnMeterMs: Long, lastTriggerMs: Long): Boolean {
        if (lastAttemptOnMeterMs > 0 && nowMs - lastAttemptOnMeterMs < PER_METER_COOLDOWN_MS) return false
        if (lastTriggerMs > 0 && nowMs - lastTriggerMs < MIN_GAP_MS) return false
        return true
    }
}

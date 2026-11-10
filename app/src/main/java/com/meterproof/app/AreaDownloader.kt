package com.meterproof.app

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * يحمّل قطع المنطقة واحدة واحدة على خيط خلفي. التقدّم محفوظ على القرص (ملف لكل قطعة مكتملة)،
 * فالإلغاء أو انقطاع الشبكة لا يضيّع ما اكتمل، ويكمل الضغط التالي من حيث توقف.
 *
 * الاستدعاءات على الـ Listener تأتي من الخيط الخلفي؛ على الواجهة أن تنقلها إلى خيط الواجهة.
 */
class AreaDownloader(
    private val store: AreaStore,
    private val client: OverpassClient = OverpassClient()
) {

    interface Listener {
        /** done: عدد القطع المكتملة (بما فيها السابقة)، total: كل القطع المطلوبة. */
        fun onProgress(done: Int, total: Int, bytes: Long)

        fun onFinished()

        /** cancelled = true إن ألغى المستخدم؛ وإلا reason سبب التوقف (شبكة، خادم، مساحة). */
        fun onStopped(reason: String, cancelled: Boolean)
    }

    private val cancelled = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    val running: Boolean get() = thread?.isAlive == true

    fun cancel() {
        cancelled.set(true)
    }

    fun start(cells: List<AreaCell>, listener: Listener) {
        if (running) return
        cancelled.set(false)
        val t = Thread {
            run(cells, listener)
        }
        t.isDaemon = true
        thread = t
        t.start()
    }

    private fun run(cells: List<AreaCell>, listener: Listener) {
        val total = cells.size
        var done = cells.count { store.has(it) }
        var bytes = store.bytes()
        listener.onProgress(done, total, bytes)

        for (cell in cells) {
            if (cancelled.get()) {
                listener.onStopped("أُلغي التحميل", true)
                return
            }
            if (store.has(cell)) continue
            try {
                val ways = client.fetch(AreaPlan.boundsOf(cell)) { cancelled.get() }
                store.write(cell, ways)
            } catch (e: IOException) {
                if (cancelled.get()) {
                    listener.onStopped("أُلغي التحميل", true)
                } else {
                    listener.onStopped(describe(e), false)
                }
                return
            } catch (e: Exception) {
                listener.onStopped("خطأ غير متوقع (${e.javaClass.simpleName})", false)
                return
            }
            done++
            bytes = store.bytes()
            listener.onProgress(done, total, bytes)
            // مهلة قصيرة بين القطع: لطف بالخوادم العامة
            try {
                Thread.sleep(600)
            } catch (e: InterruptedException) {
                listener.onStopped("أُلغي التحميل", true)
                return
            }
        }
        listener.onFinished()
    }

    private fun describe(e: IOException): String {
        val m = e.message ?: ""
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException ->
                "لا يوجد اتصال بالإنترنت. اضغط «متابعة» حين يعود"
            e is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال. اضغط «متابعة»"
            m.contains("No space", ignoreCase = true) || m.contains("ENOSPC") ->
                "امتلأت مساحة الهاتف"
            else -> "توقف التحميل: $m. اضغط «متابعة»"
        }
    }
}

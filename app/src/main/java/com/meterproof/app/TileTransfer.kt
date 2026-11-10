package com.meterproof.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast

/**
 * تحميل منطقة من البلاطات إلى الجهاز (زر «تحميل الخارطة» في الشاشة الرئيسية والخارطة الكاملة).
 * منقول من دالة dl() في smart-remplacement: تأكيد بعدد الصور والحجم، ثم 6 تحميلات متوازية،
 * وتقدّم يظهر، ثم رسالة بالنتيجة. ما نُزِّل يبقى ويعمل دون إنترنت.
 */
object TileTransfer {

    @Volatile private var cancelFlag = false
    @Volatile var running = false
        private set

    /** آخر نص تقدّم/نتيجة للعرض في الشاشة الرئيسية (null = لا شيء). */
    @Volatile var status: String? = null
        private set

    fun cancel() {
        cancelFlag = true
    }

    fun templateNow(context: android.content.Context): String {
        val s = Settings(context)
        return TileLayers.resolve(s.tileUrl, s.tileSat)
    }

    @Volatile private var cachedInfo = ""
    @Volatile private var cachedAt = 0L

    /** يصف المخزن للعرض. الأرقام تُخزَّن 15 ثانية كي لا يُمسح الجدول كل تحديث للشاشة. */
    fun describe(context: android.content.Context): String {
        val now = System.currentTimeMillis()
        val template = templateNow(context)
        val layer = when {
            Settings(context).tileUrl.isNotBlank() -> "رابط مخصص"
            TileLayers.isSatellite(template) -> "قمر صناعي"
            else -> "مخطط"
        }
        if (now - cachedAt > 15_000L || cachedInfo.isEmpty()) {
            val c = try { TileCache.get(context) } catch (e: Exception) { null }
            val n = c?.count() ?: 0L
            val mb = (c?.sizeBytes() ?: 0L) / (1024 * 1024)
            cachedInfo = "البلاطات المخزّنة على الجهاز: $n (≈ $mb ميغابايت)"
            cachedAt = now
        }
        return "الطبقة الحالية: $layer. $cachedInfo"
    }

    /** يطلب التأكيد ثم يحمّل. onUpdate يُستدعى على خيط الواجهة بنص التقدم، وonDone عند الانتهاء. */
    fun confirmAndDownload(act: Activity, box: GeoBox?, onUpdate: () -> Unit) {
        if (running) {
            Toast.makeText(act, "التحميل جارٍ. اضغط الزر نفسه لإلغائه", Toast.LENGTH_SHORT).show()
            return
        }
        if (box == null) {
            Toast.makeText(act, "لا توجد إحداثيات (مسار أو عدادات أو صور) لتحديد المنطقة", Toast.LENGTH_LONG).show()
            return
        }
        if (!Net.online(act)) {
            Toast.makeText(act, "يلزم اتصال بالإنترنت لتحميل الخارطة (مرة واحدة)", Toast.LENGTH_LONG).show()
            return
        }
        val template = templateNow(act)
        val plan = TilePlans.plan(box)
        if (plan.isEmpty) {
            Toast.makeText(act, "المنطقة كبيرة جدًا", Toast.LENGTH_LONG).show()
            return
        }
        val mb = plan.estimateMb(TileLayers.avgKb(template))
        AlertDialog.Builder(act)
            .setTitle("تحميل الخارطة")
            .setMessage(
                "${plan.total} صورة (≈ $mb ميغابايت)، التقريب ${plan.zMin} إلى ${plan.zMax}.\n" +
                    "تُحمَّل من مزوّد الخارطة عبر الإنترنت، ويُرسل إليه موقع المنطقة. متابعة؟"
            )
            .setPositiveButton("متابعة") { _, _ -> start(act, template, plan, onUpdate) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun start(act: Activity, template: String, plan: TilePlan, onUpdate: () -> Unit) {
        cancelFlag = false
        running = true
        status = "⬇ 0 / ${plan.total}"
        onUpdate()
        val app = act.applicationContext
        Thread {
            val cache = TileCache.get(app)
            val r = TileDownloader(HttpTileFetcher(), cache, template).run(
                plan.tiles, { cancelFlag }
            ) { done, total ->
                if (done % 10 == 0 || done == total) {
                    status = "⬇ $done / $total"
                    act.runOnUiThread { onUpdate() }
                }
            }
            val msg = when {
                r.aborted != null -> "توقّف التحميل: ${r.aborted}"
                r.cancelled -> "أُلغي التحميل. المتاح الآن دون إنترنت: ${r.available} من ${r.total}"
                r.available == 0 ->
                    "❌ فشل التحميل (${r.firstError}). جرّب الطبقة الأخرى أو اتصالًا آخر"
                else ->
                    "✔ حُفظت الخارطة: ${r.available} / ${r.total} صورة" +
                        (if (r.failed > 0) " (${r.failed} إخفاق: ${r.firstError})" else "") +
                        ". تعمل الآن دون إنترنت"
            }
            running = false
            status = null
            cachedAt = 0L
            act.runOnUiThread {
                onUpdate()
                try {
                    Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    // الشاشة أُغلقت
                }
            }
        }.start()
    }

    /** يحذف كل البلاطات المخزّنة. */
    fun clear(context: android.content.Context) {
        TileCache.get(context).clear()
        status = null
        cachedAt = 0L
    }
}

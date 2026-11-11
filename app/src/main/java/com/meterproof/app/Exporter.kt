package com.meterproof.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تصدير الأدلة إلى مجلد التنزيلات (Download/MeterProof/<التاريخ>/):
 *   images/           الصور بعد التشويه بصيغة jpg (كما هي، دون أي تعديل)
 *   records.jsonl     سجل الصور وسلسلة البصمات
 *   track.jsonl       مسار GPS
 *   events.jsonl      أحداث التوقف والتلاعب وبداية/نهاية الجلسات (بسلسلة بصمات)
 *   summary_today.pdf ملخص اليوم الحالي
 *   verification.txt  نتيجة التحقق من السلسلة لحظة التصدير
 *   map.html          الخارطة التفاعلية (تُفتح في المتصفح، وتحتاج إنترنت لعرض الخارطة فقط)
 *
 * الملفات المصدّرة نسخ؛ الأصل يبقى في مجلد التطبيق الخاص ولا يُحذف.
 */
object Exporter {

    data class Result(val ok: Boolean, val message: String)

    fun exportAll(context: Context, store: EvidenceStore): Result {
        return try {
            val images = store.imageFiles()
            if (images.isEmpty()) return Result(false, "لا توجد صور لتصديرها")

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = "MeterProof/$stamp"

            for (f in images) {
                write(context, "$dir/images", f.name, "image/jpeg") { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
            }
            copyIfExists(context, dir, store.recordsFileRaw(), "records.jsonl")
            copyIfExists(context, dir, store.trackFileRaw(), "track.jsonl")
            copyIfExists(context, dir, store.eventsFileRaw(), "events.jsonl")

            val verification = store.verify()
            write(context, dir, "verification.txt", "text/plain") { out ->
                out.write((verification + "\n").toByteArray(Charsets.UTF_8))
            }

            val stops = StopPairing.pair(store.events())
            val allMeters = try {
                RoundDb(context).markers()
            } catch (e: Exception) {
                emptyList()
            }
            val html = MapHtml.build(
                store.trackPoints(), store.photoPoints(), stops, withImages = true, meters = allMeters
            )
            write(context, dir, "map.html", "text/html") { out ->
                out.write(html.toByteArray(Charsets.UTF_8))
            }

            // ملخص اليوم الحالي بصيغة PDF (فشله لا يُسقط بقية التصدير)
            try {
                val (s, e) = Days.range(0)
                // من مخزن الجهاز فقط (لا شبكة في «تصدير الكل»)
                val pdf = summaryPdfBytes(
                    store, s, e, verification, visitedMeters(context, s, e), ReportTiles.source(context)
                )
                write(context, dir, "summary_today.pdf", "application/pdf") { out -> out.write(pdf.bytes) }
            } catch (ex: Exception) {
                // نتابع بدون الملخص
            }

            Result(true, "تم تصدير ${images.size} صورة إلى: التنزيلات/$dir")
        } catch (e: Exception) {
            Result(false, "فشل التصدير: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * بلاطات خرائط تقرير هذا اليوم: ما يلزم وما هو ناقص من مخزن الجهاز، لعرضه على المستخدم قبل أي جلب.
     * null إن لم تكن للتقرير نقاط موقع. يُستدعى من خيط خلفي (يقرأ قاعدة البيانات).
     */
    fun prepareSummaryMap(
        context: Context, store: EvidenceStore, dayStartMs: Long, dayEndMs: Long
    ): ReportTiles.Prep? = try {
        val d = dayData(store, dayStartMs, dayEndMs)
        val plan = PdfReport.mapPlan(
            d.track, d.photos, d.summary.stops, visitedMeters(context, dayStartMs, dayEndMs)
        )
        if (plan.overview == null) null else ReportTiles.prepare(context, plan)
    } catch (e: Exception) {
        null
    }

    /**
     * يصدّر ملخص يوم واحد (PDF) إلى Download/MeterProof/.
     * [fetchMissing]: يجلب بلاطات الخارطة الناقصة قبل الرسم (بموافقة المستخدم فقط، ومع اتصال).
     */
    fun exportSummaryPdf(
        context: Context, store: EvidenceStore, dayStartMs: Long, dayEndMs: Long,
        fetchMissing: Boolean = false
    ): Result {
        return try {
            val meters = visitedMeters(context, dayStartMs, dayEndMs)
            var fetchNote = ""
            if (fetchMissing && Net.online(context)) {
                val prep = prepareSummaryMap(context, store, dayStartMs, dayEndMs)
                if (prep != null && prep.missing.isNotEmpty()) {
                    val r = ReportTiles.fetch(context, prep)
                    fetchNote = when {
                        r.aborted != null -> "\nتوقف جلب الخارطة: ${r.aborted}"
                        r.failed > 0 -> "\nجُلبت ${r.ok} صورة للخارطة وتعذّر ${r.failed} (${r.firstError})"
                        else -> "\nجُلبت ${r.ok} صورة للخارطة"
                    }
                }
            }
            val built = summaryPdfBytes(
                store, dayStartMs, dayEndMs, store.verify(), meters, ReportTiles.source(context)
            )
            val name = "summary_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dayStartMs)) + ".pdf"
            write(context, "MeterProof", name, "application/pdf") { out -> out.write(built.bytes) }
            Result(true, "تم حفظ الملخص: التنزيلات/MeterProof/$name" + mapNote(built.map) + fetchNote)
        } catch (e: Exception) {
            Result(false, "فشل تصدير الملخص: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** وصف صادق لخلفية الخارطة في الملف الناتج. */
    private fun mapNote(o: ReportMapOutcome?): String = when {
        o == null -> ""
        o.usedTiles && o.complete ->
            "\nالخارطة بخلفية القمر الصناعي" + (if (o.details > 0) " و${o.details} خرائط تفصيلية" else "")
        o.usedTiles ->
            "\nخلفية الخارطة ناقصة في بعض المواضع (بلاطات غير مخزنة)" +
                (if (o.details > 0) "، و${o.details} خرائط تفصيلية" else "")
        else -> "\nبلا خلفية خارطة: لا بلاطات مخزنة للمنطقة. حمّل الخارطة أو صدّر مع اتصال"
    }

    /** عدادات الجولة التي زيرت في هذا اليوم فقط (وصول أو تصوير)، كي لا تتضخم خارطة التقرير. */
    private fun visitedMeters(context: Context, dayStartMs: Long, dayEndMs: Long): List<MeterPoint> = try {
        RoundDb(context).markers().filter { it.visitedAt in dayStartMs until dayEndMs }
    } catch (e: Exception) {
        emptyList()
    }

    private class DayData(val now: Long, val summary: DaySummary, val track: List<GeoPoint>, val photos: List<PhotoPoint>)

    private fun dayData(store: EvidenceStore, dayStartMs: Long, dayEndMs: Long): DayData {
        val now = System.currentTimeMillis()
        val summary = DaySummaryBuilder.build(
            dayStartMs, dayEndMs, now, store.timedTrack(), store.events(), store.photoTimes()
        )
        val dayTrack = store.timedTrack()
            .filter { it.t in dayStartMs until dayEndMs }
            .map { GeoPoint(it.lat, it.lon) }
        val dayPhotos = store.photoPoints().filter { it.timeMillis in dayStartMs until dayEndMs }
        return DayData(now, summary, dayTrack, dayPhotos)
    }

    private class PdfBuild(val bytes: ByteArray, val map: ReportMapOutcome?)

    /**
     * يبني ملف الملخص. إن فشل رسم خلفية الخارطة لأي سبب يُعاد البناء بلا خلفية (السلوك القديم)،
     * فلا يضيع التقرير بسبب الخارطة.
     */
    private fun summaryPdfBytes(
        store: EvidenceStore, dayStartMs: Long, dayEndMs: Long, verification: String,
        meters: List<MeterPoint> = emptyList(), map: ReportMapSource? = null
    ): PdfBuild {
        val d = dayData(store, dayStartMs, dayEndMs)
        fun build(m: ReportMapSource?): PdfBuild {
            val bos = ByteArrayOutputStream()
            val outcome = PdfReport.write(d.summary, d.now, d.track, d.photos, verification, bos, meters, m)
            return PdfBuild(bos.toByteArray(), if (m != null) outcome else null)
        }
        return try {
            build(map)
        } catch (e: Throwable) {
            if (map == null) throw e
            build(null)
        }
    }

    private fun copyIfExists(context: Context, dir: String, src: File, name: String) {
        if (!src.exists()) return
        write(context, dir, name, "application/octet-stream") { out ->
            src.inputStream().use { it.copyTo(out) }
        }
    }

    /** يكتب ملفًا داخل Download/<relDir>. أندرويد 10+ عبر MediaStore (دون صلاحيات)، وأقدم عبر الملفات. */
    private fun write(
        context: Context,
        relDir: String,
        name: String,
        mime: String,
        body: (OutputStream) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relDir)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("تعذر إنشاء الملف $name")
            val out = resolver.openOutputStream(uri) ?: throw IOException("تعذر فتح الملف $name")
            out.use(body)
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(base, relDir)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("تعذر إنشاء المجلد $relDir")
            File(dir, name).outputStream().use(body)
        }
    }
}

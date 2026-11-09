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
            val html = MapHtml.build(store.trackPoints(), store.photoPoints(), stops, withImages = true)
            write(context, dir, "map.html", "text/html") { out ->
                out.write(html.toByteArray(Charsets.UTF_8))
            }

            // ملخص اليوم الحالي بصيغة PDF (فشله لا يُسقط بقية التصدير)
            try {
                val (s, e) = Days.range(0)
                val pdf = summaryPdfBytes(context, store, s, e, verification)
                write(context, dir, "summary_today.pdf", "application/pdf") { out -> out.write(pdf) }
            } catch (ex: Exception) {
                // نتابع بدون الملخص
            }

            Result(true, "تم تصدير ${images.size} صورة إلى: التنزيلات/$dir")
        } catch (e: Exception) {
            Result(false, "فشل التصدير: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** يصدّر ملخص يوم واحد (PDF) إلى Download/MeterProof/. */
    fun exportSummaryPdf(
        context: Context, store: EvidenceStore, dayStartMs: Long, dayEndMs: Long
    ): Result {
        return try {
            val pdf = summaryPdfBytes(context, store, dayStartMs, dayEndMs, store.verify())
            val name = "summary_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(dayStartMs)) + ".pdf"
            write(context, "MeterProof", name, "application/pdf") { out -> out.write(pdf) }
            Result(true, "تم حفظ الملخص: التنزيلات/MeterProof/$name")
        } catch (e: Exception) {
            Result(false, "فشل تصدير الملخص: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun summaryPdfBytes(
        context: Context, store: EvidenceStore, dayStartMs: Long, dayEndMs: Long, verification: String
    ): ByteArray {
        val now = System.currentTimeMillis()
        val summary = DaySummaryBuilder.build(
            dayStartMs, dayEndMs, now, store.timedTrack(), store.events(), store.photoTimes()
        )
        val dayTrack = store.timedTrack()
            .filter { it.t in dayStartMs until dayEndMs }
            .map { GeoPoint(it.lat, it.lon) }
        val dayPhotos = store.photoPoints().filter { it.timeMillis in dayStartMs until dayEndMs }
        val bos = ByteArrayOutputStream()
        PdfReport.write(summary, now, dayTrack, dayPhotos, verification, bos,
            com.meterproof.app.map.TileStore.providerOrNull(context))
        return bos.toByteArray()
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

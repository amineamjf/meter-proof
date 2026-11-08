package com.meterproof.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * ملف الخارطة المحلية: يُستورد مرة واحدة إلى مساحة التطبيق الخاصة ويبقى هناك.
 * لا يحتاج التطبيق إذن إنترنت، فلا يحمّل شيئًا بنفسه.
 */
object MapPack {

    private const val DIR = "maps"
    private const val MARGIN_BYTES = 64L * 1024 * 1024
    private const val REPORT_EVERY = 4L * 1024 * 1024

    sealed class Result {
        class Ok(val message: String) : Result()
        class Fail(val message: String) : Result()
    }

    fun file(ctx: Context): File = File(File(ctx.filesDir, DIR), "basemap.mbtiles")

    fun exists(ctx: Context): Boolean = file(ctx).isFile

    fun delete(ctx: Context) {
        val f = file(ctx)
        f.delete()
        File(f.path + "-journal").delete()
        Settings(ctx).mapPackInfo = ""
    }

    /** وصف قصير للعرض (من معلومات محفوظة عند الاستيراد، دون فتح الملف). */
    fun describe(ctx: Context): String {
        if (!exists(ctx)) return "لا توجد خارطة محلية (تظهر خلفية فارغة). استورد ملف MBTiles"
        val parts = Settings(ctx).mapPackInfo.split('|')
        val mb = file(ctx).length() / (1024 * 1024)
        return if (parts.size == 3) {
            "خارطة محلية: ${parts[0]}، التكبير ${parts[1]} إلى ${parts[2]}، $mb ميغابايت"
        } else {
            "خارطة محلية مستوردة ($mb ميغابايت)"
        }
    }

    /**
     * ينسخ الملف المختار إلى مساحة التطبيق ثم يتحقق منه ثم يستبدل به الخارطة السابقة.
     * يعمل على خيط خلفي (النسخ قد يطول)، وonProgress يُستدعى بالبايتات المنسوخة والكلي (-1 إن جُهل).
     * لا يُستبدل شيء إلا إن نجح التحقق، فلا تضيع الخارطة السابقة بسبب ملف سيئ.
     */
    fun importFrom(ctx: Context, uri: Uri, onProgress: (Long, Long) -> Unit): Result {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val tmp = File(dir, "basemap.tmp")
        tmp.delete()
        try {
            val total = sizeOf(ctx, uri)
            if (total > 0) {
                val need = total + MARGIN_BYTES
                val free = dir.usableSpace
                if (free < need) {
                    return Result.Fail(
                        "المساحة الحرة لا تكفي: يلزم نحو ${need / (1024 * 1024)} ميغابايت " +
                            "ويتوفر ${free / (1024 * 1024)}"
                    )
                }
            }

            val input = ctx.contentResolver.openInputStream(uri)
                ?: return Result.Fail("تعذّر فتح الملف المختار")
            input.use { src ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var copied = 0L
                    var reported = 0L
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - reported >= REPORT_EVERY) {
                            onProgress(copied, total)
                            reported = copied
                        }
                    }
                    out.fd.sync()
                }
            }

            when (val r = MbTiles.open(tmp)) {
                is MbTiles.OpenResult.Fail -> {
                    tmp.delete()
                    return Result.Fail(r.reason)
                }
                is MbTiles.OpenResult.Ok -> {
                    val t = r.tiles
                    val info = "${t.name}|${t.minZoom}|${t.maxZoom}"
                    t.close()
                    val dest = file(ctx)
                    if (!tmp.renameTo(dest)) {
                        dest.delete()
                        if (!tmp.renameTo(dest)) {
                            tmp.delete()
                            return Result.Fail("تعذّر حفظ الخارطة في مساحة التطبيق")
                        }
                    }
                    Settings(ctx).mapPackInfo = info
                    return Result.Ok(describe(ctx))
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            return Result.Fail("فشل النسخ (${e.javaClass.simpleName}). قد تكون المساحة ممتلئة")
        }
    }

    private fun sizeOf(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }
}

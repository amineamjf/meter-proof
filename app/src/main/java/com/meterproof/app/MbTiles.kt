package com.meterproof.app

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import java.io.File

/**
 * قارئ ملف خارطة بصيغة MBTiles (قاعدة SQLite فيها جدول tiles بصور png/jpg/webp).
 * القراءة فقط، بلا إنترنت. البلاطات المتجهة (pbf) غير مدعومة ويُرفض ملفها برسالة واضحة.
 */
class MbTiles private constructor(
    private val db: SQLiteDatabase,
    val name: String,
    val minZoom: Int,
    val maxZoom: Int,
    val attribution: String
) {

    /** بايتات صورة البلاطة (x من الغرب، y من الشمال) أو null إن لم توجد. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? = try {
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), TileMath.tmsRow(z, y).toString())
        ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
    } catch (e: Exception) {
        null
    }

    fun close() {
        try {
            db.close()
        } catch (e: Exception) {
            // لا شيء
        }
    }

    sealed class OpenResult {
        class Ok(val tiles: MbTiles) : OpenResult()
        class Fail(val reason: String) : OpenResult()
    }

    companion object {
        private val TAGS = Regex("<[^>]*>")

        /** يفتح الملف ويتحقق منه (جدول tiles، صيغة صور، بلاطة تُفكّ، مدى التكبير). */
        fun open(file: File): OpenResult {
            val db = try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e: Exception) {
                return OpenResult.Fail("الملف ليس قاعدة بيانات MBTiles صالحة")
            }
            try {
                val hasTiles = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE name='tiles' AND type IN ('table','view')", null
                ).use { it.moveToFirst() }
                if (!hasTiles) {
                    db.close()
                    return OpenResult.Fail("لا يحتوي الملف جدول tiles: ليس ملف MBTiles")
                }

                fun meta(key: String): String? = try {
                    db.rawQuery("SELECT value FROM metadata WHERE name=?", arrayOf(key))
                        .use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (e: Exception) {
                    null
                }

                val format = meta("format")?.trim()?.lowercase()
                if (format == "pbf" || format == "mvt") {
                    db.close()
                    return OpenResult.Fail(
                        "الملف بلاطات متجهة (pbf) وهي غير مدعومة. المطلوب ملف بلاطات صور (png أو jpg أو webp)"
                    )
                }

                val sample: ByteArray? = db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null)
                    .use { if (it.moveToFirst()) it.getBlob(0) else null }
                if (sample == null || sample.isEmpty()) {
                    db.close()
                    return OpenResult.Fail("الملف لا يحتوي أي بلاطة")
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(sample, 0, sample.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    db.close()
                    return OpenResult.Fail("البلاطات ليست صورًا يمكن عرضها (png أو jpg أو webp)")
                }

                var minZ = meta("minzoom")?.trim()?.toIntOrNull()
                var maxZ = meta("maxzoom")?.trim()?.toIntOrNull()
                if (minZ == null || maxZ == null) {
                    db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use {
                        if (it.moveToFirst() && !it.isNull(0) && !it.isNull(1)) {
                            minZ = it.getInt(0)
                            maxZ = it.getInt(1)
                        }
                    }
                }
                val lo = minZ
                val hi = maxZ
                if (lo == null || hi == null || lo < 0 || hi > 22 || lo > hi) {
                    db.close()
                    return OpenResult.Fail("تعذّر تحديد مدى التكبير في الملف")
                }

                val name = (meta("name") ?: file.nameWithoutExtension).trim().take(60)
                val attribution = clean(meta("attribution") ?: "")
                return OpenResult.Ok(MbTiles(db, name, lo, hi, attribution))
            } catch (e: Exception) {
                try {
                    db.close()
                } catch (ignored: Exception) {
                    // لا شيء
                }
                return OpenResult.Fail("تعذّرت قراءة الملف (${e.javaClass.simpleName})")
            }
        }

        /** يزيل وسوم HTML من نص الإسناد ويفك أشهر الرموز. */
        internal fun clean(s: String): String =
            TAGS.replace(s, "").replace("&copy;", "©").replace("&amp;", "&").trim().take(80)
    }
}

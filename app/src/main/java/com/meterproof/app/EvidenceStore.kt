package com.meterproof.app

import android.content.Context
import android.location.Location
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * مخزن الأدلة المحلي.
 *
 * - الصور (بعد التشويه فقط) في مجلد خاص بالتطبيق.
 * - records.jsonl: سجل لكل صورة، وكل سجل يحمل بصمة تتضمن بصمة السجل السابق (سلسلة بصمات)،
 *   فيُكتشف أي حذف أو تعديل لاحق على السجلات أو الصور.
 * - track.jsonl: مسار GPS.
 */
class EvidenceStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "evidence").apply { mkdirs() }
    private val imageDir = File(root, "images").apply { mkdirs() }
    private val recordsFile = File(root, "records.jsonl")
    private val trackFile = File(root, "track.jsonl")

    companion object {
        const val GENESIS = "GENESIS"

        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun recordHash(
            id: String, t: String, lat: String, lon: String, acc: String,
            mode: String, sha: String, prev: String
        ): String {
            val canonical = listOf(id, t, lat, lon, acc, mode, sha, prev).joinToString("|")
            return sha256(canonical.toByteArray(Charsets.UTF_8))
        }
    }

    @Synchronized
    fun addRecord(jpeg: ByteArray, mode: String, loc: Location?): String {
        val id = UUID.randomUUID().toString()
        val t = System.currentTimeMillis().toString()
        val lat = loc?.latitude?.toString() ?: ""
        val lon = loc?.longitude?.toString() ?: ""
        val acc = loc?.accuracy?.toString() ?: ""
        val sha = sha256(jpeg)
        val prev = lastHash()
        val hash = recordHash(id, t, lat, lon, acc, mode, sha, prev)

        // الصورة أولًا ثم السجل، فلا يوجد سجل بلا صورة
        File(imageDir, "$id.jpg").writeBytes(jpeg)

        val json = JSONObject()
            .put("id", id)
            .put("t", t)
            .put("lat", lat)
            .put("lon", lon)
            .put("acc", acc)
            .put("mode", mode)
            .put("sha", sha)
            .put("prev", prev)
            .put("hash", hash)
        recordsFile.appendText(json.toString() + "\n")
        return id
    }

    @Synchronized
    fun addTrackPoint(loc: Location) {
        val json = JSONObject()
            .put("t", System.currentTimeMillis().toString())
            .put("lat", loc.latitude.toString())
            .put("lon", loc.longitude.toString())
            .put("acc", loc.accuracy.toString())
        trackFile.appendText(json.toString() + "\n")
    }

    @Synchronized
    fun readRecords(): List<JSONObject> {
        if (!recordsFile.exists()) return emptyList()
        return recordsFile.readLines()
            .filter { it.isNotBlank() }
            .map { JSONObject(it) }
    }

    @Synchronized
    fun count(): Int = try { readRecords().size } catch (e: Exception) { 0 }

    @Synchronized
    fun lastTime(): Long? = try {
        readRecords().lastOrNull()?.getString("t")?.toLongOrNull()
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun trackPointCount(): Int {
        if (!trackFile.exists()) return 0
        return try { trackFile.readLines().count { it.isNotBlank() } } catch (e: Exception) { 0 }
    }

    @Synchronized
    fun imageFiles(): List<File> = try {
        readRecords()
            .map { File(imageDir, it.getString("id") + ".jpg") }
            .filter { it.exists() }
    } catch (e: Exception) {
        emptyList()
    }

    /** ملف الصورة لمعرّف سجل معيّن. */
    fun imageFile(id: String): File = File(imageDir, "$id.jpg")

    /** نسخة من ملف السجلات الخام (للتصدير). قد لا يكون موجودًا. */
    fun recordsFileRaw(): File = recordsFile

    /** نسخة من ملف المسار الخام (للتصدير). قد لا يكون موجودًا. */
    fun trackFileRaw(): File = trackFile

    /** نقاط مسار GPS الصالحة بالترتيب الزمني. */
    @Synchronized
    fun trackPoints(): List<GeoPoint> {
        if (!trackFile.exists()) return emptyList()
        return try {
            trackFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull {
                    try {
                        val j = JSONObject(it)
                        val lat = j.getString("lat").toDoubleOrNull()
                        val lon = j.getString("lon").toDoubleOrNull()
                        if (lat != null && lon != null) GeoPoint(lat, lon) else null
                    } catch (e: Exception) {
                        null
                    }
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * مواقع الصور التي لها إحداثيات. الحقل index هو ترتيب الصورة في imageFiles()
     * (أي نفس الرقم الذي تعرضه المعاينة)، وصور بلا إحداثيات تُحسب في الترقيم ولا تظهر على الخارطة.
     */
    @Synchronized
    fun photoPoints(): List<PhotoPoint> {
        return try {
            val out = ArrayList<PhotoPoint>()
            var index = 0
            for (r in readRecords()) {
                val id = r.getString("id")
                if (!File(imageDir, "$id.jpg").exists()) continue
                val lat = r.optString("lat").toDoubleOrNull()
                val lon = r.optString("lon").toDoubleOrNull()
                if (lat != null && lon != null) {
                    out.add(
                        PhotoPoint(
                            index = index,
                            id = id,
                            timeMillis = r.optString("t").toLongOrNull() ?: 0L,
                            lat = lat,
                            lon = lon,
                            accuracy = r.optString("acc").toFloatOrNull()
                        )
                    )
                }
                index++
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun lastHash(): String {
        val last = try { readRecords().lastOrNull() } catch (e: Exception) { null }
        return last?.getString("hash") ?: GENESIS
    }

    /** يتحقق من سلامة السلسلة والصور. يعيد رسالة جاهزة للعرض. */
    @Synchronized
    fun verify(): String {
        return try {
            val records = readRecords()
            var prev = GENESIS
            for ((i, r) in records.withIndex()) {
                val n = i + 1
                val id = r.getString("id")
                if (r.getString("prev") != prev) return "❌ السلسلة مكسورة عند السجل $n (سجل محذوف أو مُعدَّل)"
                val expected = recordHash(
                    id, r.getString("t"), r.getString("lat"), r.getString("lon"),
                    r.getString("acc"), r.getString("mode"), r.getString("sha"), r.getString("prev")
                )
                if (expected != r.getString("hash")) return "❌ بيانات السجل $n مُعدَّلة"
                val img = File(imageDir, "$id.jpg")
                if (!img.exists()) return "❌ صورة السجل $n محذوفة"
                if (sha256(img.readBytes()) != r.getString("sha")) return "❌ صورة السجل $n مُعدَّلة"
                prev = r.getString("hash")
            }
            "✅ السلسلة سليمة (${records.size} سجل)"
        } catch (e: Exception) {
            "❌ ملف السجلات تالف"
        }
    }
}

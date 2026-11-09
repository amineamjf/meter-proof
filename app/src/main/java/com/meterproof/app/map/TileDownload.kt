package com.meterproof.app.map

import kotlin.math.min

/** قواعد رابط البلاطات وفحص الصور. نقي. */
object TileUrl {
    /** null = صالح، وإلا رسالة الخطأ. HTTPS فقط حمايةً للمفاتيح والبيانات. */
    fun validate(template: String): String? {
        val t = template.trim()
        if (!t.startsWith("https://")) return "الرابط يجب أن يبدأ بـ https://"
        for (k in listOf("{z}", "{x}", "{y}")) if (!t.contains(k)) return "الرابط ينقصه $k"
        if (t.contains(" ")) return "الرابط يحتوي مسافة"
        if (t.length > 500) return "الرابط طويل جدًا"
        return null
    }

    fun build(template: String, z: Int, x: Int, y: Int): String =
        template.trim().replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    /** هل الرابط يشير إلى خوادم OSM الرسمية (التي لا تسمح بالتنزيل بالجملة)؟ */
    fun isOfficialOsm(template: String): Boolean =
        Regex("^https://([a-c]\\.)?tile\\.openstreetmap\\.org/").containsMatchIn(template.trim())
}

object ImageSniffer {
    /** PNG أو JPEG أو WebP فقط، كي لا تُخزَّن صفحة خطأ HTML على أنها بلاطة. */
    fun isImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
        val webp = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        return png || jpg || webp
    }
}

object DownloadLimits {
    const val MIN_ZOOM = 8
    const val MAX_ZOOM = 19
    /** سقف صلب يحمي من تنزيل بملايين البلاطات بالخطأ (وحجم التخزين). */
    const val MAX_TILES = 60_000L
    /** متوسط تقريبي لحجم بلاطة نقطية (تقدير، يختلف بين المزوّدين). */
    const val AVG_TILE_BYTES = 22_000L
}

data class DownloadPlan(val bounds: Bounds, val zMin: Int, val zMax: Int) {
    val total: Long get() = TileMath.countTiles(bounds, zMin, zMax)
    val estimatedBytes: Long get() = total * DownloadLimits.AVG_TILE_BYTES
    val withinCap: Boolean get() = total <= DownloadLimits.MAX_TILES
    fun valid(): Boolean = zMin in DownloadLimits.MIN_ZOOM..DownloadLimits.MAX_ZOOM &&
        zMax in zMin..DownloadLimits.MAX_ZOOM

    /** البلاطات من التقريب الأدنى إلى الأعلى، فأي توقف يترك خارطة خشنة صالحة. */
    fun tiles(): Sequence<Triple<Int, Int, Int>> = sequence {
        for (z in zMin..zMax) {
            val r = TileMath.range(bounds, z)
            for (y in r.y0..r.y1) for (x in r.x0..r.x1) yield(Triple(z, x, y))
        }
    }
}

data class FetchResult(val status: Int, val bytes: ByteArray? = null, val ioError: Boolean = false)

fun interface TileFetcher { fun fetch(url: String): FetchResult }

interface TileSink {
    fun has(z: Int, x: Int, y: Int): Boolean
    fun put(z: Int, x: Int, y: Int, bytes: ByteArray)
}

data class DownloadResult(
    val downloaded: Int, val skipped: Int, val missing: Int, val failed: Int,
    val aborted: String?, val cancelled: Boolean
) {
    val ok: Boolean get() = aborted == null && !cancelled && failed == 0
}

/**
 * حلقة التنزيل: تستأنف (تتخطى الموجود)، تعيد المحاولة بتراجع أُسّي، وتتوقف فورًا
 * إن رفض المزوّد (401 مفتاح خاطئ، أو 403/429 مرارًا) بدل الاستمرار في طرق بابه.
 */
class TileDownloader(
    private val fetcher: TileFetcher,
    private val sink: TileSink,
    private val template: String,
    private val delayMs: Long = 100,
    private val maxRetries: Int = 3,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    fun run(
        plan: DownloadPlan,
        cancelled: () -> Boolean,
        progress: (done: Int, total: Long) -> Unit = { _, _ -> }
    ): DownloadResult {
        require(plan.valid()) { "خطة غير صالحة" }
        require(plan.withinCap) { "عدد البلاطات ${plan.total} يتجاوز السقف ${DownloadLimits.MAX_TILES}" }
        TileUrl.validate(template)?.let { throw IllegalArgumentException(it) }

        var downloaded = 0; var skipped = 0; var missing = 0; var failed = 0
        var blocked = 0
        var seen = 0
        for ((z, x, y) in plan.tiles()) {
            if (cancelled()) return DownloadResult(downloaded, skipped, missing, failed, null, true)
            seen++
            if (sink.has(z, x, y)) { skipped++; progress(seen, plan.total); continue }

            val url = TileUrl.build(template, z, x, y)
            var attempt = 0
            var stored = false
            while (true) {
                val r = fetcher.fetch(url)
                when {
                    r.status == 401 ->
                        return DownloadResult(downloaded, skipped, missing, failed, "المفتاح مرفوض (401)", false)
                    r.status == 403 || r.status == 429 -> {
                        blocked++
                        if (blocked >= 3)
                            return DownloadResult(downloaded, skipped, missing, failed,
                                "المزوّد يرفض الطلبات (${r.status}). تحقق من الشروط والمفتاح ثم أعد المحاولة لاحقًا", false)
                    }
                    r.status == 404 -> { missing++; break }
                    r.status == 200 && r.bytes != null && ImageSniffer.isImage(r.bytes) -> {
                        sink.put(z, x, y, r.bytes); downloaded++; blocked = 0; stored = true; break
                    }
                    // 200 بمحتوى ليس صورة، أو 5xx، أو خطأ شبكة: نعيد المحاولة
                }
                attempt++
                if (attempt > maxRetries) { failed++; break }
                sleeper(min(30_000L, 500L shl attempt))
                if (cancelled()) return DownloadResult(downloaded, skipped, missing, failed, null, true)
            }
            progress(seen, plan.total)
            if (stored && delayMs > 0) sleeper(delayMs)
        }
        return DownloadResult(downloaded, skipped, missing, failed, null, false)
    }
}

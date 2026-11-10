package com.meterproof.app

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class FetchResult(val status: Int, val bytes: ByteArray? = null, val error: String? = null)

fun interface TileFetcher { fun fetch(url: String): FetchResult }

interface TileStoreApi {
    fun has(key: String): Boolean
    fun put(key: String, bytes: ByteArray)
}

/** PNG أو JPEG أو WebP فقط، كي لا تُخزَّن صفحة خطأ HTML على أنها بلاطة. */
object ImageSniffer {
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

data class DownloadOutcome(
    val total: Int,
    val ok: Int,
    val skipped: Int,
    val failed: Int,
    val firstError: String?,
    val aborted: String?,
    val cancelled: Boolean
) {
    /** ما صار متاحًا دون إنترنت (جديد + كان موجودًا). */
    val available: Int get() = ok + skipped
}

/**
 * تحميل قائمة بلاطات إلى المخزن بعدة خيوط (6 افتراضيًا كما في smart-remplacement)،
 * يتخطى الموجود (فيُستأنف دون إعادة)، ويعدّ الإخفاقات ويحتفظ بأول خطأ لعرضه.
 * يتوقف فورًا عند 401 (مفتاح مرفوض) أو عند 3 رفضات متتالية 403/429، بدل الاستمرار في طرق باب المزوّد.
 * نقي: الشبكة والتخزين واجهتان.
 */
class TileDownloader(
    private val fetcher: TileFetcher,
    private val store: TileStoreApi,
    private val template: String,
    private val threads: Int = 6
) {
    fun run(
        tiles: List<TileId>,
        cancelled: () -> Boolean,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): DownloadOutcome {
        val total = tiles.size
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val ok = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val blocked = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)
        val abort = AtomicReference<String?>(null)

        fun fail(msg: String) {
            failed.incrementAndGet()
            firstError.compareAndSet(null, msg)
        }

        val worker = Runnable {
            while (true) {
                if (cancelled() || abort.get() != null) break
                val i = next.getAndIncrement()
                if (i >= total) break
                val t = tiles[i]
                val key = TileLayers.key(template, t.z, t.x, t.y)
                if (store.has(key)) {
                    skipped.incrementAndGet()
                } else {
                    val r = try {
                        fetcher.fetch(TileLayers.build(template, t.z, t.x, t.y))
                    } catch (e: Exception) {
                        FetchResult(0, null, e.message ?: e.javaClass.simpleName)
                    }
                    val b = r.bytes
                    when {
                        r.status == 200 && b != null && ImageSniffer.isImage(b) -> {
                            try {
                                store.put(key, b)
                                ok.incrementAndGet()
                                blocked.set(0)
                            } catch (e: Exception) {
                                fail("تعذّر الحفظ: " + (e.message ?: e.javaClass.simpleName))
                            }
                        }
                        r.status == 401 -> {
                            fail("HTTP 401")
                            abort.compareAndSet(null, "المفتاح مرفوض (HTTP 401). تحقق من الرابط والمفتاح")
                        }
                        r.status == 403 || r.status == 429 -> {
                            fail("HTTP " + r.status)
                            if (blocked.incrementAndGet() >= 3) {
                                abort.compareAndSet(
                                    null,
                                    "المزوّد يرفض الطلبات (HTTP ${r.status}). أوقفتُ التحميل كي لا نثقل عليه. " +
                                        "جرّب الطبقة الأخرى أو مزوّدًا آخر أو وقتًا لاحقًا"
                                )
                            }
                        }
                        r.status == 200 -> fail("المحتوى ليس صورة")
                        r.status > 0 -> fail("HTTP " + r.status)
                        else -> fail(r.error ?: "خطأ شبكة")
                    }
                }
                progress(done.incrementAndGet(), total)
            }
        }

        if (threads <= 1 || total <= 1) {
            worker.run()
        } else {
            val pool = Executors.newFixedThreadPool(minOf(threads, total))
            repeat(minOf(threads, total)) { pool.execute(worker) }
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.DAYS)
        }
        return DownloadOutcome(
            total, ok.get(), skipped.get(), failed.get(), firstError.get(), abort.get(), cancelled()
        )
    }
}

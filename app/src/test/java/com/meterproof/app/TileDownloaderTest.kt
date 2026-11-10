package com.meterproof.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TileDownloaderTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)
    private val html = "<html>error</html>".toByteArray()
    private val tpl = TileLayers.PLAN
    private val tiles = (0 until 40).map { TileId(15, it, it + 1) }

    private class Store : TileStoreApi {
        val data = ConcurrentHashMap<String, ByteArray>()
        override fun has(key: String) = data.containsKey(key)
        override fun put(key: String, bytes: ByteArray) { data[key] = bytes }
    }

    private fun run(f: TileFetcher, s: Store = Store(), threads: Int = 1, cancel: () -> Boolean = { false }) =
        TileDownloader(f, s, tpl, threads).run(tiles, cancel)

    @Test fun downloadsEverythingWithOneThread() {
        val s = Store(); val calls = AtomicInteger()
        val r = run({ calls.incrementAndGet(); FetchResult(200, png) }, s)
        assertEquals(40, r.ok); assertEquals(0, r.failed); assertEquals(40, calls.get()); assertEquals(40, s.data.size)
        assertEquals(40, r.available); assertNull(r.aborted); assertFalse(r.cancelled)
    }

    @Test fun sixThreadsStoreEachTileExactlyOnce() {
        val s = Store(); val calls = AtomicInteger()
        val r = run({ calls.incrementAndGet(); FetchResult(200, png) }, s, threads = 6)
        assertEquals(40, r.ok); assertEquals(40, calls.get()); assertEquals(40, s.data.size)
    }

    @Test fun requestsUseTheTemplateOrder() {
        val urls = ConcurrentHashMap.newKeySet<String>()
        TileDownloader({ urls.add(it); FetchResult(200, png) }, Store(), TileLayers.SAT, 1)
            .run(listOf(TileId(17, 62772, 52546)), { false })
        assertEquals(setOf(TileLayers.build(TileLayers.SAT, 17, 62772, 52546)), urls)
    }

    @Test fun resumeSkipsWhatIsAlreadyStoredAndDoesNotRefetch() {
        val s = Store(); val calls = AtomicInteger()
        tiles.take(10).forEach { s.put(TileLayers.key(tpl, it.z, it.x, it.y), png) }
        val r = run({ calls.incrementAndGet(); FetchResult(200, png) }, s)
        assertEquals(10, r.skipped); assertEquals(30, r.ok); assertEquals(30, calls.get()); assertEquals(40, r.available)
    }

    @Test fun cancelStopsAndKeepsWhatWasDownloaded() {
        val s = Store(); val n = AtomicInteger()
        val r = run({ FetchResult(200, png) }, s, cancel = { n.getAndIncrement() >= 5 })
        assertTrue(r.cancelled); assertEquals(5, r.ok); assertEquals(5, s.data.size)
    }

    @Test fun htmlErrorPageIsNeverStoredAsATile() {
        val s = Store()
        val r = run({ FetchResult(200, html) }, s)
        assertEquals(0, s.data.size); assertEquals(40, r.failed); assertEquals("المحتوى ليس صورة", r.firstError)
    }

    @Test fun failuresAreCountedAndTheFirstErrorIsKept() {
        val n = AtomicInteger()
        val r = run({ if (n.getAndIncrement() % 2 == 0) FetchResult(500) else FetchResult(200, png) })
        assertEquals(20, r.ok); assertEquals(20, r.failed); assertEquals("HTTP 500", r.firstError)
    }

    @Test fun fetcherExceptionsAreFailuresNotCrashes() {
        val r = run({ throw java.io.IOException("no route") })
        assertEquals(40, r.failed); assertEquals("no route", r.firstError)
    }

    @Test fun networkErrorResultKeepsItsMessage() {
        val r = run({ FetchResult(0, null, "timeout") })
        assertEquals(40, r.failed); assertEquals("timeout", r.firstError)
    }

    @Test fun badKeyAbortsOnTheFirstRequest() {
        val calls = AtomicInteger()
        val r = run({ calls.incrementAndGet(); FetchResult(401) })
        assertEquals(1, calls.get()); assertNotNull(r.aborted); assertEquals(0, r.ok)
    }

    @Test fun repeatedRateLimitingAbortsInsteadOfHammeringTheProvider() {
        val calls = AtomicInteger()
        val r = run({ calls.incrementAndGet(); FetchResult(429) })
        assertEquals(3, calls.get()); assertNotNull(r.aborted)
    }

    @Test fun anIsolatedRefusalDoesNotAbort() {
        val n = AtomicInteger()
        val r = run({ if (n.getAndIncrement() == 3) FetchResult(403) else FetchResult(200, png) })
        assertNull(r.aborted); assertEquals(39, r.ok); assertEquals(1, r.failed)
    }

    @Test fun progressReachesTheTotal() {
        val last = AtomicInteger()
        TileDownloader({ FetchResult(200, png) }, Store(), tpl, 6).run(tiles, { false }) { d, t ->
            assertEquals(40, t); last.accumulateAndGet(d) { a, b -> maxOf(a, b) }
        }
        assertEquals(40, last.get())
    }

    @Test fun emptyListIsANoOp() {
        val r = TileDownloader({ FetchResult(200, png) }, Store(), tpl, 6).run(emptyList(), { false })
        assertEquals(0, r.total); assertEquals(0, r.ok)
    }

    @Test fun imageSniffer() {
        assertTrue(ImageSniffer.isImage(png))
        assertFalse(ImageSniffer.isImage(html))
        assertFalse(ImageSniffer.isImage(ByteArray(3)))
        assertTrue(ImageSniffer.isImage(ByteArray(16).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }))
        assertTrue(ImageSniffer.isImage("RIFF....WEBPVP8 ".toByteArray()))
    }
}

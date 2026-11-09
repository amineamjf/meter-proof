package com.meterproof.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileDownloadTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)
    private val html = "<html>error</html>".toByteArray()
    private val tpl = "https://tiles.example.com/{z}/{x}/{y}.png?key=K"
    private val casa = Bounds(33.5641, -7.6006, 33.5821, -7.5790)

    private class Sink(val have: MutableSet<Triple<Int, Int, Int>> = HashSet()) : TileSink {
        val puts = ArrayList<Triple<Int, Int, Int>>()
        override fun has(z: Int, x: Int, y: Int) = Triple(z, x, y) in have
        override fun put(z: Int, x: Int, y: Int, bytes: ByteArray) { puts.add(Triple(z, x, y)); have.add(Triple(z, x, y)) }
    }

    private fun dl(f: TileFetcher, s: Sink, delay: Long = 0, sleeps: MutableList<Long> = ArrayList()) =
        TileDownloader(f, s, tpl, delay, 3) { sleeps.add(it) }

    // ---- الرابط
    @Test fun templateValidation() {
        assertNull(TileUrl.validate(tpl))
        assertNotNull(TileUrl.validate("http://x/{z}/{x}/{y}.png"))
        assertNotNull(TileUrl.validate("https://x/{z}/{x}.png"))
        assertNotNull(TileUrl.validate("https://x/{z}/{x}/{y} .png"))
        assertNotNull(TileUrl.validate(""))
    }
    @Test fun urlBuild() = assertEquals("https://tiles.example.com/17/62772/52546.png?key=K", TileUrl.build(tpl, 17, 62772, 52546))
    @Test fun officialOsmIsDetected() {
        assertTrue(TileUrl.isOfficialOsm("https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertTrue(TileUrl.isOfficialOsm("https://a.tile.openstreetmap.org/{z}/{x}/{y}.png"))
        assertFalse(TileUrl.isOfficialOsm(tpl))
    }
    @Test fun sniffer() {
        assertTrue(ImageSniffer.isImage(png))
        assertFalse(ImageSniffer.isImage(html))
        assertFalse(ImageSniffer.isImage(ByteArray(3)))
        val jpg = ByteArray(16).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        assertTrue(ImageSniffer.isImage(jpg))
        val webp = "RIFF....WEBPVP8 ".toByteArray()
        assertTrue(ImageSniffer.isImage(webp))
    }

    // ---- الخطة
    @Test fun planCountsAndOrdersLowZoomFirst() {
        val p = DownloadPlan(TileMath.boundsAround(33.5731, -7.5898, 1000.0), 15, 17)
        assertEquals(115L, p.total)
        assertEquals(115L * DownloadLimits.AVG_TILE_BYTES, p.estimatedBytes)
        val zs = p.tiles().map { it.first }.toList()
        assertEquals(115, zs.size)
        assertEquals(zs.sorted(), zs)
        assertEquals(15, zs.first()); assertEquals(17, zs.last())
    }
    @Test fun planCapAndValidity() {
        val huge = DownloadPlan(Bounds(27.0, -13.0, 36.0, -1.0), 8, 19)
        assertFalse(huge.withinCap)
        assertFalse(DownloadPlan(casa, 5, 12).valid())
        assertFalse(DownloadPlan(casa, 15, 14).valid())
        assertTrue(DownloadPlan(casa, 12, 17).valid())
    }
    @Test(expected = IllegalArgumentException::class) fun overCapPlanIsRefused() {
        dl({ FetchResult(200, png) }, Sink()).run(DownloadPlan(Bounds(27.0, -13.0, 36.0, -1.0), 8, 19), { false })
    }

    // ---- التنزيل
    private val plan = DownloadPlan(casa, 14, 16)

    @Test fun downloadsEverythingOnce() {
        val s = Sink(); var calls = 0
        val r = dl({ calls++; FetchResult(200, png) }, s).run(plan, { false })
        assertTrue(r.ok); assertEquals(plan.total.toInt(), r.downloaded); assertEquals(calls, r.downloaded)
        assertEquals(plan.total.toInt(), s.puts.size)
    }
    @Test fun resumeSkipsWhatExistsAndDoesNotRefetch() {
        val s = Sink(); var calls = 0
        val first = plan.tiles().take(3).toList(); s.have.addAll(first)
        val r = dl({ calls++; FetchResult(200, png) }, s).run(plan, { false })
        assertEquals(3, r.skipped); assertEquals(plan.total.toInt() - 3, calls)
    }
    @Test fun cancelStopsImmediatelyKeepingProgress() {
        val s = Sink(); var n = 0
        val r = dl({ FetchResult(200, png) }, s).run(plan, { n++ >= 4 })
        assertTrue(r.cancelled); assertEquals(4, r.downloaded)
    }
    @Test fun htmlErrorPageIsNeverStoredAsATile() {
        val s = Sink()
        val r = dl({ FetchResult(200, html) }, s).run(DownloadPlan(casa, 14, 14), { false })
        assertEquals(0, s.puts.size); assertEquals(DownloadPlan(casa, 14, 14).total.toInt(), r.failed)
    }
    @Test fun retriesTransientErrorsWithExponentialBackoff() {
        val s = Sink(); val sleeps = ArrayList<Long>(); var n = 0
        val one = DownloadPlan(Bounds(33.5731, -7.5898, 33.5731, -7.5898), 14, 14)
        val r = dl({ if (n++ < 2) FetchResult(503) else FetchResult(200, png) }, s, 0, sleeps).run(one, { false })
        assertEquals(1, r.downloaded); assertEquals(0, r.failed)
        assertEquals(listOf(1000L, 2000L), sleeps)
    }
    @Test fun givesUpOnATileAfterMaxRetriesAndContinues() {
        val s = Sink(); var n = 0
        val two = DownloadPlan(Bounds(33.5731, -7.5898, 33.5731, -7.5898), 14, 15)
        val r = dl({ if (n++ < 4) FetchResult(500) else FetchResult(200, png) }, s).run(two, { false })
        assertEquals(1, r.failed); assertEquals(1, r.downloaded); assertFalse(r.ok)
    }
    @Test fun notFoundTilesAreCountedMissingNotFailed() {
        val r = dl({ FetchResult(404) }, Sink()).run(DownloadPlan(casa, 14, 14), { false })
        assertEquals(DownloadPlan(casa, 14, 14).total.toInt(), r.missing); assertEquals(0, r.failed)
    }
    @Test fun badKeyAbortsOnFirstRequest() {
        var calls = 0
        val r = dl({ calls++; FetchResult(401) }, Sink()).run(plan, { false })
        assertEquals(1, calls); assertNotNull(r.aborted)
    }
    @Test fun repeatedRateLimitingAbortsInsteadOfHammeringTheServer() {
        var calls = 0
        val r = dl({ calls++; FetchResult(429) }, Sink()).run(plan, { false })
        assertEquals(3, calls); assertNotNull(r.aborted); assertEquals(0, r.downloaded)
    }
    @Test fun politeDelayBetweenSuccessfulTiles() {
        val sleeps = ArrayList<Long>()
        val one = DownloadPlan(casa, 14, 14)
        dl({ FetchResult(200, png) }, Sink(), 100, sleeps).run(one, { false })
        assertEquals(one.total.toInt(), sleeps.count { it == 100L })
    }
    @Test fun progressReachesTotal() {
        var last = 0
        dl({ FetchResult(200, png) }, Sink()).run(plan, { false }) { d, _ -> last = d }
        assertEquals(plan.total.toInt(), last)
    }
}

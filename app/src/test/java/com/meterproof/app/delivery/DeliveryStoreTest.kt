package com.meterproof.app.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeliveryStoreTest {

    private fun tempStore(): Pair<DeliveryStore, File> {
        val dir = Files.createTempDirectory("mp-delivery").toFile()
        dir.deleteOnExit()
        val d = File(dir, "delivery")
        return Pair(DeliveryStore(d), d)
    }

    @Test
    fun featLineRoundTrips() {
        val f = SyntheticScenes.deliver(1)[20]
        val back = Feat.parse(f.toLine())
        assertNotNull(back)
        assertEquals(f.t, back!!.t)
        assertEquals(f.skin, back.skin, 1e-3f)
        assertEquals(f.objX, back.objX, 1e-3f)
    }

    @Test
    fun badFeatLinesAreRejected() {
        assertNull(Feat.parse("1,2,3"))
        assertNull(Feat.parse("x," + "0.1,".repeat(12) + "0.1"))
    }

    @Test
    fun samplesSurviveSaveAndLoad() {
        val (store, _) = tempStore()
        val s1 = Sample(Label.POS_ON, 2, SyntheticScenes.deliver(1))
        val s2 = Sample(Label.NEG_WALK, 2, SyntheticScenes.walk(1))
        store.addSample(s1)
        store.addSample(s2)
        val loaded = store.loadSamples()
        assertEquals(2, loaded.size)
        assertEquals(Label.POS_ON, loaded[0].label)
        assertEquals(s1.frames.size, loaded[0].frames.size)
        assertEquals(Label.NEG_WALK, loaded[1].label)
        assertEquals(2, loaded[1].colorVersion)
    }

    @Test
    fun storedFilesContainOnlyNumbersAndCodesNeverPixels() {
        val (store, dir) = tempStore()
        store.addSample(Sample(Label.POS_ON, 1, SyntheticScenes.deliver(1)))
        store.saveColorModel(ColorModel(version = 3))
        store.saveParams(DeliveryParams(version = 2))
        val allowed = Regex("^[a-zA-Z0-9_.,;|=\\-\\n]*$")
        for (f in dir.listFiles()!!) {
            assertTrue("${f.name} has unexpected characters", allowed.matches(f.readText()))
            // أكبر سطر: عشرات الإطارات × 14 رقمًا، لا آلاف البكسلات
            assertTrue(f.length() < 200_000L)
        }
    }

    @Test
    fun colorModelAndParamsPersist() {
        val (store, _) = tempStore()
        assertNull(store.loadColorModel())
        assertNull(store.loadParams())
        store.saveColorModel(ColorModel(version = 5, cbMin = 70))
        store.saveParams(DeliveryParams(skinMin = 0.03f, underTrusted = true, version = 7))
        assertEquals(5, store.loadColorModel()!!.version)
        assertEquals(0.03f, store.loadParams()!!.skinMin, 0f)
        assertTrue(store.loadParams()!!.underTrusted)
    }

    @Test
    fun deleteLastAndClear() {
        val (store, _) = tempStore()
        store.addSample(Sample(Label.POS_ON, 1, SyntheticScenes.deliver(1)))
        store.addSample(Sample(Label.NEG_HAND, 1, SyntheticScenes.handOnly(1)))
        assertTrue(store.deleteLastSample())
        assertEquals(1, store.loadSamples().size)
        assertTrue(store.deleteLastSample())
        assertFalse(store.deleteLastSample())
        store.addSample(Sample(Label.NEG_HAND, 1, SyntheticScenes.handOnly(2)))
        store.clearAll()
        assertEquals(0, store.loadSamples().size)
        assertNull(store.loadColorModel())
    }

    @Test
    fun corruptedLinesAreSkippedNotFatal() {
        val (store, _) = tempStore()
        store.addSample(Sample(Label.POS_ON, 1, SyntheticScenes.deliver(1)))
        assertNull(DeliveryStore.parse("garbage"))
        assertNull(DeliveryStore.parse("unknown_label|1|0,0"))
        assertEquals(1, store.loadSamples().size)
    }

    @Test
    fun paramsSerializationRoundTrips() {
        val p = DeliveryParams(skinMin = 0.031f, holdMs = 777, underTrusted = true, version = 9)
        assertEquals(p, DeliveryParams.parse(p.serialize()))
    }
}

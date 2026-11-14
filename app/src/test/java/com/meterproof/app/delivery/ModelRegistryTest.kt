package com.meterproof.app.delivery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    private fun dir(): File = File.createTempFile("reg", "").let { it.delete(); it.mkdirs(); it }

    private fun model(p: Float, r: Float) = WindowModel(
        FloatArray(WindowFeatures.SIZE), FloatArray(WindowFeatures.SIZE) { 1f },
        FloatArray(2 * WindowFeatures.SIZE), 0f, 0.6f, 0.4f, 1L, 20, 20, WindowMetrics(p, r, r)
    )

    private fun meta(p: Float, r: Float, hf: Int = -1, hn: Int = -1) = ModelRegistry.Meta(
        0, 100L, "dev", 20, 20, 18, 1, 2, 19, p, r, -1, -1, hf, hn, false, "cfg", "", ""
    )

    @Test fun firstModelIsAcceptedAndStable() {
        val reg = ModelRegistry(dir())
        val d = reg.submit(meta(0.95f, 0.9f), model(0.95f, 0.9f), null)
        assertTrue(d.accepted)
        assertEquals(1, reg.stable()!!.meta.version)
        assertNotNull(reg.stable()!!.window)
    }

    @Test fun regressedModelIsRejectedAndStableKept() {
        val reg = ModelRegistry(dir())
        reg.submit(meta(0.97f, 0.9f), model(0.97f, 0.9f), null)
        val d = reg.submit(meta(0.90f, 0.9f), model(0.90f, 0.9f), null)
        assertFalse(d.accepted)
        assertEquals("PRECISION_REGRESSED", d.reason)
        assertEquals(1, reg.stable()!!.meta.version)
        assertEquals(2, reg.list().size)
        assertEquals(ModelRegistry.REJECTED, reg.list().first().meta.decision)
    }

    @Test fun recallRegressionRejected() {
        val (ok, why) = ModelRegistry.decide(meta(0.95f, 0.9f), meta(0.95f, 0.8f))
        assertFalse(ok); assertEquals("RECALL_REGRESSED", why)
    }

    @Test fun holdoutWorseRejected() {
        val (ok, why) = ModelRegistry.decide(meta(0.95f, 0.9f, 0, 10), meta(0.95f, 0.9f, 3, 10))
        assertFalse(ok); assertEquals("HOLDOUT_FALSE_ACCEPT_WORSE", why)
    }

    @Test fun betterModelReplacesAndRollbackWorks() {
        val reg = ModelRegistry(dir())
        reg.submit(meta(0.95f, 0.85f), model(0.95f, 0.85f), null)
        reg.submit(meta(0.96f, 0.90f), model(0.96f, 0.90f), null)
        assertEquals(2, reg.stable()!!.meta.version)
        val back = reg.rollback()
        assertEquals(1, back!!.meta.version)
        assertEquals(1, reg.stable()!!.meta.version)
        assertNull(reg.rollback()) // لا سابق قبل الأول
        assertTrue(reg.setStable(2))
    }

    @Test fun rejectedCannotBecomeStableAndMetaRoundTrips() {
        val reg = ModelRegistry(dir())
        reg.submit(meta(0.97f, 0.9f), model(0.97f, 0.9f), null)
        reg.submit(meta(0.80f, 0.9f), model(0.80f, 0.9f), null)
        assertFalse(reg.setStable(2))
        val m = reg.list().last().meta
        assertEquals(1, m.version)
        assertEquals(20, m.invoiceImages)
        assertTrue(m.f1 > 0.9f)
    }

    @Test fun clearAndLegacyImport() {
        val d = dir()
        val reg = ModelRegistry(d)
        reg.importLegacy(model(0.95f, 0.9f), null, "dev")
        assertEquals("IMPORTED", reg.stable()!!.meta.reason)
        reg.importLegacy(model(0.5f, 0.5f), null, "dev") // لا يعيد الاستيراد
        assertEquals(1, reg.list().size)
        reg.clear()
        assertNull(reg.stable())
    }
}

class AtomicTextTest {
    @Test fun writesAndReplacesWithoutLeavingTemp() {
        val d = File.createTempFile("atom", "").let { it.delete(); it.mkdirs(); it }
        val f = File(d, "x.txt")
        AtomicText.write(f, "one")
        AtomicText.write(f, "two")
        assertEquals("two", f.readText())
        assertFalse(File(d, "x.txt.tmp").exists())
    }

    @Test fun staleTempDoesNotCorruptStableRead() {
        val reg = ModelRegistry(File.createTempFile("rgst", "").let { it.delete(); it.mkdirs(); it })
        val w = WindowModel(
            FloatArray(WindowFeatures.SIZE), FloatArray(WindowFeatures.SIZE) { 1f },
            FloatArray(2 * WindowFeatures.SIZE), 0f, 0.6f, 0.4f, 1L, 20, 20, WindowMetrics(0.95f, 0.9f, 0.9f)
        )
        val m = ModelRegistry.Meta(0, 1L, "d", 20, 20, 18, 1, 2, 19, 0.95f, 0.9f, -1, -1, -1, -1, false, "c", "", "")
        reg.submit(m, w, null)
        // محاكاة إغلاق أثناء كتابة إصدار ثانٍ: ملف مؤقت ناقص بجوار السليم
        File(File(reg.javaClass.getDeclaredField("modelsDir").also { it.isAccessible = true }.get(reg) as File, ""), "v2.txt.tmp")
            .writeText("version=2\ngarbage")
        assertEquals(1, reg.stable()!!.meta.version)
        assertEquals(1, reg.list().size)
    }
}

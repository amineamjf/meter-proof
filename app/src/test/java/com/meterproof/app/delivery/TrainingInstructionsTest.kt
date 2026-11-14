package com.meterproof.app.delivery

import com.meterproof.app.delivery.TestFrames.rect
import com.meterproof.app.delivery.TestFrames.rgb
import com.meterproof.app.delivery.TestFrames.solid
import com.meterproof.app.delivery.TestFrames.stripes
import com.meterproof.app.privacy.Region
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingInstructionsTest {

    private val w = 160
    private val h = 120

    /**
     * مشهد: جدار فاتح كبير يسارًا (ليس فاتورة)، وورقة صغيرة نصّية يمينًا (الفاتورة).
     * الجدار هو «الأكبر»، وهذا ما كان يُضيّع الفاتورة في المحلل القديم.
     */
    private fun scene(): IntArray {
        val px = solid(w, h, rgb(40, 40, 40))
        rect(px, w, 5, 20, 70, 100, rgb(235, 235, 230))        // جدار فاتح كبير
        rect(px, w, 100, 30, 145, 80, rgb(240, 240, 235))      // الفاتورة
        stripes(px, w, 103, 33, 142, 77, rgb(170, 170, 170), 6, 2)
        return px
    }

    private val invoiceBox = Region(100f / w, 30f / h, 145f / w, 80f / h)
    private val wallBox = Region(5f / w, 20f / h, 70f / w, 100f / h)

    // ---------------------------------------------------------------- المحلل متعدد المرشحين

    @Test
    fun analyzeAllFindsBothCandidatesLargestFirst() {
        val all = InvoiceAnalyzer.analyzeAll(scene(), w, h)
        assertEquals(2, all.size)
        assertTrue(all[0].features.areaFrac > all[1].features.areaFrac)
        // الأكبر هو الجدار، والثاني هو الفاتورة
        assertTrue(SIM(all[0].region, wallBox) > 0.5f)
        assertTrue(SIM(all[1].region, invoiceBox) > 0.5f)
    }

    @Test
    fun analyzeStillReturnsOnlyTheLargest() {
        val one = InvoiceAnalyzer.analyze(scene(), w, h)!!
        val all = InvoiceAnalyzer.analyzeAll(scene(), w, h)
        assertEquals(all[0].features, one.features)
    }

    @Test
    fun analyzeWithinFindsThePaperInsideTheDrawnRegionOnly() {
        val c = InvoiceAnalyzer.analyzeWithin(scene(), w, h, invoiceBox)!!
        assertTrue(SIM(c.region, invoiceBox) > 0.5f)
        // منطقة على الخلفية الداكنة: لا شيء
        assertNull(InvoiceAnalyzer.analyzeWithin(scene(), w, h, Region(0.0f, 0.0f, 0.2f, 0.1f)))
    }

    private fun SIM(a: Region, b: Region) = InstructionSampler.iou(a, b)

    // ---------------------------------------------------------------- اشتقاق العينات

    @Test
    fun recognizeMakesPositiveFromDrawnRegionAndNegativesFromOthers() {
        val all = InvoiceAnalyzer.analyzeAll(scene(), w, h)
        val s = InstructionSampler.derive(InstructionKind.RECOGNIZE, invoiceBox, all, null)
        assertEquals(1, s.count { it.positive })
        assertEquals(1, s.count { !it.positive }) // الجدار
        val pos = s.first { it.positive }.features
        assertEquals(all[1].features, pos)
    }

    @Test
    fun ignoreMakesNegativesFromCandidatesInsideTheRegion() {
        val all = InvoiceAnalyzer.analyzeAll(scene(), w, h)
        val s = InstructionSampler.derive(InstructionKind.IGNORE, wallBox, all, null)
        assertEquals(1, s.size)
        assertFalse(s[0].positive)
        assertEquals(all[0].features, s[0].features)
    }

    @Test
    fun recognizeFallsBackToWithinWhenGlobalAnalysisMissedIt() {
        val within = InvoiceAnalyzer.analyzeWithin(scene(), w, h, invoiceBox)
        val s = InstructionSampler.derive(InstructionKind.RECOGNIZE, invoiceBox, emptyList(), within)
        assertEquals(1, s.size)
        assertTrue(s[0].positive)
    }

    @Test
    fun emptyRegionGivesNoSamples() {
        assertTrue(InstructionSampler.derive(InstructionKind.RECOGNIZE, invoiceBox, emptyList(), null).isEmpty())
        assertTrue(InstructionSampler.derive(InstructionKind.IGNORE, invoiceBox, emptyList(), null).isEmpty())
    }

    // ---------------------------------------------------------------- الترميز

    private fun ins(
        id: String, kind: InstructionKind = InstructionKind.RECOGNIZE,
        pl: Placement = Placement.ABOVE_DOOR, box: Region = invoiceBox, note: String = "ملاحظة"
    ): TrainingInstruction {
        val all = InvoiceAnalyzer.analyzeAll(scene(), w, h)
        return TrainingInstruction(
            id, kind, box, pl, "abcdef12-0000", note, 1000L,
            InstructionSampler.derive(kind, box, all, null)
        )
    }

    /** تعليمة موضع بعينة موجبة جاهزة (دون الاعتماد على تطابق المنطقة مع مشهد الاختبار). */
    private fun placed(id: String, pl: Placement, box: Region) = TrainingInstruction(
        id, InstructionKind.RECOGNIZE, box, pl, "abcdef12-0000", "", 1000L,
        listOf(DerivedSample(InvoiceFeatures(0.2f, 0.9f, 0.3f, 0.5f, 0.0f, 0.15f), true))
    )

    @Test
    fun codecRoundTrips() {
        val a = ins("id-1", note = "فاتورة فوق الباب")
        val b = ins("id-2", InstructionKind.IGNORE, Placement.UNSPECIFIED, wallBox, "جدار الجار")
        val back = InstructionCodec.decode(InstructionCodec.encode(listOf(a, b)))
        assertEquals(2, back.size)
        assertEquals(a.copy(region = a.region.clamped()), back[0])
        assertEquals(b.kind, back[1].kind)
        assertEquals(b.samples, back[1].samples)
    }

    @Test
    fun codecIgnoresCorruptLinesAndCleansNotes() {
        val good = InstructionCodec.encode(listOf(ins("ok")))
        val back = InstructionCodec.decode("garbage\n$good\nTI1;x;R;1,2;U;p;1;n;")
        assertEquals(1, back.size)
        val dirty = ins("n", note = "a;b|c:d,e\nf")
        val clean = InstructionCodec.decode(InstructionCodec.encode(listOf(dirty)))[0].note
        assertFalse(clean.contains(';') || clean.contains('|') || clean.contains(':') || clean.contains('\n'))
        assertTrue(InstructionCodec.cleanNote("x".repeat(500)).length <= InstructionCodec.MAX_NOTE)
    }

    // ---------------------------------------------------------------- المخزن: إضافة وتعديل وحذف

    private fun tmp(): File = java.nio.file.Files.createTempDirectory("instr").toFile()

    @Test
    fun storeAddEditDelete() {
        val st = InstructionStore(tmp())
        assertTrue(st.list().isEmpty())
        st.add(ins("a")); st.add(ins("b", InstructionKind.IGNORE, Placement.UNSPECIFIED, wallBox))
        assertEquals(listOf("a", "b"), st.list().map { it.id })

        val edited = st.get("a")!!.copy(placement = Placement.BELOW_DOOR, note = "عُدِّلت")
        assertTrue(st.update(edited))
        assertEquals(Placement.BELOW_DOOR, st.get("a")!!.placement)
        assertEquals("عُدِّلت", st.get("a")!!.note)

        assertTrue(st.delete("a"))
        assertNull(st.get("a"))
        assertFalse(st.delete("a"))
        assertFalse(st.update(edited))
        assertEquals(listOf("b"), st.list().map { it.id })
    }

    @Test
    fun storeSamplesFollowInstructionsSoDeletingRemovesThem() {
        val st = InstructionStore(tmp())
        st.add(ins("a")); st.add(ins("b"))
        val before = st.samples().size
        assertTrue(before > 0)
        st.delete("b")
        assertTrue(st.samples().size < before)
        st.clear()
        assertTrue(st.samples().isEmpty())
    }

    @Test
    fun tasksUseSeparateFolders() {
        assertTrue(TrainingTask.INVOICE.dirName != TrainingTask.METER.dirName)
        val root = tmp()
        val inv = InstructionStore(File(root, TrainingTask.INVOICE.dirName))
        val met = InstructionStore(File(root, TrainingTask.METER.dirName))
        inv.add(ins("a"))
        assertTrue(met.list().isEmpty())
    }

    @Test
    fun instructionSamplesFeedTheTrainer() {
        // عدد كافٍ من التعليمات يكفي للتدريب (15 موجبة و15 سالبة على الأقل)
        val list = (1..16).map { ins("r$it") } + (1..16).map {
            ins("i$it", InstructionKind.IGNORE, Placement.UNSPECIFIED, wallBox)
        }
        val samples = list.flatMap { i -> i.samples.map { Sample(it.features, it.positive, i.createdAt + 1) } }
        assertTrue(samples.count { it.positive } >= 15)
        assertTrue(samples.count { !it.positive } >= 15)
        // المثالان متطابقان تقريبًا: الفاصل سهل، فيجب ألا يفشل التدريب بسبب «عدم كفاية» العينات
        val r = InvoiceTrainer.train(samples, 5L)
        if (r is InvoiceTrainer.Result.NotReady) {
            assertFalse(r.reasons.contains("NEED_MORE_POSITIVES") || r.reasons.contains("NEED_MORE_NEGATIVES"))
        }
    }

    // ---------------------------------------------------------------- مواضع الفاتورة

    @Test
    fun placementDerivedFromWhereTheOwnerMarkedInvoices() {
        val above = (1..3).map { placed("a$it", Placement.ABOVE_DOOR, Region(0.40f, 0.05f, 0.60f, 0.20f)) }
        val below = (1..3).map { placed("b$it", Placement.BELOW_DOOR, Region(0.40f, 0.75f, 0.60f, 0.92f)) }
        val prior = PlacementPrior.from(above + below)
        assertTrue(prior.active)
        assertEquals(Placement.ABOVE_DOOR, prior.placementOf(Region(0.42f, 0.07f, 0.58f, 0.18f)))
        assertEquals(Placement.BELOW_DOOR, prior.placementOf(Region(0.42f, 0.78f, 0.58f, 0.90f)))
        assertNull(prior.placementOf(Region(0.0f, 0.4f, 0.1f, 0.5f)))
    }

    @Test
    fun priorNeedsEnoughExamplesAndNeverBlocksWhenInactive() {
        val few = PlacementPrior.from((1..3).map { placed("a$it", Placement.ABOVE_DOOR, invoiceBox) })
        assertFalse(few.active)
        assertTrue(few.allowsDetected(Region(0f, 0f, 0.1f, 0.1f))) // غير فعّال: لا قيد
        assertTrue(PlacementPrior.NONE.allowsDetected(Region(0f, 0f, 0.1f, 0.1f)))
    }

    @Test
    fun unspecifiedAndIgnoreInstructionsDoNotFormPlacements() {
        val list = (1..8).map { placed("u$it", Placement.UNSPECIFIED, invoiceBox) } +
            (1..8).map { ins("i$it", InstructionKind.IGNORE, Placement.ABOVE_DOOR, wallBox) }
        assertFalse(PlacementPrior.from(list).active)
    }

    @Test
    fun activePriorOnlyDowngradesNeverUpgrades() {
        val model = InvoiceModel(
            mean = List(6) { 0f }, std = List(6) { 1f },
            weights = listOf(0f, 0f, 0f, 0f, 0f, 20f), bias = -2f,
            acceptThreshold = 0.5f, uncertainThreshold = 0.2f,
            trainedAt = 1L, positives = 20, negatives = 20, metrics = ModelMetrics(1f, 1f, 1f, 4)
        )
        val strong = InvoiceCandidate(
            InvoiceFeatures(0.2f, 0.9f, 0.3f, 0.5f, 0.0f, 0.15f), Region(0.0f, 0.4f, 0.1f, 0.5f)
        )
        val prior = PlacementPrior.from(
            (1..6).map { placed("a$it", Placement.ABOVE_DOOR, Region(0.4f, 0.05f, 0.6f, 0.2f)) }
        )
        assertTrue(prior.active)
        assertEquals(DeliveryState.DETECTED, DeliveryDetector.classify(strong, model).state)
        assertEquals(DeliveryState.UNCERTAIN, DeliveryDetector.classify(strong, model, prior).state)
        // مرشح ضعيف خارج المنطقة لا يرتفع
        val weak = strong.copy(features = strong.features.copy(textiness = 0.0f))
        assertEquals(DeliveryState.NONE, DeliveryDetector.classify(weak, model, prior).state)
        // مرشح قوي داخل المنطقة يبقى مؤكدًا
        val inside = strong.copy(region = Region(0.42f, 0.07f, 0.58f, 0.18f))
        val r = DeliveryDetector.classify(inside, model, prior)
        assertEquals(DeliveryState.DETECTED, r.state)
        assertEquals(Placement.ABOVE_DOOR, r.placement)
    }

    @Test
    fun detectorPicksTheInvoiceEvenWhenAWallIsLarger() {
        // نموذج يكافئ كثافة الحواف: الورقة النصّية تفوز، والجدار الأملس لا
        val model = InvoiceModel(
            mean = List(6) { 0f }, std = List(6) { 1f },
            weights = listOf(0f, 0f, 0f, 0f, 0f, 20f), bias = -2f,
            acceptThreshold = 0.5f, uncertainThreshold = 0.2f,
            trainedAt = 1L, positives = 20, negatives = 20, metrics = ModelMetrics(1f, 1f, 1f, 4)
        )
        val frame = TestFrames.Fake(scene(), w, h)
        val r = DeliveryDetector(model).detect(frame)
        assertEquals(DeliveryState.DETECTED, r.state)
        assertNotNull(r.region)
        assertTrue(InstructionSampler.iou(r.region!!, invoiceBox) > 0.5f)
    }
}

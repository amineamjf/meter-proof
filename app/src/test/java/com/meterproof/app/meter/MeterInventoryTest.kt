package com.meterproof.app.meter

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterInventoryTest {
    private val task = MeterTask("T1", "AB-1234 56", 33.5731, -7.5898, "حي النصر")
    private fun ocr(t: String?, c: Float = 0.9f, ok: Boolean = true) = OcrResult(t, c, ok)

    @Test fun exactMatchIgnoringFormatting() =
        assertEquals(MeterStatus.METER_MATCH, MeterMatcher.evaluate(task, ocr("ab 123456")))

    @Test fun unusableImageWinsOverEverything() =
        assertEquals(MeterStatus.IMAGE_UNUSABLE, MeterMatcher.evaluate(task, ocr("AB123456", ok = false)))

    @Test fun lowConfidenceOrEmptyIsUncertainNeverMismatch() {
        assertEquals(MeterStatus.OCR_UNCERTAIN, MeterMatcher.evaluate(task, ocr("999999", 0.3f)))
        assertEquals(MeterStatus.OCR_UNCERTAIN, MeterMatcher.evaluate(task, ocr(null)))
        assertEquals(MeterStatus.OCR_UNCERTAIN, MeterMatcher.evaluate(task, ocr("--")))
    }

    @Test fun oneCharOrConfusableGoesToManualReview() {
        assertEquals(MeterStatus.MANUAL_REVIEW, MeterMatcher.evaluate(task, ocr("AB123457")))
        val t = MeterTask("T2", "10508")
        assertEquals(MeterStatus.MANUAL_REVIEW, MeterMatcher.evaluate(t, ocr("1O5O8"))) // O بدل 0
        assertEquals(MeterStatus.MANUAL_REVIEW, MeterMatcher.evaluate(t, ocr("IO5O8"))) // I بدل 1
    }

    @Test fun clearlyDifferentIsMismatch() =
        assertEquals(MeterStatus.METER_MISMATCH, MeterMatcher.evaluate(task, ocr("ZZ999999")))

    @Test fun farLocationWarnsOnlyOnMatch() {
        val near = Fix(33.5732, -7.5899, 10f)
        val far = Fix(33.60, -7.60, 10f)
        assertEquals(MeterStatus.METER_MATCH, MeterMatcher.evaluate(task, ocr("AB123456"), near))
        assertEquals(MeterStatus.LOCATION_WARNING, MeterMatcher.evaluate(task, ocr("AB123456"), far))
        // دقة GPS ضعيفة تُوسّع الحد فلا إنذار كاذب
        assertEquals(MeterStatus.METER_MATCH, MeterMatcher.evaluate(task, ocr("AB123456"), Fix(33.5745, -7.5898, 100f)))
    }

    @Test fun correctionKeepsOriginalAndLogsEdit() {
        val rec = MeterInventory.record(task, ocr("ZZ999999"), null, 100L)
        assertEquals(MeterStatus.METER_MISMATCH, rec.status)
        val fixed = MeterInventory.correct(rec, task, "AB 123456", "الرقم واضح على اللوحة", 200L)!!
        assertEquals("ZZ999999", fixed.originalSerial)
        assertEquals("AB123456", fixed.effectiveSerial)
        assertEquals(MeterStatus.METER_MATCH, fixed.status)
        assertTrue(fixed.manuallyReviewed)
        assertEquals(1, fixed.history.size)
        assertEquals("ZZ999999", fixed.history[0].old)
        // تصحيح ثانٍ يُراكم السجل ولا يمحو الأول
        val again = MeterInventory.correct(fixed, task, "AB123457", "تراجع", 300L)!!
        assertEquals(2, again.history.size)
        assertEquals("ZZ999999", again.originalSerial)
    }

    @Test fun correctionRejectsEmptyReasonOrWrongTask() {
        val rec = MeterInventory.record(task, ocr("AB123457"), null, 1L)
        assertNull(MeterInventory.correct(rec, task, "AB123456", " ", 2L))
        assertNull(MeterInventory.correct(rec, task, "", "سبب", 2L))
        assertNull(MeterInventory.correct(rec, MeterTask("OTHER", "1"), "1", "سبب", 2L))
    }

    @Test fun joinIsByTaskIdNotRowOrder() {
        val t1 = MeterTask("A", "111"); val t2 = MeterTask("B", "222"); val t3 = MeterTask("C", "333")
        val r2 = MeterInventory.record(t2, ocr("222"), null, 1L)
        val r1 = MeterInventory.record(t1, ocr("111"), null, 2L)
        val stray = MeterInventory.record(MeterTask("Z", "9"), ocr("9"), null, 3L)
        val j = MeterInventory.join(listOf(t1, t2, t3), listOf(r2, r1, stray))
        assertEquals(listOf("A", "B"), j.matched.map { it.first.taskId })
        assertEquals("A", j.matched[0].second.taskId)
        assertEquals(listOf("C"), j.missingRecord.map { it.taskId })
        assertEquals(listOf("Z"), j.unknownRecord.map { it.taskId })
    }

    @Test fun csvImportRejectsBadAndDuplicateRows() {
        val csv = "taskId,serial,lat,lon,label\nA,111,33.5,-7.5,x\nA,222\n,333\nB,\nC,444,33.5\nD,555,95,0\nE,666\n"
        val r = TaskCsv.parse(csv)
        assertEquals(listOf("A", "E"), r.tasks.map { it.taskId })
        assertEquals(5, r.rejected.size)
    }

    @Test fun storeRoundTripsAndUpsertsById() {
        val d = File.createTempFile("mtr", "").let { it.delete(); it.mkdirs(); it }
        val st = MeterStore(d)
        st.importTasks("A,AB|12\\3,33.5,-7.5,حي|الأول\nB,222")
        assertEquals("AB123", st.tasks()[0].expectedSerial)
        val t = st.tasks()[0]
        val rec = MeterInventory.record(t, ocr("AB124"), null, 5L)
        st.upsert(MeterInventory.correct(rec, t, "AB123", "سبب|فيه\nسطر", 6L)!!)
        st.upsert(MeterInventory.record(st.tasks()[1], ocr("222"), null, 7L))
        st.upsert(MeterInventory.record(t, ocr("AB123"), null, 8L)) // يستبدل سجل A لا يضيف
        val all = st.records()
        assertEquals(2, all.size)
        assertEquals(MeterStatus.METER_MATCH, all.first { it.taskId == "A" }.status)
        val corrected = MeterCodec.decodeRecord(MeterCodec.encodeRecord(
            MeterInventory.correct(rec, t, "AB123", "سبب|فيه\nسطر", 6L)!!))!!
        assertEquals("سبب|فيه\nسطر", corrected.history[0].reason)
        assertNotNull(corrected.correctedSerial)
        assertNull(MeterCodec.decodeRecord("garbage"))
    }

    @Test fun summaryCountsStatuses() {
        val recs = listOf(
            MeterInventory.record(task, ocr("AB123456"), null, 1L),
            MeterInventory.record(task, ocr("ZZ999999"), null, 2L),
            MeterInventory.record(task, ocr("ZZ999998"), null, 3L)
        )
        val s = MeterInventory.summary(recs)
        assertEquals(1, s[MeterStatus.METER_MATCH]); assertEquals(2, s[MeterStatus.METER_MISMATCH])
    }
}

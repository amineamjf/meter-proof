package com.meterproof.app.evidence

import com.meterproof.app.privacy.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EvidenceBoxesTest {
    private val p1 = "aaaaaaaa-1111"
    private val p2 = "bbbbbbbb-2222"

    private fun store(): EvidenceBoxStore =
        EvidenceBoxStore(Files.createTempDirectory("ebox").toFile())

    private fun box(id: String, kind: BoxKind, photo: String, r: Region = Region(0.2f, 0.3f, 0.6f, 0.7f)) =
        EvidenceBox(id, kind, r, photo, 100L)

    @Test fun roundTripKeepsBoxesAndNegatives() {
        val d = BoxData(
            listOf(box("11111111-a", BoxKind.METER_BOX, p1), box("22222222-b", BoxKind.DOOR, p1)),
            listOf(NoBoxMark(BoxKind.METER_BOX, p2, 5L))
        )
        val back = EvidenceBoxCodec.decode(EvidenceBoxCodec.encode(d))
        assertEquals(d, back)
    }

    @Test fun corruptLinesAreSkippedNotFatal() {
        val good = EvidenceBoxCodec.encode(BoxData(listOf(box("11111111-a", BoxKind.METER_BOX, p1)), emptyList()))
        val text = "garbage\nEB1;x;M;a,b,c,d;$p1;1\nEB1;11111111-b;Z;0,0,1,1;$p1;1\nEB1;11111111-c;M;0.5,0.5,0.5,0.5;$p1;1\n$good\nEN1;M;not an id;1"
        val back = EvidenceBoxCodec.decode(text)
        assertEquals(1, back.boxes.size)
        assertEquals(0, back.noBox.size)
    }

    @Test fun tinyAndNanBoxesRejected() {
        val s = store()
        assertFalse(s.addBox(box("11111111-a", BoxKind.METER_BOX, p1, Region(0.5f, 0.5f, 0.51f, 0.51f))))
        assertTrue(s.addBox(box("11111111-b", BoxKind.METER_BOX, p1)))
        assertEquals(1, s.boxesOf(p1).size)
    }

    @Test fun outOfRangeIsClamped() {
        val s = store()
        assertTrue(s.addBox(box("11111111-a", BoxKind.DOOR, p1, Region(-0.5f, 0.1f, 2f, 0.9f))))
        val r = s.boxesOf(p1).single().region
        assertEquals(0f, r.left, 0f); assertEquals(1f, r.right, 0f)
    }

    @Test fun drawingBoxClearsNoBoxMarkOfSameKindOnly() {
        val s = store()
        s.markNoBox(p1, BoxKind.METER_BOX, 1L)
        s.markNoBox(p1, BoxKind.DOOR, 1L)
        s.addBox(box("11111111-a", BoxKind.METER_BOX, p1))
        assertEquals(listOf(BoxKind.DOOR), s.noBoxMarks(p1).map { it.kind })
    }

    @Test fun markingNoBoxRemovesBoxesOfThatKindOnly() {
        val s = store()
        s.addBox(box("11111111-a", BoxKind.METER_BOX, p1))
        s.addBox(box("11111111-b", BoxKind.DOOR, p1))
        s.markNoBox(p1, BoxKind.METER_BOX, 2L)
        assertEquals(listOf(BoxKind.DOOR), s.boxesOf(p1).map { it.kind })
        assertEquals(1, s.noBoxMarks(p1).size)
    }

    @Test fun unlabeledPhotoIsNeverCountedAsNegative() {
        val s = store()
        s.addBox(box("11111111-a", BoxKind.METER_BOX, p1))
        val c = s.counts(BoxKind.METER_BOX)
        assertEquals(1, c.positivePhotos); assertEquals(0, c.negativePhotos)
        assertFalse(c.readyForFirstTraining)
    }

    @Test fun countsAndReadiness() {
        val s = store()
        for (i in 0 until 30) s.addBox(box("%08d-a".format(i), BoxKind.METER_BOX, "%08d-ab".format(i)))
        for (i in 0 until 15) s.markNoBox("%08d-cd".format(i), BoxKind.METER_BOX, 1L)
        assertTrue(s.counts(BoxKind.METER_BOX).readyForFirstTraining)
        assertFalse(s.counts(BoxKind.DOOR).readyForFirstTraining)
    }

    @Test fun deleteBoxAndIndependentFolder() {
        val s = store()
        s.addBox(box("11111111-a", BoxKind.METER_BOX, p1))
        assertTrue(s.deleteBox("11111111-a"))
        assertFalse(s.deleteBox("11111111-a"))
        assertTrue(s.data().boxes.isEmpty())
        assertEquals("evidence_boxes", EvidenceBoxStore.DIR_NAME)
        assertTrue(com.meterproof.app.delivery.TrainingTask.values().none { it.dirName == EvidenceBoxStore.DIR_NAME })
    }

    @Test fun duplicatePhotoIdCountedOnce() {
        val s = store()
        s.addBox(box("11111111-a", BoxKind.METER_BOX, p1))
        s.addBox(box("11111111-b", BoxKind.METER_BOX, p1, Region(0.1f, 0.1f, 0.3f, 0.3f)))
        val c = s.counts(BoxKind.METER_BOX)
        assertEquals(1, c.positivePhotos); assertEquals(2, c.boxes)
    }
}

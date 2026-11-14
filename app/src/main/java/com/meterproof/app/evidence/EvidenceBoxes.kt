package com.meterproof.app.evidence

import com.meterproof.app.delivery.AtomicText
import com.meterproof.app.privacy.Region
import java.io.File
import java.util.UUID

/**
 * مربعات «دليل الوصول»: صندوق العداد أو الباب، يرسمها المالك على صوره ليتعلم منها التطبيق لاحقًا.
 *
 * مستقلة تمامًا عن تدريب الفواتير: مجلد بيانات خاص ([DIR_NAME])، ولا تُقرأ ولا تُكتب من/إلى تعليمات الفاتورة.
 * تُحفظ إحداثيات نسبية (0..1) ومعرّف الصورة فقط، بلا بكسلات ولا نصوص حرة.
 * لا تغيّر الصورة ولا السجل المبصوم. حذف الصورة لا يكسرها (تبقى بياناتها الوصفية).
 */
enum class BoxKind(val code: String, val arabic: String) {
    METER_BOX("M", "صندوق العداد"),
    DOOR("D", "الباب");

    companion object {
        fun fromCode(c: String): BoxKind? = values().firstOrNull { it.code == c }
    }
}

/** مربع مرسوم على صورة. */
data class EvidenceBox(
    val id: String,
    val kind: BoxKind,
    val region: Region,
    val photoId: String,
    val createdAt: Long
)

/**
 * علامة «لا يوجد [kind] في هذه الصورة»: سلبية صريحة. صورة بلا مربع وبلا علامة تبقى «غير معلَّمة»،
 * ولا تُعدّ سلبية أبدًا (غياب الرسم ليس غياب الجسم).
 */
data class NoBoxMark(val kind: BoxKind, val photoId: String, val createdAt: Long)

data class BoxData(val boxes: List<EvidenceBox>, val noBox: List<NoBoxMark>)

/** ترميز نصي سطرًا لكل عنصر. السطر التالف يُتجاهل ولا يُفشل القراءة. */
object EvidenceBoxCodec {
    private const val BOX = "EB1"
    private const val NEG = "EN1"
    private val ID = Regex("^[0-9a-fA-F-]{8,40}$")

    fun encode(d: BoxData): String {
        val lines = ArrayList<String>()
        for (b in d.boxes) {
            val r = b.region
            lines += listOf(
                BOX, b.id, b.kind.code,
                listOf(r.left, r.top, r.right, r.bottom).joinToString(","),
                b.photoId, b.createdAt.toString()
            ).joinToString(";")
        }
        for (n in d.noBox) lines += listOf(NEG, n.kind.code, n.photoId, n.createdAt.toString()).joinToString(";")
        return lines.joinToString("\n")
    }

    fun decode(text: String): BoxData {
        val boxes = ArrayList<EvidenceBox>()
        val negs = ArrayList<NoBoxMark>()
        for (line in text.lineSequence()) {
            val p = line.trim().split(';')
            try {
                when (p.firstOrNull()) {
                    BOX -> {
                        if (p.size != 6) continue
                        val kind = BoxKind.fromCode(p[2]) ?: continue
                        val c = p[3].split(',').map { it.toFloat() }
                        if (c.size != 4 || c.any { it.isNaN() || it.isInfinite() }) continue
                        val r = Region(c[0], c[1], c[2], c[3]).clamped()
                        if (!valid(r)) continue
                        if (!ID.matches(p[1]) || !ID.matches(p[4])) continue
                        boxes += EvidenceBox(p[1], kind, r, p[4], p[5].toLong())
                    }
                    NEG -> {
                        if (p.size != 4) continue
                        val kind = BoxKind.fromCode(p[1]) ?: continue
                        if (!ID.matches(p[2])) continue
                        negs += NoBoxMark(kind, p[2], p[3].toLong())
                    }
                }
            } catch (e: NumberFormatException) {
                // سطر تالف: يُتجاهل
            }
        }
        return BoxData(boxes, negs)
    }

    /** مربع صالح: مساحة ظاهرة وليس أصغر من نقطة. */
    fun valid(r: Region): Boolean = r.right - r.left >= MIN_SIDE && r.bottom - r.top >= MIN_SIDE

    const val MIN_SIDE = 0.02f
}

/** عدّادات الجاهزية: كم مثالًا إيجابيًا وسلبيًا صريحًا لكل نوع، وكم صورة مختلفة. */
data class BoxCounts(val positivePhotos: Int, val negativePhotos: Int, val boxes: Int) {
    /** حدّ أدنى مبدئي للتجربة، لا ضمان دقة. */
    val readyForFirstTraining get() = positivePhotos >= MIN_POSITIVE && negativePhotos >= MIN_NEGATIVE

    companion object {
        const val MIN_POSITIVE = 30
        const val MIN_NEGATIVE = 15
    }
}

/**
 * مخزن المربعات: ملف نصي واحد في [DIR_NAME]. يأخذ المجلد مباشرة ليُختبر على JVM.
 * صورة واحدة قد تحمل عدة مربعات، لكن صورة فيها مربع من نوع ما لا تحمل علامة «لا يوجد» لنفس النوع
 * (الحدث الأحدث يفوز: رسم مربع يمسح العلامة، وإضافة العلامة تمسح مربعات ذلك النوع على الصورة).
 */
class EvidenceBoxStore(dir: File) {

    private val file = File(dir.apply { mkdirs() }, "boxes.txt")

    companion object {
        const val DIR_NAME = "evidence_boxes"
        const val MAX_BOXES = 1500
        private val LOCK = Any()
        fun newId(): String = UUID.randomUUID().toString()
    }

    fun data(): BoxData = synchronized(LOCK) {
        if (!file.exists()) BoxData(emptyList(), emptyList()) else EvidenceBoxCodec.decode(file.readText())
    }

    fun boxesOf(photoId: String): List<EvidenceBox> = data().boxes.filter { it.photoId == photoId }

    fun noBoxMarks(photoId: String): List<NoBoxMark> = data().noBox.filter { it.photoId == photoId }

    fun addBox(b: EvidenceBox): Boolean = synchronized(LOCK) {
        val clamped = b.region.clamped()
        if (!EvidenceBoxCodec.valid(clamped)) return false
        val d = data()
        val boxes = (d.boxes + b.copy(region = clamped)).takeLast(MAX_BOXES)
        val negs = d.noBox.filterNot { it.photoId == b.photoId && it.kind == b.kind }
        write(BoxData(boxes, negs))
        true
    }

    fun deleteBox(id: String): Boolean = synchronized(LOCK) {
        val d = data()
        val rest = d.boxes.filter { it.id != id }
        if (rest.size == d.boxes.size) return false
        write(BoxData(rest, d.noBox))
        true
    }

    /** يعلّم الصورة بأنها بلا [kind]، ويمسح مربعات ذلك النوع عليها. */
    fun markNoBox(photoId: String, kind: BoxKind, now: Long): Unit = synchronized(LOCK) {
        val d = data()
        val boxes = d.boxes.filterNot { it.photoId == photoId && it.kind == kind }
        val negs = d.noBox.filterNot { it.photoId == photoId && it.kind == kind } + NoBoxMark(kind, photoId, now)
        write(BoxData(boxes, negs))
    }

    fun clearNoBox(photoId: String, kind: BoxKind): Unit = synchronized(LOCK) {
        val d = data()
        write(BoxData(d.boxes, d.noBox.filterNot { it.photoId == photoId && it.kind == kind }))
    }

    fun counts(kind: BoxKind): BoxCounts {
        val d = data()
        val pos = d.boxes.filter { it.kind == kind }
        return BoxCounts(
            positivePhotos = pos.map { it.photoId }.toSet().size,
            negativePhotos = d.noBox.filter { it.kind == kind }.map { it.photoId }.toSet().size,
            boxes = pos.size
        )
    }

    fun clear() {
        synchronized(LOCK) { file.delete() }
    }

    private fun write(d: BoxData) = AtomicText.write(file, EvidenceBoxCodec.encode(d))
}

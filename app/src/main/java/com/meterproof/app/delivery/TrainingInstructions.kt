package com.meterproof.app.delivery

import com.meterproof.app.privacy.Region
import kotlin.math.max
import kotlin.math.min

/**
 * مهام التدريب المستقلة. لكل مهمة مجلد بيانات خاص بها، فلا تختلط عيناتها ولا نماذجها ولا تعليماتها.
 * الفواتير فقط مفعّلة الآن. اسم مجلدها `delivery` هو المجلد القائم أصلًا، كي لا تضيع معايرة سابقة.
 * العداد محجوز ولا يُستعمل (مؤجَّل بقرار المالك).
 */
enum class TrainingTask(val dirName: String) {
    INVOICE("delivery"),
    METER("meter_training")
}

/** نوع التعليمة: ما الذي يريد المالك أن يتعلمه التطبيق من المنطقة المرسومة. */
enum class InstructionKind(val code: String, val arabic: String) {
    /** هذه المنطقة فيها الفاتورة: تعرّف على ما يشبهها. */
    RECOGNIZE("R", "فاتورة: تعرّف عليها"),

    /** هذه المنطقة فيها شيء ليس فاتورة (ورقة جار، سيارة، جدار): تجاهل ما يشبهه. */
    IGNORE("I", "تجاهل: ليست فاتورة");

    companion object {
        fun fromCode(c: String): InstructionKind? = values().firstOrNull { it.code == c }
    }
}

/** موضع الفاتورة عند المدخل (للتعليمات من نوع RECOGNIZE). */
enum class Placement(val code: String, val arabic: String) {
    ABOVE_DOOR("A", "فوق الباب"),
    BELOW_DOOR("B", "تحت الباب"),
    BESIDE_DOOR("S", "بجانب الباب"),
    UNSPECIFIED("U", "غير محدد");

    companion object {
        fun fromCode(c: String): Placement? = values().firstOrNull { it.code == c }
    }
}

/** عينة مشتقة من تعليمة: سمات رقمية فقط (لا بكسلات) وتصنيفها. */
data class DerivedSample(val features: InvoiceFeatures, val positive: Boolean)

/**
 * تعليمة تدريب واحدة رسمها المالك على صورة. تحمل عيّناتها المشتقة معها، فتبقى مستقلة عن الصورة:
 * حذف التعليمة يحذف عيّناتها، وحذف الصورة لا يكسرها. لا تُحفظ أي بكسلات.
 *
 * [note] ملاحظة قصيرة محلية للمالك فقط (لا تدخل السجل المبصوم ولا التصدير).
 */
data class TrainingInstruction(
    val id: String,
    val kind: InstructionKind,
    val region: Region,
    val placement: Placement,
    val photoId: String,
    val note: String,
    val createdAt: Long,
    val samples: List<DerivedSample>
) {
    val positives: Int get() = samples.count { it.positive }
    val negatives: Int get() = samples.count { !it.positive }
}

/** ترميز نصي سطرًا لكل تعليمة (بلا JSON حتى يُختبر على JVM). السطر التالف يُتجاهل ولا يُفشل القراءة. */
object InstructionCodec {
    private const val V = "TI1"
    const val MAX_NOTE = 60

    /** الملاحظة بلا أي فاصل يستعمله الترميز، وبطول محدود. */
    fun cleanNote(s: String): String =
        s.map { if (it == ';' || it == '|' || it == ':' || it == ',' || it == '\n' || it == '\r') ' ' else it }
            .joinToString("").trim().take(MAX_NOTE)

    fun encode(list: List<TrainingInstruction>): String = list.joinToString("\n") { i ->
        val r = i.region
        listOf(
            V, i.id, i.kind.code, listOf(r.left, r.top, r.right, r.bottom).joinToString(","),
            i.placement.code, i.photoId, i.createdAt.toString(), cleanNote(i.note),
            i.samples.joinToString("|") { s ->
                (if (s.positive) "1" else "0") + ":" + s.features.toList().joinToString(",")
            }
        ).joinToString(";")
    }

    fun decode(text: String): List<TrainingInstruction> {
        val out = ArrayList<TrainingInstruction>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            try {
                val p = line.split(';')
                if (p.size != 9 || p[0] != V) continue
                val box = p[3].split(',').map { it.toFloat() }
                if (box.size != 4 || box.any { !it.isFinite() }) continue
                val samples = if (p[8].isEmpty()) emptyList() else p[8].split('|').map { part ->
                    val (flag, feats) = part.split(':', limit = 2)
                    val f = InvoiceFeatures.fromList(feats.split(',').map { it.toFloat() })
                        ?: throw IllegalArgumentException("bad features")
                    DerivedSample(f, flag == "1")
                }
                out.add(
                    TrainingInstruction(
                        id = p[1],
                        kind = InstructionKind.fromCode(p[2]) ?: continue,
                        region = Region(box[0], box[1], box[2], box[3]).clamped(),
                        placement = Placement.fromCode(p[4]) ?: Placement.UNSPECIFIED,
                        photoId = p[5],
                        createdAt = p[6].toLong(),
                        note = p[7],
                        samples = samples
                    )
                )
            } catch (e: Exception) {
                // سطر تالف: يُتجاهل
            }
        }
        return out
    }
}

/**
 * يحوّل منطقة رسمها المالك إلى عينات تدريب. الفكرة: «التعليم بالإشارة». من كل صورة نحصل على عدة
 * أمثلة لا مثال واحد، لأن المحلل يعيد كل المرشحين الفاتحين (جدار، سيارة، ورقة، فاتورة).
 *
 * - RECOGNIZE: المرشح الأكثر تداخلًا مع المنطقة موجب. وباقي المرشحين البعيدين عنها سالبون (حتى 3):
 *   «هذه الأشياء الفاتحة الأخرى في صورة فيها فاتورة ليست الفاتورة».
 * - IGNORE: كل مرشح يقع في المنطقة سالب.
 * - إن لم يلتقط المحلل العام شيئًا داخل المنطقة، يُستعمل [within] (تحليل داخل المنطقة وحدها).
 */
object InstructionSampler {
    const val MIN_OVERLAP = 0.25f
    private const val FAR_OVERLAP = 0.10f
    const val MAX_NEGATIVES_PER_RECOGNIZE = 3
    const val MAX_NEGATIVES_PER_IGNORE = 5

    /** تداخل إطارين (نسبة المشترك إلى الاتحاد). */
    fun iou(a: Region, b: Region): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bt = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bt - t)
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** أي جزء من [inner] يقع داخل [outer] (0..1). */
    fun coverage(inner: Region, outer: Region): Float {
        if (inner.area <= 0f) return 0f
        val l = max(inner.left, outer.left)
        val t = max(inner.top, outer.top)
        val r = min(inner.right, outer.right)
        val bt = min(inner.bottom, outer.bottom)
        return max(0f, r - l) * max(0f, bt - t) / inner.area
    }

    fun derive(
        kind: InstructionKind,
        region: Region,
        all: List<InvoiceCandidate>,
        within: InvoiceCandidate?
    ): List<DerivedSample> {
        val box = region.clamped()
        return when (kind) {
            InstructionKind.RECOGNIZE -> {
                val hit = all.filter { iou(box, it.region) >= MIN_OVERLAP }
                    .maxByOrNull { iou(box, it.region) }
                val positive = hit ?: within ?: return emptyList()
                val negatives = all
                    .filter { it !== positive && iou(box, it.region) < FAR_OVERLAP }
                    .sortedByDescending { it.features.areaFrac }
                    .take(MAX_NEGATIVES_PER_RECOGNIZE)
                listOf(DerivedSample(positive.features, true)) +
                    negatives.map { DerivedSample(it.features, false) }
            }
            InstructionKind.IGNORE -> {
                val hits = all.filter {
                    iou(box, it.region) >= MIN_OVERLAP || coverage(it.region, box) >= 0.5f
                }.sortedByDescending { it.features.areaFrac }.take(MAX_NEGATIVES_PER_IGNORE)
                val chosen = if (hits.isNotEmpty()) hits else listOfNotNull(within)
                chosen.map { DerivedSample(it.features, false) }
            }
        }
    }
}

/**
 * أين تظهر الفاتورة عادة بالنسبة لإطار الصورة، لكل موضع علّمه المالك (فوق الباب / تحته / بجانبه).
 * المنطق محافظ عمدًا: لا يرفع قبولًا أبدًا، بل يخفّض مرشحًا خارج كل المناطق المتعلَّمة من «مؤكد»
 * إلى «غير مؤكد» (القبول الخاطئ أخطر من الفوات). ولا يعمل قبل [MIN_FOR_GATING] أمثلة، كي لا تُقيَّد
 * الفاتورة بناءً على مثالين.
 */
class PlacementPrior(val boxes: Map<Placement, Region>, val examples: Int) {

    val active: Boolean get() = examples >= MIN_FOR_GATING && boxes.isNotEmpty()

    /** الموضع الذي يقع فيه مركز [r]، أو null إن لم يقع في أي موضع متعلَّم. الأقرب مركزًا عند التعدد. */
    fun placementOf(r: Region): Placement? {
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        return boxes.entries
            .filter { cx >= it.value.left && cx <= it.value.right && cy >= it.value.top && cy <= it.value.bottom }
            .minByOrNull {
                val bx = (it.value.left + it.value.right) / 2f
                val by = (it.value.top + it.value.bottom) / 2f
                (cx - bx) * (cx - bx) + (cy - by) * (cy - by)
            }?.key
    }

    /** هل يُسمح للمرشح بأن يكون «مؤكدًا»؟ دائمًا نعم إن لم يكن النموذج فعّالًا. */
    fun allowsDetected(r: Region): Boolean = !active || placementOf(r) != null

    companion object {
        const val MIN_FOR_GATING = 6
        private const val MARGIN = 0.12f
        val NONE = PlacementPrior(emptyMap(), 0)

        /** من تعليمات RECOGNIZE التي التقطت عينة موجبة ولها موضع محدد. */
        fun from(list: List<TrainingInstruction>): PlacementPrior {
            val usable = list.filter {
                it.kind == InstructionKind.RECOGNIZE &&
                    it.placement != Placement.UNSPECIFIED && it.positives > 0
            }
            val boxes = LinkedHashMap<Placement, Region>()
            for ((pl, group) in usable.groupBy { it.placement }) {
                val xs = group.map { (it.region.left + it.region.right) / 2f }
                val ys = group.map { (it.region.top + it.region.bottom) / 2f }
                boxes[pl] = Region(
                    xs.min() - MARGIN, ys.min() - MARGIN, xs.max() + MARGIN, ys.max() + MARGIN
                ).clamped()
            }
            return PlacementPrior(boxes, usable.size)
        }
    }
}

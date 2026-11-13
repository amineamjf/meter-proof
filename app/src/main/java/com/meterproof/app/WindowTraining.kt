package com.meterproof.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meterproof.app.delivery.DeliveryStore
import com.meterproof.app.delivery.InstructionKind
import com.meterproof.app.delivery.InstructionStore
import com.meterproof.app.delivery.TrainImage
import com.meterproof.app.delivery.TwoStageTrainer
import com.meterproof.app.delivery.VCand
import com.meterproof.app.delivery.VImage
import com.meterproof.app.delivery.WindowScene
import com.meterproof.app.delivery.WindowTrainer

/**
 * تدريب كاشف الفاتورة بالنوافذ من الصور المعلَّمة: مربعات «فاتورة» و«تجاهل» المرسومة، والصور المصنَّفة «بلا فاتورة».
 * تُقرأ الصور من ملفات الأدلة الأصلية داخل الذاكرة فقط، ولا يُكتب منها شيء. يُحفظ النموذج النهائي (أوزان).
 */
object WindowTraining {

    class Outcome(
        val result: WindowTrainer.Result,
        /** صور مصنَّفة «فاتورة» بلا مربع مرسوم: لا يمكن التعلم منها (لا نعرف أين الفاتورة). */
        val invoiceWithoutBox: Int,
        val unreadable: Int,
        val usedInvoiceImages: Int,
        val usedNoneImages: Int,
        /** نتيجة المدقِّق الدلالي (المرحلة الثانية)، أو null إن لم تُجرَّ. */
        val twoStage: TwoStageTrainer.Result? = null,
        /** سبب عدم إجراء المرحلة الثانية، إن لم تُجرَ. */
        val twoStageSkipped: String? = null,
        /** قرار سجل الإصدارات (اعتماد أو رفض) حين نجح التدريب، وإلا null. */
        val decision: com.meterproof.app.delivery.ModelRegistry.Decision? = null
    )

    private const val MAX_IMAGES = 120

    fun run(context: Context, now: Long = System.currentTimeMillis()): Outcome {
        val evidence = EvidenceStore(context)
        val instructions = com.meterproof.app.delivery.InstructionStores.forTask(context).list()
        val labels = PhotoLabels.latest(evidence.events())
        val flagsById = try {
            evidence.readRecords().associate { it.optString("id") to it.optString("flags") }
        } catch (e: Exception) {
            emptyMap()
        }

        val images = ArrayList<TrainImage>()
        var unreadable = 0
        val byPhoto = instructions.groupBy { it.photoId }
        val covered = HashSet<String>()

        fun load(id: String): WindowScene? {
            val flags = flagsById[id] ?: ""
            if (!PhotoLabels.usableForTraining(flags)) return null
            val f = evidence.imageFile(id)
            if (!f.exists()) return null
            val px = decode(f) ?: return null
            return try {
                WindowScene.from(px.first, px.second, px.third)
            } finally {
                px.first.fill(0)
            }
        }

        // صور فيها تعليمات (الأحدث أولًا)
        for ((photoId, list) in byPhoto.entries.sortedByDescending { e -> e.value.maxOf { it.createdAt } }) {
            if (images.size >= MAX_IMAGES) break
            val recognize = list.filter { it.kind == InstructionKind.RECOGNIZE }.map { it.region }
            val ignore = list.filter { it.kind == InstructionKind.IGNORE }.map { it.region }
            val none = recognize.isEmpty() && labels[photoId].let { it == PhotoLabels.Label.NONE || it == PhotoLabels.Label.SIMILAR }
            val scene = load(photoId)
            if (scene == null) { unreadable++; continue }
            covered.add(photoId)
            images.add(TrainImage.build(photoId, scene, recognize, ignore, none))
        }
        // صور «بلا فاتورة» بلا تعليمات
        for ((id, label) in labels) {
            if (images.size >= MAX_IMAGES) break
            if ((label != PhotoLabels.Label.NONE && label != PhotoLabels.Label.SIMILAR) || id in covered) continue
            val scene = load(id)
            if (scene == null) { unreadable++; continue }
            images.add(TrainImage.build(id, scene, emptyList(), emptyList(), true))
        }

        val invoiceWithoutBox = labels.count { (id, l) ->
            l == PhotoLabels.Label.INVOICE &&
                byPhoto[id]?.none { it.kind == InstructionKind.RECOGNIZE } != false
        }
        val result = WindowTrainer.train(images, now)
        val ds = DeliveryStore(context)

        // المرحلة الثانية: مدقِّق دلالي فوق كاشف النوافذ (لا معنى لها دون كاشف نوافذ جاهز)
        var two: TwoStageTrainer.Result? = null
        var skipped: String? = null
        if (result is WindowTrainer.Result.Ready) {
            try {
                val plans = TwoStageTrainer.plan(images, now)
                if (plans == null) {
                    skipped = "الصور قليلة للترشيح المتقاطع"
                } else {
                    val fold = TwoStageTrainer.folds(images)
                    val v = ArrayList<VImage>()
                    var failedDescribe = 0
                    for ((i, img) in images.withIndex()) {
                        if (fold[i] < 0) continue
                        val px = decode(evidence.imageFile(img.id))
                        if (px == null) { failedDescribe++; continue }
                        try {
                            fun conv(c: com.meterproof.app.delivery.Cand): VCand? {
                                val s = SemanticLabeler.vector(px.first, px.second, px.third, c.region) ?: return null
                                return VCand(c.stage1, c.hit, s)
                            }
                            val cands = plans[i].cands.mapNotNull { conv(it) }
                            if (cands.size < plans[i].cands.size) failedDescribe++
                            v.add(VImage(img.id, img.hasInvoice, fold[i], cands, plans[i].extra.mapNotNull { conv(it) }))
                        } finally {
                            px.first.fill(0)
                        }
                    }
                    if (failedDescribe > v.size / 4 + 1) {
                        skipped = "تعذّر وصف الكثير من المرشحين (فشل نموذج الوصف)"
                    } else {
                        two = TwoStageTrainer.train(v, now)
                    }
                }
            } catch (e: Throwable) {
                skipped = "تعذّرت المرحلة الثانية (${e.javaClass.simpleName})"
            }
        }

        // سجل الإصدارات: يُعتمد الجديد فقط إن لم يتراجع عن المستقر. الفاشل لا يمسّ المستقر.
        var decision: com.meterproof.app.delivery.ModelRegistry.Decision? = null
        if (result is WindowTrainer.Result.Ready) {
            val verifier = (two as? TwoStageTrainer.Result.Ready)?.model
            val eff = (two as? TwoStageTrainer.Result.Ready)?.report ?: result.report
            val m = result.model.metrics
            val vm = verifier?.metrics
            val h = result.report.holdout
            val meta = com.meterproof.app.delivery.ModelRegistry.Meta(
                version = 0, trainedAt = now,
                device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                invoiceImages = eff.invoiceImages, noneImages = eff.noneImages,
                tp = eff.hit, fp = eff.falseAccepts, fn = eff.invoiceImages - eff.hit,
                tn = eff.noneImages - eff.falseAccepts,
                precision = vm?.precision ?: m.precision, recall = vm?.recall ?: m.recall,
                holdoutDetected = h?.detected ?: -1, holdoutInvoice = h?.invoiceImages ?: -1,
                holdoutFalse = h?.falseAccepts ?: -1, holdoutNone = h?.noneImages ?: -1,
                hasVerifier = verifier != null,
                settings = "windows;rounds=${WindowTrainer.Config().rounds};epochs=${WindowTrainer.Config().epochs};" +
                    "verifier=${if (verifier != null) "yes" else "no"}",
                decision = "", reason = ""
            )
            decision = ds.registry.submit(meta, result.model, verifier)
        }
        return Outcome(
            result, invoiceWithoutBox, unreadable,
            images.count { it.hasInvoice }, images.count { it.fullyNegative }, two, skipped, decision
        )
    }

    /** يفك الصورة بدقة لا تتجاوز 1280 للضلع الأطول: (بكسلات، عرض، ارتفاع) أو null. */
    fun decode(f: java.io.File): Triple<IntArray, Int, Int>? {
        var bmp: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= 1280) sample *= 2
            bmp = BitmapFactory.decodeFile(
                f.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return Triple(px, bmp.width, bmp.height)
        } catch (e: Throwable) {
            return null
        } finally {
            bmp?.recycle()
        }
    }
}

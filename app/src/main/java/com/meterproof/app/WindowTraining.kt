package com.meterproof.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meterproof.app.delivery.DeliveryStore
import com.meterproof.app.delivery.InstructionKind
import com.meterproof.app.delivery.InstructionStore
import com.meterproof.app.delivery.TrainImage
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
        val usedNoneImages: Int
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
            val none = recognize.isEmpty() && labels[photoId] == PhotoLabels.Label.NONE
            val scene = load(photoId)
            if (scene == null) { unreadable++; continue }
            covered.add(photoId)
            images.add(TrainImage.build(photoId, scene, recognize, ignore, none))
        }
        // صور «بلا فاتورة» بلا تعليمات
        for ((id, label) in labels) {
            if (images.size >= MAX_IMAGES) break
            if (label != PhotoLabels.Label.NONE || id in covered) continue
            val scene = load(id)
            if (scene == null) { unreadable++; continue }
            images.add(TrainImage.build(id, scene, emptyList(), emptyList(), true))
        }

        val invoiceWithoutBox = labels.count { (id, l) ->
            l == PhotoLabels.Label.INVOICE &&
                byPhoto[id]?.none { it.kind == InstructionKind.RECOGNIZE } != false
        }
        val result = WindowTrainer.train(images, now)
        DeliveryStore(context).applyWindowResult(result)
        return Outcome(
            result, invoiceWithoutBox, unreadable,
            images.count { it.hasInvoice }, images.count { it.fullyNegative }
        )
    }

    /** يفك الصورة بدقة لا تتجاوز 1280 للضلع الأطول: (بكسلات، عرض، ارتفاع) أو null. */
    private fun decode(f: java.io.File): Triple<IntArray, Int, Int>? {
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

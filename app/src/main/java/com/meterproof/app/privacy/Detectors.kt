package com.meterproof.app.privacy

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.util.concurrent.TimeUnit

/** إطار مبني على Bitmap. الكواشف تتطلبه، وأي إطار آخر يُعدّ فشلًا (فالصورة تُتلف). */
class BitmapFrame(val bitmap: Bitmap) : Frame {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun argb(): IntArray {
        val px = IntArray(width * height)
        bitmap.getPixels(px, 0, width, 0, 0, width, height)
        return px
    }
}

private fun bitmapOf(frame: Frame): Bitmap =
    (frame as? BitmapFrame)?.bitmap ?: throw IllegalArgumentException("BitmapFrame required")

/**
 * كاشف الوجوه (ML Kit، النموذج مضمّن ويعمل دون إنترنت).
 * وضع الدقة العالية وأصغر حجم وجه 2% من عرض الصورة: نريد أعلى استدعاء ولو زادت الإنذارات الكاذبة،
 * لأن الإنذار الكاذب يعني إتلاف صورة فقط.
 */
class FaceHazardDetector : HazardDetector {
    override val name: String = NAME

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.02f)
                .build()
        )
    }

    override fun detect(frame: Frame): List<Hazard> {
        val bmp = bitmapOf(frame)
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)), 9, TimeUnit.SECONDS)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        return faces.map { f ->
            val b = f.boundingBox
            Hazard(HazardKind.FACE, 1f, Region(b.left / w, b.top / h, b.right / w, b.bottom / h).clamped())
        }
    }

    companion object {
        const val NAME = "FACE"
    }
}

/**
 * كاشف الأشخاص (ML Kit Selfie Segmentation، النموذج مضمّن ويعمل دون إنترنت).
 * يعطي قناع أشخاص لكل بكسل، والقرار في PersonMask. يلتقط الجسم من الخلف أيضًا،
 * لكنه صُمم للقطات الشخصية فقد يُفوّت أشخاصًا صغارًا جدًا أو بعيدين. الواجهة تسمح
 * بإضافة كاشف آخر (مثل COCO) لاحقًا دون تغيير البوابة.
 */
class PersonHazardDetector : HazardDetector {
    override val name: String = NAME

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                // قناع بحجم مخرج النموذج (نحو 256x256) بدل تكبيره إلى حجم الصورة: أسرع وأخف ذاكرة
                .enableRawSizeMask()
                .build()
        )
    }

    override fun detect(frame: Frame): List<Hazard> {
        val bmp = bitmapOf(frame)
        val mask = Tasks.await(segmenter.process(InputImage.fromBitmap(bmp, 0)), 9, TimeUnit.SECONDS)
        val w = mask.width
        val h = mask.height
        val buf = mask.buffer
        buf.rewind()
        val floats = buf.asFloatBuffer()
        val values = FloatArray(w * h)
        if (floats.remaining() < values.size) throw IllegalStateException("mask buffer too small")
        floats.get(values)
        return listOfNotNull(PersonMask.analyze(values, w, h))
    }

    companion object {
        const val NAME = "PERSON"
    }
}

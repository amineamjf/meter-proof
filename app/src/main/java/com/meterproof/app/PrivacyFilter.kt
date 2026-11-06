package com.meterproof.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * مرشح الخصوصية: يعمل بالكامل على الجهاز وفي الذاكرة.
 *
 * يكشف الوجوه ويطمسها بفسيفساء قوية. إن حدث أي خطأ يعيد null
 * ويُتلف المُستدعي الصورة (الأمان افتراضيًا): لا تُحفظ صورة لم يكتمل تشويهها.
 *
 * ملاحظة: كاشف الوجوه لا يكشف الأشخاص من الخلف أو الجانب البعيد.
 * كشف الأشخاص واللوحات مخطط في المرحلة 3.
 */
object PrivacyFilter {

    private const val MAX_SIDE = 1280
    private const val BLOCKS_ACROSS = 8

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.04f)
                .build()
        )
    }

    fun process(src: Bitmap): Bitmap? {
        return try {
            val scaled = scaleDown(src)
            val bmp = scaled.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val faces = Tasks.await(
                detector.process(InputImage.fromBitmap(bmp, 0)),
                20, TimeUnit.SECONDS
            )
            for (face in faces) {
                pixelate(bmp, face.boundingBox)
            }
            bmp
        } catch (e: Throwable) {
            null
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= MAX_SIDE) return src
        val scale = MAX_SIDE / longest.toFloat()
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun pixelate(bmp: Bitmap, box: Rect) {
        val padX = (box.width() * 0.35f).toInt()
        val padY = (box.height() * 0.35f).toInt()
        val r = Rect(box.left - padX, box.top - padY, box.right + padX, box.bottom + padY)
        if (!r.intersect(0, 0, bmp.width, bmp.height)) return
        val w = r.width()
        val h = r.height()
        if (w < 2 || h < 2) return

        val region = Bitmap.createBitmap(bmp, r.left, r.top, w, h)
        val smallH = maxOf(1, BLOCKS_ACROSS * h / w)
        val small = Bitmap.createScaledBitmap(region, BLOCKS_ACROSS, smallH, true)
        val big = Bitmap.createScaledBitmap(small, w, h, false)
        Canvas(bmp).drawBitmap(big, r.left.toFloat(), r.top.toFloat(), null)
    }
}

package com.meterproof.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.meterproof.app.delivery.Semantic
import com.meterproof.app.delivery.SemanticProvider
import com.meterproof.app.privacy.Region
import java.util.concurrent.TimeUnit

/**
 * وصف دلالي لمنطقة من إطار بنموذج ML Kit المضمَّن (دون إنترنت): متجه درجات التصنيفات العامة
 * (ورقة، نص، باب، جدار، …) بحسب رقم التصنيف. يجب استدعاؤه من خيط خلفي، لا من الخيط الرئيسي.
 * القص يبقى في الذاكرة ويُرمى فورًا.
 */
object SemanticLabeler : SemanticProvider {

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.01f).build())
    }

    override fun vector(argb: IntArray, w: Int, h: Int, region: Region): FloatArray? {
        var bmp: Bitmap? = null
        return try {
            // هامش 10% من المحيط ليرى النموذج سياق الورقة، وحد أدنى 48 بكسلًا
            val mx = (region.right - region.left) * 0.1f
            val my = (region.bottom - region.top) * 0.1f
            var x0 = ((region.left - mx) * w).toInt().coerceIn(0, w - 1)
            var x1 = ((region.right + mx) * w).toInt().coerceIn(x0 + 1, w)
            var y0 = ((region.top - my) * h).toInt().coerceIn(0, h - 1)
            var y1 = ((region.bottom + my) * h).toInt().coerceIn(y0 + 1, h)
            if (x1 - x0 < 48) { x0 = (x0 - 24).coerceAtLeast(0); x1 = (x1 + 24).coerceAtMost(w) }
            if (y1 - y0 < 48) { y0 = (y0 - 24).coerceAtLeast(0); y1 = (y1 + 24).coerceAtMost(h) }
            val cw = x1 - x0
            val ch = y1 - y0
            if (cw < 8 || ch < 8) return null
            bmp = Bitmap.createBitmap(argb, y0 * w + x0, w, cw, ch, Bitmap.Config.ARGB_8888)
            val labels = Tasks.await(labeler.process(InputImage.fromBitmap(bmp, 0)), 8, TimeUnit.SECONDS)
            val v = FloatArray(Semantic.DIM)
            for (l in labels) if (l.index in 0 until Semantic.DIM) v[l.index] = l.confidence
            v
        } catch (e: Throwable) {
            null
        } finally {
            bmp?.recycle()
        }
    }
}

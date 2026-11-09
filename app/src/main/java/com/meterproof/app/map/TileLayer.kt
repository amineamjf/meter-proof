package com.meterproof.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** مصدر بلاطات للقراءة (المخزن المحلي). */
interface TileProvider {
    val minZoom: Int
    val maxZoom: Int
    val attribution: String
    fun tile(z: Int, x: Int, y: Int): Bitmap?
}

/**
 * يرسم البلاطات تحت المسار والدوائر. لا يصل إلى الشبكة أبدًا: ما ليس في المخزن يُترك
 * فارغًا (أو يُرسم من بلاطة أب أقل دقة إن وُجدت) دون أي طلب.
 */
class TileLayer(private val provider: TileProvider) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()

    /** يعيد عدد البلاطات المرسومة (0 = لا شيء من الخارطة متاح لهذه المنطقة). */
    fun draw(canvas: Canvas, cam: MapCamera): Int {
        val z = cam.tileZoom(provider.minZoom, provider.maxZoom)
        var drawn = 0
        canvas.save()
        canvas.clipRect(0f, 0f, cam.width, cam.height)
        for (s in cam.slots(z)) {
            dst.set(s.left, s.top, s.left + s.size, s.top + s.size)
            val bmp = provider.tile(s.z, s.x, s.y)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, dst, paint); drawn++
                continue
            }
            // بلاطة أب أقل دقة: نقتطع الربع/الثُمن المناسب ونكبّره
            var d = 1
            while (d <= 3 && s.z - d >= provider.minZoom) {
                val parent = provider.tile(s.z - d, s.x shr d, s.y shr d)
                if (parent != null) {
                    val cell = parent.width shr d
                    val ox = (s.x - ((s.x shr d) shl d)) * cell
                    val oy = (s.y - ((s.y shr d) shl d)) * cell
                    src.set(ox, oy, ox + cell, oy + cell)
                    canvas.drawBitmap(parent, src, dst, paint); drawn++
                    break
                }
                d++
            }
        }
        canvas.restore()
        return drawn
    }

    companion object {
        val ATTRIBUTION_BG: Int = Color.argb(200, 255, 255, 255)
    }
}

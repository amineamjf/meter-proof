package com.meterproof.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * خارطة مبسطة تعمل دون إنترنت: ترسم مسار GPS وأماكن الصور على لوحة بيضاء (بدون طبقة شوارع).
 * الضغط على دائرة صورة يستدعي onPhotoTap بترتيبها. للخارطة الحقيقية استخدم map.html.
 */
class TrackMapView(context: Context) : View(context) {

    var onPhotoTap: ((Int) -> Unit)? = null

    private var track: List<GeoPoint> = emptyList()
    private var photos: List<PhotoPoint> = emptyList()
    private var selected = -1

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bgPaint = Paint().apply { color = Color.parseColor("#F3F5F7") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        textSize = dp(12f)
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        strokeWidth = dp(2f)
    }

    // مواضع دوائر الصور على الشاشة (للضغط)
    private var photoX = FloatArray(0)
    private var photoY = FloatArray(0)

    fun setData(track: List<GeoPoint>, photos: List<PhotoPoint>) {
        this.track = track
        this.photos = photos
        invalidate()
    }

    fun setSelected(imageIndex: Int) {
        selected = imageIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val all = ArrayList<GeoPoint>(track.size + photos.size)
        all.addAll(track)
        for (p in photos) all.add(GeoPoint(p.lat, p.lon))
        if (all.isEmpty()) {
            infoPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("لا توجد نقاط موقع بعد", w / 2, h / 2, infoPaint)
            photoX = FloatArray(0)
            photoY = FloatArray(0)
            return
        }

        var minLat = all[0].lat
        var maxLat = all[0].lat
        var minLon = all[0].lon
        var maxLon = all[0].lon
        for (p in all) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        val midLat = (minLat + maxLat) / 2
        val lonFactor = cos(Math.toRadians(midLat))
        // مدى أدنى (~50 م) كي لا ينهار الرسم عند نقطة واحدة
        val minSpan = 0.0005
        val spanX = max((maxLon - minLon) * lonFactor, minSpan)
        val spanY = max(maxLat - minLat, minSpan)

        val pad = dp(28f)
        val availW = max(w - 2 * pad, 1f)
        val availH = max(h - 2 * pad, 1f)
        val scale = min(availW / spanX, availH / spanY).toFloat() // بكسل لكل درجة عرض
        val offX = (w - (spanX * scale).toFloat()) / 2
        val offY = (h - (spanY * scale).toFloat()) / 2

        fun px(p: GeoPoint): Float = offX + (((p.lon - minLon) * lonFactor) * scale).toFloat()
        fun py(p: GeoPoint): Float = offY + (((maxLat - p.lat)) * scale).toFloat()

        // المسار
        if (track.size >= 2) {
            val path = Path()
            path.moveTo(px(track[0]), py(track[0]))
            for (i in 1 until track.size) path.lineTo(px(track[i]), py(track[i]))
            canvas.drawPath(path, linePaint)
        }
        if (track.isNotEmpty()) {
            dotPaint.color = Color.parseColor("#2E7D32") // بداية
            canvas.drawCircle(px(track.first()), py(track.first()), dp(6f), dotPaint)
            if (track.size > 1) {
                dotPaint.color = Color.parseColor("#0D47A1") // آخر نقطة
                canvas.drawCircle(px(track.last()), py(track.last()), dp(6f), dotPaint)
            }
        }

        // الصور
        photoX = FloatArray(photos.size)
        photoY = FloatArray(photos.size)
        for ((i, p) in photos.withIndex()) {
            val gp = GeoPoint(p.lat, p.lon)
            val x = px(gp)
            val y = py(gp)
            photoX[i] = x
            photoY[i] = y
            val isSel = p.index == selected
            dotPaint.color = if (isSel) Color.parseColor("#B71C1C") else Color.parseColor("#EF5350")
            canvas.drawCircle(x, y, if (isSel) dp(13f) else dp(10f), dotPaint)
            if (isSel) {
                ringPaint.color = Color.BLACK
                canvas.drawCircle(x, y, dp(13f), ringPaint)
            }
            canvas.drawText((p.index + 1).toString(), x, y + dp(4f), textPaint)
        }

        // شريط المقياس: المتر لكل بكسل = 111320 / (بكسل لكل درجة)
        val metersPerPx = 111320.0 / scale
        val steps = doubleArrayOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0)
        var chosen = steps.last()
        for (s in steps) {
            if (s / metersPerPx >= dp(50f)) { chosen = s; break }
        }
        val barPx = (chosen / metersPerPx).toFloat().coerceAtMost(w * 0.5f)
        val bx = dp(12f)
        val by = h - dp(14f)
        canvas.drawLine(bx, by, bx + barPx, by, scalePaint)
        canvas.drawLine(bx, by - dp(4f), bx, by + dp(4f), scalePaint)
        canvas.drawLine(bx + barPx, by - dp(4f), bx + barPx, by + dp(4f), scalePaint)
        infoPaint.textAlign = Paint.Align.LEFT
        val label = if (chosen >= 1000) "${(chosen / 1000).toInt()} كم" else "${chosen.toInt()} م"
        canvas.drawText(label, bx, by - dp(8f), infoPaint)

        // الشمال
        infoPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("↑ شمال", w - dp(10f), dp(18f), infoPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return photos.isNotEmpty()
        if (event.action == MotionEvent.ACTION_UP) {
            var best = -1
            var bestDist = dp(26f)
            for (i in photos.indices) {
                if (i >= photoX.size) break
                val d = abs(photoX[i] - event.x) + abs(photoY[i] - event.y)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            if (best >= 0) {
                onPhotoTap?.invoke(photos[best].index)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}

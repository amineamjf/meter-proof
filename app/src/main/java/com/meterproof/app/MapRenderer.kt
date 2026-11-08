package com.meterproof.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * يرسم المسار وأماكن الصور والتوقفات على لوحة. يعمل دون إنترنت.
 * يُستعمل في الشاشة (TrackMapView) وفي تقرير PDF. [density] يضبط حجم الخطوط والدوائر.
 *
 * بلا [Projector] يلائم الرسم نفسه تلقائيًا ويملأ الخلفية (سلوك PDF والاحتياط).
 * ومع Projector (الخارطة بالبلاطات) يستعمل إسقاطه ولا يلمس الخلفية، فالبلاطات مرسومة تحته.
 */
class MapRenderer(private val density: Float) {

    /** إسقاط خارجي: يحوّل الإحداثيات إلى بكسل الشاشة ويعطي المتر لكل بكسل. */
    interface Projector {
        fun x(lon: Double): Float
        fun y(lat: Double): Float
        fun metersPerPixel(): Double
    }

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
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        style = Paint.Style.FILL
    }

    /** مواضع دوائر الصور على الشاشة بعد آخر رسم (للضغط). */
    var photoX = FloatArray(0)
        private set
    var photoY = FloatArray(0)
        private set

    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        track: List<GeoPoint>,
        photos: List<PhotoPoint>,
        stops: List<Stop>,
        selected: Int,
        projector: Projector? = null
    ) {
        if (projector == null) canvas.drawRect(0f, 0f, w, h, bgPaint)

        val validStops = stops.filter { !(it.lat == 0.0 && it.lon == 0.0) }

        val px: (Double) -> Float
        val py: (Double) -> Float
        val metersPerPx: Double

        if (projector != null) {
            px = { lon -> projector.x(lon) }
            py = { lat -> projector.y(lat) }
            metersPerPx = projector.metersPerPixel()
        } else {
            val all = ArrayList<GeoPoint>(track.size + photos.size + validStops.size)
            all.addAll(track)
            for (p in photos) all.add(GeoPoint(p.lat, p.lon))
            for (s in validStops) all.add(GeoPoint(s.lat, s.lon))
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

            px = { lon -> offX + (((lon - minLon) * lonFactor) * scale).toFloat() }
            py = { lat -> offY + ((maxLat - lat) * scale).toFloat() }
            // المتر لكل بكسل = 111320 / (بكسل لكل درجة)
            metersPerPx = 111320.0 / scale
        }

        // المسار
        if (track.size >= 2) {
            val path = Path()
            path.moveTo(px(track[0].lon), py(track[0].lat))
            for (i in 1 until track.size) path.lineTo(px(track[i].lon), py(track[i].lat))
            canvas.drawPath(path, linePaint)
        }
        if (track.isNotEmpty()) {
            dotPaint.color = Color.parseColor("#2E7D32") // بداية
            canvas.drawCircle(px(track.first().lon), py(track.first().lat), dp(6f), dotPaint)
            if (track.size > 1) {
                dotPaint.color = Color.parseColor("#0D47A1") // آخر نقطة
                canvas.drawCircle(px(track.last().lon), py(track.last().lat), dp(6f), dotPaint)
            }
        }

        // التوقفات: مربعات برتقالية
        dotPaint.color = Color.parseColor("#EF6C00")
        for (s in validStops) {
            val x = px(s.lon)
            val y = py(s.lat)
            canvas.drawRect(x - dp(7f), y - dp(7f), x + dp(7f), y + dp(7f), dotPaint)
        }

        // الصور: دوائر حمراء مرقّمة
        photoX = FloatArray(photos.size)
        photoY = FloatArray(photos.size)
        for ((i, p) in photos.withIndex()) {
            val x = px(p.lon)
            val y = py(p.lat)
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

        // شريط المقياس
        val steps = doubleArrayOf(
            5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0,
            5000.0, 10000.0, 20000.0, 50000.0
        )
        var chosen = steps.last()
        for (s in steps) {
            if (s / metersPerPx >= dp(50f)) { chosen = s; break }
        }
        val barPx = (chosen / metersPerPx).toFloat().coerceAtMost(w * 0.5f)
        val bx = dp(12f)
        val by = h - dp(14f)
        val label = if (chosen >= 1000) "${(chosen / 1000).toInt()} كم" else "${chosen.toInt()} م"
        infoPaint.textAlign = Paint.Align.LEFT
        if (projector != null) {
            // فوق البلاطات: خلفية فاتحة كي يُقرأ المقياس
            canvas.drawRect(
                bx - dp(4f), by - dp(24f), bx + max(barPx, infoPaint.measureText(label)) + dp(6f),
                by + dp(8f), haloPaint
            )
        }
        canvas.drawLine(bx, by, bx + barPx, by, scalePaint)
        canvas.drawLine(bx, by - dp(4f), bx, by + dp(4f), scalePaint)
        canvas.drawLine(bx + barPx, by - dp(4f), bx + barPx, by + dp(4f), scalePaint)
        canvas.drawText(label, bx, by - dp(8f), infoPaint)

        // الشمال
        infoPaint.textAlign = Paint.Align.RIGHT
        if (projector != null) {
            val tw = infoPaint.measureText("↑ شمال")
            canvas.drawRect(w - dp(10f) - tw - dp(4f), dp(4f), w - dp(6f), dp(24f), haloPaint)
        }
        canvas.drawText("↑ شمال", w - dp(10f), dp(18f), infoPaint)
    }
}

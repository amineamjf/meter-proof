package com.meterproof.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.meterproof.app.map.MapCamera
import com.meterproof.app.map.TileLayer
import com.meterproof.app.map.TileProvider

/**
 * يرسم المسار وأماكن الصور والتوقفات على لوحة، ويعمل دون إنترنت.
 * إن وُجد [tiles] (بلاطات منزَّلة مسبقًا) رُسمت الخارطة الحقيقية تحت كل شيء؛
 * وإلا بقي الرسم المبسط على خلفية سادة كما كان.
 * يُستعمل في الشاشة (TrackMapView) وفي تقرير PDF. [density] يضبط حجم الخطوط والدوائر.
 */
class MapRenderer(private val density: Float, var tiles: TileProvider? = null) {

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
        color = Color.argb(215, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(6.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TileLayer.ATTRIBUTION_BG }
    private var tileLayer: TileLayer? = null
    private var tileLayerFor: TileProvider? = null

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
        selected: Int
    ) {
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val validStops = stops.filter { !(it.lat == 0.0 && it.lon == 0.0) }

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

        val pad = dp(28f)
        val cam = MapCamera.fit(all.map { Pair(it.lat, it.lon) }, w, h, pad)!!
        fun px(lon: Double): Float = cam.x(lon)
        fun py(lat: Double): Float = cam.y(lat)

        // البلاطات تحت كل شيء
        var tilesDrawn = 0
        val provider = tiles
        if (provider != null) {
            if (tileLayerFor !== provider) { tileLayer = TileLayer(provider); tileLayerFor = provider }
            tilesDrawn = tileLayer!!.draw(canvas, cam)
        }
        val overTiles = tilesDrawn > 0

        // المسار
        if (track.size >= 2) {
            val path = Path()
            path.moveTo(px(track[0].lon), py(track[0].lat))
            for (i in 1 until track.size) path.lineTo(px(track[i].lon), py(track[i].lat))
            if (overTiles) canvas.drawPath(path, haloPaint)
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

        // شريط المقياس (متر حقيقي لكل بكسل عند مركز الخارطة)
        val metersPerPx = cam.metersPerPixel
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
        if (overTiles) {
            canvas.drawRoundRect(bx - dp(6f), by - dp(24f), bx + barPx + dp(12f), by + dp(8f), dp(6f), dp(6f), chipPaint)
        }
        canvas.drawLine(bx, by, bx + barPx, by, scalePaint)
        canvas.drawLine(bx, by - dp(4f), bx, by + dp(4f), scalePaint)
        canvas.drawLine(bx + barPx, by - dp(4f), bx + barPx, by + dp(4f), scalePaint)
        infoPaint.textAlign = Paint.Align.LEFT
        val label = if (chosen >= 1000) "${(chosen / 1000).toInt()} كم" else "${chosen.toInt()} م"
        canvas.drawText(label, bx, by - dp(8f), infoPaint)

        // الشمال
        infoPaint.textAlign = Paint.Align.RIGHT
        if (overTiles) canvas.drawRoundRect(w - dp(70f), dp(3f), w - dp(4f), dp(25f), dp(6f), dp(6f), chipPaint)
        canvas.drawText("↑ شمال", w - dp(10f), dp(18f), infoPaint)

        // نسب البيانات (مطلوبة قانونيًا مع بيانات OpenStreetMap)
        if (overTiles && provider != null) {
            val small = Paint(infoPaint).apply { textSize = dp(9f); textAlign = Paint.Align.RIGHT }
            val text = provider.attribution
            val tw = small.measureText(text)
            canvas.drawRect(w - tw - dp(10f), h - dp(14f), w, h, chipPaint)
            canvas.drawText(text, w - dp(5f), h - dp(4f), small)
        }
    }
}

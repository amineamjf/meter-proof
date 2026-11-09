package com.meterproof.app

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * خارطة تعمل دون إنترنت (بلاطات منزَّلة مسبقًا إن وُجدت، وإلا رسم مبسط): المسار (أزرق) وأماكن الصور (دوائر حمراء) والتوقفات (مربعات برتقالية).
 * الضغط على دائرة صورة يستدعي onPhotoTap بترتيبها. للخارطة الحقيقية استخدم map.html.
 */
class TrackMapView(context: Context) : View(context) {

    var onPhotoTap: ((Int) -> Unit)? = null

    private var track: List<GeoPoint> = emptyList()
    private var photos: List<PhotoPoint> = emptyList()
    private var stops: List<Stop> = emptyList()
    private var selected = -1

    private val density = resources.displayMetrics.density
    private val renderer = MapRenderer(density)

    fun setData(track: List<GeoPoint>, photos: List<PhotoPoint>, stops: List<Stop>) {
        this.track = track
        this.photos = photos
        this.stops = stops
        invalidate()
    }

    /** بلاطات الخارطة المنزَّلة (null = الرسم المبسط على خلفية سادة). */
    fun setTiles(p: com.meterproof.app.map.TileProvider?) {
        if (renderer.tiles !== p) {
            renderer.tiles = p
            invalidate()
        }
    }

    fun setSelected(imageIndex: Int) {
        selected = imageIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, width.toFloat(), height.toFloat(), track, photos, stops, selected)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return photos.isNotEmpty()
        if (event.action == MotionEvent.ACTION_UP) {
            var best = -1
            var bestDist = 26f * density
            val xs = renderer.photoX
            val ys = renderer.photoY
            for (i in photos.indices) {
                if (i >= xs.size) break
                val d = abs(xs[i] - event.x) + abs(ys[i] - event.y)
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

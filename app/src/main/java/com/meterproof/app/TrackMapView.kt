package com.meterproof.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * الخارطة داخل التطبيق: المسار (أزرق) وأماكن الصور (دوائر حمراء) والتوقفات (مربعات برتقالية).
 * إن استُورد ملف خارطة محلي (MapPack) رُسمت شوارعه تحت ذلك دون إنترنت، وإلا تبقى الخلفية فارغة.
 *
 * interactive = false: عرض ثابت يلائم نفسه مع النقاط ويترك التمرير للصفحة (الشاشة الرئيسية).
 * interactive = true: تحريك بإصبع وتكبير بإصبعين أو بنقرتين (شاشة الخارطة الكاملة).
 * الضغط على دائرة صورة يستدعي onPhotoTap بترتيبها.
 */
class TrackMapView(context: Context, private val interactive: Boolean = false) : View(context) {

    var onPhotoTap: ((Int) -> Unit)? = null

    /** الضغط على عداد من الجولة (دائرة ملوّنة بحالته). */
    var onMeterTap: ((MeterPoint) -> Unit)? = null

    private var track: List<GeoPoint> = emptyList()
    private var photos: List<PhotoPoint> = emptyList()
    private var stops: List<Stop> = emptyList()
    private var meters: List<MeterPoint> = emptyList()
    private var selected = -1

    private val density = resources.displayMetrics.density
    private val renderer = MapRenderer(density)

    /** حجم البلاطة المعروضة (256 نقطة مستقلة الكثافة، فيُقرأ النص على الشاشات الدقيقة). */
    private val tilePx = 256.0 * density

    // ---- الكاميرا (مركز وتكبير حقيقي)
    private var zoom = 15.0
    private var centerLat = 0.0
    private var centerLon = 0.0
    private var hasData = false
    private var autoFit = true
    private var needFit = true
    private var cxPx = 0.0
    private var cyPx = 0.0

    // ---- البلاطات
    @Volatile private var source: TileSource? = null
    private var layer: MapLayer = MapLayer.STREETS
    private var area: AreaLayer? = null
    private var onlinePool: ExecutorService? = null
    private var keyMissing = false

    // ---- موقعي والمسار والهدف (فوق الخارطة)
    private var myLat = Double.NaN
    private var myLon = Double.NaN
    private var myAcc = 0f
    private var routePts: List<GeoPoint>? = null
    private var targetPt: GeoPoint? = null
    private val myPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1257D6"); style = Paint.Style.STROKE; strokeWidth = 4.5f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routePath = Path()
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B91C1C"); textSize = 13f * density; textAlign = Paint.Align.CENTER
    }
    private var io: ExecutorService? = null
    private val cache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceIn(4 * 1024, 48 * 1024)
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pending = HashSet<Long>()
    private val missing = HashSet<Long>()
    private val decodeOpts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val attrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#37474F")
        textSize = 10f * density
        textAlign = Paint.Align.RIGHT
    }
    private val attrBg = Paint().apply { color = Color.parseColor("#CCFFFFFF") }

    private val projector = object : MapRenderer.Projector {
        override fun x(lon: Double): Float =
            (width / 2.0 + TileMath.lonToX(lon, zoom, tilePx) - cxPx).toFloat()

        override fun y(lat: Double): Float =
            (height / 2.0 + TileMath.latToY(lat, zoom, tilePx) - cyPx).toFloat()

        override fun metersPerPixel(): Double = TileMath.metersPerPixel(centerLat, zoom, tilePx)
    }

    // ---- الإيماءات (للوضع التفاعلي فقط)
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!hasData) return false
                autoFit = false
                zoomAround(
                    detector.focusX, detector.focusY,
                    zoom + ln(detector.scaleFactor.toDouble()) / ln(2.0)
                )
                return true
            }
        }
    ).apply { isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (!hasData) return false
                autoFit = false
                val cx = TileMath.lonToX(centerLon, zoom, tilePx) + distanceX
                val cy = TileMath.latToY(centerLat, zoom, tilePx) + distanceY
                centerLon = TileMath.xToLon(cx, zoom, tilePx).coerceIn(-180.0, 180.0)
                centerLat = TileMath.yToLat(cy, zoom, tilePx)
                    .coerceIn(-TileMath.MAX_LAT, TileMath.MAX_LAT)
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean = tapPhoto(e.x, e.y) || tapMeter(e.x, e.y)

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!hasData) return false
                autoFit = false
                zoomAround(e.x, e.y, zoom + 1.0)
                return true
            }
        }
    )

    fun setData(
        track: List<GeoPoint>,
        photos: List<PhotoPoint>,
        stops: List<Stop>,
        meters: List<MeterPoint> = emptyList()
    ) {
        this.track = track
        this.photos = photos
        this.stops = stops
        this.meters = meters
        if (autoFit) needFit = true
        invalidate()
    }

    fun setSelected(imageIndex: Int) {
        selected = imageIndex
        invalidate()
    }

    private fun areaLayer(): AreaLayer {
        var a = area
        if (a == null) {
            a = AreaLayer(AreaStore(context), density)
            area = a
        }
        return a
    }

    /** يعيد تحميل مصدر الخلفية بحسب الطبقة (بعد استيراد ملف أو حذفه أو تغيير الطبقة أو المفتاح). */
    fun reloadTiles() {
        val old = source
        source = null
        cache.evictAll()
        pending.clear()
        missing.clear()
        keyMissing = false
        if (old != null) executor()?.execute { old.close() }
        area?.refresh()
        invalidate()
        when (layer) {
            MapLayer.STREETS -> {
                if (!MapPack.exists(context)) return
                executor()?.execute {
                    val r = MbTiles.open(MapPack.file(context))
                    post {
                        if (r is MbTiles.OpenResult.Ok && layer == MapLayer.STREETS) {
                            source = MbTilesSource(r.tiles)
                            if (autoFit) needFit = true
                            invalidate()
                        } else if (r is MbTiles.OpenResult.Ok) {
                            r.tiles.close()
                        }
                    }
                }
            }
            else -> {
                val key = Settings(context).maptilerKey
                if (key.isEmpty()) {
                    keyMissing = true
                } else {
                    source = OnlineTileSource(context, layer, key)
                }
                invalidate()
            }
        }
    }

    fun getLayer(): MapLayer = layer

    /** يحدد الطبقة قبل إرفاق الواجهة (بلا إعادة تحميل؛ سيجري عند الإرفاق). */
    fun initLayer(l: MapLayer) {
        layer = l
    }

    fun setLayer(l: MapLayer) {
        layer = l
        reloadTiles()
    }

    /** هل توجد خارطة أزقة محمّلة (متجهة) تُرسم في طبقة الشوارع. */
    fun hasAreaData(): Boolean = areaLayer().hasData

    fun setMyLocation(lat: Double, lon: Double, acc: Float) {
        myLat = lat
        myLon = lon
        myAcc = acc
        invalidate()
    }

    fun hasMyLocation(): Boolean = !myLat.isNaN()

    fun setRoute(points: List<GeoPoint>?) {
        routePts = points
        invalidate()
    }

    fun setTarget(p: GeoPoint?) {
        targetPt = p
        invalidate()
    }

    /** يوسّط الخارطة على نقطة بتكبير معيّن (للبحث و«موقعي»). */
    fun focusOn(lat: Double, lon: Double, z: Double) {
        autoFit = false
        needFit = false
        hasData = true
        centerLat = lat.coerceIn(-TileMath.MAX_LAT, TileMath.MAX_LAT)
        centerLon = lon.coerceIn(-180.0, 180.0)
        zoom = z.coerceIn(minZoomAllowed(), maxZoomAllowed())
        invalidate()
    }

    /** يعيد الكاميرا لتلائم كل النقاط. */
    fun recenter() {
        autoFit = true
        needFit = true
        invalidate()
    }

    /** تكبير أو تصغير حول مركز الشاشة (لأزرار + و −). */
    fun zoomBy(delta: Double) {
        if (!hasData) return
        autoFit = false
        zoomAround(width / 2f, height / 2f, zoom + delta)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reloadTiles()
    }

    override fun onDetachedFromWindow() {
        val t = source
        source = null
        area?.close()
        area = null
        onlinePool?.shutdownNow()
        onlinePool = null
        cache.evictAll()
        pending.clear()
        missing.clear()
        val ex = io
        io = null
        if (ex != null) {
            if (t != null) {
                try {
                    ex.execute { t.close() }
                } catch (e: Exception) {
                    // لا شيء
                }
            }
            ex.shutdown()
        } else {
            t?.close()
        }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (autoFit) needFit = true
    }

    // ---------------------------------------------------------------- الكاميرا

    private fun vectorMode(): Boolean = layer == MapLayer.STREETS && areaLayer().hasData

    private fun minZoomAllowed(): Double =
        (source?.let { it.minZoom - 1.0 } ?: 2.0).coerceAtLeast(2.0)

    private fun maxZoomAllowed(): Double =
        if (vectorMode()) 20.0 else (source?.let { it.maxZoom + 2.0 } ?: 19.0).coerceAtMost(21.0)

    private fun fitToData() {
        val pts = ArrayList<GeoPoint>(track.size + photos.size + stops.size)
        pts.addAll(track)
        for (p in photos) pts.add(GeoPoint(p.lat, p.lon))
        for (s in stops) if (!(s.lat == 0.0 && s.lon == 0.0)) pts.add(GeoPoint(s.lat, s.lon))
        // لا مسار ولا صور ولا توقفات بعد: نعرض الجولة نفسها
        if (pts.isEmpty()) for (m in meters) pts.add(GeoPoint(m.lat, m.lon))
        if (pts.isEmpty()) {
            hasData = false
            return
        }
        var minLat = pts[0].lat
        var maxLat = pts[0].lat
        var minLon = pts[0].lon
        var maxLon = pts[0].lon
        for (p in pts) {
            minLat = min(minLat, p.lat); maxLat = kotlin.math.max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = kotlin.math.max(maxLon, p.lon)
        }
        centerLon = (minLon + maxLon) / 2
        // المنتصف في إسقاط ميركاتور (لا متوسط العرض) كي تتوسط النقاط الشاشة
        val midY = (TileMath.latToY(minLat, 0.0, tilePx) + TileMath.latToY(maxLat, 0.0, tilePx)) / 2
        centerLat = TileMath.yToLat(midY, 0.0, tilePx)
        val cap = min(maxZoomAllowed(), 18.0)
        zoom = TileMath.fitZoom(
            minLat, maxLat, minLon, maxLon, width.toDouble(), height.toDouble(),
            tilePx, 28.0 * density, cap
        ).coerceIn(minZoomAllowed(), cap)
        hasData = true
    }

    /** يغيّر التكبير مع إبقاء النقطة تحت الإصبع في مكانها. */
    private fun zoomAround(fx: Float, fy: Float, newZoom: Double) {
        val nz = newZoom.coerceIn(minZoomAllowed(), maxZoomAllowed())
        val dx = fx - width / 2.0
        val dy = fy - height / 2.0
        val cx = TileMath.lonToX(centerLon, zoom, tilePx)
        val cy = TileMath.latToY(centerLat, zoom, tilePx)
        val fLon = TileMath.xToLon(cx + dx, zoom, tilePx)
        val fLat = TileMath.yToLat(cy + dy, zoom, tilePx)
        zoom = nz
        val nx = TileMath.lonToX(fLon, zoom, tilePx)
        val ny = TileMath.latToY(fLat, zoom, tilePx)
        centerLon = TileMath.xToLon(nx - dx, zoom, tilePx).coerceIn(-180.0, 180.0)
        centerLat = TileMath.yToLat(ny - dy, zoom, tilePx)
            .coerceIn(-TileMath.MAX_LAT, TileMath.MAX_LAT)
        invalidate()
    }

    // ---------------------------------------------------------------- البلاطات

    private fun executor(): ExecutorService? {
        var ex = io
        if (ex == null) {
            ex = Executors.newSingleThreadExecutor()
            io = ex
        }
        return ex
    }

    private fun key(z: Int, x: Int, y: Int): Long =
        (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()

    private fun onlineExecutor(): ExecutorService {
        var ex = onlinePool
        if (ex == null) {
            ex = Executors.newFixedThreadPool(4)
            onlinePool = ex
        }
        return ex!!
    }

    private fun request(mb: TileSource, z: Int, x: Int, y: Int, key: Long) {
        if (key in missing || !pending.add(key)) return
        try {
            val pool = if (mb is OnlineTileSource) onlineExecutor() else executor()
            pool?.execute {
                val bytes = mb.tile(z, x, y)
                val bmp = if (bytes != null) {
                    try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    } catch (e: Throwable) {
                        null
                    }
                } else {
                    null
                }
                post {
                    pending.remove(key)
                    if (source === mb) {
                        if (bmp != null) {
                            cache.put(key, bmp)
                            invalidate()
                        } else {
                            missing.add(key)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            pending.remove(key)
        }
    }

    private fun drawTiles(canvas: Canvas, w: Float, h: Float) {
        val mb = source ?: return
        val z = floor(zoom + 0.5).toInt().coerceIn(mb.minZoom, mb.maxZoom)
        val dst = tilePx * 2.0.pow(zoom - z)
        val xr = TileMath.tileRange(cxPx, w.toDouble(), dst, z)
        val yr = TileMath.tileRange(cyPx, h.toDouble(), dst, z)
        val size = dst.toFloat() + 0.75f // تداخل طفيف يمنع الخطوط الرفيعة بين البلاطات
        for (ty in yr) {
            for (tx in xr) {
                val k = key(z, tx, ty)
                val bmp = cache.get(k)
                if (bmp != null) {
                    val left = (w / 2.0 + tx * dst - cxPx).toFloat()
                    val top = (h / 2.0 + ty * dst - cyPx).toFloat()
                    rect.set(left, top, left + size, top + size)
                    canvas.drawBitmap(bmp, null, rect, tilePaint)
                } else {
                    request(mb, z, tx, ty, k)
                }
            }
        }
    }

    /** رسالة فوق الخارطة حين تنقص طبقة القمر الصناعي أو التضاريس شيئًا (مفتاح أو خطأ). */
    private fun drawLayerMessage(canvas: Canvas, w: Float) {
        val msg = when {
            keyMissing -> "أدخل مفتاح MapTiler من إعدادات التطبيق لعرض ${layer.label}"
            (source as? OnlineTileSource)?.lastError != null -> (source as OnlineTileSource).lastError
            else -> null
        } ?: return
        canvas.drawText(msg, w / 2, 96f * density, msgPaint)
    }

    private fun drawRouteAndMe(canvas: Canvas, w: Float, h: Float) {
        val rp = routePts
        if (rp != null && rp.size >= 2) {
            routePath.reset()
            for (i in rp.indices) {
                val px = projector.x(rp[i].lon)
                val py = projector.y(rp[i].lat)
                if (i == 0) routePath.moveTo(px, py) else routePath.lineTo(px, py)
            }
            canvas.drawPath(routePath, routeCasing)
            canvas.drawPath(routePath, routeLine)
        }
        val t = targetPt
        if (t != null) {
            val tx = projector.x(t.lon)
            val ty = projector.y(t.lat)
            myPaint.style = Paint.Style.STROKE
            myPaint.strokeWidth = 3f * density
            myPaint.color = Color.parseColor("#E11D48")
            canvas.drawCircle(tx, ty, 16f * density, myPaint)
            myPaint.style = Paint.Style.FILL
        }
        if (!myLat.isNaN()) {
            val mx = projector.x(myLon)
            val my = projector.y(myLat)
            val mpp = projector.metersPerPixel()
            val r = if (mpp > 0) (myAcc / mpp).toFloat() else 0f
            myPaint.style = Paint.Style.FILL
            myPaint.color = Color.parseColor("#2E2B7DE9")
            canvas.drawCircle(mx, my, maxOf(r, 12f * density), myPaint)
            myPaint.color = Color.WHITE
            canvas.drawCircle(mx, my, 9f * density, myPaint)
            myPaint.color = Color.parseColor("#2B7DE9")
            canvas.drawCircle(mx, my, 6.5f * density, myPaint)
        }
    }

    private fun drawAttribution(canvas: Canvas, w: Float, h: Float) {
        val text = when {
            vectorMode() -> AreaLayer.ATTRIBUTION
            else -> source?.attribution ?: return
        }
        if (text.isEmpty()) return
        val tw = attrPaint.measureText(text)
        val right = w - 6f * density
        val bottom = h - 4f * density
        canvas.drawRect(
            right - tw - 4f * density, bottom - 14f * density, right + 3f * density, bottom, attrBg
        )
        canvas.drawText(text, right, bottom - 3f * density, attrPaint)
    }

    // ---------------------------------------------------------------- الرسم واللمس

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (needFit && width > 0 && height > 0) {
            fitToData()
            needFit = false
        }
        if (!hasData) {
            renderer.draw(canvas, w, h, track, photos, stops, selected, null, meters)
            return
        }
        cxPx = TileMath.lonToX(centerLon, zoom, tilePx)
        cyPx = TileMath.latToY(centerLat, zoom, tilePx)
        canvas.drawColor(Color.parseColor("#F3F5F7"))
        if (vectorMode()) {
            areaLayer().draw(
                canvas, w, h, cxPx, cyPx, zoom, tilePx,
                { r -> post(r) }, { invalidate() }
            )
        } else {
            drawTiles(canvas, w, h)
            drawLayerMessage(canvas, w)
        }
        renderer.draw(canvas, w, h, track, photos, stops, selected, projector, meters)
        drawRouteAndMe(canvas, w, h)
        drawAttribution(canvas, w, h)
    }

    /** يعيد ترتيب الصورة الأقرب للنقطة إن كانت ضمن مدى اللمس، وإلا -1. */
    private fun nearestPhoto(x: Float, y: Float): Int {
        var best = -1
        var bestDist = 26f * density
        val xs = renderer.photoX
        val ys = renderer.photoY
        for (i in photos.indices) {
            if (i >= xs.size) break
            val d = abs(xs[i] - x) + abs(ys[i] - y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /** أقرب عداد من الجولة ضمن مدى اللمس، وإلا -1. */
    private fun nearestMeter(x: Float, y: Float): Int {
        var best = -1
        var bestDist = 24f * density
        val xs = renderer.meterX
        val ys = renderer.meterY
        for (i in meters.indices) {
            if (i >= xs.size) break
            val d = abs(xs[i] - x) + abs(ys[i] - y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private fun tapMeter(x: Float, y: Float): Boolean {
        val best = nearestMeter(x, y)
        if (best < 0) return false
        onMeterTap?.invoke(meters[best])
        performClick()
        return true
    }

    private fun tapPhoto(x: Float, y: Float): Boolean {
        val best = nearestPhoto(x, y)
        if (best < 0) return false
        onPhotoTap?.invoke(photos[best].index)
        performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) {
            // عرض ثابت: الضغط على دائرة فقط، والتمرير يبقى للصفحة
            if (event.action == MotionEvent.ACTION_DOWN) return photos.isNotEmpty() || meters.isNotEmpty()
            if (event.action == MotionEvent.ACTION_UP &&
                (tapPhoto(event.x, event.y) || tapMeter(event.x, event.y))
            ) return true
            return super.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

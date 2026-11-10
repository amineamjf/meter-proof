package com.meterproof.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
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
 * تحت ذلك بلاطات الخارطة (مخطط أو قمر صناعي): تُقرأ من مخزن الجهاز دون إنترنت، وما ليس فيه
 * يُجلب ويُخزَّن إن وُجد اتصال (نفس آلية خارطة smart-remplacement). دون اتصال وبلا مخزون تبقى الخلفية فارغة.
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
    /** قالب رابط الطبقة الحالية، ورقم يتغير مع كل تبديل طبقة كي لا تُرسم نتائج طبقة قديمة. */
    @Volatile private var tileTemplate: String = TileLayers.PLAN
    @Volatile private var layerGen = 0
    private var io: ExecutorService? = null
    private val cache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceIn(4 * 1024, 48 * 1024)
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pending = HashSet<Long>()

    /** بلاطات فشل جلبها (دون اتصال أو خطأ): لا نعيد المحاولة قبل مرور RETRY_MS كي لا نطرق الشبكة باستمرار. */
    private val failedAt = HashMap<Long, Long>()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val srcRect = Rect()
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

    /** يعيد قراءة الطبقة المختارة ويفرغ الذاكرة (بعد تبديل الطبقة أو الرابط أو حذف المخزن). */
    fun refreshTiles() {
        val st = Settings(context)
        tileTemplate = TileLayers.resolve(st.tileUrl, st.tileSat)
        layerGen++
        cache.evictAll()
        pending.clear()
        failedAt.clear()
        invalidate()
    }

    /** يبدّل بين المخطط والقمر الصناعي. يعيد false إن كان رابط مخصص مضبوطًا (له الأولوية). */
    fun toggleSatellite(): Boolean {
        val st = Settings(context)
        if (st.tileUrl.isNotBlank()) return false
        st.tileSat = !st.tileSat
        refreshTiles()
        return true
    }

    /** المنطقة الظاهرة الآن (لتحميلها)، أو null قبل توفر بيانات. */
    fun visibleBox(): GeoBox? {
        if (!hasData || width == 0 || height == 0) return null
        val cx = TileMath.lonToX(centerLon, zoom, tilePx)
        val cy = TileMath.latToY(centerLat, zoom, tilePx)
        val west = TileMath.xToLon(cx - width / 2.0, zoom, tilePx).coerceIn(-180.0, 180.0)
        val east = TileMath.xToLon(cx + width / 2.0, zoom, tilePx).coerceIn(-180.0, 180.0)
        val north = TileMath.yToLat(cy - height / 2.0, zoom, tilePx).coerceIn(-TileMath.MAX_LAT, TileMath.MAX_LAT)
        val south = TileMath.yToLat(cy + height / 2.0, zoom, tilePx).coerceIn(-TileMath.MAX_LAT, TileMath.MAX_LAT)
        return GeoBox(south, north, west, east)
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
        refreshTiles()
    }

    override fun onDetachedFromWindow() {
        layerGen++
        cache.evictAll()
        pending.clear()
        failedAt.clear()
        io?.shutdown()
        io = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (autoFit) needFit = true
    }

    // ---------------------------------------------------------------- الكاميرا

    private fun minZoomAllowed(): Double = MIN_Z.toDouble()

    private fun maxZoomAllowed(): Double = MAX_Z.toDouble()

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
            ex = Executors.newFixedThreadPool(3)
            io = ex
        }
        return ex
    }

    private fun key(z: Int, x: Int, y: Int): Long =
        (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()

    /**
     * يجلب بلاطة في الخلفية: من مخزن الجهاز أولًا، وإن لم توجد ويوجد اتصال تُجلب وتُخزَّن.
     * لا يُطلب شيء من الشبكة إن كانت البلاطة مخزّنة.
     */
    private fun request(z: Int, x: Int, y: Int, key: Long) {
        val failed = failedAt[key]
        if (failed != null && SystemClock.uptimeMillis() - failed < RETRY_MS) return
        if (!pending.add(key)) return
        val gen = layerGen
        val template = tileTemplate
        val app = context.applicationContext
        try {
            executor()?.execute {
                val k = TileLayers.key(template, z, x, y)
                var bytes: ByteArray? = try {
                    TileCache.get(app).get(k)
                } catch (e: Exception) {
                    null
                }
                if (bytes == null && Net.online(app)) {
                    val r = HttpTileFetcher().fetch(TileLayers.build(template, z, x, y))
                    val b = r.bytes
                    if (r.status == 200 && b != null && ImageSniffer.isImage(b)) {
                        bytes = b
                        try {
                            TileCache.get(app).put(k, b)
                        } catch (e: Exception) {
                            // العرض يعمل حتى لو تعذّر الحفظ
                        }
                    }
                }
                val data = bytes
                val bmp = if (data != null) {
                    try {
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    } catch (e: Throwable) {
                        null
                    }
                } else {
                    null
                }
                post {
                    pending.remove(key)
                    if (gen == layerGen) {
                        if (bmp != null) {
                            cache.put(key, bmp)
                            invalidate()
                        } else {
                            failedAt[key] = SystemClock.uptimeMillis()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            pending.remove(key)
        }
    }

    private fun drawTiles(canvas: Canvas, w: Float, h: Float) {
        val z = floor(zoom + 0.5).toInt().coerceIn(MIN_Z, MAX_Z)
        val dst = tilePx * 2.0.pow(zoom - z)
        val xr = TileMath.tileRange(cxPx, w.toDouble(), dst, z)
        val yr = TileMath.tileRange(cyPx, h.toDouble(), dst, z)
        val size = dst.toFloat() + 0.75f // تداخل طفيف يمنع الخطوط الرفيعة بين البلاطات
        for (ty in yr) {
            for (tx in xr) {
                val k = key(z, tx, ty)
                val left = (w / 2.0 + tx * dst - cxPx).toFloat()
                val top = (h / 2.0 + ty * dst - cyPx).toFloat()
                rect.set(left, top, left + size, top + size)
                val bmp = cache.get(k)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, rect, tilePaint)
                } else {
                    // أثناء التحميل: ربع بلاطة الأب (الأقل دقة) إن كانت جاهزة بدل فراغ
                    if (z > MIN_Z) {
                        val parent = cache.get(key(z - 1, tx shr 1, ty shr 1))
                        if (parent != null) {
                            val half = parent.width / 2
                            val sx = (tx and 1) * half
                            val sy = (ty and 1) * half
                            srcRect.set(sx, sy, sx + half, sy + half)
                            canvas.drawBitmap(parent, srcRect, rect, tilePaint)
                        }
                    }
                    request(z, tx, ty, k)
                }
            }
        }
    }

    private fun drawAttribution(canvas: Canvas, w: Float, h: Float) {
        val text = TileLayers.attribution(tileTemplate)
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
        drawTiles(canvas, w, h)
        renderer.draw(canvas, w, h, track, photos, stops, selected, projector, meters)
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

    private companion object {
        const val MIN_Z = 10
        const val MAX_Z = 19
        const val RETRY_MS = 20_000L
    }
}

package com.meterproof.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * يرسم خارطة المنطقة المحمّلة (شوارع ومبانٍ ومياه) متجهيًا بـ Canvas، دون إنترنت وبأي تكبير حتى 20.
 * القطع تُقرأ على خيط خلفي وتُحفظ في ذاكرة محدودة؛ الرسم والقراءة من ذاكرة القطع على خيط الواجهة فقط.
 */
class AreaLayer(private val store: AreaStore, private val density: Float) {

    companion object {
        private const val MAX_CACHED = 24
        private const val MIN_DRAW_ZOOM = 11.5
        const val ATTRIBUTION = "© OpenStreetMap contributors"
    }

    private class RenderWay(
        val kind: Int,
        val x: DoubleArray,
        val y: DoubleArray,
        val minX: Double, val minY: Double, val maxX: Double, val maxY: Double
    )

    private class LoadedCell(val ways: List<RenderWay>)

    private val cache = object : LinkedHashMap<String, LoadedCell>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LoadedCell>?): Boolean =
            size > MAX_CACHED
    }
    private val loading = HashSet<String>()
    private var available: Set<String> = emptySet()
    private var io: ExecutorService? = null

    /** هل توجد قطع محمّلة (يُحدَّث عند refresh فقط، فلا نفحص القرص في كل إطار). */
    var hasData: Boolean = false
        private set

    init {
        refresh()
    }

    /** يعيد قراءة قائمة القطع المتاحة ويفرّغ الذاكرة (بعد تحميل أو حذف). */
    fun refresh() {
        cache.clear()
        loading.clear()
        val ids = HashSet<String>()
        store.cellIds().forEach { ids.add(it) }
        available = ids
        hasData = ids.isNotEmpty()
    }

    fun close() {
        io?.shutdownNow()
        io = null
        cache.clear()
        loading.clear()
    }

    // ---------------------------------------------------------------- الأقلام

    private fun dp(v: Float) = v * density

    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val waterFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBD7EE"); style = Paint.Style.FILL
    }
    private val bldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E9E4DA"); style = Paint.Style.FILL
    }
    private val bldLine = stroke(Color.parseColor("#CDC5B6"), dp(0.8f))
    private val waterLine = stroke(Color.parseColor("#9CC6E8"), dp(2f))
    private val pathLine = stroke(Color.parseColor("#A8896B"), dp(1.3f)).apply {
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    }
    private val casing = stroke(Color.WHITE, 1f)
    private val body = stroke(Color.WHITE, 1f)
    private val path = Path()

    // ---------------------------------------------------------------- التحميل

    private fun executor(): ExecutorService {
        var ex = io
        if (ex == null) {
            ex = Executors.newSingleThreadExecutor()
            io = ex
        }
        return ex!!
    }

    private fun load(id: String, cell: AreaCell, post: (Runnable) -> Unit, invalidate: () -> Unit) {
        if (!loading.add(id)) return
        try {
            executor().execute {
                val ways = store.read(cell)
                val loaded = if (ways == null) null else LoadedCell(toRender(ways))
                post(Runnable {
                    loading.remove(id)
                    if (loaded != null) {
                        cache[id] = loaded
                        invalidate()
                    } else {
                        // قطعة تالفة حُذفت: لا نعيد المحاولة في كل إطار
                        available = available - id
                        hasData = available.isNotEmpty()
                    }
                })
            }
        } catch (e: Exception) {
            loading.remove(id)
        }
    }

    private fun toRender(ways: List<OsmWay>): List<RenderWay> {
        val out = ArrayList<RenderWay>(ways.size)
        for (w in ways) {
            val n = w.size
            if (n < 2) continue
            val xs = DoubleArray(n)
            val ys = DoubleArray(n)
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (i in 0 until n) {
                val x = TileMath.lonToX(w.lon[i] * 1e-6, 0.0, 1.0)
                val y = TileMath.latToY(w.lat[i] * 1e-6, 0.0, 1.0)
                xs[i] = x
                ys[i] = y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            out.add(RenderWay(w.kind, xs, ys, minX, minY, maxX, maxY))
        }
        return out
    }

    // ---------------------------------------------------------------- الرسم

    /**
     * يرسم الطبقة. [cxPx] و[cyPx] مركز الشاشة في إحداثيات العالم بحجم بلاطة [tilePx] عند [zoom].
     * [post] ينقل تنفيذًا إلى خيط الواجهة، و[invalidate] يطلب إعادة الرسم بعد وصول قطعة.
     */
    fun draw(
        canvas: Canvas, w: Float, h: Float, cxPx: Double, cyPx: Double, zoom: Double, tilePx: Double,
        post: (Runnable) -> Unit, invalidate: () -> Unit
    ) {
        if (!hasData || zoom < MIN_DRAW_ZOOM) return
        val world = tilePx * 2.0.pow(zoom)
        val vx0 = (cxPx - w / 2) / world
        val vx1 = (cxPx + w / 2) / world
        val vy0 = (cyPx - h / 2) / world
        val vy1 = (cyPx + h / 2) / world

        val latTop = TileMath.yToLat(vy0 * 1.0, 0.0, 1.0)
        val latBottom = TileMath.yToLat(vy1 * 1.0, 0.0, 1.0)
        val lonLeft = TileMath.xToLon(vx0, 0.0, 1.0)
        val lonRight = TileMath.xToLon(vx1, 0.0, 1.0)
        val r0 = Math.floor(minOf(latTop, latBottom) / AreaPlan.CELL_DEG).toInt()
        val r1 = Math.floor(maxOf(latTop, latBottom) / AreaPlan.CELL_DEG).toInt()
        val c0 = Math.floor(minOf(lonLeft, lonRight) / AreaPlan.CELL_DEG).toInt()
        val c1 = Math.floor(maxOf(lonLeft, lonRight) / AreaPlan.CELL_DEG).toInt()
        if ((r1 - r0 + 1).toLong() * (c1 - c0 + 1) > 60) return // بعيد جدًا: لا نرسم ولا نحمّل

        val visible = ArrayList<LoadedCell>()
        for (r in r0..r1) for (c in c0..c1) {
            val cell = AreaCell(r, c)
            val id = cell.id
            if (id !in available) continue
            val lc = cache[id]
            if (lc != null) visible.add(lc) else load(id, cell, post, invalidate)
        }
        if (visible.isEmpty()) return

        val ox = cxPx - w / 2
        val oy = cyPx - h / 2
        val scale = 2.0.pow((zoom - 16) * 0.7).coerceIn(0.45, 3.0).toFloat()

        fun fillKind(kind: Int, paint: Paint?, line: Paint?) {
            path.reset()
            var any = false
            for (lc in visible) for (way in lc.ways) {
                if (way.kind != kind) continue
                if (way.maxX < vx0 || way.minX > vx1 || way.maxY < vy0 || way.minY > vy1) continue
                for (i in way.x.indices) {
                    val px = (way.x[i] * world - ox).toFloat()
                    val py = (way.y[i] * world - oy).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                any = true
            }
            if (!any) return
            if (paint != null) canvas.drawPath(path, paint)
            if (line != null) canvas.drawPath(path, line)
        }

        fun road(kind: Int, casingColor: Int, bodyColor: Int, widthDp: Float) {
            path.reset()
            var any = false
            for (lc in visible) for (way in lc.ways) {
                if (way.kind != kind) continue
                if (way.maxX < vx0 || way.minX > vx1 || way.maxY < vy0 || way.minY > vy1) continue
                for (i in way.x.indices) {
                    val px = (way.x[i] * world - ox).toFloat()
                    val py = (way.y[i] * world - oy).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                any = true
            }
            if (!any) return
            val wd = dp(widthDp) * scale
            casing.color = casingColor
            casing.strokeWidth = wd + dp(1.6f)
            canvas.drawPath(path, casing)
            body.color = bodyColor
            body.strokeWidth = wd
            canvas.drawPath(path, body)
        }

        fillKind(WayKind.WATER_AREA, waterFill, null)
        if (zoom >= 15.0) fillKind(WayKind.BUILDING, bldFill, bldLine)
        waterLine.strokeWidth = dp(2f) * scale
        fillKind(WayKind.WATER_LINE, null, waterLine)
        if (zoom >= 15.5) {
            pathLine.strokeWidth = dp(1.3f) * scale.coerceAtLeast(0.8f)
            fillKind(WayKind.PATH, null, pathLine)
        }
        if (zoom >= 14.5) road(WayKind.SERVICE, Color.parseColor("#CBD0D6"), Color.WHITE, 2.4f)
        if (zoom >= 13.0) road(WayKind.MINOR, Color.parseColor("#BFC6CD"), Color.WHITE, 3.6f)
        road(WayKind.MAJOR, Color.parseColor("#D4A24C"), Color.parseColor("#FFE08A"), 5f)
        road(WayKind.MOTORWAY, Color.parseColor("#C8794A"), Color.parseColor("#FFB27A"), 6f)
    }
}

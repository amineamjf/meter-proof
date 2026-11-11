package com.meterproof.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.pow
import kotlin.math.roundToInt

/** مصدر بلاطات لتقرير PDF: يقرأ فقط، ولا يلمس الشبكة أبدًا. */
fun interface ReportTileSource {
    fun get(z: Int, x: Int, y: Int): ByteArray?
}

/** قالب الطبقة المستعملة في التقرير ومصدر بلاطاتها. */
class ReportMapSource(val template: String, val tiles: ReportTileSource)

/** يقرأ من مخزن البلاطات على الجهاز (نفس مفاتيح الخارطة الحية). */
class CacheTileSource(context: Context, private val template: String) : ReportTileSource {
    private val cache = TileCache.get(context)

    override fun get(z: Int, x: Int, y: Int): ByteArray? = try {
        cache.get(TileLayers.key(template, z, x, y))
    } catch (e: Exception) {
        null
    }
}

/**
 * يرسم خلفية خارطة التقرير من البلاطات المخزنة، ثم يضع النسب.
 *
 * تُركَّب البلاطات الظاهرة في صورة واحدة بحجم اللوحة مضروبًا في [SCALE] (دقة مضاعفة، D3) ثم تُرسم على
 * لوحة PDF صورةً واحدة: لا تُضمَّن بلاطات كاملة خارج الإطار فيصغر الملف. أما المسار والدوائر والأرقام فتبقى
 * متجهية (MapRenderer) فتبقى حادة عند التكبير في عارض PDF.
 *
 * بلاطة ناقصة تُعوَّض من أقرب بلاطة أقل دقة مخزنة (حتى [MAX_PARENT_STEPS] درجات)، وإلا تبقى بقعة رمادية.
 * استدعِ [release] بعد إغلاق المستند لتحرير الصور المركّبة.
 */
class ReportMapRenderer(private val source: ReportMapSource) {

    class Outcome(val needed: Int, val exact: Int, val fallback: Int) {
        val drawn: Int get() = exact + fallback
        /** هل تغطي البلاطات كل اللوحة بدقتها الكاملة؟ */
        val complete: Boolean get() = needed > 0 && exact == needed
    }

    private val composed = ArrayList<Bitmap>()
    private val attrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        textSize = 8f
        textAlign = Paint.Align.RIGHT
    }
    private val haloPaint = Paint().apply { color = Color.parseColor("#D9FFFFFF") }

    fun projector(v: ReportMapView): MapRenderer.Projector = object : MapRenderer.Projector {
        private val cx = TileMath.lonToX(v.centerLon, v.zoom, ReportMapPlanner.TILE_PTS)
        private val cy = TileMath.latToY(v.centerLat, v.zoom, ReportMapPlanner.TILE_PTS)

        override fun x(lon: Double): Float =
            (v.w / 2 + TileMath.lonToX(lon, v.zoom, ReportMapPlanner.TILE_PTS) - cx).toFloat()

        override fun y(lat: Double): Float =
            (v.h / 2 + TileMath.latToY(lat, v.zoom, ReportMapPlanner.TILE_PTS) - cy).toFloat()

        override fun metersPerPixel(): Double = ReportMapPlanner.metersPerPt(v)
    }

    /** يرسم الخلفية على [canvas] (الأصل في أعلى يسار اللوحة، وحدتها نقاط PDF). */
    fun drawBackground(canvas: Canvas, v: ReportMapView): Outcome {
        val tiles = ReportMapPlanner.tilesFor(v)
        val bw = (v.w * SCALE).roundToInt().coerceAtLeast(1)
        val bh = (v.h * SCALE).roundToInt().coerceAtLeast(1)
        val bg = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            return Outcome(tiles.size, 0, 0) // ذاكرة غير كافية: بلا خلفية بدل إفشال التقرير
        }
        composed.add(bg)
        val bc = Canvas(bg)
        bc.drawColor(Color.parseColor("#D5D9DE"))
        bc.scale(SCALE, SCALE)

        val cx = TileMath.lonToX(v.centerLon, v.zoom, ReportMapPlanner.TILE_PTS)
        val cy = TileMath.latToY(v.centerLat, v.zoom, ReportMapPlanner.TILE_PTS)
        val dst = ReportMapPlanner.TILE_PTS * 2.0.pow(v.zoom - v.tileLevel)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val dstRect = RectF()
        val srcRect = Rect()
        var exact = 0
        var fallback = 0

        for (t in tiles) {
            val left = (v.w / 2 + t.x * dst - cx).toFloat()
            val top = (v.h / 2 + t.y * dst - cy).toFloat()
            dstRect.set(left, top, left + dst.toFloat(), top + dst.toFloat())

            val own = decode(source.tiles.get(t.z, t.x, t.y))
            if (own != null) {
                bc.drawBitmap(own, null, dstRect, paint)
                own.recycle()
                exact++
                continue
            }
            for (k in 1..MAX_PARENT_STEPS) {
                val pz = t.z - k
                if (pz < 0) break
                val parent = decode(source.tiles.get(pz, t.x shr k, t.y shr k)) ?: continue
                val sub = parent.width / (1 shl k).toFloat()
                val sx = (t.x - ((t.x shr k) shl k)) * sub
                val sy = (t.y - ((t.y shr k) shl k)) * sub
                srcRect.set(sx.toInt(), sy.toInt(), (sx + sub).toInt().coerceAtLeast(sx.toInt() + 1), (sy + sub).toInt().coerceAtLeast(sy.toInt() + 1))
                bc.drawBitmap(parent, srcRect, dstRect, paint)
                parent.recycle()
                fallback++
                break
            }
        }

        canvas.drawBitmap(bg, null, RectF(0f, 0f, v.w.toFloat(), v.h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        return Outcome(tiles.size, exact, fallback)
    }

    /** نسب البيانات على الصورة نفسها (أسفل اليمين)، فلا تنفصل عنها إن نُسخت وحدها. */
    fun drawAttribution(canvas: Canvas, v: ReportMapView) {
        val text = TileLayers.reportAttribution(source.template)
        val tw = attrPaint.measureText(text)
        val w = v.w.toFloat()
        val h = v.h.toFloat()
        canvas.drawRect(w - tw - 10f, h - 15f, w - 2f, h - 2f, haloPaint)
        canvas.drawText(text, w - 6f, h - 5f, attrPaint)
    }

    /** يحرر الصور المركّبة. يُستدعى بعد إغلاق المستند (قد تظل مرجعًا عليها حتى ذلك الحين). */
    fun release() {
        for (b in composed) if (!b.isRecycled) b.recycle()
        composed.clear()
    }

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Throwable) {
            null
        }
    }

    companion object {
        /** دقة الصورة المركّبة بالنسبة لنقاط PDF (2 = ضعف الدقة). */
        const val SCALE = 2f
        const val MAX_PARENT_STEPS = 3
    }
}

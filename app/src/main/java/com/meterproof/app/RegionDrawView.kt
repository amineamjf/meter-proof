package com.meterproof.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.meterproof.app.delivery.InstructionSampler
import com.meterproof.app.privacy.Region
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * صورة مع مستطيل يرسمه المالك بإصبعه (إحداثيات نسبية 0..1 من الصورة، لا من الشاشة).
 *
 * - السحب يرسم المنطقة الحالية.
 * - لمسة قصيرة داخل إطار رمادي متقطّع (ورقة اكتشفها المحلل) تحدد ذلك الإطار كمنطقة، فلا حاجة لدقة اليد.
 * - [existing] تعليمات سابقة على الصورة نفسها تُرسم بخط رفيع بلون نوعها.
 */
class RegionDrawView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }

    var region: Region? = null
        set(v) {
            field = v
            invalidate()
        }

    /** إطارات الورق التي رآها المحلل (للاختيار بلمسة). */
    var candidates: List<Region> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** تعليمات سابقة على هذه الصورة مع لون كل منها. */
    var existing: List<Pair<Region, Int>> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    /** يُستدعى بعد أي تغيير من اللمس. */
    var onRegionChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val fit = RectF()
    private val tmp = RectF()
    private var startX = 0f
    private var startY = 0f
    private var moved = false

    private val current = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Ui.ACCENT
    }
    private val currentFill = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 189, 93, 58)
    }
    private val cand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(200, 220, 220, 220)
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val old = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    /** مستطيل عرض الصورة داخل الواجهة (ملاءمة مركزية). */
    private fun imageRect(): RectF {
        val b = bitmap
        if (b == null || width == 0 || height == 0) {
            fit.set(0f, 0f, 0f, 0f)
            return fit
        }
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * s
        val h = b.height * s
        val l = (width - w) / 2f
        val t = (height - h) / 2f
        fit.set(l, t, l + w, t + h)
        return fit
    }

    private fun rel(x: Float, y: Float): Pair<Float, Float> {
        val r = imageRect()
        if (r.width() <= 0f || r.height() <= 0f) return 0f to 0f
        return ((x - r.left) / r.width()).coerceIn(0f, 1f) to ((y - r.top) / r.height()).coerceIn(0f, 1f)
    }

    private fun toRect(r: Region, out: RectF): RectF {
        val f = imageRect()
        out.set(
            f.left + r.left * f.width(), f.top + r.top * f.height(),
            f.left + r.right * f.width(), f.top + r.bottom * f.height()
        )
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        val f = imageRect()
        canvas.drawBitmap(b, null, f, null)
        for (c in candidates) canvas.drawRect(toRect(c, tmp), cand)
        for ((r, color) in existing) {
            old.color = color
            canvas.drawRect(toRect(r, tmp), old)
        }
        region?.let {
            val rc = toRect(it, tmp)
            canvas.drawRect(rc, currentFill)
            canvas.drawRect(rc, current)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startX = event.x
                startY = event.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(event.x - startX) > 12f * density || abs(event.y - startY) > 12f * density)) {
                    moved = true
                }
                if (moved) {
                    val (x0, y0) = rel(startX, startY)
                    val (x1, y1) = rel(event.x, event.y)
                    region = Region(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    // مستطيل أصغر من 2% في أي بُعد لا معنى له: يُهمَل
                    val r = region
                    if (r != null && (r.right - r.left < 0.02f || r.bottom - r.top < 0.02f)) region = null
                } else {
                    val (x, y) = rel(event.x, event.y)
                    // أصغر إطار مكتشف يحوي نقطة اللمس
                    val hit = candidates
                        .filter { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
                        .minByOrNull { it.area }
                    if (hit != null) region = hit
                }
                onRegionChanged?.invoke()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    /** أكبر إطار مكتشف يتداخل مع [r] (للتلميح فقط). */
    fun bestCandidateFor(r: Region): Region? =
        candidates.maxByOrNull { InstructionSampler.iou(r, it) }
}

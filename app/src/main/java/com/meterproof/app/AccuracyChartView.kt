package com.meterproof.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * رسم صغير لتغيّر الدقة بعد كل جولة تدريب، مع خط أفقي للهدف.
 * القيم بين 0 و1. المحور من 0% إلى 100% دائمًا كي لا يُضخَّم تغيّر صغير.
 */
class AccuracyChartView(context: Context) : View(context) {

    private var values: List<Float> = emptyList()
    private var target = 0.95f
    private val d = resources.displayMetrics.density

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        strokeWidth = 1f * d
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        strokeWidth = 1.5f * d
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E7490")
        strokeWidth = 2.5f * d
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E7490")
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475467")
        textSize = 10f * d
    }

    fun setData(values: List<Float>, target: Float) {
        this.values = values
        this.target = target
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (110f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 30f * d
        val right = width - 8f * d
        val top = 8f * d
        val bottom = height - 18f * d
        fun yOf(v: Float): Float = bottom - (bottom - top) * v.coerceIn(0f, 1f)

        canvas.drawLine(left, bottom, right, bottom, axis)
        canvas.drawLine(left, top, left, bottom, axis)
        canvas.drawText("100%", 2f * d, top + 8f * d, label)
        canvas.drawText("0%", 8f * d, bottom, label)

        val ty = yOf(target)
        canvas.drawLine(left, ty, right, ty, targetPaint)
        canvas.drawText("الهدف ${(target * 100).toInt()}%", left + 4f * d, ty - 3f * d, label)

        if (values.isEmpty()) {
            canvas.drawText("لا جولات تدريب مقيسة بعد", left + 12f * d, (top + bottom) / 2, label)
            return
        }
        val n = values.size
        fun xOf(i: Int): Float =
            if (n == 1) (left + right) / 2 else left + (right - left) * i / (n - 1)

        if (n > 1) {
            val path = Path()
            for (i in 0 until n) {
                val x = xOf(i)
                val y = yOf(values[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, line)
        }
        for (i in 0 until n) canvas.drawCircle(xOf(i), yOf(values[i]), 3.5f * d, dot)
        canvas.drawText("جولات التدريب: $n", right - 80f * d, height - 4f * d, label)
    }
}

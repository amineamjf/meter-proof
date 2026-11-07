package com.meterproof.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تقرير PDF ليوم عمل واحد: الإحصاءات وخارطة المسار والتوقفات وحالة التحقق من البصمات.
 * يحصل عليه العامل ليحتفظ بنسخة تحميه عند أي نزاع.
 */
object PdfReport {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    fun write(
        summary: DaySummary,
        nowMs: Long,
        track: List<GeoPoint>,
        photos: List<PhotoPoint>,
        verification: String,
        out: OutputStream
    ) {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var canvas = page.canvas
        var y = 60f

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            isFakeBoldText = true
            textAlign = Paint.Align.RIGHT
            color = Color.BLACK
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            textAlign = Paint.Align.RIGHT
            color = Color.parseColor("#263238")
        }
        val heading = Paint(body).apply {
            textSize = 14f
            isFakeBoldText = true
        }
        val maxWidth = PAGE_W - 2 * MARGIN
        val right = PAGE_W - MARGIN

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            y = 50f
        }

        fun line(text: String, paint: Paint, gap: Float = 18f) {
            var rest = text
            while (rest.isNotEmpty()) {
                if (y > PAGE_H - 50f) newPage()
                val n = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                canvas.drawText(rest.substring(0, n), right, y, paint)
                y += gap
                rest = rest.substring(n).trimStart()
            }
        }

        val dateText = SimpleDateFormat("EEEE dd/MM/yyyy", Locale("ar", "MA"))
            .format(Date(summary.dayStartMs))
        line("MeterProof: تقرير يوم عمل", title, 28f)
        line(dateText, heading, 24f)

        for (l in SummaryText.statLines(summary)) line(l, body)
        y += 6f
        line("حالة سلسلة البصمات: $verification", body)
        y += 10f

        // الخارطة
        val mapW = (PAGE_W - 2 * MARGIN)
        val mapH = 340f
        if (y + mapH > PAGE_H - 50f) newPage()
        canvas.save()
        canvas.translate(MARGIN, y)
        canvas.clipRect(0f, 0f, mapW, mapH)
        MapRenderer(1.2f).draw(canvas, mapW, mapH, track, photos, summary.stops, -1)
        canvas.restore()
        y += mapH + 20f

        line("التوقفات الطويلة:", heading, 22f)
        val stopLines = SummaryText.stopLines(summary, nowMs)
        if (stopLines.isEmpty()) {
            line("لا توجد توقفات طويلة", body)
        } else {
            for (l in stopLines.take(300)) line(l, body)
        }

        y += 16f
        line("هذا التقرير نسخة من السجل المحفوظ باسمك، يحميك عند أي شكوى.", body)

        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

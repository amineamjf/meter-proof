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
/** ما جرى لخلفية الخارطة في التقرير، لتُعرض للمستخدم بصدق (كاملة، ناقصة، أو غائبة). */
class ReportMapOutcome(
    val usedTiles: Boolean,
    val needed: Int,
    val exact: Int,
    val fallback: Int,
    val details: Int
) {
    val complete: Boolean get() = usedTiles && needed > 0 && exact == needed
}

object PdfReport {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    /** أبعاد الخارطة العامة والتفصيلية بنقاط PDF؛ يعتمد عليها التخطيط وجلب البلاطات الناقصة. */
    const val MAP_W = 515.0
    const val MAP_H = 340.0
    const val DETAIL_H = 300.0

    /** خطة خرائط التقرير لهذه البيانات (تستعملها الكتابة وجلب الناقص بالقيم نفسها). */
    fun mapPlan(
        track: List<GeoPoint>, photos: List<PhotoPoint>, stops: List<Stop>, meters: List<MeterPoint>
    ): ReportMapPlan = ReportMapPlanner.plan(track, photos, stops, meters, MAP_W, MAP_H, DETAIL_H)

    /**
     * [map] اختياري: إن وُجد ترسم الخارطة العامة فوق بلاطات القمر الصناعي المخزنة (مع النسب)،
     * وتُضاف خرائط تفصيلية لتجمعات النقاط المتراكبة. بلا بلاطات تبقى الخارطة كما كانت (خلفية سادة).
     * يعيد وصفًا لما رُسم.
     */
    fun write(
        summary: DaySummary,
        nowMs: Long,
        track: List<GeoPoint>,
        photos: List<PhotoPoint>,
        verification: String,
        out: OutputStream,
        meters: List<MeterPoint> = emptyList(),
        map: ReportMapSource? = null
    ): ReportMapOutcome {
        val mapRenderer = map?.let { ReportMapRenderer(it) }
        try {
            return writeInternal(summary, nowMs, track, photos, verification, out, meters, map, mapRenderer)
        } finally {
            mapRenderer?.release()
        }
    }

    private fun writeInternal(
        summary: DaySummary,
        nowMs: Long,
        track: List<GeoPoint>,
        photos: List<PhotoPoint>,
        verification: String,
        out: OutputStream,
        meters: List<MeterPoint>,
        map: ReportMapSource?,
        mapRenderer: ReportMapRenderer?
    ): ReportMapOutcome {
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

        // الخارطة العامة: فوق بلاطات القمر الصناعي المخزنة إن وُجدت، وإلا كما كانت (خلفية سادة)
        val mapW = (PAGE_W - 2 * MARGIN)
        val mapH = MAP_H.toFloat()
        val plan = if (map != null) mapPlan(track, photos, summary.stops, meters) else null
        val overview = plan?.overview
        if (y + mapH > PAGE_H - 50f) newPage()
        var tileOutcome: ReportMapRenderer.Outcome? = null
        var drewTiles = false
        canvas.save()
        canvas.translate(MARGIN, y)
        canvas.clipRect(0f, 0f, mapW, mapH)
        if (mapRenderer != null && overview != null) {
            try {
                val o = mapRenderer.drawBackground(canvas, overview)
                tileOutcome = o
                if (o.drawn > 0) {
                    MapRenderer(1.2f, compact = true).draw(
                        canvas, mapW, mapH, track, photos, summary.stops, -1,
                        mapRenderer.projector(overview), meters
                    )
                    mapRenderer.drawAttribution(canvas, overview)
                    drewTiles = true
                }
            } catch (e: Throwable) {
                drewTiles = false // أي خلل في الخلفية لا يُسقط التقرير: نعود إلى الرسم القديم
            }
        }
        if (!drewTiles) {
            MapRenderer(1.2f, compact = true).draw(canvas, mapW, mapH, track, photos, summary.stops, -1, null, meters)
        }
        canvas.restore()
        y += mapH + 6f

        if (drewTiles && map != null) {
            line("خلفية الخارطة: ${TileLayers.reportAttribution(map.template)}", body, 16f)
            val o = tileOutcome
            if (o != null && !o.complete) {
                line("الخلفية ناقصة في بعض المواضع (بلاطات غير مخزنة هناك، تظهر رمادية أو أقل وضوحًا).", body, 16f)
            }
        } else if (map != null && overview != null) {
            line("بلا خلفية خارطة: لا بلاطات مخزنة لهذه المنطقة. حمّل الخارطة، أو صدّر الملخص وأنت متصل بالإنترنت.", body, 16f)
        }
        y += 14f

        line("التوقفات الطويلة:", heading, 22f)
        val stopLines = SummaryText.stopLines(summary, nowMs)
        if (stopLines.isEmpty()) {
            line("لا توجد توقفات طويلة", body)
        } else {
            for (l in stopLines.take(300)) line(l, body)
        }

        // خرائط تفصيلية لتجمعات النقاط التي تتراكب دوائرها على الخارطة العامة (D3)
        var detailsDrawn = 0
        if (plan != null && mapRenderer != null && plan.details.isNotEmpty()) {
            y += 12f
            line("خرائط تفصيلية لتجمعات النقاط (دوائر متقاربة تتراكب في الخارطة العامة):", heading, 22f)
            for ((i, dv) in plan.details.withIndex()) {
                val c = plan.clusters[i]
                val dh = dv.h.toFloat()
                if (y + dh + 34f > PAGE_H - 50f) newPage()
                line("التجمع ${i + 1}: ${c.photos} صور، ${c.stops} توقفات، ${c.meters} عدادات", body, 18f)
                canvas.save()
                canvas.translate(MARGIN, y)
                canvas.clipRect(0f, 0f, mapW, dh)
                try {
                    mapRenderer.drawBackground(canvas, dv)
                    MapRenderer(1.2f, compact = true).draw(
                        canvas, mapW, dh, track, photos, summary.stops, -1,
                        mapRenderer.projector(dv), meters
                    )
                    mapRenderer.drawAttribution(canvas, dv)
                    detailsDrawn++
                } catch (e: Throwable) {
                    // خارطة تفصيلية واحدة فاشلة لا تُسقط التقرير
                }
                canvas.restore()
                y += dh + 16f
            }
        }

        y += 16f
        line("هذا التقرير نسخة من السجل المحفوظ باسمك، يحميك عند أي شكوى.", body)

        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
        return ReportMapOutcome(
            drewTiles, tileOutcome?.needed ?: 0, tileOutcome?.exact ?: 0, tileOutcome?.fallback ?: 0, detailsDrawn
        )
    }
}

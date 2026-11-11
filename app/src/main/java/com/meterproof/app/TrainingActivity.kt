package com.meterproof.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.meterproof.app.PhotoLabels.Label
import com.meterproof.app.delivery.GalleryTraining
import com.meterproof.app.delivery.GalleryTraining.Verdict
import com.meterproof.app.delivery.InvoiceAnalyzer
import com.meterproof.app.delivery.InvoiceCandidate
import com.meterproof.app.delivery.InvoiceTrainer
import com.meterproof.app.privacy.Region
import java.io.File
import java.util.concurrent.Executors

/**
 * التدريب المرئي من الصور المصنَّفة في المعرض: يحلّل كل صورة، يدرّب على الصور المصنَّفة، ويعرض
 * على كل صورة ما اكتشفه النموذج ودرجته وهل أصاب.
 *
 * تقرير فقط: لا يحفظ نموذجًا ولا يفعّل الكشف ولا يمسّ عينات المعايرة ولا السجل المبصوم.
 * القياس بالتحقق المتقاطع على صور المالك نفسه، وليس على بيانات ميدانية مستقلة.
 */
class TrainingActivity : Activity() {

    private lateinit var store: EvidenceStore
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var summary: TextView
    private lateinit var toggleErrors: Button
    private lateinit var list: ListView

    private var report: GalleryTraining.Report? = null
    private var rows: List<GalleryTraining.PhotoOutcome> = emptyList()
    private var errorsOnly = false

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val loader = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Volatile private var destroyed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EvidenceStore(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }
        status = TextView(this).apply { textSize = 15f; text = "جارٍ التحضير…" }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        summary = TextView(this).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(summary)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggleErrors = Button(this).apply {
            text = "الأخطاء فقط"
            isEnabled = false
            setOnClickListener { errorsOnly = !errorsOnly; refreshRows() }
        }
        buttons.addView(toggleErrors, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(
            Button(this).apply { text = "إغلاق"; setOnClickListener { finish() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(buttons)

        list = ListView(this).apply { adapter = adapter() }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        worker.execute { runTraining() }
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        loader.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- التحليل (خيط خلفي)

    private fun runTraining() {
        try {
            val labels = PhotoLabels.latest(store.events())
            val records = store.readRecords()

            val entries = ArrayList<GalleryTraining.Entry>()
            var skippedAbstract = 0
            var skippedDecode = 0
            val candidates = records.filter {
                val l = labels[it.optString("id")]
                (l == Label.INVOICE || l == Label.NONE) &&
                    store.imageFile(it.optString("id")).exists() // المحذوفة بيد المستخدم لا تدخل التدريب
            }
            for ((i, r) in candidates.withIndex()) {
                if (destroyed) return
                val id = r.optString("id")
                val label = labels[id] ?: continue
                if (!PhotoLabels.usableForTraining(r.optString("flags"))) {
                    skippedAbstract++
                    continue
                }
                val cand = analyze(store.imageFile(id))
                val t = r.optString("t").toLongOrNull() ?: 0L
                if (cand == null) {
                    // فشل فك الصورة نفسها ≠ «لا ورقة»
                    if (decodeFailed) skippedDecode++
                    else entries.add(GalleryTraining.Entry(id, label, null, t))
                } else {
                    entries.add(GalleryTraining.Entry(id, label, cand.features, t, cand.region))
                }
                val done = i + 1
                ui.post { status.text = "تحليل الصور: $done / ${candidates.size}"; progress.max = candidates.size; progress.progress = done }
            }
            if (destroyed) return

            // «غير واضح» تُعدّ فقط
            val unclearEntries = labels.filterValues { it == Label.UNCLEAR }
                .keys.map { GalleryTraining.Entry(it, Label.UNCLEAR, null, 0L) }

            val rep = GalleryTraining.run(entries + unclearEntries, System.currentTimeMillis())
            ui.post { if (!destroyed) show(rep, skippedAbstract, skippedDecode) }
        } catch (e: Throwable) {
            ui.post { status.text = "تعذّر التحليل: ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    @Volatile private var decodeFailed = false

    /** أكبر مرشح ورقة في الصورة (سماته وإطاره)، أو null (decodeFailed تميّز فشل الفك عن غياب الورقة). */
    private fun analyze(f: File): InvoiceCandidate? {
        decodeFailed = false
        var bmp: Bitmap? = null
        var px: IntArray? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                decodeFailed = true
                return null
            }
            // نفس سقف دقة البوابة (1280 بكسل للضلع الأطول) ليتشابه توزيع السمات مع الكشف الحي
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= 1280) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bmp = BitmapFactory.decodeFile(f.path, opts)
            if (bmp == null) {
                decodeFailed = true
                return null
            }
            val w = bmp.width
            val h = bmp.height
            px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            return InvoiceAnalyzer.analyze(px, w, h)
        } catch (e: Throwable) {
            decodeFailed = true
            return null
        } finally {
            px?.fill(0)
            bmp?.recycle()
        }
    }

    // ---------------------------------------------------------------- العرض

    private fun show(rep: GalleryTraining.Report, skippedAbstract: Int, skippedDecode: Int) {
        report = rep
        progress.visibility = View.GONE
        status.text = "انتهى التحليل: ${rep.outcomes.size} صورة مصنَّفة"
        toggleErrors.isEnabled = true

        val sb = StringBuilder()
        sb.append("دخل التدريب ${rep.trainedOn} صورة (وُجدت فيها ورقة). ")
        sb.append("فاتورة: ${rep.invoiceCount}، بلا فاتورة: ${rep.noneCount}، غير واضح: ${rep.unclearCount}.\n")
        if (skippedAbstract > 0) sb.append("تخطّيتُ $skippedAbstract صورة مجرّدة (لا تصلح للتدريب).\n")
        if (skippedDecode > 0) sb.append("تعذّر فك $skippedDecode صورة.\n")

        when (val r = rep.result) {
            is InvoiceTrainer.Result.Ready -> {
                val m = r.model.metrics
                sb.append("\n✅ النموذج جاهز بمقياس التحقق المتقاطع: الدقة ${pct(m.precision)}% (الهدف 95%)، ")
                sb.append("الاستدعاء ${pct(m.recall)}%.\n")
            }
            is InvoiceTrainer.Result.NotReady -> {
                sb.append("\n⚠ النموذج غير جاهز بعد:\n")
                for (c in r.reasons) sb.append("• ").append(GalleryTraining.reasonArabic(c)).append("\n")
                r.metrics?.let { sb.append("عند عتبة 50%: الدقة ${pct(it.precision)}%، الاستدعاء ${pct(it.recall)}%.\n") }
                if (rep.outcomes.any { it.score != null }) {
                    sb.append("الأحكام أدناه بعتبة تقدير 50% للعرض فقط، وليست عتبة قبول معتمدة.\n")
                }
            }
        }
        sb.append("\nالإطار الملوّن على كل صورة هو الورقة التي رآها الكاشف (لونه لون الحكم).")
        sb.append("\nتقرير فقط: لا يفعّل الكشف ولا يغيّر المعايرة. القياس على صورك نفسها، وليس على بيانات ميدانية مستقلة.")
        summary.text = sb.toString()
        refreshRows()
    }

    private fun pct(v: Float) = (v * 100f).toInt().coerceIn(0, 100)

    private fun refreshRows() {
        val rep = report ?: return
        rows = if (errorsOnly) {
            rep.outcomes.filter { it.verdict == Verdict.MISSED || it.verdict == Verdict.FALSE_ACCEPT }
        } else {
            // الأخطاء أولًا ثم غير المؤكد ثم الصحيح
            rep.outcomes.sortedBy {
                when (it.verdict) {
                    Verdict.FALSE_ACCEPT -> 0
                    Verdict.MISSED -> 1
                    Verdict.UNCERTAIN -> 2
                    Verdict.UNKNOWN -> 3
                    Verdict.CORRECT -> 4
                }
            }
        }
        toggleErrors.text = if (errorsOnly) "عرض الكل" else "الأخطاء فقط"
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun verdictText(o: GalleryTraining.PhotoOutcome): Pair<String, Int> = when (o.verdict) {
        Verdict.CORRECT -> "✔ صحيح" to Color.parseColor("#2E7D32")
        Verdict.MISSED -> (if (o.noPaper) "✘ فاتورة لم تُكتشف ورقتها" else "✘ فاتورة لم تُكتشف") to Color.parseColor("#C62828")
        Verdict.FALSE_ACCEPT -> "✘ قبول خاطئ (الأخطر)" to Color.parseColor("#B71C1C")
        Verdict.UNCERTAIN -> "؟ غير مؤكد" to Color.parseColor("#EF6C00")
        Verdict.UNKNOWN -> "لا تقدير (عينات غير كافية)" to Color.parseColor("#607D8B")
    }

    private fun adapter() = object : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row: LinearLayout
            val img: ImageView
            val text: TextView
            if (convertView == null) {
                row = LinearLayout(this@TrainingActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                img = ImageView(this@TrainingActivity).apply {
                    // الإطار كاملًا (لا قصّ) كي يظهر إطار الورقة المرسوم كله
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#ECEFF1"))
                }
                text = TextView(this@TrainingActivity).apply { textSize = 13f; setPadding(dp(10), 0, 0, 0) }
                row.addView(img, LinearLayout.LayoutParams(dp(88), dp(88)))
                row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                row = convertView as LinearLayout
                img = row.getChildAt(0) as ImageView
                text = row.getChildAt(1) as TextView
            }
            val o = rows[position]
            val (verdict, color) = verdictText(o)
            val score = when {
                o.noPaper -> "لم تُكتشف ورقة"
                o.score != null -> "الدرجة: ${pct(o.score)}%"
                else -> "بلا درجة"
            }
            text.text = "تصنيفك: ${o.label.arabic}\n$score\n$verdict"
            text.setTextColor(color)

            img.tag = o.id
            val cached = cache.get(o.id)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageBitmap(null)
                loader.execute {
                    val bmp = outlined(decode(store.imageFile(o.id), 320), o)
                    if (bmp != null) {
                        cache.put(o.id, bmp)
                        ui.post { if (img.tag == o.id) img.setImageBitmap(bmp) }
                    }
                }
            }
            row.setOnClickListener { openViewer(o) }
            return row
        }
    }

    private fun decode(f: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * يرسم إطار الورقة التي رآها الكاشف على نسخة من الصورة (بلون الحكم)، فيرى المالك ما اكتشفه
     * النموذج فعلًا. بلا إطار إن لم تُكتشف ورقة. الصورة المخزّنة لا تُمسّ.
     */
    private fun outlined(src: Bitmap?, o: GalleryTraining.PhotoOutcome): Bitmap? {
        val region: Region = o.region ?: return src
        if (src == null) return null
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val c = Canvas(bmp)
        val stroke = (maxOf(bmp.width, bmp.height) / 120f).coerceAtLeast(3f)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            isAntiAlias = true
            color = verdictText(o).second
        }
        c.drawRect(
            region.left * bmp.width, region.top * bmp.height,
            region.right * bmp.width, region.bottom * bmp.height, paint
        )
        return bmp
    }

    private fun openViewer(o: GalleryTraining.PhotoOutcome) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        col.addView(
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(outlined(decode(store.imageFile(o.id), 1600), o))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val (verdict, _) = verdictText(o)
        col.addView(TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(4))
            text = "تصنيفك: ${o.label.arabic}\n" +
                (o.score?.let { "الدرجة: ${pct(it)}%" } ?: if (o.noPaper) "لم تُكتشف ورقة" else "بلا درجة") +
                "\n$verdict"
        })
        col.addView(Button(this).apply { text = "إغلاق"; setOnClickListener { dialog.dismiss() } })
        dialog.setContentView(col)
        dialog.show()
    }
}

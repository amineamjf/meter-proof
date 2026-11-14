package com.meterproof.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.delivery.DeliveryStore
import com.meterproof.app.delivery.GalleryTraining
import com.meterproof.app.delivery.InstructionKind
import com.meterproof.app.delivery.InstructionStores
import com.meterproof.app.delivery.InvoiceTrainer
import com.meterproof.app.delivery.ModelLabels
import com.meterproof.app.delivery.SampleCodec
import com.meterproof.app.delivery.TrainingInstruction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * قائمة تعليمات تدريب كاشف الفاتورة: عرض وتعديل وحذف، وزر «درّب الآن» الذي يطبّقها.
 * مستقلة عن أي مهمة أخرى (بيانات مهمة الفواتير فقط).
 *
 * التدريب هنا يستعمل عينات المعايرة اليدوية (إن وُجدت) مع عينات التعليمات، بنفس شروط الدقة الحالية
 * (دقة 95%، 15 عينة على الأقل من كل نوع). إن لم يبلغ النموذج الهدف يُوقَف الكشف كما في شاشة المعايرة.
 */
class InstructionListActivity : Activity() {

    private val store by lazy { InstructionStores.forTask(this) }
    private lateinit var evidence: EvidenceStore
    private lateinit var settings: Settings
    private lateinit var summary: TextView
    private lateinit var list: ListView
    private var rows: List<TrainingInstruction> = emptyList()

    private val WINDOW_MIN = com.meterproof.app.delivery.WindowTrainer.Config().minInvoiceImages

    private val ui = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(2 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        evidence = EvidenceStore(this)
        settings = Settings(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "تعليمات تدريب الفاتورة"
            textSize = 18f
        })
        summary = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(6)) }
        root.addView(summary)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            Ui.button(this, "علّم صورة جديدة", Ui.Kind.TONAL, Ui.Icon.TAG, compact = true) {
                Toast.makeText(
                    this, "افتح صورة من المعرض ثم اضغط «علّم المناطق»", Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this, PhotoGalleryActivity::class.java))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            Ui.button(this, "اقتراحات بنقرة", Ui.Kind.TONAL, Ui.Icon.SPARKLE, compact = true) {
                if (DeliveryStore(this).windowModel() == null) {
                    Toast.makeText(this, "درّب أولًا (درّب الآن) ليقترح التطبيق مربعات", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(this, ProposalReviewActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            Ui.button(this, "درّب الآن", Ui.Kind.PRIMARY, Ui.Icon.SPARKLE, compact = true) { train() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(actions)

        list = ListView(this).apply { adapter = adapter() }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Ui.button(this, "إغلاق", Ui.Kind.GHOST, Ui.Icon.CLOSE, compact = true) { finish() })

        setContentView(root)
        Ui.apply(this)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        loader.shutdown()
        super.onDestroy()
    }

    private fun reload() {
        rows = store.list().sortedByDescending { it.createdAt }
        val labels = PhotoLabels.latest(evidence.events())
        val invImgs = rows.filter { it.kind == InstructionKind.RECOGNIZE }.map { it.photoId }.toSet().size
        val noneImgs = labels.values.count { it == PhotoLabels.Label.NONE || it == PhotoLabels.Label.SIMILAR }
        val need = WINDOW_MIN
        summary.text = "${rows.size} تعليمة (فاتورة: ${rows.count { it.kind == InstructionKind.RECOGNIZE }}، " +
            "تجاهل: ${rows.count { it.kind == InstructionKind.IGNORE }}).\n" +
            "صور فيها مربع «فاتورة»: $invImgs، وصور مصنَّفة «بلا فاتورة»: $noneImgs (يلزم $need من كل نوع على الأقل).\n" +
            "أرسم مربعًا حول الفاتورة في صور فيها فاتورة، وصنّف صورًا بلا فاتورة. التدريب يقرأ الصور من الذاكرة فقط."
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- التدريب

    private fun train() {
        Toast.makeText(this, "جارٍ التدريب (قد يستغرق عدة دقائق)...", Toast.LENGTH_LONG).show()
        Thread {
            val outcome = try {
                WindowTraining.run(this)
            } catch (e: Throwable) {
                null
            }
            ui.post {
                if (outcome == null) {
                    settings.deliveryDetection = false
                    AlertDialog.Builder(this).setMessage("تعذّر التدريب (ذاكرة أو صور تالفة). لم يتغير شيء.")
                        .setPositiveButton("حسنًا", null).show()
                } else {
                    showOutcome(outcome)
                }
                reload()
            }
        }.start()
    }

    private fun showOutcome(o: WindowTraining.Outcome) {
        val extra = StringBuilder()
        if (o.invoiceWithoutBox > 0) {
            extra.append("\n• ${o.invoiceWithoutBox} صورة صنّفتها «فاتورة» بلا مربع، فلا يتعلم منها: " +
                "افتحها واضغط «علّم المناطق» وارسم مربعًا حول الفاتورة.")
        }
        when (val t = o.twoStage) {
            is com.meterproof.app.delivery.TwoStageTrainer.Result.Ready ->
                extra.append("\n• المدقِّق الدلالي (نموذج الرؤية الجاهز) جاهز: على الصور خارج العينة أُصيبت " +
                    "${t.report.hit} من ${t.report.invoiceImages} فاتورة وقُبلت ${t.report.falseAccepts} خطأً " +
                    "من ${t.report.noneImages}. وهو المعتمد في الكشف الحي.")
            is com.meterproof.app.delivery.TwoStageTrainer.Result.NotReady ->
                extra.append("\n• المدقِّق الدلالي لم يبلغ الهدف (${t.reasons.joinToString { GalleryTraining.reasonArabic(it) }})، " +
                    "فيعمل كاشف النوافذ وحده.")
            null -> if (o.twoStageSkipped != null) extra.append("\n• المدقِّق الدلالي لم يُجرَّ: ${o.twoStageSkipped}.")
        }
        o.decision?.let { d ->
            extra.append(
                if (d.accepted) "\n• اعتُمد النموذج الجديد إصدارًا رقم ${d.entry.meta.version}."
                else "\n• رُفض النموذج الجديد (إصدار ${d.entry.meta.version}) لأنه ${ModelLabels.reasonArabic(d.reason)}؛ " +
                    "ما زال الإصدار المستقر السابق معتمدًا."
            )
        }
        if (o.unreadable > 0) extra.append("\n• ${o.unreadable} صورة تعذّرت قراءتها أو محذوفة أو مجرّدة.")
        when (val r = o.result) {
            is com.meterproof.app.delivery.WindowTrainer.Result.Ready -> {
                val rep = r.report
                val hold = rep.holdout?.let {
                    "\nصور محجوزة لم يرها التدريب: اكتُشفت ${it.detected} من ${it.invoiceImages} فاتورة، " +
                        "وقُبلت ${it.falseAccepts} خطأً من ${it.noneImages} «بلا فاتورة»."
                } ?: "\n(الصور قليلة لقياس محجوز بعد.)"
                val problems = rep.problems.size
                AlertDialog.Builder(this)
                    .setTitle("النموذج جاهز")
                    .setMessage(
                        "على صور التدريب نفسها: أُصيبت ${rep.hit} من ${rep.invoiceImages} فاتورة، " +
                            "وقُبلت ${rep.falseAccepts} خطأً من ${rep.noneImages} «بلا فاتورة».$hold" +
                            (if (problems > 0) "\nصور ما زالت خاطئة: $problems (أضف مربعات أدق أو أمثلة أكثر)." else "") +
                            extra +
                            "\nلا يعمل الكشف حتى تفعّله من شاشة «كشف تسليم الفواتير»."
                    )
                    .setPositiveButton("حسنًا", null)
                    .show()
            }
            is com.meterproof.app.delivery.WindowTrainer.Result.NotReady -> {
                if (DeliveryStore(this).windowModel() == null) settings.deliveryDetection = false
                val counts = "\nصور فيها مربع «فاتورة»: ${o.usedInvoiceImages}، صور «بلا فاتورة»: ${o.usedNoneImages}."
                val diag = r.report?.diagnosis?.let { d ->
                    "\n\nأفضل ما أمكن على صور التدريب نفسها: لو لم يُقبل أي خطأ لاكتُشفت ${d.hitsAtZeroWrong} من ${d.invoiceTotal} فاتورة." +
                        (if (d.hitsAtRecall80 != null) " ولاكتشاف 80% منها (${d.hitsAtRecall80}) يُقبل خطأً ${d.wrongAtRecall80} صورة." else
                            " ولا يبلغ 80% منها أصلًا.") +
                        "\nافتح «قائمة المراجعة» في المعرض: الصور الخاطئة غالبًا فيها ورقة تشبه الفاتورة. صنّفها «ورقة مشابهة»، أو راجع إن كانت «بلا فاتورة» فعلًا، وأضف فواتير بمربعات متنوعة."
                } ?: ""
                AlertDialog.Builder(this)
                    .setTitle("النموذج الجديد غير جاهز (لم يتغير المستقر إن وُجد)")
                    .setMessage(
                        r.reasons.joinToString("\n") { "• " + GalleryTraining.reasonArabic(it) } + counts + diag + extra
                    )
                    .setPositiveButton("حسنًا", null)
                    .show()
            }
        }
    }

    /** سطر القياس على أمثلة محجوزة لم يرها التدريب (أصدق من الدقة المتقاطعة). */
    private fun holdoutText(h: com.meterproof.app.delivery.HoldoutReport?): String = when {
        h == null -> "قياس الأمثلة المحجوزة: لا يكفي عدد الأمثلة بعد.\n"
        !h.trainable -> "قياس الأمثلة المحجوزة: لم يبلغ النموذج الهدف بعد عزل ${h.heldPositives + h.heldNegatives} مثالًا.\n"
        else -> "أمثلة محجوزة لم يرها التدريب: فواتير اكتُشفت ${h.truePositives} من ${h.heldPositives}، " +
            "و«بلا فاتورة» قُبلت خطأً ${h.falseAccepts} من ${h.heldNegatives}.\n"
    }

    // ---------------------------------------------------------------- القائمة

    private fun adapter() = object : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row: LinearLayout
            val img: ImageView
            val text: TextView
            if (convertView == null) {
                row = LinearLayout(this@InstructionListActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                img = ImageView(this@InstructionListActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#ECEFF1"))
                }
                text = TextView(this@InstructionListActivity).apply { textSize = 13f; setPadding(dp(10), 0, 0, 0) }
                row.addView(img, LinearLayout.LayoutParams(dp(88), dp(88)))
                row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                row = convertView as LinearLayout
                img = row.getChildAt(0) as ImageView
                text = row.getChildAt(1) as TextView
            }
            val i = rows[position]
            val place = if (i.kind == InstructionKind.RECOGNIZE) " — ${i.placement.arabic}" else ""
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(i.createdAt))
            text.text = i.kind.arabic + place + "\n" +
                (if (i.note.isNotEmpty()) i.note + "\n" else "") +
                "أمثلة: +${i.positives} / −${i.negatives}  ($date)"
            text.setTextColor(if (i.kind == InstructionKind.RECOGNIZE) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))

            val file = evidence.imageFile(i.photoId)
            img.tag = i.id
            val cached = cache.get(i.id)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else if (!file.exists()) {
                img.setImageBitmap(null) // صورتها محذوفة: تبقى التعليمة وعيّناتها
            } else {
                img.setImageBitmap(null)
                loader.execute {
                    val bmp = outlined(decode(file, 320), i)
                    if (bmp != null) {
                        cache.put(i.id, bmp)
                        ui.post { if (img.tag == i.id) img.setImageBitmap(bmp) }
                    }
                }
            }
            row.setOnClickListener { chooseAction(i) }
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

    /** نسخة من الصورة عليها منطقة التعليمة (الصورة المخزّنة لا تُمسّ). */
    private fun outlined(src: Bitmap?, i: TrainingInstruction): Bitmap? {
        if (src == null) return null
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = (maxOf(bmp.width, bmp.height) / 100f).coerceAtLeast(3f)
            isAntiAlias = true
            color = if (i.kind == InstructionKind.RECOGNIZE) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")
        }
        val r = i.region
        Canvas(bmp).drawRect(r.left * bmp.width, r.top * bmp.height, r.right * bmp.width, r.bottom * bmp.height, paint)
        return bmp
    }

    private fun chooseAction(i: TrainingInstruction) {
        AlertDialog.Builder(this)
            .setTitle(i.kind.arabic)
            .setItems(arrayOf("تعديل", "حذف", "إلغاء")) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, InstructionEditorActivity::class.java)
                            .putExtra(InstructionEditorActivity.EXTRA_INSTRUCTION, i.id)
                    )
                    1 -> confirmDelete(i)
                }
            }
            .show()
    }

    private fun confirmDelete(i: TrainingInstruction) {
        AlertDialog.Builder(this)
            .setMessage("حذف هذه التعليمة وأمثلتها (+${i.positives} / −${i.negatives})؟ الصورة نفسها لا تُحذف. أعد التدريب بعد الحذف ليتغيّر النموذج.")
            .setPositiveButton("حذف") { _, _ ->
                store.delete(i.id)
                cache.remove(i.id)
                reload()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

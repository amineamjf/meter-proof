package com.meterproof.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.delivery.InstructionCodec
import com.meterproof.app.delivery.InstructionKind
import com.meterproof.app.delivery.InstructionSampler
import com.meterproof.app.delivery.InstructionStore
import com.meterproof.app.delivery.InstructionStores
import com.meterproof.app.delivery.InvoiceAnalyzer
import com.meterproof.app.delivery.Placement
import com.meterproof.app.delivery.TrainingInstruction
import com.meterproof.app.privacy.Region
import java.io.File
import java.util.concurrent.Executors

/**
 * تعليم صورة لتدريب كاشف الفاتورة: يرسم المالك مستطيلًا حول شيء ويقول للتطبيق ما هو.
 *
 * - «فاتورة: تعرّف عليها» + موضعها (فوق الباب / تحته / بجانبه).
 * - «تجاهل: ليست فاتورة» (ورقة جار، سيارة، جدار فاتح...).
 *
 * يحفظ التطبيق أرقامًا فقط (سمات المنطقة) ولا يحفظ أي بكسلات ولا يغيّر الصورة نفسها ولا السجل المبصوم.
 * مهمة الفواتير فقط ([com.meterproof.app.delivery.TrainingTask.INVOICE])، ولا تشاركها بيانات أي مهمة أخرى.
 *
 * Extras: photoId (مطلوب)، instructionId (اختياري: تعديل تعليمة موجودة).
 */
class InstructionEditorActivity : Activity() {

    companion object {
        const val EXTRA_PHOTO = "photoId"
        const val EXTRA_INSTRUCTION = "instructionId"
        private const val MAX_ANALYSIS_SIDE = 1280 // نفس سقف بوابة الخصوصية والتدريب الآخر
    }

    private lateinit var evidence: EvidenceStore
    private lateinit var store: InstructionStore
    private lateinit var draw: RegionDrawView
    private lateinit var hint: TextView
    private lateinit var note: EditText
    private lateinit var kindButtons: Map<InstructionKind, Button>
    private lateinit var placeButtons: Map<Placement, Button>
    private lateinit var saveButton: Button

    private var photoId = ""
    private var editing: TrainingInstruction? = null
    private var kind = InstructionKind.RECOGNIZE
    private var placement = Placement.UNSPECIFIED
    private var photoFile: File? = null

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        evidence = EvidenceStore(this)
        store = InstructionStores.forTask(this)
        photoId = intent.getStringExtra(EXTRA_PHOTO) ?: ""
        editing = intent.getStringExtra(EXTRA_INSTRUCTION)?.let { store.get(it) }
        if (editing != null) {
            kind = editing!!.kind
            placement = editing!!.placement
            photoId = editing!!.photoId
        }
        val file = if (photoId.isEmpty()) null else evidence.imageFile(photoId)
        photoFile = if (file != null && file.exists()) file else null
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }
        hint = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, dp(4)) }
        root.addView(hint)

        draw = RegionDrawView(this)
        draw.onRegionChanged = { refreshHint() }
        root.addView(draw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // نوع التعليمة
        val kindRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val kb = LinkedHashMap<InstructionKind, Button>()
        for (k in InstructionKind.values()) {
            val b = Ui.button(this, k.arabic, Ui.Kind.SECONDARY, null, compact = true) { setKind(k) }
            kb[k] = b
            kindRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        kindButtons = kb
        root.addView(kindRow)

        // موضع الفاتورة
        val placeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pb = LinkedHashMap<Placement, Button>()
        for (p in Placement.values()) {
            val b = Ui.button(this, p.arabic, Ui.Kind.SECONDARY, null, compact = true) { setPlacement(p) }
            pb[p] = b
            placeRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        placeButtons = pb
        root.addView(placeRow)

        note = EditText(this).apply {
            hint = "ملاحظة قصيرة (اختياري)، مثل: فاتورة تحت الباب الأخضر"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            textSize = 14f
            setText(editing?.note ?: "")
        }
        root.addView(note)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveButton = Ui.button(this, if (editing == null) "حفظ التعليمة" else "حفظ التعديل", Ui.Kind.PRIMARY, Ui.Icon.CHECK, compact = true) { save() }
        actions.addView(saveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(
            Ui.button(this, "مسح المنطقة", Ui.Kind.SECONDARY, null, compact = true) {
                draw.region = null
                refreshHint()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(
            Ui.button(this, "إغلاق", Ui.Kind.GHOST, Ui.Icon.CLOSE, compact = true) { finish() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(actions)

        setContentView(root)
        Ui.apply(this)
        restyle()
        loadPhoto()
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- الواجهة

    private fun setKind(k: InstructionKind) {
        kind = k
        if (k == InstructionKind.IGNORE) placement = Placement.UNSPECIFIED
        restyle()
        refreshHint()
    }

    private fun setPlacement(p: Placement) {
        placement = p
        restyle()
    }

    private fun restyle() {
        for ((k, b) in kindButtons) Ui.style(b, if (k == kind) Ui.Kind.PRIMARY else Ui.Kind.SECONDARY, null, true)
        val showPlace = kind == InstructionKind.RECOGNIZE
        for ((p, b) in placeButtons) {
            b.visibility = if (showPlace) View.VISIBLE else View.GONE
            Ui.style(b, if (p == placement) Ui.Kind.TONAL else Ui.Kind.SECONDARY, null, true)
        }
    }

    private fun refreshHint() {
        hint.text = when {
            photoFile == null && editing == null -> "الصورة غير موجودة."
            draw.region == null ->
                "ارسم مستطيلًا بإصبعك حول الشيء، أو المس إطارًا رماديًا متقطّعًا (ورقة رآها الكاشف) لاختياره. " +
                    "الإطارات الملوّنة تعليمات سابقة على هذه الصورة."
            kind == InstructionKind.RECOGNIZE ->
                "هذه المنطقة هي الفاتورة. اختر موضعها بالنسبة للباب ثم احفظ."
            else ->
                "هذه المنطقة ليست فاتورة (سيتعلم التطبيق تجاهل ما يشبهها). ثم احفظ."
        }
    }

    // ---------------------------------------------------------------- الصورة

    private fun loadPhoto() {
        val f = photoFile
        if (f == null) {
            refreshHint()
            // تعليمة قديمة صورتها محذوفة: تُعدَّل بياناتها الوصفية فقط
            draw.region = editing?.region
            return
        }
        worker.execute {
            val shown = decode(f, 1600)
            val cands = analyzeCandidates(f)
            ui.post {
                if (destroyed) return@post
                draw.bitmap = shown
                draw.candidates = cands
                draw.existing = store.list()
                    .filter { it.photoId == photoId && it.id != editing?.id }
                    .map { it.region to (if (it.kind == InstructionKind.RECOGNIZE) Color.parseColor("#2E7D32") else Color.parseColor("#C62828")) }
                draw.region = editing?.region
                refreshHint()
            }
        }
    }

    private fun decode(f: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** بكسلات الصورة بدقة التحليل (ARGB)، أو null. يصفّرها المستدعي بعد الاستعمال. */
    private fun loadPixels(f: File): Triple<IntArray, Int, Int>? {
        var bmp: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / (sample * 2) >= MAX_ANALYSIS_SIDE) sample *= 2
            bmp = BitmapFactory.decodeFile(
                f.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return Triple(px, bmp.width, bmp.height)
        } catch (e: Throwable) {
            return null
        } finally {
            bmp?.recycle()
        }
    }

    private fun analyzeCandidates(f: File): List<Region> {
        val (px, w, h) = loadPixels(f) ?: return emptyList()
        return try {
            InvoiceAnalyzer.analyzeAll(px, w, h).map { it.region }
        } finally {
            px.fill(0)
        }
    }

    // ---------------------------------------------------------------- الحفظ

    private fun save() {
        val region = draw.region
        val old = editing
        if (region == null) {
            toast("ارسم منطقة أولًا")
            return
        }
        val cleanNote = InstructionCodec.cleanNote(note.text.toString())
        val file = photoFile

        // تعديل بيانات وصفية فقط (الصورة محذوفة، والمنطقة لم تتغير): تبقى العينات المشتقة كما هي
        if (file == null) {
            if (old == null || old.region != region.clamped()) {
                toast("الصورة غير موجودة؛ لا يمكن تغيير المنطقة")
                return
            }
            store.update(old.copy(kind = kind, placement = placementFor(kind), note = cleanNote))
            toast("حُفظ التعديل")
            finish()
            return
        }

        saveButton.isEnabled = false
        worker.execute {
            val loaded = loadPixels(file)
            val samples = if (loaded == null) null else {
                val (px, w, h) = loaded
                try {
                    val all = InvoiceAnalyzer.analyzeAll(px, w, h)
                    val within = InvoiceAnalyzer.analyzeWithin(px, w, h, region)
                    InstructionSampler.derive(kind, region, all, within)
                } finally {
                    px.fill(0)
                }
            }
            ui.post {
                if (destroyed) return@post
                saveButton.isEnabled = true
                when {
                    samples == null -> toast("تعذّر قراءة الصورة")
                    else -> {
                        val ins = TrainingInstruction(
                            id = old?.id ?: InstructionStore.newId(),
                            kind = kind,
                            region = region.clamped(),
                            placement = placementFor(kind),
                            photoId = photoId,
                            note = cleanNote,
                            createdAt = old?.createdAt ?: System.currentTimeMillis(),
                            samples = samples
                        )
                        if (old == null) store.add(ins) else store.update(ins)
                        toast(
                            "حُفظت التعليمة: ${ins.positives} مثال «فاتورة» و${ins.negatives} مثال «ليست فاتورة». " +
                                "الكاشف الجديد يتعلم من المربع نفسه مباشرة. اضغط «درّب الآن» في قائمة التعليمات لتطبيقها."
                        )
                        finish()
                    }
                }
            }
        }
    }

    private fun placementFor(k: InstructionKind) =
        if (k == InstructionKind.RECOGNIZE) placement else Placement.UNSPECIFIED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

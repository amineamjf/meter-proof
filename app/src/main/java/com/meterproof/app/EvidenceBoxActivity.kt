package com.meterproof.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.evidence.BoxKind
import com.meterproof.app.evidence.EvidenceBox
import com.meterproof.app.evidence.EvidenceBoxStore
import com.meterproof.app.privacy.Region
import java.io.File
import java.util.concurrent.Executors

/**
 * تعليم «دليل الوصول» على صورة: يرسم المالك مستطيلًا حول صندوق العداد (أو الباب)، أو يعلّم أن الصورة بلا ذلك.
 * يحفظ إحداثيات نسبية فقط في مجلد مستقل عن تدريب الفواتير. لا يغيّر الصورة ولا السجل المبصوم.
 * هذه الخطوة جمع أمثلة فقط؛ التدريب نفسه والقص والتغبيش خطوات لاحقة.
 *
 * Extras: photoId (مطلوب).
 */
class EvidenceBoxActivity : Activity() {

    companion object {
        const val EXTRA_PHOTO = "photoId"
    }

    private lateinit var evidence: EvidenceStore
    private lateinit var store: EvidenceBoxStore
    private lateinit var draw: RegionDrawView
    private lateinit var hint: TextView
    private lateinit var kindButtons: Map<BoxKind, Button>

    private var photoId = ""
    private var kind = BoxKind.METER_BOX
    private var photoFile: File? = null

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        evidence = EvidenceStore(this)
        store = EvidenceBoxStore(File(applicationContext.filesDir, EvidenceBoxStore.DIR_NAME))
        photoId = intent.getStringExtra(EXTRA_PHOTO) ?: ""
        val f = if (photoId.isEmpty()) null else evidence.imageFile(photoId)
        photoFile = if (f != null && f.exists()) f else null
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

        val kindRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val kb = LinkedHashMap<BoxKind, Button>()
        for (k in BoxKind.values()) {
            val b = Ui.button(this, k.arabic, Ui.Kind.SECONDARY, null, compact = true) { setKind(k) }
            kb[k] = b
            kindRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        kindButtons = kb
        root.addView(kindRow)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(
            Ui.button(this, "حفظ المربع", Ui.Kind.PRIMARY, Ui.Icon.CHECK, compact = true) { saveBox() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row1.addView(
            Ui.button(this, "مسح الرسم", Ui.Kind.SECONDARY, null, compact = true) { draw.region = null; refreshHint() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(
            Ui.button(this, "لا يوجد في هذه الصورة", Ui.Kind.TONAL, null, compact = true) { markNone() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row2.addView(
            Ui.button(this, "حذف مربعات هذا النوع هنا", Ui.Kind.DANGER, Ui.Icon.TRASH, compact = true) { deleteKindHere() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(row2)
        root.addView(
            Ui.button(this, "إغلاق", Ui.Kind.GHOST, Ui.Icon.CLOSE, compact = true) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

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

    private fun setKind(k: BoxKind) {
        kind = k
        restyle()
        refreshHint()
    }

    private fun restyle() {
        for ((k, b) in kindButtons) Ui.style(b, if (k == kind) Ui.Kind.PRIMARY else Ui.Kind.SECONDARY, null, true)
    }

    private fun refreshHint() {
        val c = store.counts(kind)
        val here = store.boxesOf(photoId).count { it.kind == kind }
        val none = store.noBoxMarks(photoId).any { it.kind == kind }
        val state = when {
            here > 0 -> "على هذه الصورة $here مربع من هذا النوع."
            none -> "هذه الصورة معلَّمة: لا يوجد ${kind.arabic}."
            else -> "هذه الصورة غير معلَّمة لهذا النوع بعد."
        }
        hint.text = (if (photoFile == null) "الصورة غير موجودة.\n" else "") +
            "ارسم مستطيلًا حول ${kind.arabic} ثم «حفظ المربع». $state\n" +
            "المجموع (${kind.arabic}): ${c.positivePhotos} صورة فيها، ${c.negativePhotos} صورة بلا. " +
            "للتجربة الأولى يلزم ${com.meterproof.app.evidence.BoxCounts.MIN_POSITIVE} و${com.meterproof.app.evidence.BoxCounts.MIN_NEGATIVE} على الأقل، ومتنوعة."
    }

    private fun loadPhoto() {
        val f = photoFile
        if (f == null) { refreshHint(); return }
        worker.execute {
            val shown = decode(f, 1600)
            ui.post {
                if (destroyed) return@post
                draw.bitmap = shown
                showExisting()
                refreshHint()
            }
        }
    }

    private fun showExisting() {
        draw.existing = store.boxesOf(photoId).map {
            it.region to (if (it.kind == BoxKind.METER_BOX) Color.parseColor("#1565C0") else Color.parseColor("#EF6C00"))
        }
    }

    private fun decode(f: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun saveBox() {
        val r: Region = draw.region ?: return toast("ارسم مستطيلًا أولًا")
        if (photoFile == null) return toast("الصورة غير موجودة")
        val ok = store.addBox(EvidenceBox(EvidenceBoxStore.newId(), kind, r.clamped(), photoId, System.currentTimeMillis()))
        if (!ok) return toast("المربع صغير جدًا")
        draw.region = null
        showExisting()
        refreshHint()
        toast("حُفظ مربع ${kind.arabic}. يمكنك رسم مربع آخر أو إغلاق الشاشة.")
    }

    private fun markNone() {
        if (photoFile == null) return toast("الصورة غير موجودة")
        store.markNoBox(photoId, kind, System.currentTimeMillis())
        draw.region = null
        showExisting()
        refreshHint()
        toast("عُلّمت: لا يوجد ${kind.arabic} في هذه الصورة")
    }

    private fun deleteKindHere() {
        val mine = store.boxesOf(photoId).filter { it.kind == kind }
        if (mine.isEmpty() && store.noBoxMarks(photoId).none { it.kind == kind }) return toast("لا شيء لحذفه")
        mine.forEach { store.deleteBox(it.id) }
        store.clearNoBox(photoId, kind)
        showExisting()
        refreshHint()
        toast("حُذف تعليم ${kind.arabic} عن هذه الصورة")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

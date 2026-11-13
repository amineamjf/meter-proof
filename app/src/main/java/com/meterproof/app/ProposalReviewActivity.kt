package com.meterproof.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.delivery.DeliveryState
import com.meterproof.app.delivery.DeliveryStore
import com.meterproof.app.delivery.InstructionKind
import com.meterproof.app.delivery.InstructionStore
import com.meterproof.app.delivery.InstructionStores
import com.meterproof.app.delivery.Placement
import com.meterproof.app.delivery.TrainingInstruction
import com.meterproof.app.delivery.WindowDetector
import java.util.concurrent.Executors

/**
 * تعلّم بنقرة: يقترح التطبيق مربعًا على صور لم تُعلَّم بعد (بكاشفه الحالي)، وتضغط:
 * «فاتورة» (يصير تعليمة «فاتورة» بذلك المربع، ويمكنك تصحيح المربع بالرسم قبل الضغط)،
 * «ليس فاتورة» (تعليمة «تجاهل» لهذا المربع)، «لا فاتورة في الصورة» (تصنيف الصورة)، أو «تخطي».
 * كل نقرة تصير مثال تدريب، ثم «درّب الآن» في القائمة يتعلم منها.
 */
class ProposalReviewActivity : Activity() {

    private lateinit var evidence: EvidenceStore
    private lateinit var instructions: InstructionStore
    private lateinit var draw: RegionDrawView
    private lateinit var status: TextView

    private val queue = ArrayDeque<String>()
    private var total = 0
    private var current: String? = null
    private var done = 0

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var destroyed = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        evidence = EvidenceStore(this)
        instructions = InstructionStores.forTask(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(4)) }
        root.addView(status)
        draw = RegionDrawView(this)
        root.addView(draw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(
            Ui.button(this, "✔ فاتورة", Ui.Kind.PRIMARY, null, compact = true) { accept() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row1.addView(
            Ui.button(this, "✘ ليس فاتورة", Ui.Kind.DANGER, null, compact = true) { reject() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(
            Ui.button(this, "لا فاتورة في الصورة", Ui.Kind.TONAL, null, compact = true) { noInvoice() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row2.addView(
            Ui.button(this, "تخطي", Ui.Kind.SECONDARY, null, compact = true) { skip() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(row2)
        root.addView(Ui.button(this, "إغلاق", Ui.Kind.GHOST, Ui.Icon.CLOSE, compact = true) { finish() })
        setContentView(root)
        Ui.apply(this)

        status.text = "جارٍ تجهيز الصور..."
        worker.execute {
            val ids = try {
                val labels = PhotoLabels.latest(evidence.events())
                val covered = instructions.list().map { it.photoId }.toSet()
                val flags = evidence.readRecords().associate { it.optString("id") to it.optString("flags") }
                evidence.readRecords().reversed().map { it.optString("id") }.filter { id ->
                    id.isNotEmpty() && id !in labels && id !in covered &&
                        evidence.imageFile(id).exists() && PhotoLabels.usableForTraining(flags[id] ?: "")
                }
            } catch (e: Exception) {
                emptyList()
            }
            ui.post {
                queue.addAll(ids)
                total = ids.size
                next()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdown()
        super.onDestroy()
    }

    private fun next() {
        current = null
        draw.bitmap = null
        draw.region = null
        val id = queue.removeFirstOrNull()
        if (id == null) {
            status.text = "انتهت الصور المتاحة. عُلّمت $done صورة. اضغط «درّب الآن» في القائمة للتعلّم منها."
            return
        }
        status.text = "جارٍ الاقتراح... (${done + 1} من $total)"
        worker.execute {
            val loaded = WindowTraining.decode(evidence.imageFile(id))
            if (loaded == null) {
                ui.post { if (!destroyed) next() }
                return@execute
            }
            val (px, w, h) = loaded
            var bmp: Bitmap? = null
            var text = ""
            var region: com.meterproof.app.privacy.Region? = null
            try {
                val ds = DeliveryStore(this)
                val model = ds.windowModel()
                val verifier = ds.verifier()
                if (model != null) {
                    val det = WindowDetector(model, verifier = verifier, semantic = if (verifier != null) SemanticLabeler else null)
                    val r = det.detect(px, w, h)
                    region = r.region
                    text = when (r.state) {
                        DeliveryState.DETECTED -> "اقتراح التطبيق: فاتورة (ثقة ${(r.score * 100).toInt()}%)"
                        DeliveryState.UNCERTAIN -> "اقتراح التطبيق: غير متأكد (${(r.score * 100).toInt()}%)"
                        DeliveryState.NONE -> "لم يجد التطبيق فاتورة؛ هذا أقرب مكان (${(r.score * 100).toInt()}%)"
                    }
                }
                bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) {
                text = "تعذّر الاقتراح"
            } finally {
                px.fill(0)
            }
            ui.post {
                if (destroyed) return@post
                current = id
                draw.bitmap = bmp
                draw.region = region
                status.text = "(${done + 1} من $total) $text\nيمكنك رسم مربع بإصبعك لتصحيح المكان قبل الضغط."
            }
        }
    }

    private fun save(kind: InstructionKind) {
        val id = current ?: return
        val r = draw.region
        if (r == null) {
            Toast.makeText(this, "لا مربع: ارسم مربعًا بإصبعك أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        instructions.add(
            TrainingInstruction(
                id = InstructionStore.newId(), kind = kind, region = r.clamped(),
                placement = Placement.UNSPECIFIED, photoId = id, note = "",
                createdAt = System.currentTimeMillis(), samples = emptyList()
            )
        )
        done++
        next()
    }

    private fun accept() = save(InstructionKind.RECOGNIZE)

    private fun reject() = save(InstructionKind.IGNORE)

    private fun noInvoice() {
        val id = current ?: return
        val detail = PhotoLabels.detail(id, PhotoLabels.Label.NONE)
        if (detail != null) {
            evidence.addEvent(PhotoLabels.EVENT, System.currentTimeMillis(), null, null, null, detail)
        }
        done++
        next()
    }

    private fun skip() {
        next()
    }
}

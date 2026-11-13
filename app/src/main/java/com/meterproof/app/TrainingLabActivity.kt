package com.meterproof.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.delivery.DeliveryStore
import com.meterproof.app.delivery.InstructionKind
import com.meterproof.app.delivery.InstructionStores
import com.meterproof.app.delivery.ModelLabels
import com.meterproof.app.delivery.ModelRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مختبر التدريب: مدخل واحد لتدريب نظام الفواتير. يعرض عدد العينات لكل فئة، والنموذج المعتمد (مواصفاته ومقاييسه
 * ومصفوفة الالتباس)، وتاريخ الإصدارات وقراراتها، مع التدريب والرجوع لإصدار سابق وإعادة ضبط بيانات التدريب.
 * نظام العدادات مفصول تمامًا ومؤجَّل؛ لا بيانات له هنا.
 */
class TrainingLabActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var trainButton: android.widget.Button
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var training = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }
        root.addView(TextView(this).apply { text = "مختبر التدريب — نظام الفواتير"; textSize = 18f })
        info = TextView(this).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(ScrollView(this).apply { addView(info) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun row(vararg views: View) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (v in views) r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(r)
        }
        trainButton = Ui.button(this, "درّب النموذج", Ui.Kind.PRIMARY, Ui.Icon.SPARKLE, compact = true) { train() }
        row(
            trainButton,
            Ui.button(this, "الصور والتصنيف", Ui.Kind.TONAL, Ui.Icon.TAG, compact = true) {
                startActivity(Intent(this, PhotoGalleryActivity::class.java))
            }
        )
        row(
            Ui.button(this, "التعليمات (مربعات)", Ui.Kind.TONAL, Ui.Icon.TAG, compact = true) {
                startActivity(Intent(this, InstructionListActivity::class.java))
            },
            Ui.button(this, "اقتراحات بنقرة", Ui.Kind.TONAL, Ui.Icon.SPARKLE, compact = true) {
                if (DeliveryStore(this).windowModel() == null) {
                    Toast.makeText(this, "درّب أولًا ليقترح التطبيق مربعات", Toast.LENGTH_LONG).show()
                } else startActivity(Intent(this, ProposalReviewActivity::class.java))
            }
        )
        row(
            Ui.button(this, "الرجوع لإصدار سابق", Ui.Kind.SECONDARY, null, compact = true) { rollback() },
            Ui.button(this, "إعادة ضبط التدريب", Ui.Kind.DANGER, null, compact = true) { confirmReset() }
        )
        root.addView(Ui.button(this, "إغلاق", Ui.Kind.GHOST, Ui.Icon.CLOSE, compact = true) { finish() })
        setContentView(root)
        Ui.apply(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun fmt(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t))

    private fun pct(f: Float) = "${(f * 100).toInt()}%"

    private fun refresh() {
        val ds = DeliveryStore(this)
        val evidence = EvidenceStore(this)
        val ins = InstructionStores.forTask(this).list()
        val labels = PhotoLabels.latest(evidence.events())
        val c = PhotoLabels.counts(labels)
        val sb = StringBuilder()
        sb.append("العينات (نظام الفواتير فقط):\n")
        sb.append("• صور فيها مربع «فاتورة»: ").append(ins.filter { it.kind == InstructionKind.RECOGNIZE }.map { it.photoId }.toSet().size).append('\n')
        sb.append("• تعليمات «تجاهل»: ").append(ins.count { it.kind == InstructionKind.IGNORE }).append('\n')
        sb.append("• صور مصنَّفة: فاتورة ").append(c.invoice).append("، بلا فاتورة ").append(c.none)
            .append("، ورقة مشابهة ").append(c.similar).append("، غير واضح ").append(c.unclear).append('\n')
        sb.append("• نظام العدادات: غير مفعّل (مؤجل، بياناته منفصلة تمامًا)\n\n")

        val reg = ds.registry
        ds.windowModel() // يهاجر الملفات القديمة إن وُجدت
        val stable = reg.stable()
        if (stable == null) {
            sb.append("النموذج المعتمد: لا يوجد بعد.\n")
        } else {
            val m = stable.meta
            sb.append("النموذج المعتمد: إصدار ").append(m.version).append(" — ").append(fmt(m.trainedAt)).append('\n')
            sb.append("الجهاز: ").append(m.device).append(" | العينات: فاتورة ").append(m.invoiceImages)
                .append("، بلا فاتورة ").append(m.noneImages).append('\n')
            sb.append("Precision ").append(pct(m.precision)).append(" | Recall ").append(pct(m.recall))
                .append(" | F1 ").append(pct(m.f1)).append('\n')
            sb.append("مصفوفة الالتباس (صور): أُصيبت ").append(m.tp).append("، فُوّتت ").append(m.fn)
                .append("، قُبلت خطأً ").append(m.fp).append("، رُفضت صوابًا ").append(m.tn).append('\n')
            if (m.holdoutInvoice >= 0) {
                sb.append("صور محجوزة: اكتُشفت ").append(m.holdoutDetected).append(" من ").append(m.holdoutInvoice)
                    .append("، وقُبلت خطأً ").append(m.holdoutFalse).append(" من ").append(m.holdoutNone).append('\n')
            }
            sb.append("المدقِّق الدلالي: ").append(if (m.hasVerifier) "نعم" else "لا").append(" | الإعدادات: ").append(m.settings).append('\n')
            sb.append("تنبيه: أرقام من أمثلتك أنت لا من الميدان؛ تتغير مع الإضاءة والأبواب. لا تعدّها دقة نهائية.\n")
        }
        val all = reg.list()
        if (all.isNotEmpty()) {
            sb.append("\nالإصدارات:\n")
            for (e in all) {
                val m = e.meta
                sb.append(if (stable?.meta?.version == m.version) "★ " else "• ")
                    .append("v").append(m.version).append(" ").append(fmt(m.trainedAt)).append(" — ")
                    .append(if (m.accepted) "معتمد" else "مرفوض").append(" (")
                    .append(ModelLabels.reasonArabic(m.reason)).append(") P ").append(pct(m.precision))
                    .append(" R ").append(pct(m.recall)).append('\n')
            }
        }
        info.text = sb.toString()
    }

    private fun train() {
        if (training) return
        training = true
        trainButton.isEnabled = false
        Toast.makeText(this, "جارٍ التدريب (قد يستغرق عدة دقائق)...", Toast.LENGTH_LONG).show()
        Thread {
            val outcome = try { WindowTraining.run(this) } catch (e: Throwable) { null }
            ui.post {
                training = false
                trainButton.isEnabled = true
                val msg = when {
                    outcome == null -> "تعذّر التدريب (ذاكرة أو صور تالفة). لم يتغير شيء."
                    outcome.decision != null ->
                        if (outcome.decision.accepted) "اعتُمد إصدار جديد رقم ${outcome.decision.entry.meta.version}."
                        else "رُفض الإصدار الجديد: ${ModelLabels.reasonArabic(outcome.decision.reason)}. المستقر السابق باقٍ."
                    else -> "لم يبلغ النموذج الجديد الهدف، والمستقر السابق (إن وُجد) باقٍ. " +
                        "التفاصيل في «التعليمات (مربعات) ← درّب الآن»."
                }
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("حسنًا", null).show()
                refresh()
            }
        }.start()
    }

    private fun rollback() {
        val reg = DeliveryStore(this).registry
        val cur = reg.stable()
        if (cur == null) {
            Toast.makeText(this, "لا نموذج معتمد", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("الرجوع من الإصدار ${cur.meta.version} إلى آخر إصدار معتمد قبله؟")
            .setPositiveButton("رجوع") { _, _ ->
                val back = reg.rollback()
                Toast.makeText(
                    this, if (back != null) "اعتُمد الإصدار ${back.meta.version}" else "لا إصدار سابق معتمد",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("إعادة ضبط تدريب الفواتير")
            .setMessage(
                "يُحذف: كل التعليمات والمربعات، وكل إصدارات النماذج، وعينات المعايرة. " +
                    "الصور لا تُحذف وتصنيفاتها تبقى في سجل الأدلة (لا يُعدَّل السجل). ويتوقف الكشف حتى تدرّب من جديد."
            )
            .setPositiveButton("حذف") { _, _ ->
                InstructionStores.forTask(this).clear()
                DeliveryStore(this).clear()
                Settings(this).deliveryDetection = false
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

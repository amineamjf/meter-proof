package com.meterproof.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.delivery.DeliveryStore
import com.meterproof.app.delivery.InvoiceTrainer
import com.meterproof.app.delivery.TrainingState
import com.meterproof.app.delivery.TrainingStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «حالة التدريب»: كيف يسير تدريب كاشف الفواتير وإلى أين وصل، وما ينقص، وهل هو مفعّل ولماذا.
 * لا تُغيّر شيئًا إلا بضغطة صريحة (تدريب أو تفعيل).
 */
class TrainingStatusActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var store: DeliveryStore

    private lateinit var headline: TextView
    private lateinit var posLabel: TextView
    private lateinit var negLabel: TextView
    private lateinit var posBar: ProgressBar
    private lateinit var negBar: ProgressBar
    private lateinit var accLabel: TextView
    private lateinit var accBar: ProgressBar
    private lateinit var chart: AccuracyChartView
    private lateinit var lastLabel: TextView
    private lateinit var missingView: TextView
    private lateinit var detectorView: TextView
    private lateinit var facesWarning: TextView
    private lateinit var enableButton: Button
    private lateinit var trainButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = DeliveryStore(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, bold: Boolean = false, size: Float = 15f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = if (bold) maxOf(size, 17f) else size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun bar(): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14))
        }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }

        col.addView(label("حالة التدريب", true))
        headline = label("", true, 18f)
        col.addView(headline)

        col.addView(label("عينات المعايرة (الحد الأدنى 15 من كل فئة)", true))
        posLabel = label("")
        posBar = bar()
        negLabel = label("")
        negBar = bar()
        col.addView(posLabel)
        col.addView(posBar)
        col.addView(negLabel)
        col.addView(negBar)

        col.addView(label("الدقة", true))
        accLabel = label("")
        accBar = bar().apply { max = 100 }
        col.addView(accLabel)
        col.addView(accBar)
        chart = AccuracyChartView(this)
        col.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(
            label(
                "الدقة تُقاس بالتحقق المتقاطع على عيناتك أنت، وليست دقة ميدانية؛ قد تتغير مع الإضاءة وأنواع الورق.",
                false, 12f
            )
        )

        lastLabel = label("")
        col.addView(lastLabel)

        col.addView(label("ما ينقصك", true))
        missingView = label("")
        col.addView(missingView)

        col.addView(label("الكاشف", true))
        detectorView = label("")
        col.addView(detectorView)
        facesWarning = label("").apply { setTextColor(Color.parseColor("#B45309")) }
        col.addView(facesWarning)

        trainButton = button("تدريب ومعايرة الآن") { train() }
        col.addView(trainButton)
        enableButton = button("تفعيل الكاشف") { TrainingActions.enable(this) { refresh() } }
        col.addView(enableButton)
        col.addView(button("التقاط عينات وإدارة المعايرة") {
            startActivity(Intent(this, DeliveryActivity::class.java))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F3F5F7"))
            addView(col)
        })
    }

    private fun refresh() {
        val st: TrainingState = TrainingStatus.compute(
            samples = store.samples(),
            history = store.history(),
            model = store.model(),
            detectionOn = settings.deliveryDetection,
            privacyFull = settings.privacyMode == "FULL"
        )

        headline.text = st.headline
        headline.setTextColor(
            when {
                st.ready -> Color.parseColor("#2E7D32")
                st.positives + st.negatives == 0 -> Color.parseColor("#475467")
                else -> Color.parseColor("#B45309")
            }
        )

        val min = st.minEach
        posBar.max = min
        posBar.progress = minOf(st.positives, min)
        posLabel.text = "فاتورة ظاهرة: ${st.positives} من $min" + if (st.positives >= min) " ✓" else ""
        negBar.max = min
        negBar.progress = minOf(st.negatives, min)
        negLabel.text = "بلا فاتورة: ${st.negatives} من $min" + if (st.negatives >= min) " ✓" else ""

        val p = st.precision
        val target = TrainingStatus.pctFloor(st.targetPrecision)
        if (p == null) {
            accLabel.text = "الدقة الحالية: لم تُقَس بعد (الهدف $target%)"
            accBar.progress = 0
        } else {
            val pc = TrainingStatus.pctFloor(p)
            accLabel.text = "الدقة الحالية: $pc% مقابل الهدف $target%" + if (pc >= target) " ✓" else ""
            accBar.progress = pc
        }
        chart.setData(st.precisionHistory, st.targetPrecision)

        val last = st.lastTrainAt
        lastLabel.text = if (last == null) {
            "آخر تدريب: لم يجرِ تدريب بعد، والعينات المضافة: ${st.positives + st.negatives}"
        } else {
            "آخر تدريب: ${dateFormat.format(Date(last))}، عينات أُضيفت منذ ذلك الحين: ${st.newSamplesSinceTrain}"
        }

        missingView.text = if (st.missing.isEmpty()) {
            "لا ينقصك شيء: الشرطان محققان (العينات والدقة)."
        } else {
            st.missing.joinToString("\n") { "• $it" }
        }

        detectorView.text = if (st.effective) {
            "الكاشف مفعّل ويعمل."
        } else {
            "الكاشف لا يعمل الآن. " + (st.disabledReason ?: "")
        }
        facesWarning.text = if (st.facesOnlyWarning) {
            "تنبيه: الكشف التلقائي معطّل في وضع «وجوه فقط» لأنه يعتمد على بوابة الخصوصية الكاملة. " +
                "حتى لو كان الكاشف جاهزًا ومفعّلًا فلن يعمل إلا بعد التحويل إلى الخصوصية الكاملة."
        } else {
            ""
        }
        facesWarning.visibility = if (st.facesOnlyWarning) View.VISIBLE else View.GONE

        // زر واحد للتفعيل: يظهر فقط حين يكون النموذج جاهزًا ولم يُفعَّل (أو فُعِّل ولا يعمل بسبب الخصوصية)
        enableButton.visibility = if (st.ready && !st.effective) View.VISIBLE else View.GONE
        enableButton.text = if (st.detectionOn) "تشغيل الكاشف فعليًا (تحويل الخصوصية إلى الكاملة)" else "تفعيل الكاشف"
    }

    private fun train() {
        trainButton.isEnabled = false
        Toast.makeText(this, "جارٍ التدريب...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = store.train()
            runOnUiThread {
                trainButton.isEnabled = true
                if (result is InvoiceTrainer.Result.NotReady) {
                    // الأمان: إن لم يصلح النموذج يتوقف الكشف (كما في شاشة المعايرة)
                    settings.deliveryDetection = false
                }
                refresh()
                TrainingNotice.maybeShow(this)
            }
        }.start()
    }
}

/** أفعال مشتركة لتفعيل الكاشف من أكثر من شاشة. */
object TrainingActions {

    /**
     * تفعيل الكاشف بزر واحد. الكشف التلقائي لا يعمل إلا في وضع الخصوصية الكاملة، فإن كان الوضع
     * «وجوه فقط» يُسأل المستخدم صراحةً قبل تحويله، ولا يتغير الوضع بصمت.
     */
    fun enable(activity: Activity, onDone: () -> Unit) {
        val s = Settings(activity)
        val store = DeliveryStore(activity)
        if (store.model() == null) {
            Toast.makeText(activity, "لا يمكن التفعيل قبل تدريب نموذج جاهز", Toast.LENGTH_LONG).show()
            return
        }
        if (s.privacyMode == "FULL") {
            s.deliveryDetection = true
            onDone()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("تفعيل الكاشف")
            .setMessage(
                "الكشف التلقائي يعمل فقط في وضع الخصوصية الكاملة (تُجرَّد الصور وتُتلف اللقطة التي فيها شخص). " +
                    "وضعك الحالي «طمس الوجوه فقط»، وصورك واضحة.\n\nماذا تريد؟"
            )
            .setPositiveButton("فعّل وحوّل إلى الكاملة") { _, _ ->
                s.privacyMode = "FULL"
                s.deliveryDetection = true
                onDone()
            }
            .setNeutralButton("فعّل فقط (لن يعمل الآن)") { _, _ ->
                s.deliveryDetection = true
                onDone()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

/** إشعار داخل التطبيق عند بلوغ الشرطين (العينات والدقة): مرة واحدة لكل نموذج. */
object TrainingNotice {

    fun maybeShow(activity: Activity) {
        if (activity.isFinishing) return
        val s = Settings(activity)
        val store = DeliveryStore(activity)
        val model = store.model() ?: return
        val st = TrainingStatus.compute(
            store.samples(), store.history(), model, s.deliveryDetection, s.privacyMode == "FULL"
        )
        if (!st.ready || s.deliveryDetection) return
        if (s.trainReadyNotified == model.id) return
        s.trainReadyNotified = model.id

        AlertDialog.Builder(activity)
            .setTitle("الكاشف جاهز")
            .setMessage(
                "بلغ الشرطان: العينات كافية (${st.positives} فاتورة ظاهرة و${st.negatives} بلا فاتورة) " +
                    "والدقة ${TrainingStatus.pctFloor(st.precision ?: 0f)}% بلغت الهدف " +
                    "${TrainingStatus.pctFloor(st.targetPrecision)}%.\n\nهل تفعّله الآن؟"
            )
            .setPositiveButton("تفعيل الآن") { _, _ -> TrainingActions.enable(activity) { } }
            .setNeutralButton("عرض الحالة") { _, _ ->
                if (activity !is TrainingStatusActivity) {
                    activity.startActivity(Intent(activity, TrainingStatusActivity::class.java))
                }
            }
            .setNegativeButton("لاحقًا", null)
            .show()
    }
}

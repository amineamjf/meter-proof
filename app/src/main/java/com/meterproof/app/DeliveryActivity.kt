package com.meterproof.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.delivery.DeliveryStore
import com.meterproof.app.delivery.InvoiceTrainer
import com.meterproof.app.delivery.SampleCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * شاشة كشف تسليم الفواتير: التقاط عينات المعايرة، التدريب، وتفعيل الكشف.
 *
 * لا تُحفظ أي صورة هنا. كل عينة تمر على بوابة الخصوصية، وإن سلمت تُحفظ سمات رقمية فقط.
 */
class DeliveryActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var store: DeliveryStore
    private lateinit var statusView: TextView
    private lateinit var enableButton: Button
    private lateinit var outlineButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500L)
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

    private fun label(text: String, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = if (bold) 17f else 15f
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }

        col.addView(label("كشف تسليم الفواتير", true))
        col.addView(
            label(
                "يتعرف التطبيق على ورقة الفاتورة في الصورة بعد معايرته على أوراقك أنت. " +
                    "لا تُحفظ أي صورة للمعايرة: تُحفظ أرقام فقط، ولا تغادر الجهاز."
            )
        )

        statusView = TextView(this).apply { textSize = 14f }
        col.addView(statusView)

        col.addView(label("1) عينات المعايرة", true))
        col.addView(
            label(
                "ضع فاتورة على باب أو طاولة والإطار خالٍ من الأشخاص، ووجّه كاميرا الهاتف إليها كما تُحمل عند التوزيع. " +
                    "التقط 15 عينة على الأقل بفواتير وإضاءات وأبواب مختلفة، ثم 15 عينة على الأقل بلا فاتورة " +
                    "(جدران، أبواب، لافتات، أوراق أخرى). إن ظهر وجه أو شخص تُرفض العينة تلقائيًا."
            )
        )
        col.addView(button("التقاط عينة: فاتورة ظاهرة") { CaptureService.calibrate(this, true) })
        col.addView(button("التقاط عينة: بلا فاتورة") { CaptureService.calibrate(this, false) })

        col.addView(label("2) التدريب والمعايرة", true))
        col.addView(button("تدريب ومعايرة من العينات") { train() })

        col.addView(label("3) التفعيل", true))
        enableButton = button("") { toggleEnabled() }
        col.addView(enableButton)
        outlineButton = button("") { toggleOutline() }
        col.addView(outlineButton)

        col.addView(label("4) البيانات", true))
        col.addView(button("مسح كل بيانات المعايرة") { confirmClear() })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    // ---------------------------------------------------------------- العرض

    private fun refresh() {
        val samples = store.samples()
        val pos = samples.count { it.positive }
        val neg = samples.size - pos
        val model = store.model()

        val sb = StringBuilder()
        sb.append("العينات: فاتورة ظاهرة ").append(pos).append("، بلا فاتورة ").append(neg)
            .append(" (المطلوب 15 على الأقل من كل نوع)\n")
        if (model != null) {
            sb.append("النموذج: جاهز (معرّف ").append(model.id).append(")\n")
            sb.append("الدقة المقاسة بالتحقق المتقاطع على عيناتك: ")
                .append(SampleCodec.pct(model.metrics.precision)).append("%، الاستدعاء: ")
                .append(SampleCodec.pct(model.metrics.recall)).append("%\n")
            sb.append("تنبيه: هذه ليست دقة ميدانية؛ تتغير مع الإضاءة وأنواع الورق، فأعد المعايرة عند الحاجة.\n")
        } else {
            sb.append("النموذج: غير جاهز (لا كشف قبل المعايرة)\n")
        }
        sb.append("الكشف: ")
            .append(
                when {
                    settings.deliveryDetection && model != null -> "مفعّل"
                    settings.deliveryDetection -> "مفعّل لكن بلا نموذج صالح، فهو متوقف"
                    else -> "معطّل"
                }
            ).append("\n")

        val last = settings.calibResult
        if (last.isNotEmpty()) {
            val code = last.substringBefore(';')
            val at = last.substringAfter(';', "").toLongOrNull()
            sb.append("آخر عينة")
            if (at != null) sb.append(" (").append(timeFormat.format(Date(at))).append(")")
            sb.append(": ").append(calibMessage(code)).append("\n")
        }
        if (settings.lastError.isNotEmpty()) {
            sb.append("⚠ آخر خطأ: ").append(settings.lastError).append("\n")
        }
        statusView.text = sb.toString()

        enableButton.text = if (settings.deliveryDetection) "إيقاف كشف التسليم" else "تفعيل كشف التسليم"
        outlineButton.text = if (settings.deliveryOutlineOnly) {
            "محتوى الفاتورة: مخفي (إطار فقط). اضغط لإظهاره"
        } else {
            "محتوى الفاتورة: ظاهر في الصورة المحفوظة. اضغط لإخفائه (إطار فقط)"
        }
    }

    private fun calibMessage(code: String): String = when (code) {
        "SAVED_POS" -> "حُفظت عينة فاتورة (أرقام فقط)"
        "SAVED_NEG" -> "حُفظت عينة بلا فاتورة (أرقام فقط)"
        "NO_PAPER" -> "لم تُرَ ورقة في الإطار، لم تُحفظ العينة. قرّب الفاتورة وحسّن الإضاءة وأعد المحاولة"
        "NEG_NO_CANDIDATE" -> "لا مرشح ورقة في الإطار (لا يلزم حفظ هذه العينة، فالكاشف لن يقيّمها أصلًا)"
        "HAZARD" -> "ظهر شخص أو وجه (أو شُك في ذلك)، رُفضت العينة ولم يُحفظ شيء"
        "FAILED" -> "فشلت المعالجة أو الكاميرا، لم تُحفظ العينة"
        else -> code
    }

    private fun reasonText(reason: String): String = when (reason) {
        "NEED_MORE_POSITIVES" -> "عينات الفاتورة الظاهرة أقل من الحد الأدنى"
        "NEED_MORE_NEGATIVES" -> "عينات «بلا فاتورة» أقل من الحد الأدنى"
        "LOW_PRECISION" -> "لا يفصل النموذج الفاتورة عن غيرها بدقة كافية (95% مطلوبة). أضف عينات أوضح وأكثر تنوعًا"
        "LOW_RECALL" -> "يفوّت النموذج فواتير كثيرة عند مستوى الدقة المطلوب. أضف عينات وحسّن الإضاءة"
        else -> reason
    }

    // ---------------------------------------------------------------- الأفعال

    private fun train() {
        Toast.makeText(this, "جارٍ التدريب...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = store.train()
            runOnUiThread {
                when (result) {
                    is InvoiceTrainer.Result.Ready -> {
                        val m = result.model.metrics
                        Toast.makeText(
                            this,
                            "تم: دقة ${SampleCodec.pct(m.precision)}% واستدعاء ${SampleCodec.pct(m.recall)}% (تحقق متقاطع)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is InvoiceTrainer.Result.NotReady -> {
                        settings.deliveryDetection = false
                        AlertDialog.Builder(this)
                            .setTitle("النموذج غير جاهز، وأُوقف الكشف")
                            .setMessage(result.reasons.joinToString("\n") { "• " + reasonText(it) })
                            .setPositiveButton("حسنًا", null)
                            .show()
                    }
                }
                refresh()
            }
        }.start()
    }

    private fun toggleEnabled() {
        if (settings.deliveryDetection) {
            settings.deliveryDetection = false
        } else if (store.model() == null) {
            Toast.makeText(this, "لا يمكن التفعيل قبل تدريب نموذج جاهز", Toast.LENGTH_LONG).show()
        } else {
            settings.deliveryDetection = true
        }
        refresh()
    }

    private fun toggleOutline() {
        settings.deliveryOutlineOnly = !settings.deliveryOutlineOnly
        refresh()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("مسح بيانات المعايرة؟")
            .setMessage("تُمسح كل العينات والنموذج ويتوقف الكشف. لا يمس هذا الصور والأدلة المحفوظة.")
            .setPositiveButton("مسح") { _, _ ->
                store.clear()
                settings.deliveryDetection = false
                settings.calibResult = ""
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

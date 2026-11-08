package com.meterproof.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * شاشة الإعداد والمتابعة (للمشرف أثناء التجربة).
 * العامل لا يحتاجها أثناء العمل: بعد الإعداد الأول تعمل الخدمة في الخلفية.
 */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var store: EvidenceStore

    private lateinit var statusView: TextView
    private lateinit var verifyView: TextView
    private lateinit var previewView: ImageView
    private lateinit var previewInfo: TextView
    private lateinit var minField: EditText
    private lateinit var maxField: EditText
    private lateinit var startField: EditText
    private lateinit var endField: EditText
    private lateinit var pauseButton: Button
    private lateinit var exportButton: Button
    private lateinit var mapView: TrackMapView
    private lateinit var stopField: EditText
    private lateinit var photoOnStopButton: Button
    private lateinit var privacyModeButton: Button
    private lateinit var dayLabel: TextView
    private lateinit var summaryView: TextView
    private lateinit var mapPackView: TextView

    @Volatile private var importNote: String? = null
    private var importing = false

    private var imageIndex = -1
    private var shownCount = -1
    private var mapCount = -1
    private var mapTrackCount = -1
    private var mapEventCount = -1
    private var dayOffset = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            uiHandler.postDelayed(this, 5000L)
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss dd/MM", Locale.US)

    private companion object {
        const val REQ_EXPORT = 3
        const val REQ_SUMMARY = 4
        const val REQ_MAP_IMPORT = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = EvidenceStore(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(ticker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- الواجهة

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = if (bold) 17f else 15f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
    }

    private fun numberField(value: Int): EditText {
        return EditText(this).apply {
            setText(value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(40), dp(16), dp(32))
        }

        col.addView(label("MeterProof: لوحة الإعداد", true))

        statusView = TextView(this).apply { textSize = 14f }
        col.addView(statusView)

        col.addView(label("1) الإعداد الأول على الهاتف", true))
        col.addView(button("منح الصلاحيات") { requestBase() })
        col.addView(button("استثناء البطارية") { openBatteryExemption() })
        col.addView(button("التشغيل التلقائي (شاومي) / إعدادات التطبيق") { openAutostart() })

        col.addView(label("2) المجال العشوائي وساعات الدوام", true))
        col.addView(label("الحد الأدنى بين صورتين (دقائق)"))
        minField = numberField(settings.minMinutes).also { col.addView(it) }
        col.addView(label("الحد الأقصى بين صورتين (دقائق)"))
        maxField = numberField(settings.maxMinutes).also { col.addView(it) }
        col.addView(label("بداية الدوام (ساعة 0 إلى 23)"))
        startField = numberField(settings.startHour).also { col.addView(it) }
        col.addView(label("نهاية الدوام (ساعة 1 إلى 24)"))
        endField = numberField(settings.endHour).also { col.addView(it) }
        col.addView(label("مدة الثبات التي تُعدّ توقفًا طويلًا (ثوانٍ، من 10 إلى 600؛ تسري من الجلسة التالية)"))
        stopField = numberField(settings.stopAfterSeconds).also { col.addView(it) }
        photoOnStopButton = button("") { togglePhotoOnStop() }
        col.addView(photoOnStopButton)
        privacyModeButton = button("") { togglePrivacyMode() }
        col.addView(privacyModeButton)
        updatePhotoOnStopText()
        col.addView(button("حفظ الإعدادات") { saveSettings(true) })

        col.addView(label("3) الجلسة", true))
        col.addView(button("بدء الجلسة") { startSession() })
        col.addView(button("إيقاف الجلسة") { CaptureService.stop(this); delayedRefresh() })
        pauseButton = button("إيقاف مؤقت") { togglePause() }
        col.addView(pauseButton)
        col.addView(button("التقاط صورة تجريبية الآن") { testCapture() })

        col.addView(label("4) الأدلة المحفوظة (بعد التشويه)", true))
        col.addView(button("التحقق من سلسلة البصمات") { verifyView.text = store.verify() })
        verifyView = TextView(this).apply { textSize = 14f }
        col.addView(verifyView)

        previewView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
            )
        }
        col.addView(previewView)
        previewInfo = TextView(this).apply { textSize = 14f }
        col.addView(previewInfo)

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val prev = button("السابقة") { showImage(imageIndex - 1) }
        val next = button("التالية") { showImage(imageIndex + 1) }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nav.addView(prev, lp)
        nav.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(nav)

        col.addView(label("5) الخارطة والتصدير", true))
        col.addView(label("المسار الأزرق: GPS، الدوائر الحمراء: الصور (اضغط دائرة لعرض صورتها)، المربعات البرتقالية: توقفات"))
        mapView = TrackMapView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
            )
            onPhotoTap = { index -> showImage(index) }
        }
        col.addView(mapView)
        col.addView(button("فتح الخارطة بملء الشاشة (تحريك وتكبير)") {
            startActivity(Intent(this, MapActivity::class.java))
        })
        mapPackView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(mapPackView)
        col.addView(button("استيراد ملف خارطة محلية (MBTiles)") { pickMapFile() })
        col.addView(button("حذف الخارطة المحلية") { confirmDeleteMap() })
        col.addView(button("فتح خارطة الإنترنت في المتصفح (احتياطي)") { openMapInBrowser() })
        exportButton = button("تصدير الصور والسجلات إلى التنزيلات") { doExport() }
        col.addView(exportButton)

        // أداة تجربة موجودة في نسخة debug فقط؛ إن غابت الشاشة (نسخة release) لا يظهر الزر
        val galleryIntent = Intent().setClassName(packageName, "com.meterproof.app.GalleryActivity")
        if (galleryIntent.resolveActivity(packageManager) != null) {
            col.addView(button("🧪 معرض الصور ومشاركتها (للتجربة فقط)") { startActivity(galleryIntent) })
        }

        col.addView(label("6) ملخص اليوم", true))
        val dayNav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dayNav.addView(
            button("اليوم السابق") { dayOffset--; renderSummary() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        dayNav.addView(
            button("اليوم التالي") { if (dayOffset < 0) { dayOffset++; renderSummary() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        col.addView(dayNav)
        dayLabel = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(dayLabel)
        summaryView = TextView(this).apply { textSize = 14f }
        col.addView(summaryView)
        col.addView(button("تصدير ملخص اليوم (PDF) إلى التنزيلات") { exportSummary() })

        col.addView(label("7) جولة العدادات", true))
        col.addView(label("استيراد ملف الجولة، قائمة العدادات الأقرب إليك، وتصوير عداد يربط الصورة والموقع به"))
        col.addView(button("فتح جولة العدادات") {
            startActivity(Intent(this, RoundActivity::class.java))
        })

        col.addView(label("8) كشف تسليم الفواتير", true))
        col.addView(label("معايرة الكاشف على أوراقك، تدريبه، وتفعيله (مغلق افتراضيًا). لا تُحفظ صور للمعايرة"))
        col.addView(button("فتح كشف تسليم الفواتير") {
            startActivity(Intent(this, DeliveryActivity::class.java))
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    // ---------------------------------------------------------------- الحالة

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun mark(ok: Boolean): String = if (ok) "✅" else "❌"

    private fun refresh() {
        val bg = if (Build.VERSION.SDK_INT >= 29) has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else true
        val notif = if (Build.VERSION.SDK_INT >= 33) has(Manifest.permission.POST_NOTIFICATIONS) else true
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        val last = store.lastTime()
        val next = settings.nextCaptureAt

        val sb = StringBuilder()
        sb.append("الكاميرا ").append(mark(has(Manifest.permission.CAMERA))).append("   ")
        sb.append("الموقع ").append(mark(has(Manifest.permission.ACCESS_FINE_LOCATION))).append("\n")
        sb.append("الموقع طوال الوقت ").append(mark(bg)).append("   ")
        sb.append("الإشعارات ").append(mark(notif)).append("\n")
        sb.append("استثناء البطارية ").append(mark(battery)).append("\n\n")
        sb.append("الجلسة: ").append(if (settings.active) "🟢 نشطة" else "⚪ متوقفة")
        if (settings.paused) sb.append(" (إيقاف مؤقت)")
        sb.append("\n")
        if (!settings.active && settings.lastStopReason == "AUTO_END_OF_HOURS" && settings.lastStopAt > 0) {
            sb.append("انتهت الجلسة تلقائيًا عند نهاية الدوام: ")
                .append(timeFormat.format(Date(settings.lastStopAt))).append("\n")
        }
        sb.append("التتبع: ").append(motionStateText()).append("\n")
        sb.append("عدد الصور المحفوظة: ").append(store.count()).append("\n")
        sb.append("نقاط المسار: ").append(store.trackPointCount()).append("\n")
        sb.append("آخر صورة: ").append(if (last != null) timeFormat.format(Date(last)) else "لا يوجد").append("\n")
        if (settings.active && next > 0) {
            sb.append("الصورة التالية تقريبًا: ").append(timeFormat.format(Date(next))).append("\n")
        }
        if (settings.discardedCount > 0) {
            sb.append("صور أُتلفت لأسباب الخصوصية: ").append(settings.discardedCount).append("\n")
        }
        if (settings.lastError.isNotEmpty()) {
            sb.append("⚠ آخر خطأ: ").append(settings.lastError).append("\n")
        }
        statusView.text = sb.toString()

        pauseButton.text = if (settings.paused) "استئناف التصوير" else "إيقاف مؤقت"
        mapPackView.text = importNote ?: MapPack.describe(this)

        val count = store.count()
        if (count != shownCount) {
            shownCount = count
            showImage(count - 1)
        }

        val trackCount = store.trackPointCount()
        val eventCount = store.events().size
        if (count != mapCount || trackCount != mapTrackCount || eventCount != mapEventCount) {
            mapCount = count
            mapTrackCount = trackCount
            mapEventCount = eventCount
            mapView.setData(store.trackPoints(), store.photoPoints(), StopPairing.pair(store.events()))
            mapView.setSelected(imageIndex)
            renderSummary()
        }
    }

    private fun motionStateText(): String = when {
        !settings.active -> "⚪ الجلسة متوقفة"
        settings.motionState == "moving" -> "🚶 قيد التتبع (متحرك)"
        settings.motionState == "stopped" ->
            "⏸ متوقف منذ " + timeFormat.format(Date(settings.stopSince))
        else -> "⚪ لا يسجّل الآن (خارج الدوام أو إيقاف مؤقت)"
    }

    // ---------------------------------------------------------------- ملخص اليوم

    private fun renderSummary() {
        val (start, end) = Days.range(dayOffset)
        val now = System.currentTimeMillis()
        val summary = DaySummaryBuilder.build(
            start, end, now, store.timedTrack(), store.events(), store.photoTimes()
        )
        dayLabel.text = SimpleDateFormat("EEEE dd/MM/yyyy", Locale("ar", "MA")).format(Date(start))
        val sb = StringBuilder()
        for (l in SummaryText.statLines(summary)) sb.append(l).append("\n")
        val stopLines = SummaryText.stopLines(summary, now)
        if (stopLines.isNotEmpty()) {
            sb.append("\nالتوقفات:\n")
            for (l in stopLines.takeLast(30)) sb.append(l).append("\n")
        }
        summaryView.text = sb.toString()
    }

    private fun exportSummary() {
        if (Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_SUMMARY)
            return
        }
        val (start, end) = Days.range(dayOffset)
        toast("جارٍ إنشاء ملف PDF")
        Thread {
            val result = Exporter.exportSummaryPdf(applicationContext, store, start, end)
            runOnUiThread { toast(result.message) }
        }.start()
    }

    private fun updatePhotoOnStopText() {
        updatePrivacyModeText()
        photoOnStopButton.text = if (settings.photoOnStop) {
            "الصورة التلقائية عند التوقف الطويل: مفعّلة (اضغط للتعطيل)"
        } else {
            "الصورة التلقائية عند التوقف الطويل: معطّلة (اضغط للتفعيل)"
        }
    }

    private fun updatePrivacyModeText() {
        privacyModeButton.text = if (settings.privacyMode == "FULL") {
            "الخصوصية: كاملة (تجريد المشهد) — اضغط للتحويل إلى طمس الوجوه فقط"
        } else {
            "الخصوصية: طمس الوجوه فقط، والباقي واضح — اضغط لتفعيل التجريد الكامل"
        }
    }

    private fun togglePrivacyMode() {
        settings.privacyMode = if (settings.privacyMode == "FULL") "FACES" else "FULL"
        updatePrivacyModeText()
    }

    private fun togglePhotoOnStop() {
        settings.photoOnStop = !settings.photoOnStop
        updatePhotoOnStopText()
    }

    private fun delayedRefresh() {
        uiHandler.postDelayed({ refresh() }, 800L)
    }

    private fun showImage(index: Int) {
        val files = store.imageFiles()
        if (files.isEmpty()) {
            previewView.setImageDrawable(null)
            previewInfo.text = "لا توجد صور بعد"
            imageIndex = -1
            return
        }
        imageIndex = index.coerceIn(0, files.lastIndex)
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(files[imageIndex].path, opts)
        previewView.setImageBitmap(bmp)
        previewInfo.text = "صورة ${imageIndex + 1} من ${files.size}"
        mapView.setSelected(imageIndex)
    }

    // ---------------------------------------------------------------- الإجراءات

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun saveSettings(showToast: Boolean): Boolean {
        val minM = minField.text.toString().toIntOrNull()
        val maxM = maxField.text.toString().toIntOrNull()
        val s = startField.text.toString().toIntOrNull()
        val e = endField.text.toString().toIntOrNull()
        if (minM == null || maxM == null || s == null || e == null) {
            toast("أدخل أرقامًا صحيحة في كل الحقول")
            return false
        }
        if (minM < 1 || maxM < minM) {
            toast("الحد الأقصى يجب أن يكون أكبر من أو يساوي الحد الأدنى، والأدنى 1 على الأقل")
            return false
        }
        if (s !in 0..23 || e !in 1..24) {
            toast("بداية الدوام من 0 إلى 23، ونهايته من 1 إلى 24")
            return false
        }
        if (s == e) {
            // مدة صفرية: لا تدخل الجلسة الدوام أبدًا فلا تنتهي تلقائيًا
            toast("بداية الدوام ونهايته لا يمكن أن تكونا الساعة نفسها")
            return false
        }
        val stopS = stopField.text.toString().toIntOrNull()
        if (stopS == null || stopS !in 10..600) {
            toast("مدة التوقف الطويل من 10 إلى 600 ثانية")
            return false
        }
        settings.stopAfterSeconds = stopS
        settings.minMinutes = minM
        settings.maxMinutes = maxM
        settings.startHour = s
        settings.endHour = e
        if (showToast) toast("تم الحفظ")
        return true
    }

    private fun startSession() {
        if (!has(Manifest.permission.CAMERA) || !has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("امنح صلاحيتي الكاميرا والموقع أولًا (الخطوة 1)")
            return
        }
        if (!saveSettings(false)) return
        CaptureService.start(this)
        delayedRefresh()
    }

    private fun testCapture() {
        if (!has(Manifest.permission.CAMERA)) {
            toast("امنح صلاحية الكاميرا أولًا")
            return
        }
        CaptureService.captureNow(this)
        toast("جارٍ التقاط صورة تجريبية، ستظهر بعد ثوانٍ")
        uiHandler.postDelayed({ refresh() }, 6000L)
    }

    private fun togglePause() {
        settings.paused = !settings.paused
        refresh()
    }

    private fun requestBase() {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && Build.VERSION.SDK_INT >= 29 &&
            has(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            toast("الآن اختر \"السماح طوال الوقت\" للموقع")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
        if (requestCode == REQ_EXPORT || requestCode == REQ_SUMMARY) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQ_EXPORT) doExport() else exportSummary()
            } else {
                toast("لا يمكن التصدير دون صلاحية التخزين")
            }
        }
        refresh()
    }

    // ---------------------------------------------------------------- التصدير والخارطة

    private fun doExport() {
        if (store.count() == 0) {
            toast("لا توجد صور لتصديرها")
            return
        }
        if (Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_EXPORT)
            return
        }
        exportButton.isEnabled = false
        toast("جارٍ التصدير، انتظر قليلًا")
        Thread {
            val result = Exporter.exportAll(applicationContext, store)
            runOnUiThread {
                exportButton.isEnabled = true
                verifyView.text = result.message
                toast(result.message)
            }
        }.start()
    }

    private fun pickMapFile() {
        if (importing) {
            toast("جارٍ استيراد ملف، انتظر حتى ينتهي")
            return
        }
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            startActivityForResult(pick, REQ_MAP_IMPORT)
        } catch (e: Exception) {
            toast("لا يوجد تطبيق ملفات لاختيار الملف")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MAP_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // الإذن المؤقت يكفي لنسخ فوري
        }
        startMapImport(uri)
    }

    /** ينسخ الملف إلى مساحة التطبيق على خيط خلفي ثم يحدّث الخارطة. لا يمسّ الخارطة السابقة إن فشل. */
    private fun startMapImport(uri: Uri) {
        importing = true
        importNote = "جارٍ نسخ الخارطة... لا تُغلق التطبيق"
        mapPackView.text = importNote
        val mb = 1024L * 1024L
        Thread {
            val result = MapPack.importFrom(this, uri) { done, total ->
                val note = if (total > 0) {
                    "جارٍ النسخ: ${done * 100 / total}% (${done / mb} من ${total / mb} ميغابايت)"
                } else {
                    "جارٍ النسخ: ${done / mb} ميغابايت"
                }
                importNote = note
                runOnUiThread { mapPackView.text = note }
            }
            runOnUiThread {
                importing = false
                importNote = null
                when (result) {
                    is MapPack.Result.Ok -> {
                        toast("تم استيراد الخارطة. يمكنك حذف الملف الأصلي لتوفير المساحة")
                        mapView.reloadTiles()
                    }
                    is MapPack.Result.Fail -> toast("تعذّر الاستيراد: ${result.message}")
                }
                mapPackView.text = MapPack.describe(this)
            }
        }.start()
    }

    private fun confirmDeleteMap() {
        if (!MapPack.exists(this)) {
            toast("لا توجد خارطة محلية")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setMessage("حذف الخارطة المحلية؟ يمكنك استيرادها من جديد من الملف الأصلي.")
            .setPositiveButton("حذف") { _, _ ->
                MapPack.delete(this)
                mapView.reloadTiles()
                mapPackView.text = MapPack.describe(this)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun openMapInBrowser() {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "map.html")
            file.writeText(
                MapHtml.build(
                    store.trackPoints(), store.photoPoints(),
                    StopPairing.pair(store.events()), withImages = false
                ),
                Charsets.UTF_8
            )
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "text/html")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(view, "افتح الخارطة بـ"))
        } catch (e: Exception) {
            toast("تعذر فتح الخارطة. صدّر الصور ثم افتح ملف map.html من مجلد التنزيلات")
        }
    }

    private fun openBatteryExemption() {
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("افتح إعدادات البطارية يدويًا واجعل التطبيق بلا قيود")
            }
        }
    }

    private fun openAutostart() {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // جرّب التالي
            }
        }
        toast("افتح إعدادات التطبيق يدويًا وفعّل التشغيل التلقائي")
    }
}

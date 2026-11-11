package com.meterproof.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private lateinit var tileInfoView: TextView
    private lateinit var tileDownloadButton: Button
    private lateinit var tileUrlField: EditText

    private var menu: SideMenu? = null
    private var savedPageId: String? = null

    private var imageIndex = -1
    private var shownCount = -1
    private var mapCount = -1
    private var mapTrackCount = -1
    private var mapEventCount = -1
    private var mapTotals: RoundTotals? = null
    private val roundDb by lazy { RoundDb(this) }
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
        const val HEADING_TAG = "heading"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = EvidenceStore(this)
        savedPageId = savedInstanceState?.getString("page")
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

    // ألوان الواجهة (نفس لوحة شاشة الجولة)
    private val cBg = Ui.BG
    private val cInk = Ui.INK
    private val cMuted = Ui.MUTED
    private val cLine = Ui.BORDER
    private val cAccent = Ui.ACCENT

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    /** عنوان قسم (bold، يبدأ بطاقة جديدة في groupIntoCards) أو نص شرح مصغّر. */
    private fun label(text: String, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            if (bold) {
                textSize = 16f
                Ui.styleHeading(this)
                tag = HEADING_TAG
                setPadding(0, dp(2), 0, dp(6))
            } else {
                textSize = 13f
                setTextColor(cMuted)
                setPadding(0, dp(6), 0, dp(2))
            }
        }
    }

    private fun button(
        text: String, kind: Ui.Kind = Ui.Kind.SECONDARY, icon: Int? = null, onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            Ui.style(this, kind, icon)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    /**
     * يجمع عناصر الشاشة في بطاقات: كل عنوان قسم (bold) يبدأ بطاقة بيضاء مستديرة تضم ما بعده
     * حتى العنوان التالي. لا يغيّر أي عنصر ولا سلوكه، الشكل فقط.
     */
    private fun groupIntoCards(col: LinearLayout): LinearLayout {
        val kids = ArrayList<View>()
        for (i in 0 until col.childCount) kids.add(col.getChildAt(i))
        col.removeAllViews()
        col.setBackgroundColor(cBg)
        var card: LinearLayout? = null
        for (v in kids) {
            if (v.tag == HEADING_TAG) {
                val c = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(Ui.SURFACE, 16, cLine)
                    setPadding(dp(16), dp(14), dp(16), dp(16))
                }
                col.addView(
                    c,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                )
                card = c
            }
            (card ?: col).addView(v)
        }
        return col
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

        col.addView(label("الإعداد الأول على الهاتف", true))
        col.addView(button("منح الصلاحيات", Ui.Kind.TONAL, Ui.Icon.SHIELD) { requestBase() })
        col.addView(button("استثناء البطارية", Ui.Kind.TONAL, Ui.Icon.BATTERY) { openBatteryExemption() })
        col.addView(button("التشغيل التلقائي (شاومي) / إعدادات التطبيق", Ui.Kind.TONAL, Ui.Icon.SETTINGS) { openAutostart() })

        col.addView(label("المجال العشوائي وساعات الدوام", true))
        col.addView(label("الحد الأدنى بين صورتين (دقائق)"))
        minField = numberField(settings.minMinutes).also { col.addView(it) }
        col.addView(label("الحد الأقصى بين صورتين (دقائق)"))
        maxField = numberField(settings.maxMinutes).also { col.addView(it) }
        col.addView(label("بداية الدوام (ساعة 0 إلى 23)"))
        startField = numberField(settings.startHour).also { col.addView(it) }
        col.addView(label("نهاية الدوام (ساعة 1 إلى 24)"))
        endField = numberField(settings.endHour).also { col.addView(it) }
        col.addView(label("مجال التصوير العشوائي (اختر جاهزًا أو اكتب الحدّين أعلاه)"))
        col.addView(presetRow(CapturePolicy.INTERVALS.map { (a, b) -> "$a–$b د" to { applyInterval(a, b) } }))
        col.addView(label("مدة الثبات التي تُعدّ توقفًا طويلًا (ثوانٍ، من 10 إلى 600؛ تسري من الجلسة التالية)"))
        stopField = numberField(settings.stopAfterSeconds).also { col.addView(it) }
        col.addView(presetRow(CapturePolicy.STOP_SECONDS.map { s -> "$s ث" to { applyStopSeconds(s) } }))
        photoOnStopButton = button("") { togglePhotoOnStop() }
        col.addView(photoOnStopButton)
        privacyModeButton = button("") { togglePrivacyMode() }
        col.addView(privacyModeButton)
        updatePhotoOnStopText()
        col.addView(button("حفظ الإعدادات", Ui.Kind.PRIMARY, Ui.Icon.CHECK) { saveSettings(true) })

        col.addView(label("الجلسة", true))
        col.addView(button("بدء الجلسة", Ui.Kind.PRIMARY, Ui.Icon.PLAY) { startSession() })
        col.addView(button("إيقاف الجلسة", Ui.Kind.DANGER, Ui.Icon.STOP) { CaptureService.stop(this); delayedRefresh() })
        pauseButton = button("إيقاف مؤقت", Ui.Kind.SECONDARY, Ui.Icon.PAUSE) { togglePause() }
        col.addView(pauseButton)
        col.addView(button("صورة الآن (تُحفظ مع الموقع)", Ui.Kind.TONAL, Ui.Icon.PHOTOS) { testCapture() })

        col.addView(label("الأدلة المحفوظة (بعد التشويه)", true))

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

        col.addView(button("معرض الصور وتصنيفها للتدريب", Ui.Kind.TONAL, Ui.Icon.PHOTOS) {
            startActivity(Intent(this, PhotoGalleryActivity::class.java))
        })

        // أداة تجربة موجودة في نسخة debug فقط؛ إن غابت الشاشة (نسخة release) لا يظهر الزر
        val galleryIntent = Intent().setClassName(packageName, "com.meterproof.app.GalleryActivity")
        if (galleryIntent.resolveActivity(packageManager) != null) {
            col.addView(button("🧪 معرض الصور ومشاركتها (للتجربة فقط)") { startActivity(galleryIntent) })
        }

        col.addView(label("الخارطة", true))
        col.addView(label("المسار الأزرق: GPS، الدوائر الحمراء: الصور (اضغط دائرة لعرض صورتها)، المربعات البرتقالية: توقفات، الدوائر الملوّنة الصغيرة: عدادات الجولة بحسب حالتها (اضغط عدادًا لتفاصيله)"))
        mapView = TrackMapView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
            )
            onPhotoTap = { index -> showImage(index) }
            onMeterTap = { m -> MeterDialog.show(this@MainActivity, m, store) { idx -> showImage(idx) } }
        }
        col.addView(mapView)
        col.addView(button("فتح الخارطة بملء الشاشة (تحريك وتكبير)", Ui.Kind.TONAL, Ui.Icon.MAP) {
            startActivity(Intent(this, MapActivity::class.java))
        })
        tileInfoView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(tileInfoView)
        tileDownloadButton = button("تحميل الخارطة حول العدادات والمسار (للعمل دون إنترنت)", Ui.Kind.TONAL, Ui.Icon.DOWNLOAD) { onTileDownloadClick() }
        col.addView(tileDownloadButton)
        col.addView(button("تبديل الطبقة: مخطط / قمر صناعي", Ui.Kind.SECONDARY, Ui.Icon.LAYERS) { toggleLayer() })
        col.addView(label("رابط بلاطات مخصص (اختياري، يتجاوز الطبقتين الافتراضيتين)"))
        tileUrlField = EditText(this).apply {
            hint = "https://…/{z}/{x}/{y}.png?key=…"
            setText(settings.tileUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            textDirection = View.TEXT_DIRECTION_LTR
        }
        col.addView(tileUrlField)
        col.addView(button("حفظ رابط البلاطات", Ui.Kind.SECONDARY, Ui.Icon.CHECK) { saveTileUrl() })
        col.addView(button("حذف الخارطة المخزّنة", Ui.Kind.DANGER, Ui.Icon.TRASH) { confirmClearTiles() })
        col.addView(button("فتح خارطة الإنترنت في المتصفح (احتياطي)", Ui.Kind.GHOST, Ui.Icon.WEB) { openMapInBrowser() })

        col.addView(label("التصدير والتحقق", true))
        exportButton = button("تصدير الصور والسجلات إلى التنزيلات", Ui.Kind.PRIMARY, Ui.Icon.EXPORT) { doExport() }
        col.addView(exportButton)
        col.addView(button("التحقق من سلسلة البصمات", Ui.Kind.SECONDARY, Ui.Icon.SHIELD) { verifyView.text = store.verify() })
        verifyView = TextView(this).apply { textSize = 14f }
        col.addView(verifyView)

        col.addView(label("ملخص اليوم", true))
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
        col.addView(button("تصدير ملخص اليوم (PDF) إلى التنزيلات", Ui.Kind.PRIMARY, Ui.Icon.FILE) { exportSummary() })

        col.addView(label("جولة العدادات", true))
        col.addView(label("استيراد ملف الجولة، قائمة العدادات الأقرب إليك، وتصوير عداد يربط الصورة والموقع به"))
        col.addView(button("فتح جولة العدادات", Ui.Kind.TONAL, Ui.Icon.PIN) {
            startActivity(Intent(this, RoundActivity::class.java))
        })

        col.addView(label("كشف تسليم الفواتير", true))
        col.addView(label("معايرة الكاشف على أوراقك، تدريبه، وتفعيله (مغلق افتراضيًا). لا تُحفظ صور للمعايرة"))
        col.addView(button("فتح كشف تسليم الفواتير", Ui.Kind.TONAL, Ui.Icon.SPARKLE) {
            startActivity(Intent(this, DeliveryActivity::class.java))
        })

        // بطاقات الأقسام (كل قسم بطاقة)، تُوزَّع على صفحات القائمة الجانبية. لا يتغير أي عنصر ولا سلوكه.
        groupIntoCards(col)
        val cards = (0 until col.childCount).map { col.getChildAt(it) }
        col.removeAllViews()

        fun pageContainer() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(4), dp(16), dp(32))
        }

        val assignment = NavPages.assign(cards.size)
        val dests: List<SideMenu.Dest>
        if (assignment == null) {
            // أمان: عدد البطاقات لا يطابق NavPages (تعديل لاحق على الأقسام)، فتُعرض كلها في صفحة واحدة
            // ولا يُخفى أي زر.
            val all = pageContainer()
            cards.forEach { all.addView(it) }
            dests = listOf(SideMenu.Dest(NavPages.Page.HOME.id, NavPages.Page.HOME.title, all))
        } else {
            val containers = NavPages.Page.values().associateWith { pageContainer() }
            cards.forEachIndexed { i, card -> containers.getValue(assignment[i]).addView(card) }
            containers.getValue(NavPages.Page.HOME).addView(buildShortcutsCard())
            dests = NavPages.Page.values().map {
                SideMenu.Dest(it.id, it.title, containers.getValue(it))
            }
        }

        val startId = NavPages.fromId(savedPageId).id
        val sideMenu = SideMenu(this, dests, if (dests.any { it.id == startId }) startId else dests.first().id)
        menu = sideMenu
        setContentView(sideMenu.root)
        Ui.apply(this)
    }

    /** بطاقة اختصارات في الرئيسية: فتح الشاشات الأكثر استعمالًا مباشرة، أو الانتقال إلى صفحة. */
    private fun buildShortcutsCard(): View {
        val sc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sc.addView(label("اختصارات", true))
        sc.addView(button("الخارطة بملء الشاشة", Ui.Kind.SECONDARY, Ui.Icon.MAP) { startActivity(Intent(this, MapActivity::class.java)) })
        sc.addView(button("معرض الصور وتصنيفها للتدريب", Ui.Kind.SECONDARY, Ui.Icon.PHOTOS) { startActivity(Intent(this, PhotoGalleryActivity::class.java)) })
        sc.addView(button("جولة العدادات", Ui.Kind.SECONDARY, Ui.Icon.PIN) { startActivity(Intent(this, RoundActivity::class.java)) })
        sc.addView(button("كشف تسليم الفواتير", Ui.Kind.SECONDARY, Ui.Icon.SPARKLE) { startActivity(Intent(this, DeliveryActivity::class.java)) })
        sc.addView(button("التصدير والتحقق", Ui.Kind.SECONDARY, Ui.Icon.EXPORT) { menu?.show(NavPages.Page.EXPORT.id) })
        sc.addView(button("الإعدادات", Ui.Kind.SECONDARY, Ui.Icon.SETTINGS) { menu?.show(NavPages.Page.SETTINGS.id) })
        groupIntoCards(sc)
        val card = sc.getChildAt(0)
        sc.removeView(card)
        return card
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        menu?.let { outState.putString("page", it.currentId) }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (menu?.handleBack(NavPages.Page.HOME.id) != true) super.onBackPressed()
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
        updateTileUi()

        val count = store.count()
        if (count != shownCount) {
            shownCount = count
            showImage(count - 1)
        }

        val trackCount = store.trackPointCount()
        val eventCount = store.events().size
        val totals = try {
            roundDb.totals()
        } catch (e: Exception) {
            null
        }
        if (count != mapCount || trackCount != mapTrackCount || eventCount != mapEventCount ||
            totals != mapTotals
        ) {
            mapCount = count
            mapTrackCount = trackCount
            mapEventCount = eventCount
            mapTotals = totals
            val meters = try {
                roundDb.markers()
            } catch (e: Exception) {
                emptyList()
            }
            mapView.setData(store.trackPoints(), store.photoPoints(), StopPairing.pair(store.events()), meters)
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
        toast("جارٍ تجهيز ملف PDF")
        Thread {
            // بلاطات الخارطة الناقصة لهذا اليوم (قراءة محلية فقط، لا شبكة هنا)
            val prep = Exporter.prepareSummaryMap(applicationContext, store, start, end)
            runOnUiThread {
                if (prep != null && prep.missing.isNotEmpty() && Net.online(this)) {
                    confirmReportTiles(prep, start, end)
                } else {
                    runSummaryExport(start, end, false)
                }
            }
        }.start()
    }

    /** الجلب يكشف للمزوّد منطقة عملك، فلا يجري إلا بموافقة صريحة، ويظهر اسم المزوّد وعدد الصور. */
    private fun confirmReportTiles(prep: ReportTiles.Prep, start: Long, end: Long) {
        val provider = when {
            TileLayers.isSatellite(prep.template) -> "Esri (قمر صناعي)"
            prep.template.trim() == TileLayers.PLAN -> "CARTO (مخطط)"
            else -> "مزوّد الرابط المخصص"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("خارطة التقرير")
            .setMessage(
                "تحتاج خارطة هذا التقرير ${prep.missing.size} صورة غير مخزّنة (≈ ${prep.estimateMb()} ميغابايت). " +
                    "تُجلب من $provider عبر الإنترنت، ويُرسل إليه موقع منطقة عملك.\n" +
                    "بدون جلبها تكون خلفية الخارطة ناقصة أو غائبة."
            )
            .setPositiveButton("جلبها وتصدير") { _, _ -> runSummaryExport(start, end, true) }
            .setNeutralButton("تصدير دون جلب") { _, _ -> runSummaryExport(start, end, false) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun runSummaryExport(start: Long, end: Long, fetch: Boolean) {
        toast(if (fetch) "جارٍ جلب الخارطة وإنشاء PDF" else "جارٍ إنشاء ملف PDF")
        Thread {
            val result = Exporter.exportSummaryPdf(applicationContext, store, start, end, fetch)
            runOnUiThread { toast(result.message) }
        }.start()
    }

    private fun updatePhotoOnStopText() {
        updatePrivacyModeText()
        photoOnStopButton.text = if (settings.photoOnStop) {
            "صورة عند كل توقف (حتى بلا قاعدة بيانات): مفعّلة (اضغط للتعطيل)"
        } else {
            "صورة عند كل توقف (حتى بلا قاعدة بيانات): معطّلة (اضغط للتفعيل)"
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

    /** صف أزرار جاهزة متساوية العرض. */
    private fun presetRow(items: List<Pair<String, () -> Unit>>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            items.forEach { (caption, action) ->
                addView(
                    Button(this@MainActivity).apply {
                        this.text = caption
                        setOnClickListener { action() }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
        }

    private fun applyInterval(minM: Int, maxM: Int) {
        minField.setText(minM.toString())
        maxField.setText(maxM.toString())
        settings.minMinutes = minM
        settings.maxMinutes = maxM
        toast("المجال العشوائي: من $minM إلى $maxM دقيقة")
    }

    private fun applyStopSeconds(s: Int) {
        stopField.setText(s.toString())
        settings.stopAfterSeconds = s
        toast("الثبات $s ثانية (تسري من الجلسة التالية)")
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

    // ---------------------------------------------------------------- بلاطات الخارطة

    private fun updateTileUi() {
        val st = TileTransfer.status
        tileInfoView.text = st ?: TileTransfer.describe(this)
        tileDownloadButton.text = if (TileTransfer.running) {
            "إلغاء التحميل الجاري"
        } else {
            "تحميل الخارطة حول العدادات والمسار (للعمل دون إنترنت)"
        }
    }

    /** نقاط العمل: المسار والصور وعدادات الجولة (دون الصفر المجهول). */
    private fun workPoints(): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>()
        for (p in store.trackPoints()) pts.add(Pair(p.lat, p.lon))
        for (p in store.photoPoints()) pts.add(Pair(p.lat, p.lon))
        try {
            for (m in roundDb.markers()) pts.add(Pair(m.lat, m.lon))
        } catch (e: Exception) {
            // بلا جولة
        }
        return pts.filter { !(it.first == 0.0 && it.second == 0.0) }
    }

    private fun onTileDownloadClick() {
        if (TileTransfer.running) {
            TileTransfer.cancel()
            return
        }
        TileTransfer.confirmAndDownload(this, TilePlans.around(workPoints())) {
            updateTileUi()
            if (!TileTransfer.running) mapView.refreshTiles()
        }
    }

    private fun toggleLayer() {
        if (!mapView.toggleSatellite()) {
            toast("رابط بلاطات مخصص مضبوط، فهو الطبقة المعتمدة. امسحه واحفظ للتبديل")
            return
        }
        updateTileUi()
    }

    private fun saveTileUrl() {
        val url = tileUrlField.text.toString().trim()
        val err = TileLayers.validate(url)
        if (err != null) {
            toast(err)
            return
        }
        settings.tileUrl = url
        mapView.refreshTiles()
        updateTileUi()
        toast(if (url.isEmpty()) "عاد التطبيق إلى الطبقات الافتراضية" else "حُفظ رابط البلاطات")
    }

    private fun confirmClearTiles() {
        android.app.AlertDialog.Builder(this)
            .setMessage("حذف كل البلاطات المخزّنة؟ تُجلب من جديد عند الحاجة إن وُجد اتصال.")
            .setPositiveButton("حذف") { _, _ ->
                TileTransfer.clear(this)
                mapView.refreshTiles()
                updateTileUi()
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
                    StopPairing.pair(store.events()), withImages = false,
                    meters = try { roundDb.markers() } catch (e: Exception) { emptyList() }
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

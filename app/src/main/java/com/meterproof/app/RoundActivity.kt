package com.meterproof.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * جولة العدادات: استيراد ملف الجولة، اختيار الحي، قائمة العدادات الأقرب إليك أولًا،
 * وتصوير عداد يربط الصورة والموقع والوقت به داخل السجل المبصوم.
 * MeterProof هنا للإثبات فقط: القراءة وإدخال الفهرس يبقيان في تطبيق القراءة.
 */
class RoundActivity : Activity() {

    private companion object {
        const val REQ_PICK = 21
        const val REQ_LOCATION = 22
        const val LIST_LIMIT = 60
    }

    private lateinit var settings: Settings
    private lateinit var db: RoundDb

    private lateinit var totalsView: TextView
    private lateinit var locView: TextView
    private lateinit var quartierButton: Button
    private lateinit var listView: ListView

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm dd/MM", Locale.US)

    private var location: Location? = null
    private var ranked: List<RankedMeter> = emptyList()
    private var locationListening = false

    private var waitingMeterId = -1L
    private var waitingSince = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 5000L)
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val prev = location
            location = l
            showLocation()
            // نعيد الترتيب فورًا عند أول نقطة أو حين يتحرك العامل أكثر من 15 مترًا
            if (prev == null ||
                Geo.distanceM(prev.latitude, prev.longitude, l.latitude, l.longitude) > 15.0
            ) {
                refresh()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        db = RoundDb(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(28), dp(10), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "جولة العدادات"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        totalsView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(totalsView)

        root.addView(button("استيراد ملف الجولة (Excel أو CSV)") { pickFile() })
        quartierButton = button("الحي: كل الأحياء") { chooseQuartier() }
        root.addView(quartierButton)
        root.addView(CheckBox(this).apply {
            text = "إظهار العدادات المنجزة"
            isChecked = settings.roundShowDone
            setOnCheckedChangeListener { _, checked ->
                settings.roundShowDone = checked
                refresh()
            }
        })
        locView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(2), 0, dp(6))
        }
        root.addView(locView)

        listView = ListView(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            ranked.getOrNull(position)?.let { showDetails(it) }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        updateQuartierButton()
        showLocation()
    }

    override fun onResume() {
        super.onResume()
        startLocation()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        stopLocation()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    // ---------------------------------------------------------------- الموقع

    private fun startLocation() {
        if (locationListening) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!lm.isProviderEnabled(p)) continue
                lm.requestLocationUpdates(p, 2_000L, 0f, locListener, Looper.getMainLooper())
                locationListening = true
                if (location == null) {
                    val last = lm.getLastKnownLocation(p)
                    val age = if (last == null) Long.MAX_VALUE
                    else (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
                    if (last != null && age < 120_000L) location = last
                }
            }
        } catch (e: SecurityException) {
            // الصلاحية سُحبت أثناء العمل
        }
        showLocation()
    }

    private fun stopLocation() {
        if (!locationListening) return
        try {
            getSystemService(LocationManager::class.java).removeUpdates(locListener)
        } catch (e: Exception) {
            // لا شيء
        }
        locationListening = false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocation()
            } else {
                toast("بدون صلاحية الموقع لا يمكن ترتيب العدادات بالأقرب ولا التصوير")
            }
        }
    }

    private fun showLocation() {
        val l = location
        locView.text = if (l == null) {
            "📍 بانتظار GPS… اخرج لمكان مكشوف"
        } else {
            val age = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000_000L
            "📍 موقعك: ±${l.accuracy.toInt()} م (قبل ${age} ث)"
        }
    }

    // ---------------------------------------------------------------- القائمة

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = ranked.size
        override fun getItem(position: Int): Any = ranked[position]
        override fun getItemId(position: Int): Long = ranked[position].item.id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(this@RoundActivity).apply {
                textSize = 15f
                setPadding(dp(6), dp(10), dp(6), dp(10))
            }
            val r = ranked[position]
            val m = r.item
            val dist = r.distanceM?.let { SummaryText.distance(it) } ?: "—"
            val address = m.address.ifEmpty { "(بلا عنوان)" }
            val sb = StringBuilder()
            sb.append(dist).append("   ").append(address).append("\n")
            sb.append("العداد ").append(m.meterNo)
            if (m.oldIndex != null) sb.append(" · القديم ").append(m.oldIndex)
            if (!m.hasGps) sb.append(" · بلا إحداثية")
            if (m.done) sb.append(" · ✅")
            if (m.flags.contains("FAR_FROM_METER")) sb.append(" · ⚠ بعيد")
            tv.text = sb.toString()
            tv.setTextColor(if (m.done) Color.parseColor("#78909C") else Color.parseColor("#212121"))
            return tv
        }
    }

    private fun refresh() {
        val quartier = settings.roundQuartier.ifEmpty { null }
        val showDone = settings.roundShowDone
        val loc = location
        val waiting = waitingMeterId
        worker.execute {
            try {
                val totals = db.totals()
                val items = db.candidates(quartier, showDone)
                val list = RoundLogic.rank(items, loc?.latitude, loc?.longitude, LIST_LIMIT)
                val waited = if (waiting > 0) db.get(waiting) else null
                ui.post { applyRefresh(totals, list, waited) }
            } catch (e: Exception) {
                // محاولة لاحقة بعد 5 ثوانٍ
            }
        }
    }

    private fun applyRefresh(totals: RoundTotals, list: List<RankedMeter>, waited: MeterItem?) {
        ranked = list
        (listView.adapter as BaseAdapter).notifyDataSetChanged()

        totalsView.text = if (totals.total == 0) {
            "لم تستورد جولة بعد. اضغط «استيراد ملف الجولة»."
        } else {
            "الجولة: ${totals.total} عدادًا   المنجز: ${totals.done}   " +
                "بلا إحداثية: ${totals.total - totals.withGps}" +
                (if (totals.flagged > 0) "   ⚠ بعيد: ${totals.flagged}" else "")
        }

        if (waited != null && waitingMeterId == waited.id && waited.doneAt >= waitingSince) {
            waitingMeterId = -1L
            toast(
                if (waited.flags.contains("FAR_FROM_METER")) {
                    "✅ سُجّلت صورة العداد ${waited.meterNo}، لكن ⚠ موقعك بعيد عن إحداثيته"
                } else {
                    "✅ سُجّلت صورة العداد ${waited.meterNo}"
                }
            )
        }
    }

    // ---------------------------------------------------------------- الحي

    private fun updateQuartierButton() {
        val q = settings.roundQuartier
        quartierButton.text = "الحي: " + (if (q.isEmpty()) "كل الأحياء" else q) + "  (اضغط للتغيير)"
    }

    private fun chooseQuartier() {
        worker.execute {
            val stats = try {
                db.quartiers()
            } catch (e: Exception) {
                emptyList()
            }
            ui.post {
                if (stats.isEmpty()) {
                    toast("استورد ملف الجولة أولًا")
                    return@post
                }
                val total = stats.sumOf { it.total }
                val done = stats.sumOf { it.done }
                val labels = ArrayList<String>()
                labels.add("كل الأحياء ($done/$total)")
                for (s in stats) labels.add("${s.name} (${s.done}/${s.total})")
                AlertDialog.Builder(this)
                    .setTitle("اختر الحي (المنجز/الكل)")
                    .setItems(labels.toTypedArray()) { _, which ->
                        settings.roundQuartier = if (which == 0) "" else stats[which - 1].name
                        updateQuartierButton()
                        refresh()
                    }
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- الاستيراد

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "application/octet-stream"
                )
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importFrom(uri)
        }
    }

    private fun importFrom(uri: Uri) {
        toast("جارٍ قراءة الملف… قد تستغرق نحو نصف دقيقة للملفات الكبيرة")
        worker.execute {
            val result: ImportResult = try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("تعذر فتح الملف")
                if (bytes.size > 60 * 1024 * 1024) throw IllegalStateException("الملف كبير جدًا (أكثر من 60 ميغابايت)")
                RoundImport.parseFile(bytes)
            } catch (e: Throwable) {
                ImportResult(emptyList(), 0, 0, 0, 0, e.message ?: e.javaClass.simpleName)
            }
            if (result.error != null || result.meters.isEmpty()) {
                ui.post { message("تعذر الاستيراد", result.error ?: "لا توجد عدادات في الملف") }
                return@execute
            }
            val saved = try {
                db.importAll(result.meters)
            } catch (e: Exception) {
                -1
            }
            ui.post {
                if (saved < 0) {
                    message("تعذر الحفظ", "فشل حفظ الجولة في قاعدة الهاتف")
                } else {
                    settings.roundQuartier = ""
                    updateQuartierButton()
                    showImportSummary(result, saved)
                    refresh()
                }
            }
        }
    }

    private fun showImportSummary(r: ImportResult, saved: Int) {
        val pct = if (saved > 0) r.withGps * 100 / saved else 0
        val quartiers = r.meters.map { it.quartier }.toSet().size
        val sb = StringBuilder()
        sb.append("تم استيراد ").append(saved).append(" عدادًا في ").append(quartiers).append(" حيًا.\n\n")
        sb.append("بإحداثية: ").append(r.withGps).append(" (").append(pct).append("%)\n")
        sb.append("بلا إحداثية: ").append(saved - r.withGps)
        sb.append(" (يتعلّمها التطبيق عند أول تصوير)\n")
        if (r.duplicates > 0) sb.append("مكرّر تجاهلته: ").append(r.duplicates).append("\n")
        if (r.skippedNoMeter > 0) sb.append("بلا رقم عداد: ").append(r.skippedNoMeter).append("\n")
        sb.append("\nلم تُنقل إلى الهاتف أسماء الزبائن ولا بقية الأعمدة.")
        message("اكتمل الاستيراد", sb.toString())
    }

    private fun message(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("حسنًا", null).show()
    }

    // ---------------------------------------------------------------- تفاصيل العداد والتصوير

    private fun showDetails(r: RankedMeter) {
        val m = r.item
        val sb = StringBuilder()
        sb.append(m.address.ifEmpty { "(بلا عنوان)" }).append("\n\n")
        sb.append("الحي: ").append(m.quartier).append("\n")
        if (m.contract.isNotEmpty()) sb.append("العقد: ").append(m.contract).append("\n")
        if (m.oldIndex != null) sb.append("الفهرس القديم: ").append(m.oldIndex).append("\n")
        sb.append("المسافة عنك: ").append(r.distanceM?.let { SummaryText.distance(it) } ?: "غير معروفة").append("\n")
        sb.append(
            if (m.hasGps) {
                if (m.learned) "الإحداثية: تعلّمها التطبيق من صورة سابقة" else "الإحداثية: من ملف الجولة"
            } else {
                "الإحداثية: غير معروفة (ستُحفظ من موقع أول صورة)"
            }
        ).append("\n")
        if (m.done) {
            sb.append("\n✅ صُوِّر ").append(timeFormat.format(Date(m.doneAt)))
            if (m.flags.isNotEmpty()) sb.append("\n⚠ ").append(m.flags)
        }

        AlertDialog.Builder(this)
            .setTitle("العداد " + m.meterNo)
            .setMessage(sb.toString())
            .setPositiveButton(if (m.done) "إعادة التصوير" else "تصوير العداد الآن") { _, _ -> capture(m) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun capture(m: MeterItem) {
        val loc = location
        if (loc == null) {
            toast("لا يوجد موقع GPS بعد. اخرج لمكان مكشوف وانتظر ثوانٍ")
            return
        }
        val ageSec = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000L
        if (ageSec > 60) {
            toast("موقعك قديم (قبل $ageSec ثانية). انتظر تحديث GPS")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast("صلاحية الكاميرا غير ممنوحة. امنحها من شاشة الإعداد الرئيسية")
            return
        }
        waitingMeterId = m.id
        waitingSince = System.currentTimeMillis()
        CaptureService.captureForMeter(
            this, m.id, loc.latitude, loc.longitude, loc.accuracy,
            loc.elapsedRealtimeNanos, loc.isMockCompat()
        )
        toast("جارٍ التصوير الآن (نحو ثانيتين). وجّه الهاتف نحو العداد وثبّته")
        ui.postDelayed({ refresh() }, 3500L)
        ui.postDelayed({ refresh() }, 7000L)
    }
}

/** هل الموقع من مزوّد وهمي؟ (واجهة جديدة من أندرويد 12). */
@Suppress("DEPRECATION")
fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider

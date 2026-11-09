package com.meterproof.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
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
    private lateinit var progressBar: ProgressBar
    private lateinit var chipsRow: LinearLayout
    private lateinit var listView: ListView

    private var query = ""
    private var lastChipsKey = ""
    private var hashes: Map<String, String> = emptyMap()

    // ألوان الواجهة
    private val cBg = Color.parseColor("#F3F5F8")
    private val cInk = Color.parseColor("#1F2933")
    private val cMuted = Color.parseColor("#667085")
    private val cLine = Color.parseColor("#E4E7EC")
    private val cPending = Color.parseColor("#98A2B3")
    private val cDone = Color.parseColor("#2E7D32")
    private val cWarn = Color.parseColor("#EF6C00")
    private val cArrive = Color.parseColor("#0E7490")
    private val cAccent = Color.parseColor("#1D4ED8")

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
            setBackgroundColor(cBg)
            setPadding(dp(12), dp(28), dp(12), dp(8))
        }
        root.addView(label(22f, cInk, true).apply { text = "جولة التوزيع" })
        totalsView = label(13f, cMuted).apply { setPadding(0, dp(2), 0, dp(6)) }
        root.addView(totalsView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(cDone)
            progressBackgroundTintList = ColorStateList.valueOf(cLine)
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }
        actions.addView(pill("⬆ استيراد ملف الجولة", true) { pickFile() })
        actions.addView(CheckBox(this).apply {
            text = "إظهار ما تم تسجيله"
            textSize = 13f
            setTextColor(cMuted)
            isChecked = settings.roundShowDone
            setOnCheckedChangeListener { _, checked ->
                settings.roundShowDone = checked
                refresh()
            }
        })
        root.addView(actions)

        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        })

        root.addView(EditText(this).apply {
            hint = "بحث: رقم العداد أو الاسم أو الشارع"
            textSize = 14f
            setSingleLine()
            setTextColor(cInk)
            setHintTextColor(cPending)
            background = shape(Color.WHITE, 12, cLine)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    refresh()
                }
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        locView = label(12f, cMuted).apply { setPadding(0, dp(6), 0, dp(4)) }
        root.addView(locView)

        listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            ranked.getOrNull(position)?.let { showDetails(it) }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

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


    // ---------------------------------------------------------------- أدوات الشكل

    private fun shape(fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun label(size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (filled) Color.WHITE else cInk)
            background = shape(if (filled) cAccent else Color.WHITE, 18, if (filled) cAccent else cLine)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (selected) Color.WHITE else cInk)
            background = shape(if (selected) cInk else Color.WHITE, 16, if (selected) cInk else cLine)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }

    private class CardHolder(
        val strip: View,
        val meterNo: TextView,
        val dist: TextView,
        val name: TextView,
        val address: TextView,
        val quartier: TextView,
        val indexes: TextView,
        val status: TextView
    )

    /** بطاقة نقطة التسليم: متوسطة الحجم، شريط ملوّن للحالة، ثم الرقم والزبون والعنوان والفهرسان والبصمة. */
    private fun buildCard(): View {
        val strip = View(this)
        val meterNo = label(16f, cInk, true)
        val dist = label(12f, cAccent, true)
        val name = label(15f, cInk).apply { setPadding(0, dp(2), 0, 0) }
        val address = label(13f, cMuted).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val quartier = label(11f, cMuted).apply {
            background = shape(Color.parseColor("#F2F4F7"), 8)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val indexes = label(12f, cMuted).apply { setPadding(dp(8), 0, 0, 0) }
        val status = label(13f, cMuted, true)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(meterNo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dist)
        }
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(quartier)
            addView(indexes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val divider = View(this).apply { setBackgroundColor(Color.parseColor("#EEF0F3")) }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(row1)
            addView(name)
            addView(address)
            addView(row3)
            addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
            addView(status)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(Color.WHITE, 14, cLine)
            clipToOutline = true
            addView(strip, LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val wrapper = LinearLayout(this).apply {
            setPadding(dp(1), dp(5), dp(1), dp(5))
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        wrapper.tag = CardHolder(strip, meterNo, dist, name, address, quartier, indexes, status)
        return wrapper
    }

    private fun bindCard(h: CardHolder, r: RankedMeter) {
        val m = r.item
        h.meterNo.text = "العداد ${m.meterNo}"
        val d = r.distanceM
        h.dist.text = if (d != null) SummaryText.distance(d) else ""
        h.dist.visibility = if (d != null) View.VISIBLE else View.GONE

        if (CustomerInfo.SHOW && m.customerName.isNotEmpty()) {
            h.name.text = m.customerName
            h.name.visibility = View.VISIBLE
        } else {
            h.name.visibility = View.GONE
        }
        h.address.text = m.address.ifEmpty { "(بلا عنوان)" }
        h.quartier.text = m.quartier

        val idx = StringBuilder()
        idx.append("القديم ").append(m.oldIndex ?: "—").append("  ←  الجديد ").append(m.newIndex ?: "—")
        if (!m.hasGps) idx.append("  ·  بلا إحداثية")
        h.indexes.text = idx.toString()

        val far = m.flags.contains("FAR_FROM_METER")
        val color: Int
        val text: String
        if (m.done) {
            val hs = m.recordId?.let { hashes[it] }.orEmpty()
            val hashPart = if (hs.isNotEmpty()) "  ·  بصمة #" + hs.take(6) else ""
            val time = timeFormat.format(Date(m.doneAt))
            if (far) {
                color = cWarn
                text = "⚠ صُوِّر $time  ·  بعيد عن الإحداثية$hashPart"
            } else {
                color = cDone
                text = "✅ صُوِّر $time$hashPart"
            }
        } else if (m.arrived) {
            val hs = m.arrivalRecord?.let { hashes[it] }.orEmpty()
            val hashPart = if (hs.isNotEmpty()) "  ·  بصمة #" + hs.take(6) else ""
            val multi = if (m.arrivalFlags.contains("MULTI_NEAR")) "  ·  ⚠ عدة عدادات قريبة" else ""
            color = cArrive
            text = "📍 وُثّق الوصول ${timeFormat.format(Date(m.arrivalAt))}$hashPart$multi" +
                "\nالتسليم غير مؤكَّد بعد"
        } else {
            color = cPending
            text = "لم يُسجَّل التسليم بعد"
        }
        h.status.text = text
        h.status.setTextColor(color)
        h.strip.setBackgroundColor(color)
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
            val v = convertView ?: buildCard()
            bindCard(v.tag as CardHolder, ranked[position])
            return v
        }
    }

    private fun refresh() {
        val quartier = settings.roundQuartier.ifEmpty { null }
        val showDone = settings.roundShowDone
        val loc = location
        val waiting = waitingMeterId
        val q = query.lowercase()
        worker.execute {
            try {
                val totals = db.totals()
                val stats = db.quartiers()
                val all = db.candidates(quartier, showDone)
                val items = if (q.isEmpty()) all else all.filter {
                    it.meterNo.lowercase().contains(q) ||
                        it.customerName.lowercase().contains(q) ||
                        it.address.lowercase().contains(q) ||
                        it.contract.lowercase().contains(q)
                }
                val list = RoundLogic.rank(items, loc?.latitude, loc?.longitude, LIST_LIMIT)
                val waited = if (waiting > 0) db.get(waiting) else null
                var h = hashes
                val wanted = list.flatMap { listOfNotNull(it.item.recordId, it.item.arrivalRecord) }
                if (wanted.any { !h.containsKey(it) }) {
                    h = try {
                        EvidenceStore(this).readRecords().associate { it.optString("id") to it.optString("hash") }
                    } catch (e: Exception) {
                        h
                    }
                }
                ui.post { applyRefresh(totals, stats, list, waited, h) }
            } catch (e: Exception) {
                // محاولة لاحقة بعد 5 ثوانٍ
            }
        }
    }

    private fun applyRefresh(
        totals: RoundTotals,
        stats: List<QuartierStat>,
        list: List<RankedMeter>,
        waited: MeterItem?,
        h: Map<String, String>
    ) {
        ranked = list
        hashes = h
        (listView.adapter as BaseAdapter).notifyDataSetChanged()

        if (totals.total == 0) {
            totalsView.text = "لم تستورد جولة بعد. اضغط «استيراد ملف الجولة»."
            progressBar.progress = 0
        } else {
            totalsView.text = "${totals.done} تسليم مسجَّل  ·  ${totals.arrived} وصول موثّق  ·  من ${totals.total}" +
                "  ·  بلا إحداثية ${totals.total - totals.withGps}" +
                (if (totals.flagged > 0) "  ·  ⚠ بعيد ${totals.flagged}" else "")
            progressBar.progress = (totals.done + totals.arrived) * 1000 / totals.total
        }
        rebuildChips(stats)

        if (waited != null && waitingMeterId == waited.id && waited.doneAt >= waitingSince) {
            waitingMeterId = -1L
            toast(
                if (waited.flags.contains("FAR_FROM_METER")) {
                    "✅ سُجّلت صورة التسليم للعداد ${waited.meterNo}، لكن ⚠ موقعك بعيد عن إحداثيته"
                } else {
                    "✅ سُجّلت صورة التسليم للعداد ${waited.meterNo}"
                }
            )
        }
    }

    // ---------------------------------------------------------------- الأحياء

    private fun resetChips() {
        lastChipsKey = ""
    }

    private fun rebuildChips(stats: List<QuartierStat>) {
        val selected = settings.roundQuartier
        val key = selected + "|" + stats.joinToString(",") { "${it.name}:${it.done}/${it.total}" }
        if (key == lastChipsKey) return
        lastChipsKey = key
        chipsRow.removeAllViews()
        if (stats.isEmpty()) return
        val total = stats.sumOf { it.total }
        val done = stats.sumOf { it.done }
        chipsRow.addView(chip("الكل $done/$total", selected.isEmpty()) {
            settings.roundQuartier = ""
            refresh()
        })
        for (st in stats) {
            chipsRow.addView(chip("${st.name} ${st.done}/${st.total}", selected == st.name) {
                settings.roundQuartier = st.name
                refresh()
            })
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
                    resetChips()
                    showImportSummary(result, saved)
                    refresh()
                }
            }
        }
    }

    private fun showImportSummary(r: ImportResult, saved: Int) {
        val pct = if (saved > 0) r.withGps * 100 / saved else 0
        val quartiers = r.meters.map { it.quartier }.toSet().size
        val names = r.meters.count { it.customerName.isNotEmpty() }
        val oldIdx = r.meters.count { it.oldIndex != null }
        val newIdx = r.meters.count { it.newIndex != null }
        val sb = StringBuilder()
        sb.append("تم استيراد ").append(saved).append(" نقطة في ").append(quartiers).append(" حيًا.\n\n")
        sb.append("• بإحداثية: ").append(r.withGps).append(" (").append(pct).append("%)\n")
        sb.append("• بلا إحداثية: ").append(saved - r.withGps)
        sb.append(" (يتعلّمها التطبيق عند أول تصوير)\n")
        if (CustomerInfo.SHOW) sb.append("• بأسماء الزبائن: ").append(names).append("\n")
        sb.append("• بفهرس قديم: ").append(oldIdx).append("\n")
        sb.append("• بفهرس جديد: ").append(newIdx).append("\n")
        if (r.duplicates > 0) sb.append("• مكرّر تجاهلته: ").append(r.duplicates).append("\n")
        if (r.skippedNoMeter > 0) sb.append("• بلا رقم عداد: ").append(r.skippedNoMeter).append("\n")
        if (CustomerInfo.SHOW && names == 0) {
            sb.append("\n⚠ لم أجد عمود اسم الزبون. العناوين المقبولة: Nom، Client، Abonné، Titulaire، Nom Client.")
        }
        message("اكتمل الاستيراد", sb.toString())
    }

    private fun message(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("حسنًا", null).show()
    }

    // ---------------------------------------------------------------- تفاصيل العداد والتصوير

    private fun showDetails(r: RankedMeter) {
        val m = r.item
        val sb = StringBuilder()
        if (CustomerInfo.SHOW && m.customerName.isNotEmpty()) sb.append(m.customerName).append("\n")
        sb.append(m.address.ifEmpty { "(بلا عنوان)" }).append("\n\n")
        sb.append("الحي: ").append(m.quartier).append("\n")
        if (m.contract.isNotEmpty()) sb.append("العقد: ").append(m.contract).append("\n")
        sb.append("الفهرس القديم: ").append(m.oldIndex ?: "—").append("\n")
        sb.append("الفهرس الجديد: ").append(m.newIndex ?: "—").append("\n")
        sb.append("المسافة عنك: ").append(r.distanceM?.let { SummaryText.distance(it) } ?: "غير معروفة").append("\n")
        sb.append(
            if (m.hasGps) {
                if (m.learned) "الإحداثية: تعلّمها التطبيق من صورة سابقة" else "الإحداثية: من ملف الجولة"
            } else {
                "الإحداثية: غير معروفة (ستُحفظ من موقع أول صورة)"
            }
        ).append("\n")
        if (m.arrived) {
            sb.append("\n📍 وُثّق الوصول ").append(timeFormat.format(Date(m.arrivalAt)))
            val ah = m.arrivalRecord?.let { hashes[it] }.orEmpty()
            if (ah.isNotEmpty()) sb.append("\nبصمة الوصول: ").append(ah.take(16))
            sb.append("\n(التسليم غير مؤكَّد بعد)")
        }
        if (m.done) {
            sb.append("\n✅ صُوِّر ").append(timeFormat.format(Date(m.doneAt)))
            val hs = m.recordId?.let { hashes[it] }.orEmpty()
            if (hs.isNotEmpty()) sb.append("\nالبصمة: ").append(hs.take(16))
            if (m.flags.isNotEmpty()) sb.append("\n⚠ ").append(m.flags)
        }

        AlertDialog.Builder(this)
            .setTitle("العداد " + m.meterNo)
            .setMessage(sb.toString())
            .setPositiveButton(if (m.done) "إعادة التصوير" else "تسجيل التسليم بالصورة") { _, _ -> capture(m) }
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
        toast("جارٍ التصوير الآن (نحو ثانيتين). وجّه الهاتف نحو الباب أو العداد وثبّته")
        ui.postDelayed({ refresh() }, 3500L)
        ui.postDelayed({ refresh() }, 7000L)
    }
}

/** هل الموقع من مزوّد وهمي؟ (واجهة جديدة من أندرويد 12). */
@Suppress("DEPRECATION")
fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider

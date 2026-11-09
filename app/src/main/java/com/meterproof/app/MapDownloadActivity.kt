package com.meterproof.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.map.Bounds
import com.meterproof.app.map.DownloadLimits
import com.meterproof.app.map.DownloadPlan
import com.meterproof.app.map.FetchResult
import com.meterproof.app.map.TileDownloader
import com.meterproof.app.map.TileFetcher
import com.meterproof.app.map.TileMath
import com.meterproof.app.map.TileStore
import com.meterproof.app.map.TileUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * تنزيل بلاطات الخارطة مرة واحدة لمنطقة العمل، ثم تعمل الخارطة دون إنترنت.
 * هذه الشاشة هي المكان الوحيد في التطبيق الذي يستعمل الشبكة، وبطلب صريح من المستخدم فقط.
 * التنزيل قابل للاستئناف (يتخطى ما نُزِّل) ويبدأ من التقريب الأدنى فيبقى ما نُزِّل صالحًا إن توقف.
 */
class MapDownloadActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var evidence: EvidenceStore

    private lateinit var urlField: EditText
    private lateinit var attrField: EditText
    private lateinit var radiusField: EditText
    private lateinit var zMinField: EditText
    private lateinit var zMaxField: EditText
    private lateinit var centerView: TextView
    private lateinit var estimateView: TextView
    private lateinit var infoView: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var deleteButton: Button

    private var center: Pair<Double, Double>? = null
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var cancelFlag = false
    @Volatile private var running = false

    private companion object {
        const val POLITE_DELAY_MS = 150L
        const val MAX_RADIUS_KM = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        evidence = EvidenceStore(this)
        buildUi()
        refreshInfo()
    }

    override fun onDestroy() {
        cancelFlag = true
        super.onDestroy()
    }

    // ---------------------------------------------------------------- الواجهة

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = if (bold) 17f else 14f
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun numberField(value: Int): EditText = EditText(this).apply {
        setText(value.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(col)

        col.addView(label("خارطة دون إنترنت", true))
        col.addView(label(
            "تُنزَّل بلاطات الخارطة لمنطقة عملك مرة واحدة، ثم تظهر تحت المسار والدوائر دون أي اتصال. " +
                "هذه الشاشة هي الوحيدة التي تستعمل الإنترنت، وفقط عند ضغطك زر التنزيل."
        ))
        col.addView(label(
            "تنبيه: خوادم OpenStreetMap الرسمية (tile.openstreetmap.org) لا تُستعمل للتنزيل بالجملة، " +
                "والتطبيق يرفضها. استعمل مزوّد بلاطات يسمح بالتخزين المحلي وفق شروطه (غالبًا مع مفتاح خاص بك)."
        ))

        col.addView(label("1) مصدر البلاطات", true))
        urlField = EditText(this).apply {
            hint = "https://…/{z}/{x}/{y}.png?key=…"
            setText(settings.tileUrl)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            textDirection = android.view.View.TEXT_DIRECTION_LTR
        }
        col.addView(urlField)
        col.addView(label("نص نسب البيانات (يُرسم على الخارطة)"))
        attrField = EditText(this).apply {
            setText(settings.tileAttribution)
            isSingleLine = true
        }
        col.addView(attrField)

        col.addView(label("2) المنطقة", true))
        centerView = label("المركز: لم يُحدَّد")
        col.addView(centerView)
        val centerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        centerRow.addView(button("موقعي الأخير") { useLastLocation() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        centerRow.addView(button("وسط مسار التتبع") { useTrackCenter() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(centerRow)

        col.addView(label("نصف القطر (كم، 1 إلى $MAX_RADIUS_KM)"))
        radiusField = numberField(settings.tileRadiusKm)
        col.addView(radiusField)
        col.addView(label("أدنى تقريب (${DownloadLimits.MIN_ZOOM}–${DownloadLimits.MAX_ZOOM}، الأعلى أدق)"))
        zMinField = numberField(settings.tileZoomMin)
        col.addView(zMinField)
        col.addView(label("أعلى تقريب (18 مناسب للشوارع والأبنية، 19 أدق وأثقل)"))
        zMaxField = numberField(settings.tileZoomMax)
        col.addView(zMaxField)

        col.addView(button("احسب الحجم") { showEstimate() })
        estimateView = label("")
        col.addView(estimateView)

        col.addView(label("3) التنزيل", true))
        startButton = button("ابدأ التنزيل") { confirmAndStart() }
        col.addView(startButton)
        cancelButton = button("إلغاء (ما نُزِّل يبقى)") { cancelFlag = true }
        cancelButton.isEnabled = false
        col.addView(cancelButton)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
        }
        col.addView(progressBar)
        progressText = label("")
        col.addView(progressText)

        col.addView(label("الخارطة المخزَّنة", true))
        infoView = label("")
        col.addView(infoView)
        deleteButton = button("حذف الخارطة المنزَّلة") { confirmDelete() }
        col.addView(deleteButton)

        setContentView(scroll)
    }

    // ---------------------------------------------------------------- المركز

    private fun setCenter(lat: Double, lon: Double, source: String) {
        center = Pair(lat, lon)
        centerView.text = "المركز ($source): " + String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)
        estimateView.text = ""
    }

    private fun useLastLocation() {
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            toast("صلاحية الموقع غير ممنوحة. امنحها من الشاشة الرئيسية أو استعمل وسط المسار.")
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        val b = best
        if (b == null) toast("لا يوجد موقع محفوظ بعد. شغّل الجلسة قليلًا أو استعمل وسط المسار.")
        else setCenter(b.latitude, b.longitude, "موقعك الأخير")
    }

    private fun useTrackCenter() {
        val pts = evidence.trackPoints()
        if (pts.isEmpty()) {
            toast("لا يوجد مسار مسجّل بعد.")
            return
        }
        val lat = (pts.minOf { it.lat } + pts.maxOf { it.lat }) / 2
        val lon = (pts.minOf { it.lon } + pts.maxOf { it.lon }) / 2
        setCenter(lat, lon, "وسط المسار")
    }

    // ---------------------------------------------------------------- الخطة

    private fun intOf(f: EditText, fallback: Int): Int = f.text.toString().trim().toIntOrNull() ?: fallback

    /** (الخطة، رسالة خطأ). */
    private fun buildPlan(): Pair<DownloadPlan?, String?> {
        val c = center ?: return Pair(null, "اختر مركز المنطقة أولًا")
        val rKm = intOf(radiusField, 0)
        if (rKm !in 1..MAX_RADIUS_KM) return Pair(null, "نصف القطر بين 1 و$MAX_RADIUS_KM كم")
        val zMin = intOf(zMinField, 0)
        val zMax = intOf(zMaxField, 0)
        val plan = DownloadPlan(
            TileMath.boundsAround(c.first, c.second, rKm * 1000.0), zMin, zMax
        )
        if (!plan.valid()) {
            return Pair(null, "التقريب بين ${DownloadLimits.MIN_ZOOM} و${DownloadLimits.MAX_ZOOM} والأعلى ≥ الأدنى")
        }
        if (!plan.withinCap) {
            return Pair(null, "العدد ${plan.total} بلاطة يتجاوز السقف ${DownloadLimits.MAX_TILES}. صغّر النصف أو خفّض أعلى تقريب")
        }
        return Pair(plan, null)
    }

    private fun mb(bytes: Long): String = String.format(java.util.Locale.US, "%.0f", bytes / 1_048_576.0)

    private fun showEstimate() {
        val (plan, err) = buildPlan()
        estimateView.text = if (plan == null) err else
            "≈ ${plan.total} بلاطة، حجم تقريبي ${mb(plan.estimatedBytes)} ميغابايت (تقدير؛ يختلف بين المزوّدين)"
    }

    // ---------------------------------------------------------------- التنزيل

    private fun confirmAndStart() {
        if (running) return
        val tpl = urlField.text.toString().trim()
        TileUrl.validate(tpl)?.let { toast(it); return }
        if (TileUrl.isOfficialOsm(tpl)) {
            toast("خوادم OpenStreetMap الرسمية لا تسمح بالتنزيل بالجملة. استعمل مزوّدًا آخر.")
            return
        }
        val (plan, err) = buildPlan()
        if (plan == null) { toast(err ?: "خطة غير صالحة"); return }

        AlertDialog.Builder(this)
            .setTitle("تأكيد التنزيل")
            .setMessage(
                "سيُنزَّل ≈ ${plan.total} بلاطة (≈ ${mb(plan.estimatedBytes)} ميغابايت) عبر الإنترنت. " +
                    "يُفضَّل الاتصال بـ Wi-Fi. يمكنك الإلغاء ثم الاستئناف لاحقًا دون إعادة ما نُزِّل."
            )
            .setPositiveButton("ابدأ") { _, _ -> start(plan, tpl) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun start(plan: DownloadPlan, tpl: String) {
        settings.tileUrl = tpl
        settings.tileAttribution = attrField.text.toString()
        settings.tileRadiusKm = intOf(radiusField, 3)
        settings.tileZoomMin = plan.zMin
        settings.tileZoomMax = plan.zMax

        val store = TileStore.get(this)
        store.setMeta("name", "MeterProof offline")
        store.setMeta("attribution", settings.tileAttribution)
        store.setMeta("type", "baselayer")
        store.setMeta("minzoom", plan.zMin.toString())
        store.setMeta("maxzoom", plan.zMax.toString())
        val b: Bounds = plan.bounds
        store.setMeta("bounds", "${b.minLon},${b.minLat},${b.maxLon},${b.maxLat}")

        cancelFlag = false
        setRunning(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressBar.progress = 0
        progressText.text = "جارٍ البدء…"

        Thread {
            var summary: String
            try {
                val r = TileDownloader(HttpTileFetcher(), store, tpl, POLITE_DELAY_MS).run(
                    plan, { cancelFlag }
                ) { done, total ->
                    if (done % 20 == 0 || done.toLong() == total) {
                        ui.post {
                            progressBar.progress = (done * 1000L / total).toInt()
                            progressText.text = "تمت معالجة $done من $total"
                        }
                    }
                }
                summary = when {
                    r.aborted != null -> "توقّف: ${r.aborted}"
                    r.cancelled -> "أُلغي. نُزِّلت ${r.downloaded} وتُخطّيت ${r.skipped} (يمكن الاستئناف)."
                    r.failed > 0 -> "اكتمل مع ${r.failed} بلاطة فاشلة؛ أعد التشغيل لاستكمالها. نُزِّلت ${r.downloaded}."
                    else -> "اكتمل: نُزِّلت ${r.downloaded}، تُخطّيت ${r.skipped}، غير متوفرة ${r.missing}."
                }
            } catch (e: Exception) {
                summary = "خطأ: ${e.message ?: e.javaClass.simpleName}"
            }
            store.flush()
            ui.post {
                setRunning(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progressText.text = summary
                refreshInfo()
            }
        }.start()
    }

    private fun setRunning(v: Boolean) {
        running = v
        startButton.isEnabled = !v
        cancelButton.isEnabled = v
        deleteButton.isEnabled = !v
    }

    // ---------------------------------------------------------------- المخزن

    private fun refreshInfo() {
        val s = try { TileStore.get(this) } catch (e: Exception) { null }
        infoView.text = if (s == null || !s.hasTiles) "لا توجد خارطة منزَّلة بعد."
        else "${s.tileCount} بلاطة، التقريب ${s.minZoom}–${s.maxZoom}، الحجم ${mb(s.sizeBytes())} ميغابايت."
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("حذف الخارطة")
            .setMessage("تُحذف كل البلاطات المنزَّلة من الجهاز (لا يتأثر المسار ولا الأدلة). متابعة؟")
            .setPositiveButton("احذف") { _, _ ->
                TileStore.deleteAll(this)
                refreshInfo()
                progressText.text = ""
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- الشبكة

    /** جلب بلاطة واحدة عبر HTTPS. لا يُسجَّل الرابط أبدًا (قد يحوي مفتاحًا). */
    private class HttpTileFetcher : TileFetcher {
        override fun fetch(url: String): FetchResult {
            var c: HttpURLConnection? = null
            return try {
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "MeterProof/0.1 (Android; offline map download)")
                val code = c.responseCode
                if (code != 200) FetchResult(code)
                else FetchResult(200, c.inputStream.use { readLimited(it, 2_000_000) })
            } catch (e: IOException) {
                FetchResult(0, null, true)
            } finally {
                c?.disconnect()
            }
        }

        private fun readLimited(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) return ByteArray(0)
            }
            return out.toByteArray()
        }
    }
}

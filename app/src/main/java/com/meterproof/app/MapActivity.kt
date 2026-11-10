package com.meterproof.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/**
 * الخارطة بملء الشاشة: تحريك بإصبع، تكبير بإصبعين أو بنقرتين، وضغط دائرة لعرض صورتها.
 *
 * - «اذهب»: اكتب رقم عداد أو اسمًا فتتمركز الخارطة عليه.
 * - «موقعي»، وبدّل الطبقة (شوارع، قمر صناعي، تضاريس).
 * - «افتح الملاحة» يرسل الإحداثية إلى تطبيق الخرائط المثبّت، و«مسار في الأزقة» يرسم المسار داخل الشوارع المحمّلة.
 * - «تحميل الخارطة» يحمّل شوارع المنطقة حول عدادات الجولة مرة واحدة، فتعمل بعدها دون إنترنت.
 */
class MapActivity : Activity() {

    private lateinit var store: EvidenceStore
    private lateinit var settings: Settings
    private lateinit var map: TrackMapView
    private lateinit var searchInput: EditText
    private lateinit var layerButton: Button
    private lateinit var previewBox: LinearLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewText: TextView
    private lateinit var targetBox: LinearLayout
    private lateinit var targetText: TextView
    private lateinit var routeText: TextView

    private var shownCount = -1
    private var shownTrack = -1
    private var shownEvents = -1
    private var shownTotals: RoundTotals? = null
    private val roundDb by lazy { RoundDb(this) }
    private val router by lazy { AreaRouter(AreaStore(this)) }
    private val worker = Executors.newSingleThreadExecutor()

    // ---- الهدف والمسار والموقع
    private var target: MeterItem? = null
    private var routePts: List<GeoPoint>? = null
    private var routeBusy = false
    private var lastRouteAt = 0L
    private var myLat: Double? = null
    private var myLon: Double? = null
    private var centerOnNextFix = false
    private var locationManager: LocationManager? = null

    // ---- التحميل
    private var downloader: AreaDownloader? = null
    private var progressDialog: AlertDialog? = null
    private var progressText: TextView? = null
    private var progressBar: ProgressBar? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, 10_000L)
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            onFix(l)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {
        }

        override fun onProviderDisabled(provider: String) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun barButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EvidenceStore(this)
        settings = Settings(this)

        val root = FrameLayout(this)

        map = TrackMapView(this, interactive = true).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            initLayer(MapLayer.of(settings.mapLayer))
            onPhotoTap = { index -> showPhoto(index) }
            onMeterTap = { m -> MeterDialog.show(this@MapActivity, m, store) { idx -> showPhoto(idx) } }
        }
        root.addView(map)

        // ---- الشريط العلوي: بحث ثم أزرار
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xEEFFFFFF.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        searchInput = EditText(this).apply {
            hint = "الذهاب إلى: رقم العداد أو الاسم"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch()
                    true
                } else {
                    false
                }
            }
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(barButton("اذهب") { doSearch() })
        top.addView(searchRow)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        bar.addView(barButton("رجوع") { finish() })
        bar.addView(barButton("＋") { map.zoomBy(1.0) })
        bar.addView(barButton("－") { map.zoomBy(-1.0) })
        bar.addView(barButton("تمركز") { map.recenter() })
        bar.addView(barButton("موقعي") { goToMe() })
        layerButton = barButton("") { switchLayer() }
        bar.addView(layerButton)
        bar.addView(barButton("تحميل الخارطة") { startDownloadFlow() })
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        top.addView(scroll)
        root.addView(
            top,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        )
        updateLayerButton()

        // ---- اللوحة السفلية: الهدف ثم معاينة الصورة
        targetText = TextView(this).apply {
            textSize = 14f
            setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        routeText = TextView(this).apply {
            textSize = 13f
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        val targetButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        targetButtons.addView(
            barButton("افتح الملاحة") { openNavigation() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        targetButtons.addView(
            barButton("مسار في الأزقة") { routeToTarget() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        targetButtons.addView(barButton("إغلاق") { clearTarget() })
        targetBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xF2FFFFFF.toInt())
            visibility = View.GONE
            addView(targetText)
            addView(routeText)
            addView(targetButtons)
        }

        previewImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewText = TextView(this).apply {
            textSize = 14f
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEEFFFFFF.toInt())
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE } // الضغط على اللوحة يخفيها
            addView(
                previewImage,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
            )
            addView(previewText)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(targetBox)
            addView(previewBox)
        }
        root.addView(
            bottom,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        )

        setContentView(root)

        if (intent.getBooleanExtra(EXTRA_DOWNLOAD, false)) {
            handler.post { startDownloadFlow() }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        startLocation()
        map.reloadTiles() // يلتقط مفتاحًا جديدًا أو خارطة محمّلة حديثًا
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        try {
            locationManager?.removeUpdates(locListener)
        } catch (e: Exception) {
            // لا شيء
        }
        super.onPause()
    }

    override fun onDestroy() {
        downloader?.cancel()
        progressDialog?.dismiss()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- النقاط على الخارطة

    /** يحمّل النقاط من جديد إن تغيّرت (الكاميرا لا تتحرك إن حرّكها المستخدم). */
    private fun reload() {
        val count = store.count()
        val track = store.trackPointCount()
        val events = store.events().size
        val totals = try {
            roundDb.totals()
        } catch (e: Exception) {
            null
        }
        if (count == shownCount && track == shownTrack && events == shownEvents && totals == shownTotals) return
        shownCount = count
        shownTrack = track
        shownEvents = events
        shownTotals = totals
        val meters = try {
            roundDb.markers()
        } catch (e: Exception) {
            emptyList()
        }
        map.setData(store.trackPoints(), store.photoPoints(), StopPairing.pair(store.events()), meters)
    }

    private fun showPhoto(index: Int) {
        val files = store.imageFiles()
        if (index !in files.indices) return
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = BitmapFactory.decodeFile(files[index].path, opts)
        previewImage.setImageBitmap(bmp)
        previewText.text = "صورة ${index + 1} من ${files.size} (اضغط اللوحة لإخفائها)"
        previewBox.visibility = View.VISIBLE
        map.setSelected(index)
    }

    // ---------------------------------------------------------------- الطبقات

    private fun updateLayerButton() {
        layerButton.text = "الطبقة: " + map.getLayer().label
    }

    private fun switchLayer() {
        val next = map.getLayer().next()
        if (next != MapLayer.STREETS && settings.maptilerKey.isEmpty()) {
            MapKeyDialog.show(this) {
                applyLayer(next)
            }
            return
        }
        applyLayer(next)
    }

    private fun applyLayer(l: MapLayer) {
        settings.mapLayer = l.name
        map.setLayer(l)
        updateLayerButton()
        if (l != MapLayer.STREETS) {
            toast("${l.label}: تُجلب من MapTiler عبر الإنترنت وتُحفظ مؤقتًا ليوم واحد فقط (شروطهم تمنع التحميل المسبق)")
        }
    }

    // ---------------------------------------------------------------- الموقع

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocation() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 2000L, 2f, locListener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let { if (myLat == null) onFix(it) }
                }
            }
        } catch (e: SecurityException) {
            // الإذن سُحب
        } catch (e: Exception) {
            // لا شيء
        }
    }

    private fun onFix(l: Location) {
        myLat = l.latitude
        myLon = l.longitude
        map.setMyLocation(l.latitude, l.longitude, l.accuracy)
        if (centerOnNextFix) {
            centerOnNextFix = false
            map.focusOn(l.latitude, l.longitude, 17.0)
        }
        updateTargetText()
        refreshRouteGuidance()
    }

    private fun goToMe() {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            return
        }
        val la = myLat
        val lo = myLon
        if (la != null && lo != null) {
            map.focusOn(la, lo, 17.0)
        } else {
            centerOnNextFix = true
            startLocation()
            toast("بانتظار إشارة GPS…")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            centerOnNextFix = true
            startLocation()
        } else if (requestCode == REQ_LOCATION) {
            toast("بلا إذن الموقع لا يظهر موقعك على الخارطة")
        }
    }

    // ---------------------------------------------------------------- الذهاب إلى

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        worker.execute {
            val res = try {
                roundDb.search(q, 12)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                when {
                    res.isEmpty() -> toast("لا نتيجة لـ «$q» في عدادات الجولة")
                    res.size == 1 -> selectTarget(res[0])
                    else -> {
                        val labels = res.map { m ->
                            val extra = if (CustomerInfo.SHOW && m.customerName.isNotEmpty()) m.customerName else m.address
                            m.meterNo + (if (extra.isNotEmpty()) " — $extra" else "") + (if (!m.hasGps) " (بلا إحداثية)" else "")
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("اختر العداد")
                            .setItems(labels) { _, which -> selectTarget(res[which]) }
                            .setNegativeButton("إلغاء", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun selectTarget(m: MeterItem) {
        val lat = m.lat
        val lon = m.lon
        if (lat == null || lon == null) {
            toast("العداد ${m.meterNo} بلا إحداثية، فلا يمكن تمركز الخارطة عليه")
            return
        }
        target = m
        routePts = null
        routeText.text = ""
        map.setRoute(null)
        map.setTarget(GeoPoint(lat, lon))
        map.focusOn(lat, lon, 18.0)
        targetBox.visibility = View.VISIBLE
        updateTargetText()
    }

    private fun clearTarget() {
        target = null
        routePts = null
        map.setTarget(null)
        map.setRoute(null)
        targetBox.visibility = View.GONE
    }

    private fun updateTargetText() {
        val m = target ?: return
        val sb = StringBuilder("العداد ").append(m.meterNo)
        if (CustomerInfo.SHOW && m.customerName.isNotEmpty()) sb.append(" — ").append(m.customerName)
        if (m.address.isNotEmpty()) sb.append("\n").append(m.address)
        val la = myLat
        val lo = myLon
        val tl = m.lat
        val tn = m.lon
        if (la != null && lo != null && tl != null && tn != null) {
            sb.append("\nالمسافة المستقيمة عنك: ").append(fmtDist(Geo.distanceM(la, lo, tl, tn)))
        }
        targetText.text = sb.toString()
    }

    private fun fmtDist(m: Double): String =
        if (m >= 1000) String.format(Locale.US, "%.1f كم", m / 1000) else "${Math.round(m)} م"

    // ---------------------------------------------------------------- الملاحة

    /** يرسل الإحداثية إلى تطبيق الخرائط المثبّت (يحتاج إنترنت أو خرائط محمّلة في ذلك التطبيق). */
    private fun openNavigation() {
        val m = target ?: return
        val lat = m.lat ?: return
        val lon = m.lon ?: return
        val ll = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val google = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$ll&mode=w"))
            .setPackage("com.google.android.apps.maps")
        try {
            startActivity(google)
            return
        } catch (e: ActivityNotFoundException) {
            // نجرّب أي تطبيق خرائط
        }
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll(" + Uri.encode("عداد ${m.meterNo}") + ")"))
        try {
            startActivity(geo)
        } catch (e: ActivityNotFoundException) {
            toast("لا يوجد تطبيق خرائط مثبّت على الهاتف")
        }
    }

    /** يرسم مسارًا مشيًا داخل الشوارع المحمّلة من موقعك إلى العداد. */
    private fun routeToTarget() {
        val m = target ?: return
        val tl = m.lat ?: return
        val tn = m.lon ?: return
        val la = myLat
        val lo = myLon
        if (la == null || lo == null) {
            toast("حدّد موقعك أولًا (زر «موقعي») ثم أعد المحاولة")
            goToMe()
            return
        }
        if (routeBusy) return
        routeBusy = true
        routeText.text = "جارٍ حساب المسار…"
        worker.execute {
            val g = try {
                router.graph()
            } catch (e: Exception) {
                null
            }
            val r = if (g != null) {
                try {
                    g.route(la, lo, tl, tn)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            runOnUiThread {
                routeBusy = false
                lastRouteAt = System.currentTimeMillis()
                when {
                    g == null -> {
                        routePts = null
                        map.setRoute(listOf(GeoPoint(la, lo), GeoPoint(tl, tn)))
                        routeText.text = "لا خارطة أزقة محمّلة: يُعرض خط مستقيم. اضغط «تحميل الخارطة» أولًا"
                    }
                    r == null -> {
                        routePts = null
                        map.setRoute(listOf(GeoPoint(la, lo), GeoPoint(tl, tn)))
                        routeText.text = "تعذّر إيجاد مسار على الشوارع المحمّلة (موقعك أو العداد بعيد عنها أو خارج المنطقة المحمّلة): خط مستقيم"
                    }
                    else -> {
                        routePts = r.points
                        map.setRoute(r.points)
                        refreshRouteGuidance()
                    }
                }
            }
        }
    }

    /** يحدّث نص التعليمات مع تحرك العامل، ويعيد الحساب إن خرج عن المسار. */
    private fun refreshRouteGuidance() {
        val pts = routePts ?: return
        val la = myLat ?: return
        val lo = myLon ?: return
        val rest = RouteGuide.remainingM(pts, la, lo)
        val mins = Math.max(1, Math.round(rest / RoadGraph.WALK_M_PER_MIN).toInt())
        routeText.text = "المتبقي ${fmtDist(rest)} · نحو $mins دقيقة مشيًا\n" + RouteGuide.instruction(pts, la, lo)
        val off = RouteGuide.offRouteM(pts, la, lo)
        if (off > 40 && !routeBusy && System.currentTimeMillis() - lastRouteAt > 6000L) {
            routeToTarget()
        }
    }

    // ---------------------------------------------------------------- تحميل الخارطة

    private fun startDownloadFlow() {
        if (downloader?.running == true) {
            toast("التحميل جارٍ بالفعل")
            return
        }
        worker.execute {
            val pts = try {
                roundDb.markers().map { GeoPoint(it.lat, it.lon) }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { confirmDownload(pts) }
        }
    }

    private fun confirmDownload(meterPoints: List<GeoPoint>) {
        val all = ArrayList(meterPoints)
        val la = myLat
        val lo = myLon
        if (all.isEmpty() && la != null && lo != null) all.add(GeoPoint(la, lo))
        val box = AreaPlan.bboxAround(all)
        if (box == null) {
            toast("لا توجد إحداثيات: استورد جولة فيها إحداثيات العدادات أولًا (أو حدّد موقعك)")
            return
        }
        val cells = AreaPlan.cellsFor(box)
        if (cells.size > AreaPlan.MAX_CELLS) {
            toast("المنطقة كبيرة جدًا (${cells.size} قطعة). الحد ${AreaPlan.MAX_CELLS}")
            return
        }
        val areaStore = AreaStore(this)
        val remaining = cells.count { !areaStore.has(it) }
        if (remaining == 0) {
            toast("الخارطة محمّلة لكامل المنطقة، وتعمل دون إنترنت")
            map.reloadTiles()
            return
        }
        val (wKm, hKm) = AreaPlan.sizeKm(box)
        val resume = remaining < cells.size
        val msg = "المنطقة حول عدادات الجولة (مع هامش ${AreaPlan.DEFAULT_MARGIN_M.toInt()} م): " +
            String.format(Locale.US, "%.1f × %.1f", wKm, hKm) + " كم\n" +
            "القطع: ${cells.size}" + (if (resume) " (المتبقي $remaining)" else "") + "\n" +
            "حجم التحميل التقريبي: ≈ ${AreaPlan.estimateDownloadMb(remaining)} ميغابايت (تقدير غير دقيق)\n" +
            "المساحة على الهاتف: ≈ ${AreaPlan.estimateStoredMb(cells.size)} ميغابايت\n\n" +
            "شوارع ومبانٍ ومياه من OpenStreetMap تُحمَّل مرة واحدة ثم تعمل دون إنترنت، بتكبير حتى 20 للأزقة. " +
            "يرى الخادم حدود المنطقة فقط، ولا تُرسل أي بيانات عن الزبائن أو الصور.\n" +
            "يمكنك الإلغاء، ويُكمَل لاحقًا من حيث توقف."
        AlertDialog.Builder(this)
            .setTitle(if (resume) "متابعة تحميل الخارطة؟" else "تحميل الخارطة؟")
            .setMessage(msg)
            .setPositiveButton(if (resume) "متابعة" else "ابدأ") { _, _ -> runDownload(cells) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun runDownload(cells: List<AreaCell>) {
        val dl = AreaDownloader(AreaStore(this))
        downloader = dl

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val text = TextView(this).apply {
            textSize = 15f
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            this.text = "جارٍ التحضير…"
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = cells.size
        }
        col.addView(text)
        col.addView(bar)
        progressText = text
        progressBar = bar
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressDialog = AlertDialog.Builder(this)
            .setTitle("تحميل الخارطة")
            .setView(col)
            .setCancelable(false)
            .setNegativeButton("إلغاء") { _, _ -> dl.cancel() }
            .show()

        dl.start(cells, object : AreaDownloader.Listener {
            override fun onProgress(done: Int, total: Int, bytes: Long) {
                runOnUiThread {
                    progressBar?.progress = done
                    val mb = bytes / (1024.0 * 1024.0)
                    progressText?.text = "اكتمل $done من $total قطعة (" +
                        String.format(Locale.US, "%.1f", mb) + " ميغابايت)"
                }
            }

            override fun onFinished() {
                runOnUiThread {
                    endDownloadUi()
                    map.reloadTiles()
                    toast("اكتمل التحميل. الخارطة تعمل الآن دون إنترنت")
                }
            }

            override fun onStopped(reason: String, cancelled: Boolean) {
                runOnUiThread {
                    endDownloadUi()
                    map.reloadTiles() // ما اكتمل من القطع يظهر فورًا
                    if (cancelled) {
                        toast("أُلغي التحميل. ما اكتمل محفوظ ويمكن المتابعة لاحقًا")
                    } else {
                        AlertDialog.Builder(this@MapActivity)
                            .setTitle("توقف التحميل")
                            .setMessage(reason + "\n\nما اكتمل من القطع محفوظ.")
                            .setPositiveButton("متابعة") { _, _ -> runDownload(cells) }
                            .setNegativeButton("إغلاق", null)
                            .show()
                    }
                }
            }
        })
    }

    private fun endDownloadUi() {
        progressDialog?.dismiss()
        progressDialog = null
        progressText = null
        progressBar = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_DOWNLOAD = "download"
        private const val REQ_LOCATION = 41
    }
}

/** نافذة إدخال مفتاح MapTiler (للقمر الصناعي والتضاريس). المفتاح يُحفظ في إعدادات الجهاز فقط. */
object MapKeyDialog {
    fun show(activity: Activity, onSaved: () -> Unit) {
        val s = Settings(activity)
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "مفتاح MapTiler"
            setText(s.maptilerKey)
        }
        AlertDialog.Builder(activity)
            .setTitle("مفتاح MapTiler")
            .setMessage(
                "للقمر الصناعي والتضاريس. سجّل في cloud.maptiler.com وانسخ مفتاح API هنا.\n\n" +
                    "تنبيه: الخطة المجانية للاستعمال غير التجاري فقط، وشروط MapTiler تمنع تحميل البلاطات " +
                    "بالجملة، لذلك هاتان الطبقتان تعملان بالإنترنت وتُخزَّنان مؤقتًا ليوم واحد فقط."
            )
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                s.maptilerKey = input.text.toString()
                if (s.maptilerKey.isNotEmpty()) onSaved()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}

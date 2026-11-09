package com.meterproof.app

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * الخارطة بملء الشاشة: تحريك بإصبع، تكبير بإصبعين أو بنقرتين، وضغط دائرة لعرض صورتها.
 * تستعمل الخارطة المحلية المستوردة إن وُجدت، ولا تحتاج إنترنت.
 */
class MapActivity : Activity() {

    private lateinit var store: EvidenceStore
    private lateinit var map: TrackMapView
    private lateinit var previewBox: LinearLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewText: TextView

    private var shownCount = -1
    private var shownTrack = -1
    private var shownEvents = -1
    private var shownTotals: RoundTotals? = null
    private val roundDb by lazy { RoundDb(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            reload()
            handler.postDelayed(this, 10_000L)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EvidenceStore(this)

        val root = FrameLayout(this)

        map = TrackMapView(this, interactive = true).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            onPhotoTap = { index -> showPhoto(index) }
            onMeterTap = { m -> MeterDialog.show(this@MapActivity, m, store) { idx -> showPhoto(idx) } }
        }
        root.addView(map)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun barButton(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }
        bar.addView(barButton("رجوع") { finish() })
        bar.addView(barButton("＋") { map.zoomBy(1.0) })
        bar.addView(barButton("－") { map.zoomBy(-1.0) })
        bar.addView(barButton("تمركز") { map.recenter() })
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(dp(8), dp(8), dp(8), 0) }
        )

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
        root.addView(
            previewBox,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

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
}

package com.meterproof.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.meterproof.app.PhotoLabels.Label
import java.io.File
import java.util.concurrent.Executors

/**
 * معرض الصور داخل التطبيق (كل النسخ): عرض الصور الملتقطة، تحديد متعدد، وتصنيف للتدريب
 * (فاتورة موضوعة / بلا فاتورة / غير واضح).
 *
 * التصنيف أحداث جديدة في السجل المبصوم (PHOTO_LABEL) ولا يمسّ الصور ولا سجلاتها.
 * لا يوجد حذف ولا مشاركة هنا عمدًا: الحذف ينتظر موافقة المالك على أسلوبه (انظر docs/ROADMAP.md، C2).
 * التدريب نفسه من الصور المحفوظة هو الخطوة التالية (C4)؛ هذه الشاشة تجهّز العينات وتعدّها فقط.
 */
class PhotoGalleryActivity : Activity() {

    private class Item(val id: String, val file: File, val flags: String)

    private enum class Filter(val title: String) {
        ALL("الكل"), UNLABELED("بلا تصنيف"), INVOICE("فاتورة"), NONE("بلا فاتورة"), UNCLEAR("غير واضح")
    }

    private lateinit var store: EvidenceStore
    private lateinit var header: TextView
    private lateinit var countsView: TextView
    private lateinit var grid: GridView
    private val filterButtons = LinkedHashMap<Filter, Button>()

    private var all: List<Item> = emptyList()        // الأحدث أولًا
    private var shown: List<Item> = emptyList()
    private var labels: Map<String, Label> = emptyMap()
    private var info: Map<String, String> = emptyMap()
    private var filter = Filter.ALL
    private val selected = LinkedHashSet<String>()

    private val ui = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EvidenceStore(this)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(28), dp(8), dp(8))
        }
        header = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(2)) }
        root.addView(header)
        countsView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, dp(6)) }
        root.addView(countsView)

        // مرشحات
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (f in Filter.values()) {
            val b = smallButton(f.title) { filter = f; applyFilter() }
            filterButtons[f] = b
            filterRow.addView(b)
        }
        root.addView(HorizontalScrollView(this).apply { addView(filterRow) })

        // تحديد
        val selRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selRow.addView(smallButton("تحديد المعروض") { selectShown() }, weight())
        selRow.addView(smallButton("إلغاء التحديد") { clearSelection() }, weight())
        root.addView(selRow)

        // تصنيف المحدد
        val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labelRow.addView(smallButton("فاتورة") { labelSelected(Label.INVOICE) }, weight())
        labelRow.addView(smallButton("بلا فاتورة") { labelSelected(Label.NONE) }, weight())
        labelRow.addView(smallButton("غير واضح") { labelSelected(Label.UNCLEAR) }, weight())
        labelRow.addView(smallButton("مسح") { labelSelected(null) }, weight())
        root.addView(labelRow)

        root.addView(smallButton("تدرّب على الصور المصنَّفة وأرني النتيجة") {
            val c = PhotoLabels.counts(labels)
            if (c.invoice + c.none < 4) {
                toast("صنّف بعض الصور أولًا (فاتورة / بلا فاتورة)")
            } else {
                startActivity(android.content.Intent(this, TrainingActivity::class.java))
            }
        })

        val cellSize = resources.displayMetrics.widthPixels / 3 - dp(8)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = object : BaseAdapter() {
                override fun getCount(): Int = shown.size
                override fun getItem(position: Int): Any = shown[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val cell: FrameLayout
                    val img: ImageView
                    val box: CheckBox
                    val badge: TextView
                    if (convertView == null) {
                        cell = FrameLayout(this@PhotoGalleryActivity)
                        cell.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellSize)
                        img = ImageView(this@PhotoGalleryActivity).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(Color.parseColor("#ECEFF1"))
                        }
                        box = CheckBox(this@PhotoGalleryActivity)
                        badge = TextView(this@PhotoGalleryActivity).apply {
                            textSize = 11f
                            setTextColor(Color.WHITE)
                            setPadding(dp(6), dp(2), dp(6), dp(2))
                        }
                        cell.addView(
                            img,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        cell.addView(
                            box,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                        cell.addView(
                            badge,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM or Gravity.START
                            )
                        )
                    } else {
                        cell = convertView as FrameLayout
                        img = cell.getChildAt(0) as ImageView
                        box = cell.getChildAt(1) as CheckBox
                        badge = cell.getChildAt(2) as TextView
                    }

                    val item = shown[position]
                    img.tag = item.id
                    val cached = cache.get(item.id)
                    if (cached != null) {
                        img.setImageBitmap(cached)
                    } else {
                        img.setImageBitmap(null)
                        loader.execute {
                            val bmp = decode(item.file, 320)
                            if (bmp != null) {
                                cache.put(item.id, bmp)
                                ui.post { if (img.tag == item.id) img.setImageBitmap(bmp) }
                            }
                        }
                    }
                    img.setOnClickListener { openViewer(item) }

                    val label = labels[item.id]
                    val usable = PhotoLabels.usableForTraining(item.flags)
                    when {
                        label != null -> {
                            badge.text = label.arabic
                            badge.setBackgroundColor(labelColor(label))
                            badge.visibility = View.VISIBLE
                        }
                        !usable -> {
                            badge.text = "مجرّدة"
                            badge.setBackgroundColor(Color.parseColor("#78909C"))
                            badge.visibility = View.VISIBLE
                        }
                        else -> badge.visibility = View.GONE
                    }

                    box.setOnCheckedChangeListener(null)
                    box.isChecked = selected.contains(item.id)
                    box.setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(item.id) else selected.remove(item.id)
                        updateHeader()
                    }
                    return cell
                }
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reload()
    }

    override fun onDestroy() {
        loader.shutdown()
        super.onDestroy()
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun labelColor(l: Label): Int = when (l) {
        Label.INVOICE -> Color.parseColor("#2E7D32")
        Label.NONE -> Color.parseColor("#C62828")
        Label.UNCLEAR -> Color.parseColor("#EF6C00")
    }

    // ---------------------------------------------------------------- البيانات

    private fun reload() {
        all = try {
            store.readRecords().reversed().mapNotNull { r ->
                val id = r.optString("id")
                val f = store.imageFile(id)
                if (id.isNotEmpty() && f.exists()) Item(id, f, r.optString("flags")) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
        info = store.describeRecords()
        labels = PhotoLabels.latest(store.events())
        selected.retainAll(all.map { it.id }.toSet())
        applyFilter()
    }

    private fun applyFilter() {
        shown = all.filter {
            val l = labels[it.id]
            when (filter) {
                Filter.ALL -> true
                Filter.UNLABELED -> l == null
                Filter.INVOICE -> l == Label.INVOICE
                Filter.NONE -> l == Label.NONE
                Filter.UNCLEAR -> l == Label.UNCLEAR
            }
        }
        for ((f, b) in filterButtons) b.isEnabled = f != filter
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        updateHeader()
    }

    private fun updateHeader() {
        header.text = "الصور: ${all.size}   المعروض: ${shown.size}   المحدد: ${selected.size}"
        val c = PhotoLabels.counts(labels)
        val need = PhotoLabels.MIN_PER_CLASS
        val ready = if (PhotoLabels.readyForTraining(c)) {
            "العينات تكفي للتدريب (التدريب من الصور المحفوظة في الخطوة التالية)"
        } else {
            "يلزم $need فاتورة و$need بلا فاتورة على الأقل للتدريب"
        }
        countsView.text = "فاتورة: ${c.invoice}   بلا فاتورة: ${c.none}   غير واضح: ${c.unclear}\n$ready"
    }

    private fun selectShown() {
        shown.forEach { selected.add(it.id) }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        updateHeader()
    }

    private fun clearSelection() {
        selected.clear()
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        updateHeader()
    }

    // ---------------------------------------------------------------- التصنيف

    private fun labelSelected(label: Label?) {
        if (selected.isEmpty()) {
            toast("لم تحدد أي صورة")
            return
        }
        val n = writeLabels(selected.toList(), label)
        selected.clear()
        reload()
        toast(if (label == null) "تم مسح تصنيف $n صورة" else "صُنّفت $n صورة: ${label.arabic}")
    }

    /** يكتب حدثًا جديدًا لكل صورة تغيّر تصنيفها فقط (لا أحداث مكررة). */
    private fun writeLabels(ids: List<String>, label: Label?): Int {
        val now = System.currentTimeMillis()
        var n = 0
        for (id in ids) {
            if (labels[id] == label) continue
            val detail = PhotoLabels.detail(id, label) ?: continue
            store.addEvent(PhotoLabels.EVENT, now, null, null, null, detail)
            n++
        }
        return n
    }

    // ---------------------------------------------------------------- العرض الكبير

    /** يفك ضغط الصورة بحجم مناسب لتوفير الذاكرة. */
    private fun decode(f: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun openViewer(item: Item) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        col.addView(
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(decode(item.file, 1600))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        fun refreshText() {
            val l = labels[item.id]
            tv.text = (info[item.id] ?: "") + "\nالتصنيف: " + (l?.arabic ?: "بلا تصنيف")
        }
        refreshText()
        col.addView(tv)

        fun setLabel(l: Label?) {
            writeLabels(listOf(item.id), l)
            labels = PhotoLabels.latest(store.events())
            refreshText()
            applyFilter()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("فاتورة") { setLabel(Label.INVOICE) }, weight())
        row.addView(smallButton("بلا فاتورة") { setLabel(Label.NONE) }, weight())
        row.addView(smallButton("غير واضح") { setLabel(Label.UNCLEAR) }, weight())
        row.addView(smallButton("مسح") { setLabel(null) }, weight())
        col.addView(row)
        col.addView(smallButton("إغلاق") { dialog.dismiss() })
        dialog.setContentView(col)
        dialog.show()
    }
}

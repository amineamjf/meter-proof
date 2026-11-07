package com.meterproof.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * أداة تجربة (نسخة debug فقط): معرض الصور المصغّرة، عرض كبير بمعلومات الموقع،
 * مشاركة صور محددة، أو مشاركة كل شيء كملف ZIP (صور + سجلات + خارطة).
 * مكانها src/debug، فلا تدخل نسخة release التي تُثبَّت على هواتف العمال.
 */
class GalleryActivity : Activity() {

    private lateinit var store: EvidenceStore
    private lateinit var header: TextView
    private lateinit var grid: GridView

    private var files: List<File> = emptyList() // الأحدث أولًا
    private var info: Map<String, String> = emptyMap()
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
        header = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(header)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(smallButton("تحديد الكل") { selectAll() }, weight())
        row1.addView(smallButton("إلغاء التحديد") { clearSelection() }, weight())
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(smallButton("مشاركة المحدد") { shareSelected() }, weight())
        row2.addView(smallButton("مشاركة الكل (ZIP)") { shareZip() }, weight())
        root.addView(row2)

        val cellSize = resources.displayMetrics.widthPixels / 3 - dp(8)
        grid = GridView(this).apply {
            numColumns = 3
            horizontalSpacing = dp(4)
            verticalSpacing = dp(4)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = object : BaseAdapter() {
                override fun getCount(): Int = files.size
                override fun getItem(position: Int): Any = files[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val cell: FrameLayout
                    val img: ImageView
                    val box: CheckBox
                    if (convertView == null) {
                        cell = FrameLayout(this@GalleryActivity)
                        cell.layoutParams = AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, cellSize
                        )
                        img = ImageView(this@GalleryActivity).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(Color.parseColor("#ECEFF1"))
                        }
                        box = CheckBox(this@GalleryActivity)
                        cell.addView(
                            img,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        cell.addView(
                            box,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                    } else {
                        cell = convertView as FrameLayout
                        img = cell.getChildAt(0) as ImageView
                        box = cell.getChildAt(1) as CheckBox
                    }

                    val f = files[position]
                    img.tag = f.name
                    val cached = cache.get(f.name)
                    if (cached != null) {
                        img.setImageBitmap(cached)
                    } else {
                        img.setImageBitmap(null)
                        loader.execute {
                            val bmp = decode(f, 320)
                            if (bmp != null) {
                                cache.put(f.name, bmp)
                                ui.post { if (img.tag == f.name) img.setImageBitmap(bmp) }
                            }
                        }
                    }
                    img.setOnClickListener { openViewer(position) }

                    box.setOnCheckedChangeListener(null)
                    box.isChecked = selected.contains(f.name)
                    box.setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(f.name) else selected.remove(f.name)
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

    private fun reload() {
        files = store.imageFiles().reversed()
        info = store.describeRecords()
        selected.retainAll(files.map { it.name }.toSet())
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        updateHeader()
    }

    private fun updateHeader() {
        header.text = "الصور: ${files.size}   المحدد: ${selected.size}   (اضغط الصورة لعرضها كبيرة)"
    }

    private fun selectAll() {
        selected.clear()
        files.forEach { selected.add(it.name) }
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        updateHeader()
    }

    private fun clearSelection() {
        selected.clear()
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        updateHeader()
    }

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

    private fun openViewer(position: Int) {
        val f = files.getOrNull(position) ?: return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(decode(f, 1600))
        }
        col.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = info[f.nameWithoutExtension] ?: ""
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        col.addView(tv)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallButton("مشاركة") { shareFiles(listOf(f)) }, weight())
        row.addView(smallButton("إغلاق") { dialog.dismiss() }, weight())
        col.addView(row)
        dialog.setContentView(col)
        dialog.show()
    }

    // ---------------------------------------------------------------- المشاركة

    private fun shareSelected() {
        shareFiles(files.filter { selected.contains(it.name) })
    }

    private fun shareFiles(list: List<File>) {
        if (list.isEmpty()) {
            toast("لم تحدد أي صورة")
            return
        }
        try {
            val uris = ArrayList<Uri>()
            for (f in list) uris.add(FileProvider.getUriForFile(this, "$packageName.files", f))
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            intent.type = "image/jpeg"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "مشاركة الصور"))
        } catch (e: Exception) {
            toast("تعذرت المشاركة: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun shareZip() {
        if (files.isEmpty()) {
            toast("لا توجد صور")
            return
        }
        toast("جارٍ تجهيز الملف المضغوط، انتظر قليلًا")
        Thread {
            try {
                val zip = buildZip()
                ui.post { shareZipFile(zip) }
            } catch (e: Exception) {
                ui.post { toast("فشل تجهيز الملف: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun shareZipFile(zip: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
            val intent = Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, uri)
            intent.type = "application/zip"
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "مشاركة الملف المضغوط"))
        } catch (e: Exception) {
            toast("تعذرت المشاركة: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** ZIP: images/ + records.jsonl + events.jsonl + track.jsonl + verification.txt + map.html */
    private fun buildZip(): File {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()
            ?.filter { it.name.startsWith("meterproof_") && it.name.endsWith(".zip") }
            ?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "meterproof_$stamp.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { z ->
            z.setLevel(1)
            fun addBytes(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
            fun addFile(name: String, f: File) {
                if (!f.exists()) return
                z.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            for (f in store.imageFiles()) addFile("images/${f.name}", f)
            addFile("records.jsonl", store.recordsFileRaw())
            addFile("events.jsonl", store.eventsFileRaw())
            addFile("track.jsonl", store.trackFileRaw())
            addBytes("verification.txt", (store.verify() + "\n").toByteArray(Charsets.UTF_8))
            val html = MapHtml.build(
                store.trackPoints(), store.photoPoints(),
                StopPairing.pair(store.events()), withImages = true
            )
            addBytes("map.html", html.toByteArray(Charsets.UTF_8))
        }
        return out
    }
}

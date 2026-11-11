package com.meterproof.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * غلاف الشاشة الرئيسية: شريط علوي بزر القائمة وعنوان الصفحة، ومحتوى الصفحة الحالية، وقائمة جانبية
 * تنزلق من جهة البداية (اليمين في العربية) للتنقل بين الصفحات. تصميم أولي بلا مكتبات إضافية.
 *
 * لا يملك أي منطق عمل: يعرض [Dest.content] المُسلَّمة إليه فقط. الصفحات التي ليست معروضة تبقى
 * في الذاكرة بحالتها (حقولها وعناصرها حية)، فلا يضيع نص مكتوب عند التبديل.
 */
class SideMenu(
    private val activity: Activity,
    private val dests: List<Dest>,
    startId: String
) {
    class Dest(val id: String, val title: String, val content: View)

    private val cBg = Ui.BG
    private val cInk = Ui.INK
    private val cLine = Ui.BORDER

    private fun iconFor(id: String): Int = when (id) {
        NavPages.Page.HOME.id -> Ui.Icon.HOME
        NavPages.Page.PHOTOS.id -> Ui.Icon.PHOTOS
        NavPages.Page.MAP.id -> Ui.Icon.MAP
        NavPages.Page.ROUND.id -> Ui.Icon.PIN
        NavPages.Page.SETTINGS.id -> Ui.Icon.SETTINGS
        NavPages.Page.EXPORT.id -> Ui.Icon.EXPORT
        else -> Ui.Icon.LIST
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    val root: FrameLayout = FrameLayout(activity)
    private val titleView = TextView(activity)
    private val scroll = ScrollView(activity)
    private val scrim = View(activity)
    private val drawer = LinearLayout(activity)
    private val items = LinkedHashMap<String, Button>()

    var currentId: String = dests.first().id
        private set

    init {
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(cBg)

        // المحتوى: شريط علوي + صفحة
        val main = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(8), dp(28), dp(12), dp(8))
        }
        bar.addView(
            Button(activity).apply {
                Ui.style(this, Ui.Kind.GHOST, null, compact = true)
                setCompoundDrawablesRelative(Ui.icon(activity, Ui.Icon.MENU, cInk, 24), null, null, null)
                setPadding(dp(14), 0, 0, 0)
                contentDescription = "القائمة"
                setOnClickListener { toggle() }
            },
            LinearLayout.LayoutParams(dp(52), dp(44))
        )
        titleView.apply {
            textSize = 18f
            Ui.styleHeading(this)
            setPadding(dp(4), 0, 0, 0)
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        main.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        main.addView(
            View(activity).apply { setBackgroundColor(cLine) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        )
        scroll.setBackgroundColor(cBg)
        main.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(main, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ستار يغلق القائمة عند الضغط خارجها
        scrim.apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            visibility = View.GONE
            setOnClickListener { close() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // القائمة الجانبية
        drawer.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(14), dp(36), dp(14), dp(14))
            visibility = View.GONE
            isClickable = true // لا تمرّر اللمس إلى ما تحتها
        }
        drawer.addView(
            TextView(activity).apply {
                text = "MeterProof"
                textSize = 20f
                Ui.styleHeading(this)
                setPadding(dp(4), 0, 0, dp(12))
            }
        )
        for (d in dests) {
            val b = Button(activity).apply {
                text = d.title
                Ui.style(this, Ui.Kind.GHOST, iconFor(d.id))
                setOnClickListener { show(d.id); close() }
            }
            items[d.id] = b
            drawer.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }
        root.addView(
            drawer,
            FrameLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        )

        show(startId)
    }

    private fun rounded(fill: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** يعرض صفحة بمعرّفها؛ معرّف مجهول يترك الصفحة الحالية كما هي. */
    fun show(id: String) {
        val d = dests.firstOrNull { it.id == id } ?: return
        currentId = d.id
        titleView.text = d.title
        (d.content.parent as? ViewGroup)?.removeView(d.content)
        scroll.removeAllViews()
        scroll.addView(d.content)
        scroll.scrollTo(0, 0)
        for ((k, b) in items) {
            val on = k == d.id
            Ui.style(b, if (on) Ui.Kind.TONAL else Ui.Kind.GHOST, iconFor(k))
        }
    }

    val isOpen: Boolean get() = drawer.visibility == View.VISIBLE

    fun open() {
        scrim.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
    }

    fun close() {
        scrim.visibility = View.GONE
        drawer.visibility = View.GONE
    }

    fun toggle() = if (isOpen) close() else open()

    /**
     * زر الرجوع: يغلق القائمة إن كانت مفتوحة، وإلا يعود من أي صفحة إلى [homeId] أولًا.
     * يعيد true إن عالج الضغطة، وfalse لترك السلوك الافتراضي (الخروج).
     */
    fun handleBack(homeId: String): Boolean {
        if (isOpen) {
            close()
            return true
        }
        if (currentId != homeId) {
            show(homeId)
            return true
        }
        return false
    }
}

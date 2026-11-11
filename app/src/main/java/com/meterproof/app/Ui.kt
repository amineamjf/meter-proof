package com.meterproof.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * نظام التصميم الموحّد: ألوان دافئة هادئة، أزرار مسطحة بتموّج لمس، أيقونات خطية (vector)،
 * وبطاقات بحدود رفيعة بلا ظلال. لا مكتبات خارجية ولا موارد غير الأيقونات.
 *
 * الشكل فقط: لا يغيّر سلوك أي زر ولا منطقه.
 */
object Ui {

    // ---- اللوحة
    val BG = Color.parseColor("#FAF9F5")
    val SURFACE = Color.parseColor("#FFFFFF")
    val BORDER = Color.parseColor("#E8E6DC")
    val INK = Color.parseColor("#141413")
    val MUTED = Color.parseColor("#6E6D66")
    val ACCENT = Color.parseColor("#BD5D3A")
    val ACCENT_PRESSED = Color.parseColor("#A04B2C")
    val ACCENT_SOFT = Color.parseColor("#F4E7E0")
    val ACCENT_INK = Color.parseColor("#8F3F22")
    val DANGER = Color.parseColor("#B3261E")
    val DISABLED_FILL = Color.parseColor("#EFEDE6")
    val DISABLED_INK = Color.parseColor("#A8A69D")

    enum class Kind { PRIMARY, SECONDARY, TONAL, DANGER, GHOST }

    /** أيقونات الموارد (res/drawable/ic_*.xml). */
    object Icon {
        val MENU = R.drawable.ic_menu
        val HOME = R.drawable.ic_home
        val PHOTOS = R.drawable.ic_photos
        val MAP = R.drawable.ic_map
        val PIN = R.drawable.ic_pin
        val SETTINGS = R.drawable.ic_settings
        val EXPORT = R.drawable.ic_export
        val TRASH = R.drawable.ic_trash
        val CHECK = R.drawable.ic_check
        val CLOSE = R.drawable.ic_close
        val TAG = R.drawable.ic_tag
        val SPARKLE = R.drawable.ic_sparkle
        val PLAY = R.drawable.ic_play
        val PAUSE = R.drawable.ic_pause
        val STOP = R.drawable.ic_stop
        val SHIELD = R.drawable.ic_shield
        val LAYERS = R.drawable.ic_layers
        val REFRESH = R.drawable.ic_refresh
        val FILE = R.drawable.ic_file
        val PLUS = R.drawable.ic_plus
        val LOCK = R.drawable.ic_lock
        val BATTERY = R.drawable.ic_battery
        val DOWNLOAD = R.drawable.ic_download
        val WEB = R.drawable.ic_web
        val BOLT = R.drawable.ic_bolt
        val LIST = R.drawable.ic_list
        val BACK = R.drawable.ic_back
        val NEXT = R.drawable.ic_next
    }

    private const val SKIN_TAG = "ui-skinned"

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(c: Context, v: Int): Float = v * c.resources.displayMetrics.density

    fun shape(c: Context, fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(c, radiusDp)
            if (stroke != 0) setStroke(dp(c, 1), stroke)
        }

    /** أيقونة ملوّنة بحجم [sizeDp]، أو null إن لم تُحمَّل. */
    fun icon(c: Context, res: Int, color: Int, sizeDp: Int = 20) = try {
        c.getDrawable(res)?.mutate()?.apply {
            setTint(color)
            val s = dp(c, sizeDp)
            setBounds(0, 0, s, s)
        }
    } catch (e: Exception) {
        null
    }

    /** يطبّق نمط الزر على زر موجود (نصه وسلوكه كما هما). */
    fun style(b: Button, kind: Kind = Kind.SECONDARY, icon: Int? = null, compact: Boolean = false) {
        val c = b.context
        val fill: Int
        val pressed: Int
        val ink: Int
        val stroke: Int
        when (kind) {
            Kind.PRIMARY -> { fill = ACCENT; pressed = ACCENT_PRESSED; ink = Color.WHITE; stroke = 0 }
            Kind.SECONDARY -> { fill = SURFACE; pressed = Color.parseColor("#F1EFE7"); ink = INK; stroke = BORDER }
            Kind.TONAL -> { fill = ACCENT_SOFT; pressed = Color.parseColor("#EBD5CB"); ink = ACCENT_INK; stroke = 0 }
            Kind.DANGER -> { fill = SURFACE; pressed = Color.parseColor("#FBE9E7"); ink = DANGER; stroke = Color.parseColor("#E6B8B3") }
            Kind.GHOST -> { fill = Color.TRANSPARENT; pressed = Color.parseColor("#14000000"); ink = INK; stroke = 0 }
        }
        val radius = 12
        val normal = shape(c, fill, radius, stroke)
        val off = shape(c, DISABLED_FILL, radius)
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), off)
            addState(intArrayOf(), normal)
        }
        val mask = shape(c, Color.WHITE, radius)
        b.background = RippleDrawable(ColorStateList.valueOf(pressed), states, mask)
        b.stateListAnimator = null
        b.elevation = 0f
        b.isAllCaps = false
        b.textSize = if (compact) 13f else 15f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(DISABLED_INK, ink)
            )
        )
        b.minHeight = dp(c, if (compact) 40 else 48)
        b.minimumHeight = dp(c, if (compact) 40 else 48)
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8))
        b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        if (icon != null) {
            val d = icon(c, icon, ink, if (compact) 18 else 20)
            b.setCompoundDrawablesRelative(d, null, null, null)
            b.compoundDrawablePadding = dp(c, 10)
        } else {
            b.setCompoundDrawablesRelative(null, null, null, null)
            b.gravity = Gravity.CENTER
        }
        b.tag = SKIN_TAG
    }

    /** زر جديد منسَّق. */
    fun button(
        c: Context, text: String, kind: Kind = Kind.SECONDARY, icon: Int? = null,
        compact: Boolean = false, onClick: () -> Unit
    ): Button = Button(c).apply {
        this.text = text
        style(this, kind, icon, compact)
        setOnClickListener { onClick() }
    }

    /** بطاقة بيضاء بحد رفيع، رأسية. */
    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(c, SURFACE, 16, BORDER)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16))
    }

    /**
     * يمرّ على شجرة العرض ويُنسّق كل زر عادي لم يُنسَّق بعد (الأزرار الافتراضية للنظام)
     * بنمط ثانوي. يترك الأزرار المنسَّقة وأي عنصر آخر كما هو.
     */
    fun skin(root: View) {
        if (root is Button) {
            if (root.tag != SKIN_TAG) style(root, Kind.SECONDARY, null, compact = true)
            return
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) skin(root.getChildAt(i))
    }

    /** نص عنوان صغير بأيقونة اختيارية (لعنوان البطاقة). */
    fun styleHeading(t: TextView) {
        t.setTextColor(INK)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /**
     * مظهر النافذة: شريط الحالة بلون الخلفية بأيقونات داكنة، ثم تنسيق الأزرار غير المنسَّقة.
     * يُستدعى بعد setContentView في كل شاشة. الشكل فقط.
     */
    fun apply(activity: android.app.Activity) {
        try {
            val w = activity.window
            w.setBackgroundDrawable(ColorDrawable(BG))
            w.statusBarColor = BG
            w.navigationBarColor = BG
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            skin(w.decorView)
        } catch (e: Exception) {
            // الشكل لا يُسقط الشاشة
        }
    }

    @Suppress("unused")
    private val unused = ColorDrawable(0)
}

package com.meterproof.app

import java.security.MessageDigest

/**
 * طبقات بلاطات الخارطة ورابطها. منطق نقي بلا أندرويد (يُختبر على JVM).
 * منقول من آلية خارطة smart-remplacement: مخطط (CARTO Voyager) وقمر صناعي (Esri)، أو رابط مخصص.
 * رابط الطبقة قالب فيه {z} و{x} و{y} (وترتيبها يختلف بين المزوّدين، فلا يُفترض ترتيب).
 */
object TileLayers {
    /**
     * الخارطة العادية الأساسية: Esri World Street Map. اعتُمدت لأن القمر الصناعي (Esri أيضًا) يعمل على
     * أجهزة المالك بينما ظهرت خلفية CARTO رمادية فارغة. الترتيب {z}/{y}/{x} كالقمر الصناعي.
     */
    const val PLAN = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}"

    /** الخارطة العادية البديلة (CARTO Voyager)، تُجرَّب تلقائيًا إن فشلت الأساسية بالتتابع. */
    const val PLAN_ALT = "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png"

    const val SAT = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** الرابط الفعّال: المخصص إن وُجد، وإلا القمر الصناعي إن طُلب، وإلا المخطط. */
    fun resolve(custom: String, satellite: Boolean = false): String =
        custom.trim().ifEmpty { if (satellite) SAT else PLAN }

    /** القمر الصناعي فقط (World_Imagery). الخارطة العادية من Esri أيضًا فلا يكفي البحث عن اسم المزوّد. */
    fun isSatellite(template: String): Boolean = template.contains("World_Imagery")

    private fun isEsriStreet(template: String): Boolean = template.trim() == PLAN

    /** البديل التلقائي للخارطة العادية، أو null إن لم يكن [template] خارطة عادية افتراضية. */
    fun fallbackFor(template: String): String? = when (template.trim()) {
        PLAN -> PLAN_ALT
        PLAN_ALT -> PLAN
        else -> null
    }

    /** نص النسب الذي يُرسم على الخارطة (مطلوب مع بيانات OpenStreetMap وصور Esri). */
    fun attribution(template: String): String = when {
        isSatellite(template) -> "© Esri"
        isEsriStreet(template) -> "© Esri © OpenStreetMap"
        else -> "© OpenStreetMap © CARTO"
    }

    /**
     * نص النسب المطبوع على خرائط تقرير PDF. للقمر الصناعي هو سطر الإسناد المنشور في صفحة World Imagery
     * الرسمية لدى Esri («Sources: Esri, Maxar, Earthstar Geographics, and the GIS User Community»).
     * لا نسب ديناميكية بحسب الموقع (مزوّدو الصور يختلفون بين المناطق)، ولم تُراجَع شروط الاستخدام
     * نفسها: انظر docs/MAP_TILES.md. للرابط المخصص لا نعرف المزوّد، فيُذكر ذلك صراحة.
     */
    fun reportAttribution(template: String): String = when {
        isSatellite(template) -> "Sources: Esri, Maxar, Earthstar Geographics, and the GIS User Community"
        isEsriStreet(template) -> "Sources: Esri, HERE, Garmin, © OpenStreetMap contributors, and the GIS User Community"
        template.trim() == PLAN_ALT -> "© OpenStreetMap contributors © CARTO"
        else -> "خلفية الخارطة: مزوّد مخصص"
    }

    fun build(template: String, z: Int, x: Int, y: Int): String =
        template.trim().replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    /** null = صالح (والفارغ صالح ويعني الافتراضي)، وإلا رسالة الخطأ. HTTPS فقط حمايةً للمفاتيح. */
    fun validate(custom: String): String? {
        val t = custom.trim()
        if (t.isEmpty()) return null
        if (!t.startsWith("https://")) return "الرابط يجب أن يبدأ بـ https://"
        for (k in listOf("{z}", "{x}", "{y}")) if (!t.contains(k)) return "الرابط ينقصه $k"
        if (t.any { it.isWhitespace() }) return "الرابط يحتوي مسافة"
        if (t.length > 500) return "الرابط طويل جدًا"
        return null
    }

    /** معرّف قصير ثابت للقالب، كي لا تختلط بلاطات طبقتين في المخزن. */
    fun id(template: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(template.trim().toByteArray(Charsets.UTF_8))
        return d.take(6).joinToString("") { "%02x".format(it) }
    }

    fun key(template: String, z: Int, x: Int, y: Int): String = id(template) + "|" + z + "/" + x + "/" + y

    /** متوسط حجم البلاطة بالكيلوبايت (تقدير للعرض فقط، يختلف بين المزوّدين). */
    fun avgKb(template: String): Int = if (isSatellite(template)) 35 else 15
}

/**
 * قرار التحويل التلقائي بين مزوّدَي الخارطة العادية. نقي بلا أندرويد.
 * يعدّ إخفاقات الجلب المتتالية (أي اتصال تمّ ثم رفضه المزوّد أو لم يرجع صورة)، ونجاح واحد يصفّر العدّاد.
 * لا يُحسب غياب الإنترنت إخفاقًا لأنه لا يعني أن المزوّد معطوب.
 */
class TileFailover(private val threshold: Int = 3) {
    private var consecutive = 0

    /** true عند بلوغ [threshold] إخفاقات متتالية: حان وقت تجربة المزوّد الآخر (ويصفّر العدّاد). */
    fun onFailure(): Boolean {
        consecutive++
        if (consecutive < threshold) return false
        consecutive = 0
        return true
    }

    fun onSuccess() {
        consecutive = 0
    }
}

package com.meterproof.app

import java.security.MessageDigest

/**
 * طبقات بلاطات الخارطة ورابطها. منطق نقي بلا أندرويد (يُختبر على JVM).
 * منقول من آلية خارطة smart-remplacement: مخطط (CARTO Voyager) وقمر صناعي (Esri)، أو رابط مخصص.
 * رابط الطبقة قالب فيه {z} و{x} و{y} (وترتيبها يختلف بين المزوّدين، فلا يُفترض ترتيب).
 */
object TileLayers {
    const val PLAN = "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png"
    const val SAT = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** الرابط الفعّال: المخصص إن وُجد، وإلا القمر الصناعي إن طُلب، وإلا المخطط. */
    fun resolve(custom: String, satellite: Boolean = false): String =
        custom.trim().ifEmpty { if (satellite) SAT else PLAN }

    fun isSatellite(template: String): Boolean = template.contains("arcgis")

    /** نص النسب الذي يُرسم على الخارطة (مطلوب مع بيانات OpenStreetMap وصور Esri). */
    fun attribution(template: String): String =
        if (isSatellite(template)) "© Esri" else "© OpenStreetMap © CARTO"

    /**
     * نص النسب المطبوع على خرائط تقرير PDF. للقمر الصناعي هو سطر الإسناد المنشور في صفحة World Imagery
     * الرسمية لدى Esri («Sources: Esri, Maxar, Earthstar Geographics, and the GIS User Community»).
     * لا نسب ديناميكية بحسب الموقع (مزوّدو الصور يختلفون بين المناطق)، ولم تُراجَع شروط الاستخدام
     * نفسها: انظر docs/MAP_TILES.md. للرابط المخصص لا نعرف المزوّد، فيُذكر ذلك صراحة.
     */
    fun reportAttribution(template: String): String = when {
        isSatellite(template) -> "Sources: Esri, Maxar, Earthstar Geographics, and the GIS User Community"
        template.trim() == PLAN -> "© OpenStreetMap contributors © CARTO"
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

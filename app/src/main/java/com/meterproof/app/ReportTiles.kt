package com.meterproof.app

import android.content.Context

/**
 * بلاطات خرائط تقرير PDF: أي طبقة تُستعمل، وما الناقص منها في مخزن الجهاز، وجلبه عند الطلب (D2).
 *
 * الجلب لا يجري إلا بموافقة صريحة من المستخدم في حوار التصدير (فهو يكشف للمزوّد منطقة عملك)،
 * وبالعدد الذي تحتاجه خرائط هذا التقرير فقط (سقف [ReportMapPlanner.MAX_TILES_PER_MAP] لكل خارطة)،
 * عبر المنزِّل القائم الذي يتوقف عند رفض المزوّد المتكرر ولا يعيد تنزيل المخزّن.
 */
object ReportTiles {

    /** رابط طبقة التقرير: المخصص إن وُجد، وإلا القمر الصناعي (المطلوب لخارطة التقرير). */
    fun template(context: Context): String =
        TileLayers.resolve(Settings(context).tileUrl, satellite = true)

    class Prep(val template: String, val needed: List<TileId>, val missing: List<TileId>) {
        /** حجم الناقص بالميغابايت (تقدير للعرض فقط، بمنزلة واحدة عشرية). */
        fun estimateMb(): String =
            "%.1f".format(java.util.Locale.US, missing.size * TileLayers.avgKb(template) / 1024.0)
    }

    fun prepare(context: Context, plan: ReportMapPlan): Prep {
        val template = template(context)
        val needed = plan.allTiles()
        val cache = TileCache.get(context)
        val missing = needed.filter { !cache.has(TileLayers.key(template, it.z, it.x, it.y)) }
        return Prep(template, needed, missing)
    }

    /** يجلب الناقص إلى المخزن. يُستدعى من خيط خلفي فقط. */
    fun fetch(context: Context, prep: Prep, cancelled: () -> Boolean = { false }): DownloadOutcome =
        TileDownloader(HttpTileFetcher(), TileCache.get(context), prep.template).run(prep.missing, cancelled)

    /** مصدر للتقرير يقرأ من المخزن بلا شبكة. */
    fun source(context: Context): ReportMapSource {
        val t = template(context)
        return ReportMapSource(t, CacheTileSource(context, t))
    }
}

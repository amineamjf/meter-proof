package com.meterproof.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** طبقات الخارطة: شوارع (من بيانات OSM المحمّلة)، قمر صناعي، تضاريس (الأخيرتان من MapTiler عبر الإنترنت). */
enum class MapLayer(val label: String) {
    STREETS("شوارع"),
    SATELLITE("قمر صناعي"),
    TERRAIN("تضاريس");

    fun next(): MapLayer = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(name: String): MapLayer = values().firstOrNull { it.name == name } ?: STREETS
    }
}

/** مصدر بلاطات صور (256 بكسل، x من الغرب وy من الشمال). */
interface TileSource {
    val minZoom: Int
    val maxZoom: Int
    val attribution: String
    fun tile(z: Int, x: Int, y: Int): ByteArray?
    fun close()
}

/** ملف MBTiles محلي كمصدر بلاطات. */
class MbTilesSource(private val mb: MbTiles) : TileSource {
    override val minZoom: Int get() = mb.minZoom
    override val maxZoom: Int get() = mb.maxZoom
    override val attribution: String get() = mb.attribution
    override fun tile(z: Int, x: Int, y: Int): ByteArray? = mb.tile(z, x, y)
    override fun close() = mb.close()
}

/**
 * بلاطات MapTiler (قمر صناعي أو تضاريس) عبر الإنترنت بمفتاح المستخدم.
 *
 * شروط MapTiler Cloud (القسم 6.3) تمنع التحميل الجماعي للبلاطات دون اتفاق كتابي، وتسمح بتخزين
 * مؤقت شخصي لمستخدم واحد (القسم 5.7). لذلك لا نحمّل مسبقًا ولا نخزّن دائمًا: تُجلب البلاطات التي
 * يراها المستخدم فقط، وتُحفظ مؤقتًا في مجلد الكاش ليوم واحد ثم تُحذف.
 * الخطة المجانية لا تصلح للاستعمال التجاري (القسم 1.2).
 */
class OnlineTileSource(
    context: Context,
    private val layer: MapLayer,
    private val key: String
) : TileSource {

    override val minZoom: Int = 0
    override val maxZoom: Int = 18
    override val attribution: String = "© MapTiler © OpenStreetMap contributors"

    /** آخر خطأ مفهوم للعرض (مفتاح مرفوض، حصة منتهية…)، أو null. */
    @Volatile var lastError: String? = null
        private set

    private val dir = File(context.applicationContext.cacheDir, "maptiler/${layer.name}").apply { mkdirs() }

    init {
        purgeOld()
    }

    companion object {
        const val TTL_MS = 24L * 60 * 60 * 1000
        private const val USER_AGENT = "MeterProof-Android/0.2"

        /** رابط البلاطة. معرّفا الطبقتين ثابتان هنا ليسهل تغييرهما إن غيّرت MapTiler أسماءهما. */
        fun url(layer: MapLayer, z: Int, x: Int, y: Int, key: String): String? = when (layer) {
            MapLayer.SATELLITE -> "https://api.maptiler.com/maps/satellite/256/$z/$x/$y.jpg?key=$key"
            MapLayer.TERRAIN -> "https://api.maptiler.com/maps/outdoor-v2/256/$z/$x/$y.png?key=$key"
            MapLayer.STREETS -> null
        }

        fun errorFor(code: Int): String = when (code) {
            401, 403 -> "مفتاح MapTiler مرفوض أو غير صالح ($code)"
            404 -> "الطبقة غير متاحة على MapTiler (404)"
            429 -> "انتهت حصة MapTiler المجانية (429)"
            else -> "خطأ من MapTiler ($code)"
        }
    }

    private fun purgeOld() {
        val now = System.currentTimeMillis()
        (dir.listFiles() ?: return).forEach { f ->
            if (now - f.lastModified() > TTL_MS) f.delete()
        }
    }

    override fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val f = File(dir, "${z}_${x}_$y")
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < TTL_MS) {
            try {
                return f.readBytes()
            } catch (e: Exception) {
                // نجرّب الشبكة
            }
        }
        val u = url(layer, z, x, y, key) ?: return null
        val c = try {
            URL(u).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            c.connectTimeout = 8_000
            c.readTimeout = 12_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code == 200) {
                lastError = null
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size in 1..(3 * 1024 * 1024)) {
                    try {
                        val tmp = File(dir, "${z}_${x}_$y.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(f)) tmp.delete()
                    } catch (e: Exception) {
                        // الكاش اختياري
                    }
                    bytes
                } else {
                    null
                }
            } else {
                if (code != 204) lastError = errorFor(code)
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    override fun close() {
        // لا موارد مفتوحة
    }
}

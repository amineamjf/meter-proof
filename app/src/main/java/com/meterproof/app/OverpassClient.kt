package com.meterproof.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * يجلب بيانات الشوارع والمباني والمياه من OpenStreetMap عبر واجهة Overpass (بيانات، لا بلاطات).
 * بيانات OSM مرخّصة ODbL وتسمح بالتنزيل، بخلاف خادم البلاطات العام الذي يمنع التحميل بالجملة.
 *
 * لطف مع الخوادم العامة: طلب واحد في كل مرة لقطعة صغيرة، تأخير بين القطع، تعريف واضح بالتطبيق،
 * وتبديل الخادم عند الخطأ.
 */
class OverpassClient(
    private val mirrors: List<String> = DEFAULT_MIRRORS
) {

    companion object {
        val DEFAULT_MIRRORS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        private const val USER_AGENT = "MeterProof-Android/0.2 (offline street map download)"
        private const val MAX_BYTES = 60 * 1024 * 1024
        /** انتظار بين جولات المحاولة (بالثواني) حين تفشل كل الخوادم، لتحمّل انقطاع قصير. */
        private val ROUND_WAITS_S = intArrayOf(2, 6, 15)

        fun query(b: BBox): String {
            val box = "${b.minLat},${b.minLon},${b.maxLat},${b.maxLon}"
            return "[out:json][timeout:60];(" +
                "way[\"highway\"]($box);" +
                "way[\"building\"]($box);" +
                "way[\"waterway\"~\"^(river|stream|canal|drain)\$\"]($box);" +
                "way[\"natural\"=\"water\"]($box);" +
                ");out geom;"
        }

        /** يحوّل ردّ Overpass (JSON) إلى عناصر مرسومة. يرمي IOException إن كان الرد خطأ أو ناقصًا. */
        fun parse(json: String): List<OsmWay> {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw IOException("رد غير مفهوم من الخادم")
            }
            val remark = root.optString("remark", "")
            if (remark.contains("error", ignoreCase = true) || remark.contains("timed out", ignoreCase = true)) {
                // الرد الجزئي لا يُقبل: قطعة ناقصة تعني شوارع مفقودة بصمت
                throw IOException("الخادم لم يكمل الاستعلام ($remark)")
            }
            val arr = root.optJSONArray("elements") ?: throw IOException("رد بلا بيانات")
            val out = ArrayList<OsmWay>(arr.length())
            for (i in 0 until arr.length()) {
                val el = arr.optJSONObject(i) ?: continue
                if (el.optString("type") != "way") continue
                val tags = el.optJSONObject("tags") ?: continue
                val kind = WayKind.classify(
                    tags.optString("highway", "").ifEmpty { null },
                    tags.optString("building", "").ifEmpty { null },
                    tags.optString("waterway", "").ifEmpty { null },
                    tags.optString("natural", "").ifEmpty { null }
                )
                if (kind == 0) continue
                val g = el.optJSONArray("geometry") ?: continue
                if (g.length() < 2) continue
                val la = IntArray(g.length())
                val lo = IntArray(g.length())
                var ok = true
                for (k in 0 until g.length()) {
                    val p = g.optJSONObject(k)
                    if (p == null || !p.has("lat") || !p.has("lon")) {
                        ok = false
                        break
                    }
                    la[k] = Math.round(p.getDouble("lat") * 1e6).toInt()
                    lo[k] = Math.round(p.getDouble("lon") * 1e6).toInt()
                }
                if (ok) out.add(OsmWay(el.optLong("id"), kind, la, lo))
            }
            return out
        }
    }

    /**
     * يجلب قطعة. يجرّب الخوادم بالتناوب وعدة جولات مع انتظار متزايد؛ إن فشل الجميع يرمي IOException
     * برسالة مفهومة (والتقدّم المحفوظ لا يضيع، فيمكن الاستئناف).
     */
    fun fetch(b: BBox, isCancelled: () -> Boolean): List<OsmWay> {
        var last = "تعذّر الاتصال"
        for (round in 0..ROUND_WAITS_S.size) {
            for (url in mirrors) {
                if (isCancelled()) throw IOException("أُلغي")
                try {
                    return parse(post(url, query(b)))
                } catch (e: IOException) {
                    last = e.message ?: last
                }
            }
            if (round < ROUND_WAITS_S.size) {
                var left = ROUND_WAITS_S[round] * 10
                while (left-- > 0) {
                    if (isCancelled()) throw IOException("أُلغي")
                    Thread.sleep(100)
                }
            }
        }
        throw IOException(last)
    }

    private fun post(url: String, q: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 75_000
            c.doOutput = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            val code = c.responseCode
            if (code == 429) throw IOException("الخادم مزدحم (429)")
            if (code != 200) throw IOException("خطأ من الخادم ($code)")
            val len = c.contentLength
            if (len > MAX_BYTES) throw IOException("الرد كبير جدًا")
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (text.length > MAX_BYTES) throw IOException("الرد كبير جدًا")
            return text
        } finally {
            c.disconnect()
        }
    }
}

package com.meterproof.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * يبني صفحة خارطة تفاعلية (OpenStreetMap عبر Leaflet) تعمل في المتصفح.
 * التطبيق نفسه لا يحتاج إنترنت: المتصفح هو من يحمّل الخارطة.
 *
 * withImages = true عند التصدير (الصور بجانب الملف في المجلد images/)،
 * وfalse عند الفتح المباشر من التطبيق (لا صور بجانب الملف).
 */
object MapHtml {

    fun build(track: List<GeoPoint>, photos: List<PhotoPoint>, withImages: Boolean): String {
        val trackJson = JSONArray()
        for (p in track) trackJson.put(JSONArray().put(p.lat).put(p.lon))

        val photosJson = JSONArray()
        for (p in photos) {
            val o = JSONObject()
                .put("n", p.index + 1)
                .put("id", p.id)
                .put("t", p.timeMillis)
                .put("lat", p.lat)
                .put("lon", p.lon)
            if (p.accuracy != null) o.put("acc", p.accuracy.toDouble())
            photosJson.put(o)
        }

        return TEMPLATE
            .replace("__TRACK__", trackJson.toString())
            .replace("__PHOTOS__", photosJson.toString())
            .replace("__WITH_IMAGES__", if (withImages) "true" else "false")
    }

    private val TEMPLATE = """<!DOCTYPE html>
<html dir="rtl" lang="ar">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MeterProof - الخارطة</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
  html, body, #map { height: 100%; margin: 0; }
  .note { position: absolute; z-index: 1000; bottom: 8px; right: 8px; background: rgba(255,255,255,.9);
          padding: 6px 10px; border-radius: 6px; font: 13px sans-serif; }
</style>
</head>
<body>
<div id="map"></div>
<div class="note">الخط الأزرق: مسار GPS، والدوائر الحمراء: أماكن الصور (اضغط عليها)</div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
var track = __TRACK__;
var photos = __PHOTOS__;
var withImages = __WITH_IMAGES__;

var map = L.map('map');
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '&copy; OpenStreetMap'
}).addTo(map);

var bounds = [];
if (track.length) {
  L.polyline(track, { color: '#1565c0', weight: 4 }).addTo(map);
  bounds = bounds.concat(track);
}
photos.forEach(function (p) {
  var m = L.circleMarker([p.lat, p.lon], {
    radius: 10, color: '#b71c1c', fillColor: '#ef5350', fillOpacity: 0.9
  }).addTo(map);
  var html = '<b>صورة ' + p.n + '</b><br>' + new Date(p.t).toLocaleString('ar-MA');
  if (p.acc !== undefined) html += '<br>دقة الموقع: ' + Math.round(p.acc) + ' م';
  if (withImages) html += '<br><img src="images/' + p.id + '.jpg" style="width:220px;margin-top:6px">';
  m.bindPopup(html);
  bounds.push([p.lat, p.lon]);
});
if (bounds.length) {
  map.fitBounds(bounds, { padding: [30, 30], maxZoom: 18 });
} else {
  map.setView([33.9, -6.9], 6);
}
</script>
</body>
</html>
"""
}

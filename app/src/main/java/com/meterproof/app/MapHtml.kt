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

    fun build(
        track: List<GeoPoint>,
        photos: List<PhotoPoint>,
        stops: List<Stop>,
        withImages: Boolean,
        meters: List<MeterPoint> = emptyList()
    ): String {
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

        val stopsJson = JSONArray()
        for (s in stops) {
            if (s.lat == 0.0 && s.lon == 0.0) continue
            stopsJson.put(
                JSONArray().put(s.lat).put(s.lon).put(s.startMs).put(s.endMs ?: 0L)
            )
        }

        // عدادات الجولة: رقم العداد وحالته فقط (بلا اسم زبون ولا عنوان في الملف المُصدَّر)
        val metersJson = JSONArray()
        for (m in meters) {
            val o = JSONObject()
                .put("no", m.meterNo)
                .put("lat", m.lat)
                .put("lon", m.lon)
                .put("s", m.state.name)
            if (m.photoId != null) o.put("photo", m.photoId)
            metersJson.put(o)
        }

        return TEMPLATE
            .replace("__METERS__", metersJson.toString())
            .replace("__TRACK__", trackJson.toString())
            .replace("__PHOTOS__", photosJson.toString())
            .replace("__STOPS__", stopsJson.toString())
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
<div class="note">الخط الأزرق: مسار GPS، الأحمر: الصور، البرتقالي: توقفات، الدوائر الصغيرة: عدادات الجولة (رمادي لم يُزَر، أخضر مزرق وصول، أخضر صُوِّر، برتقالي بعيد)</div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
var track = __TRACK__;
var photos = __PHOTOS__;
var stops = __STOPS__;
var meters = __METERS__;
var withImages = __WITH_IMAGES__;

var map = L.map('map');
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '&copy; OpenStreetMap'
}).addTo(map);

var bounds = [];
var meterColors = { PENDING: '#98a2b3', ARRIVED: '#0e7490', DONE: '#2e7d32', FLAGGED: '#ef6c00' };
var meterNames = {
  PENDING: 'لم يُسجَّل التسليم بعد',
  ARRIVED: 'وُثّق الوصول، التسليم غير مؤكَّد بعد',
  DONE: 'صُوِّر',
  FLAGGED: 'صُوِّر لكن الموقع بعيد عن الإحداثية'
};
meters.forEach(function (m) {
  var c = meterColors[m.s] || '#98a2b3';
  var mk = L.circleMarker([m.lat, m.lon], {
    radius: m.s === 'PENDING' ? 5 : 7, color: '#ffffff', weight: 1.5, fillColor: c, fillOpacity: 1
  }).addTo(map);
  var html = '<b>العداد ' + m.no + '</b><br>' + (meterNames[m.s] || '');
  if (withImages && m.photo) html += '<br><img src="images/' + m.photo + '.jpg" style="width:220px;margin-top:6px">';
  mk.bindPopup(html);
  if (!track.length) bounds.push([m.lat, m.lon]);
});
if (track.length) {
  L.polyline(track, { color: '#1565c0', weight: 4 }).addTo(map);
  bounds = bounds.concat(track);
}
stops.forEach(function (s) {
  var m = L.circleMarker([s[0], s[1]], {
    radius: 9, color: '#e65100', fillColor: '#fb8c00', fillOpacity: 0.9
  }).addTo(map);
  var html = '<b>توقف</b><br>من ' + new Date(s[2]).toLocaleTimeString('ar-MA');
  if (s[3]) {
    html += '<br>إلى ' + new Date(s[3]).toLocaleTimeString('ar-MA');
    html += '<br>المدة: ' + Math.max(1, Math.round((s[3] - s[2]) / 60000)) + ' دقيقة';
  } else {
    html += '<br>ما زال جاريًا';
  }
  m.bindPopup(html);
  bounds.push([s[0], s[1]]);
});
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

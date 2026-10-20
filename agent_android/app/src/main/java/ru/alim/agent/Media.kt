package ru.alim.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** YouTube без ключа: страница поиска содержит JSON ytInitialData — достаём из него ролики. */
object YouTube {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    const val PKG = "com.google.android.youtube"

    class Video(val id: String, val title: String, val channel: String, val length: String, val views: String, val ago: String) {
        val url get() = "https://www.youtube.com/watch?v=$id"
        val thumb get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        fun item() = Shop.Item(title, length.ifBlank { "видео" }, url, thumb, "YouTube · $channel".take(60))
    }

    private fun text(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
        ?: o?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }.orEmpty()

    private fun collect(x: Any?, out: MutableList<JSONObject>) {
        when (x) {
            is JSONObject -> {
                x.optJSONObject("videoRenderer")?.let { out += it; return }
                x.keys().forEach { k -> collect(x.opt(k), out) }
            }
            is JSONArray -> for (i in 0 until x.length()) collect(x.opt(i), out)
        }
    }

    /** period: "" — всё время, "today", "week", "month". */
    fun search(query: String, period: String = "", limit: Int = 8): List<Video> {
        val sp = when (period) { "today" -> "&sp=EgIIAg%253D%253D"; "week" -> "&sp=EgIIAw%253D%253D"; "month" -> "&sp=EgIIBA%253D%253D"; else -> "" }
        val url = "https://www.youtube.com/results?hl=ru&gl=RU&search_query=" + URLEncoder.encode(query, "UTF-8") + sp
        val html = http.newCall(Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36")
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI").build()).execute().use { r ->
            if (!r.isSuccessful) error("YouTube ответил ${r.code}")
            r.body?.string().orEmpty()
        }
        val start = html.indexOf("ytInitialData").takeIf { it >= 0 } ?: error("YouTube не отдал выдачу (возможно, нужен VPN)")
        val a = html.indexOf('{', start)
        val end = html.indexOf("</script>", a).takeIf { it > 0 } ?: html.length
        val b = html.lastIndexOf('}', end)
        val data = JSONObject(html.substring(a, b + 1))
        val found = ArrayList<JSONObject>(); collect(data, found)
        return found.mapNotNull { v ->
            val id = v.optString("videoId").ifBlank { return@mapNotNull null }
            Video(id, text(v.optJSONObject("title")), text(v.optJSONObject("ownerText")), text(v.optJSONObject("lengthText")),
                text(v.optJSONObject("viewCountText")), text(v.optJSONObject("publishedTimeText")))
        }.distinctBy { it.id }.take(limit)
    }

    fun format(l: List<Video>) = l.mapIndexed { i, v ->
        "${i + 1}. ${v.title} — ${v.channel}${if (v.length.isNotBlank()) ", ${v.length}" else ""}${if (v.ago.isNotBlank()) ", ${v.ago}" else ""}${if (v.views.isNotBlank()) ", ${v.views}" else ""}"
    }.joinToString("\n")
}

/** Адреса и маршруты: координаты через OpenStreetMap, время в пути — OSRM, показываем в Яндекс Картах. */
object Geo {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    const val MAPS_PKG = "ru.yandex.yandexmaps"
    const val NAVI_PKG = "ru.yandex.yandexnavi"

    class Place(val lat: Double, val lon: Double, val name: String) {
        val ll get() = String.format(Locale.US, "%.6f,%.6f", lat, lon)
    }

    private fun get(url: String) = http.newCall(Request.Builder().url(url).header("User-Agent", "AlimAgent/1.0 (android)")
        .header("Accept-Language", "ru").build()).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }

    /** Найти место: сначала как есть, потом с городом Алима. */
    fun find(q: String): Place? {
        fun one(s: String): Place? = runCatching {
            val a = JSONArray(get("https://nominatim.openstreetmap.org/search?format=json&limit=1&accept-language=ru&q=" + URLEncoder.encode(s, "UTF-8")))
            if (a.length() == 0) null else a.getJSONObject(0).let { Place(it.getString("lat").toDouble(), it.getString("lon").toDouble(), it.optString("display_name")) }
        }.getOrNull()
        val city = Prefs.homeCity
        return if (city.isNotBlank() && !q.contains(city, true)) one("$q, $city") ?: one(q) else one(q)
    }

    /** Расстояние и время на машине или пешком. */
    fun eta(a: Place, b: Place, walk: Boolean): String = runCatching {
        val prof = if (walk) "foot" else "driving"
        val j = JSONObject(get("https://router.project-osrm.org/route/v1/$prof/${a.lon},${a.lat};${b.lon},${b.lat}?overview=false"))
        val r = j.optJSONArray("routes")?.optJSONObject(0) ?: return@runCatching ""
        val km = r.optDouble("distance") / 1000; val min = r.optDouble("duration") / 60
        val t = if (min >= 60) "${(min / 60).toInt()} ч ${(min % 60).toInt()} мин" else "${min.toInt().coerceAtLeast(1)} мин"
        String.format(Locale("ru"), "%.1f км, примерно %s", km, t)
    }.getOrDefault("")
}

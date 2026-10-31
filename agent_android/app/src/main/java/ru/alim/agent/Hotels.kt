package ru.alim.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Отели на трёх сайтах через невидимый браузер. Страницу города на сайте находим поиском (адреса городов у сайтов свои),
 * дописываем даты и гостей, ждём, пока выдача нарисуется с ценами, и отдаём модели текст вокруг цен — она сравнивает.
 */
object Hotels {
    private class Site(val name: String, val host: String, val query: String, val path: Regex, val params: (LocalDate, LocalDate, Int) -> String)

    private val dmy = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val sites = listOf(
        Site("Островок", "ostrovok.ru", "ostrovok.ru отели", Regex("/hotel/[a-z_-]+/[a-z0-9_-]+/?$")) { i, o, g -> "dates=${i.format(dmy)}-${o.format(dmy)}&guests=$g" },
        Site("Яндекс Путешествия", "travel.yandex.ru", "travel.yandex.ru отели", Regex("/hotels/[a-z0-9_-]+/?$")) { i, o, g -> "checkinDate=$i&checkoutDate=$o&adults=$g" },
        Site("Ozon Travel", "ozon.ru", "ozon travel отели", Regex("/travel/.*hotel|/travel/hotels")) { i, o, g -> "checkIn=$i&checkOut=$o&dateFrom=$i&dateTo=$o&adults=$g&guests=$g" },
    )

    private val urlRe = Regex("https?://[^\\s)\\]>\"']+")

    private fun cityPage(s: Site, found: List<String>): String? = found.asSequence()
        .flatMap { urlRe.findAll(it).map { m -> m.value.trimEnd('.', ',') } }
        .map { it.substringBefore('?').substringBefore('#') }
        .firstOrNull { u -> u.contains(s.host) && s.path.containsMatchIn(u) && !u.contains("/mid") && !u.contains("/search") }

    /** Только куски с ценами: по 6 строк до каждой строки с «₽». */
    private fun around(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && it.length < 160 }
        val keep = sortedSetOf<Int>()
        lines.forEachIndexed { i, l -> if (l.contains("₽") || Regex("\\d\\s?\\d{3}\\s?(руб|р\\.)").containsMatchIn(l)) for (k in (i - 6).coerceAtLeast(0)..i) keep += k }
        var prev = -2
        val sb = StringBuilder()
        for (k in keep) { if (k != prev + 1) sb.append("—\n"); sb.append(lines[k]).append('\n'); prev = k }
        return sb.toString().take(3200)
    }

    // текст выдачи + ссылки на сами отели (у каждого сайта свой вид адреса отеля)
    private val JS = """(function(){
var t=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').slice(0,20000);
var seen={},L=[];
document.querySelectorAll('a[href]').forEach(function(a){
  var h=a.href;
  if(!/\/mid\d|travel\.yandex\.ru\/hotels\/[^\/?#]+\/[^\/?#]+|\/travel\/hotels?\/[^?#]*\d/.test(h)) return;
  if(/\/search|\/reviews|#/.test(h.split('?')[0])) return;
  var k=h.split('?')[0]; if(seen[k]) return;
  var n=(a.innerText||a.getAttribute('aria-label')||a.title||'').replace(/\s+/g,' ').trim();
  if(n.length<3){var c=a.closest('article,li,[data-index],[class*=card],[class*=Card]'); n=c?(c.innerText||'').replace(/\s+/g,' ').trim():'';}
  n=n.slice(0,70); if(n.length<3) return;
  seen[k]=1; L.push(n+' | '+h);
});
return t+'\n@@L@@\n'+L.slice(0,40).join('\n');
})()"""

    /** Ссылки на сами отели с датами: «Название — ссылка». */
    private fun links(raw: String, params: String): String = raw.substringAfter("@@L@@", "").lines()
        .mapNotNull { l -> val k = l.lastIndexOf(" | "); if (k < 3) null else l.take(k).trim() to l.drop(k + 3).trim() }
        .filter { it.second.startsWith("http") }
        .distinctBy { it.second.substringBefore('?') }
        .take(15)
        .joinToString("\n") { (n, u) -> "• $n — ${u.substringBefore('?').trimEnd('/')}/?$params" }

    suspend fun all(ctx: Context, city: String, ci: LocalDate, co: LocalDate, g: Int, search: (String) -> List<String>): String = coroutineScope {
        val nights = java.time.temporal.ChronoUnit.DAYS.between(ci, co).coerceAtLeast(1)
        val parts = sites.map { s ->
            async {
                val found = withContext(Dispatchers.IO) { runCatching { search("${s.query} $city") }.getOrDefault(emptyList()) }
                val page = cityPage(s, found) ?: return@async "## ${s.name}\nне нашёл страницу города «$city» на ${s.host}"
                val url = page.trimEnd('/') + "/?" + s.params(ci, co, g)
                val raw = runCatching {
                    WebReader.run(ctx, url, JS, { t -> t.count { it == '₽' } >= 8 }, 40_000)
                }.getOrDefault("")
                var cut = around(raw.substringBefore("@@L@@"))
                var hl = links(raw, s.params(ci, co, g))
                var link = url
                // с датами выдача не нарисовалась — пробуем страницу города без параметров (там хотя бы цены «от»)
                if (cut.isBlank()) {
                    val raw2 = runCatching { WebReader.run(ctx, page, JS, { t -> t.count { it == '₽' } >= 6 }, 45_000) }.getOrDefault("")
                    val c2 = around(raw2.substringBefore("@@L@@"))
                    if (c2.isNotBlank()) { cut = "(без дат — цены «от», на даты уточняй на сайте)\n$c2"; link = page; hl = links(raw2, s.params(ci, co, g)) }
                }
                "## ${s.name}\nстраница города: $link\n" + cut.ifBlank { "цены не показались (сайт не дорисовал выдачу)" } +
                    if (hl.isNotBlank()) "\nСсылки на отели (url для карточки и покупки бери ОТСЮДА — на сам отель, с датами):\n$hl" else ""
            }
        }.awaitAll()
        "Отели «$city», ${ci.format(dmy)}–${co.format(dmy)} ($nights ${if (nights in 2..4) "ночи" else if (nights == 1L) "ночь" else "ночей"}), гостей: $g. " +
            "Цены могут быть за ночь или за весь срок — смотри подпись рядом. Для каждого отеля url — его собственная ссылка из «Ссылки на отели» того сайта (не страница города); нет ссылки на отель — тогда страница города.\n\n" +
            parts.joinToString("\n\n")
    }
}

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

    private val JS = "(function(){return (document.body?document.body.innerText:'').replace(/\\n{3,}/g,'\\n\\n').slice(0,20000)})()"

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
                val cut = around(raw)
                "## ${s.name}\nссылка: $url\n" + cut.ifBlank { "цены не показались (сайт не дорисовал выдачу)" }
            }
        }.awaitAll()
        "Отели «$city», ${ci.format(dmy)}–${co.format(dmy)} ($nights ${if (nights in 2..4) "ночи" else if (nights == 1L) "ночь" else "ночей"}), гостей: $g. " +
            "Цены могут быть за ночь или за весь срок — смотри подпись рядом. Ссылку для покупки бери из строки «ссылка» того сайта, где дешевле.\n\n" +
            parts.joinToString("\n\n")
    }
}

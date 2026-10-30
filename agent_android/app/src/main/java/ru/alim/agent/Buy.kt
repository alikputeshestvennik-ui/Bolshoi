package ru.alim.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Шаги покупки для «Лучшего выбора»: модель перечисляет, что купить (рейс, автобус, поезд, отель),
 * а ссылки Пилот собирает сам — рейс точным предложением Авиасейлса, остальное только из реально найденных страниц.
 * Выдуманный адрес не проходит: вместо него — поиск Яндекса по точному названию.
 */
object Buy {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun yandex(q: String) = "https://yandex.ru/search/?text=" + enc(q)

    /** Привести best (и его alt) к виду buy: [{kind, label, url}]. seen — всё, что инструменты вернули в этой задаче. */
    fun fix(best: String, seen: String): String {
        val b = runCatching { JSONObject(best) }.getOrNull() ?: return best
        fun one(o: JSONObject) {
            val src = o.optJSONArray("buy") ?: return
            val out = JSONArray()
            for (k in 0 until minOf(6, src.length())) {
                val it = src.optJSONObject(k) ?: continue
                val kind = it.optString("type").lowercase().ifBlank { "link" }
                val label = it.optString("label").trim().ifBlank { continue }
                val url = when (kind) {
                    "flight", "avia", "рейс" -> flight(it)
                    else -> it.optString("url").trim().takeIf { u -> u.startsWith("http") && seen.contains(u.take(60)) }
                } ?: yandex(label + when (kind) { "hotel" -> " забронировать"; "bus", "train", "flight" -> " купить билет"; else -> "" })
                out.put(JSONObject().put("kind", kind).put("label", label.take(90)).put("url", url))
            }
            if (out.length() > 0) o.put("buy", out) else o.remove("buy")
        }
        one(b)
        b.optJSONArray("alt")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { one(it) } }
        return b.toString()
    }

    /** Рейс: точное предложение из поиска (по номеру рейса), иначе поиск Авиасейлса на дату. */
    private fun flight(it: JSONObject): String? {
        val fn = it.optString("flight").uppercase().replace(Regex("[^A-Z0-9А-Я]"), "")
        if (fn.length >= 3) Avia.byFlight[fn]?.let { return it }
        val date = it.optString("date").trim()
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) return null
        val from = runCatching { Avia.place(it.optString("from"))?.first }.getOrNull() ?: return null
        val to = runCatching { Avia.place(it.optString("to"))?.first }.getOrNull() ?: return null
        return "https://www.aviasales.ru/search/$from${date.substring(8, 10)}${date.substring(5, 7)}${to}1"
    }

    fun items(o: JSONObject): List<Triple<String, String, String>> = o.optJSONArray("buy")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Triple(it.optString("kind"), it.optString("label"), it.optString("url")) }
            .filter { it.third.startsWith("http") }
    }.orEmpty()

    /** Открыть ссылку: приложение, если оно берёт такие адреса (Авиасейлс, Яндекс Путешествия), иначе новая вкладка браузера. */
    fun open(ctx: Context, url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(android.provider.Browser.EXTRA_CREATE_NEW_TAB, true)
        runCatching { ctx.startActivity(i) }
    }
}

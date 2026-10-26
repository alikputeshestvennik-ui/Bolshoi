package ru.alim.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Авиабилеты через официальный API Авиасейлса (Travelpayouts Data API).
 * Цены — из поисков пользователей Авиасейлса за последние ~2 суток (кэш), не «живая» выдача:
 * итоговую цену показывает сам Авиасейлс при открытии.
 */
object Avia {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val token: String get() = BuildConfig.TRAVELPAYOUTS_TOKEN.trim()
    val ready: Boolean get() = token.isNotBlank()

    /** Ссылки на последние найденные рейсы — чтобы открыть «второй» в приложении Авиасейлса. */
    var lastLinks: List<String>
        get() = Prefs.aviaLinks.split('\n').filter { it.isNotBlank() }
        private set(v) { Prefs.aviaLinks = v.joinToString("\n") }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("Авиасейлс ${r.code}")
            b
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Город/аэропорт → IATA-код и название. Уже код из 3 латинских букв — оставляем. */
    fun place(q: String): Pair<String, String>? {
        val t = q.trim()
        if (Regex("^[A-Za-z]{3}$").matches(t)) return t.uppercase() to t.uppercase()
        val arr = JSONArray(get("https://autocomplete.travelpayouts.com/places2?term=${enc(t)}&locale=ru&types%5B%5D=city&types%5B%5D=airport"))
        val o = arr.optJSONObject(0) ?: return null
        val code = o.optString("type").let { if (it == "airport") o.optString("city_code").ifBlank { o.optString("code") } else o.optString("code") }
        return code to o.optString("name").ifBlank { t }
    }

    private val airlines = mapOf(
        "SU" to "Аэрофлот", "FV" to "Россия", "U6" to "Уральские авиалинии", "S7" to "S7", "DP" to "Победа", "A4" to "Азимут",
        "UT" to "ЮТэйр", "N4" to "Nordwind", "5N" to "Smartavia", "WZ" to "Red Wings", "YC" to "Ямал", "R3" to "Якутия",
        "7R" to "РусЛайн", "IO" to "ИрАэро", "ZF" to "Azur Air", "EO" to "Pegas Fly", "TK" to "Turkish Airlines", "PC" to "Pegasus",
        "J2" to "AZAL", "HY" to "Uzbekistan Airways", "KC" to "Air Astana", "DV" to "SCAT", "EK" to "Emirates", "FZ" to "flydubai",
        "QR" to "Qatar Airways", "EY" to "Etihad", "MS" to "EgyptAir", "B2" to "Belavia", "2S" to "Southwind", "XC" to "Corendon",
    )

    private fun dur(min: Int): String = if (min <= 0) "" else "${min / 60} ч ${"%02d".format(min % 60)} м"

    /**
     * Цены на дату (ГГГГ-ММ-ДД) или на месяц (ГГГГ-ММ). returnDate — для «туда-обратно».
     * Возвращает текст для Пилота; ссылки сохраняет для открытия.
     */
    fun search(from: String, to: String, date: String, returnDate: String = "", direct: Boolean = false): String {
        if (!ready) return "нет токена Авиасейлса (TRAVELPAYOUTS_TOKEN)"
        val o = place(from) ?: return "не понял город вылета «$from»"
        val d = place(to) ?: return "не понял город прилёта «$to»"
        val oneWay = returnDate.isBlank()
        val url = "https://api.travelpayouts.com/aviasales/v3/prices_for_dates?origin=${o.first}&destination=${d.first}" +
            "&departure_at=${enc(date)}" + (if (!oneWay) "&return_at=${enc(returnDate)}" else "") +
            "&one_way=$oneWay&direct=$direct&currency=rub&sorting=price&limit=8&token=$token"
        val j = JSONObject(get(url))
        if (!j.optBoolean("success", true)) return "Авиасейлс не ответил: ${j.optString("error").take(120)}"
        val data = j.optJSONArray("data") ?: JSONArray()
        if (data.length() == 0) {
            lastLinks = emptyList()
            return "${o.second} → ${d.second}, $date: в кэше Авиасейлса цен нет (на эту дату недавно никто не искал). " +
                "Попробуй соседние даты или весь месяц (ГГГГ-ММ)."
        }
        val links = ArrayList<String>()
        val sb = StringBuilder("${o.second} (${o.first}) → ${d.second} (${d.first})${if (!oneWay) ", туда-обратно" else ""}:\n")
        for (i in 0 until data.length()) {
            val f = data.optJSONObject(i) ?: continue
            val dep = f.optString("departure_at")      // 2026-11-22T13:00:00+03:00
            val day = dep.take(10).split('-').let { if (it.size == 3) "${it[2]}.${it[1]}" else dep.take(10) }
            val time = dep.drop(11).take(5)
            val al = f.optString("airline"); val name = airlines[al] ?: al
            val tr = f.optInt("transfers")
            val transfers = if (tr == 0) "прямой" else "$tr пересадк${if (tr == 1) "а" else "и"}"
            sb.append("${links.size + 1}. $day $time · $name ($al ${f.optString("flight_number")}) · $transfers · ${dur(f.optInt("duration_to", f.optInt("duration")))}")
            if (!oneWay) f.optString("return_at").takeIf { it.length >= 16 }?.let { r -> sb.append(" · обратно ${r.substring(8, 10)}.${r.substring(5, 7)} ${r.substring(11, 16)}") }
            sb.append(" · ${"%,d".format(f.optInt("price")).replace(',', ' ')} ₽\n")
            links += "https://www.aviasales.ru" + f.optString("link")
        }
        lastLinks = links
        sb.append("(цены из поисков на Авиасейлсе за последние ~2 суток; точную цену покажет Авиасейлс при открытии)")
        return sb.toString()
    }

    /** Открыть рейс n (с 1) или поиск в приложении Авиасейлса; нет приложения — в браузере. */
    fun open(ctx: Context, n: Int): String {
        val url = lastLinks.getOrNull((n - 1).coerceAtLeast(0)) ?: return "сначала найди рейсы — открывать пока нечего"
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("ru.aviasales").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(app); true }.getOrDefault(false)) return "открыл рейс $n в приложении Авиасейлса"
        return if (runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false))
            "приложения Авиасейлса нет — открыл рейс $n в браузере" else "не открылось"
    }
}

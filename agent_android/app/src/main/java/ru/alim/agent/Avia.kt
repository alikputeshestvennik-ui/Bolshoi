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
    /** Найденные в поисках рейсы: «SU1153» → точная ссылка на предложение (для кнопок покупки). */
    val byFlight = java.util.concurrent.ConcurrentHashMap<String, String>()
    var lastLinks: List<String>
        get() = Prefs.aviaLinks.split('\n').filter { it.isNotBlank() }
        private set(v) { Prefs.aviaLinks = v.joinToString("\n") }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build()).execute().use { r ->
            val b = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("Авиасейлс ${r.code}: " + (runCatching { JSONObject(b).optString("error") }.getOrNull()?.ifBlank { null } ?: b.take(120)))
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
        val dre = Regex("^\\d{4}-\\d{2}(-\\d{2})?$")
        if (!dre.matches(date)) return "дата «$date» не подходит: нужна одна дата ГГГГ-ММ-ДД или месяц ГГГГ-ММ. На выходные — вызови отдельно на каждый день"
        if (returnDate.isNotBlank() && !dre.matches(returnDate)) return "дата обратно «$returnDate» не подходит: нужна ГГГГ-ММ-ДД или ГГГГ-ММ"
        if (date.length == 10 && date < java.time.LocalDate.now().toString()) return "дата $date уже прошла — возьми будущую"
        // Крым: аэропорт Симферополя закрыт — сразу ищем из Сочи и говорим про дорогу
        val crimea = Regex("ялт|севастоп|симфероп|алушт|евпатор|феодос|керч|судак|крым|^sip$", RegexOption.IGNORE_CASE).containsMatchIn(from.trim())
        if (crimea) return "Аэропорт Симферополя закрыт для гражданских рейсов — из Крыма летают через Сочи (Адлер): " +
            "автобус из Ялты через Крымский мост ≈15 ч, учти его в расписании. Ищу из Сочи:\n" +
            search("Сочи", to, date, returnDate, direct)
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
            val mins = f.optInt("duration_to", f.optInt("duration"))
            sb.append("${links.size + 1}. $day $time · $name ($al ${f.optString("flight_number")}) · $transfers · ${dur(mins)}")
            arrival(dep, mins, d.first)?.let { sb.append(" · ").append(it) }
            if (!oneWay) f.optString("return_at").takeIf { it.length >= 16 }?.let { r -> sb.append(" · обратно ${r.substring(8, 10)}.${r.substring(5, 7)} ${r.substring(11, 16)}") }
            sb.append(" · ${"%,d".format(f.optInt("price")).replace(',', ' ')} ₽\n")
            links += "https://www.aviasales.ru" + f.optString("link")
            byFlight[(al + f.optString("flight_number")).uppercase().replace(Regex("[^A-Z0-9А-Я]"), "")] = links.last()
        }
        lastLinks = links
        sb.append("(время вылета — местное для города вылета, «прилёт» уже пересчитан в местное время города прилёта — бери его как есть. Цены из поисков на Авиасейлсе за последние ~2 суток, обычно БЕЗ багажа — багаж в тарифе не гарантирован; точную цену и условия покажет Авиасейлс при открытии)")
        return sb.toString()
    }

    /** Пояса аэропортов и городов (часы от UTC) — чтобы время прилёта было местным, а не «по времени вылета». */
    private val tzUtc = mapOf(
        "MOW" to 3, "SVO" to 3, "DME" to 3, "VKO" to 3, "ZIA" to 3, "LED" to 3, "AER" to 3, "MRV" to 3, "KZN" to 3, "ROV" to 3, "VOG" to 3,
        "KRR" to 3, "MCX" to 3, "VOZ" to 3, "GOJ" to 3, "OGZ" to 3, "NAL" to 3, "AAQ" to 3, "STW" to 3, "MMK" to 3, "ARH" to 3, "KVX" to 3,
        "CSY" to 3, "SKX" to 3, "PES" to 3, "EGO" to 3, "LPK" to 3, "BZK" to 3, "IAR" to 3, "SCW" to 3, "GRV" to 3, "UUA" to 3,
        "KGD" to 2, "KUF" to 4, "IJK" to 4, "ASF" to 4, "ULV" to 4, "ULY" to 4, "GSV" to 4, "RTW" to 4,
        "SVX" to 5, "PEE" to 5, "UFA" to 5, "CEK" to 5, "TJM" to 5, "MQF" to 5, "REN" to 5, "SGC" to 5, "NUX" to 5, "HMA" to 5, "NJC" to 5, "KRO" to 5,
        "OMS" to 6, "OVB" to 7, "KJA" to 7, "BAX" to 7, "TOF" to 7, "KEJ" to 7, "NOZ" to 7, "ABA" to 7, "RGK" to 7, "NSK" to 7,
        "IKT" to 8, "UUD" to 8, "BTK" to 8, "HTA" to 9, "YKS" to 9, "MJZ" to 9, "BQS" to 9, "VVO" to 10, "KHV" to 10, "UUS" to 11, "GDX" to 11, "PKC" to 12,
        "IST" to 3, "SAW" to 3, "AYT" to 3, "DXB" to 4, "AUH" to 4, "EVN" to 4, "TBS" to 4, "GYD" to 4, "TAS" to 5, "SKD" to 5, "ALA" to 5, "NQZ" to 5, "TSE" to 5,
        "FRU" to 6, "MSQ" to 3, "CAI" to 2, "HRG" to 2, "SSH" to 2, "BKK" to 7, "HKT" to 7, "PEK" to 8, "PKX" to 8, "PVG" to 8, "SHA" to 8, "HAN" to 7, "SGN" to 7, "CXR" to 7)

    /** «прилёт 19.07 23:30 местное (по МСК 18:30)» — из времени вылета (со смещением) и длительности. */
    private fun arrival(dep: String, mins: Int, dest: String): String? = runCatching {
        if (mins <= 0) return null
        val utc = tzUtc[dest.uppercase()] ?: return null
        val d = java.time.OffsetDateTime.parse(dep)
        val a = d.plusMinutes(mins.toLong()).withOffsetSameInstant(java.time.ZoneOffset.ofHours(utc))
        val f = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")
        val msk = a.withOffsetSameInstant(java.time.ZoneOffset.ofHours(3))
        "прилёт ${a.format(f)} местное" + (if (utc != 3) " (UTC+$utc; по МСК ${msk.format(f)})" else "")
    }.getOrNull()

    /** Открыть рейс n (с 1) или поиск в приложении Авиасейлса; нет приложения — в браузере. */
    fun open(ctx: Context, n: Int): String {
        val url = lastLinks.getOrNull((n - 1).coerceAtLeast(0)) ?: return "сначала найди рейсы — открывать пока нечего"
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("ru.aviasales").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(app); true }.getOrDefault(false)) return "открыл рейс $n в приложении Авиасейлса"
        return if (runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false))
            "приложения Авиасейлса нет — открыл рейс $n в браузере" else "не открылось"
    }
}

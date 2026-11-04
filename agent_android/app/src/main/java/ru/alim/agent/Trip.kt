package ru.alim.agent

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Сверка маршрута из отчёта кодом (модели такое пропускают):
 *  - nightGaps: ночь без жилья — из отеля выехал, а уезжаешь только на следующий день (или приехал раньше заезда);
 *  - missingLegs: нет отрезка туда / отеля / обратно.
 */
object Trip {
    private val TRANSPORT = setOf("bus", "train", "flight", "car", "taxi", "ship")
    private val dateRe = Regex("(?<![\\d:.])(\\d{1,2})\\.(\\d{2})(?:\\.(\\d{2,4}))?(?![\\d:])")
    private val timeRe = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")

    /** Даты и время из строки «Чт 7.01 · 09:00 → 11:45» / «3.01 → 6.01 · 3 ночи». Дата без времени — 12:00. */
    private class Pt(val dt: LocalDateTime, val hasTime: Boolean)

    private val MONTHS = listOf("январ", "феврал", "март", "апрел", "ма[йя]", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")

    /** «3 января» → «3.01», «3→6.01» / «3–6 января» → «3.01 → 6.01». */
    fun norm(src: String): String {
        var s = src
        MONTHS.forEachIndexed { i, m ->
            val mm = "%02d".format(i + 1)
            s = Regex("(?<![\\d.:])(\\d{1,2})\\s*(→|–|—|-|по)\\s*(\\d{1,2})\\s+$m[а-я]*", RegexOption.IGNORE_CASE).replace(s) { r -> "${r.groupValues[1]}.$mm → ${r.groupValues[3]}.$mm" }
            s = Regex("(?<![\\d.:])(\\d{1,2})\\s+$m[а-я]*", RegexOption.IGNORE_CASE).replace(s) { r -> "${r.groupValues[1]}.$mm" }
        }
        s = Regex("(?<![\\d.:])(\\d{1,2})\\s*(→|–|—|-)\\s*(\\d{1,2})\\.(\\d{2})(?![\\d:])").replace(s) { r -> "${r.groupValues[1]}.${r.groupValues[4]} → ${r.groupValues[3]}.${r.groupValues[4]}" }
        return s
    }

    private fun points(src: String, base: LocalDate?): List<Pt> {
        val s = norm(src)
        val out = ArrayList<Pt>()
        var lastDate: LocalDate? = base
        // идём по строке слева направо: дата задаёт день, время после неё — точку
        val tokens = (dateRe.findAll(s).map { it.range.first to it } + timeRe.findAll(s).map { it.range.first to it }).sortedBy { it.first }.toList()
        var pendingDate: LocalDate? = null
        for ((_, m) in tokens) {
            if (m.value.contains('.')) {
                if (pendingDate != null) out += Pt(pendingDate.atTime(12, 0), false)
                val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
                if (mo !in 1..12 || d !in 1..31) continue
                val y = m.groupValues[3].takeIf { it.isNotBlank() }?.toInt()?.let { if (it < 100) 2000 + it else it }
                    ?: guessYear(mo, lastDate)
                val date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: continue
                pendingDate = date; lastDate = date
            } else {
                val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
                if (h > 23 || mi > 59) continue
                val day = pendingDate ?: lastDate ?: continue
                // «16:20 → 07:35» без второй даты: время меньше прошлого — следующий день
                val prev = out.lastOrNull()?.takeIf { pendingDate == null }
                var at = day.atTime(h, mi)
                if (prev != null && prev.hasTime && at.isBefore(prev.dt)) at = at.plusDays(1)
                out += Pt(at, true); pendingDate = null; lastDate = at.toLocalDate()
            }
        }
        if (pendingDate != null) out += Pt(pendingDate.atTime(12, 0), false)
        return out
    }

    private fun guessYear(mo: Int, last: LocalDate?): Int {
        val now = LocalDate.now()
        if (last != null) return if (mo < last.monthValue - 5) last.year + 1 else last.year
        val y = now.year
        return if (LocalDate.of(y, mo, 1).isBefore(now.minusMonths(2))) y + 1 else y
    }

    private fun legs(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

    /** Все маршруты отчёта: лучший и из сравнения, с названием варианта. */
    private fun routes(rep: JSONObject): List<Pair<String, List<JSONObject>>> {
        val out = ArrayList<Pair<String, List<JSONObject>>>()
        legs(rep.optJSONArray("route")).takeIf { it.isNotEmpty() }?.let { out += "лучший вариант" to it }
        rep.optJSONArray("compare")?.let { c -> for (k in 0 until c.length()) c.optJSONObject(k)?.let { v ->
            legs(v.optJSONArray("route")).takeIf { it.isNotEmpty() }?.let { out += v.optString("name").ifBlank { "вариант ${k + 1}" } to it }
        } }
        return out
    }

    fun nightGaps(rep: JSONObject?): List<String> {
        rep ?: return emptyList()
        val out = ArrayList<String>()
        for ((name, rt) in routes(rep)) {
            var base: LocalDate? = null
            val pts = rt.map { l ->
                var p = points(l.optString("when"), base)
                // у отеля даты бывают в названии или подписи: «Отель «Моя», 3 ночи (3→6.01)»
                if (l.optString("kind") == "stay" && p.size < 2) points(l.optString("when") + " " + l.optString("what") + " " + l.optString("sub"), base).takeIf { it.size >= 2 }?.let { p = it }
                p.also { q -> q.lastOrNull()?.let { base = it.dt.toLocalDate() } }
            }
            for ((k, l) in rt.withIndex()) {
                if (l.optString("kind") != "stay") continue
                val sp = pts[k]; if (sp.size < 2) continue
                val checkIn = sp.first().dt.toLocalDate(); val checkOut = sp.last().dt.toLocalDate()
                // следующий транспорт после отеля
                val nx = (k + 1 until rt.size).firstOrNull { rt[it].optString("kind") in TRANSPORT && pts[it].isNotEmpty() }
                if (nx != null) {
                    val dep = pts[nx].first().dt
                    val nights = java.time.temporal.ChronoUnit.DAYS.between(checkOut, dep.toLocalDate()).toInt()
                    // уезжаешь на следующий день до 06:00 — ночь можно провести в дороге/аэропорту, не считаем
                    if (nights >= 2 || (nights == 1 && (!pts[nx].first().hasTime || dep.hour >= 6)))
                        out += "$name: выезд из отеля ${fmt(checkOut)}, а «${rt[nx].optString("what").take(40)}» только ${fmt(dep.toLocalDate())}${if (pts[nx].first().hasTime) " в %02d:%02d".format(dep.hour, dep.minute) else ""} — $nights ноч. без жилья"
                }
                // транспорт до отеля
                val pv = (k - 1 downTo 0).firstOrNull { rt[it].optString("kind") in TRANSPORT && pts[it].isNotEmpty() }
                if (pv != null) {
                    val arr = pts[pv].last().dt
                    val gap = java.time.temporal.ChronoUnit.DAYS.between(arr.toLocalDate(), checkIn).toInt()
                    // приехал вечером накануне заезда — ночь без жилья (приехал рано утром в день заезда — ок)
                    if (gap >= 2 || (gap == 1 && pts[pv].last().hasTime && arr.hour >= 12))
                        out += "$name: приезд ${fmt(arr.toLocalDate())}, а заезд в отель только ${fmt(checkIn)} — $gap ноч. без жилья"
                }
            }
        }
        return out.distinct().take(4)
    }

    /** Части не хватает отрезка: в просьбе есть отель/поездка, а в маршруте нет дороги туда или обратно. */
    fun missingLegs(rep: JSONObject?, ask: String): List<String> {
        val x = ask.lowercase()
        val wantsTrip = Regex("перел[её]т|самол[её]т|рейс|поездк|съездить|слетать|поехать|автобус|поезд").containsMatchIn(x)
        val wantsStay = Regex("отел|гостиниц|жиль|ноч").containsMatchIn(x)
        if (!wantsTrip) return emptyList()
        val rt = legs(rep?.optJSONArray("route"))
        if (rt.isEmpty()) return listOf("в отчёте нет маршрута (route)")
        val out = ArrayList<String>()
        val bad = Regex("нет данных|не найден|не нашёл|уточняется|^\\s*[—-]?\\s*$", RegexOption.IGNORE_CASE)
        val stayAt = rt.indexOfFirst { it.optString("kind") == "stay" }
        val wantsBack = Regex("обратн|вернуться|назад|туда и обратно|возвращ|домой").containsMatchIn(x)
        if (wantsStay && stayAt < 0) out += "отель (нет отрезка stay)"
        val before = if (stayAt >= 0) rt.take(stayAt) else rt
        val after = if (stayAt >= 0) rt.drop(stayAt + 1) else emptyList()
        if (before.none { it.optString("kind") in TRANSPORT }) out += "дорога туда"
        if (wantsBack && stayAt >= 0 && after.none { it.optString("kind") in TRANSPORT }) out += "дорога обратно"
        rt.filter { it.optString("kind") in TRANSPORT && bad.containsMatchIn(it.optString("sub")) }
            .forEach { out += "${it.optString("what").take(40)} — без цены/времени" }
        return out.take(4)
    }

    private fun fmt(d: LocalDate) = "%d.%02d".format(d.dayOfMonth, d.monthValue)
}

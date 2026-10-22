package ru.alim.agent

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Бесплатные лимиты Google AI Studio: сколько можно в минуту и в сутки (сутки у Google — по тихоокеанскому времени,
 * сброс около 10:00 по Москве). Считаем сами и заранее переходим на соседнюю модель, а не ждём отказа.
 */
object Quota {
    class Lim(val rpm: Int, val rpd: Int)

    /** Лимиты бесплатного тарифа Алима (с его экрана AI Studio). */
    private val LIMITS = mapOf(
        "gemini-3.5-flash-lite" to Lim(15, 500),
        "gemini-3.1-flash-lite" to Lim(15, 500),
        "gemini-3.8-flash" to Lim(5, 20),
        "gemini-3.6-flash" to Lim(5, 20),
        "gemini-3-flash-preview" to Lim(5, 20),
        "gemini-2.5-flash" to Lim(5, 20),
        "gemini-2.5-flash-lite" to Lim(10, 20),
    )
    /** Соседи по качеству: сильные Flash → лёгкие Lite. */
    private val CHAIN = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3-flash-preview", "gemini-2.5-flash",
        "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")
    /** Чуть не доходим до потолка — запас на ошибки счёта. */
    private const val SAFE_RPM = 1
    private const val SAFE_RPD = 2

    private val minute = HashMap<String, ArrayDeque<Long>>()
    /** Модели, которые Google сам «закрыл» (429): до какого времени не трогать. */
    private val blockedUntil = HashMap<String, Long>()
    private var day = ""
    private val used = HashMap<String, Int>()

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Los_Angeles") }.format(Date())

    @Synchronized private fun load() {
        val d = today()
        if (day == d) return
        day = d; used.clear()
        runCatching {
            val o = JSONObject(Prefs.gQuota)
            if (o.optString("day") == d) o.optJSONObject("n")?.let { n -> n.keys().forEach { used[it] = n.optInt(it) } }
        }
    }

    @Synchronized private fun save() {
        val n = JSONObject(); used.forEach { (k, v) -> n.put(k, v) }
        Prefs.gQuota = JSONObject().put("day", day).put("n", n).toString()
    }

    private fun lim(id: String) = LIMITS[id] ?: Lim(10, 200)

    @Synchronized fun usedToday(id: String): Int { load(); return used[id] ?: 0 }

    @Synchronized private fun inMinute(id: String): Int {
        val q = minute.getOrPut(id) { ArrayDeque() }
        val t = System.currentTimeMillis() - 60_000
        while (q.isNotEmpty() && q.first() < t) q.removeFirst()
        return q.size
    }

    @Synchronized fun dayLeft(id: String): Boolean { load(); return usedToday(id) < lim(id).rpd - SAFE_RPD && (blockedUntil[id] ?: 0) < System.currentTimeMillis() }
    @Synchronized fun minuteLeft(id: String): Boolean = inMinute(id) < lim(id).rpm - SAFE_RPM

    /** Через сколько мс освободится место в минутном окне. */
    @Synchronized fun waitMs(id: String): Long {
        val q = minute[id] ?: return 0
        if (inMinute(id) < lim(id).rpm - SAFE_RPM) return 0
        return (q.first() + 60_000 - System.currentTimeMillis()).coerceAtLeast(0) + 200
    }

    /** Записать запрос (до отправки — чтобы параллельные запросы видели друг друга). */
    @Synchronized fun take(id: String) {
        load()
        minute.getOrPut(id) { ArrayDeque() }.addLast(System.currentTimeMillis())
        used[id] = (used[id] ?: 0) + 1
        save()
    }

    /** Google ответил 429: дневной лимит — до конца суток, минутный — на минуту. */
    @Synchronized fun hit(id: String, daily: Boolean) {
        load()
        if (daily) { used[id] = lim(id).rpd; save() }
        else blockedUntil[id] = System.currentTimeMillis() + 60_000
    }

    /**
     * Какую модель Google взять вместо [want]: она сама, если влезает; иначе соседи (сначала того же уровня и ниже).
     * Пусто — на сегодня всё израсходовано, пора на платную.
     * Если место есть только «через несколько секунд» — подождём до [maxWait] мс.
     */
    fun pick(want: String, maxWait: Long): String? {
        val start = CHAIN.indexOf(want).let { if (it < 0) 0 else it }
        val order = listOf(want) + CHAIN.drop(start).filter { it != want } + CHAIN.take(start).reversed()
        val alive = order.filter { dayLeft(it) }
        if (alive.isEmpty()) return null
        alive.firstOrNull { minuteLeft(it) }?.let { return it }
        // все упёрлись в минутный лимит — ждём ближайшее окно, если недолго
        val best = alive.minByOrNull { waitMs(it) } ?: return null
        val w = waitMs(best)
        if (w > maxWait) return null
        Thread.sleep(w)
        return best
    }

    /** Строка для настроек: сколько потрачено сегодня. */
    fun summary(): String {
        val l = CHAIN.filter { usedToday(it) > 0 }
        if (l.isEmpty()) return "Google сегодня: ещё ничего"
        return "Google сегодня: " + l.joinToString(" · ") { "${Agent.short(it)} ${usedToday(it)}/${lim(it).rpd}" }
    }
}

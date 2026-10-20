package ru.alim.agent

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Долгая память агента: короткие факты об Алиме и его жизни.
 * source: «ты» — сказал сам, «агент» — агент записал во время задачи, «авто» — вывод мониторинга (стоит проверить).
 */
object Memory {
    data class Fact(val id: Long, val ts: Long, val text: String, val source: String)

    val version = mutableIntStateOf(0)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("memory", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<Fact> {
        val a = runCatching { JSONArray(sp(ctx).getString("f", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Fact(it.optLong("id"), it.optLong("ts"), it.optString("t"), it.optString("s", "ты")) }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Fact>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("ts", it.ts).put("t", it.text).put("s", it.source)) }
        sp(ctx).edit().putString("f", a.toString()).apply()
        version.intValue = version.intValue + 1
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Добавить факт; почти такой же уже есть — заменить старый новым. */
    @Synchronized
    fun add(ctx: Context, text: String, source: String): Fact? {
        val t = text.trim().take(300)
        if (t.length < 3 || NotifStore.sensitive(t)) return null
        val n = norm(t)
        val list = all(ctx).filterNot { norm(it.text) == n }.toMutableList()
        val f = Fact(System.currentTimeMillis(), System.currentTimeMillis(), t, source)
        list += f
        save(ctx, list.takeLast(400))
        return f
    }

    @Synchronized fun remove(ctx: Context, id: Long) = save(ctx, all(ctx).filterNot { it.id == id })
    @Synchronized fun confirm(ctx: Context, id: Long) = save(ctx, all(ctx).map { if (it.id == id) it.copy(source = "ты") else it })

    /** Поиск по словам: чем больше совпало, тем выше. */
    fun search(ctx: Context, q: String, limit: Int = 12): List<Fact> {
        val words = norm(q).split(' ').filter { it.length >= 3 }.map { it.take(5) }
        if (words.isEmpty()) return all(ctx).takeLast(limit)
        return all(ctx).map { f -> f to words.count { w -> norm(f.text).contains(w) } }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Fact, Int>> { it.second }.thenByDescending { it.first.ts })
            .take(limit).map { it.first }
    }

    /** Что подмешать в каждую задачу: свежие факты (авто — с пометкой). */
    fun forPrompt(ctx: Context, limit: Int = 40): String =
        all(ctx).takeLast(limit).joinToString("\n") { "- [${it.id}] ${it.text}${if (it.source == "авто") " (догадка)" else ""}" }
}

/** Лента событий: всё, что агент увидел сам — уведомления, SMS, письма. Хранится только на телефоне. */
object Events {
    data class Item(
        val id: Long, val ts: Long, val kind: String, val source: String, val from: String,
        val title: String, val text: String, val bulk: Boolean, val triaged: Boolean, val urgent: Boolean, val note: String,
    )

    val version = mutableIntStateOf(0)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("events", Context.MODE_PRIVATE)
    private var lastId = 0L

    @Synchronized
    fun all(ctx: Context): List<Item> {
        val a = runCatching { JSONArray(sp(ctx).getString("e", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.optLong("id"), it.optLong("ts"), it.optString("k"), it.optString("src"), it.optString("from"), it.optString("title"),
                it.optString("text"), it.optBoolean("bulk"), it.optBoolean("tr"), it.optBoolean("urg"), it.optString("note"))
        }
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Item>) {
        val cut = System.currentTimeMillis() - 4 * 86_400_000L
        val a = JSONArray()
        list.filter { it.ts > cut }.takeLast(600).forEach {
            a.put(JSONObject().put("id", it.id).put("ts", it.ts).put("k", it.kind).put("src", it.source).put("from", it.from)
                .put("title", it.title).put("text", it.text).put("bulk", it.bulk).put("tr", it.triaged).put("urg", it.urgent).put("note", it.note))
        }
        sp(ctx).edit().putString("e", a.toString()).apply()
        version.intValue = version.intValue + 1
    }

    /** Новое событие. Текст сразу чистится от кодов и денег — дальше телефона он не уйдёт. */
    @Synchronized
    fun add(ctx: Context, kind: String, source: String, from: String, title: String, text: String, bulk: Boolean = false): Item? {
        val t = NotifStore.safe(text.trim()).take(1500)
        if (from.isBlank() && title.isBlank() && t.isBlank()) return null
        val list = all(ctx)
        val now = System.currentTimeMillis()
        if (list.takeLast(60).any { it.kind == kind && it.source == source && it.from == from && it.title == title && it.text == t && now - it.ts < 6 * 3600_000L }) return null
        val id = maxOf(now, lastId + 1).also { lastId = it }
        val sensitive = t.startsWith("[скрыто")
        val item = Item(id, now, kind, source, from.trim().take(120), title.trim().take(200), t, bulk, triaged = bulk || sensitive, urgent = false, note = "")
        save(ctx, list + item)
        return item
    }

    @Synchronized
    fun mark(ctx: Context, triagedIds: Set<Long>, urgent: Map<Long, String>) =
        save(ctx, all(ctx).map {
            if (it.id in triagedIds || it.id in urgent) it.copy(triaged = true, urgent = it.urgent || it.id in urgent, note = urgent[it.id] ?: it.note) else it
        })

    fun since(ctx: Context, ts: Long) = all(ctx).filter { it.ts >= ts }
    fun pending(ctx: Context) = all(ctx).filter { !it.triaged }
    fun byId(ctx: Context, id: Long) = all(ctx).firstOrNull { it.id == id }

    fun line(e: Item): String {
        val tm = SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(e.ts))
        val kind = when (e.kind) { "mail" -> "письмо"; "sms" -> "SMS"; "msg" -> "сообщение"; else -> "уведомление" }
        return "#${e.id} $tm $kind [${e.source}] ${e.from}${if (e.title.isNotBlank() && e.title != e.from) " — ${e.title}" else ""}: ${e.text.take(400)}"
    }
}

/** Итоги дня — последние две недели. */
object Digests {
    data class D(val ts: Long, val text: String)
    val version = mutableIntStateOf(0)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("digests", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<D> {
        val a = runCatching { JSONArray(sp(ctx).getString("d", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { D(it.optLong("ts"), it.optString("t")) }
    }

    @Synchronized
    fun add(ctx: Context, text: String) {
        val a = JSONArray()
        (all(ctx) + D(System.currentTimeMillis(), text)).takeLast(14).forEach { a.put(JSONObject().put("ts", it.ts).put("t", it.text)) }
        sp(ctx).edit().putString("d", a.toString()).apply()
        version.intValue = version.intValue + 1
    }
}

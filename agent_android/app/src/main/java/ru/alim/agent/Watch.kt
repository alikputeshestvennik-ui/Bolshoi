package ru.alim.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * «Слежка»: Пилот сам раз в N минут проверяет тему в нескольких источниках
 * и пишет Алиму только когда что-то изменилось. Первый ответ — сразу, в конце — «закончил».
 */
object Watch {
    class Item(
        val id: Long, val topic: String, val what: String, val sources: List<String>,
        val everyMin: Int, val until: Long,
        var state: String = "", var key: String = "", var src: String = "",
        var nextAt: Long = 0, var runs: Int = 0, var changes: Int = 0,
        var status: String = "active", var err: String = "",
        /** "state" — состояние темы; "feed" — новые посты Telegram-канала/группы. */
        val mode: String = "state", val chatId: Long = 0, var lastMsg: Long = 0,
    ) { val title get() = (if (mode == "feed") "Лента #" else "Слежка #") + "${id % 1000}: $topic" }

    val items = androidx.compose.runtime.mutableStateListOf<Item>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var app: Context? = null
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "watch.json")

    @Synchronized fun init(ctx: Context) {
        app = ctx.applicationContext
        if (loaded) return
        loaded = true
        runCatching {
            val a = JSONArray(file(ctx).readText())
            for (k in 0 until a.length()) {
                val o = a.getJSONObject(k)
                val src = o.optJSONArray("src")?.let { s -> (0 until s.length()).map { s.optString(it) } }.orEmpty()
                items += Item(o.getLong("id"), o.optString("topic"), o.optString("what"), src, o.optInt("every", 60), o.optLong("until"),
                    o.optString("state"), o.optString("key"), o.optString("from"), o.optLong("next"), o.optInt("runs"), o.optInt("chg"),
                    o.optString("st", "active"), o.optString("err"), o.optString("mode", "state"), o.optLong("chat"), o.optLong("lastMsg"))
            }
        }
    }

    private fun save() {
        val ctx = app ?: return
        val keep = items.filter { it.status == "active" } + items.filter { it.status != "active" }.takeLast(10)
        val a = JSONArray()
        for (it in keep) a.put(JSONObject().put("id", it.id).put("topic", it.topic).put("what", it.what).put("src", JSONArray(it.sources))
            .put("every", it.everyMin).put("until", it.until).put("state", it.state).put("key", it.key).put("from", it.src)
            .put("next", it.nextAt).put("runs", it.runs).put("chg", it.changes).put("st", it.status).put("err", it.err).put("mode", it.mode).put("chat", it.chatId).put("lastMsg", it.lastMsg))
        runCatching { file(ctx).writeText(a.toString()) }
    }

    fun nextDue(): Long = items.filter { it.status == "active" }.map { minOf(it.nextAt, it.until.takeIf { u -> u > 0 } ?: Long.MAX_VALUE) }.filter { it > 0 }.minOrNull() ?: 0L

    fun start(ctx: Context, topic: String, what: String, sources: List<String>, everyMin: Int, until: Long): String {
        init(ctx)
        val now = System.currentTimeMillis()
        if (items.count { it.status == "active" } >= 5) return "уже идут 5 слежек — останови одну (watch_stop)"
        items.firstOrNull { it.status == "active" && it.topic.equals(topic, true) }?.let { return "за «$topic» уже слежу (#${it.id % 1000}) — сначала останови" }
        val every = everyMin.coerceIn(15, 24 * 60)
        val end = if (until > now) until else endOfDay(now)
        val it = Item(now, topic, what.ifBlank { topic }, sources.map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.take(5), every, end)
        it.nextAt = now + every * 60_000L
        items += it; save(); Planner.plan(ctx)
        scope.launch { check(it, first = true) }
        return "начал слежку «$topic»: проверяю каждые ${every} мин в ${maxOf(it.sources.size, 1) + 1} источниках до ${Deleg.whenText(end)}. Первый результат пришлю сейчас, дальше — только при изменениях."
    }

    /** Лента Telegram: каждые N минут присылать новые посты с фото/видео. */
    suspend fun startFeed(ctx: Context, chat: String, everyMin: Int, until: Long): String {
        init(ctx)
        if (Tg.state != Tg.St.READY) return "личный Telegram не подключён — ленту смотреть не могу"
        val (cid, title) = Tg.resolveChat(chat) ?: return "не нашёл в Telegram «$chat» — уточни название или @ник"
        if (items.any { it.status == "active" && it.mode == "feed" && it.chatId == cid }) return "за лентой «$title» уже слежу"
        if (items.count { it.status == "active" } >= 5) return "уже идут 5 слежек — останови одну (watch_stop)"
        val now = System.currentTimeMillis()
        val every = everyMin.coerceIn(15, 24 * 60)
        val end = if (until > now) until else endOfDay(now)
        val it = Item(now, title, "новые посты", emptyList(), every, end, mode = "feed", chatId = cid)
        it.nextAt = now + every * 60_000L
        items += it; save(); Planner.plan(ctx)
        scope.launch { check(it, first = true) }
        return "смотрю ленту «$title»: проверяю каждые $every мин до ${Deleg.whenText(end)}. Последний пост пришлю сейчас, дальше — каждый новый с фото/видео."
    }

    fun stop(ctx: Context, id: Long?): String {
        init(ctx)
        if (id != null) items.firstOrNull { x -> x.id % 1000 == id % 1000 && x.status != "active" }?.let { x -> return "«${x.title}» уже не идёт" }
        val it = (if (id != null) items.firstOrNull { x -> x.id % 1000 == id % 1000 && x.status == "active" } else items.lastOrNull { x -> x.status == "active" })
            ?: return "активных слежек нет — нечего останавливать"
        it.status = "stopped"; save(); Planner.plan(ctx)
        return "остановил слежку «${it.topic}»" + (if (it.state.isNotBlank()) ". Последнее: ${it.state}" else "")
    }

    fun list(): String = items.takeLast(8).reversed().joinToString("\n") { x ->
        "#${x.id % 1000} ${x.topic} — " + (if (x.status == "active") "каждые ${x.everyMin} мин до ${Deleg.whenText(x.until)}" else "закончена") +
            (if (x.state.isNotBlank()) "; сейчас: ${x.state}" else "") + " (проверок ${x.runs}, изменений ${x.changes})"
    }.ifBlank { "слежек нет" }

    fun summary(): String = items.filter { it.status == "active" }.joinToString("\n") { x ->
        "- #${x.id % 1000} ${x.topic}: " + x.state.ifBlank { "первая проверка" } + " (каждые ${x.everyMin} мин до ${Deleg.whenText(x.until)})"
    }

    suspend fun tick() {
        val now = System.currentTimeMillis()
        for (it in items.toList()) {
            if (it.status != "active") continue
            if (it.until in 1..now) {
                it.status = "done"; save()
                tell(it, "🏁 ${it.title}\nЗакончил следить (${Deleg.whenText(it.until)}). Проверок: ${it.runs}, изменений: ${it.changes}." +
                    (if (it.state.isNotBlank()) "\nПоследнее: ${it.state}" else ""), "закончил")
                continue
            }
            if (it.nextAt in 1..now) { it.nextAt = now + it.everyMin * 60_000L; save(); check(it, first = false) }
        }
        app?.let { Planner.plan(it) }
    }

    private suspend fun checkFeed(it: Item, first: Boolean) {
        val ctx = app ?: return
        if (Tg.state != Tg.St.READY) { runCatching { Tg.start(ctx) }; kotlinx.coroutines.delay(8000) }
        val posts = runCatching { Tg.feed(it.chatId, if (first) 0L else it.lastMsg) }.getOrDefault(emptyList())
        it.runs++
        if (first) it.lastMsg = maxOf(it.lastMsg, runCatching { Tg.lastId(it.chatId) }.getOrDefault(0L))
        if (first && posts.isEmpty()) { save(); tell(it, "📡 ${it.title}\nПостов пока не вижу. Проверяю каждые ${it.everyMin} мин до ${Deleg.whenText(it.until)}.", "смотрю ленту"); return }
        for ((k, p) in posts.withIndex()) {
            it.lastMsg = maxOf(it.lastMsg, p.id)
            if (!first) it.changes++
            val head = (if (first) "📡 ${it.title} — последний пост" else "🆕 ${it.topic}") + " · ${Tg.postTime(p.date)}"
            val tail = if (first && k == posts.lastIndex) "\n\nДальше пришлю каждый новый пост, до ${Deleg.whenText(it.until)}." else ""
            post(it, "$head\n${p.text.ifBlank { "(без текста)" }}$tail", p.media)
        }
        save()
    }

    private fun post(it: Item, text: String, media: List<String>) {
        val ctx = app ?: return
        scope.launch(Dispatchers.Main) {
            runCatching { Chat.main.add(Msg(Chat.main.newId(), false, text, System.currentTimeMillis(), status = "read", tgPhotos = media.joinToString("\n"))) }
        }
        Monitor.notify(ctx, 7500 + (it.id % 50).toInt(), it.topic, text.lines().drop(1).joinToString(" ").take(200), open = "chat:main")
        Alert.fire(ctx, Alert.NORMAL)
    }

    private suspend fun check(it: Item, first: Boolean) = lock.withLock {
        val ctx = app ?: return@withLock
        if (it.mode == "feed") { checkFeed(it, first); return@withLock }
        val got = gather(ctx, it)
        it.runs++
        if (got.isBlank()) {
            it.err = "источники не ответили"; save()
            if (first) tell(it, "👁 ${it.title}\nИсточники сейчас не ответили — попробую через ${it.everyMin} мин.", "нет данных")
            return@withLock
        }
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "Ты следишь за ситуацией для Алима. Тема: ${it.topic}. Что важно: ${it.what}.\n" +
                "Тебе дают свежие данные из нескольких источников. Сведи их в ОДНУ строку текущего состояния (до 160 знаков, конкретно: открыто/закрыто, пробки, сколько ждать, цифры) и укажи источник и время данных, если есть.\n" +
                "key — короткий нормализованный ключ состояния (2–5 слов, например «открыт; пробки небольшие»). Одинаковая ситуация — одинаковый key, даже если слова в источниках другие.\n" +
                "changed=true только если СУЩЕСТВЕННО изменилось по сравнению с прошлым состоянием (открыли/закрыли, ограничения, резкий рост/спад). Мелкие колебания и пересказ того же — false.\n" +
                "Источники противоречат — бери самый свежий и упомяни расхождение. Данных о теме нет — state_line «нет свежих данных», changed=false.\n" +
                "Ответ — только JSON: {\"state_line\": \"…\", \"key\": \"…\", \"changed\": true|false, \"source\": \"…\"}"))
            .put(JSONObject().put("role", "user").put("content",
                "ПРОШЛОЕ СОСТОЯНИЕ: " + (it.state.ifBlank { "(первая проверка)" }) + " [key: ${it.key}]\n" +
                "СЕЙЧАС ${Deleg.whenText(System.currentTimeMillis())}.\n\nДАННЫЕ:\n" + got.take(9000)))
        val models = listOf(Prefs.bgModel, Prefs.smartModel, Prefs.fastModel).filter { m -> !Llm.isYandex(m) }.ifEmpty { listOf(Prefs.bgModel, Prefs.fastModel) }
        var j: JSONObject? = null
        for (m in models.distinct()) {
            val t = runCatching { Llm.chat(m, msgs, 500).text }.getOrNull() ?: continue
            j = runCatching { JSONObject(t.substringAfter('{', "").let { b -> "{" + b.substringBeforeLast('}') + "}" }) }.getOrNull()
            if (j != null && j.optString("state_line").isNotBlank()) break
        }
        val jj = j ?: run { it.err = "не смог разобрать"; save(); return@withLock }
        val line = jj.optString("state_line").trim().take(220)
        val key = jj.optString("key").trim().lowercase()
        val src = jj.optString("source").trim().take(80)
        val changed = !first && line.isNotBlank() && !line.startsWith("нет свежих", true) &&
            (jj.optBoolean("changed") || (key.isNotBlank() && it.key.isNotBlank() && key != it.key && jj.has("changed").not()))
        val prev = it.state
        if (first || changed || it.state.isBlank()) { it.state = line; it.key = key; it.src = src }
        it.err = ""; save()
        when {
            first -> tell(it, "👁 ${it.title}\nСейчас: $line" + srcTail(line, src) +
                "\nПроверяю каждые ${it.everyMin} мин до ${Deleg.whenText(it.until)}, напишу только если изменится.", line)
            changed -> { it.changes++; save()
                tell(it, "🔔 ${it.topic}: $line" + srcTail(line, src) + (if (prev.isNotBlank()) "\nБыло: $prev" else ""), line) }
        }
    }

    private fun srcTail(line: String, src: String): String {
        if (src.isBlank() || src.contains("нет данных", true)) return ""
        val key = src.substringBefore(',').substringBefore(" —").trim().lowercase()
        return if (key.length >= 3 && line.lowercase().contains(key.take(12))) "" else " ($src)"
    }

    /** Собираем данные: каналы (@ник), страницы (http…), плюс поиск по теме. */
    private suspend fun gather(ctx: Context, it: Item): String {
        val parts = ArrayList<String>()
        suspend fun tool(n: String, a: JSONObject): String? = runCatching { Tools.byName(n)?.run?.invoke(ctx, a) }.getOrNull()
            ?.takeIf { r -> r.isNotBlank() && !r.startsWith("поиск ничего") && !r.contains("не найден") }
        for (s in it.sources) {
            val r = when {
                s.startsWith("@") || s.contains("t.me/") -> tool("tg_channel", JSONObject().put("username", s))
                s.startsWith("http") -> tool("web_read", JSONObject().put("url", s))
                else -> tool("web_search", JSONObject().put("query", s))
            }
            if (r != null) parts += "ИСТОЧНИК $s:\n" + r.take(2500)
        }
        val urls = LinkedHashSet<String>()
        for (q in listOf("${it.topic} сейчас", "${it.topic} ${it.what}".take(120)).distinct()) {
            tool("web_search", JSONObject().put("query", q))?.let { r ->
                parts += "ПОИСК «$q»:\n" + r.take(2500)
                Regex("https?://[^\\s)\\]>\"]+").findAll(r).map { m -> m.value.trimEnd('.', ',') }.filterNot { u -> u.contains("yandex.ru/search") || u.contains("duckduckgo") }.take(2).forEach { u -> urls += u }
            }
        }
        // свежие цифры обычно в самой статье, а не в сниппете — читаем лучшую ссылку
        for (u in urls.take(if (it.sources.isEmpty()) 2 else 1)) tool("web_read", JSONObject().put("url", u))?.let { r -> parts += "СТАТЬЯ $u:\n" + r.take(3000) }
        return parts.joinToString("\n\n=====\n\n")
    }

    private fun tell(it: Item, text: String, short: String) {
        val ctx = app ?: return
        val change = text.startsWith("🔔")
        Alert.fire(ctx, if (change) Alert.IMPORTANT else Alert.NORMAL, if (change) "${it.topic}: ${short.take(160)}" else "")
        scope.launch(Dispatchers.Main) { runCatching { Chat.main.say(text) } }
        Monitor.notify(ctx, 7500 + (it.id % 50).toInt(), it.topic, short.take(200), open = "chat:main")
    }

    private fun endOfDay(now: Long): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
    }
}

/** Один будильник на ближайшее событие поручений и слежки — будит телефон и в режиме сна. */
object Planner {
    fun plan(ctx: Context) {
        val c = ctx.applicationContext
        val next = listOf(Deleg.nextDue(), Watch.nextDue()).filter { it > 0 }.minOrNull()
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 4711, Intent(c, PlanAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (next == null) { am.cancel(pi); return }
        val at = maxOf(next, System.currentTimeMillis() + 30_000L)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class PlanAlarm : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val res = goAsync()
        val ctx = c.applicationContext
        val wl = ctx.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pilot:plan")
        wl.acquire(3 * 60_000L)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { Prefs.init(ctx) }; runCatching { Chat.init(ctx) }
                Deleg.init(ctx); Watch.init(ctx)
                runCatching { Deleg.tick() }
                runCatching { Watch.tick() }
                Planner.plan(ctx)
            } finally { runCatching { wl.release() }; res.finish() }
        }
    }
}

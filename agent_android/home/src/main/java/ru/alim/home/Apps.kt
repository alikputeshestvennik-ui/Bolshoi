package ru.alim.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Приложение на экране: что запускать и как подписать. */
data class App(val pkg: String, val cls: String, val label: String) {
    val key get() = "$pkg/$cls"
}

/** Список приложений, главный экран, скрытые, свои буквы — всё хранится в настройках лаунчера. */
object Apps {
    private const val TEN = "ru.alim.agent"
    private fun sp(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load(c: Context): List<App> {
        val pm = c.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != c.packageName }
            .map { App(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString().trim().ifBlank { it.activityInfo.packageName }) }
            .distinctBy { it.key }
        return list.sortedWith(compareBy({ script(it.label) }, { it.label.lowercase() }))
    }

    /** Кириллица — первой, потом латиница, потом остальное. */
    private fun script(s: String): Int {
        val ch = s.firstOrNull { it.isLetterOrDigit() } ?: return 3
        return when {
            ch in 'А'..'я' || ch == 'Ё' || ch == 'ё' -> 0
            ch in 'A'..'Z' || ch in 'a'..'z' -> 1
            ch.isDigit() -> 2
            else -> 3
        }
    }

    /** Буква раздела в списке. */
    fun section(a: App): String {
        val ch = a.label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'
        return if (ch.isDigit()) "#" else ch.toString().replace('Ё', 'Е')
    }

    // ——— главный экран ———

    private val PREFERRED = listOf(
        "org.telegram.messenger", "ru.yandex.yandexmaps", "com.deepseek.chat", "com.google.android.gm",
        "ru.nspk.mirpay", "ru.oneme.app", "com.yandex.aliceapp", "ru.rostel", "com.anthropic.claude",
        "com.google.android.youtube", "ru.yandex.weatherplugin", "ru.alim.route"
    )
    const val HOME_MAX = 16

    fun home(c: Context, all: List<App>): List<App> {
        val saved = sp(c).getString("home", null)
        val byKey = all.associateBy { it.key }
        if (saved != null) return runCatching { JSONArray(saved).let { a -> (0 until a.length()).mapNotNull { byKey[a.optString(it)] } } }.getOrDefault(emptyList())
        val picked = ArrayList<App>()
        PREFERRED.forEach { p -> all.firstOrNull { it.pkg == p }?.let(picked::add) }
        all.filter { it.pkg !in DOCK_PKGS(c) && it !in picked && it.pkg != TEN }.forEach { if (picked.size < 12) picked += it }
        return picked.take(12)
    }

    fun setHome(c: Context, list: List<App>) {
        sp(c).edit().putString("home", JSONArray().also { a -> list.take(HOME_MAX).forEach { a.put(it.key) } }.toString()).apply()
    }

    // ——— скрытые и буквы ———

    fun hidden(c: Context): Set<String> = sp(c).getStringSet("hidden", emptySet()) ?: emptySet()
    fun setHidden(c: Context, key: String, hide: Boolean) {
        val s = hidden(c).toMutableSet(); if (hide) s += key else s -= key
        sp(c).edit().putStringSet("hidden", s).apply()
    }

    fun customLetters(c: Context): Map<String, String> = runCatching {
        val o = JSONObject(sp(c).getString("letters", "{}") ?: "{}"); o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())
    fun setLetter(c: Context, key: String, v: String?) {
        val o = JSONObject(sp(c).getString("letters", "{}") ?: "{}")
        if (v.isNullOrBlank()) o.remove(key) else o.put(key, v.trim().take(2))
        sp(c).edit().putString("letters", o.toString()).apply()
    }

    private fun first(label: String): String = (label.firstOrNull { it.isLetterOrDigit() } ?: '•').uppercaseChar().toString()

    /** Две буквы, когда первые совпали: «Mir Pay» → «MP», «Маршрут» → «Ма». */
    private fun two(label: String): String {
        val words = label.split(Regex("[\\s.\\-·]+")).filter { w -> w.any { it.isLetterOrDigit() } }
        if (words.size >= 2) return first(words[0]) + (words[1].firstOrNull { it.isLetterOrDigit() } ?: ' ').toString()
        val letters = label.filter { it.isLetterOrDigit() }
        return if (letters.length >= 2) letters[0].uppercaseChar().toString() + letters[1].lowercaseChar() else first(label)
    }

    /** Буквы для плиток: одна; если на главном у двух одинаковая — по две. Свои буквы Алима — главнее. */
    fun letters(c: Context, shown: List<App>): Map<String, String> {
        val custom = customLetters(c)
        val auto = shown.associate { it.key to first(it.label) }
        val clash = auto.filter { (k, v) -> k !in custom && auto.count { it.value == v } > 1 }.keys
        return shown.associate { a -> a.key to (custom[a.key] ?: if (a.key in clash) two(a.label) else auto[a.key]!!) }
    }

    // ——— нижний ряд: системные действия ———

    @Suppress("FunctionName")
    private fun DOCK_PKGS(c: Context): Set<String> = setOfNotNull(TEN, defaultPkg(c, Intent(Intent.ACTION_DIAL)),
        defaultPkg(c, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)),
        defaultPkg(c, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))

    private fun defaultPkg(c: Context, i: Intent): String? = runCatching {
        c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()

    fun launch(c: Context, a: App): Boolean = start(c, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(a.pkg, a.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))

    fun start(c: Context, i: Intent): Boolean = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    fun dial(c: Context) = start(c, Intent(Intent.ACTION_DIAL))
    fun sms(c: Context) = start(c, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)) ||
        (android.provider.Telephony.Sms.getDefaultSmsPackage(c)?.let { p -> c.packageManager.getLaunchIntentForPackage(p)?.let { start(c, it) } } ?: false)
    fun browser(c: Context) = start(c, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)) ||
        start(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://ya.ru")))
    fun camera(c: Context) = start(c, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
    fun ten(c: Context) = c.packageManager.getLaunchIntentForPackage(TEN)?.let { start(c, it) } ?: false
    /** «Спроси Тень» — быстрое окно Тени поверх главного экрана. */
    fun ask(c: Context) = start(c, Intent(Intent.ACTION_ASSIST).setClassName(TEN, "$TEN.QuickActivity")) || ten(c)
    fun info(c: Context, a: App) = start(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
    fun uninstall(c: Context, a: App) = start(c, Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))

    /** Свайп вниз — шторка уведомлений (официального способа нет, но на Самсунге работает). */
    fun shade(c: Context) {
        runCatching {
            @Suppress("WrongConstant") val sb = c.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }
    }

    // ——— вопрос Тени прямо с главного ———

    class Msg(val text: String, val rich: Boolean)
    class Reply(val working: Boolean, val step: String, val msgs: List<Msg>, val error: String = "")

    /** Отправить вопрос в чат Тени. Время отправки — с него ждём ответ. null — Тень недоступна. */
    fun ask(c: Context, text: String): Long? = runCatching {
        c.contentResolver.call(Uri.parse("content://ru.alim.agent.home"), "ask", text, null)?.getLong("ts")
    }.getOrNull()

    fun reply(c: Context, since: Long): Reply? = runCatching {
        c.contentResolver.query(Uri.parse("content://ru.alim.agent.home/reply?since=$since"), null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) return@use null
            val o = JSONObject(cur.getString(0))
            val a = o.optJSONArray("msgs") ?: JSONArray()
            Reply(o.optBoolean("working"), o.optString("step"),
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Msg(it.optString("text"), it.optBoolean("files") || it.optBoolean("items")) })
        }
    }.getOrNull()

    // ——— данные Тени ———

    class TenState(val ok: Boolean, val lastText: String, val lastTs: Long, val lastMeta: String, val spent: Double, val busy: Boolean,
                   val gameTitle: String, val gameTurns: Int, val gameLimit: Int)

    fun state(c: Context): TenState = runCatching {
        c.contentResolver.query(Uri.parse("content://ru.alim.agent.home/status"), null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) return@use null
            val o = JSONObject(cur.getString(0))
            val last = o.optJSONObject("last"); val g = o.optJSONObject("game")
            TenState(true, last?.optString("text").orEmpty(), last?.optLong("ts") ?: 0L, last?.optString("meta").orEmpty(),
                o.optDouble("spent", 0.0), o.optBoolean("busy"), g?.optString("title").orEmpty(), g?.optInt("turns") ?: 0, g?.optInt("limit") ?: 0)
        }
    }.getOrNull() ?: TenState(false, "", 0, "", 0.0, false, "", 0, 0)
}

/** Что лежит на рабочих столах: приложение или папка. */
sealed class Item { abstract val id: String }
data class AppItem(val app: App) : Item() { override val id get() = app.key }
data class FolderItem(val fid: String, val name: String, val apps: List<App>) : Item() { override val id get() = "folder:$fid" }

object Desk {
    /** Первый стол — под карточками Тени, 3 ряда; следующие — во весь экран, 6 рядов. */
    const val FIRST = 12
    const val NEXT = 24
    const val PAGES = 7
    fun cap(page: Int) = if (page == 0) FIRST else NEXT

    private fun sp(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    private fun parse(o: Any?, byKey: Map<String, App>): Item? = when (o) {
        is String -> byKey[o]?.let { AppItem(it) }
        is JSONObject -> {
            val ks = o.optJSONArray("a") ?: JSONArray()
            val apps = (0 until ks.length()).mapNotNull { byKey[ks.optString(it)] }
            when { apps.size >= 2 -> FolderItem(o.optString("f"), o.optString("n").ifBlank { "Папка" }, apps); apps.size == 1 -> AppItem(apps[0]); else -> null }
        }
        else -> null
    }

    /** Столы по отдельности. Старый формат (одним списком) раскладываем по столам. */
    fun load(c: Context, all: List<App>): List<List<Item>> {
        val byKey = all.associateBy { it.key }
        val raw = sp(c).getString("home", null) ?: return normalize(listOf(Apps.home(c, all).map { AppItem(it) }))
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return listOf(emptyList())
        if (a.length() > 0 && a.opt(0) is JSONArray)
            return normalize((0 until a.length()).map { i -> val pa = a.optJSONArray(i) ?: JSONArray(); (0 until pa.length()).mapNotNull { parse(pa.opt(it), byKey) } })
        val flat = (0 until a.length()).mapNotNull { parse(a.opt(it), byKey) }
        return normalize(listOf(flat.take(FIRST)) + flat.drop(FIRST).chunked(NEXT))
    }

    fun save(c: Context, pages: List<List<Item>>) {
        val out = JSONArray()
        pages.forEach { pg ->
            val a = JSONArray()
            pg.forEach { when (it) {
                is AppItem -> a.put(it.app.key)
                is FolderItem -> a.put(JSONObject().put("f", it.fid).put("n", it.name).put("a", JSONArray().also { j -> it.apps.forEach { x -> j.put(x.key) } }))
            } }
            out.put(a)
        }
        sp(c).edit().putString("home", out.toString()).apply()
    }

    /** Лишнее со стола переезжает на следующий (создаётся сам), пустые столы убираются. */
    fun normalize(pages: List<List<Item>>): List<List<Item>> {
        val out = pages.map { it.toMutableList() }.toMutableList()
        if (out.isEmpty()) out.add(mutableListOf())
        var i = 0
        while (i < out.size) {
            while (out[i].size > cap(i) && i < PAGES - 1) {
                val x = out[i].removeAt(out[i].size - 1)
                if (i + 1 >= out.size) out.add(mutableListOf())
                out[i + 1].add(0, x)
            }
            i++
        }
        return out.filterIndexed { k, p -> k == 0 || p.isNotEmpty() }
    }

    fun appsIn(pages: List<List<Item>>): List<App> = pages.flatten().flatMap { when (it) { is AppItem -> listOf(it.app); is FolderItem -> it.apps } }
    fun contains(pages: List<List<Item>>, key: String) = appsIn(pages).any { it.key == key }
    fun find(pages: List<List<Item>>, id: String): Pair<Int, Int>? {
        pages.forEachIndexed { p, l -> val i = l.indexOfFirst { it.id == id }; if (i >= 0) return p to i }
        return null
    }

    private fun removeKey(l: List<Item>, key: String): List<Item> = l.mapNotNull { when (it) {
        is AppItem -> if (it.app.key == key) null else it
        is FolderItem -> { val rest = it.apps.filter { a -> a.key != key }; when { rest.size >= 2 -> it.copy(apps = rest); rest.size == 1 -> AppItem(rest[0]); else -> null } }
    } }
    /** Убрать приложение откуда угодно (стол или папка); папка из одного — снова приложение. */
    fun remove(pages: List<List<Item>>, key: String) = normalize(pages.map { removeKey(it, key) })

    /** Добавить: на последний стол, если там есть место, иначе — новый стол. */
    fun add(pages: List<List<Item>>, item: Item): List<List<Item>> {
        val l = pages.map { it.toMutableList() }.toMutableList()
        if (l.last().size < cap(l.size - 1) || l.size >= PAGES) l.last().add(item) else l.add(mutableListOf(item))
        return normalize(l)
    }

    /** Перенести на стол page в позицию idx (idx < 0 — в конец). Стола ещё нет — появится. */
    fun moveTo(pages: List<List<Item>>, id: String, page: Int, idx: Int): List<List<Item>> {
        val at = find(pages, id) ?: return pages
        val item = pages[at.first][at.second]
        val l = pages.map { p -> p.filter { it.id != id }.toMutableList() }.toMutableList()
        while (l.size <= page && l.size < PAGES) l.add(mutableListOf())
        val tgt = l[page.coerceAtMost(l.size - 1)]
        tgt.add(if (idx < 0) tgt.size else idx.coerceIn(0, tgt.size), item)
        return normalize(l)
    }

    /** Бросил приложение на другое — папка; на папку — внутрь. */
    fun merge(pages: List<List<Item>>, dragId: String, targetId: String): List<List<Item>> {
        val at = find(pages, dragId) ?: return pages
        val drag = pages[at.first][at.second] as? AppItem ?: return pages
        return normalize(pages.map { pg -> pg.mapNotNull { x -> when {
            x.id == dragId -> null
            x.id == targetId && x is AppItem -> FolderItem(System.currentTimeMillis().toString(36), "Папка", listOf(x.app, drag.app))
            x.id == targetId && x is FolderItem -> x.copy(apps = x.apps + drag.app)
            else -> x
        } } })
    }

    fun replace(pages: List<List<Item>>, id: String, with: List<Item>) = normalize(pages.map { pg -> pg.flatMap { if (it.id == id) with else listOf(it) } })
    fun insertAfter(pages: List<List<Item>>, id: String, item: Item) = normalize(pages.map { pg -> pg.flatMap { if (it.id == id) listOf(it, item) else listOf(it) } })
    fun folder(pages: List<List<Item>>, fid: String) = pages.flatten().firstOrNull { it is FolderItem && it.fid == fid } as? FolderItem
}

/** Недавние приложения: из статистики Android (если дан доступ), иначе — что открывали из лаунчера. */
object Recent {
    private fun sp(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun granted(c: Context): Boolean = runCatching {
        val ops = c.getSystemService(android.app.AppOpsManager::class.java)
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName)
            else @Suppress("DEPRECATION") ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun note(c: Context, a: App) {
        val l = (listOf(a.pkg) + (sp(c).getString("recent", "") ?: "").split(',').filter { it.isNotBlank() && it != a.pkg }).take(20)
        sp(c).edit().putString("recent", l.joinToString(",")).apply()
    }

    fun list(c: Context, all: List<App>, n: Int = 10): List<App> {
        val byPkg = all.groupBy { it.pkg }
        val pkgs: List<String> = if (granted(c)) runCatching {
            val um = c.getSystemService(android.app.usage.UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            um.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, now - 3 * 86_400_000L, now)
                .filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 0 }
                .sortedByDescending { it.lastTimeUsed }.map { it.packageName }.distinct()
        }.getOrDefault(emptyList()) else (sp(c).getString("recent", "") ?: "").split(',')
        return pkgs.filter { it != c.packageName }.mapNotNull { byPkg[it]?.firstOrNull() }.take(n)
    }

    fun ask(c: Context) = Apps.start(c, android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

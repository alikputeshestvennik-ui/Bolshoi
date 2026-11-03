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

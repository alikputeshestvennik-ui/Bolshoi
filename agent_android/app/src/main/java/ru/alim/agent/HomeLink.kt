package ru.alim.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Данные для лаунчера «Тень · Дом»: последнее сообщение Тени, траты за сегодня, идёт ли задача, игра.
 * Доступ — только приложениям с той же подписью (permission signature). Читаем файлы напрямую,
 * без запуска всего агента: лаунчер спрашивает часто, а Тень может быть не запущена.
 */
class HomeProvider : ContentProvider() {
    override fun onCreate() = true

    private fun allowed() = context?.checkCallingOrSelfPermission("ru.alim.agent.permission.HOME") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Поднять Тень в процессе так же, как при открытии окна (без экрана). */
    private fun boot() {
        val ctx = context ?: return
        Prefs.init(ctx); runCatching { Th.load() }
        if (!Agent.ready()) Agent.app = ctx.applicationContext
        Chat.init(ctx); runCatching { Deleg.init(ctx) }; runCatching { Watch.init(ctx) }; Online.init(ctx)
        runCatching { ArturService.start(ctx) }   // держит Тень живой, пока она отвечает
    }

    /** «ask» — вопрос с главного экрана лаунчера: уходит в чат Тени как обычное сообщение. Возвращает время отправки. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!allowed()) throw SecurityException("нет доступа")
        // «Передать Тени» из СМС лаунчера: переписка целиком + цель и указания
        if (method == "handoff" && extras != null) {
            val out = Bundle()
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    boot()
                    val h = JSONArray(extras.getString("history") ?: "[]")
                    val lines = (0 until h.length()).mapNotNull { h.optJSONObject(it) }.map { Deleg.Line(it.optBoolean("me"), it.optString("t"), it.optLong("ts")) }
                    out.putString("msg", Deleg.handoff(context!!, extras.getString("who").orEmpty().ifBlank { extras.getString("addr").orEmpty() }, extras.getString("addr").orEmpty(),
                        extras.getString("goal").orEmpty(), extras.getString("notes").orEmpty(), lines, extras.getBoolean("confirm"), extras.getLong("until"), extras.getBoolean("manual")))
                } catch (e: Exception) { out.putString("msg", "не вышло: ${e.message?.take(80)}") } finally { done.countDown() }
            }
            done.await(5, TimeUnit.SECONDS)
            return out
        }
        if (method == "handoffState" || method == "takeback") {
            val out = Bundle()
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    boot()
                    val it = Deleg.activeFor(arg.orEmpty())
                    if (method == "takeback" && it != null) { out.putString("msg", Deleg.stop(context!!, it.id)); out.putBoolean("active", false) }
                    else { out.putBoolean("active", it != null); out.putString("state", it?.let { x -> Deleg.stateText(x) }.orEmpty()); out.putString("goal", it?.goal.orEmpty()) }
                } catch (e: Exception) { out.putBoolean("active", false) } finally { done.countDown() }
            }
            done.await(5, TimeUnit.SECONDS)
            return out
        }
        if (method != "ask" || arg.isNullOrBlank()) return null
        val ts = System.currentTimeMillis()
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { try { boot(); Chat.main.send(arg.trim()) } finally { done.countDown() } }
        done.await(5, TimeUnit.SECONDS)
        return Bundle().apply { putLong("ts", ts) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val c = MatrixCursor(arrayOf("json"))
        val ctx = context ?: return c
        // ответ на вопрос с главного: всё, что Тень написала после since, и работает ли ещё
        if (uri.lastPathSegment == "reply") {
            val since = uri.getQueryParameter("since")?.toLongOrNull() ?: 0L
            val o = JSONObject()
            runCatching {
                val core = Agent.of(Friends.MAIN)
                o.put("working", core.busy || core.fastBusy)
                o.put("step", runCatching { core.steps.lastOrNull { !it.done }?.text.orEmpty() }.getOrDefault(""))
                val out = JSONArray()
                Chat.main.msgs.toList().filter { !it.me && !it.note && it.ts >= since && it.text.isNotBlank() }.forEach { m ->
                    out.put(JSONObject().put("text", m.text.take(4000)).put("ts", m.ts).put("files", m.files.isNotBlank()).put("items", m.items.isNotBlank()))
                }
                o.put("msgs", out)
            }.onFailure { o.put("working", false).put("msgs", JSONArray()) }
            c.addRow(arrayOf(o.toString())); return c
        }
        val o = JSONObject()
        runCatching {
            val a = JSONArray(File(ctx.filesDir, "chat.json").readText())
            for (i in a.length() - 1 downTo 0) {
                val m = a.optJSONObject(i) ?: continue
                if (m.optBoolean("me") || m.optBoolean("n")) continue
                val t = m.optString("t").trim()
                if (t.isBlank()) continue
                o.put("last", JSONObject().put("text", t.take(400)).put("ts", m.optLong("ts")).put("meta", m.optString("mt")))
                break
            }
        }
        runCatching { o.put("spent", Journal.all(ctx).filter { it.ts >= Journal.startOfDay() }.sumOf { it.cost }) }
        runCatching { o.put("busy", Agent.ready() && Agent.busy) }
        runCatching {
            val g = JSONObject(File(ctx.filesDir, "game_${Friends.MAIN}.json").readText())
            if (!g.optBoolean("over")) o.put("game", JSONObject().put("kind", g.optString("kind")).put("title", g.optJSONObject("secret")?.optString("title").orEmpty())
                .put("turns", g.optInt("turns")).put("limit", g.optInt("limit")))
        }
        c.addRow(arrayOf(o.toString()))
        return c
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

package ru.alim.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
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

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val c = MatrixCursor(arrayOf("json"))
        val ctx = context ?: return c
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

package ru.alim.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Запись падений Тени: причина сохраняется в файл, при следующем запуске — заметка в чате,
 * а текст ошибки сразу в буфере обмена (вставить в чат Claude).
 */
object CrashLog {
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return; installed = true
        val f = File(ctx.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                f.writeText("Тень ${BuildConfig.VERSION_CODE} · " + java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale("ru")).format(java.util.Date()) +
                    " · поток ${t.name}\n" + sw.toString().take(8000))
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Было падение — показать и скопировать (один раз). */
    fun report(ctx: Context) {
        val f = File(ctx.filesDir, "crash.txt")
        val txt = runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")
        if (txt.isBlank()) return
        runCatching { f.delete() }
        runCatching { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Ошибка Тени", txt)) }
        val head = txt.lines().drop(1).take(3).joinToString(" / ").take(260)
        runCatching { Chat.main.note("Тень падала: $head\nТекст ошибки уже скопирован — вставь его в чат Claude, по нему видно, где поломка.") }
    }
}

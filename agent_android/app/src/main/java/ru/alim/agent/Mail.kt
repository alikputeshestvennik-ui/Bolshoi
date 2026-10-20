package ru.alim.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sun.mail.imap.IMAPFolder
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress

/**
 * Gmail через IMAP, только чтение (папка открывается READ_ONLY — письма не помечаются прочитанными).
 * Вход — пароль приложения Google (нужна двухэтапная проверка), хранится зашифрованно на телефоне.
 */
object Mail {
    class Acc(val n: Int, val email: String, val pass: String)

    private fun sec(ctx: Context): SharedPreferences = runCatching {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(ctx, "mail_sec", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }.getOrElse { ctx.getSharedPreferences("mail_plain", Context.MODE_PRIVATE) }

    private fun state(ctx: Context) = ctx.getSharedPreferences("mail_state", Context.MODE_PRIVATE)

    fun email(ctx: Context, n: Int) = sec(ctx).getString("e$n", "").orEmpty()
    fun hasPass(ctx: Context, n: Int) = sec(ctx).getString("p$n", "").orEmpty().isNotBlank()

    fun accounts(ctx: Context): List<Acc> = (1..2).mapNotNull { n ->
        val e = email(ctx, n); val p = sec(ctx).getString("p$n", "").orEmpty()
        if (e.isNotBlank() && p.isNotBlank()) Acc(n, e, p) else null
    }

    /** Пароль пустой — оставляем прежний. Сменился адрес — читаем ящик заново. */
    fun save(ctx: Context, n: Int, email: String, pass: String) {
        val old = email(ctx, n)
        sec(ctx).edit().putString("e$n", email.trim()).apply {
            if (pass.isNotBlank()) putString("p$n", pass.replace(" ", "").trim())
        }.apply()
        if (old != email.trim()) state(ctx).edit().remove("uid$n").remove("val$n").apply()
    }

    fun remove(ctx: Context, n: Int) {
        sec(ctx).edit().remove("e$n").remove("p$n").apply()
        state(ctx).edit().remove("uid$n").remove("val$n").apply()
    }

    private fun <T> withInbox(acc: Acc, block: (IMAPFolder) -> T): T {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.gmail.com"); put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000"); put("mail.imaps.timeout", "25000")
        }
        val store = Session.getInstance(props).getStore("imaps")
        try {
            store.connect("imap.gmail.com", acc.email, acc.pass)
        } catch (e: javax.mail.AuthenticationFailedException) {
            error("Gmail ${acc.email}: не пускает — нужен пароль приложения (не обычный пароль) и включённая двухэтапная проверка")
        }
        try {
            val f = store.getFolder("INBOX") as IMAPFolder
            f.open(Folder.READ_ONLY)
            try { return block(f) } finally { runCatching { f.close(false) } }
        } finally { runCatching { store.close() } }
    }

    fun check(ctx: Context, n: Int): String {
        val acc = accounts(ctx).firstOrNull { it.n == n } ?: return "заполни адрес и пароль приложения"
        return withInbox(acc) { "подключено, во входящих ${it.messageCount} писем" }
    }

    /** Новые письма с прошлой проверки (первый раз — 10 последних) → лента событий. */
    fun fetchNew(ctx: Context, acc: Acc): Int = withInbox(acc) { f ->
        val st = state(ctx)
        val validity = f.uidValidity
        var last = if (st.getLong("val${acc.n}", -1) == validity) st.getLong("uid${acc.n}", 0) else 0L
        val msgs: Array<Message> = if (last == 0L) {
            val c = f.messageCount
            if (c == 0) emptyArray() else f.getMessages(maxOf(1, c - 9), c)
        } else f.getMessagesByUID(last + 1, UIDFolder.LASTUID)
        f.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(UIDFolder.FetchProfileItem.UID) })
        var added = 0
        for (m in msgs) {
            val uid = f.getUID(m)
            if (uid <= last) continue
            val from = (m.from?.firstOrNull() as? InternetAddress)?.let { it.personal?.takeIf { p -> p.isNotBlank() } ?: it.address }.orEmpty()
            val bulk = m.getHeader("List-Unsubscribe") != null || m.getHeader("Precedence")?.any { it.contains("bulk", true) || it.contains("list", true) } == true
            val text = if (bulk) "" else runCatching { textOf(m) }.getOrDefault("")
            if (Events.add(ctx, "mail", "Gmail ${acc.n}", from, m.subject.orEmpty(), text, bulk) != null) added++
            if (uid > last) last = uid
        }
        st.edit().putLong("val${acc.n}", validity).putLong("uid${acc.n}", last).apply()
        added
    }

    private fun textOf(p: Part, depth: Int = 0): String {
        if (depth > 5) return ""
        return when {
            p.isMimeType("text/plain") -> (p.content as? String).orEmpty()
            p.isMimeType("text/html") -> (p.content as? String).orEmpty()
                .replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                val parts = (0 until mp.count).map { mp.getBodyPart(it) }
                (parts.firstOrNull { it.isMimeType("text/plain") } ?: parts.firstOrNull { it.isMimeType("text/html") })
                    ?.let { textOf(it, depth + 1) }
                    ?: parts.map { textOf(it, depth + 1) }.firstOrNull { it.isNotBlank() }.orEmpty()
            }
            else -> ""
        }.replace(Regex("\\s+"), " ").trim().take(1200)
    }
}

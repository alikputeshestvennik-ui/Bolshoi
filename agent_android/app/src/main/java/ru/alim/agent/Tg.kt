package ru.alim.agent

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Телеграм Алима через официальную библиотеку Telegram (TDLib) и его собственные api-ключи.
 * Это сторонний клиент его же аккаунта — Telegram такое разрешает. Делает только то, что делает сам Алим:
 *  — вход по его номеру и коду (коды входа из служебного чата Telegram приложение не читает и не пересказывает);
 *  — показывает его чаты и ищет по переписке (не помечая сообщения прочитанными);
 *  — отправляет сообщения ТОЛЬКО по прямой просьбе Алима и после его подтверждения.
 * Сессия лежит в закрытой папке приложения, база зашифрована ключом из защищённого хранилища Android.
 */
object Tg {
    enum class St { OFF, NO_KEYS, STARTING, WAIT_PHONE, WAIT_CODE, WAIT_PASSWORD, READY, ERROR }

    var state by mutableStateOf(St.OFF); private set
    var hint by mutableStateOf(""); private set
    var me by mutableStateOf(""); private set
    var error by mutableStateOf("")
    @Volatile var shownOnOpen = false

    private var client: Client? = null
    private var app: Context? = null
    private const val SERVICE_CHAT = 777000L   // служебный чат Telegram (коды входа) — не читаем

    val apiId: Int get() = BuildConfig.TG_API_ID.trim().toIntOrNull() ?: 0
    val apiHash: String get() = BuildConfig.TG_API_HASH.trim()
    val available: Boolean get() = runCatching { Class.forName("org.drinkless.tdlib.Client"); true }.getOrDefault(false)
    val configured: Boolean get() = apiId != 0 && apiHash.isNotBlank()

    // ——— запуск и вход ———

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (state == St.READY || state == St.STARTING) return
        if (!available) { state = St.OFF; error = "Библиотека Telegram не вшита в эту сборку"; return }
        if (!configured) { state = St.NO_KEYS; return }
        state = St.STARTING; error = ""
        runCatching { Client.setLogMessageHandler(0) { _, _ -> } }
        client = Client.create({ u -> onUpdate(u) }, null, null)
    }

    private fun onUpdate(u: TdApi.Object) {
        if (u is TdApi.UpdateAuthorizationState) onAuth(u.authorizationState)
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val p = TdApi.SetTdlibParameters()
                val dir = app!!.filesDir.resolve("tdlib")
                p.databaseDirectory = dir.absolutePath
                p.filesDirectory = dir.resolve("files").absolutePath
                p.databaseEncryptionKey = dbKey()
                p.useFileDatabase = true
                p.useChatInfoDatabase = true
                p.useMessageDatabase = true
                p.useSecretChats = false
                p.apiId = apiId
                p.apiHash = apiHash
                p.systemLanguageCode = "ru"
                p.deviceModel = Build.MODEL ?: "Android"
                p.systemVersion = "Android ${Build.VERSION.RELEASE}"
                p.applicationVersion = "Пилот"
                client?.send(p) { r -> if (r is TdApi.Error) fail("параметры: ${r.message}") }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> { state = St.WAIT_PHONE; hint = "" }
            is TdApi.AuthorizationStateWaitEmailAddress -> { state = St.WAIT_PHONE; hint = "Telegram просит email" }
            is TdApi.AuthorizationStateWaitCode -> { state = St.WAIT_CODE; hint = codeHint(s.codeInfo) }
            is TdApi.AuthorizationStateWaitPassword -> { state = St.WAIT_PASSWORD; hint = s.passwordHint ?: "" }
            is TdApi.AuthorizationStateWaitRegistration -> fail("этот номер не зарегистрирован в Telegram")
            is TdApi.AuthorizationStateReady -> { state = St.READY; hint = ""; loadMe() }
            is TdApi.AuthorizationStateLoggingOut -> state = St.STARTING
            is TdApi.AuthorizationStateClosed -> { state = St.OFF; client = null }
            else -> {}
        }
    }

    private fun codeHint(info: TdApi.AuthenticationCodeInfo?): String = when (val t = info?.type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> "Код пришёл в Telegram на другом устройстве"
        is TdApi.AuthenticationCodeTypeSms -> "Код в SMS на ${info.phoneNumber ?: "твой номер"}"
        is TdApi.AuthenticationCodeTypeCall -> "Код продиктуют звонком"
        else -> "Введи код из Telegram"
    }

    fun sendPhone(phone: String) {
        val f = TdApi.SetAuthenticationPhoneNumber()
        f.phoneNumber = phone.trim()
        client?.send(f) { r -> if (r is TdApi.Error) fail("номер: ${r.message}") }
    }

    fun sendCode(code: String) {
        val f = TdApi.CheckAuthenticationCode()
        f.code = code.trim()
        client?.send(f) { r -> if (r is TdApi.Error) fail("код не подошёл: ${r.message}") }
    }

    fun sendPassword(pwd: String) {
        val f = TdApi.CheckAuthenticationPassword()
        f.password = pwd
        client?.send(f) { r -> if (r is TdApi.Error) fail("пароль не подошёл: ${r.message}") }
    }

    fun logout() {
        client?.send(TdApi.LogOut()) {}
        state = St.OFF; me = ""
    }

    private fun fail(m: String) { error = m; state = St.ERROR }

    private fun loadMe() {
        client?.send(TdApi.GetMe()) { r -> if (r is TdApi.User) me = listOfNotNull(r.firstName, r.lastName).joinToString(" ").trim() }
    }

    /** Ключ шифрования базы TDLib — создаём один раз, храним в защищённом хранилище Android. */
    private fun dbKey(): ByteArray {
        val sp = EncryptedSharedPreferences.create(app!!, "tg_sec",
            MasterKey.Builder(app!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        sp.getString("k", null)?.let { return android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sp.edit().putString("k", android.util.Base64.encodeToString(k, android.util.Base64.NO_WRAP)).apply()
        return k
    }

    // ——— общий вызов TDLib как suspend ———

    private suspend inline fun <reified T : TdApi.Object> call(f: TdApi.Function<*>): T? {
        val c = client ?: return null
        return withTimeoutOrNull(25_000) {
            suspendCancellableCoroutine { cont ->
                c.send(f) { r -> cont.resume(r as? T) }
            }
        }
    }

    // ——— чтение (без отметки «прочитано») ———

    private suspend fun chatTitle(id: Long): String = call<TdApi.Chat>(TdApi.GetChat(id))?.title ?: "чат"

    private suspend fun senderName(s: TdApi.MessageSender?): String = when (s) {
        is TdApi.MessageSenderUser -> call<TdApi.User>(TdApi.GetUser(s.userId))?.let {
            listOfNotNull(it.firstName, it.lastName).joinToString(" ").trim().ifBlank { "кто-то" }
        } ?: "кто-то"
        is TdApi.MessageSenderChat -> chatTitle(s.chatId)
        else -> ""
    }

    private fun contentText(c: TdApi.MessageContent?): String = when (c) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> "[фото] ${c.caption.text}".trim()
        is TdApi.MessageVideo -> "[видео] ${c.caption.text}".trim()
        is TdApi.MessageVoiceNote -> "[голосовое]"
        is TdApi.MessageDocument -> "[файл] ${c.document.fileName}"
        is TdApi.MessageSticker -> "[стикер ${c.sticker.emoji}]"
        is TdApi.MessageAnimation -> "[гиф]"
        is TdApi.MessageAudio -> "[аудио]"
        is TdApi.MessageCall -> "[звонок]"
        else -> "[${c?.javaClass?.simpleName?.removePrefix("Message") ?: "сообщение"}]"
    }

    private fun hhmm(ts: Int) = SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(ts * 1000L))

    /** Обзор чатов: кто писал, сколько непрочитанных. onlyUnread — только с непрочитанными. */
    suspend fun overview(limit: Int = 20, onlyUnread: Boolean): String {
        if (state != St.READY) return "Telegram не подключён"
        call<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), 50))
        val chats = call<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), 50))?.chatIds?.toList().orEmpty()
        val out = StringBuilder()
        var n = 0
        for (id in chats) {
            if (id == SERVICE_CHAT) continue
            val chat = call<TdApi.Chat>(TdApi.GetChat(id)) ?: continue
            if (onlyUnread && chat.unreadCount == 0) continue
            val last = chat.lastMessage
            val who = if (last != null && !last.isOutgoing) senderName(last.senderId) else if (last?.isOutgoing == true) "ты" else ""
            out.append("• ${chat.title}")
            if (chat.unreadCount > 0) out.append(" (${chat.unreadCount} новых)")
            if (last != null) out.append(": ${if (who.isNotBlank()) "$who — " else ""}${contentText(last.content).take(120)}")
            out.append('\n')
            if (++n >= limit) break
        }
        return if (out.isBlank()) (if (onlyUnread) "Непрочитанного нет" else "Чатов нет") else out.toString().trim()
    }

    /** Найти чат по имени и прочитать последние сообщения. */
    suspend fun readChat(query: String, count: Int = 20): String {
        if (state != St.READY) return "Telegram не подключён"
        val found = call<TdApi.Chats>(TdApi.SearchChats(query, null, 1))?.chatIds?.firstOrNull()
            ?: call<TdApi.Chats>(TdApi.SearchChatsOnServer(query, null, 1))?.chatIds?.firstOrNull()
            ?: return "не нашёл чат «$query»"
        val title = chatTitle(found)
        val hist = call<TdApi.Messages>(TdApi.GetChatHistory(found, 0, 0, count.coerceIn(1, 50), false))?.messages?.toList().orEmpty()
        if (hist.isEmpty()) return "в «$title» пусто или не удалось прочитать"
        val sb = StringBuilder("Чат «$title»:")
        for (m in hist.reversed()) {
            val who = if (m.isOutgoing) "ты" else senderName(m.senderId)
            sb.append("\n[${hhmm(m.date)}] $who: ${contentText(m.content).replace('\n', ' ').take(300)}")
        }
        return sb.toString()
    }

    /** Поиск по всем перепискам. */
    suspend fun search(query: String, count: Int = 15): String {
        if (state != St.READY) return "Telegram не подключён"
        val f = TdApi.SearchMessages().apply {
            this.query = query; this.limit = count.coerceIn(1, 40); chatList = TdApi.ChatListMain(); offset = ""
        }
        val res = call<TdApi.FoundMessages>(f)?.messages?.toList().orEmpty()
        if (res.isEmpty()) return "по «$query» ничего не нашёл"
        val out = StringBuilder()
        for (m in res.take(count)) {
            val who = if (m.isOutgoing) "ты" else senderName(m.senderId)
            out.append("• [${hhmm(m.date)}] ${chatTitle(m.chatId)} / $who: ${contentText(m.content).replace('\n', ' ').take(160)}\n")
        }
        return out.toString().trim()
    }

    // ——— отправка (только по просьбе Алима, с подтверждением на уровне инструмента) ———

    /** Вернёт id чата по имени — чтобы показать Алиму, кому именно уйдёт. */
    suspend fun findChat(query: String): Pair<Long, String>? {
        val id = call<TdApi.Chats>(TdApi.SearchChats(query, null, 1))?.chatIds?.firstOrNull()
            ?: call<TdApi.Chats>(TdApi.SearchChatsOnServer(query, null, 1))?.chatIds?.firstOrNull() ?: return null
        return id to chatTitle(id)
    }

    suspend fun sendMessage(chatId: Long, text: String): String {
        val content = TdApi.InputMessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
        val f = TdApi.SendMessage().apply { this.chatId = chatId; inputMessageContent = content }
        val r = call<TdApi.Message>(f)
        return if (r != null) "отправил в «${chatTitle(chatId)}»" else "не получилось отправить"
    }

    // ——— подписка на канал (по просьбе Алима, с подтверждением на уровне инструмента) ———

    suspend fun join(username: String): String {
        if (state != St.READY) return "Telegram не подключён"
        val name = username.trim().removePrefix("@").substringAfterLast("/")
        val chat = call<TdApi.Chat>(TdApi.SearchPublicChat(name)) ?: return "не нашёл @$name"
        val r = call<TdApi.Ok>(TdApi.JoinChat(chat.id))
        return if (r != null) "подписался на «${chat.title}»" else "не получилось подписаться на «${chat.title}»"
    }

    // ——— медиа по запросу: скачать фото из чата/паблика и показать ———

    suspend fun fetchFile(fileId: Int): String? = download(fileId)

    private suspend fun download(fileId: Int): String? {
        val f = call<TdApi.File>(TdApi.DownloadFile(fileId, 1, 0, 0, true)) ?: return null
        return f.local?.path?.takeIf { it.isNotBlank() && f.local.isDownloadingCompleted }
    }

    private fun bestPhoto(p: TdApi.Photo?): Int? = p?.sizes?.maxByOrNull { it.width * it.height }?.photo?.id

    /** Последние медиа в чате/паблике: подписи + локальные пути к скачанным фото (включая обложки видео). */
    suspend fun chatMedia(query: String, count: Int = 12): Pair<String, List<String>> {
        if (state != St.READY) return "Telegram не подключён" to emptyList()
        val id = call<TdApi.Chats>(TdApi.SearchChats(query, null, 1))?.chatIds?.firstOrNull()
            ?: call<TdApi.Chats>(TdApi.SearchChatsOnServer(query, null, 1))?.chatIds?.firstOrNull()
            ?: return "не нашёл чат «$query»" to emptyList()
        val title = chatTitle(id)
        val hist = call<TdApi.Messages>(TdApi.GetChatHistory(id, 0, 0, 50, false))?.messages?.toList().orEmpty()
        val paths = ArrayList<String>()
        val caps = StringBuilder()
        for (m in hist) {
            if (paths.size >= count) break
            val c = m.content
            val coverId = when (c) {
                is TdApi.MessagePhoto -> bestPhoto(c.photo)
                is TdApi.MessageVideo -> bestPhoto(c.cover) ?: c.video?.thumbnail?.file?.id
                else -> null
            } ?: continue
            val vidId = (c as? TdApi.MessageVideo)?.video?.video?.id
            download(coverId)?.let { path ->
                // обложка + id видеофайла (качаем по тапу, чтобы не тянуть тяжёлое зря)
                paths += if (vidId != null) "$path\tvid=$vidId" else path
                val cap = when (c) { is TdApi.MessagePhoto -> c.caption.text; is TdApi.MessageVideo -> "[видео] ${c.caption.text}"; else -> "" }.trim()
                if (cap.isNotBlank()) caps.append("• ").append(cap.take(120)).append('\n')
            }
        }
        val head = if (paths.isEmpty()) "в «$title» свежих фото не нашёл" else "Медиа из «$title» (${paths.size})" + (if (caps.isNotBlank()) ":\n$caps" else "")
        return head.trim() to paths
    }

    /** При открытии приложения: кратко показать новое в Telegram (вместо постоянных пушей). */
    suspend fun openSummary(): String? {
        if (state != St.READY) return null
        val s = overview(limit = 8, onlyUnread = true)
        return s.takeIf { it.isNotBlank() && it != "Непрочитанного нет" && it != "Telegram не подключён" }
    }
}

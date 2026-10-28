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
        // ответ собеседника по поручению: только входящие, не служебный чат
        if (u is TdApi.UpdateNewMessage) {
            val m = u.message
            if (!m.isOutgoing && m.chatId != SERVICE_CHAT && m.chatId > 0) app?.let { ctx -> runCatching { Deleg.onIncoming(ctx, "tg", m.chatId.toString(), contentText(m.content)) } }
        }
    }

    /** Текст в чат (для поручений). */
    suspend fun sendText(chatId: Long, text: String): Boolean {
        if (state != St.READY) return false
        val content = TdApi.InputMessageText().apply { this.text = TdApi.FormattedText(text, emptyArray()) }
        return call<TdApi.Message>(TdApi.SendMessage().apply { this.chatId = chatId; inputMessageContent = content }) != null
    }

    /** Фото/файлы альбомом (до 10). */
    suspend fun sendFiles(chatId: Long, paths: List<String>): Boolean {
        if (state != St.READY || paths.isEmpty()) return false
        val contents: Array<TdApi.InputMessageContent> = paths.take(10).map { p ->
            if (p.endsWith(".jpg") || p.endsWith(".png"))
                TdApi.InputMessagePhoto().apply { photo = TdApi.InputPhoto().apply { this.photo = TdApi.InputFileLocal(p); addedStickerFileIds = IntArray(0) } }
            else TdApi.InputMessageDocument().apply { document = TdApi.InputDocument().apply { this.document = TdApi.InputFileLocal(p) } }
        }.toTypedArray()
        return if (contents.size == 1) call<TdApi.Message>(TdApi.SendMessage().apply { this.chatId = chatId; inputMessageContent = contents[0] }) != null
            else call<TdApi.Messages>(TdApi.SendMessageAlbum().apply { this.chatId = chatId; inputMessageContents = contents }) != null
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

    /** Границы суток по местному времени телефона: "today"/"сегодня", "yesterday"/"вчера" или "ГГГГ-ММ-ДД". null — не фильтровать. */
    private fun dayBounds(spec: String): Pair<Long, Long>? {
        val s = spec.trim().lowercase()
        if (s.isBlank()) return null
        val c = java.util.Calendar.getInstance()   // местный часовой пояс телефона
        when (s) {
            "today", "сегодня" -> {}
            "yesterday", "вчера" -> c.add(java.util.Calendar.DAY_OF_YEAR, -1)
            else -> runCatching { c.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)!! }.getOrElse { return null }
        }
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        val start = c.timeInMillis
        return start to (start + 86_400_000L)
    }

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
    suspend fun readChat(query: String, count: Int = 20, day: String = ""): String {
        if (state != St.READY) return "Telegram не подключён"
        val found = call<TdApi.Chats>(TdApi.SearchChats(query, null, 1))?.chatIds?.firstOrNull()
            ?: call<TdApi.Chats>(TdApi.SearchChatsOnServer(query, null, 1))?.chatIds?.firstOrNull()
            ?: return "не нашёл чат «$query»"
        val title = chatTitle(found)
        val bounds = dayBounds(day)
        val limit = if (bounds != null) 80 else count.coerceIn(1, 50)
        var hist = call<TdApi.Messages>(TdApi.GetChatHistory(found, 0, 0, limit, false))?.messages?.toList().orEmpty()
        if (bounds != null) hist = hist.filter { it.date * 1000L in bounds.first until bounds.second }
        if (hist.isEmpty()) return if (bounds != null) "в «$title» за эту дату ничего не нашёл" else "в «$title» пусто или не удалось прочитать"
        val sb = StringBuilder("Чат «$title»${if (bounds != null) " ($day)" else ""}:")
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

    suspend fun fetchFile(fileId: Int): String? {
        val c = client ?: return null
        // видео бывают тяжёлые — ждём дольше, чем обычный запрос
        return withTimeoutOrNull(180_000) {
            suspendCancellableCoroutine { cont ->
                c.send(TdApi.DownloadFile(fileId, 32, 0, 0, true)) { r ->
                    cont.resume((r as? TdApi.File)?.local?.path?.takeIf { it.isNotBlank() && (r as TdApi.File).local.isDownloadingCompleted })
                }
            }
        }
    }

    private suspend fun download(fileId: Int): String? {
        val f = call<TdApi.File>(TdApi.DownloadFile(fileId, 1, 0, 0, true)) ?: return null
        return f.local?.path?.takeIf { it.isNotBlank() && f.local.isDownloadingCompleted }
    }

    private fun bestPhoto(p: TdApi.Photo?): Int? = p?.sizes?.maxByOrNull { it.width * it.height }?.photo?.id

    /** Последние медиа в чате/паблике: подписи + локальные пути к скачанным фото (включая обложки видео). */
    /** kind: "video" — только ролики, "photo" — только фото, "all"/прочее — и то и другое. */
    suspend fun chatMedia(query: String, count: Int = 12, day: String = "", kind: String = "all"): Pair<String, List<String>> {
        if (state != St.READY) return "Telegram не подключён" to emptyList()
        val id = call<TdApi.Chats>(TdApi.SearchChats(query, null, 1))?.chatIds?.firstOrNull()
            ?: call<TdApi.Chats>(TdApi.SearchChatsOnServer(query, null, 1))?.chatIds?.firstOrNull()
            ?: return "не нашёл чат «$query»" to emptyList()
        val title = chatTitle(id)
        val bounds = dayBounds(day)
        val onlyVideo = kind.startsWith("vid") || kind.startsWith("вид")
        val onlyPhoto = kind.startsWith("ph") || kind.startsWith("фот")
        var hist = call<TdApi.Messages>(TdApi.GetChatHistory(id, 0, 0, if (bounds != null) 100 else 60, false))?.messages?.toList().orEmpty()
        if (bounds != null) hist = hist.filter { it.date * 1000L in bounds.first until bounds.second }
        val paths = ArrayList<String>()
        val caps = StringBuilder()
        var nVid = 0; var nPhoto = 0; var skippedPhoto = 0; var skippedVid = 0
        for (m in hist) {
            if (paths.size >= count) break
            val c = m.content
            val isVideo = c is TdApi.MessageVideo
            val isPhoto = c is TdApi.MessagePhoto
            if (!isVideo && !isPhoto) continue
            if (onlyVideo && !isVideo) { skippedPhoto++; continue }
            if (onlyPhoto && !isPhoto) { skippedVid++; continue }
            val coverId = when (c) {
                is TdApi.MessagePhoto -> bestPhoto(c.photo)
                is TdApi.MessageVideo -> bestPhoto(c.cover) ?: c.video?.thumbnail?.file?.id
                else -> null
            } ?: continue
            val vidId = (c as? TdApi.MessageVideo)?.video?.video?.id
            download(coverId)?.let { path ->
                paths += if (vidId != null) "$path\tvid=$vidId" else path
                if (isVideo) nVid++ else nPhoto++
                val cap = when (c) { is TdApi.MessagePhoto -> "[фото] ${c.caption.text}"; is TdApi.MessageVideo -> "[видео] ${c.caption.text}"; else -> "" }.trim()
                if (cap != "[фото]" && cap != "[видео]") caps.append("• ").append(cap.take(120)).append('\n')
            }
        }
        val head = when {
            paths.isNotEmpty() -> "Из «$title»: " + listOfNotNull(if (nVid > 0) "видео $nVid" else null, if (nPhoto > 0) "фото $nPhoto" else null).joinToString(", ") + (if (caps.isNotBlank()) ":\n$caps" else "")
            onlyVideo && skippedPhoto > 0 -> "видео${if (bounds != null) " за этот день" else ""} в «$title» нет, только фото ($skippedPhoto). Показать фото?"
            onlyPhoto && skippedVid > 0 -> "фото${if (bounds != null) " за этот день" else ""} в «$title» нет, только видео ($skippedVid). Показать видео?"
            else -> "в «$title»${if (bounds != null) " за этот день" else ""} медиа не нашёл"
        }
        return head.trim() to paths
    }

    /** При открытии приложения: кратко показать новое в Telegram (вместо постоянных пушей). */
    /**
     * Новые ЛИЧНЫЕ сообщения, которые ещё не показывались в Пилоте. Группы и каналы не трогаем.
     * Помним последний показанный id по каждому чату; после переустановки берём только свежее (за 2 суток), без простыни старья.
     */
    private val privMutex = kotlinx.coroutines.sync.Mutex()
    suspend fun newPrivate(): String? = privMutex.withLockOrNull {
        if (state != St.READY) return@withLockOrNull null
        call<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), 50))
        val ids = call<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), 50))?.chatIds?.toList().orEmpty()
        val shown = runCatching { org.json.JSONObject(Prefs.tgShown) }.getOrDefault(org.json.JSONObject())
        val fresh = System.currentTimeMillis() / 1000 - 2 * 86400
        val out = StringBuilder(); var n = 0
        for (id in ids) {
            if (id == SERVICE_CHAT) continue
            val chat = call<TdApi.Chat>(TdApi.GetChat(id)) ?: continue
            if (chat.type !is TdApi.ChatTypePrivate) continue
            val last = chat.lastMessage ?: continue
            if (last.isOutgoing || chat.unreadCount == 0) continue
            val seen = shown.optLong(id.toString(), 0L)
            if (last.id <= seen) continue
            if (seen == 0L && last.date < fresh) { shown.put(id.toString(), last.id); continue }
            shown.put(id.toString(), last.id)
            out.append("• ${chat.title}")
            if (chat.unreadCount > 1) out.append(" (${chat.unreadCount})")
            out.append(": ${contentText(last.content).replace('\n', ' ').take(140)}\n")
            if (++n >= 10) break
        }
        Prefs.tgShown = shown.toString()
        out.toString().trim().ifBlank { null }
    }

    private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLockOrNull(block: () -> T?): T? {
        if (!tryLock()) return null
        try { return block() } finally { unlock() }
    }

    suspend fun openSummary(): String? {
        if (state != St.READY) return null
        val s = overview(limit = 8, onlyUnread = true)
        return s.takeIf { it.isNotBlank() && it != "Непрочитанного нет" && it != "Telegram не подключён" }
    }
}

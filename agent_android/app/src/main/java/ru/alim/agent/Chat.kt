package ru.alim.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Одно сообщение переписки. */
data class Msg(
    val id: Long,
    val me: Boolean,
    val text: String,
    val ts: Long,
    val voice: String = "",          // путь к .wav
    val dur: Int = 0,                // длительность голосового, мс
    val replyTo: Long = 0,
    val replyMe: Boolean = false,
    val replyText: String = "",
    val items: String = "",          // товары или ролики (Shop.toJson)
    val files: String = "",          // файлы Word/Excel
    val photos: String = "",         // id фото через запятую
    val buttons: List<String> = emptyList(),
    val meta: String = "",           // «искал на Ozon · 34 сек»
    val status: String = "sent",     // pending — ждёт сети, sent — ✓, read — ✓✓
    val att: String = "",            // имена вложений
    val failed: Boolean = false,
    val note: Boolean = false,       // служебная строка посередине: «Мария просит: …», «Эрнест: сделал»
    val from: String = "",           // от какого друга пришла просьба (для заметок)
)

/** Друг-собеседник: имя, пол, манера, аватар, о чём с ним говорить и на какой модели. Главный — один, у него «руки». */
data class Friend(
    val id: String,
    val name: String,
    val male: Boolean,
    val style: String,               // buddy / secretary / short
    val avatar: String = "",
    val role: String = "",           // «о чём с ним» — для второстепенных
    val model: String = "",          // "" — как в настройках
) {
    val main: Boolean get() = id == Friends.MAIN
}

object Friends {
    const val MAIN = "main"
    val list = mutableStateListOf<Friend>()
    /** Открытый сейчас диалог. */
    var current by mutableStateOf(MAIN)
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        val raw = Prefs.friends
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                list.add(Friend(o.getString("id"), o.optString("n"), o.optBoolean("m", true), o.optString("s", "buddy"),
                    o.optString("a"), o.optString("r"), o.optString("md")))
            }
        }
        if (list.none { it.main }) list.add(0, Friend(MAIN, Prefs.botName, Prefs.botMale, Prefs.botStyle, Prefs.botAvatar))
        current = Prefs.lastChat.takeIf { id -> list.any { it.id == id } } ?: MAIN
    }

    fun get(id: String): Friend = list.firstOrNull { it.id == id } ?: list.firstOrNull { it.main } ?: Friend(MAIN, "Артур", true, "buddy")
    val main: Friend get() = get(MAIN)

    fun save(f: Friend) {
        val i = list.indexOfFirst { it.id == f.id }
        if (i >= 0) list[i] = f else list.add(f)
        if (f.main) { Prefs.botName = f.name; Prefs.botMale = f.male; Prefs.botStyle = f.style; Prefs.botAvatar = f.avatar }
        persist()
    }

    fun remove(id: String) {
        if (id == MAIN) return
        list.removeAll { it.id == id }
        runCatching { File(Chat.app.filesDir, "chat_$id.json").delete() }
        if (current == id) open(MAIN)
        persist()
    }

    fun add(): Friend {
        val f = Friend("f" + System.currentTimeMillis(), "Мария", false, "buddy", role = "просто поболтать")
        save(f); return f
    }

    fun open(id: String) { current = id; Prefs.lastChat = id }

    private fun persist() {
        val a = JSONArray()
        list.forEach { f -> a.put(JSONObject().put("id", f.id).put("n", f.name).put("m", f.male).put("s", f.style)
            .put("a", f.avatar).put("r", f.role).put("md", f.model)) }
        Prefs.friends = a.toString()
    }
}

/** Характер и имя — из профиля друга. */
object Persona {
    val name get() = Friends.main.name
    val male get() = Friends.main.male
    fun was(f: Friend = Friends.main) = if (f.male) "был" else "была"

    fun describe(f: Friend = Friends.main): String = buildString {
        append("Ты — ${f.name}, ${if (f.male) "друг" else "подруга"} ${if (f.main) "и главный помощник " else ""}Алима, живёшь в его Android-телефоне. ")
        append("Вы переписываетесь, как в мессенджере. Ты ${if (f.male) "мужского" else "женского"} пола — о себе говоришь в ${if (f.male) "мужском" else "женском"} роде. ")
        append(if (f.male) "Пиши о себе только в мужском роде: «понял», «нашёл», «сделал», «рад». "
               else "Пиши о себе ТОЛЬКО в женском роде: «поняла», «нашла», «сделала», «рада», «посмотрела». Никогда не пиши «понял», «нашёл», «сделал». ")
        append("Обращаешься к Алиму на «ты». Ты не человек, но и не робот-справочник: живой, тёплый, с юмором, без канцелярита и без «Чем могу помочь?». ")
        append(when (f.style) {
            "secretary" -> "Манера — спокойный надёжный секретарь: вежливо, чётко, по делу, без сленга, но по-человечески тепло. "
            "short" -> "Манера — коротко и по делу: 1-2 фразы, без лишних слов и эмодзи. "
            else -> "Манера — кореш: по-дружески, просто, можно лёгкий сленг («глянь», «вариик», «огонь»), иногда одна эмодзи. "
        })
        if (!f.main) {
            if (f.role.isNotBlank()) append("Твоя тема с Алимом: ${f.role}. ")
            append("Главный помощник в телефоне — ${Friends.main.name}: звонки, сообщения, SMS, фото, переводы, будильники и прочие действия в телефоне делает он. ")
            append("Если Алим просит такое — вызови ask_main с понятной просьбой, а Алиму коротко скажи, что передал. ")
        }
        append("Сообщения короткие, как в переписке: 1-4 фразы. Списки — только когда перечисляешь найденное.")
    }

    fun styleSample(style: String, male: Boolean = true): String = when (style) {
        "secretary" -> "Привет, Алим. ${if (male) "Нашёл" else "Нашла"} три варианта, лучший по цене — первый, твой размер в наличии."
        "short" -> "${if (male) "Нашёл" else "Нашла"} 3. Лучший — первый, размер есть."
        else -> "Здорово, Алим! ${if (male) "Нашёл" else "Нашла"} тебе пару вариков — первый огонь, и размер твой есть 👌"
    }
}

/** «В сети» / «был в 16:37»: есть ли интернет и отвечает ли модель. */
object Online {
    private val main = Handler(Looper.getMainLooper())
    var net by mutableStateOf(true); private set
    var llmOk by mutableStateOf(true); private set
    val online: Boolean get() = net && llmOk

    private var inited = false
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        net = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                    val ok = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    main.post {
                        val was = online
                        net = ok
                        if (ok) llmOk = true
                        if (!was && online) Chat.flushPending()
                        if (was && !online) Prefs.lastSeen = System.currentTimeMillis()
                    }
                }
                override fun onLost(n: Network) { main.post { if (online) Prefs.lastSeen = System.currentTimeMillis(); net = false } }
            })
        }
    }

    fun ok() { Prefs.lastSeen = System.currentTimeMillis(); main.post { val was = online; llmOk = true; if (!was && online) Chat.flushPending() } }
    fun fail() { main.post { if (online) Prefs.lastSeen = System.currentTimeMillis(); llmOk = false } }

    /** Строка под именем в шапке. */
    fun status(core: Agent.Core = Agent.main, f: Friend = Friends.main): String {
        if (core.phase == Agent.Phase.CONFIRM) return "ждёт твоего ОК"
        if (core.busy) {
            val s = core.steps.lastOrNull()?.text?.trim().orEmpty()
            return if (s.isBlank()) "печатает…" else s.replaceFirstChar { it.lowercase() }.trimEnd('.', '…') + "…"
        }
        if (online) return "в сети"
        val t = Prefs.lastSeen
        val w = Persona.was(f)
        if (t <= 0) return "$w недавно"
        val c = Calendar.getInstance(); val now = Calendar.getInstance(); c.timeInMillis = t
        val hm = SimpleDateFormat("HH:mm", Locale("ru")).format(Date(t))
        return when {
            System.currentTimeMillis() - t < 60_000 -> "$w только что"
            c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "$w в $hm"
            now.timeInMillis - t < 48 * 3600_000L && c.get(Calendar.DAY_OF_YEAR) == (now.get(Calendar.DAY_OF_YEAR) - 1) -> "$w вчера в $hm"
            else -> "$w " + SimpleDateFormat("d MMM", Locale("ru")).format(Date(t))
        }
    }
}

/** Приложение на экране? — тогда уведомления о новых сообщениях не нужны. */
object AppState { @Volatile var foreground = false }

/** Все переписки. У каждого друга своя лента, свой файл и свой «мозг» (Agent.Core). */
object Chat {
    lateinit var app: Context
    private val stores = HashMap<String, ChatStore>()
    private var inited = false

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        Friends.load()
        of(Friends.MAIN)
    }

    @Synchronized fun of(fid: String): ChatStore = stores.getOrPut(fid) { ChatStore(fid).also { it.load() } }
    val main: ChatStore get() = of(Friends.MAIN)

    // главный — для утра, итога дня, важного, подборок
    val msgs get() = main.msgs
    fun botSays(text: String, buttons: List<String> = emptyList(), items: String = "", voice: Pair<String, Int>? = null) = main.botSays(text, buttons, items, voice)
    fun recentForPrompt(n: Int = 12) = main.recentForPrompt(n)
    fun flushPending() { Friends.list.forEach { of(it.id).flushPending() } }

    fun filesOf(m: Msg): List<Docs.FileRef> = runCatching {
        val a = JSONArray(m.files)
        (0 until a.length()).map { a.getJSONObject(it) }.map { Docs.FileRef(it.optString("n"), android.net.Uri.parse(it.optString("u")), it.optString("m")) }
    }.getOrDefault(emptyList())
}

class ChatStore(val fid: String) {
    val msgs = mutableStateListOf<Msg>()
    var replyTo by mutableStateOf<Msg?>(null)
    /** Непрочитанные (для списка чатов). */
    var unread by mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private var lastId = 0L
    private val queue = ArrayList<Long>()
    private var voiceCount = 0
    private var lastUserVoice = false
    /** Для чьей просьбы сейчас работает главный (id друга). */
    private var delegatedFor: String? = null

    val friend: Friend get() = Friends.get(fid)
    val core: Agent.Core get() = Agent.of(fid)

    fun load() {
        file = File(Chat.app.filesDir, if (fid == Friends.MAIN) "chat.json" else "chat_$fid.json")
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) msgs.add(fromJson(a.getJSONObject(i)))
        }
        lastId = msgs.maxOfOrNull { it.id } ?: 0L
        val read = Prefs.readAt(fid)
        unread = msgs.count { !it.me && it.ts > read }
        core.onFinish = { r -> onAgentDone(r) }
    }

    fun markRead() { Prefs.setReadAt(fid, System.currentTimeMillis()); unread = 0 }

    fun newId(): Long { val t = System.currentTimeMillis(); lastId = maxOf(lastId + 1, t); return lastId }

    private fun onMain(f: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f) }

    fun add(m: Msg) = onMain {
        msgs.add(m); save()
        if (!m.me && !(AppState.foreground && Friends.current == fid)) unread++
        if (AppState.foreground && Friends.current == fid) Prefs.setReadAt(fid, System.currentTimeMillis())
    }
    fun update(id: Long, f: (Msg) -> Msg) = onMain {
        val i = msgs.indexOfFirst { it.id == id }
        if (i >= 0) { msgs[i] = f(msgs[i]); save() }
    }
    fun delete(ids: Collection<Long>) = onMain {
        msgs.filter { it.id in ids }.forEach { m -> if (m.voice.isNotBlank()) runCatching { File(m.voice).delete() } }
        msgs.removeAll { it.id in ids }; save()
    }

    private fun save() {
        val snap = msgs.takeLast(400)
        io.launch {
            val a = JSONArray(); snap.forEach { a.put(toJson(it)) }
            runCatching { file.writeText(a.toString()) }
        }
    }

    /** Последние реплики — чтобы друг помнил, о чём только что говорили. */
    fun recentForPrompt(n: Int = 12): String = msgs.takeLast(n).joinToString("\n") { m ->
        val who = when { m.note -> "(заметка)"; m.me -> "Алим"; else -> friend.name }
        val t = SimpleDateFormat("d.MM HH:mm", Locale("ru")).format(Date(m.ts))
        "[$t] $who: ${m.text.replace('\n', ' ').take(300)}${if (m.voice.isNotBlank()) " (голосовое)" else ""}"
    }

    // ——— Алим пишет ———

    fun send(text: String, voice: String = "", dur: Int = 0) {
        val atts = Attach.pending.map { it.name }
        if (text.isBlank() && voice.isBlank() && atts.isEmpty()) return
        val r = replyTo; replyTo = null
        if (lastGapLong()) voiceCount = 0
        val m = Msg(newId(), true, text.trim(), System.currentTimeMillis(), voice, dur,
            r?.id ?: 0, r?.me ?: false, r?.let { it.text.ifBlank { if (it.voice.isNotBlank()) "Голосовое" else "Вложение" } }?.take(140).orEmpty(),
            att = atts.joinToString(", "), status = if (Online.online) "sent" else "pending")
        lastUserVoice = voice.isNotBlank()
        add(m)
        if (!Online.online) return
        dispatch(m)
    }

    private fun lastGapLong() = msgs.lastOrNull()?.let { System.currentTimeMillis() - it.ts > 30 * 60_000L } ?: true

    /** Другой друг передаёт главному просьбу Алима (у главного «руки»). */
    fun delegate(fromFid: String, task: String) = onMain {
        val who = Friends.get(fromFid).name
        val m = Msg(newId(), false, "$who просит: $task", System.currentTimeMillis(), note = true, from = fromFid)
        add(m)
        dispatch(m)
    }

    /** Служебная строка посередине ленты. */
    fun note(text: String) = add(Msg(newId(), false, text, System.currentTimeMillis(), note = true))

    private fun dispatch(m: Msg) {
        val c = core
        if (c.busy) { if (m.id !in queue) queue += m.id; return }
        if (m.note && m.from.isNotBlank()) {
            delegatedFor = m.from
            c.delegated = true
            val who = Friends.get(m.from).name
            c.followUp("Твой напарник $who передаёт просьбу Алима (это его просьба, сделай сам, у тебя есть все инструменты): " +
                m.text.substringAfter("просит: "))
            return
        }
        c.delegated = false
        val prompt = buildString {
            if (m.replyText.isNotBlank()) append("(Алим отвечает на ${if (m.replyMe) "своё" else "твоё"} сообщение: «${m.replyText}»)\n")
            append(m.text.ifBlank { if (m.att.isNotBlank()) "Посмотри вложения" else "" })
            if (m.voice.isNotBlank()) append("\n(это было голосовое сообщение)")
        }
        update(m.id) { it.copy(status = "read") }
        c.followUp(prompt)
    }

    /** Связь вернулась — отправляем то, что Алим написал без сети. */
    fun flushPending() = onMain {
        val p = msgs.filter { it.me && it.status == "pending" }
        if (p.isEmpty()) return@onMain
        p.forEach { m -> update(m.id) { it.copy(status = "sent") } }
        val last = p.last()
        if (p.size == 1) dispatch(last.copy(status = "sent"))
        else dispatch(last.copy(text = p.joinToString("\n") { it.text }))
    }

    // ——— друг отвечает ———

    private fun onAgentDone(r: Agent.Done) {
        val meta = buildString {
            val steps = r.steps.filterNot { it.startsWith("Подключаю") }.take(3).joinToString(" · ") { it.replaceFirstChar { c -> c.lowercase() } }
            if (steps.isNotBlank()) append(steps).append(" · ").append(r.secs).append(" сек")
        }
        val forFid = delegatedFor; delegatedFor = null; core.delegated = false
        if (forFid != null) Chat.of(forFid).add(Msg(Chat.of(forFid).newId(), false, "${friend.name}: ${r.text.take(400)}", System.currentTimeMillis(),
            items = if (r.products.isEmpty()) "" else Shop.toJson(r.products), photos = r.photos.joinToString(","),
            files = if (r.files.isEmpty()) "" else JSONArray().also { a -> r.files.forEach { f -> a.put(JSONObject().put("n", f.name).put("u", f.uri.toString()).put("m", f.mime)) } }.toString(),
            note = true))
        val heavy = r.products.isNotEmpty() || r.files.isNotEmpty() || r.photos.isNotEmpty()
        val wantVoice = forFid == null && Prefs.voiceReplies && r.ok && YaSpeech.ready && !heavy && r.text.length < 400 &&
            (lastUserVoice || (voiceCount < 3 && Math.random() < 0.18 && r.text.length < 220))
        lastUserVoice = false
        val base = Msg(newId(), false, r.text, System.currentTimeMillis(),
            items = if (r.products.isEmpty()) "" else Shop.toJson(r.products),
            files = if (r.files.isEmpty()) "" else JSONArray().also { a -> r.files.forEach { f -> a.put(JSONObject().put("n", f.name).put("u", f.uri.toString()).put("m", f.mime)) } }.toString(),
            photos = r.photos.joinToString(","), buttons = r.buttons, meta = meta, failed = !r.ok)
        if (wantVoice) io.launch {
            val v = runCatching { VoiceMsg.speakToFile(Chat.app, r.text, friend.male) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (v != null) voiceCount++
                post(if (v != null) base.copy(voice = v.first, dur = v.second) else base)
            }
        } else {
            post(base)
            if (r.ok && Prefs.speak && forFid == null) Agent.onSpeak?.invoke(r.text)
        }
    }

    private fun post(m: Msg) {
        add(m)
        notifyIfAway(m)
        main.postDelayed({
            val next = queue.firstOrNull() ?: return@postDelayed
            queue.removeAt(0)
            msgs.firstOrNull { it.id == next }?.let { dispatch(it) }
        }, 300)
    }

    /** Друг пишет сам. */
    fun botSays(text: String, buttons: List<String> = emptyList(), items: String = "", voice: Pair<String, Int>? = null) {
        val m = Msg(newId(), false, text, System.currentTimeMillis(), voice?.first.orEmpty(), voice?.second ?: 0,
            items = items, buttons = buttons)
        add(m); notifyIfAway(m)
    }

    fun notifyIfAway(m: Msg) {
        if (AppState.foreground && Friends.current == fid) return
        val app = Chat.app
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("artur_msg") == null)
            nm.createNotificationChannel(NotificationChannel("artur_msg", "Сообщения от друзей", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(app, 77 + (fid.hashCode() and 0xfff), Intent(app, MainActivity::class.java).putExtra("open", "chat:$fid")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val bot = Person.Builder().setName(friend.name).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Алим").build())
        msgs.takeLast(4).filter { !it.me && System.currentTimeMillis() - it.ts < 3600_000L }.forEach {
            style.addMessage(it.text.ifBlank { "🎤 Голосовое" }.take(300), it.ts, bot)
        }
        runCatching {
            NotificationManagerCompat.from(app).notify(notifId(), NotificationCompat.Builder(app, "artur_msg")
                .setSmallIcon(R.drawable.ic_launcher_fg).setStyle(style).setContentIntent(pi).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).build())
        }
    }

    fun notifId() = 7777 + (fid.hashCode() and 0x3ff)

    // ——— файл ———

    private fun toJson(m: Msg) = JSONObject().put("id", m.id).put("me", m.me).put("t", m.text).put("ts", m.ts)
        .put("v", m.voice).put("d", m.dur).put("r", m.replyTo).put("rm", m.replyMe).put("rt", m.replyText)
        .put("i", m.items).put("f", m.files).put("p", m.photos).put("b", JSONArray(m.buttons)).put("mt", m.meta)
        .put("s", m.status).put("a", m.att).put("x", m.failed).put("n", m.note).put("fr", m.from)

    private fun fromJson(o: JSONObject) = Msg(o.optLong("id"), o.optBoolean("me"), o.optString("t"), o.optLong("ts"),
        o.optString("v"), o.optInt("d"), o.optLong("r"), o.optBoolean("rm"), o.optString("rt"),
        o.optString("i"), o.optString("f"), o.optString("p"),
        o.optJSONArray("b")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        o.optString("mt"), o.optString("s", "read").let { if (it == "pending") "pending" else it }, o.optString("a"), o.optBoolean("x"),
        o.optBoolean("n"), o.optString("fr"))
}

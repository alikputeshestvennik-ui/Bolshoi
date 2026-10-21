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

/** Одно сообщение переписки с Артуром. */
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
)

/** Характер и имя — из профиля. */
object Persona {
    val name get() = Prefs.botName
    val male get() = Prefs.botMale
    fun was() = if (male) "был" else "была"

    fun describe(): String = buildString {
        append("Ты — $name, ${if (male) "друг" else "подруга"} и помощник Алима, живёшь в его Android-телефоне. ")
        append("Вы переписываетесь, как в мессенджере. Ты ${if (male) "мужского" else "женского"} пола — о себе говоришь в ${if (male) "мужском" else "женском"} роде. ")
        append("Обращаешься к Алиму на «ты». Ты не человек, но и не робот-справочник: живой, тёплый, с юмором, без канцелярита и без «Чем могу помочь?». ")
        append(when (Prefs.botStyle) {
            "secretary" -> "Манера — спокойный надёжный секретарь: вежливо, чётко, по делу, без сленга, но по-человечески тепло. "
            "short" -> "Манера — коротко и по делу: 1-2 фразы, без лишних слов и эмодзи. "
            else -> "Манера — кореш: по-дружески, просто, можно лёгкий сленг («глянь», «вариик», «огонь»), иногда одна эмодзи. "
        })
        append("Сообщения короткие, как в переписке: 1-4 фразы. Списки — только когда перечисляешь найденное.")
    }

    fun styleSample(): String = when (Prefs.botStyle) {
        "secretary" -> "Привет, Алим. Нашёл три варианта, лучший по цене — первый, твой размер в наличии."
        "short" -> "Нашёл 3. Лучший — первый, размер есть."
        else -> "Здорово, Алим! Нашёл тебе пару вариков — первый огонь, и размер твой есть 👌"
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
    fun status(): String {
        if (Agent.phase == Agent.Phase.CONFIRM) return "ждёт твоего ОК"
        if (Agent.busy) {
            val s = Agent.steps.lastOrNull()?.text?.trim().orEmpty()
            return if (s.isBlank()) "печатает…" else s.replaceFirstChar { it.lowercase() }.trimEnd('.', '…') + "…"
        }
        if (online) return "в сети"
        val t = Prefs.lastSeen
        val w = Persona.was()
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

/** Переписка: хранится в файле, новые сообщения уходят агенту, его ответы приходят сюда. */
object Chat {
    val msgs = mutableStateListOf<Msg>()
    var replyTo by mutableStateOf<Msg?>(null)
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private lateinit var app: Context
    private var lastId = 0L
    /** Сообщения Алима, которые ждут, пока агент освободится. */
    private val queue = ArrayList<Long>()
    /** Голосовых от Артура в текущем разговоре (не больше 3). */
    private var voiceCount = 0
    private var lastUserVoice = false

    fun init(ctx: Context) {
        if (file != null) return
        app = ctx.applicationContext
        file = File(app.filesDir, "chat.json")
        runCatching {
            val a = JSONArray(file!!.readText())
            for (i in 0 until a.length()) msgs.add(fromJson(a.getJSONObject(i)))
        }
        lastId = msgs.maxOfOrNull { it.id } ?: 0L
        Agent.onFinish = { r -> onAgentDone(r) }
    }

    fun newId(): Long { val t = System.currentTimeMillis(); lastId = maxOf(lastId + 1, t); return lastId }

    private fun onMain(f: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f) }

    fun add(m: Msg) = onMain { msgs.add(m); save() }
    fun update(id: Long, f: (Msg) -> Msg) = onMain {
        val i = msgs.indexOfFirst { it.id == id }
        if (i >= 0) { msgs[i] = f(msgs[i]); save() }
    }
    fun clear() = onMain { msgs.clear(); save() }
    fun delete(ids: Collection<Long>) = onMain {
        val gone = msgs.filter { it.id in ids }
        gone.forEach { m -> if (m.voice.isNotBlank()) runCatching { java.io.File(m.voice).delete() } }
        msgs.removeAll { it.id in ids }; save()
    }

    private fun save() {
        val snap = msgs.takeLast(400)
        io.launch {
            val a = JSONArray(); snap.forEach { a.put(toJson(it)) }
            runCatching { file?.writeText(a.toString()) }
        }
    }

    /** Последние реплики — чтобы Артур помнил, о чём только что говорили. */
    fun recentForPrompt(n: Int = 12): String = msgs.takeLast(n).joinToString("\n") { m ->
        val who = if (m.me) "Алим" else Persona.name
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

    private fun dispatch(m: Msg) {
        if (Agent.busy) { if (m.id !in queue) queue += m.id; return }
        val prompt = buildString {
            if (m.replyText.isNotBlank()) append("(Алим отвечает на ${if (m.replyMe) "своё" else "твоё"} сообщение: «${m.replyText}»)\n")
            append(m.text.ifBlank { if (m.att.isNotBlank()) "Посмотри вложения" else "" })
            if (m.voice.isNotBlank()) append("\n(это было голосовое сообщение)")
        }
        update(m.id) { it.copy(status = "read") }
        Agent.followUp(prompt)
    }

    /** Связь вернулась — отправляем то, что Алим написал без сети. */
    fun flushPending() = onMain {
        val p = msgs.filter { it.me && it.status == "pending" }
        if (p.isEmpty()) return@onMain
        p.forEach { m -> update(m.id) { it.copy(status = "sent") } }
        // все накопленное — одной просьбой: так агент видит картину целиком
        val last = p.last()
        if (p.size == 1) dispatch(last.copy(status = "sent"))
        else dispatch(last.copy(text = p.joinToString("\n") { it.text }))
    }

    // ——— Артур отвечает ———

    private fun onAgentDone(r: Agent.Done) {
        val meta = buildString {
            val steps = r.steps.filterNot { it.startsWith("Подключаю") }.take(3).joinToString(" · ") { it.replaceFirstChar { c -> c.lowercase() } }
            if (steps.isNotBlank()) append(steps).append(" · ").append(r.secs).append(" сек")
        }
        val heavy = r.products.isNotEmpty() || r.files.isNotEmpty() || r.photos.isNotEmpty()
        val wantVoice = Prefs.voiceReplies && r.ok && YaSpeech.ready && !heavy && r.text.length < 400 &&
            (lastUserVoice || (voiceCount < 3 && Math.random() < 0.18 && r.text.length < 220))
        lastUserVoice = false
        val base = Msg(newId(), false, r.text, System.currentTimeMillis(),
            items = if (r.products.isEmpty()) "" else Shop.toJson(r.products),
            files = if (r.files.isEmpty()) "" else JSONArray().also { a -> r.files.forEach { f -> a.put(JSONObject().put("n", f.name).put("u", f.uri.toString()).put("m", f.mime)) } }.toString(),
            photos = r.photos.joinToString(","), buttons = r.buttons, meta = meta, failed = !r.ok)
        if (wantVoice) io.launch {
            val v = runCatching { VoiceMsg.speakToFile(app, r.text) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (v != null) voiceCount++
                post(if (v != null) base.copy(voice = v.first, dur = v.second) else base)
            }
        } else {
            post(base)
            if (r.ok && Prefs.speak) Agent.onSpeak?.invoke(r.text)
        }
    }

    private fun post(m: Msg) {
        add(m)
        notifyIfAway(m)
        // пока Артур был занят, Алим мог написать ещё
        main.postDelayed({
            val next = queue.firstOrNull() ?: return@postDelayed
            queue.removeAt(0)
            msgs.firstOrNull { it.id == next }?.let { dispatch(it) }
        }, 300)
    }

    /** Артур пишет сам (утро, итог дня, «как дела?», подборка). */
    fun botSays(text: String, buttons: List<String> = emptyList(), items: String = "", voice: Pair<String, Int>? = null) {
        val m = Msg(newId(), false, text, System.currentTimeMillis(), voice?.first.orEmpty(), voice?.second ?: 0,
            items = items, buttons = buttons)
        add(m); notifyIfAway(m)
    }

    fun notifyIfAway(m: Msg) {
        if (AppState.foreground) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("artur_msg") == null)
            nm.createNotificationChannel(NotificationChannel("artur_msg", "Сообщения от ${Persona.name}", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(app, 77, Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val bot = Person.Builder().setName(Persona.name).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Алим").build())
        msgs.takeLast(4).filter { !it.me && System.currentTimeMillis() - it.ts < 3600_000L }.forEach {
            style.addMessage(it.text.ifBlank { "🎤 Голосовое" }.take(300), it.ts, bot)
        }
        runCatching {
            NotificationManagerCompat.from(app).notify(7777, NotificationCompat.Builder(app, "artur_msg")
                .setSmallIcon(R.drawable.ic_launcher_fg).setStyle(style).setContentIntent(pi).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE).build())
        }
    }

    // ——— файл ———

    private fun toJson(m: Msg) = JSONObject().put("id", m.id).put("me", m.me).put("t", m.text).put("ts", m.ts)
        .put("v", m.voice).put("d", m.dur).put("r", m.replyTo).put("rm", m.replyMe).put("rt", m.replyText)
        .put("i", m.items).put("f", m.files).put("p", m.photos).put("b", JSONArray(m.buttons)).put("mt", m.meta)
        .put("s", m.status).put("a", m.att).put("x", m.failed)

    private fun fromJson(o: JSONObject) = Msg(o.optLong("id"), o.optBoolean("me"), o.optString("t"), o.optLong("ts"),
        o.optString("v"), o.optInt("d"), o.optLong("r"), o.optBoolean("rm"), o.optString("rt"),
        o.optString("i"), o.optString("f"), o.optString("p"),
        o.optJSONArray("b")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        o.optString("mt"), o.optString("s", "read").let { if (it == "pending") "pending" else it }, o.optString("a"), o.optBoolean("x"))

    fun filesOf(m: Msg): List<Docs.FileRef> = runCatching {
        val a = JSONArray(m.files)
        (0 until a.length()).map { a.getJSONObject(it) }.map { Docs.FileRef(it.optString("n"), android.net.Uri.parse(it.optString("u")), it.optString("m")) }
    }.getOrDefault(emptyList())
}

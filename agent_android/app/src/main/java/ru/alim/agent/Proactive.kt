package ru.alim.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Артур пишет первым: доброе утро, «как дела?», смешной ролик. Всё — с оглядкой на «не беспокоить» и частоту. */
object Proactive {
    private val lock = Mutex()

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun json(t: String): JSONObject {
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        return runCatching { JSONObject(t.substring(a, b + 1)) }.getOrDefault(JSONObject())
    }

    fun quiet(h: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean {
        val f = Prefs.quietFrom; val t = Prefs.quietTo
        return if (f == t) false else if (f > t) h >= f || h < t else h in f until t
    }

    suspend fun tick(ctx: Context): String = lock.withLock {
        Prefs.init(ctx); Chat.init(ctx)
        if (!Online.online || Agent.busy) return "—"
        val c = Calendar.getInstance(); val h = c.get(Calendar.HOUR_OF_DAY)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
        if (Prefs.initMorning && h >= Prefs.morningHour && h < 12 && Prefs.lastMorning != today && !quiet(h)) {
            Prefs.lastMorning = today
            return runCatching { morning(ctx); "утро" }.getOrElse { "утро: ${it.message?.take(60)}" }
        }
        if (Prefs.chatty > 0 && !quiet(h)) {
            val now = System.currentTimeMillis()
            val lastMsg = Chat.msgs.lastOrNull()?.ts ?: 0L
            val p = when (Prefs.chatty) { 1 -> 0.01; 2 -> 0.03; else -> 0.06 }
            val gap = when (Prefs.chatty) { 1 -> 8; 2 -> 4; else -> 2 } * 3600_000L
            if (now - lastMsg > 2 * 3600_000L && now - Prefs.lastChatty > gap && Math.random() < p) {
                Prefs.lastChatty = now
                return runCatching { chat(ctx); "поболтать" }.getOrElse { "поболтать: ${it.message?.take(60)}" }
            }
        }
        "—"
    }

    /**
     * Остальные друзья сами не болтают. Пишут, только если разговор оборвался и забыт на несколько часов,
     * или главный нашёл в уведомлениях что-то, связанное с их разговором.
     */
    /** Служебный шум, который друзьям пересказывать не нужно. */
    private val NOISE = Regex("загружен|скачан|download|github|сборк|\\.apk|\\.zip|claude|rustore|обновлен|update|vpn|впн|прошла проверку|не прошла проверку", RegexOption.IGNORE_CASE)

    private suspend fun friends(ctx: Context) {
        if (quiet()) return
        val now = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        for (f in Friends.list.filter { !it.main }) {
            val c = Chat.of(f.id)
            if (c.core.busy) continue
            val last = c.msgs.lastOrNull { !it.note } ?: continue
            val lastMine = c.msgs.lastOrNull { it.me }?.ts ?: 0L
            // не больше одного неотвеченного: уже писала сама после последнего сообщения Алима — молчит
            val initAt = Prefs.nudgedAt(f.id)
            if (initAt > lastMine) continue
            // не чаще раза в 6 часов и не больше двух раз в день
            if (now - initAt < 6 * 3600_000L) continue
            if (Prefs.initCount(f.id, day) >= 2) continue
            val model = f.model.ifBlank { Prefs.bg }
            fun mark() { Prefs.setNudgedAt(f.id, now); Prefs.addInitCount(f.id, day) }
            // 1) разговор оборвался — мягко вернуться (один раз)
            if (now - last.ts in 3 * 3600_000L..36 * 3600_000L && last.me.not() && lastMine > 0) {
                val sys = Persona.describe(f) + "\nВаш разговор оборвался несколько часов назад. Если тема явно НЕ закрыта (Алим что-то искал, решал, " +
                    "собирался сделать) — напиши одно короткое сообщение, чтобы ненавязчиво к ней вернуться. Если Алим попрощался, поблагодарил, " +
                    "ответил коротко или тема закрыта — верни пустой text. Сомневаешься — пустой text. Ответь ТОЛЬКО JSON: {\"text\": \"…\"}"
                val r = Llm.chat(model, JSONArray().put(msg("system", sys)).put(msg("user", c.recentForPrompt(14))), 250)
                val t = json(r.text).optString("text").trim()
                Prefs.setNudgedAt(f.id, now) // даже если промолчала — второй раз к этой паузе не возвращаемся
                if (t.isNotBlank()) { Prefs.addInitCount(f.id, day); c.botSays(t.take(500)); return }
                continue
            }
            // 2) главный заметил в уведомлениях что-то ПРЯМО по теме этого разговора
            if (now - lastMine < 2 * 86_400_000L) {
                val since = maxOf(Prefs.evCheck(f.id), now - 6 * 3600_000L)
                Prefs.setEvCheck(f.id, now)
                val ev = Events.since(ctx, since).filterNot { it.bulk }.map { Events.line(it).take(200) }.filterNot { NOISE.containsMatchIn(it) }.takeLast(25)
                if (ev.isEmpty()) continue
                val sys = Persona.describe(f) + "\nНиже новые уведомления с телефона Алима — их заметил ${Friends.main.name}. Почти всегда они НЕ касаются вашего " +
                    "разговора — тогда related=false. related=true ТОЛЬКО если уведомление прямо про предмет вашего разговора (например, вы обсуждали заказ " +
                    "трусов на Ozon — и пришло «заказ Ozon доставлен»). Покупки, платежи, письма, сервисы, приложения, не упомянутые в разговоре, — НЕ связаны. " +
                    "Если related=true — в why назови, с какой репликой разговора связь, а в text напиши Алиму одно короткое сообщение. " +
                    "Ответь ТОЛЬКО JSON: {\"related\": true|false, \"why\": \"…\", \"text\": \"…\"}"
                val user = "Ваш разговор:\n${c.recentForPrompt(10)}\n\nУведомления:\n" + ev.joinToString("\n")
                val r = Llm.chat(model, JSONArray().put(msg("system", sys)).put(msg("user", user)), 300)
                val j = json(r.text)
                val t = j.optString("text").trim()
                if (j.optBoolean("related") && j.optString("why").length > 8 && t.isNotBlank()) { mark(); c.botSays(t.take(400)); return }
            }
        }
    }

    private suspend fun morning(ctx: Context) {
        val weather = runCatching { Tools.byName("weather")?.run?.invoke(ctx, JSONObject()) }.getOrNull().orEmpty()
        val since = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 22) }.timeInMillis
        val ev = Events.since(ctx, since).filterNot { it.bulk }.takeLast(40).joinToString("\n") { Events.line(it).take(200) }
        val sys = Persona.describe() + "\nСейчас утро. Напиши Алиму первое сообщение дня: поприветствуй живо (не шаблонно), " +
            "коротко погода (что надеть/взять), и кто писал ночью — только люди, без рекламы; если никто — так и скажи или не упоминай. " +
            "2-5 коротких фраз, можно маленький список «кто писал». Ответь ТОЛЬКО JSON: {\"text\": \"…\", \"buttons\": [\"до 2 кнопок, например «Ответить маме»\"]}"
        val user = "Дата: ${SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())}\nПогода: $weather\nСобытия за ночь:\n${ev.ifBlank { "(ничего)" }}\n" +
            "Память об Алиме:\n${Memory.forPrompt(ctx, 25)}"
        val r = Llm.chat(Prefs.bg, JSONArray().put(msg("system", sys)).put(msg("user", user)), 600)
        val j = json(r.text)
        val text = j.optString("text").ifBlank { r.text.trim() }.take(1200)
        val btn = j.optJSONArray("buttons")?.let { a -> (0 until minOf(2, a.length())).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }
        Chat.botSays(text, btn)
        Journal.add(ctx, TaskRec(System.currentTimeMillis(), "Доброе утро", 1, Agent.short(Prefs.bg), r.cost(Prefs.bg), true, 0, text))
    }

    private suspend fun chat(ctx: Context) {
        val sys = Persona.describe() + "\nТы сам решил написать Алиму первым, просто по-дружески — как друг в мессенджере. " +
            "Варианты: спросить, как он/чем занят (учти время суток), вспомнить что-то из недавней переписки или памяти и спросить, как прошло, " +
            "или скинуть смешной/интересный ролик по его вкусу. Не повторяй то, что уже писал недавно. Одно короткое сообщение, 1-2 фразы. " +
            "Ответь ТОЛЬКО JSON: {\"text\": \"…\", \"youtube\": \"поисковый запрос для ролика или пусто\", \"voice\": true|false}"
        val user = "Сейчас: ${SimpleDateFormat("EEEE, HH:mm", Locale("ru")).format(Date())}\nНедавняя переписка:\n${Chat.recentForPrompt(12).ifBlank { "(пусто)" }}\n" +
            "Память об Алиме:\n${Memory.forPrompt(ctx, 30)}"
        val r = Llm.chat(Prefs.bg, JSONArray().put(msg("system", sys)).put(msg("user", user)), 300)
        val j = json(r.text)
        val text = j.optString("text").ifBlank { return }.take(500)
        val q = j.optString("youtube").trim()
        val items = if (q.isNotBlank()) runCatching { YouTube.search(q, "month", 5).shuffled().take(1) }.getOrDefault(emptyList()) else emptyList()
        val voice = if (items.isEmpty() && Prefs.voiceReplies && j.optBoolean("voice") && VoiceMsg.canSpeak)
            runCatching { VoiceMsg.speakToFile(ctx, text) }.getOrNull() else null
        Chat.botSays(text, items = if (items.isEmpty()) "" else Shop.toJson(items.map { it.item() }), voice = voice)
        Journal.add(ctx, TaskRec(System.currentTimeMillis(), "Написал сам: ${text.take(40)}", 1, Agent.short(Prefs.bg), r.cost(Prefs.bg), true, 0, text))
    }
}

/** «Артур рядом»: тихое постоянное уведомление, чтобы Android не усыплял его, даже если смахнуть приложение. */
class ArturService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this); Th.load()
        Agent.app = applicationContext
        Chat.init(this); Online.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("artur_bg") == null)
            nm.createNotificationChannel(NotificationChannel("artur_bg", "${Persona.name} рядом (фон)", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "artur_bg").setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle("${Persona.name} рядом").setContentText("Следит за сообщениями и пишет, когда нужно")
            .setOngoing(true).setSilent(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_MIN).build()
        runCatching {
            ServiceCompat.startForeground(this, 4242, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        }
        scope.launch {
            delay(60_000)
            while (true) {
                runCatching { Proactive.tick(this@ArturService) }
                delay(15 * 60_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ArturService::class.java)) }
        }
    }
}

/** После перезагрузки телефона — снова «рядом». */
class BootIn : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Prefs.init(ctx); ArturService.start(ctx) }
    }
}

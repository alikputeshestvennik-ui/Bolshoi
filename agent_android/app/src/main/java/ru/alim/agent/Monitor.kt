package ru.alim.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Фон: раз в N минут забрать почту, разобрать новое (важное — сразу уведомлением, факты — в память),
 * вечером собрать итог дня. Модели уходит только новое и не рассылки; коды и деньги вычищены заранее.
 */
object Monitor {
    fun schedule(ctx: Context, replace: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        if (!Prefs.monitorOn) { wm.cancelUniqueWork("monitor"); return }
        val req = PeriodicWorkRequestBuilder<MonitorWorker>(Prefs.interval.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork("monitor", if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runNow(ctx: Context, digest: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<MonitorWorker>()
            .setInputData(androidx.work.Data.Builder().putBoolean("digest", digest).build()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(if (digest) "digest-now" else "monitor-now", ExistingWorkPolicy.REPLACE, req)
    }

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun json(t: String): JSONObject {
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        return if (a >= 0 && b > a) runCatching { JSONObject(t.substring(a, b + 1)) }.getOrElse { JSONObject() } else JSONObject()
    }

    /** Почта обоих ящиков. Ошибки — в статус, мониторинг продолжается. */
    fun pullMail(ctx: Context): String {
        val errs = ArrayList<String>()
        var n = 0
        Mail.accounts(ctx).forEach { acc -> runCatching { n += Mail.fetchNew(ctx, acc) }.onFailure { errs += (it.message ?: "ошибка почты").take(160) } }
        return if (errs.isEmpty()) "почта: новых $n" else errs.joinToString("; ")
    }

    /** Разбор новых событий быстрой моделью. */
    fun triage(ctx: Context): String {
        val pending = Events.pending(ctx)
        if (pending.isEmpty()) return "нового нет"
        val batch = pending.take(40)
        val sys = buildString {
            append("Ты — фоновый помощник Алима. Тебе дают новые события с его телефона (уведомления, SMS, письма).\n")
            append("1) Выбери ВАЖНОЕ — то, о чём Алиму стоит узнать в ближайший час: личное сообщение от живого человека с вопросом или просьбой, ")
            append("пропущенный звонок, доставка/заказ готов к получению, письмо от человека или по работе, предупреждение безопасности, срочные сроки. ")
            append("Реклама, акции, новости, игры, соцсети, «вы вошли» от сервисов, которыми он пользуется прямо сейчас, — не важно.\n")
            append("2) Выпиши новые УСТОЙЧИВЫЕ факты о его жизни, если они явно видны (кто есть кто по имени, регулярные дела, сервисы, которыми он пользуется). ")
            append("Не записывай здоровье, деньги, пароли, адреса, интимное и разовые события. Чаще всего фактов нет — это нормально.\n")
            append("Что уже известно об Алиме:\n").append(Memory.forPrompt(ctx, 30).ifBlank { "(пока ничего)" }).append("\n")
            append("Ответь ТОЛЬКО JSON: {\"urgent\": [{\"id\": 123, \"say\": \"коротко для уведомления, до 12 слов\"}], \"facts\": [\"…\"]}")
        }
        val user = "Сейчас: ${SimpleDateFormat("EEEE d MMMM, HH:mm", Locale("ru")).format(Date())}\nСобытия:\n" + batch.joinToString("\n") { Events.line(it) }
        val model = Prefs.bg
        val r = Llm.chat(model, JSONArray().put(msg("system", sys)).put(msg("user", user)), 700)
        val j = json(r.text)
        val urgent = HashMap<Long, String>()
        j.optJSONArray("urgent")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                val id = o.optLong("id"); if (batch.any { it.id == id }) urgent[id] = o.optString("say").ifBlank { "Новое важное" }
            }
        }
        val facts = j.optJSONArray("facts")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty().take(3)
        facts.forEach { Memory.add(ctx, it, "авто") }
        Events.mark(ctx, batch.map { it.id }.toSet(), urgent)
        val byId = batch.associateBy { it.id }
        if (urgent.isNotEmpty() && Prefs.initImportant) {
            // важное — сообщением Артура в переписку (и уведомлением, если приложение закрыто)
            Chat.init(ctx)
            val lines = urgent.map { (id, say) -> (byId[id]?.from?.ifBlank { null }?.let { "**$it** — " } ?: "") + say }
            val who = urgent.keys.mapNotNull { byId[it]?.from?.takeIf { f -> f.isNotBlank() } }.distinct()
            Chat.botSays(if (lines.size == 1) "Алим, глянь: ${lines[0]}" else "Алим, важное:\n" + lines.joinToString("\n") { "- $it" },
                buttons = who.take(2).map { "Ответить: $it" })
        }
        Journal.add(ctx, TaskRec(System.currentTimeMillis(), "Мониторинг: ${batch.size} новых, важных ${urgent.size}", 1, Agent.short(model), r.cost(model), true))
        return "разобрано ${batch.size}, важных ${urgent.size}${if (facts.isNotEmpty()) ", фактов ${facts.size}" else ""}"
    }

    /** Одна проверка за раз: если система запустила несколько копий (после обновления, догоняя пропуски) — лишние пропускаем. */
    val runLock = kotlinx.coroutines.sync.Mutex()

    /** Забронировать сегодняшний итог: true — этот запуск пишет итог, остальные уже не будут. */
    @Synchronized fun claimDigest(force: Boolean): Boolean {
        if (!force && !digestDue()) return false
        Prefs.lastDigest = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return true
    }

    fun digestDue(): Boolean {
        val c = Calendar.getInstance()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
        return c.get(Calendar.HOUR_OF_DAY) >= Prefs.digestHour && Prefs.lastDigest != today
    }

    /** Свежие ролики YouTube по интересам Алима из памяти: запись в истории с карточками + уведомление. */
    fun ytPicks(ctx: Context): String {
        if (!Prefs.ytPicks) return "подборка выключена"
        val mem = Memory.forPrompt(ctx, 50)
        if (mem.isBlank()) return "в памяти пока нет интересов"
        val model = Prefs.bg
        val sys = "По фактам об Алиме придумай до 3 коротких запросов (2-4 слова, по-русски) для поиска свежих роликов на YouTube по его устойчивым интересам: " +
            "хобби, увлечения, темы, техника, места, проекты. Не про здоровье и деньги. Ответь ТОЛЬКО JSON {\"q\": [\"…\"]}; интересов не видно — {\"q\": []}."
        val r = Llm.chat(model, JSONArray().put(msg("system", sys)).put(msg("user", mem)), 200)
        val qa = json(r.text).optJSONArray("q") ?: JSONArray()
        val queries = (0 until minOf(3, qa.length())).map { qa.optString(it) }.filter { it.isNotBlank() }
        if (queries.isEmpty()) return "интересов не нашёл"
        val seen = Prefs.ytSeen.split(',').filter { it.isNotBlank() }.toMutableList()
        val picks = queries.flatMap { q -> runCatching { YouTube.search(q, "week", 8) }.getOrDefault(emptyList()).filter { it.id !in seen }.take(2) }
            .distinctBy { it.id }.take(6)
        if (picks.isEmpty()) return "нового нет"
        seen += picks.map { it.id }; Prefs.ytSeen = seen.takeLast(400).joinToString(",")
        val text = "Свежее на YouTube по твоим интересам (${queries.joinToString(", ")}):\n" + picks.joinToString("\n") { "- **${it.title}** — ${it.channel}" }
        Journal.add(ctx, TaskRec(System.currentTimeMillis(), "Подборка YouTube: ${queries.joinToString(", ")}", 1, Agent.short(model), r.cost(model), true,
            0, text, "подбираю ролики по интересам\t${queries.joinToString(" · ")}", Shop.toJson(picks.map { it.item() })))
        Chat.init(ctx)
        Chat.botSays("Глянь, что свежего по твоим темам (${queries.joinToString(", ")}):", items = Shop.toJson(picks.map { it.item() }))
        return "подборка: ${picks.size}"
    }

    /** Итог дня: события за сутки и задачи агента → короткая сводка + факты в память. */
    fun digest(ctx: Context): String {
        val from = System.currentTimeMillis() - 24 * 3600_000L
        val ev = Events.since(ctx, from)
        val tasks = Journal.since(ctx, Journal.startOfDay()).filterNot { it.title.startsWith("Мониторинг") }
        val bulkCount = ev.count { it.bulk }
        val sys = buildString {
            append("Ты — помощник Алима. Составь ИТОГ ДНЯ по событиям с его телефона. По-русски, просто, на «ты», 6–10 коротких строк:\n")
            append("— что было важного и от кого; — что ещё ЖДЁТ ответа или действия (самое полезное); — письма людей; — что делал агент.\n")
            append("Рекламу не перечисляй, просто скажи, сколько её было. Не выдумывай того, чего нет в событиях.\n")
            append("Отдельно выпиши до 3 новых устойчивых фактов о его жизни (без здоровья, денег, паролей, адресов).\n")
            append("Что уже известно:\n").append(Memory.forPrompt(ctx, 30).ifBlank { "(пока ничего)" }).append("\n")
            append("Ответь ТОЛЬКО JSON: {\"digest\": \"текст итога\", \"facts\": [\"…\"]}")
        }
        val user = buildString {
            append("Дата: ").append(SimpleDateFormat("EEEE d MMMM", Locale("ru")).format(Date())).append("\n")
            append("События (важные помечены !):\n")
            ev.filterNot { it.bulk }.takeLast(120).forEach { append(if (it.urgent) "! " else "").append(Events.line(it).take(260)).append("\n") }
            append("Рассылок и рекламы: $bulkCount\n")
            append("Задачи агента сегодня:\n")
            tasks.forEach { append("- ${it.title} (${if (it.ok) "ок" else "ошибка"})\n") }
        }
        val model = Prefs.bg
        val r = Llm.chat(model, JSONArray().put(msg("system", sys)).put(msg("user", user)), 1200)
        val j = json(r.text)
        val text = j.optString("digest").ifBlank { r.text.trim() }.take(3000)
        Digests.add(ctx, text)
        j.optJSONArray("facts")?.let { a -> (0 until minOf(3, a.length())).forEach { Memory.add(ctx, a.optString(it), "авто") } }
        Prefs.lastDigest = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        Chat.init(ctx)
        Chat.botSays("Итог дня, коротко:\n$text")
        Journal.add(ctx, TaskRec(System.currentTimeMillis(), "Итог дня", 1, Agent.short(model), r.cost(model), true, 0, text))
        runCatching { ytPicks(ctx) }
        return "итог готов"
    }

    fun notify(ctx: Context, id: Int, title: String, text: String, open: String = "") {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("important") == null)
            nm.createNotificationChannel(NotificationChannel("important", "Важное и итог дня", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).putExtra("open", open)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            NotificationManagerCompat.from(ctx).notify(id, NotificationCompat.Builder(ctx, "important").setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}

class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        Prefs.init(ctx)
        val forceDigest = inputData.getBoolean("digest", false)
        if (!Prefs.monitorOn && !forceDigest) return@withContext Result.success()
        if (!Monitor.runLock.tryLock()) return@withContext Result.success()
        try {
        val log = ArrayList<String>()
        log += Monitor.pullMail(ctx)
        log += runCatching { Monitor.triage(ctx) }.getOrElse { "разбор: ${it.message?.take(120)}" }
        if (Monitor.claimDigest(forceDigest)) log += runCatching { Monitor.digest(ctx) }.getOrElse { Prefs.lastDigest = ""; "итог: ${it.message?.take(120)}" }
        log += runCatching { Proactive.tick(ctx) }.getOrElse { "сам: ${it.message?.take(80)}" }
        Prefs.monitorStatus = SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date()) + " — " + log.joinToString(" · ")
        } finally { Monitor.runLock.unlock() }
        Result.success()
    }
}

/** Входящие SMS сразу в ленту событий. */
class SmsIn : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Prefs.init(ctx)
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = parts.firstOrNull()?.originatingAddress.orEmpty()
        val text = parts.joinToString("") { it.messageBody.orEmpty() }
        val name = runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(from)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: from
        Events.add(ctx, "sms", "SMS", name, "", text)
    }
}

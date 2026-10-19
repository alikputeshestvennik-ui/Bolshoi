package ru.alim.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Входящие из мессенджеров, пойманные по уведомлениям. Кнопка «Ответить» живёт, пока живо уведомление. */
object NotifStore {
    class Msg(
        val id: Int, val app: String, val pkg: String, val from: String, val text: String,
        val ts: Long, val group: Boolean, val action: Notification.Action?,
    ) {
        val canReply get() = action?.remoteInputs?.isNotEmpty() == true
        fun time(): String = SimpleDateFormat("HH:mm", Locale("ru")).format(Date(ts))
    }

    val version = mutableIntStateOf(0)
    private val msgs = ArrayList<Msg>()
    private var nextId = 1

    val PACKAGES = mapOf(
        "org.telegram.messenger" to "Telegram", "org.telegram.messenger.web" to "Telegram",
        "org.thunderdog.challegram" to "Telegram X",
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp",
        "ru.oneme.app" to "MAX",
        "com.google.android.apps.messaging" to "SMS", "com.samsung.android.messaging" to "SMS",
    )

    @Synchronized fun recent(): List<Msg> = msgs.toList()
    @Synchronized fun byId(id: Int): Msg? = msgs.firstOrNull { it.id == id }

    @Synchronized
    fun add(app: String, pkg: String, from: String, text: String, group: Boolean, action: Notification.Action?): Msg? {
        // Мессенджеры перевыпускают одно и то же уведомление — дубли не нужны
        if (msgs.takeLast(15).any { it.pkg == pkg && it.from == from && it.text == text }) return null
        val m = Msg(nextId++, app, pkg, from, text, System.currentTimeMillis(), group, action)
        msgs += m
        while (msgs.size > 80) msgs.removeAt(0)
        version.intValue = version.intValue + 1
        return m
    }

    /** Коды, пароли, деньги — агент их не видит и не трогает. */
    fun sensitive(text: String): Boolean {
        val t = text.lowercase()
        val words = listOf("код", "code", "пароль", "password", "парол", "cvv", "списан", "перевод", "карт", "баланс", "смс-код", "никому не сообщайте")
        return words.any { t.contains(it) } && Regex("\\d{3,}").containsMatchIn(t) || t.contains("никому не сообщайте")
    }
    fun safe(text: String) = if (sensitive(text)) "[скрыто: похоже на код или деньги]" else text.take(400)

    fun reply(ctx: Context, id: Int, text: String): String {
        val m = byId(id) ?: return "сообщение #$id не найдено"
        val act = m.action ?: return "у этого уведомления нет кнопки ответа"
        val inputs = act.remoteInputs ?: return "у этого уведомления нет поля ответа"
        if (text.isBlank()) return "пустой ответ"
        val intent = Intent()
        val bundle = Bundle()
        inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        return try {
            act.actionIntent.send(ctx, 0, intent); "ответил ${m.from} в ${m.app}"
        } catch (e: PendingIntent.CanceledException) {
            "уведомление уже закрыто — ответить так нельзя, открой чат"
        }
    }

    fun accessGranted(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun requestRebind(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            NotificationListenerService.requestRebind(ComponentName(ctx, NotifListener::class.java))
        }
    }
}

class NotifListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() { super.onCreate(); Prefs.init(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val app = NotifStore.PACKAGES[pkg] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val group = (style?.isGroupConversation == true) ||
            (Build.VERSION.SDK_INT >= 28 && ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
        val from = (if (group) "${style?.conversationTitle ?: title}: ${last?.person?.name ?: ""}" else (last?.person?.name?.toString() ?: title)).trim()
        val text = (last?.text ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (from.isEmpty() || text.isEmpty()) return
        val action = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
        val m = NotifStore.add(app, pkg, from, text, group, action) ?: return
        if (Prefs.autoReply && !group && m.canReply && !NotifStore.sensitive(text)) scope.launch { AutoReply.handle(applicationContext, m) }
    }
}

/** Автоответчик: быстрая модель читает правила Алима и решает — ответить, промолчать или только пересказать. */
object AutoReply {
    private val lastReply = HashMap<String, Long>()

    private fun inContacts(ctx: Context, name: String): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            ctx.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    fun handle(ctx: Context, m: NotifStore.Msg) {
        val key = m.pkg + m.from
        val now = System.currentTimeMillis()
        // Не переписываемся с собеседником сами с собой: не чаще раза в 2 минуты
        if (now - (lastReply[key] ?: 0L) < 120_000L) return
        val sys = buildString {
            append("Ты — автоответчик Алима в мессенджерах. Его правила:\n").append(Prefs.rules).append("\n\n")
            append("Решай строго по правилам. Пишешь от имени Алима, коротко и по-человечески, без подписи «бот».\n")
            append("Ответь ТОЛЬКО JSON: {\"reply\": \"текст ответа или null, если отвечать не нужно\", \"note\": \"пересказ для Алима в 1 фразу\"}")
        }
        val user = "Время: ${SimpleDateFormat("HH:mm, EEEE", Locale("ru")).format(Date(now))}\n" +
            "Приложение: ${m.app}\nОт: ${m.from} (в контактах: ${if (inContacts(ctx, m.from)) "да" else "нет"})\nСообщение: ${m.text}"
        val model = Prefs.fastModel
        try {
            val r = Llm.chat(model, JSONArray().put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", user)), 400)
            val a = r.text.indexOf('{'); val b = r.text.lastIndexOf('}')
            val j = if (a >= 0 && b > a) JSONObject(r.text.substring(a, b + 1)) else JSONObject()
            val reply = j.optString("reply").trim().takeIf { it.isNotEmpty() && it != "null" }
            val note = j.optString("note").trim().ifBlank { m.text.take(120) }
            if (reply != null) {
                val res = NotifStore.reply(ctx, m.id, reply)
                lastReply[key] = now
                notify(ctx, "Ответил: ${m.from}", "«$reply»\n$note ($res)")
            } else {
                notify(ctx, "${m.from} · ${m.app}", note)
            }
            Journal.add(ctx, TaskRec(now, "Автоответ: ${m.from}", 1, Agent.short(model), r.cost(model), true))
        } catch (e: Exception) {
            notify(ctx, "Автоответчик не сработал", e.message ?: "ошибка")
        }
    }

    fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("auto") == null)
            nm.createNotificationChannel(NotificationChannel("auto", "Автоответчик", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(),
                NotificationCompat.Builder(ctx, "auto").setSmallIcon(R.drawable.ic_launcher_fg)
                    .setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

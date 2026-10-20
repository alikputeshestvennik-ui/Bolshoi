package ru.alim.agent

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Руки агента. Всё, что идёт людям (звонок, SMS, фото, ответ в мессенджере), — с confirm:
 * приложение показывает карточку «Нужно твоё ОК» и ждёт Алима.
 */
object Tools {
    class Tool(
        val name: String,
        val help: String,
        val confirm: ((JSONObject) -> String)? = null,
        val run: suspend (Context, JSONObject) -> String,
    )

    /** Фото из последнего поиска — их можно отправить, не перечисляя. */
    val lastPhotos = ArrayList<Long>()
    /** Товары из последнего поиска — «открой второй». */
    val lastShop = ArrayList<Shop.Item>()
    private val ru = Locale("ru")
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun digits(s: String) = s.filter { it.isDigit() || it == '+' }

    private suspend fun launch(ctx: Context, i: Intent): Boolean = withContext(Dispatchers.Main) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private val apps = mapOf(
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "max" to listOf("ru.oneme.app"),
        "ozon" to listOf(Shop.OZON_PKG),
        "wb" to listOf(Shop.WB_PKG),
    )
    private fun installed(ctx: Context, app: String): String? =
        apps[app.lowercase()]?.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    val list: List<Tool> = listOf(
        Tool("now", "{} — текущие дата, день недели и время") { _, _ ->
            SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", ru).format(Date())
        },

        Tool("contacts_find", "{\"name\": \"Саша\"} — найти номер в контактах (частичное совпадение имени)") t@{ ctx, a ->
            if (!has(ctx, Manifest.permission.READ_CONTACTS)) return@t "нет разрешения на контакты — пусть Алим выдаст его в «Подключениях»"
            val q = a.optString("name").trim()
            if (q.isEmpty()) return@t "пустое имя"
            val out = LinkedHashSet<String>()
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )?.use { c -> while (c.moveToNext() && out.size < 10) out += "${c.getString(0)}: ${c.getString(1)?.replace(" ", "")}" }
            if (out.isEmpty()) "не найдено" else out.joinToString("\n")
        },

        Tool("call", "{\"number\": \"+7978…\", \"name\": \"Саша\"} — позвонить",
            confirm = { a -> "Позвонить ${a.optString("name").ifBlank { a.optString("number") }}?" }) t@{ ctx, a ->
            val n = digits(a.optString("number"))
            if (n.length < 3) return@t "нет номера — сначала contacts_find"
            val direct = has(ctx, Manifest.permission.CALL_PHONE)
            val ok = launch(ctx, Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
            if (!ok) "не получилось открыть звонилку" else if (direct) "звоню" else "открыл набор номера, осталось нажать вызов"
        },

        Tool("sms_send", "{\"number\": \"+7978…\", \"name\": \"Саша\", \"text\": \"…\"} — отправить SMS",
            confirm = { a -> "SMS ${a.optString("name").ifBlank { a.optString("number") }}:\n«${a.optString("text")}»" }) t@{ ctx, a ->
            if (!has(ctx, Manifest.permission.SEND_SMS)) return@t "нет разрешения на SMS"
            val n = digits(a.optString("number")); val text = a.optString("text")
            if (n.length < 3 || text.isBlank()) return@t "нужны номер и текст"
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
            sm.sendMultipartTextMessage(n, null, sm.divideMessage(text), null, null)
            "отправлено"
        },

        Tool("photos_find", "{\"from\": \"2026-06-13\", \"to\": \"2026-06-13\", \"camera_only\": true} — найти фото за даты (to можно не указывать)") t@{ ctx, a ->
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if (!has(ctx, perm)) return@t "нет разрешения на фото"
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val from = runCatching { fmt.parse(a.optString("from"))!!.time }.getOrNull() ?: return@t "дата from в формате ГГГГ-ММ-ДД"
            val to = (runCatching { fmt.parse(a.optString("to"))!!.time }.getOrNull() ?: from) + 86_400_000L
            val cameraOnly = a.optBoolean("camera_only", false)
            lastPhotos.clear()
            val times = ArrayList<String>()
            val hm = SimpleDateFormat("d MMM HH:mm", ru)
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
                arrayOf(from.toString(), to.toString()), "${MediaStore.Images.Media.DATE_TAKEN} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val bucket = c.getString(2).orEmpty()
                    if (cameraOnly && !bucket.equals("Camera", true)) continue
                    lastPhotos += c.getLong(0)
                    if (times.size < 25) times += hm.format(Date(c.getLong(1)))
                }
            }
            if (lastPhotos.isEmpty()) "фото за эти даты не найдено"
            else "найдено ${lastPhotos.size} фото, от новых к старым: ${times.joinToString(", ")}${if (lastPhotos.size > times.size) " …" else ""}. " +
                "Отправить — photos_send с \"which\": \"all\" или номерами по порядку, например \"1,3,5\"."
        },

        Tool("photos_send", "{\"which\": \"all\" | \"1,3\", \"app\": \"telegram|whatsapp|max|any\", \"text\": \"подпись\"} — отправить найденные фото (откроется выбор чата)",
            confirm = { a -> "Отправить ${pick(a.optString("which", "all")).size} фото в ${a.optString("app", "любое приложение")}? Там выберешь чат." }) t@{ ctx, a ->
            val ids = pick(a.optString("which", "all"))
            if (ids.isEmpty()) return@t "сначала photos_find"
            val uris = ArrayList<Uri>(ids.map { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) })
            val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.optString("text").takeIf { it.isNotBlank() }?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
            val pkg = installed(ctx, a.optString("app"))
            val ok = if (pkg != null) launch(ctx, i.setPackage(pkg)) else launch(ctx, Intent.createChooser(i, "Отправить фото"))
            if (ok) "открыл отправку ${ids.size} фото${if (pkg != null) " в ${a.optString("app")}" else ""} — Алим выберет чат" else "не получилось открыть отправку"
        },

        Tool("whatsapp_write", "{\"number\": \"+7978…\", \"text\": \"…\"} — открыть чат WhatsApp с готовым текстом (Алим нажмёт «Отправить»)") t@{ ctx, a ->
            val n = a.optString("number").filter { it.isDigit() }
            if (n.length < 10) return@t "нужен номер"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + URLEncoder.encode(a.optString("text"), "UTF-8")))
            installed(ctx, "whatsapp")?.let { i.setPackage(it) }
            if (launch(ctx, i)) "открыл чат с текстом, осталось нажать «Отправить»" else "WhatsApp не открылся"
        },

        Tool("telegram_open", "{\"username\": \"sasha_p\"} — открыть чат в Telegram по нику") t@{ ctx, a ->
            val u = a.optString("username").trim().removePrefix("@")
            if (u.isEmpty()) return@t "нужен ник"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$u"))
            installed(ctx, "telegram")?.let { i.setPackage(it) }
            if (launch(ctx, i)) "открыл чат @$u" else "Telegram не открылся"
        },

        Tool("messages_recent", "{\"app\": \"telegram|whatsapp|max|sms|\", \"from\": \"имя или пусто\", \"limit\": 10} — последние входящие из уведомлений") { _, a ->
            val app = a.optString("app").lowercase(); val from = a.optString("from").lowercase()
            val l = NotifStore.recent().filter {
                (app.isBlank() || it.app.lowercase().contains(app)) && (from.isBlank() || it.from.lowercase().contains(from))
            }.takeLast(a.optInt("limit", 10).coerceIn(1, 30))
            if (l.isEmpty()) "входящих нет (или нет доступа к уведомлениям)"
            else l.joinToString("\n") { "#${it.id} [${it.app}] ${it.from}: ${NotifStore.safe(it.text)} (${it.time()})${if (it.canReply) "" else " — ответить нельзя"}" }
        },

        Tool("message_reply", "{\"id\": 12, \"text\": \"…\"} — ответить на входящее прямо из уведомления",
            confirm = { a -> NotifStore.byId(a.optInt("id"))?.let { "Ответить ${it.from} в ${it.app}:\n«${a.optString("text")}»" } ?: "Ответить на сообщение #${a.optInt("id")}?" }) { ctx, a ->
            NotifStore.reply(ctx, a.optInt("id"), a.optString("text"))
        },

        Tool("alarm_set", "{\"hour\": 7, \"minute\": 30, \"label\": \"…\"} — поставить будильник") { ctx, a ->
            val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            a.optString("label").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (launch(ctx, i)) "будильник на %02d:%02d поставлен".format(a.optInt("hour"), a.optInt("minute")) else "часы не открылись"
        },

        Tool("timer_set", "{\"seconds\": 300, \"label\": \"…\"} — таймер") { ctx, a ->
            val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            a.optString("label").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (launch(ctx, i)) "таймер запущен" else "часы не открылись"
        },

        Tool("weather", "{\"place\": \"Ялта\"} — погода сейчас и на 3 дня (место можно не указывать)") { _, a ->
            val place = a.optString("place").ifBlank { Prefs.homeCity }
            runCatching { weather(place) }.getOrElse { e1 ->
                runCatching { wttr(place) }.getOrElse { e2 -> "погода не пришла: open-meteo — ${e1.message}; wttr.in — ${e2.message}" }
            }
        },

        Tool("open_app", "{\"name\": \"Ozon\"} — открыть приложение по названию") t@{ ctx, a ->
            val q = a.optString("name").trim().lowercase()
            if (q.isEmpty()) return@t "нужно название"
            val pm = ctx.packageManager
            val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .sortedBy { it.first.length }
                .firstOrNull { it.first.lowercase().contains(q) } ?: return@t "приложение «$q» не найдено"
            val i = pm.getLaunchIntentForPackage(found.second) ?: return@t "не открывается"
            if (launch(ctx, i)) "открыл ${found.first}" else "не открылось"
        },

        Tool("web_open", "{\"query\": \"…\"} или {\"url\": \"https://…\"} — открыть поиск Яндекса или сайт в браузере") t@{ ctx, a ->
            val url = a.optString("url").takeIf { it.startsWith("http") }
                ?: a.optString("query").takeIf { it.isNotBlank() }?.let { "https://yandex.ru/search/?text=" + URLEncoder.encode(it, "UTF-8") }
                ?: return@t "нужен url или query"
            if (launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "открыл в браузере" else "браузер не открылся"
        },

        Tool("gptunnel_balance", "{} — баланс счёта gptunnel") { _, _ ->
            val b = Llm.balance(); Prefs.lastBalance = rub(b); "баланс ${rub(b)}"
        },

        Tool("spend", "{} — сколько агент потратил сегодня и за месяц") { ctx, _ ->
            val d = Journal.since(ctx, Journal.startOfDay()); val m = Journal.since(ctx, Journal.startOfMonth())
            "сегодня ${d.size} задач на ${rub(d.sumOf { it.cost })}, за месяц ${m.size} на ${rub(m.sumOf { it.cost })}, лимит ${Prefs.monthLimit} ₽"
        },

        Tool("photos_last", "{\"count\": 1} — самые свежие фото, первое — последнее снятое") t@{ ctx, a ->
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if (!has(ctx, perm)) return@t "нет разрешения на фото"
            val n = a.optInt("count", 1).coerceIn(1, 30)
            lastPhotos.clear()
            val times = ArrayList<String>()
            val hm = SimpleDateFormat("d MMM HH:mm", ru)
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && lastPhotos.size < n) { lastPhotos += c.getLong(0); times += hm.format(Date(c.getLong(1) * 1000)) }
            }
            if (lastPhotos.isEmpty()) "фото нет" else "готово ${lastPhotos.size} фото (от новых к старым): ${times.joinToString(", ")}. " +
                "Отправить — photos_send с \"which\": \"all\" или \"1\"."
        },

        Tool("photos_show", "{\"which\": \"all\" | \"1,3\"} — показать найденные фото сеткой на экране задачи") t@{ _, a ->
            val ids = pick(a.optString("which", "all")).take(60)
            if (ids.isEmpty()) return@t "сначала photos_find или photos_last"
            withContext(Dispatchers.Main) { Agent.photos.clear(); Agent.photos.addAll(ids) }
            "показал ${ids.size} фото на экране; нажатие на фото откроет его в Галерее"
        },

        Tool("photos_open", "{\"which\": \"1\"} — открыть одно найденное фото в Галерее") t@{ ctx, a ->
            val id = pick(a.optString("which", "1")).firstOrNull() ?: return@t "сначала photos_find или photos_last"
            val ok = launch(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(photoUri(id), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            if (ok) "открыл фото в Галерее" else "Галерея не открылась"
        },

        Tool("sms_read", "{\"from\": \"имя или номер, можно пусто\", \"limit\": 5} — последние входящие SMS") t@{ ctx, a ->
            if (!has(ctx, Manifest.permission.READ_SMS)) return@t "нет разрешения на чтение SMS — пусть Алим выдаст его в «Подключениях»"
            val from = a.optString("from").trim().lowercase(); val lim = a.optInt("limit", 5).coerceIn(1, 20)
            val out = ArrayList<String>()
            val df = SimpleDateFormat("d MMM HH:mm", ru)
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                var seen = 0
                while (c.moveToNext() && out.size < lim && seen < 300) {
                    seen++
                    val addr = c.getString(0).orEmpty()
                    val name = contactName(ctx, addr) ?: addr
                    if (from.isNotBlank() && !name.lowercase().contains(from) && !addr.contains(from)) continue
                    out += "$name (${df.format(Date(c.getLong(2)))}): ${NotifStore.safe(c.getString(1).orEmpty())}"
                }
            }
            if (out.isEmpty()) "SMS не найдено" else out.joinToString("\n")
        },

        Tool("ussd", "{\"code\": \"*100#\"} — USSD-запрос оператору (баланс, тариф, остатки); ответ оператора придёт текстом",
            confirm = { a -> "Отправить оператору запрос ${a.optString("code")}?" }) t@{ ctx, a ->
            if (!has(ctx, Manifest.permission.CALL_PHONE)) return@t "нет разрешения на звонки — оно нужно для USSD"
            val code = a.optString("code").trim()
            if (!code.matches(Regex("[*#0-9]{3,30}"))) return@t "код должен быть вида *100#"
            val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return@t "нет связи"
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(40_000) {
                    suspendCancellableCoroutine<String> { cont ->
                        try {
                            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                                override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) {
                                    if (cont.isActive) cont.resume("ответ оператора: $response")
                                }
                                override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                                    if (cont.isActive) cont.resume("оператор не ответил на $request (код $failureCode)")
                                }
                            }, Handler(Looper.getMainLooper()))
                        } catch (e: SecurityException) {
                            if (cont.isActive) cont.resume("нет разрешения на USSD")
                        }
                    }
                } ?: "оператор не ответил за 40 секунд"
            }
        },

        Tool("tg_channel", "{\"username\": \"ник_канала\"} — последние посты ОТКРЫТОГО Telegram-канала (ник без @). Ник не знаешь — найди через web_search") t@{ _, a ->
            val u = a.optString("username").trim().removePrefix("@").removePrefix("https://").removePrefix("t.me/").substringBefore('/').substringBefore('?')
            if (u.isEmpty()) return@t "нужен ник канала"
            val html = httpGet("https://t.me/s/$u")
            val posts = Regex("<div class=\"tgme_widget_message_text[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { clean(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
            val dates = Regex("<time datetime=\"([^\"]+)\"").findAll(html).map { it.groupValues[1].take(16).replace('T', ' ') }.toList()
            if (posts.isEmpty()) return@t "канал @$u не найден, закрыт или без текстовых постов"
            posts.takeLast(3).mapIndexed { i, p ->
                val d = dates.getOrNull(dates.size - (posts.takeLast(3).size - i))
                "${d ?: ""} ${p.take(1200)}"
            }.joinToString("\n---\n", "Последние посты @$u (последний внизу):\n")
        },

        Tool("web_search", "{\"query\": \"…\"} — поиск в интернете, вернёт заголовки, ссылки и отрывки. Потом прочитай лучшую ссылку через web_read") t@{ _, a ->
            val q = a.optString("query").trim()
            if (q.isEmpty()) return@t "пустой запрос"
            val errs = ArrayList<String>()
            for (engine in listOf(YaSearch::search, ::ddgHtml, ::ddgLite, ::bing)) {
                val r = runCatching { engine(q) }.onFailure { errs += it.message ?: "ошибка" }.getOrNull()
                if (!r.isNullOrEmpty()) return@t r.take(6).joinToString("\n\n")
            }
            "поиск ничего не дал" + if (errs.isNotEmpty()) " (${errs.joinToString("; ").take(300)})" else ""
        },

        Tool("web_read", "{\"url\": \"https://…\"} — прочитать текст страницы") t@{ _, a ->
            val url = a.optString("url").trim()
            if (!url.startsWith("http")) return@t "нужна ссылка"
            val html = httpGet(url)
            val body = html.replace(Regex("<(script|style|noscript)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
            clean(body).replace(Regex("\\s{2,}"), " ").take(4000).ifBlank { "страница пустая" }
        },

        Tool("yoomoney_balance", "{} — баланс кошелька ЮMoney") { ctx, _ -> Yoo.balance(ctx) },

        Tool("yoomoney_history", "{\"days\": 7, \"limit\": 30} — операции ЮMoney за последние дни: приходы и расходы") { ctx, a ->
            Yoo.history(ctx, a.optInt("days", 7).coerceIn(1, 90), a.optInt("limit", 30))
        },

        Tool("memory_save", "{\"text\": \"Денис — брат Алима\"} — запомнить факт об Алиме надолго") { ctx, a ->
            Memory.add(ctx, a.optString("text"), "агент")?.let { "запомнил" } ?: "не запомнил (пусто или похоже на код/деньги)"
        },

        Tool("memory_search", "{\"query\": \"Денис\"} — найти в памяти") { ctx, a ->
            Memory.search(ctx, a.optString("query")).joinToString("\n") { "[${it.id}] ${it.text} (${it.source})" }.ifBlank { "в памяти ничего" }
        },

        Tool("memory_forget", "{\"id\": 123} — удалить запись из памяти",
            confirm = { a -> "Забыть запись #${a.optLong("id")}?" }) { ctx, a ->
            Memory.remove(ctx, a.optLong("id")); "забыл"
        },

        Tool("events", "{\"kind\": \"mail|sms|msg|notif|\", \"hours\": 24, \"query\": \"\", \"important\": false} — что пришло на телефон: письма, SMS, сообщения, уведомления") { ctx, a ->
            val kind = a.optString("kind").trim(); val q = a.optString("query").trim().lowercase()
            val from = System.currentTimeMillis() - a.optInt("hours", 24).coerceIn(1, 96) * 3600_000L
            val l = Events.since(ctx, from).filter {
                (kind.isBlank() || it.kind == kind) && (!a.optBoolean("important") || it.urgent) && !it.bulk &&
                    (q.isBlank() || (it.from + " " + it.title + " " + it.text).lowercase().contains(q))
            }.takeLast(a.optInt("limit", 20).coerceIn(1, 40))
            if (l.isEmpty()) "ничего не найдено" else l.joinToString("\n") { Events.line(it).take(300) }
        },

        Tool("mail_check", "{\"limit\": 10} — забрать новую почту из обоих ящиков Gmail и показать последние письма людей") { ctx, a ->
            val st = Monitor.pullMail(ctx)
            val l = Events.all(ctx).filter { it.kind == "mail" && !it.bulk }.takeLast(a.optInt("limit", 10).coerceIn(1, 30))
            "$st\n" + (if (l.isEmpty()) "писем от людей нет (или почта не подключена в «Подключениях»)" else l.joinToString("\n") { Events.line(it).take(300) })
        },

        Tool("event_read", "{\"id\": 123} — полный текст письма или уведомления по номеру") { ctx, a ->
            Events.byId(ctx, a.optLong("id"))?.let { "${it.source} · ${it.from} · ${it.title}\n${it.text}" } ?: "не найдено"
        },

        Tool("image_generate", "{\"prompt\": \"подробное описание картинки\", \"aspect\": \"1:1|16:9|9:16|4:3|3:4\"} — нарисовать картинку (Alice AI ART), сохранится в Галерею и покажется на экране") t@{ ctx, a ->
            val prompt = a.optString("prompt").trim()
            if (prompt.isEmpty()) return@t "нужно описание картинки"
            val id = YaArt.generate(ctx, prompt, a.optString("aspect", "1:1"))
            lastPhotos.clear(); lastPhotos += id
            withContext(Dispatchers.Main) { Agent.photos.clear(); Agent.photos.add(id) }
            "картинка готова и показана на экране, сохранена в Галерею (альбом «Агент»). Отправить — photos_send с which=1"
        },

        Tool("attachment_send", "{\"which\": \"all\" | \"1,2\", \"app\": \"telegram|whatsapp|max|any\", \"text\": \"подпись\"} — переслать вложения из последней задачи",
            confirm = { a -> "Переслать вложения (${a.optString("which", "all")}) в ${a.optString("app", "приложение")}? Там выберешь чат." }) t@{ ctx, a ->
            val w = a.optString("which", "all").trim().lowercase()
            val list = if (w == "all" || w.isEmpty()) Attach.sent.toList()
                else w.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.mapNotNull { Attach.sent.getOrNull(it - 1) }
            if (list.isEmpty()) return@t "вложений нет"
            val i = if (list.size == 1) Intent(Intent.ACTION_SEND).setType(list[0].mime).putExtra(Intent.EXTRA_STREAM, list[0].uri)
                else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list.map { it.uri }))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            a.optString("text").takeIf { it.isNotBlank() }?.let { i.putExtra(Intent.EXTRA_TEXT, it) }
            val pkg = installed(ctx, a.optString("app"))
            val ok = if (pkg != null) launch(ctx, i.setPackage(pkg)) else launch(ctx, Intent.createChooser(i, "Отправить"))
            if (ok) "открыл отправку ${list.size} файл(ов) — Алим выберет чат" else "не получилось открыть отправку"
        },

        Tool("shop_search", "{\"store\": \"ozon|wb|both\", \"query\": \"кроссовки asics gel-kayano мужские\", \"max_price\": 8000, \"seller\": \"Ozon Россия\", \"original\": true} — найти товары. seller — только у этого продавца, original — только с отметкой «Оригинал» (агент сам откроет страницы товаров). Подделки (−90%, «осталось N ед», слишком дёшево для бренда) скрываются; all=true — показать всё") t@{ ctx, a ->
            val q = a.optString("query").trim()
            if (q.isEmpty()) return@t "нужен запрос"
            val maxP = a.optInt("max_price", 0).takeIf { it > 0 }
            val store = a.optString("store", "both").lowercase()
            val seller = a.optString("seller").trim()
            val orig = a.optBoolean("original", false)
            val found = ArrayList<Shop.Item>(); val notes = StringBuilder()
            if (store != "ozon") runCatching { Shop.wb(q, maxP) }.onSuccess { found += it }.onFailure { notes.append("Wildberries: ${it.message}\n") }
            if (store != "wb") {
                val pages = if (seller.isNotBlank() || orig) listOf(1, 2) else listOf(1)
                val res = coroutineScope { pages.map { pg -> async { runCatching { Shop.ozon(ctx, q, maxP, pg) } } }.map { it.await() } }
                res.forEach { r -> r.onSuccess { l -> l.forEach { x -> if (found.none { it.url == x.url }) found += x } } }
                if (res.all { it.isFailure }) notes.append("Ozon: ${res.first().exceptionOrNull()?.message}\n")
            }
            // нужен конкретный продавец — добираем ссылки на товары через поиск Яндекса (как это делает Алиса)
            if (seller.isNotBlank() && store != "wb" && YaSearch.ready) runCatching { YaSearch.search("site:ozon.ru $q $seller") }.onSuccess { res ->
                res.forEach { line ->
                    Regex("https?://(www\\.)?ozon\\.ru/product/[^\\s?#]+").find(line)?.value?.let { u ->
                        if (found.none { it.url.trimEnd('/') == u.trimEnd('/') }) found.add(0, Shop.Item(line.substringBefore(" — ").take(150), "", u, "", "Ozon"))
                    }
                }
            }
            val showAll = a.optBoolean("all", false)
            var hidden = 0
            fun clean(l: List<Shop.Item>) = if (showAll) l else l.filter { Shop.suspect(it, q).isEmpty().also { ok -> if (!ok) hidden++ } }
            var list = clean(found)
            var checkedN = 0
            if (seller.isNotBlank() || orig) {
                val wbs = list.filter { it.store == "WB" }.filter { seller.isBlank() || Shop.sellerMatch(seller, it.seller) }
                // дорогие сначала: у настоящих продавцов цены выше, чем у подделок
                val ozc = list.filter { it.store == "Ozon" }.sortedByDescending { Shop.rub(it.price) }.take(12)
                checkedN = ozc.size
                val checked = ozc.chunked(4).flatMap { ch ->
                    coroutineScope { ch.map { it to async { runCatching { Shop.ozonInfo(ctx, it.url) }.getOrNull() } }.map { (it, d) -> it to d.await() } }
                }
                val oz = checked.mapNotNull { (it, inf) ->
                    if (inf == null) return@mapNotNull null
                    val item = if (it.price.isBlank()) Shop.Item(inf.title.ifBlank { it.title }.take(150), inf.price, it.url, inf.img, "Ozon")
                               else it
                    item.seller = inf.seller; item.original = inf.original
                    item.takeIf {
                        (seller.isBlank() || Shop.sellerMatch(seller, inf.seller)) && (!orig || seller.isNotBlank() || inf.original) &&
                            (maxP == null || Shop.rub(it.price).let { r -> r == 0 || r <= maxP })
                    }
                }
                list = clean(oz + if (orig && seller.isBlank()) emptyList() else wbs)
            }
            lastShop.clear(); lastShop += list.take(8)
            withContext(Dispatchers.Main) { Agent.products.clear(); Agent.products.addAll(lastShop) }
            val out = StringBuilder(notes)
            if (lastShop.isEmpty()) out.append(when {
                seller.isNotBlank() -> "У продавца «$seller» не нашёл (проверил $checkedN карточек Ozon). Попробуй другой запрос (модель, без лишних слов) или shop_open с store и query."
                orig -> "С отметкой «Оригинал» не нашёл среди $checkedN карточек."
                else -> "Ничего подходящего."
            }) else {
                out.append("Найдено ${lastShop.size}:\n")
                lastShop.forEachIndexed { i, x ->
                    out.append("${i + 1}. [${x.store}] ${x.title} — ${x.price.ifBlank { "цена ?" }}")
                    if (x.seller.isNotBlank()) out.append(" — продавец ${x.seller}")
                    if (x.original) out.append(" — отметка «Оригинал»")
                    out.append("\n")
                }
            }
            if (hidden > 0) out.append("\nСкрыл $hidden подозрительных (кричащие скидки −70…−95%, «осталось N ед», цена ниже реальной для бренда) — скорее всего подделки. Скажи Алиму об этом одной фразой.")
            if (seller.isBlank() && !orig) out.append("\nПродавца и «Оригинал» на Ozon видно только в карточке: если Алиму важна оригинальность — повтори с original=true или seller.")
            out.append("\nРазмер — shop_check. Открыть товар — shop_open с номером.")
            out.toString()
        },

        Tool("shop_open", "{\"n\": 2} или {\"url\": \"…\"} или {\"store\": \"ozon\", \"query\": \"…\"} — открыть товар или поиск в приложении магазина") t@{ ctx, a ->
            val url = a.optString("url").takeIf { it.startsWith("http") }
                ?: lastShop.getOrNull(a.optInt("n", 0) - 1)?.url
                ?: a.optString("query").takeIf { it.isNotBlank() }?.let { q ->
                    if (a.optString("store").lowercase() == "wb") "https://www.wildberries.ru/catalog/0/search.aspx?search=" + URLEncoder.encode(q, "UTF-8")
                    else "https://www.ozon.ru/search/?text=" + URLEncoder.encode(q, "UTF-8")
                } ?: return@t "нужен номер из поиска, ссылка или запрос"
            val pkg = if (url.contains("ozon.ru")) installed(ctx, "ozon") else if (url.contains("wildberries")) installed(ctx, "wb") else null
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { pkg?.let { setPackage(it) } }
            if (launch(ctx, i) || launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "открыл${if (pkg != null) " в приложении" else ""}" else "не открылось"
        },

        Tool("shop_check", "{\"n\": \"1,2,3\", \"size\": \"43\"} — проверить у найденных товаров размер, продавца и отметку «Оригинал»") t@{ ctx, a ->
            val size = a.optString("size").trim()
            val ns = a.optString("n", "1,2,3").split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.take(5)
            val pick = ns.mapNotNull { n -> lastShop.getOrNull(n - 1)?.let { n to it } }
            if (pick.isEmpty()) return@t "сначала shop_search"
            val res = coroutineScope {
                pick.map { (n, it) -> async {
                    runCatching {
                        if (it.store == "WB") {
                            val r = if (size.isNotBlank()) Shop.wbSize(it.url, size) else ""
                            if (size.isNotBlank()) it.size = when { r.contains("ЕСТЬ") -> "$size есть"; r.contains("раскупили") || r.contains("нет в сетке") -> "$size нет"; else -> "" }
                            "$n. ${it.title.take(60)}: $r${if (it.seller.isNotBlank()) "; продавец ${it.seller}" else ""}"
                        } else {
                            val inf = Shop.ozonInfo(ctx, it.url)
                            if (inf != null) { it.seller = inf.seller; it.original = inf.original }
                            val r = if (size.isNotBlank()) Shop.ozonSize(inf, size) else ""
                            if (size.isNotBlank()) it.size = when { r.contains("есть в размерной") -> "$size в сетке"; r.contains("не видно") -> "$size нет"; else -> "" }
                            "$n. ${it.title.take(60)}: $r; продавец ${inf?.seller?.ifBlank { "?" } ?: "?"}; ${if (inf?.original == true) "отметка «Оригинал» есть" else "отметки «Оригинал» нет"}"
                        }
                    }.getOrElse { e -> "$n. не проверил: ${e.message}" }
                } }.map { it.await() }
            }
            withContext(Dispatchers.Main) { val cp = Agent.products.toList(); Agent.products.clear(); Agent.products.addAll(cp) }
            res.joinToString("\n")
        },

        Tool("web_page", "{\"url\": \"https://…\"} — прочитать сайт, который не открывается через web_read (тяжёлый, на JavaScript)") t@{ ctx, a ->
            val url = a.optString("url").trim()
            if (!url.startsWith("http")) return@t "нужна ссылка"
            WebReader.text(ctx, url)
        },

        Tool("escalate", "{\"reason\": \"…\"} — передать задачу сильной модели (только для сложных задач)") { _, _ -> "" },
    )

    /** DuckDuckGo, HTML-версия: ссылки идут через редирект с параметром uddg. */
    private fun ddgHtml(q: String): List<String> {
        val body = okhttp3.FormBody.Builder().add("q", q).add("kl", "ru-ru").build()
        val html = http.newCall(Request.Builder().url("https://html.duckduckgo.com/html/").post(body)
            .header("User-Agent", UA).header("Accept-Language", "ru-RU,ru;q=0.9").build()).execute().use {
            if (!it.isSuccessful) error("ddg ${it.code}"); it.body?.string().orEmpty()
        }
        val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snips = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, m ->
            val url = realUrl(m.groupValues[1]) ?: return@mapIndexedNotNull null
            if (url.contains("duckduckgo.com/y.js")) return@mapIndexedNotNull null // реклама
            "${clean(m.groupValues[2])} — $url\n${snips.getOrNull(i).orEmpty().take(240)}"
        }
    }

    /** DuckDuckGo Lite — ещё проще, на случай если HTML-версия закрыта. */
    private fun ddgLite(q: String): List<String> {
        val html = httpGet("https://lite.duckduckgo.com/lite/?kl=ru-ru&q=" + URLEncoder.encode(q, "UTF-8"))
        val links = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*class=['\"]result-link['\"][^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snips = Regex("class=['\"]result-snippet['\"][^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, m ->
            val url = realUrl(m.groupValues[1]) ?: return@mapIndexedNotNull null
            "${clean(m.groupValues[2])} — $url\n${snips.getOrNull(i).orEmpty().take(240)}"
        }
    }

    private fun bing(q: String): List<String> {
        val html = httpGet("https://www.bing.com/search?setlang=ru&cc=RU&q=" + URLEncoder.encode(q, "UTF-8"))
        return Regex("<li class=\"b_algo\"(.*?)(?=<li class=\"b_algo\"|</ol>)", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { m ->
            val b = m.groupValues[1]
            val link = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b) ?: return@mapNotNull null
            val snip = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            "${clean(link.groupValues[2])} — ${link.groupValues[1].replace("&amp;", "&")}\n${snip.take(240)}"
        }.toList()
    }

    /** Достаёт настоящий адрес из редиректа DuckDuckGo. */
    private fun realUrl(href: String): String? {
        val h = href.replace("&amp;", "&")
        val u = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: if (h.startsWith("//")) "https:$h" else h
        return u.takeIf { it.startsWith("http") }
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    private fun httpGet(url: String): String =
        http.newCall(Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ru-RU,ru;q=0.9").build()).execute().use { r ->
            if (!r.isSuccessful) error("сайт ответил ${r.code}")
            r.body?.string().orEmpty()
        }

    /** HTML → текст: переносы строк, без тегов, с расшифровкой частых сущностей. */
    private fun clean(html: String): String = html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>|</div>|</li>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

    private fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    fun byName(n: String) = list.firstOrNull { it.name == n }
    fun describe() = list.joinToString("\n") { "- ${it.name} ${it.help}" }

    private fun pick(which: String): List<Long> {
        val w = which.trim().lowercase()
        if (w.isEmpty() || w == "all" || w == "last" || w == "все") return lastPhotos.toList()
        return w.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.mapNotNull { lastPhotos.getOrNull(it - 1) }.distinct()
    }

    private fun weather(place: String): String {
        val g = http.newCall(Request.Builder().url("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" +
            URLEncoder.encode(place, "UTF-8")).build()).execute().use { it.body?.string().orEmpty() }
        val p = JSONObject(g).optJSONArray("results")?.optJSONObject(0) ?: return "не нашёл место «$place»"
        val lat = p.optDouble("latitude"); val lon = p.optDouble("longitude")
        val f = http.newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            "&timezone=auto&forecast_days=3&wind_speed_unit=ms").build()).execute().use { it.body?.string().orEmpty() }
        val j = JSONObject(f)
        val c = j.optJSONObject("current") ?: return "погода не пришла"
        val d = j.optJSONObject("daily")
        val days = StringBuilder()
        if (d != null) {
            val dates = d.optJSONArray("time")
            for (k in 0 until (dates?.length() ?: 0)) {
                days.append("\n${dates!!.optString(k)}: ${d.optJSONArray("temperature_2m_min")?.optDouble(k)?.toInt()}…" +
                    "${d.optJSONArray("temperature_2m_max")?.optDouble(k)?.toInt()}°, осадки ${d.optJSONArray("precipitation_probability_max")?.optInt(k)}%, " +
                    code(d.optJSONArray("weather_code")?.optInt(k) ?: 0))
            }
        }
        return "${p.optString("name")}: сейчас ${c.optDouble("temperature_2m").toInt()}° (ощущается ${c.optDouble("apparent_temperature").toInt()}°), " +
            "${code(c.optInt("weather_code"))}, ветер ${c.optDouble("wind_speed_10m").toInt()} м/с$days"
    }

    /** Запасной источник погоды: wttr.in. */
    private fun wttr(place: String): String {
        val j = JSONObject(httpGet("https://wttr.in/" + URLEncoder.encode(place, "UTF-8") + "?format=j1&lang=ru"))
        val c = j.getJSONArray("current_condition").getJSONObject(0)
        fun desc(o: JSONObject) = o.optJSONArray("lang_ru")?.optJSONObject(0)?.optString("value")
            ?: o.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value").orEmpty()
        val sb = StringBuilder("$place: сейчас ${c.optString("temp_C")}° (ощущается ${c.optString("FeelsLikeC")}°), ${desc(c)}, " +
            "ветер ${(c.optString("windspeedKmph").toDoubleOrNull()?.div(3.6))?.toInt() ?: "?"} м/с")
        val days = j.optJSONArray("weather")
        for (k in 0 until (days?.length() ?: 0)) {
            val d = days!!.getJSONObject(k)
            val noon = d.optJSONArray("hourly")?.optJSONObject(4)
            sb.append("\n${d.optString("date")}: ${d.optString("mintempC")}…${d.optString("maxtempC")}°, " +
                "дождь ${noon?.optString("chanceofrain") ?: "?"}%, ${noon?.let { desc(it) } ?: ""}")
        }
        return sb.toString()
    }

    private fun code(w: Int) = when (w) {
        0 -> "ясно"; 1, 2 -> "переменная облачность"; 3 -> "пасмурно"; 45, 48 -> "туман"
        in 51..57 -> "морось"; in 61..67 -> "дождь"; in 71..77 -> "снег"; in 80..82 -> "ливень"
        in 85..86 -> "снегопад"; in 95..99 -> "гроза"; else -> "погода $w"
    }
}

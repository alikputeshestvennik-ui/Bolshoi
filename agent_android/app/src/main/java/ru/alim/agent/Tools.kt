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
import android.provider.CallLog
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
        /** Подтверждение отпечатком пальца (переводы денег). */
        val secure: Boolean = false,
        val run: suspend (Context, JSONObject) -> String,
    )

    /** Что доступно второстепенным друзьям; остальное они просят у главного через ask_main. */
    val FRIEND_TOOLS = setOf("now", "weather", "web_search", "web_read", "web_page", "tg_channel", "memory_save", "memory_search",
        "youtube_search", "youtube_open", "shop_search", "shop_check", "shop_open", "maps_route", "maps_search",
        "file_create", "file_read", "pdf_edit", "draw", "image_generate", "escalate", "ask_main")

    /** Фото из последнего поиска — их можно отправить, не перечисляя. */
    val lastPhotos = ArrayList<Long>()
    /** Товары из последнего поиска — «открой второй». */
    val lastShop = ArrayList<Shop.Item>()
    /** Ролики из последнего поиска YouTube. */
    val lastVideos = ArrayList<YouTube.Video>()
    /** Все поиски YouTube за текущую задачу — в конце на экране оставляем только выбранные ролики. */
    val taskVideos = ArrayList<List<YouTube.Video>>()
    private val ru = Locale("ru")
    /** Куки в памяти: без них часть сайтов (Туту) гоняет по кругу редиректов — «Too many follow-up requests». */
    private val jar = object : okhttp3.CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            val h = url.host; store[h] = (store[h].orEmpty().filter { o -> cookies.none { it.name == o.name } } + cookies).takeLast(40)
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = store[url.host].orEmpty().filter { it.matches(url) }
    }
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).cookieJar(jar).build()

    private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun digits(s: String) = s.filter { it.isDigit() || it == '+' }
    private val CYR = "а б в г д е ё ж з и й к л м н о п р с т у ф х ц ч ш щ ъ ы ь э ю я".split(" ")
    private val LAT = "a b v g d e e zh z i y k l m n o p r s t u f h ts ch sh sch   y   e yu ya".split(" ")
    /** Грубая транслитерация, чтобы «спортмастер» находил отправителя SPORTMASTER. */
    private fun translit(s: String) = buildString { for (ch in s.lowercase()) { val i = CYR.indexOf(ch.toString()); append(if (i >= 0) LAT[i] else ch) } }

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

        Tool("sms_send", "{\"number\": \"+7978…\", \"name\": \"Саша\", \"text\": \"…\", \"sim\": \"1|2|оператор, необяз.\"} — отправить SMS. Две SIM и sim не указан — спроси у Алима, с какой",
            confirm = { a -> "SMS ${a.optString("name").ifBlank { a.optString("number") }}:\n«${a.optString("text")}»" }) t@{ ctx, a ->
            if (Agent.cur().delegIntent) return@t "СТОП: это поручение — разовые сообщения тут нельзя. Вызови delegate (channel, number/chat, sim, goal с полной задачей Алима) — переговорщик сам поздоровается, будет ждать ответы и пришлёт итог."
            if (!has(ctx, Manifest.permission.SEND_SMS)) return@t "нет разрешения на SMS"
            val n = digits(a.optString("number")); val text = a.optString("text")
            if (n.length < 3 || text.isBlank()) return@t "нужны номер и текст"
            Device.simChoice(ctx, a.optString("sim"))?.let { return@t it }
            val subId = Device.pickSim(ctx, a.optString("sim"))
            val base: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
            val sm = if (subId != null) runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base) else base
            sm.sendMultipartTextMessage(n, null, sm.divideMessage(text), null, null)
            "отправлено" + (Device.sims(ctx).firstOrNull { it.subId == subId }?.let { " (${it.carrier})" } ?: "")
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

        Tool("photos_search", "{\"query\": \"я в чёрной футболке\", \"days\": 365, \"screenshots\": false} — найти фото ПО СОДЕРЖИМОМУ (что на них изображено): смотрит последние ~150 снимков. По дате — photos_find") t@{ ctx, a ->
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if (!has(ctx, perm)) return@t "нет разрешения на фото"
            val q = a.optString("query").trim().ifBlank { return@t "что искать на фото?" }
            val (ids, seen) = PhotoSearch.find(ctx, q, a.optInt("days", 365).coerceIn(1, 3650), a.optInt("max", 150).coerceIn(16, 300), a.optBoolean("screenshots", false))
            lastPhotos.clear(); lastPhotos += ids
            withContext(Dispatchers.Main) { Agent.cur().photos.clear(); Agent.cur().photos.addAll(ids.take(30)) }
            if (ids.isEmpty()) "среди $seen последних фото «$q» не нашёл — можно поискать глубже (days побольше) или со скриншотами"
            else "нашёл ${ids.size} фото «$q» среди $seen последних — показал сеткой. Отправить — photos_send."
        },

        Tool("photos_send", "{\"which\": \"all\" | \"1,3\", \"app\": \"telegram|whatsapp|max|any\", \"text\": \"подпись\"} — отправить найденные фото (откроется приложение, чат Алим выберет сам)",
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

        Tool("alarm_set", "{\"hour\": 7, \"minute\": 30, \"label\": \"…\"} — будильник на ближайшие сутки (дату не принимает!). Напоминание на конкретную дату — только event_create") { ctx, a ->
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

        Tool("weather", "{\"place\": \"Ялта\" или \"Казань, Петербург\", \"from\": \"ГГГГ-ММ-ДД\", \"to\": \"ГГГГ-ММ-ДД\"} — погода: прогноз на 16 дней; даты дальше — ориентир по тем же дням трёх прошлых лет. Несколько городов — через запятую в одном вызове. from/to необязательны") { _, a ->
            val places = a.optString("place").ifBlank { Prefs.homeCity }.split(Regex("\\s*(,|;|\\s+и\\s+)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
            val from = a.optString("from").trim().takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }
            val to = a.optString("to").trim().takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) } ?: from
            places.joinToString("\n\n") { place ->
                // один повтор Open-Meteo (бывают разовые сбои), потом запасной wttr.in
                runCatching { weather(place, from, to) }.recoverCatching { weather(place, from, to) }.getOrElse { e1 ->
                    runCatching { wttr(place) }.getOrElse { e2 ->
                        "ошибка: $place: погода не загрузилась (Open-Meteo — ${e1.message?.take(60)}; wttr.in — ${e2.message?.take(60)}). Скорее всего нет VPN или сети: скажи Алиму прямо «погоду не загрузил, проверь VPN», а не «нет данных» молча; в таблице и карточке погоду не выдумывай."
                    }
                }
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

        Tool("web_open", "{\"query\": \"…\"} или {\"url\": \"https://…\"} — открыть сайт в браузере Алиму. ТОЛЬКО если он прямо попросил «открой». Для поиска информации не используй — читай сам через web_read/web_page") t@{ ctx, a ->
            val url = a.optString("url").takeIf { it.startsWith("http") }
                ?: a.optString("query").takeIf { it.isNotBlank() }?.let { "https://yandex.ru/search/?text=" + URLEncoder.encode(it, "UTF-8") }
                ?: return@t "нужен url или query"
            if (launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "открыл в браузере" else "браузер не открылся"
        },

        Tool("spend", "{} — сколько Тень потратил (сегодня, за месяц), средняя цена задачи по моделям, во сколько обходятся быстрые ответы. На любые вопросы про расходы и «во сколько обойдётся» — только по этим цифрам") { ctx, _ ->
            val d = Journal.since(ctx, Journal.startOfDay()); val m = Journal.since(ctx, Journal.startOfMonth())
            val lane = m.filter { it.title.startsWith("Быстрые ответы") }
            val tasks = m.filter { !it.background }
            val byModel = tasks.groupBy { it.model }.entries.sortedByDescending { e -> e.value.size }.take(6).joinToString("\n") { (k, l) ->
                val cheap = l.minOf { it.cost }; val dear = l.maxOf { it.cost }
                "• $k: ${l.size} задач, в среднем ${rub(l.sumOf { it.cost } / l.size)} (от ${rub(cheap)} до ${rub(dear)})"
            }
            "Сегодня: ${d.size} записей на ${rub(d.sumOf { it.cost })}. За месяц: ${rub(m.sumOf { it.cost })} (задачи ${rub(tasks.sumOf { it.cost })}, " +
                "быстрые ответы чата ${rub(lane.sumOf { it.cost })} за ${lane.sumOf { it.steps }} сообщений). Лимит ${Prefs.monthLimit} ₽.\n" +
                "Модели сейчас: чат ${Agent.short(Prefs.fastModel)}, руки ${Agent.short(Prefs.smartModel)}, фон ${Agent.short(Prefs.bgModel)}. " +
                "AnyModel: ${AnyPrice.RUB_PER_M} ₽ за миллион токенов баланса × коэффициент модели; Яндекс бесплатно.\n" +
                "Средняя цена задачи за месяц по моделям:\n" + byModel.ifBlank { "пока нет задач" }
        },

        Tool("calc", "{\"expr\": \"итого: 25572 + 1200 + 2200 + 25592; на человека: (25572+25592)/2; скидка: 8799*0.56\"} — посчитать ТОЧНО (+ − * / ^ %, скобки; несколько выражений через «;», можно с подписью «имя:»). Любые суммы, разницы, проценты, «×2 на двоих» — только через calc, не в уме") t@{ _, a ->
            val src = a.optString("expr").ifBlank { a.optString("expression") }.trim()
            if (src.isEmpty()) return@t "нужно expr"
            src.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.take(30).joinToString("\n") { part ->
                val label = part.substringBefore(':', "").takeIf { part.contains(':') && !Regex("\\d:\\d").containsMatchIn(part) }?.trim().orEmpty()
                val ex = if (label.isNotEmpty()) part.substringAfter(':') else part
                runCatching { Calc(ex).run() }.fold(
                    { v -> (if (label.isNotEmpty()) "$label = " else "$ex = ") + fmtNum(v) },
                    { e -> "${label.ifEmpty { ex }}: не посчитал (${e.message})" })
            }
        },

        Tool("history_search", "{\"query\": \"Тула\", \"days\": 14} — найти прошлые задачи Тени (что Алим просил и что ты ответил): «помнишь…», «та задача», «что ты вчера нашёл», «повтори для …». query пустой — последние задачи") { ctx, a ->
            val q = a.optString("query").trim().lowercase()
            val days = a.optInt("days", 30).coerceIn(1, 365)
            val from = System.currentTimeMillis() - days * 86_400_000L
            val words = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.map { it.take(5) }   // «Туле», «Тулу» → «тул»
            val recs = Journal.all(ctx).filter { it.ts >= from && !it.background }.reversed()
            val hits = (if (words.isEmpty()) recs else recs.filter { r ->
                val hay = (r.title + " " + r.result).lowercase()
                words.all { w -> hay.contains(w.take(if (w.length > 4) 4 else w.length)) }
            }).take(5)
            if (hits.isEmpty()) "В истории за $days дн. не нашёл задач по «$q». Не выдумывай: спроси Алима, что он имеет в виду."
            else "Нашёл в истории задач (новые сверху):\n" + hits.joinToString("\n\n") { r ->
                val d = SimpleDateFormat("d MMM HH:mm", ru).format(java.util.Date(r.ts))
                "• $d — просьба: «${r.title.take(400)}»${if (!r.ok) " (не закончена)" else ""}\n  ответ: ${r.result.replace(Regex("\\s+"), " ").take(1500).ifBlank { "—" }}"
            } + "\nЕсли Алим просит продолжить или повторить — бери условия ИЗ ЭТОЙ просьбы (откуда, даты, сколько человек), ничего не додумывай."
        },

        Tool("photos_last", "{\"count\": 1, \"kind\": \"any|screenshot|camera\"} — самые свежие фото, первое — последнее. «Последний скриншот» — kind screenshot, «последнее фото с камеры» — camera") t@{ ctx, a ->
            val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if (!has(ctx, perm)) return@t "нет разрешения на фото"
            val n = a.optInt("count", 1).coerceIn(1, 30)
            lastPhotos.clear()
            val times = ArrayList<String>()
            val hm = SimpleDateFormat("d MMM HH:mm", ru)
            val kind = a.optString("kind", "any").lowercase()
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && lastPhotos.size < n) {
                    val shot = c.getString(2).orEmpty().let { it.contains("screenshot", true) || it.contains("снимки экрана", true) }
                    if (kind == "screenshot" && !shot) continue
                    if (kind == "camera" && shot) continue
                    lastPhotos += c.getLong(0); times += hm.format(Date(c.getLong(1) * 1000))
                }
            }
            if (lastPhotos.isEmpty()) "фото нет" else "готово ${lastPhotos.size} фото (от новых к старым): ${times.joinToString(", ")}. " +
                "Отправить — photos_send с \"which\": \"all\" или \"1\"."
        },

        Tool("photos_show", "{\"which\": \"all\" | \"1,3\"} — показать найденные фото сеткой на экране задачи") t@{ _, a ->
            val ids = pick(a.optString("which", "all")).take(60)
            if (ids.isEmpty()) return@t "сначала photos_find или photos_last"
            withContext(Dispatchers.Main) { Agent.cur().photos.clear(); Agent.cur().photos.addAll(ids) }
            "показал ${ids.size} фото на экране; нажатие на фото откроет его в Галерее"
        },

        Tool("photos_open", "{\"which\": \"1\"} — открыть одно найденное фото в Галерее") t@{ ctx, a ->
            val id = pick(a.optString("which", "1")).firstOrNull() ?: return@t "сначала photos_find или photos_last"
            val ok = launch(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(photoUri(id), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            if (ok) "открыл фото в Галерее" else "Галерея не открылась"
        },

        Tool("sms_read", "{\"query\": \"спортмастер бонусы\", \"from\": \"отправитель, необяз.\", \"days\": 90, \"limit\": 15} — найти SMS по отправителю ИЛИ по словам в тексте за период. Ищет по всему ящику, латиницу и кириллицу сопоставляет сам. Без query и from — просто последние") t@{ ctx, a ->
            if (!has(ctx, Manifest.permission.READ_SMS)) return@t "нет разрешения на чтение SMS — пусть Алим выдаст его в «Подключениях»"
            val q = a.optString("query").trim().lowercase()
            val from = a.optString("from").trim().lowercase()
            val needles = (listOf(q, from).filter { it.isNotBlank() } + listOf(q, from).filter { it.isNotBlank() }.map { translit(it) }).distinct()
            val lim = a.optInt("limit", if (needles.isEmpty()) 8 else 15).coerceIn(1, 40)
            val days = a.optInt("days", 90).coerceIn(1, 3650)
            val since = System.currentTimeMillis() - days * 86_400_000L
            val out = ArrayList<String>()
            val df = SimpleDateFormat("d MMM HH:mm", ru)
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} DESC")?.use { c ->
                var seen = 0
                while (c.moveToNext() && out.size < lim && seen < 2000) {
                    seen++
                    val addr = c.getString(0).orEmpty()
                    val body = c.getString(1).orEmpty()
                    val name = contactName(ctx, addr) ?: addr
                    val hay = "${name.lowercase()} ${addr.lowercase()} ${body.lowercase()}"
                    if (needles.isNotEmpty() && needles.none { hay.contains(it) }) continue
                    out += "$name (${df.format(Date(c.getLong(2)))}): ${NotifStore.safe(body)}"
                }
            }
            if (out.isEmpty()) (if (needles.isEmpty()) "SMS нет" else "по этому в SMS за $days дн. ничего не нашёл") else out.joinToString("\n")
        },

        Tool("calls_log", "{\"days\": 7, \"type\": \"out|in|missed|all\", \"who\": \"имя/номер, необяз.\"} — журнал звонков за период: кто, когда, тип, длительность; плюс сводка (сколько звонков и средняя длительность)") t@{ ctx, a ->
            if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return@t "нет доступа к журналу вызовов — пусть Алим выдаст его в «Подключениях»"
            val days = a.optInt("days", 7).coerceIn(1, 365)
            val since = System.currentTimeMillis() - days * 86_400_000L
            val want = a.optString("type", "all").lowercase()
            val who = a.optString("who").trim().lowercase()
            val df = SimpleDateFormat("d MMM HH:mm", ru)
            val rows = ArrayList<String>(); var cnt = 0; var dur = 0L
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ?", arrayOf(since.toString()), "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(0).orEmpty(); val nm = c.getString(1).orEmpty().ifBlank { contactName(ctx, num) ?: num }
                    val t = c.getInt(2); val d = c.getLong(4)
                    val kind = when (t) { CallLog.Calls.OUTGOING_TYPE -> "out"; CallLog.Calls.INCOMING_TYPE -> "in"; CallLog.Calls.MISSED_TYPE -> "missed"; else -> "other" }
                    if (want != "all" && kind != want) continue
                    if (who.isNotBlank() && !nm.lowercase().contains(who) && !num.contains(who)) continue
                    val label = when (kind) { "out" -> "исходящий"; "in" -> "входящий"; "missed" -> "пропущенный"; else -> "звонок" }
                    if (kind != "missed") { cnt++; dur += d }
                    if (rows.size < 40) rows += "$nm — $label, ${df.format(Date(c.getLong(3)))}${if (d > 0) ", ${d / 60} мин ${d % 60} сек" else ""}"
                }
            }
            if (rows.isEmpty()) return@t "звонков за $days дн. не нашёл"
            val avg = if (cnt > 0) dur / cnt else 0
            "За $days дн.: состоявшихся $cnt, средняя длительность ${avg / 60} мин ${avg % 60} сек.\n" + rows.take(25).joinToString("\n")
        },

        Tool("ussd", "{\"code\": \"*100#\", \"sim\": \"1|2|оператор, необяз.\"} — USSD-запрос оператору (баланс, тариф, остатки). Две SIM и sim не указан — спроси у Алима, с какой",
            confirm = { a -> "Отправить оператору запрос ${a.optString("code")}?" }) t@{ ctx, a ->
            if (!has(ctx, Manifest.permission.CALL_PHONE)) return@t "нет разрешения на звонки — оно нужно для USSD"
            val code = a.optString("code").trim()
            if (!code.matches(Regex("[*#0-9]{3,30}"))) return@t "код должен быть вида *100#"
            Device.simChoice(ctx, a.optString("sim"))?.let { return@t it }
            val subId = Device.pickSim(ctx, a.optString("sim"))
            val tmBase = ctx.getSystemService(TelephonyManager::class.java) ?: return@t "нет связи"
            val tm = if (subId != null) runCatching { tmBase.createForSubscriptionId(subId) }.getOrDefault(tmBase) else tmBase
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

        Tool("music_search", "{\"query\": \"Баста\"} — найти треки/альбом в Яндекс Музыке") t@{ _, a ->
            if (!YaMusic.ready) return@t "Яндекс Музыка не подключена — ⋮ → Настройки → Яндекс Музыка"
            val q = a.optString("query").trim(); if (q.isEmpty()) return@t "что искать?"
            YaMusic.search(q)
        },
        Tool("music_play", "{\"query\": \"Баста - Сансара\"} — поставить трек: найдёт и откроет в приложении Яндекс Музыки") t@{ ctx, a ->
            if (!YaMusic.ready) return@t "Яндекс Музыка не подключена"
            val q = a.optString("query").trim(); if (q.isEmpty()) return@t "что поставить?"
            YaMusic.play(ctx, q)
        },
        Tool("music_my", "{} — твои плейлисты в Яндекс Музыке") t@{ _, _ ->
            if (!YaMusic.ready) return@t "Яндекс Музыка не подключена"
            YaMusic.myPlaylists()
        },

        Tool("event_create", "{\"title\": \"встреча с Егором\", \"when\": \"ГГГГ-ММ-ДД ЧЧ:ММ\" (или «завтра 15:00»), \"duration_min\": 60, \"place\": \"\"} — создать событие в календаре Алима. Дату пиши полностью: 2026-12-01 10:00",
            // в карточке — дата так, как её понял Пилот, а не слова модели: ошибку видно до «Да»
            confirm = { a -> Device.parseWhen(a.optString("when"))?.let { "В календарь: «${a.optString("title")}» — ${Device.whenLabel(it)}?" } ?: "" }) t@{ ctx, a ->
            val title = a.optString("title").trim(); if (title.isEmpty()) return@t "что за событие?"
            val start = Device.parseWhen(a.optString("when")) ?: return@t "не понял дату «${a.optString("when")}» — передай when как ГГГГ-ММ-ДД ЧЧ:ММ, например 2026-12-01 10:00"
            if (start < System.currentTimeMillis() - 60_000) return@t "дата ${Device.whenLabel(start)} уже прошла — проверь when"
            Device.createEvent(ctx, title, start, start + a.optInt("duration_min", 60) * 60_000L, a.optString("place")) + " (${Device.whenLabel(start)})"
        },
        Tool("time_calc", "{\"start\": \"2027-01-02 16:55\", \"add\": \"1д 6ч 15м\"} — прибавить время в пути к отправлению; или {\"from\": \"2027-01-02 14:00\", \"to\": \"2027-01-03 07:35\"} — сколько между двумя моментами; {\"start\": …, \"add\": …, \"to\": \"момент вылета\"} — успеваю ли. Если города в разных часовых поясах — добавь \"tz\": на сколько часов пояс прибытия впереди пояса отправления (Ялта → Екатеринбург: 2; Самара → Ялта: -1). Точный счёт по часам для цепочек поездки") t@{ _, a ->
            val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            // любой формат: «2027-01-02 16:55», «2027-01-02T16:55:00», «02.01.2027 16:55», «2 января 16:55»
            fun dt(x: String) = runCatching { java.time.LocalDateTime.parse(x.trim().replace('T', ' ').take(16), f) }.getOrNull()
                ?: runCatching { Device.parseWhenAt(x, java.time.LocalDateTime.now()) }.getOrNull()
            fun lbl(d: java.time.LocalDateTime) = d.format(f) + " (" + d.format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMMM", java.util.Locale("ru"))) + ")"
            // модели путают ключи: start/from/depart, add/duration/travel, to/end/arrive — принимаем всё
            fun pick(vararg k: String) = k.map { a.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            val st = pick("start", "from", "depart", "departure", "begin", "at")
            val add = pick("add", "duration", "travel", "plus", "dur", "in_path").lowercase()
            val to = pick("to", "end", "arrive", "arrival", "until")
            // tz — на сколько часов пояс города прибытия впереди пояса отправления (Ялта → Екатеринбург: 2; Самара → Ялта: -1)
            val tz = pick("tz", "tz_shift", "shift", "utc_shift").replace(Regex("[^-+\\d.]"), "").toDoubleOrNull() ?: 0.0
            val tzMin = (tz * 60).toLong()
            val got = "получил: " + a.toString().take(160)
            if (st.isBlank()) return@t "нужен start+add или from+to; $got"
            val d0 = dt(st) ?: return@t "не понял момент «$st» — дай как ГГГГ-ММ-ДД ЧЧ:ММ; $got"
            if (add.isNotBlank()) {
                fun n(re: String) = Regex("(\\d+)\\s*$re").find(add)?.groupValues?.get(1)?.toLong() ?: 0L
                var mins = n("(д|дн|сут|d)") * 1440 + n("(ч|h)") * 60 + n("(м|мин|m)(?!е)")
                Regex("^(\\d{1,2}):(\\d{2})$").find(add)?.let { mins = it.groupValues[1].toLong() * 60 + it.groupValues[2].toLong() }
                if (mins == 0L) return@t "add нужен как «1д 6ч 15м»; $got"
                val r = d0.plusMinutes(mins + tzMin)
                var out = "${lbl(d0)} + ${mins / 1440} д ${mins % 1440 / 60} ч ${mins % 60} м" +
                    (if (tzMin != 0L) " + разница поясов ${if (tz > 0) "+" else ""}${tz.toString().removeSuffix(".0")} ч (время прибытия — местное)" else "") + " = ${lbl(r)}"
                // срок возврата берём из ПРОСЬБЫ Алима, а не из того, что модель подставила: сверяем сами
                deadline?.let { dl ->
                    val toDt = to.takeIf { it.isNotBlank() }?.let { dt(it) }
                    if (toDt != dl && r.isAfter(dl.minusHours(20)) && r.isBefore(dl.plusDays(4))) {
                        val g2 = java.time.Duration.between(r, dl).toMinutes()
                        out += "\nСРОК из просьбы Алима: ${lbl(dl)} — " + (if (g2 >= 0) "запас ${g2 / 60} ч ${g2 % 60} м" else "опоздание ${-g2 / 60} ч ${-g2 % 60} м") +
                            (if (toDt != null) ". ВНИМАНИЕ: ты сравнивал с ${lbl(toDt)} — это не срок из просьбы" else "")
                    }
                }
                if (to.isNotBlank()) dt(to)?.let { b ->
                    val gap = java.time.Duration.between(r, b).toMinutes()
                    out += if (gap >= 0) "; до ${lbl(b)} остаётся ${gap / 60} ч ${gap % 60} м — УСПЕВАЕШЬ" else "; это ПОЗЖЕ ${lbl(b)} на ${-gap / 60} ч ${-gap % 60} м — не успеваешь"
                }
                return@t out
            }
            if (to.isNotBlank()) {
                val b0 = dt(to) ?: return@t "не понял момент «$to» — дай как ГГГГ-ММ-ДД ЧЧ:ММ; $got"
                val m = java.time.Duration.between(d0, b0).toMinutes() - tzMin   // реальное время в пути: местные часы минус разница поясов
                val am = kotlin.math.abs(m)
                return@t "от ${lbl(d0)} до ${lbl(b0)}: ${if (m < 0) "минус " else ""}${am / 1440} д ${am % 1440 / 60} ч ${am % 60} м" + if (m < 0) " — второй момент РАНЬШЕ первого, не успеваешь" else ""
            }
            "нужен start+add или from+to"
        },
        Tool("event_list", "{\"day\": \"today|tomorrow|ГГГГ-ММ-ДД\"} — события календаря на день («что у меня завтра»)") t@{ ctx, a ->
            val c = java.util.Calendar.getInstance()
            when (a.optString("day").lowercase()) { "tomorrow", "завтра" -> c.add(java.util.Calendar.DAY_OF_YEAR, 1)
                "today", "сегодня", "" -> {}
                else -> runCatching { c.time = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(a.optString("day"))!! } }
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0)
            val start = c.timeInMillis
            Device.readEvents(ctx, start, start + 86_400_000L)
        },
        Tool("my_location", "{} — где Алим сейчас (GPS): город и координаты. Для «погода где я», «маршрут отсюда»") t@{ ctx, _ ->
            val loc = Device.location(ctx) ?: return@t "нет доступа к геолокации или координат — выдай разрешение в «Подключениях» и включи геолокацию"
            "ты сейчас: ${loc.second.ifBlank { "координаты ${"%.4f".format(loc.first.first)}, ${"%.4f".format(loc.first.second)}" }} (${"%.5f".format(loc.first.first)}, ${"%.5f".format(loc.first.second)})"
        },
        Tool("contact_add", "{\"name\": \"Вася\", \"number\": \"+7978…\"} — сохранить номер в контакты",
            confirm = { a -> "Сохранить контакт «${a.optString("name")}» — ${a.optString("number")}?" }) t@{ ctx, a ->
            val name = a.optString("name").trim(); val num = a.optString("number").trim()
            if (name.isEmpty() || num.length < 3) return@t "нужны имя и номер"
            Device.addContact(ctx, name, num)
        },
        Tool("flashlight", "{\"on\": true} — включить/выключить фонарик") t@{ ctx, a -> Device.flashlight(ctx, a.optBoolean("on", true)) },
        Tool("phone_volume", "{\"percent\": 60} — громкость 0-100%") t@{ ctx, a -> Device.volume(ctx, a.optInt("percent", 50)) },
        Tool("phone_ringer", "{\"mode\": \"normal|vibrate|silent\"} — режим звонка") t@{ ctx, a -> Device.ringer(ctx, a.optString("mode", "normal")) },
        Tool("do_not_disturb", "{\"on\": true} — режим «Не беспокоить»") t@{ ctx, a -> Device.dnd(ctx, a.optBoolean("on", true)) },
        Tool("battery", "{} — заряд батареи и состояние") t@{ ctx, _ -> Device.battery(ctx) },
        Tool("power_saver", "{} — открыть настройки энергосбережения (включить его сам Android не даёт)") t@{ ctx, _ -> Device.openBatterySettings(ctx) },
        Tool("note_add", "{\"text\": \"купить хлеб\"} — добавить заметку/напоминание в список") t@{ _, a -> Device.noteAdd(a.optString("text")) },
        Tool("note_list", "{} — показать список заметок") t@{ _, _ -> Device.noteList() },
        Tool("note_done", "{\"which\": \"1 или текст\"} — убрать заметку из списка") t@{ _, a -> Device.noteDone(a.optString("which")) },

        Tool("gh_build", "{} — статус последней сборки приложения «Тень» на GitHub (идёт / успешно / упала)") t@{ _, _ ->
            Git.lastBuild()
        },

        Tool("gh_update", "{} — скачать свежую собранную версию приложения и открыть установку (Алим подтвердит). Только по его просьбе «обнови/поставь новую версию»") t@{ ctx, _ ->
            Git.installLatest(ctx)
        },

        Tool("tg_overview", "{\"unread\": true} — что нового в ЛИЧНОМ Telegram Алима: кто писал, сколько непрочитанных. unread=true — только непрочитанные") t@{ _, a ->
            if (Tg.state != Tg.St.READY) return@t "личный Telegram не подключён — Алим включает его в ⋮ → Настройки → Telegram"
            Tg.overview(onlyUnread = a.optBoolean("unread", true))
        },

        Tool("tg_chat", "{\"chat\": \"Егор\", \"count\": 20, \"day\": \"today|yesterday|ГГГГ-ММ-ДД\"} — прочитать сообщения в чате/канале Telegram. day — за конкретный день (по местному времени); без day — последние. Прочитанным не помечает") t@{ _, a ->
            if (Tg.state != Tg.St.READY) return@t "личный Telegram не подключён"
            val q = a.optString("chat").trim(); if (q.isEmpty()) return@t "какой чат?"
            Tg.readChat(q, a.optInt("count", 20), a.optString("day"))
        },

        Tool("tg_search", "{\"query\": \"адрес склада\"} — поиск по всем перепискам личного Telegram Алима") t@{ _, a ->
            if (Tg.state != Tg.St.READY) return@t "личный Telegram не подключён"
            val q = a.optString("query").trim(); if (q.isEmpty()) return@t "что искать?"
            Tg.search(q, a.optInt("count", 15))
        },

        Tool("tg_send", "{\"chat\": \"Егор\", \"text\": \"буду через 10 минут\"} — отправить сообщение в личном Telegram Алима. ТОЛЬКО по прямой просьбе Алима. Подтверждение покажет приложение",
            confirm = { a -> "Отправить в Telegram «${a.optString("chat")}»:\n${a.optString("text")}" }) t@{ _, a ->
            if (Agent.cur().delegIntent) return@t "СТОП: это поручение — разовые сообщения тут нельзя. Вызови delegate (channel, number/chat, sim, goal с полной задачей Алима) — переговорщик сам поздоровается, будет ждать ответы и пришлёт итог."
            if (Tg.state != Tg.St.READY) return@t "личный Telegram не подключён"
            val q = a.optString("chat").trim(); val text = a.optString("text").trim()
            if (q.isEmpty() || text.isEmpty()) return@t "нужно кому и что"
            val chat = Tg.findChat(q) ?: return@t "не нашёл чат «$q» в Telegram"
            Tg.sendMessage(chat.first, text)
        },

        Tool("delegate", "{\"who\": \"Егор\", \"channel\": \"telegram|sms\", \"chat\": \"имя или @ник в Telegram (для telegram)\", \"number\": \"+7… (для sms, можно пусто — найду в контактах)\", \"sim\": \"1|2|оператор (для sms; две SIM и не сказано — спроси Алима)\", \"goal\": \"ПОЛНОЕ поручение своими словами Алима: что выяснить/о чём договориться, все его условия и рамки, факты\", \"start_at\": \"ГГГГ-ММ-ДДTЧЧ:ММ — начать позже (пусто — сразу)\", \"repeat\": \"daily 10:00 | weekdays 18:00 | weekly mon,fri 10:00 | every 2h — повторять по расписанию (пусто — один раз)\", \"until\": \"ГГГГ-ММ-ДД — до какого дня повторять\"} — ПОРУЧЕНИЕ: Тень сам ведёт переписку от имени Алима (здоровается, пишет по шагам, ждёт ответы, прикладывает фото из вложений этой задачи), пока не договорится, и присылает итог. Для «свяжись с …», «спроси у …», «договорись с …», «уточни у … и напиши мне». Канал не назван: есть Telegram-чат с человеком — telegram, иначе sms",
            confirm = { a ->
                val ch = if (a.optString("channel").startsWith("t", true)) "Telegram" else "SMS"
                val to = a.optString(if (ch == "Telegram") "chat" else "number").ifBlank { a.optString("who") }
                val att = Attach.sent.size
                val sim = a.optString("sim").takeIf { it.isNotBlank() && ch == "SMS" }?.let { " · SIM $it" }.orEmpty()
                val rep = a.optString("repeat").trim()
                val sa = Deleg.parseWhen(a.optString("start_at"))
                val wh = when { rep.isNotEmpty() -> "\nКогда: ${Deleg.humanRepeat(rep)}" + (Deleg.parseWhen(a.optString("until"))?.let { u -> ", до ${Deleg.whenText(u)}" } ?: "")
                    sa != null && sa > System.currentTimeMillis() + 60_000L -> "\nКогда: ${Deleg.whenText(sa)}"; else -> "" }
                "Поручение от твоего имени\nКому: ${a.optString("who")} · $ch ($to)$sim\nЗадача: ${a.optString("goal").take(400)}$wh" + (if (att > 0) "\nФото: $att" else "") + "\n\nПишу сам и отчитаюсь итогом."
            }) t@{ ctx, a ->
            val who = a.optString("who").trim(); val goal = a.optString("goal").trim()
            if (who.isEmpty() || goal.isEmpty()) return@t "нужно кому и что поручить"
            val startAt = Deleg.parseWhen(a.optString("start_at")) ?: 0L
            val repeat = a.optString("repeat").trim()
            val until = Deleg.parseWhen(a.optString("until"))?.let { u -> if (a.optString("until").trim().length <= 10) u + 14 * 3600_000L else u } ?: 0L
            val tg = a.optString("channel").startsWith("t", true)
            if (tg) {
                if (Tg.state != Tg.St.READY) return@t "личный Telegram не подключён — могу через SMS, если есть номер"
                val q = a.optString("chat").trim().ifBlank { who }
                val chat = Tg.findChat(q) ?: return@t "не нашёл в Telegram «$q» — спроси у Алима ник или номер"
                val words = who.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
                if (!q.startsWith("@") && words.isNotEmpty() && words.none { chat.second.lowercase().contains(it.take(4)) })
                    return@t "в Telegram нашёлся «${chat.second}» — это не похоже на «$who». Уточни у Алима"
                Deleg.start(ctx, chat.second, "tg", chat.first.toString(), goal, Attach.sent.toList(), startAt = startAt, repeat = repeat, until = until)
            } else {
                if (!has(ctx, Manifest.permission.SEND_SMS)) return@t "нет разрешения на SMS"
                val defSms = runCatching { android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1)
                if (a.optString("sim").isBlank() && defSms < 0) Device.simChoice(ctx, "")?.let { return@t "$it (потом снова вызови delegate с sim)" }
                val subId = Device.pickSim(ctx, a.optString("sim")) ?: defSms
                var num = digits(a.optString("number"))
                var name = who
                if (num.length < 5) {
                    val found = LinkedHashMap<String, String>()
                    ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null)?.use { c ->
                        while (c.moveToNext()) { val n = digits(c.getString(1)); if (n.length >= 5) found.putIfAbsent(n.takeLast(10), c.getString(0) + "|" + n) }
                    }
                    if (found.isEmpty()) return@t "не нашёл «$who» в контактах — спроси у Алима номер"
                    if (found.size > 1) return@t "в контактах несколько: " + found.values.joinToString("; ") { it.replace("|", " ") } + " — спроси у Алима, кому"
                    found.values.first().split("|").let { name = it[0]; num = it[1] }
                }
                // номер дан напрямую — подпишем человека именем из контактов, если он там есть
                if (name.any { it.isDigit() } && name.filter { it.isDigit() }.length >= 7) contactName(ctx, num)?.let { name = it }
                Deleg.start(ctx, name, "sms", num, goal, Attach.sent.toList(), subId, startAt = startAt, repeat = repeat, until = until)
            }
        },

        Tool("delegate_answer", "{\"id\": 123, \"text\": \"решение Алима\"} — передать в идущее поручение ответ/решение Алима на вопрос переговорщика или дополнение (id можно не указывать — возьму то, что ждёт ответа)") t@{ ctx, a ->
            Deleg.answer(ctx, a.optLong("id").takeIf { it > 0 }, a.optString("text").trim().ifBlank { return@t "нужен текст" })
        },

        Tool("delegate_finish", "{\"id\": 123} — завершить поручение: вежливо попрощаться с собеседником и прислать Алиму итог («заверши», «хватит, давай итог»)") t@{ ctx, a ->
            Deleg.finish(ctx, a.optLong("id").takeIf { it > 0 })
        },

        Tool("delegate_stop", "{\"id\": 123} — остановить поручение (больше не писать собеседнику)") t@{ ctx, a ->
            Deleg.stop(ctx, a.optLong("id").takeIf { it > 0 })
        },

        Tool("watch_start", "{\"topic\": \"Крымский мост\", \"what\": \"что именно отслеживать: открыт/закрыт, ограничения, пробки\", \"sources\": [\"@ник_канала\", \"https://…\", \"поисковый запрос\"], \"every_min\": 30, \"until\": \"ГГГГ-ММ-ДДTЧЧ:ММ (пусто — до конца дня)\", \"feed\": \"название или @ник Telegram-канала/группы — режим ЛЕНТЫ\"} — СЛЕЖКА. Режим ЛЕНТЫ (feed): «смотри посты в тг-группе …, присылай новое с фото/видео» — каждый новый пост приходит в чат с текстом и медиа. Иначе — СОСТОЯНИЕ: Тень сам проверяет тему каждые N минут (≥15) в нескольких источниках, первый результат присылает сразу, дальше — ТОЛЬКО при изменениях, до указанного времени. Для «проверяй каждые …», «следи за …», «сообщай, если изменится». Источники подбери сами: официальный канал + новости + поиск") t@{ ctx, a ->
            val topic = a.optString("topic").trim().ifBlank { return@t "за чем следить?" }
            val src = a.optJSONArray("sources")?.let { s -> (0 until s.length()).map { s.optString(it) } }
                ?: a.optString("sources").split(',', ';').map { it.trim() }
            val feed = a.optString("feed").trim()
            if (feed.isNotEmpty()) return@t Watch.startFeed(ctx, feed, a.optInt("every_min", 15), Deleg.parseWhen(a.optString("until")) ?: 0L)
            Watch.start(ctx, topic, a.optString("what").trim(), src, a.optInt("every_min", 60), Deleg.parseWhen(a.optString("until")) ?: 0L)
        },
        Tool("watch_stop", "{\"id\": 123, \"topic\": \"название, если номер не назван\", \"in_min\": 0} — остановить слежку («хватит следить за …», «больше не проверяй»). Назвал по имени — передай topic. «Через N минут закончи» — in_min = N (не останавливай сразу)") t@{ ctx, a ->
            Watch.stop(ctx, a.optLong("id").takeIf { it > 0 }, a.optString("topic").trim(), a.optInt("in_min", 0))
        },
        Tool("watch_list", "{} — какие слежки идут и что сейчас") { ctx, _ -> Watch.init(ctx); Watch.list() },

        Tool("delegate_list", "{} — поручения: что идёт, что ждёт Алима, итоги") { ctx, _ -> Deleg.init(ctx); Deleg.list() },

        Tool("tg_join", "{\"username\": \"@example\"} — подписаться на Telegram-канал/чат в личном Telegram Алима. Только по прямой просьбе",
            confirm = { a -> "Подписаться в Telegram на ${a.optString("username")}?" }) t@{ _, a ->
            if (Tg.state != Tg.St.READY) return@t "личный Telegram не подключён"
            val u = a.optString("username").trim(); if (u.isEmpty()) return@t "на кого подписаться?"
            Tg.join(u)
        },

        Tool("tg_media", "{\"chat\": \"Крымский ветер\", \"count\": 8, \"day\": \"today|yesterday|ГГГГ-ММ-ДД\", \"kind\": \"video|photo|all\"} — ПОКАЗАТЬ медиа из чата/паблика Telegram прямо в чате (видео играет по ▶). При «покажи видео/ролик» ставь kind=video, при «фото» — photo. Если инструмент вернул, что видео нет (только фото), ТАК и скажи, не называй фото видео. НЕ открывай приложение Telegram") t@{ _, a ->
            if (Tg.state != Tg.St.READY) return@t "личный Telegram не подключён"
            val q = a.optString("chat").trim(); if (q.isEmpty()) return@t "из какого чата?"
            val (text, paths) = Tg.chatMedia(q, a.optInt("count", 10), a.optString("day"), a.optString("kind", "all"))
            if (paths.isNotEmpty()) withContext(Dispatchers.Main) { Agent.cur().tgPhotos.clear(); Agent.cur().tgPhotos.addAll(paths) }
            text
        },

        Tool("flights", "{\"from\": \"Сочи\", \"to\": \"Стамбул\", \"date\": \"2026-11-22\", \"return_date\": \"\", \"direct\": false} — авиабилеты через API Авиасейлса: рейсы и цены на дату (ГГГГ-ММ-ДД) или на месяц (ГГГГ-ММ). Города пиши по-русски, коды подберутся сами; несколько городов прилёта или дат — через запятую. return_date — только если просят туда-обратно. direct=true — только прямые") t@{ _, a ->
            val from = a.optString("from").trim(); val to = a.optString("to").trim(); val date = a.optString("date").trim()
            if (from.isEmpty() || to.isEmpty()) return@t "откуда и куда?"
            if (date.isEmpty()) return@t "на какую дату? спроси у Алима"
            // несколько городов прилёта или дат за раз — ищем по каждому
            val tos = to.split(Regex("\\s*(,|;|\\s+и\\s+)\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
            val dates = date.split(Regex("\\s*[,;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4)
            val ret = a.optString("return_date").trim(); val direct = a.optBoolean("direct", false)
            val res = if (tos.size == 1 && dates.size == 1) Avia.search(from, tos[0], dates[0], ret, direct)
            else tos.flatMap { t -> dates.map { d -> runCatching { Avia.search(from, t, d, ret, direct) }.getOrElse { e -> "$from → $t, $d: ${e.message}" } } }
                .joinToString("\n\n")
            // Алим в Крыму, а вылет из Сочи/Краснодара — напоминаем про дорогу до аэропорта (модели забывают и пишут «6 часов»)
            val crimeaHome = Regex("ялт|севастоп|симфероп|алушт|евпатор|феодос|керч|судак|крым", RegexOption.IGNORE_CASE).containsMatchIn(Prefs.homeCity)
            if (crimeaHome && Regex("сочи|адлер|aer|краснодар|krr", RegexOption.IGNORE_CASE).containsMatchIn(from) && !res.startsWith("Аэропорт Симферополя"))
                res + "\n\nПОМНИ: Алим в ${Prefs.homeCity}. До аэропорта Сочи автобус через Крымский мост ≈15 ч (найди точное расписание web_search «автобус Ялта аэропорт Сочи расписание»). Рейс подходит, только если Алим успевает к нему с учётом этого автобуса и своих ограничений по времени — посчитай time_calc, не на глаз."
            else res
        },

        Tool("flights_open", "{\"n\": 1} — открыть рейс номер n из последнего поиска flights в приложении Авиасейлса (там покупка). Только по просьбе «открой»") t@{ ctx, a ->
            Avia.open(ctx, a.optInt("n", 1))
        },

        Tool("yt_new", "{\"days\": 7, \"favs\": true} — ПОКАЗАТЬ новые видео из подписок YouTube Алима за N дней (по умолчанию 7). favs=true — только любимые каналы (если выбраны), иначе по всем подпискам. Видео играют прямо в чате. Нужен его Google-аккаунт и VPN") t@{ _, a ->
            if (!YtMe.ready) return@t "YouTube не подключён — открой настройки → YouTube и войди в Google-аккаунт"
            val vids = withContext(Dispatchers.IO) { runCatching { YtMe.newFromSubs(a.optInt("days", 7), a.optBoolean("favs", true)) }.getOrElse { return@withContext null } }
                ?: return@t "не вышло получить видео (проверь VPN)"
            if (vids.isEmpty()) return@t "новых видео за этот период не нашёл"
            withContext(Dispatchers.Main) { Agent.cur().ytVideos.clear(); Agent.cur().ytVideos.addAll(vids.map { "${it.id}\t${it.title}\t${it.channel}" }) }
            "нашёл ${vids.size} видео из подписок:\n" + vids.joinToString("\n") { "• ${it.channel}: ${it.title}" }
        },

        Tool("yt_liked", "{\"count\": 20} — ПОКАЗАТЬ видео, которые Алим лайкнул на YouTube. Играют прямо в чате") t@{ _, a ->
            if (!YtMe.ready) return@t "YouTube не подключён — открой настройки → YouTube и войди"
            val vids = withContext(Dispatchers.IO) { runCatching { YtMe.liked(a.optInt("count", 20)) }.getOrElse { return@withContext null } }
                ?: return@t "не вышло получить лайкнутые (проверь VPN)"
            if (vids.isEmpty()) return@t "лайкнутых видео не нашёл"
            withContext(Dispatchers.Main) { Agent.cur().ytVideos.clear(); Agent.cur().ytVideos.addAll(vids.map { "${it.id}\t${it.title}\t${it.channel}" }) }
            "твои лайкнутые видео (${vids.size}):\n" + vids.joinToString("\n") { "• ${it.channel}: ${it.title}" }
        },

        Tool("yt_playlists", "{} — показать названия плейлистов Алима на YouTube") t@{ _, _ ->
            if (!YtMe.ready) return@t "YouTube не подключён"
            val pl = withContext(Dispatchers.IO) { runCatching { YtMe.playlists() }.getOrElse { return@withContext null } } ?: return@t "не вышло (проверь VPN)"
            if (pl.isEmpty()) return@t "плейлистов нет" else "Твои плейлисты:\n" + pl.joinToString("\n") { "• ${it.title}" }
        },

        Tool("yt_playlist_add", "{\"video\": \"ID_или_название\", \"playlist\": \"Посмотреть позже\"} — добавить видео в плейлист Алима на YouTube. Если видео недавно показывали в чате — бери его ID из списка; иначе спроси, какое именно") t@{ _, a ->
            if (!YtMe.ready) return@t "YouTube не подключён"
            var video = a.optString("video").trim()
            val pl = a.optString("playlist").trim(); if (pl.isEmpty()) return@t "в какой плейлист добавить?"
            if (video.isEmpty()) return@t "какое видео добавить? назови или дай ссылку"
            // если дали ссылку — вытащим id
            Regex("(?:v=|youtu\\.be/|embed/)([\\w-]{11})").find(video)?.let { video = it.groupValues[1] }
            // если это не 11-символьный id, попробуем найти среди показанных видео по названию
            if (!Regex("^[\\w-]{11}$").matches(video)) {
                val found = Agent.cur().ytVideos.map { it.split('\t') }.firstOrNull { it.getOrNull(1)?.contains(video, true) == true }?.getOrNull(0)
                if (found != null) video = found else return@t "не понял, какое видео — дай ссылку или точное название из показанных"
            }
            withContext(Dispatchers.IO) { runCatching { YtMe.addToPlaylist(pl, video) }.getOrElse { "не вышло: ${it.message}" } }
        },

        Tool("tg_channel", "{\"username\": \"ник_канала\"} — последние посты ОТКРЫТОГО Telegram-канала (ник без @). Ник не знаешь — найди через web_search") t@{ _, a ->
            val u = a.optString("username").trim().removePrefix("@").removePrefix("https://").removePrefix("t.me/").substringBefore('/').substringBefore('?')
            if (u.isEmpty()) return@t "нужен ник канала"
            val html = httpGet("https://t.me/s/$u")
            val posts = Regex("<div class=\"tgme_widget_message_text[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { clean(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
            val dates = Regex("<time datetime=\"([^\"]+)\"").findAll(html).map { SrcTime.iso(it.groupValues[1]) }.toList()
            if (posts.isEmpty()) return@t "канал @$u не найден, закрыт или без текстовых постов"
            posts.takeLast(3).mapIndexed { i, p ->
                val d = dates.getOrNull(dates.size - (posts.takeLast(3).size - i))
                SrcTime.note(d)
                "[${d?.let { SrcTime.label(it) } ?: "без даты"}] ${p.take(1200)}"
            }.joinToString("\n---\n", "Последние посты @$u (последний внизу):\n")
        },

        Tool("web_search", "{\"query\": \"…\"} — поиск в интернете, вернёт заголовки, ссылки и отрывки. Потом прочитай лучшую ссылку через web_read") t@{ _, a ->
            val q = listOf("query", "q", "text", "search", "keywords").map { a.optString(it).trim() }.firstOrNull { it.isNotEmpty() }
                ?: a.keys().asSequence().map { a.optString(it).trim() }.firstOrNull { it.length > 2 }.orEmpty()
            if (q.isEmpty()) return@t "пустой запрос — передай args как {\"query\": \"текст поиска\"}"
            val errs = ArrayList<String>()
            for (engine in listOf(YaSearch::search, ::ddgHtml, ::ddgLite, ::bing)) {
                val r = runCatching { engine(q) }.onFailure { errs += it.message ?: "ошибка" }.getOrNull()
                if (!r.isNullOrEmpty()) return@t r.take(8).joinToString("\n\n")
            }
            "поиск ничего не дал" + if (errs.isNotEmpty()) " (${errs.joinToString("; ").take(300)})" else ""
        },

        Tool("web_read", "{\"url\": \"https://…\"} — прочитать текст страницы") t@{ _, a ->
            val url = a.optString("url").trim()
            if (!url.startsWith("http")) return@t "нужна ссылка"
            val html = httpGet(url)
            val pub = SrcTime.fromHtml(html); SrcTime.note(pub)
            val body = html.replace(Regex("<(script|style|noscript)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), " ")
            (pub?.let { "[Опубликовано: ${SrcTime.label(it)}]\n" } ?: "[Дата публикации не указана]\n") +
                clean(body).replace(Regex("\\s{2,}"), " ").take(9000).ifBlank { "страница пустая" }
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
            withContext(Dispatchers.Main) { Agent.cur().photos.clear(); Agent.cur().photos.add(id) }
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

        Tool("shop_search", "{\"store\": \"ozon|wb|both\", \"query\": \"кроссовки asics gel-kayano мужские\", \"max_price\": 8000, \"seller\": \"Ozon Россия\", \"original\": true} — найти товары. seller — только у этого продавца, original=true — ТОЛЬКО если Алим сам сказал «оригинал» (у техники отметки нет). Подделки (−90%, «осталось N ед», слишком дёшево для бренда) скрываются; Алим сказал «пусть не оригинал», «любые», «подешевле» — all=true") t@{ ctx, a ->
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
                        (seller.isBlank() || Shop.sellerMatch(seller, inf.seller)) &&
                            (maxP == null || Shop.rub(it.price).let { r -> r == 0 || r <= maxP })
                    }
                }
                val ozOrig = if (orig && seller.isBlank()) oz.filter { it.original } else oz
                list = if (orig && seller.isBlank() && ozOrig.isEmpty()) {
                    notes.append("Отметки «Оригинал» нет ни у одного из $checkedN проверенных (у техники и многих вещей её не бывает) — показываю обычные варианты, скажи об этом Алиму.\n")
                    clean(oz + wbs)
                } else clean(ozOrig + if (orig && seller.isBlank()) emptyList() else wbs)
            }
            // всё отсеялось как подозрительное — лучше показать с пометкой, чем ничего
            if (list.isEmpty() && found.isNotEmpty() && seller.isBlank()) {
                list = found.take(6).onEach { if (Shop.suspect(it, q).isNotEmpty()) it.size = "⚠ подделка?" }
                notes.append("Нормальных вариантов не нашлось — показываю, что есть; помеченные ⚠ похожи на подделки (слишком дёшево или кричащие скидки). Скажи это Алиму.\n")
                hidden = 0
            }
            // «покажи ещё»: уже показанное в разговоре убираем; мало осталось — добираем со второй страницы Ozon
            if (Shop.shown.isNotEmpty() && seller.isBlank()) {
                var fresh = list.filter { it.url !in Shop.shown }
                if (fresh.size < 4 && store != "wb") runCatching { Shop.ozon(ctx, q, maxP, 2) }.onSuccess { more ->
                    fresh = fresh + clean(more).filter { m -> m.url !in Shop.shown && fresh.none { it.url == m.url } }
                }
                if (fresh.size >= 2) { if (fresh.size < list.size) notes.append("Уже показанные Алиму товары убрал — это новые варианты.\n"); list = fresh }
            }
            lastShop.clear(); lastShop += list.take(8)
            withContext(Dispatchers.Main) { Agent.cur().products.clear(); Agent.cur().products.addAll(lastShop) }
            val out = StringBuilder(notes)
            if (lastShop.isEmpty()) out.append(when {
                seller.isNotBlank() -> "У продавца «$seller» не нашёл (проверил $checkedN карточек Ozon). Попробуй другой запрос (модель, без лишних слов) или shop_open с store и query."
                orig -> "С отметкой «Оригинал» не нашёл среди $checkedN карточек."
                else -> "Ozon/WB ничего не показали. Попробуй запрос короче: бренд + тип вещи (без цвета и деталей)."
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
            val ns = a.optString("n").ifBlank { (1..lastShop.size.coerceAtMost(8)).joinToString(",") }.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.take(8)
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
                            if (size.isNotBlank()) it.size = when { r.contains("В НАЛИЧИИ") -> "$size в наличии"; r.contains("РАСКУПИЛИ") -> "$size нет в наличии"
                                r.contains("есть в размерной") -> "$size в сетке"; r.contains("не видно") -> "$size нет"; else -> "" }
                            "$n. ${it.title.take(60)}: $r; продавец ${inf?.seller?.ifBlank { "?" } ?: "?"}; ${if (inf?.original == true) "отметка «Оригинал» есть" else "отметки «Оригинал» нет"}"
                        }
                    }.getOrElse { e -> "$n. не проверил: ${e.message}" }
                } }.map { it.await() }
            }
            withContext(Dispatchers.Main) { val cp = Agent.cur().products.toList(); Agent.cur().products.clear(); Agent.cur().products.addAll(cp) }
            res.joinToString("\n")
        },

        Tool("youtube_search", "{\"query\": \"обзор asics gel-kayano 33\", \"period\": \"\" | \"today\" | \"week\" | \"month\"} — найти ролики на YouTube (карточки с превью, нажатие открывает YouTube)") t@{ _, a ->
            val q = a.optString("query").trim()
            if (q.isEmpty()) return@t "нужен запрос"
            val l = YouTube.search(q, a.optString("period"))
            if (l.isEmpty()) return@t "ничего не нашёл"
            lastVideos.clear(); lastVideos += l; taskVideos += l
            withContext(Dispatchers.Main) { Agent.cur().products.clear(); Agent.cur().products.addAll(l.map { it.item() }) }
            "Нашёл ${l.size}:\n" + YouTube.format(l) + "\nКарточки на экране. Открыть ролик — youtube_open с номером."
        },

        Tool("youtube_open", "{\"n\": 1} или {\"query\": \"…\"} — открыть найденный ролик или поиск в приложении YouTube") t@{ ctx, a ->
            val url = lastVideos.getOrNull(a.optInt("n", 0) - 1)?.url
                ?: a.optString("query").takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/results?search_query=" + URLEncoder.encode(it, "UTF-8") }
                ?: return@t "нужен номер или запрос"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (launch(ctx, Intent(i).setPackage(YouTube.PKG)) || launch(ctx, i)) "открыл" else "не открылось"
        },

        Tool("maps_route", "{\"to\": \"Ливадийский дворец\", \"from\": \"\", \"mode\": \"auto\"} — маршрут в Яндекс Картах. from пусто — от того места, где Алим сейчас. mode: auto машина, mt общественный транспорт, pd пешком, taxi такси") t@{ ctx, a ->
            val to = a.optString("to").trim()
            if (to.isEmpty()) return@t "куда?"
            val from = a.optString("from").trim()
            val mode = a.optString("mode", "auto").lowercase().let { if (it in setOf("auto", "mt", "pd", "taxi", "bc")) it else "auto" }
            val dst = Geo.find(to)
            val src = if (from.isNotBlank()) Geo.find(from) else null
            val rtext = (src?.ll ?: from) + "~" + (dst?.ll ?: to)
            val q = "rtext=" + URLEncoder.encode(rtext, "UTF-8") + "&rtt=$mode"
            val ok = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?$q")).setPackage(Geo.MAPS_PKG)) ||
                launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?$q")))
            val eta = if (src != null && dst != null && mode != "mt") Geo.eta(src, dst, mode == "pd") else ""
            buildString {
                append(if (ok) "открыл маршрут в Яндекс Картах" else "Карты не открылись")
                if (dst != null) append("; куда: ${dst.name.take(140)}") else append("; точный адрес «$to» не нашёл — Карты поищут сами")
                if (eta.isNotBlank()) append("; $eta")
            }
        },

        Tool("maps_search", "{\"query\": \"аптека рядом\"} — найти места в Яндекс Картах (кафе, аптеки, адрес)") t@{ ctx, a ->
            val q = a.optString("query").trim()
            if (q.isEmpty()) return@t "что искать?"
            val e = URLEncoder.encode(q, "UTF-8")
            val ok = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?text=$e")).setPackage(Geo.MAPS_PKG)) ||
                launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?text=$e")))
            if (ok) "открыл поиск «$q» в Яндекс Картах" else "Карты не открылись"
        },

        Tool("file_create", "{\"type\": \"docx\", \"name\": \"Список дел\", \"text\": \"# Заголовок\\n## Раздел\\n- пункт\\n**жирный**\\n| Колонка | Колонка |\\n| a | b |\"} или {\"type\": \"xlsx\", \"name\": \"Бюджет\", \"sheets\": [{\"name\": \"Октябрь\", \"rows\": [[\"Статья\", \"Сумма\"], [\"Еда\", 15000], [\"Итого\", \"=SUM(B2:B2)\"]]}]} — создать документ Word или таблицу Excel (формулы через =); {\"type\": \"pdf\", \"name\": …, \"text\": та же разметка, что у docx} — PDF. Сохраняется в Загрузки/Свои, карточка на экране") t@{ ctx, a ->
            // модели (Alice Flash) иногда кладут sheets строкой или лист — строкой JSON: чиним, а не падаем непонятной ошибкой
            (a.opt("sheets") as? String)?.let { s -> runCatching { a.put("sheets", org.json.JSONArray(s)) } }
            if (!a.has("sheets") && a.has("rows")) a.put("sheets", org.json.JSONArray().put(org.json.JSONObject().put("name", "Лист1").put("rows", a.opt("rows"))))
            a.optJSONArray("sheets")?.let { sh -> for (k in 0 until sh.length()) (sh.opt(k) as? String)?.let { s -> runCatching { sh.put(k, org.json.JSONObject(s)) } } }
            val type = a.optString("type").lowercase().ifBlank { if (a.has("sheets") || a.has("rows")) "xlsx" else "docx" }
            // «≈12 350 ₽», «16 000», «~3500 руб» — это числа: пишем числом, иначе формулы «Итог» дают 0
            var textNums = 0; val badRefs = ArrayList<String>()
            if (type == "xlsx") a.optJSONArray("sheets")?.let { sh -> for (k in 0 until sh.length()) sh.optJSONObject(k)?.optJSONArray("rows")?.let { rows ->
                for (r in 1 until rows.length()) rows.optJSONArray(r)?.let { row -> for (c in 0 until row.length()) (row.opt(c) as? String)?.let { v ->
                    val m = Regex("^\\s*[≈~±]?\\s*(\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+|\\d+)(?:[.,](\\d+))?\\s*(₽|руб\\.?|р\\.?)?\\s*$").find(v)
                    if (m != null && !v.startsWith("=")) { row.put(c, (m.groupValues[1].replace(Regex("\\D"), "") + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")).toDouble()); textNums++ }
                } }
                // формулы, которые ссылаются на ячейки с текстом вроде «≈10 500 ₽ (Арт-Отель)», — посчитают 0
                for (r in 1 until rows.length()) rows.optJSONArray(r)?.let { row -> for (c in 0 until row.length()) (row.opt(c) as? String)?.takeIf { it.startsWith("=") }?.let { f ->
                    Regex("([A-Z]{1,2})(\\d+)").findAll(f.uppercase()).forEach { ref ->
                        val ci = ref.groupValues[1].fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
                        val ri = ref.groupValues[2].toInt() - 1
                        val tv = rows.optJSONArray(ri)?.opt(ci)
                        if (tv is String && !tv.startsWith("=") && Regex("\\d").containsMatchIn(tv)) badRefs += ref.value
                    }
                } }
            } }
            // строка «Итого/Всего», где число равно сумме чисел над ней, — делаем формулой =SUM(): поправил цену в таблице — итог пересчитался
            if (type == "xlsx") a.optJSONArray("sheets")?.let { sh -> for (k in 0 until sh.length()) sh.optJSONObject(k)?.optJSONArray("rows")?.let { rows ->
                for (r in 2 until rows.length()) {
                    val row = rows.optJSONArray(r) ?: continue
                    val lab = (row.opt(0) as? String)?.trim()?.lowercase().orEmpty()
                    if (!(lab.startsWith("итого") || lab.startsWith("всего") || lab.startsWith("итог "))) continue
                    for (c in 1 until minOf(row.length(), 26)) {
                        val v = (row.opt(c) as? Number)?.toDouble() ?: continue
                        var sum = 0.0; var cnt = 0; var first = r
                        for (q in r - 1 downTo 1) { val x = rows.optJSONArray(q)?.opt(c) as? Number ?: break; sum += x.toDouble(); cnt++; first = q }
                        if (cnt >= 2 && kotlin.math.abs(sum - v) < 0.5) {
                            val col = 'A' + c
                            row.put(c, "=SUM($col${first + 1}:$col$r)")
                        }
                    }
                }
            } }
            // «=15360+15840» → «=D2+D5», числа листа «Итог» → ссылки на ячейки других листов: поправил цену — пересчиталось везде
            if (type == "xlsx") runCatching { linkTotals(a.optJSONArray("sheets")) }
            // сводка против листов-деталей: «Итог» по Москве обязан совпасть с «ИТОГО» листа «Москва» (Sonnet забывал обратный рейс в сводке)
            if (type == "xlsx" && !a.optBoolean("force")) runCatching { summaryMismatch(a.optJSONArray("sheets")) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { m ->
                return@t "ошибка сводки: ${m.joinToString("; ")}. Сводка не сходится с листами по городам — в ней пропущен участок или посчитан дважды. " +
                    "Сделай итог сводки ссылкой на ИТОГО листа (='Москва'!D7) и пересоздай файл (тот же name). Если расхождение задумано — добавь \"force\": true."
            }
            // таблица из одних заголовков — не сохраняем и не даём сказать «таблица готова»
            if (type == "xlsx") a.optJSONArray("sheets")?.let { sh ->
                val dataRows = (0 until sh.length()).sumOf { k -> ((sh.optJSONObject(k)?.optJSONArray("rows")?.length() ?: 0) - 1).coerceAtLeast(0) }
                if (dataRows == 0) return@t "ошибка: в таблице только заголовки, строк с данными нет. Заполни строки найденными данными (по строке на вариант) и повтори file_create."
            }
            val name = a.optString("name").ifBlank { if (type == "xlsx") "Таблица" else "Документ" }
            // «[[рисунок N]]» в PDF — снимаем рисунки этой задачи на светлой бумаге
            val pics = HashMap<Int, android.graphics.Bitmap>()
            if (type == "pdf") Regex("\\[\\[\\s*рисунок\\s*(\\d+)\\s*]]", RegexOption.IGNORE_CASE).findAll(a.optString("text")).map { it.groupValues[1].toInt() }.distinct().forEach { n ->
                Viz.task.ifEmpty { Agent.cur().files.filter { it.mime == Viz.MIME } }.getOrNull(n - 1)?.let { r -> Viz.shoot(ctx, Viz.wrap(Viz.read(r), paper = true)).bmp?.let { pics[n] = it } }
            }
            val ref = if (type == "pdf") Docs.save(ctx, name, Docs.PDF, runCatching { Pdf.create(a.optString("text").ifBlank { return@t "нужен text" }, pics) }.getOrElse { e -> return@t "PDF не собрался: ${e.message?.take(80)}" })
                else if (type == "xlsx") Docs.save(ctx, name, Docs.XLSX, runCatching { Docs.xlsx(a) }.getOrElse { e ->
                    return@t "ошибка формата таблицы (${e.message?.take(80)}). Нужно: \"sheets\": [{\"name\": \"Сравнение\", \"rows\": [[\"Город\", \"Цена\"], [\"Казань\", 12000]]}] — массив объектов, rows — массив массивов. Повтори file_create в этом формате." })
                else Docs.save(ctx, name, Docs.DOCX, Docs.docx(a.optString("text").ifBlank { return@t "нужен text" }))
            withContext(Dispatchers.Main) {
                // пересобрал тот же файл в этой задаче — старую карточку убираем, остаётся одна, свежая
                val base = ref.name.substringBeforeLast('.').replace(Regex("\\s*\\(\\d+\\)$"), "")
                Agent.cur().files.removeAll { f ->
                    val b = f.name.substringBeforeLast('.').replace(Regex("\\s*\\(\\d+\\)$"), "")
                    f.name.substringAfterLast('.') == ref.name.substringAfterLast('.') && b.length >= 6 && (b == base || base.startsWith(b) || b.startsWith(base))
                }
                Agent.cur().files.add(0, ref)
            }
            // итоги по формулам считаем сами и отдаём модели: суммы в ответе должны совпасть с таблицей (Sonnet написала 40 325 вместо 39 775)
            val sums = if (type == "xlsx") runCatching { formulaTotals(a.optJSONArray("sheets")) }.getOrDefault("") else ""
            "создал «${ref.name}» в Загрузки/Свои — карточка на экране, нажатием открывается, там же «Отправить»." +
                (if (sums.isNotBlank()) "\nФормулы в таблице дают (именно эти суммы пиши в ответе и карточке):\n$sums" else "") +
                (if (badRefs.isNotEmpty()) "\nВНИМАНИЕ: формулы ссылаются на ячейки с ТЕКСТОМ (${badRefs.distinct().take(6).joinToString()}) — Excel посчитает их как 0, итог неверный. Пересоздай файл (тот же name): числа (цены) — отдельными столбцами только числом, пояснения — в соседнем столбце." else "")
        },

        Tool("file_read", "{\"n\": 1, \"from\": \"attachment\" | \"made\"} — прочитать .docx/.xlsx/.pdf (из вложений Алима или из файлов, сделанных агентом), чтобы ответить по ним или поправить") t@{ ctx, a ->
            val n = a.optInt("n", 1)
            val att = Attach.sent.filter { it.name.endsWith(".docx", true) || it.name.endsWith(".xlsx", true) || it.name.endsWith(".pdf", true) }
            val useMade = a.optString("from").lowercase() == "made" || att.isEmpty()
            val (uri, name) = if (useMade) Docs.made.getOrNull(n - 1)?.let { it.uri to it.name } ?: return@t "файлов Word/Excel нет"
                else att.getOrNull(n - 1)?.let { it.uri to it.name } ?: return@t "нет такого вложения"
            val t = Docs.read(ctx, uri, name)
            "Файл «$name»:\n${t.take(14000)}\n\n" + if (name.endsWith(".pdf", true)) "Чтобы изменить PDF — pdf_edit (замена текста на месте, вписать, поля формы, страницы); переписать целиком — file_create type pdf. Оригинал не тронется."
                else "Чтобы изменить — file_create с тем же type и исправленным содержимым целиком (новый файл рядом, оригинал не тронется)."
        },

        Tool("draw", "{\"title\": \"Выручка пивоваров 2025\", \"html\": \"<svg …>…</svg><script>…</script>\"} — нарисовать живую картинку в чате: график, диаграмму, схему, шкалу времени, " +
            "мини-калькулятор с ползунками, сравнение с сортировкой. Страница без интернета и библиотек: чистые SVG/canvas/HTML/JS. Ширина экрана ~360–400px, высота карточки ~320px (больше — откроют на весь экран). " +
            "Цвета ТОЛЬКО из темы: CSS var(--ink) текст, var(--muted) подписи, var(--line) сетка, var(--bg) фон, var(--s1)…var(--s5) серии, var(--r) скругление; в JS — window.T.ink/.muted/.line/.series[]. " +
            "SVG — всегда viewBox=\"0 0 360 H\" и width=\"100%\" (не фиксированные px); шкала от 0 до max×1,1, слева и снизу поля под подписи, легенда не наезжает. Что вылезло за край — Тень вернёт ошибкой. " +
            "Подсказки по тапу (наведения на телефоне нет). Подписи по-русски, числа с пробелами (40 100). Вставить в PDF — строкой [[рисунок N]] в text у file_create type pdf") t@{ ctx, a ->
            val html = a.optString("html").ifBlank { return@t "нужен html" }
            if (Regex("<script[^>]+src=|<link[^>]+href=\\s*[\"']?https?:|@import", RegexOption.IGNORE_CASE).containsMatchIn(html))
                return@t "ошибка: внешние библиотеки и шрифты не загрузятся (интернета у рисунка нет). Перепиши на чистом SVG/canvas/JS и повтори draw."
            val title = a.optString("title").ifBlank { "Рисунок" }
            val shot = Viz.shoot(ctx, Viz.wrap(html), needBitmap = false)
            if (shot.errors.isNotEmpty()) return@t "рисунок с ошибками, на экран не вывел: ${shot.errors.distinct().take(4).joinToString("; ")}. Исправь и повтори draw целиком."
            val ref = Viz.save(ctx, title, html)
            withContext(Dispatchers.Main) {
                // перерисовал тот же рисунок — старую карточку убираем
                Agent.cur().files.removeAll { it.mime == Viz.MIME && it.name == ref.name }
                Agent.cur().files.add(ref)
            }
            Viz.task.removeAll { it.name == ref.name }; Viz.task.add(ref)
            "нарисовал «$title» — живая карточка на экране (рисунок ${Viz.task.size} в этой задаче; в PDF — строка [[рисунок ${Viz.task.size}]]). Высота страницы ${shot.heightCss}px" +
                (if (shot.heightCss > 420) " — выше карточки, в ленте видна верхняя часть; если главное не наверху — сделай компактнее." else ".") +
                " В ответе не описывай рисунок подробно — он на экране; дай вывод словами."
        },

        Tool("pdf_edit", "{\"n\": 1, \"from\": \"attachment\" | \"made\", \"name\": \"Договор исправленный\", \"ops\": [{\"op\": \"replace\", \"from\": \"старый текст\", \"to\": \"новый\"}, " +
            "{\"op\": \"add_text\", \"page\": 1, \"x\": 20, \"y\": 250, \"text\": \"Подпись: ____\", \"size\": 11}, {\"op\": \"fill\", \"fields\": {\"ФИО\": \"…\"}}, " +
            "{\"op\": \"delete_pages\", \"pages\": [3]}, {\"op\": \"extract\", \"pages\": [1,2]}, {\"op\": \"rotate\", \"pages\": [2], \"deg\": 90}, {\"op\": \"reorder\", \"order\": [2,1,3]}, " +
            "{\"op\": \"merge\", \"with\": [2]}, {\"op\": \"watermark\", \"text\": \"КОПИЯ\"}, {\"op\": \"cover\", \"page\": 1, \"x\": 20, \"y\": 40, \"w\": 60, \"h\": 8}]} — поправить PDF: " +
            "замена текста прямо на странице, вписать текст (x, y — мм от левого верхнего угла; без y — внизу), заполнить поля формы, удалить/оставить/повернуть/переставить страницы, склеить с другими вложенными PDF (with — номера вложений), водяной знак, закрыть область. Результат — новый файл, оригинал цел") t@{ ctx, a ->
            val n = a.optInt("n", 1)
            val att = Attach.sent.filter { it.name.endsWith(".pdf", true) || it.mime == Docs.PDF }
            val made = Docs.made.filter { it.mime == Docs.PDF }
            val useMade = a.optString("from").lowercase() == "made" || att.isEmpty()
            val (uri, name) = if (useMade) made.getOrNull(n - 1)?.let { it.uri to it.name } ?: return@t "PDF-файлов нет: пришли PDF вложением или сделай его file_create type pdf"
                else att.getOrNull(n - 1)?.let { it.uri to it.name } ?: return@t "нет такого PDF во вложениях (их ${att.size})"
            val ops = a.optJSONArray("ops") ?: (a.opt("ops") as? String)?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
                ?: a.optString("op").takeIf { it.isNotBlank() }?.let { org.json.JSONArray().put(a) } ?: return@t "нужен ops — список действий"
            val (bytes, log) = runCatching { Pdf.edit(ctx, uri, ops, att.map { it.uri }) }.getOrElse { e -> return@t "PDF не открылся для правки: ${e.message?.take(100)}" }
            val out = a.optString("name").ifBlank { name.substringBeforeLast('.') + " (правка)" }
            val ref = Docs.save(ctx, out, Docs.PDF, bytes)
            withContext(Dispatchers.Main) { Agent.cur().files.add(0, ref) }
            val check = runCatching { Pdf.text(ctx, ref.uri, 6) }.getOrDefault("").take(3000)
            "сделал «${ref.name}» в Загрузки (карточка на экране). Что сделано:\n$log" + (if (check.isNotBlank()) "\n\nТекст результата для проверки (первые страницы):\n$check" else "")
        },

        Tool("file_send", "{\"n\": 1, \"app\": \"telegram|whatsapp|max|any\"} — отправить файл, сделанный агентом",
            confirm = { a -> "Отправить файл «${Docs.made.getOrNull(a.optInt("n", 1) - 1)?.name ?: "?"}» в ${a.optString("app", "приложение")}? Там выберешь чат." }) t@{ ctx, a ->
            val f = Docs.made.getOrNull(a.optInt("n", 1) - 1) ?: return@t "файлов нет"
            val i = Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, f.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pkg = installed(ctx, a.optString("app"))
            val ok = if (pkg != null) launch(ctx, i.setPackage(pkg)) else launch(ctx, Intent.createChooser(i, "Отправить"))
            if (ok) "открыл отправку «${f.name}»" else "не получилось открыть отправку"
        },

        Tool("play_open", "{\"query\": \"Яндекс Go\"} или {\"package\": \"ru.yandex.taxi\"} — Google Play: найти приложение или открыть его страницу (установить/обновить Алим нажмёт сам)") t@{ ctx, a ->
            val pkg = a.optString("package").trim(); val q = a.optString("query").trim()
            val (m, w) = when {
                pkg.isNotBlank() -> "market://details?id=$pkg" to "https://play.google.com/store/apps/details?id=$pkg"
                q.isNotBlank() -> URLEncoder.encode(q, "UTF-8").let { "market://search?q=$it&c=apps" to "https://play.google.com/store/search?q=$it&c=apps" }
                else -> return@t "что искать?"
            }
            if (launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(m)).setPackage("com.android.vending")) || launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(w))))
                "открыл Google Play" else "Google Play не открылся"
        },

        Tool("kion_open", "{\"query\": \"Нюрнберг\"} — найти фильм или сериал в KION и открыть; без query — просто открыть KION") t@{ ctx, a ->
            val pm = ctx.packageManager
            val kion = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .firstOrNull { it.loadLabel(pm).toString().contains("kion", true) || it.activityInfo.packageName.contains("mtstv") }?.activityInfo?.packageName
            val q = a.optString("query").trim()
            val openApp: suspend () -> Boolean = { kion?.let { pm.getLaunchIntentForPackage(it) }?.let { launch(ctx, it) } ?: false }
            if (q.isEmpty()) return@t if (openApp()) "открыл KION" else "KION не установлен"
            val link = runCatching { YaSearch.search("site:kion.ru $q") }.getOrDefault(emptyList())
                .firstNotNullOfOrNull { Regex("https?://(www\\.)?kion\\.ru/[^\\s]+").find(it)?.value }
            if (link != null) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                if (kion != null && launch(ctx, Intent(i).setPackage(kion))) return@t "открыл «$q» в KION: $link"
                if (launch(ctx, i)) return@t "открыл «$q» на сайте KION (приложение ссылку не приняло): $link"
            }
            if (openApp()) "поиском «$q» в KION не нашёл — открыл приложение, найди там" else "KION не установлен и «$q» не нашёл"
        },

        Tool("ask_main", "{\"task\": \"позвони маме и скажи, что задержусь\"} — передать главному просьбу Алима: звонки, сообщения, SMS, фото, переводы, будильники и любые действия в телефоне делает он") t@{ _, a ->
            val core = Agent.cur()
            if (core.isMain) return@t "ты и есть главный — делай сам"
            val task = a.optString("task").trim().ifBlank { return@t "что передать?" }
            Chat.main.delegate(core.fid, task)
            "передал ${Friends.main.name}; он сделает и отпишется сюда заметкой"
        },

        Tool("yoomoney_transfer", "{\"to\": \"+79219990099\", \"amount\": 500, \"comment\": \"за обед\"} — подготовить перевод с кошелька ЮMoney на кошелёк ЮMoney по номеру телефона (СБП в банки не поддерживается). Только если Алим сам в чате написал, кому и сколько. Номер — через contacts_find") t@{ ctx, a ->
            val core = Agent.cur()
            if (!core.isMain || core.delegated) return@t "переводы — только по прямой просьбе Алима в чате с ${Friends.main.name}"
            val amount = a.optDouble("amount", 0.0)
            if (amount <= 0) return@t "нужна сумма"
            val whole = amount.toLong().toString()
            if (!core.task.replace(Regex("[\\s  ]"), "").contains(whole))
                return@t "сумму $whole Алим в сообщении не называл — спроси у него сумму, сам не придумывай"
            val to = a.optString("to").trim().ifBlank { return@t "кому? нужен номер телефона" }
            Yoo.prepare(ctx, to, amount, a.optString("comment"), contactName(ctx, to))
        },

        Tool("yoomoney_send", "{} — отправить подготовленный перевод (приложение покажет карточку с получателем и суммой и спросит отпечаток пальца)",
            confirm = { _ -> Yoo.pendingText() }, secure = true) t@{ ctx, _ ->
            val core = Agent.cur()
            if (!core.isMain || core.delegated) return@t "нельзя"
            Yoo.send(ctx)
        },

        Tool("hotels", "{\"city\": \"Нижний Новгород\", \"checkin\": \"2026-12-17\", \"checkout\": \"2026-12-20\", \"guests\": 1} — отели на даты сразу на двух сайтах: Островок и Яндекс Путешествия — с ценами и ссылками. Один и тот же отель на разных сайтах стоит по-разному — сравни и назови, где дешевле") t@{ ctx, a ->
            val city = (a.optString("city").ifBlank { a.optString("q") }).trim()
            if (city.isEmpty()) return@t "нужен city"
            fun day(k: String) = a.optString(k).trim().let { s -> runCatching { java.time.LocalDate.parse(s.take(10)) }.getOrNull() ?: runCatching { Device.parseWhenAt(s, java.time.LocalDateTime.now())?.toLocalDate() }.getOrNull() }
            var ci = day("checkin") ?: day("from") ?: return@t "нужен checkin как ГГГГ-ММ-ДД"
            var co = day("checkout") ?: day("to") ?: ci.plusDays(1)
            // модель поставила прошедший год («20–22 февраля» → 2026-02-20) — сайты на прошлые даты цен не дают: берём ближайшие будущие
            val today = java.time.LocalDate.now()
            while (ci.isBefore(today)) { ci = ci.plusYears(1); co = co.plusYears(1) }
            if (!co.isAfter(ci)) co = ci.plusDays(1)
            val g = a.optInt("guests", a.optInt("adults", 1)).coerceIn(1, 6)
            Hotels.all(ctx, city, ci, co, g) { q -> listOf(YaSearch::search, ::ddgHtml, ::ddgLite, ::bing).firstNotNullOfOrNull { e -> runCatching { e(q) }.getOrNull()?.takeIf { it.isNotEmpty() } }.orEmpty() }
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
    fun describe(only: Set<String>? = null) = list.filter { if (only == null) it.name != "ask_main" else it.name in only }
        .joinToString("\n") { "- ${it.name} ${it.help}" }

    private fun pick(which: String): List<Long> {
        val w = which.trim().lowercase()
        if (w.isEmpty() || w == "all" || w == "last" || w == "все") return lastPhotos.toList()
        return w.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.mapNotNull { lastPhotos.getOrNull(it - 1) }.distinct()
    }

    /** Срок из просьбы Алима («вернуться до 8:00 утра 20 июня») — time_calc сверяет с ним сам, не полагаясь на модель. */
    /** Считает формулы таблицы (числа, ссылки A1 и Лист!A1, SUM, + - * /, скобки) — «Итог!C7 «ИТОГО» = 39 775». */
    fun formulaTotals(sheets: org.json.JSONArray?): String {
        if (sheets == null) return ""
        val fe = FormulaEval(sheets)
        return buildString {
            var n = 0
            for (sn in fe.order) { val rows = fe.grid[sn.lowercase()] ?: continue
                for (r in 0 until rows.length()) { val row = rows.optJSONArray(r) ?: continue
                    for (c in 0 until row.length()) { val f = row.opt(c) as? String ?: continue
                        if (!f.startsWith("=") || n >= 14) continue
                        val v = fe.cell(sn.lowercase(), c, r) ?: continue
                        val lab = (row.opt(0) as? String)?.take(40).orEmpty()
                        append("$sn!${'A' + c}${r + 1}").append(if (lab.isNotBlank()) " «$lab»" else "").append(" = ")
                            .append(Math.round(v).toString().reversed().chunked(3).joinToString(" ").reversed()).append('\n'); n++
                    } } }
        }.trim()
    }

    /** Сверка присланной таблицы без модели: формулы, итоговые строки, числа сводки против строк других листов. */
    fun reviewXlsx(sheets: org.json.JSONArray?): String {
        if (sheets == null || sheets.length() == 0) return ""
        val fe = FormulaEval(sheets)
        fun col(c: Int): String = if (c < 26) "${'A' + c}" else "${'A' + (c / 26 - 1)}${'A' + c % 26}"
        fun f(v: Double) = fmtNum(v)
        val names = fe.order
        fun rowsOf(k: Int) = sheets.optJSONObject(k)?.optJSONArray("rows") ?: org.json.JSONArray()
        fun lab(row: org.json.JSONArray?) = (row?.opt(0) as? String)?.trim().orEmpty()
        val ok = ArrayList<String>(); val bad = ArrayList<String>(); val calc = ArrayList<String>()
        // 1) формулы: что дают, отрицательные итоги
        for ((k, sn) in names.withIndex()) { val rows = rowsOf(k)
            for (r in 0 until rows.length()) { val row = rows.optJSONArray(r) ?: continue
                for (c in 0 until row.length()) { val fm = row.opt(c) as? String ?: continue
                    if (!fm.startsWith("=")) continue
                    val v = fe.cell(sn.lowercase(), c, r)
                    val head = (rows.optJSONArray(0)?.opt(c) as? String)?.take(30) ?: (rows.optJSONArray(1)?.opt(c) as? String)?.take(30)
                    val where = "$sn!${col(c)}${r + 1}" + (if (lab(row).isNotBlank()) " «${lab(row).take(30)}»" else "") + (if (!head.isNullOrBlank()) " [${head}]" else "")
                    if (v == null) { bad += "$where: формула $fm не считается (ошибка или ссылка на текст)"; continue }
                    if (calc.size < 30) calc += "$where $fm = ${f(v)}"
                    if (v < 0) bad += "$where = ${f(v)} — отрицательно (перерасход/минус)"
                } } }
        // 2) строки «итого/всего» с числом: совпадает ли с суммой чисел выше
        for ((k, sn) in names.withIndex()) { val rows = rowsOf(k)
            for (r in 2 until rows.length()) { val row = rows.optJSONArray(r) ?: continue
                val l = lab(row).lowercase()
                if (!(l.startsWith("итог") || l.startsWith("всего") || l.startsWith("сумма"))) continue
                for (c in 1 until row.length()) {
                    val v = fe.cell(sn.lowercase(), c, r) ?: continue
                    var s = 0.0; var n = 0
                    for (q in r - 1 downTo 1) { val x = fe.cell(sn.lowercase(), c, q) ?: break; s += x; n++ }
                    if (n < 2) continue
                    if (kotlin.math.abs(s - v) < 0.5) ok += "$sn!${col(c)}${r + 1} «${lab(row)}» = ${f(v)} — равно сумме $n строк выше"
                    else bad += "$sn!${col(c)}${r + 1} «${lab(row)}» = ${f(v)}, а сумма $n строк выше = ${f(s)} (разница ${f(v - s)})"
                } } }
        // 3) строки с одинаковым названием на других листах (детали) против чисел строки сводки
        data class Det(val sheet: String, val label: String, val text: String, val vals: List<Double>)
        val dets = ArrayList<Det>()
        val summaryLabels = HashMap<Int, List<Pair<Int, String>>>()
        for ((k, _) in names.withIndex()) { val rows = rowsOf(k)
            summaryLabels[k] = (1 until rows.length()).mapNotNull { r -> lab(rows.optJSONArray(r)).takeIf { it.length in 2..60 }?.let { r to it } } }
        for ((k, sn) in names.withIndex()) { val rows = rowsOf(k)
            for (r in 1 until rows.length()) { val row = rows.optJSONArray(r) ?: continue
                val l = lab(row); if (l.isBlank()) continue
                val vals = (1 until row.length()).mapNotNull { c -> if (row.opt(c) is Number || (row.opt(c) as? String)?.startsWith("=") == true) fe.cell(sn.lowercase(), c, r) else null }.filter { it >= 100 }
                val txt = (1 until minOf(row.length(), 4)).mapNotNull { c -> (row.opt(c) as? String)?.takeIf { !it.startsWith("=") && it.length > 3 } }.joinToString(", ").take(70)
                dets += Det(sn, l, txt, vals)
            } }
        val sourced = HashSet<String>()
        for ((k, sn) in names.withIndex()) { val rows = rowsOf(k)
            val myLabels = summaryLabels[k].orEmpty().map { it.second.lowercase() }
            for ((r, l0) in summaryLabels[k].orEmpty()) {
                val l = l0.lowercase(); val row = rows.optJSONArray(r) ?: continue
                // детали других листов: точное имя или «<имя> — …», но не те, у которых в этой сводке есть своя строка точнее
                val group = dets.filter { d -> d.sheet != sn && d.vals.size == 1 && run {
                    val dl = d.label.lowercase()
                    (dl == l || dl.startsWith("$l ") || dl.startsWith("$l—") || dl.startsWith("$l -")) &&
                        myLabels.none { o -> o != l && o.length > l.length && (dl == o || dl.startsWith("$o ")) } } }
                if (group.size < 2) continue
                val gv = group.map { it.vals[0] }; val total = gv.sum()
                val cells = (1 until row.length()).mapNotNull { c -> if (row.opt(c) is Number) c to (row.opt(c) as Number).toDouble() else null }.filter { it.second >= 100 }
                val hit = cells.firstOrNull { kotlin.math.abs(it.second - total) < 0.5 }
                if (hit != null) { ok += "$sn!${col(hit.first)}${r + 1} «$l0» = ${f(hit.second)} — сходится с суммой ${group.size} строк листа «${group[0].sheet}»"; sourced += "$k:${hit.first}:$r"; continue }
                // число сводки = сумма части строк → какие строки забыты
                if (gv.size <= 12) for ((c, v) in cells) {
                    var found = -1
                    for (mask in 1 until (1 shl gv.size)) { if (Integer.bitCount(mask) < 1 || mask == (1 shl gv.size) - 1) continue
                        if (kotlin.math.abs(gv.indices.filter { mask shr it and 1 == 1 }.sumOf { gv[it] } - v) < 0.5) { found = mask; break } }
                    if (found > 0) {
                        val miss = group.indices.filter { found shr it and 1 == 0 }
                        bad += "$sn!${col(c)}${r + 1} «$l0» = ${f(v)}, но на листе «${group[0].sheet}» у «$l0» ${group.size} строк на ${f(total)}; не учтено: " +
                            miss.joinToString("; ") { "${group[it].text.ifBlank { group[it].label }} — ${f(gv[it])}" } + " (должно быть ${f(total)}, разница ${f(total - v)})"
                        sourced += "$k:$c:$r"; break
                    }
                }
            }
        }
        // 4) числа-константы, которых нет больше нигде в файле — не подкреплены
        val allVals = HashMap<Long, Int>()
        for ((k, sn) in names.withIndex()) { val rows = rowsOf(k)
            for (r in 0 until rows.length()) { val row = rows.optJSONArray(r) ?: continue
                for (c in 0 until row.length()) { val v = fe.cell(sn.lowercase(), c, r) ?: continue; if (v >= 100) allVals.merge(Math.round(v), 1) { x, y -> x + y } }
                // числа внутри текста («от 6 175 ₽») тоже считаем подтверждением
                for (c in 0 until row.length()) { val s = row.opt(c) as? String ?: continue; if (s.startsWith("=")) continue
                    Regex("\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+|\\d{3,}").findAll(s).forEach { m -> m.value.filter(Char::isDigit).toLongOrNull()?.let { allVals.merge(it, 1) { x, y -> x + y } } } }
            } }
        val lone = ArrayList<String>()
        for ((k, sn) in names.withIndex()) { val rows = rowsOf(k)
            for (r in 1 until rows.length()) { val row = rows.optJSONArray(r) ?: continue
                for (c in 1 until row.length()) { val v = (row.opt(c) as? Number)?.toDouble() ?: continue
                    if (v < 100 || "$k:$c:$r" in sourced) continue
                    if ((allVals[Math.round(v)] ?: 0) <= 1) lone += "$sn!${col(c)}${r + 1}" + (if (lab(row).isNotBlank()) " «${lab(row).take(25)}»" else "") + " = ${f(v)}"
                } } }
        return buildString {
            append("СВЕРКА ТАБЛИЦЫ (посчитала Тень сама, точно — опирайся на неё, цифры бери отсюда):\n")
            if (calc.isNotEmpty()) append("Формулы дают:\n").append(calc.joinToString("\n")).append('\n')
            if (ok.isNotEmpty()) append("Сходится:\n").append(ok.joinToString("\n") { "+ $it" }).append('\n')
            if (bad.isNotEmpty()) append("ОШИБКИ / РАСХОЖДЕНИЯ:\n").append(bad.joinToString("\n") { "! $it" }).append('\n')
            if (lone.isNotEmpty()) append("Числа без подтверждения в файле (ни на других листах, ни в тексте — откуда взяты, неясно; возможно, оценка):\n").append(lone.take(15).joinToString("\n") { "? $it" }).append('\n')
            if (bad.isEmpty()) append("Арифметических ошибок не нашла.\n")
        }.trim()
    }

    /** Строки сводки, названные как другой лист: их итог должен совпасть с «ИТОГО» того листа. */
    fun summaryMismatch(sheets: org.json.JSONArray?): List<String> {
        if (sheets == null) return emptyList()
        val fe = FormulaEval(sheets)
        val names = fe.order
        fun rows(k: Int) = sheets.optJSONObject(k)?.optJSONArray("rows") ?: org.json.JSONArray()
        // итог листа-детали: числа в последней строке «итог/всего»
        val detailTotal = HashMap<String, Double>()
        names.forEachIndexed { k, n ->
            val rr = rows(k)
            for (r in rr.length() - 1 downTo 1) {
                val row = rr.optJSONArray(r) ?: continue
                val lab = (row.opt(0) as? String)?.trim()?.lowercase().orEmpty()
                if (!(lab.startsWith("итог") || lab.startsWith("всего"))) continue
                val v = (1 until row.length()).mapNotNull { c -> fe.cell(n.lowercase(), c, r) }.maxOrNull() ?: continue
                detailTotal[n.lowercase()] = v; break
            }
        }
        if (detailTotal.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        names.forEachIndexed { k, n ->
            val rr = rows(k)
            val head = rr.optJSONArray(0) ?: return@forEachIndexed
            val totCol = (0 until head.length()).firstOrNull { c -> (head.opt(c) as? String)?.lowercase()?.let { h -> h.contains("итог") || h.contains("всего") } == true } ?: return@forEachIndexed
            for (r in 1 until rr.length()) {
                val row = rr.optJSONArray(r) ?: continue
                val lab = (row.opt(0) as? String)?.trim()?.lowercase().orEmpty()
                val d = detailTotal[lab] ?: continue
                if (lab == n.lowercase()) continue
                val v = fe.cell(n.lowercase(), totCol, r) ?: continue
                if (kotlin.math.abs(v - d) > 1.0) out += "«${row.opt(0)}» на листе «$n» = ${fmtNum(v)}, а ИТОГО листа «${row.opt(0)}» = ${fmtNum(d)} (разница ${fmtNum(d - v)})"
            }
        }
        return out
    }

    /** Итоги-числа превращаем в ссылки: «=15360+15840» → «=D2+D5»; на листе «Итог» число, равное ячейке другого листа, → «='Отели'!D2». */
    fun linkTotals(sheets: org.json.JSONArray?) {
        if (sheets == null) return
        fun col(c: Int): String = if (c < 26) "${'A' + c}" else "${'A' + (c / 26 - 1)}${'A' + c % 26}"
        val names = (0 until sheets.length()).map { k -> sheets.optJSONObject(k)?.optString("name").orEmpty().ifBlank { "Лист${k + 1}" } }
        val sumRe = Regex("^=\\s*(\\d+(?:\\.\\d+)?(?:\\s*\\+\\s*\\d+(?:\\.\\d+)?)+)\\s*$")
        for (k in 0 until sheets.length()) {
            val rows = sheets.optJSONObject(k)?.optJSONArray("rows") ?: continue
            for (r in 1 until rows.length()) { val row = rows.optJSONArray(r) ?: continue
                for (c in 1 until row.length()) {
                    val f = row.opt(c) as? String ?: continue
                    val m = sumRe.find(f) ?: continue
                    val nums = m.groupValues[1].split('+').map { it.trim().toDouble() }
                    val used = HashSet<Int>()
                    val refs = nums.map { n -> (1 until r).firstOrNull { q -> q !in used && (rows.optJSONArray(q)?.opt(c) as? Number)?.toDouble() == n }?.also { used += it } }
                    if (refs.all { it != null }) row.put(c, "=" + refs.joinToString("+") { "${col(c)}${it!! + 1}" })
                }
            }
        }
        val fe = FormulaEval(sheets)
        for (k in 0 until sheets.length()) {
            if (!Regex("итог|сводк|всего", RegexOption.IGNORE_CASE).containsMatchIn(names[k])) continue
            val rows = sheets.optJSONObject(k)?.optJSONArray("rows") ?: continue
            for (r in 1 until rows.length()) { val row = rows.optJSONArray(r) ?: continue
                for (c in 1 until row.length()) {
                    val v = (row.opt(c) as? Number)?.toDouble() ?: continue
                    if (v < 100) continue
                    var best: String? = null; var bestF = false; var count = 0
                    for (j in 0 until sheets.length()) { if (j == k) continue
                        val rr = sheets.optJSONObject(j)?.optJSONArray("rows") ?: continue
                        for (q in 1 until rr.length()) { val ro = rr.optJSONArray(q) ?: continue
                            for (cc in 0 until ro.length()) {
                                val isF = (ro.opt(cc) as? String)?.startsWith("=") == true
                                if (!isF && ro.opt(cc) !is Number) continue
                                val cv = fe.cell(names[j].lowercase(), cc, q) ?: continue
                                if (kotlin.math.abs(cv - v) < 0.5) { count++
                                    if (best == null || (isF && !bestF)) { best = "='${names[j]}'!${col(cc)}${q + 1}"; bestF = isF } }
                            }
                        }
                    }
                    if (best != null && (count == 1 || bestF)) row.put(c, best)
                }
            }
        }
    }

    private class FormulaEval(sheets: org.json.JSONArray) {
        val grid = HashMap<String, org.json.JSONArray>()
        val order = ArrayList<String>()
        private val memo = HashMap<String, Double?>()
        private val busy = HashSet<String>()
        init {
            for (k in 0 until sheets.length()) sheets.optJSONObject(k)?.let { o ->
                val n = o.optString("name").ifBlank { "Лист${k + 1}" }; grid[n.lowercase()] = o.optJSONArray("rows") ?: org.json.JSONArray(); order += n }
        }
        private fun colIdx(c: String) = c.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

        fun cell(sheet: String, col: Int, row: Int): Double? {
            val key = "$sheet!$col:$row"
            if (memo.containsKey(key)) return memo[key]
            if (!busy.add(key)) return null
            val v = grid[sheet]?.optJSONArray(row)?.opt(col)
            val r: Double? = when (v) {
                is Number -> v.toDouble()
                is String -> if (v.startsWith("=")) runCatching { P(v.drop(1), sheet).run() }.getOrNull()
                    else v.replace(Regex("[\\s\\u00A0\\u202F₽]"), "").replace(',', '.').toDoubleOrNull()
                else -> null
            }
            busy.remove(key); memo[key] = r; return r
        }

        /** Разбор одной формулы: сумма → произведение → множитель (число, ссылка, диапазон в SUM, скобки). */
        private inner class P(val t: String, val sheet: String) {
            var i = 0
            fun run(): Double? { val v = expr(); return if (i >= t.length) v else null }
            private fun ws() { while (i < t.length && t[i] == ' ') i++ }
            private fun expr(): Double? { var v = term() ?: return null
                while (true) { ws(); if (i >= t.length) return v
                    when (t[i]) { '+' -> { i++; v += term() ?: return null }; '-' -> { i++; v -= term() ?: return null }; else -> return v } } }
            private fun term(): Double? { var v = factor() ?: return null
                while (true) { ws(); if (i >= t.length) return v
                    when (t[i]) { '*' -> { i++; v *= factor() ?: return null }; '/' -> { i++; val d = factor() ?: return null; if (d == 0.0) return null; v /= d }; else -> return v } } }
            private fun ref(): Triple<String, Int, Int>? {
                val m = Regex("^(?:'([^']+)'!|([\\p{L}\\p{N}_]+)!)?\\$?([A-Za-z]{1,2})\\$?(\\d+)").find(t.substring(i)) ?: return null
                i += m.value.length
                val sh = (m.groupValues[1].ifBlank { m.groupValues[2] }).ifBlank { sheet }.lowercase()
                return Triple(sh, colIdx(m.groupValues[3]), m.groupValues[4].toInt() - 1)
            }
            private fun factor(): Double? {
                ws(); if (i >= t.length) return null
                if (t[i] == '-') { i++; return factor()?.let { -it } }
                if (t[i] == '(') { i++; val v = expr(); ws(); if (i < t.length && t[i] == ')') i++ else return null; return v }
                Regex("^\\d+(?:[.,]\\d+)?").find(t.substring(i))?.let { i += it.value.length; return it.value.replace(',', '.').toDouble() }
                if (t.regionMatches(i, "SUM(", 0, 4, ignoreCase = true)) {
                    i += 4; var sum = 0.0
                    while (true) {
                        ws(); val a = ref()
                        if (a != null) {
                            ws()
                            if (i < t.length && t[i] == ':') { i++; val b = ref() ?: return null
                                for (r in minOf(a.third, b.third)..maxOf(a.third, b.third)) for (c in minOf(a.second, b.second)..maxOf(a.second, b.second)) sum += cell(a.first, c, r) ?: 0.0
                            } else sum += cell(a.first, a.second, a.third) ?: 0.0
                        } else sum += expr() ?: return null
                        ws(); if (i >= t.length) return null
                        if (t[i] == ';' || t[i] == ',') { i++; continue }
                        if (t[i] == ')') { i++; return sum }
                        return null
                    }
                }
                val r = ref() ?: return null
                return cell(r.first, r.second, r.third) ?: 0.0
            }
        }
    }

    /** 25572 → «25 572», 0.5 → «0,5». */
    fun fmtNum(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        val whole = if (r % 1.0 == 0.0) r.toLong().toString() else null
        return if (whole != null) whole.reversed().chunked(3).joinToString(" ").reversed().replace("- ", "-")
               else String.format(java.util.Locale("ru"), "%,.2f", r).replace('\u00A0', ' ').trimEnd('0').trimEnd(',')
    }

    /** Точный калькулятор выражений: числа (пробелы и запятая допускаются), + − * / ^ %, скобки. */
    private class Calc(src: String) {
        private val t = src.replace(Regex("(?<=\\d)[ \\u00A0\\u202F](?=\\d)"), "").replace('×', '*').replace('÷', '/').replace('−', '-').replace("₽", "").replace(" ", "")
        private var i = 0
        fun run(): Double { val v = expr(); if (i < t.length) error("лишнее: «${t.substring(i).take(10)}»"); return v }
        private fun expr(): Double { var v = term(); while (i < t.length && (t[i] == '+' || t[i] == '-')) { val op = t[i++]; val r = term(); v = if (op == '+') v + r else v - r }; return v }
        private fun term(): Double { var v = pow(); while (i < t.length && (t[i] == '*' || t[i] == '/')) { val op = t[i++]; val r = pow(); if (op == '/' && r == 0.0) error("деление на 0"); v = if (op == '*') v * r else v / r }; return v }
        private fun pow(): Double { val b = unary(); return if (i < t.length && t[i] == '^') { i++; Math.pow(b, pow()) } else b }
        private fun unary(): Double = if (i < t.length && t[i] == '-') { i++; -unary() } else if (i < t.length && t[i] == '+') { i++; unary() } else atom()
        private fun atom(): Double {
            if (i < t.length && t[i] == '(') { i++; val v = expr(); if (i >= t.length || t[i] != ')') error("нет «)»"); i++; return pct(v) }
            val m = Regex("^\\d+(?:[.,]\\d+)?").find(t.substring(i)) ?: error("ожидал число")
            i += m.value.length
            return pct(m.value.replace(',', '.').toDouble())
        }
        private fun pct(v: Double): Double = if (i < t.length && t[i] == '%') { i++; v / 100.0 } else v
    }

    @Volatile var deadline: java.time.LocalDateTime? = null
    fun parseDeadline(text: String) {
        deadline = runCatching {
            val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
            val m = Regex("(?:до|к|не позже|не позднее)\\s+(\\d{1,2})[:.](\\d{2})\\s*(?:утра\\s+)?(\\d{1,2})\\s+(${months.joinToString("|")})", RegexOption.IGNORE_CASE).find(text)
                ?: return@runCatching null
            val mon = months.indexOf(m.groupValues[4].lowercase()) + 1
            val y = Regex("\\b(20\\d\\d)\\b").find(text)?.groupValues?.get(1)?.toInt()
            var dt = java.time.LocalDateTime.of(y ?: java.time.LocalDate.now().year, mon, m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt())
            if (y == null && dt.isBefore(java.time.LocalDateTime.now())) dt = dt.plusYears(1)
            dt
        }.getOrNull()
    }

    private fun weather(place: String, from: String? = null, to: String? = null): String {
        val g = http.newCall(Request.Builder().url("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" +
            URLEncoder.encode(place, "UTF-8")).build()).execute().use { it.body?.string().orEmpty() }
        val p = JSONObject(g).optJSONArray("results")?.optJSONObject(0) ?: return "не нашёл место «$place»"
        val lat = p.optDouble("latitude"); val lon = p.optDouble("longitude")
        val f = http.newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            "&timezone=auto&forecast_days=16&wind_speed_unit=ms").build()).execute().use { it.body?.string().orEmpty() }
        val j = JSONObject(f)
        val c = j.optJSONObject("current") ?: return "погода не пришла"
        val d = j.optJSONObject("daily")
        val days = StringBuilder()
        if (d != null) {
            val dates = d.optJSONArray("time")
            for (k in 0 until (dates?.length() ?: 0)) {
                val day = dates!!.optString(k)
                if (from != null && (day < from || day > (to ?: from))) continue
                if (from == null && k >= 7) break
                days.append("\n${dates.optString(k)}: ${d.optJSONArray("temperature_2m_min")?.optDouble(k)?.toInt()}…" +
                    "${d.optJSONArray("temperature_2m_max")?.optDouble(k)?.toInt()}°, осадки ${d.optJSONArray("precipitation_probability_max")?.optInt(k)}%, " +
                    code(d.optJSONArray("weather_code")?.optInt(k) ?: 0))
            }
        }
        // запрошенные даты дальше горизонта прогноза — честно говорим и даём ориентир по прошлым годам
        val last = d?.optJSONArray("time")?.let { it.optString(it.length() - 1) }.orEmpty()
        if (from != null && last.isNotEmpty() && (to ?: from) > last) {
            val start = if (from > last) from else last
            days.append("\nТочного прогноза на ${if (from > last) "эти даты" else "даты после $last"} ещё нет (горизонт — 16 дней). " +
                runCatching { climate(lat, lon, start, to ?: from) }.getOrElse { "Ориентир по прошлым годам не пришёл." })
        }
        return "${p.optString("name")}: сейчас ${c.optDouble("temperature_2m").toInt()}° (ощущается ${c.optDouble("apparent_temperature").toInt()}°), " +
            "${code(c.optInt("weather_code"))}, ветер ${c.optDouble("wind_speed_10m").toInt()} м/с$days" +
            "\n(источник: Open-Meteo, данные на " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()) + ")"
    }

    /** Те же дни трёх прошлых лет (архив Open-Meteo) — ориентир, когда до дат больше 16 дней. */
    private fun climate(lat: Double, lon: Double, from: String, to: String): String {
        val f = java.time.LocalDate.parse(from); val t = java.time.LocalDate.parse(to)
        val mins = ArrayList<Double>(); val maxs = ArrayList<Double>(); val rain = ArrayList<Double>()
        // осадки считаем по окну шире нужных дат (±5 дней): за 3 года по 3–5 дням доля дождливых прыгает от 0 до 80 %
        for (y in 1..3) {
            val j = JSONObject(httpGet("https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon" +
                "&start_date=${f.minusYears(y.toLong()).minusDays(5)}&end_date=${t.minusYears(y.toLong()).plusDays(5)}" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum&timezone=auto")).optJSONObject("daily") ?: continue
            val times = j.optJSONArray("time")
            for (k in 0 until (times?.length() ?: 0)) {
                val day = runCatching { java.time.LocalDate.parse(times!!.optString(k)) }.getOrNull() ?: continue
                val inRange = !day.isBefore(f.minusYears(y.toLong())) && !day.isAfter(t.minusYears(y.toLong()))
                j.optJSONArray("precipitation_sum")?.takeIf { !it.isNull(k) }?.let { rain += it.optDouble(k) }
                if (inRange) {
                    j.optJSONArray("temperature_2m_min")?.takeIf { !it.isNull(k) }?.let { mins += it.optDouble(k) }
                    j.optJSONArray("temperature_2m_max")?.takeIf { !it.isNull(k) }?.let { maxs += it.optDouble(k) }
                }
            }
        }
        if (mins.isEmpty()) return "Ориентир по прошлым годам не нашёлся."
        val wet = rain.count { it >= 1.0 } * 100 / rain.size.coerceAtLeast(1)
        return "Ориентир — эти же дни за 3 прошлых года: ночью ≈${mins.average().toInt()}°, днём ≈${maxs.average().toInt()}° " +
            "(от ${mins.min().toInt()}° до ${maxs.max().toInt()}°), дней с осадками ≥1 мм ≈$wet% (по ${rain.size} дням вокруг этих дат за 3 года). Это не прогноз, а норма."
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


/** Время публикации того, что прочитал агент (статьи, посты) — чтобы «данные от» было временем источника, а не «сейчас». */
object SrcTime {
    @Volatile var newest: Long = 0
    fun reset() { newest = 0 }
    private val fmt get() = java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale.US)
    fun label(ms: Long): String = fmt.format(java.util.Date(ms))
    fun note(ms: Long?) { if (ms != null && ms > 0 && ms <= System.currentTimeMillis() + 3_600_000) { if (ms > newest) newest = ms } }
    /** ISO-8601 («2026-10-27T15:20:00+00:00», «…Z», «2026-10-27») → мс. */
    fun iso(s: String?): Long? {
        val t = s?.trim()?.takeIf { it.length >= 10 } ?: return null
        return runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.ZonedDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t.take(19)).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(t.take(10)).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
    /** Дата публикации из HTML: meta article:published_time / datePublished / <time datetime>. */
    fun fromHtml(html: String): Long? {
        val pats = listOf(
            "property=[\"'](?:article:published_time|og:published_time)[\"'][^>]*content=[\"']([^\"']+)",
            "content=[\"']([^\"']+)[\"'][^>]*property=[\"'](?:article:published_time|og:published_time)",
            "itemprop=[\"']datePublished[\"'][^>]*(?:content|datetime)=[\"']([^\"']+)",
            "\"datePublished\"\\s*:\\s*\"([^\"]+)\"",
            "name=[\"'](?:pubdate|publish-date|date)[\"'][^>]*content=[\"']([^\"']+)",
            "<time[^>]*datetime=[\"']([^\"']+)")
        for (p in pats) Regex(p, RegexOption.IGNORE_CASE).find(html)?.let { m -> iso(m.groupValues[1])?.let { return it } }
        return null
    }
}

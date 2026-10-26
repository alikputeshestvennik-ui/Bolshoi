package ru.alim.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Настройки агента. Модели и цены (₽ за 1000 токенов) — как на страницах моделей gptunnel, правятся в «Подключениях». */
object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("agent", Context.MODE_PRIVATE)
    }

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var fastModel: String get() = s("fm", "claude-5.5-haiku"); set(v) = put("fm", v.trim())
    /** Быстрая полоса: простую болтовню Пилот отвечает сразу лёгкой моделью (с потоковым выводом), минуя полный цикл агента. */
    var fastLane: Boolean get() = b("fastlane", true); set(v) = putB("fastlane", v)
    var smartModel: String get() = s("sm", "claude-5.5-sonnet"); set(v) = put("sm", v.trim())
    var fastIn: String get() = s("fi", "0.03"); set(v) = put("fi", v.trim())
    var fastOut: String get() = s("fo", "0.15"); set(v) = put("fo", v.trim())
    var smartIn: String get() = s("si", "0.4"); set(v) = put("si", v.trim())
    var smartOut: String get() = s("so", "2"); set(v) = put("so", v.trim())
    var homeCity: String get() = s("city", "Ялта"); set(v) = put("city", v.trim())
    var monthLimit: String get() = s("limit", "1200"); set(v) = put("limit", v.trim())
    var rules: String get() = s("rules", DEFAULT_RULES); set(v) = put("rules", v)
    var lastBalance: String get() = s("bal", ""); set(v) = put("bal", v)
    var autoReply: Boolean get() = b("ar", false); set(v) = putB("ar", v)
    /** Отвечать ли тем, кто есть в контактах телефона. По умолчанию — нет. */
    var replyKnown: Boolean get() = b("rk", false); set(v) = putB("rk", v)
    /** Зачитывать текстовые ответы вслух (голосовые Артура — отдельно). В переписке по умолчанию выключено. */
    var speak: Boolean get() = b("speak_chat", false); set(v) = putB("speak_chat", v)
    var notes: List<String> get() = runCatching { org.json.JSONArray(s("notes", "[]")).let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrDefault(emptyList())
        set(v) = put("notes", org.json.JSONArray(v).toString())
    var askedPerms: Boolean get() = b("asked", false); set(v) = putB("asked", v)
    var monitorOn: Boolean get() = b("mon", true); set(v) = putB("mon", v)
    /** Распознавание и озвучка: "android" или "yandex" (SpeechKit). */
    var sttEngine: String get() = s("stt", "android"); set(v) = put("stt", v)
    /** Озвучка: google (Gemini, голос как в звонке) · yandex · android. */
    var ttsEngine: String get() = s("tts2", "google"); set(v) = put("tts2", v)
    /** Голос Google для главного друга («» — по полу автоматически). */
    var gVoice: String get() = s("gvoice", ""); set(v) = put("gvoice", v)
    /** Фоновая модель: уведомления, сводки, сам пишет, поиск фото. Пусто — как «Чат». */
    var bgModel: String get() = s("bgm", "google:gemini-3.5-flash-lite"); set(v) = put("bgm", v.trim())
    val bg: String get() = bgModel.ifBlank { fastModel }
    /** Счётчики бесплатных запросов Google за сутки (JSON). */
    fun initCount(fid: String, day: String) = if (sp.getString("icd_$fid", "") == day) sp.getInt("icn_$fid", 0) else 0
    fun addInitCount(fid: String, day: String) { val n = initCount(fid, day) + 1; sp.edit().putString("icd_$fid", day).putInt("icn_$fid", n).apply() }
    var seededMustafa: Boolean get() = b("mustafa", false); set(v) = putB("mustafa", v)
    var gQuota: String get() = s("gq", ""); set(v) = put("gq", v)
    var ttsVoice: String get() = s("voice", "filipp"); set(v) = put("voice", v)
    var interval: Int get() = sp.getInt("int", 30); set(v) = sp.edit().putInt("int", v).apply()
    var digestHour: Int get() = sp.getInt("dh", 21); set(v) = sp.edit().putInt("dh", v).apply()
    var lastDigest: String get() = s("ld", ""); set(v) = put("ld", v)
    var monitorStatus: String get() = s("ms", ""); set(v) = put("ms", v)
    // ——— Оформление и характер ———
    var theme: String get() = s("theme", "mint"); set(v) = put("theme", v)
    /** Подложка: "pattern" — узор темы, "photo" — картинка из файла wallpaper.jpg. */
    var wallpaper: String get() = s("wall", "pattern"); set(v) = put("wall", v)
    var wallDim: Int get() = sp.getInt("wdim", 25); set(v) = sp.edit().putInt("wdim", v).apply()
    var botName: String get() = s("bname", "Артур").ifBlank { "Артур" }; set(v) = put("bname", v.trim())
    var botMale: Boolean get() = b("bmale", true); set(v) = putB("bmale", v)
    /** Манера: "buddy" — кореш, "secretary" — секретарь, "short" — коротко. */
    var botStyle: String get() = s("bstyle", "buddy"); set(v) = put("bstyle", v)
    /** Аватар: "" — буква, иначе путь к файлу картинки. */
    var botAvatar: String get() = s("bava", ""); set(v) = put("bava", v)
    // ——— Инициатива ———
    var initMorning: Boolean get() = b("imorn", true); set(v) = putB("imorn", v)
    var morningHour: Int get() = sp.getInt("mhour", 8); set(v) = sp.edit().putInt("mhour", v).apply()
    var initImportant: Boolean get() = b("iimp", true); set(v) = putB("iimp", v)
    /** «Просто поболтать»: 0 никогда, 1 редко, 2 иногда, 3 часто. */
    var chatty: Int get() = sp.getInt("chatty", 2); set(v) = sp.edit().putInt("chatty", v).apply()
    /** Кнопки быстрых ответов под сообщениями Артура. По умолчанию скрыты. */
    var quickButtons: Boolean get() = b("qbtn", false); set(v) = putB("qbtn", v)
    var voiceReplies: Boolean get() = b("vrep2", false); set(v) = putB("vrep2", v)
    var quietFrom: Int get() = sp.getInt("qfrom", 23); set(v) = sp.edit().putInt("qfrom", v).apply()
    var quietTo: Int get() = sp.getInt("qto", 8); set(v) = sp.edit().putInt("qto", v).apply()
    var lastMorning: String get() = s("lmorn", ""); set(v) = put("lmorn", v)
    var lastChatty: Long get() = sp.getLong("lchat", 0L); set(v) = sp.edit().putLong("lchat", v).apply()
    var lastSeen: Long get() = sp.getLong("lseen", 0L); set(v) = sp.edit().putLong("lseen", v).apply()
    // ——— друзья ———
    var friends: String get() = s("friends", ""); set(v) = put("friends", v)
    var lastChat: String get() = s("lastchat", "main"); set(v) = put("lastchat", v)
    fun readAt(fid: String): Long = sp.getLong("read_$fid", 0L)
    fun setReadAt(fid: String, t: Long) = sp.edit().putLong("read_$fid", t).apply()
    fun nudgedAt(fid: String): Long = sp.getLong("nudge_$fid", 0L)
    fun setNudgedAt(fid: String, t: Long) = sp.edit().putLong("nudge_$fid", t).apply()
    fun evCheck(fid: String): Long = sp.getLong("evc_$fid", 0L)
    fun setEvCheck(fid: String, t: Long) = sp.edit().putLong("evc_$fid", t).apply()

    /** Подборка свежих роликов YouTube по интересам из памяти — вместе с итогом дня. */
    var ytPicks: Boolean get() = b("yt", true); set(v) = putB("yt", v)
    var ytSeen: String get() = s("yts", ""); set(v) = put("yts", v)

    fun num(t: String) = t.replace(',', '.').trim().toDoubleOrNull() ?: 0.0
    /** Цена вызова в рублях по токенам. */
    fun price(model: String, inTok: Int, outTok: Int): Double {
        if (Llm.isGoogle(model)) return 0.0
        val smart = model == smartModel && model != fastModel
        val pin = num(if (smart) smartIn else fastIn)
        val pout = num(if (smart) smartOut else fastOut)
        return inTok / 1000.0 * pin + outTok / 1000.0 * pout
    }

    val DEFAULT_RULES = """
Клиенты (кого нет в контактах) спрашивают про заказ или доставку — вежливо ответь, что заказ в пути и я скоро сообщу время.
С 23:00 до 8:00 всем, кроме семьи, — «Отвечу утром».
Семье за меня не отвечай, только перескажи, что написали.
Рекламу, рассылки и группы не трогай.
""".trim()
}

/** Одна выполненная задача — для ленты «Сегодня» и экрана расходов. */
data class TaskRec(val ts: Long, val title: String, val steps: Int, val model: String, val cost: Double, val ok: Boolean,
                   val dur: Long = 0, val result: String = "", val log: String = "", val items: String = "") {
    /** Фоновые записи (мониторинг, итог дня, автоответ) — не просьбы Алима. */
    val background: Boolean get() = title.startsWith("Мониторинг") || title.startsWith("Итог дня") || title.startsWith("Автоответ")
}

object Journal {
    /** Меняется при каждой записи — экраны перечитывают журнал. */
    val version = mutableIntStateOf(0)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("journal", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<TaskRec> {
        val a = runCatching { JSONArray(sp(ctx).getString("t", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            TaskRec(it.optLong("ts"), it.optString("title"), it.optInt("steps"), it.optString("model"),
                it.optDouble("cost", 0.0).let { c -> if (c.isNaN()) 0.0 else c }, it.optBoolean("ok", true),
                it.optLong("dur"), it.optString("res"), it.optString("log"), it.optString("items"))
        }
    }

    @Synchronized
    fun add(ctx: Context, r: TaskRec) {
        val list = (all(ctx) + r).takeLast(600)
        val a = JSONArray()
        list.forEachIndexed { i, it ->
            val o = JSONObject().put("ts", it.ts).put("title", it.title).put("steps", it.steps)
                .put("model", it.model).put("cost", it.cost).put("ok", it.ok).put("dur", it.dur)
            // подробности (шаги, ответ, товары) храним только у последних 120 записей
            if (i >= list.size - 120) o.put("res", it.result.take(2500)).put("log", it.log.take(2500)).put("items", it.items.take(4000))
            a.put(o)
        }
        sp(ctx).edit().putString("t", a.toString()).apply()
        version.intValue = version.intValue + 1
    }

    fun since(ctx: Context, from: Long) = all(ctx).filter { it.ts >= from }

    private fun cal() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    fun startOfDay(): Long = cal().timeInMillis
    fun startOfWeek(): Long = cal().apply { add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis
    fun startOfMonth(): Long = cal().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
}

fun rub(v: Double): String = if (v < 10) String.format(java.util.Locale("ru"), "%.2f ₽", v) else String.format(java.util.Locale("ru"), "%.0f ₽", v)

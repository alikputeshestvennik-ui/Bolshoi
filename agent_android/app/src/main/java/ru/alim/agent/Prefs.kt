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
    var speak: Boolean get() = b("speak", true); set(v) = putB("speak", v)
    var askedPerms: Boolean get() = b("asked", false); set(v) = putB("asked", v)
    var monitorOn: Boolean get() = b("mon", true); set(v) = putB("mon", v)
    /** Распознавание и озвучка: "android" или "yandex" (SpeechKit). */
    var sttEngine: String get() = s("stt", "android"); set(v) = put("stt", v)
    var ttsEngine: String get() = s("tts", "android"); set(v) = put("tts", v)
    var ttsVoice: String get() = s("voice", "filipp"); set(v) = put("voice", v)
    var interval: Int get() = sp.getInt("int", 30); set(v) = sp.edit().putInt("int", v).apply()
    var digestHour: Int get() = sp.getInt("dh", 21); set(v) = sp.edit().putInt("dh", v).apply()
    var lastDigest: String get() = s("ld", ""); set(v) = put("ld", v)
    var monitorStatus: String get() = s("ms", ""); set(v) = put("ms", v)

    fun num(t: String) = t.replace(',', '.').trim().toDoubleOrNull() ?: 0.0
    /** Цена вызова в рублях по токенам. */
    fun price(model: String, inTok: Int, outTok: Int): Double {
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
data class TaskRec(val ts: Long, val title: String, val steps: Int, val model: String, val cost: Double, val ok: Boolean)

object Journal {
    /** Меняется при каждой записи — экраны перечитывают журнал. */
    val version = mutableIntStateOf(0)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("journal", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<TaskRec> {
        val a = runCatching { JSONArray(sp(ctx).getString("t", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            TaskRec(it.optLong("ts"), it.optString("title"), it.optInt("steps"), it.optString("model"),
                it.optDouble("cost", 0.0).let { c -> if (c.isNaN()) 0.0 else c }, it.optBoolean("ok", true))
        }
    }

    @Synchronized
    fun add(ctx: Context, r: TaskRec) {
        val list = (all(ctx) + r).takeLast(600)
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("ts", it.ts).put("title", it.title).put("steps", it.steps)
                .put("model", it.model).put("cost", it.cost).put("ok", it.ok))
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

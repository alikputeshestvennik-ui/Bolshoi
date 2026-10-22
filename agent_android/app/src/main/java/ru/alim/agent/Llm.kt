package ru.alim.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Запросы к gptunnel (формат OpenAI) — как в Маршруте: ключ из секрета GPTUNNEL_KEY, оплата с личного счёта. */
object Llm {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val BASE = "https://gptunnel.ru/v1"

    /** Как gptunnel принимает ключ: «Authorization: ключ» или «Bearer ключ» — запоминаем сработавший. */
    @Volatile private var bearer = false

    val key: String get() = BuildConfig.GPTUNNEL_KEY.trim()

    class Reply(val text: String, val inTok: Int, val outTok: Int, private val apiCost: Double) {
        /** Цена в рублях: из ответа gptunnel, если он её прислал, иначе по ценам из настроек. */
        fun cost(model: String): Double = if (apiCost > 0) apiCost else Prefs.price(model, inTok, outTok)
    }

    private fun post(url: String, body: JSONObject, br: Boolean): Pair<Int, String> =
        http.newCall(Request.Builder().url(url)
            .header("Authorization", if (br) "Bearer $key" else key)
            .post(body.toString().toRequestBody(JSON)).build()).execute().use { it.code to (it.body?.string() ?: "") }

    private fun get(url: String, br: Boolean): Pair<Int, String> =
        http.newCall(Request.Builder().url(url)
            .header("Authorization", if (br) "Bearer $key" else key).get().build()).execute().use { it.code to (it.body?.string() ?: "") }

    /** Один ход модели. Неизвестное gptunnel название модели — подбираем верное, запоминаем и повторяем. */
    /** YandexGPT и другие модели Yandex AI Studio: «yandexgpt-5.1/latest» или полный «gpt://каталог/…». */
    fun isYandex(m: String) = m.startsWith("gpt://") || m.startsWith("yc:") || m.startsWith("yandex", true)
    /** Google AI Studio: «google:gemini-3.5-flash-lite», «gemini-…», «gemma-…». Нужен VPN. */
    fun isGoogle(m: String) = m.startsWith("google:") || m.startsWith("gemini", true) || m.startsWith("gemma", true)
    /** Платная ли модель (для подсказок в настройках). */
    fun free(m: String) = isGoogle(m)

    /** Запасная модель, если Google не ответил (нет VPN, лимит): Claude через gptunnel, иначе Яндекс. */
    private fun fallback(): String = when {
        key.isNotBlank() -> "claude-5.5-haiku"
        YaCloud.llmKey.isNotBlank() -> "yc:aliceai-llm-flash"
        else -> ""
    }

    class GoogleLimit(val daily: Boolean, msg: String) : Exception(msg)

    private fun googleChat(id: String, messages: JSONArray, maxTokens: Int, json: Boolean): Reply {
        val k = Call.key
        if (k.isBlank()) error("нет ключа Google (GOOGLE_API)")
        fun body(js: Boolean) = JSONObject().put("model", id).put("temperature", 0.3).put("max_tokens", maxTokens + 2000).put("messages", messages)
            .also { if (js) it.put("response_format", JSONObject().put("type", "json_object")) }
            .also { if (js && !id.contains("lite") && !id.startsWith("gemma")) it.put("reasoning_effort", "low") }
        fun send(b: JSONObject) = http.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
            .header("Authorization", "Bearer $k").post(b.toString().toRequestBody(JSON)).build()).execute()
            .use { it.code to (it.body?.string() ?: "") }
        var r = send(body(json))
        if (r.first == 400 && json) r = send(body(false))
        if (r.first == 503 || r.first == 500) { Thread.sleep(1500); r = send(body(false)) }
        if (r.first == 429) {
            val daily = r.second.contains("PerDay", true) || r.second.contains("per day", true) || r.second.contains("daily", true)
            throw GoogleLimit(daily, "Google: лимит ${if (daily) "на сутки" else "в минуту"}")
        }
        if (r.first !in 200..299) error("Google ${r.first}: ${r.second.take(200)}")
        val j = JSONObject(r.second)
        val text = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
        val u = j.optJSONObject("usage") ?: JSONObject()
        return Reply(text, u.optInt("prompt_tokens"), u.optInt("completion_tokens"), 0.0)
    }

    /**
     * Google с учётом лимитов: берём выбранную модель, если влезает; иначе соседнюю бесплатную;
     * всё бесплатное на сегодня кончилось, нет VPN или Google упал — запасная платная.
     */
    private fun googleRouted(model: String, messages: JSONArray, maxTokens: Int, json: Boolean, maxWait: Long): Reply {
        val want = model.removePrefix("google:")
        var last: Exception? = null
        for (i in 0 until 4) {
            val id = Quota.pick(want, maxWait) ?: break
            Quota.take(id)
            try { return googleChat(id, messages, maxTokens, json) }
            catch (e: GoogleLimit) { Quota.hit(id, e.daily); last = e }   // эта модель упёрлась — пробуем соседнюю
            catch (e: Exception) { last = e; break }                        // нет VPN или Google сломался — сразу на запасную
        }
        val fb = fallback()
        if (fb.isBlank()) throw last ?: IllegalStateException("бесплатный лимит Google на сегодня исчерпан")
        return chatRaw(fb, messages, maxTokens, json)
    }

    /** Любой вызов модели: успех — Артур «в сети», обрыв связи — «был(а) в…». */
    fun chat(model: String, messages: JSONArray, maxTokens: Int = 1200, json: Boolean = true): Reply {
        if (isGoogle(model)) {
            // фон может подождать окно до 20 с, переписка — не больше 3 с
            val wait = if (model == Prefs.bg && model != Prefs.fastModel) 20_000L else 3_000L
            return googleRouted(model, messages, maxTokens, json, wait).also { Online.ok() }
        }
        return try { chatRaw(model, messages, maxTokens, json).also { Online.ok() } }
        catch (e: java.io.IOException) { Online.fail(); throw e }
    }

    private fun chatRaw(model: String, messages: JSONArray, maxTokens: Int, json: Boolean): Reply =
        if (isYandex(model)) yandexChat(model, messages, maxTokens, json) else try {
            chatOnce(model, messages, maxTokens, json)
        } catch (e: ModelNotFound) {
            val fixed = findModel(model) ?: error("gptunnel не знает модель «$model», и подобрать название не вышло — впиши его в «Подключениях»")
            if (model == Prefs.fastModel) Prefs.fastModel = fixed
            if (model == Prefs.smartModel) Prefs.smartModel = fixed
            chatOnce(fixed, messages, maxTokens, json)
        }

    class ModelNotFound(msg: String) : Exception(msg)

    /** Названия-кандидаты: у gptunnel бывает «claude-4.5-haiku», бывает и наоборот; плюс список /v1/models, если он есть. */
    fun findModel(model: String): String? {
        val m = model.lowercase()
        val kind = listOf("haiku", "sonnet", "opus").firstOrNull { m.contains(it) } ?: return null
        val ver = Regex("(\\d+)[.-](\\d+)").find(m)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
            ?: Regex("(\\d+)").find(m)?.groupValues?.get(1) ?: return null
        val listed = runCatching { models() }.getOrDefault(emptyList())
            .filter { it.lowercase().contains(kind) && (it.contains(ver) || it.contains(ver.replace('.', '-'))) }
        val guesses = listOf("claude-$ver-$kind", "claude-$kind-$ver", "claude-$kind-${ver.replace('.', '-')}",
            "claude-${ver.replace('.', '-')}-$kind", "anthropic/claude-$kind-$ver")
        for (c in (listed + guesses).distinct()) {
            if (c == model) continue
            val ok = runCatching {
                chatOnce(c, JSONArray().put(JSONObject().put("role", "user").put("content", "ok")), 5, false); true
            }.getOrElse { it !is ModelNotFound && it.message?.contains("404") != true }
            if (ok) return c
        }
        return null
    }

    /** Список моделей gptunnel (формат OpenAI: data[].id), если такой адрес у них есть. */
    fun models(): List<String> {
        val r = get("$BASE/models", bearer)
        if (r.first !in 200..299) return emptyList()
        val a = JSONObject(r.second).optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
    }

    private fun chatOnce(model: String, messages: JSONArray, maxTokens: Int, json: Boolean): Reply {
        if (key.isBlank()) error("В сборке нет ключа GPTUNNEL_KEY — проверь секрет на GitHub и пересобери")
        fun body(js: Boolean) = JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", maxTokens)
            .put("useWalletBalance", true).put("messages", messages)
            .also { if (js) it.put("response_format", JSONObject().put("type", "json_object")) }
        var b = body(json)
        var r = post("$BASE/chat/completions", b, bearer)
        if (r.first == 401 || r.first == 403) {
            val r2 = post("$BASE/chat/completions", b, !bearer)
            if (r2.first in 200..299) bearer = !bearer
            r = r2
        }
        if (r.first == 400 && json) { b = body(false); r = post("$BASE/chat/completions", b, bearer) }
        if (r.first == 404 || r.second.contains("invalid_model_error") || r.second.contains("Model not found", true))
            throw ModelNotFound("модель «$model» не найдена")
        if (r.first !in 200..299) error("модель «$model» ответила ${r.first}: ${r.second.take(200)}")
        val j = JSONObject(r.second)
        val text = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "")
        val u = j.optJSONObject("usage") ?: JSONObject()
        val apiCost = u.optDouble("total_cost", 0.0).let { if (it.isNaN()) 0.0 else it }
        return Reply(text, u.optInt("prompt_tokens"), u.optInt("completion_tokens"), apiCost)
    }

    /** Баланс личного счёта gptunnel в рублях (GET /v1/balance?useWalletBalance=true). */
    fun balance(): Double {
        if (key.isBlank()) error("нет ключа")
        val url = "$BASE/balance?useWalletBalance=true"
        var r = get(url, bearer)
        if (r.first == 401 || r.first == 403) {
            val r2 = get(url, !bearer)
            if (r2.first in 200..299) bearer = !bearer
            r = r2
        }
        if (r.first !in 200..299) error("баланс: ${r.first} ${r.second.take(120)}")
        return findNumber(r.second) ?: error("не понял ответ: ${r.second.take(120)}")
    }

    /** Ответ бывает {"balance": 512.3} или вложенным — ищем первое число в знакомых полях. */
    private fun findNumber(txt: String): Double? {
        txt.trim().toDoubleOrNull()?.let { return it }
        val j = runCatching { JSONObject(txt) }.getOrNull() ?: return null
        return scan(j)
    }

    private fun scan(j: JSONObject): Double? {
        for (k in listOf("balance", "amount", "sum", "value", "total")) {
            if (j.has(k)) {
                val v = j.opt(k)
                if (v is Number) return v.toDouble()
                if (v is String) v.replace(',', '.').toDoubleOrNull()?.let { return it }
                if (v is JSONObject) scan(v)?.let { return it }
            }
        }
        val it = j.keys()
        while (it.hasNext()) {
            val v = j.opt(it.next())
            if (v is JSONObject) scan(v)?.let { n -> return n }
        }
        return null
    }
}

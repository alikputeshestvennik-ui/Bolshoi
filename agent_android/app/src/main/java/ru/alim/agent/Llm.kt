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
    /** Какая модель реально ответила на последний запрос (Google при лимите тихо переключается на соседнюю или запасную). */
    @Volatile var lastUsed = ""
    /** Когда GPTunnel последний раз сказал «нет денег» — 15 минут его не трогаем как запасной. */
    @Volatile var brokeAt = 0L
    val broke: Boolean get() = System.currentTimeMillis() - brokeAt < 15 * 60_000L

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
        key.isNotBlank() && !broke -> "claude-5.5-haiku"
        YaCloud.llmKey.isNotBlank() -> "yc:aliceai-llm-flash"
        else -> ""
    }

    class GoogleLimit(val daily: Boolean, msg: String) : Exception(msg)
    /** На счёте gptunnel кончились деньги (402) — повтор не поможет, нужна другая модель или пополнение. */
    class NoMoney(msg: String) : Exception(msg)

    /**
     * Текст ответа. Некоторые модели (Qwen) вместо content присылают null и родной вызов функции (tool_calls) —
     * переводим его в наш JSON-формат, иначе в чат уходило слово «null».
     */
    fun content(m: JSONObject): String {
        if (!m.isNull("content")) { val t = m.optString("content", ""); if (t.isNotBlank() && t != "null") return t }
        val tc = m.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
        if (tc != null) {
            val args = runCatching { JSONObject(tc.optString("arguments", "{}")) }.getOrDefault(JSONObject())
            val name = tc.optString("name")
            return JSONObject().put("step", name).put("tool", name).put("args", args).put("say", "").put("done", false).toString()
        }
        return ""
    }

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
        val text = content(j.getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
        val u = j.optJSONObject("usage") ?: JSONObject()
        lastUsed = "google:$id"
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

    private fun yandexChat(model: String, messages: JSONArray, maxTokens: Int, json: Boolean): Reply {
        val k = YaCloud.llmKey
        if (k.isBlank()) error("нет ключа Yandex Cloud (YANDEX_API_KEY)")
        val uri = if (model.startsWith("gpt://")) model else {
            val f = YaCloud.folder; if (f.isBlank()) error("нет ID каталога Yandex Cloud")
            "gpt://$f/${model.removePrefix("yandex:").removePrefix("yc:")}"
        }
        fun body(js: Boolean) = JSONObject().put("model", uri).put("temperature", 0.2).put("max_tokens", maxTokens.coerceAtMost(8000)).put("messages", messages)
            .also { if (js) it.put("response_format", JSONObject().put("type", "json_object")) }
        val keys = (listOf(k) + YaCloud.all).distinct()
        fun sendWith(key: String, b: JSONObject) = http.newCall(Request.Builder().url("https://llm.api.cloud.yandex.net/v1/chat/completions")
            .header("Authorization", "Api-Key $key").post(b.toString().toRequestBody(JSON)).build()).execute()
            .use { it.code to (it.body?.string() ?: "") }
        var r = Pair(0, "")
        for (attempt in 0 until 3) {
            for (key in keys) {
                r = sendWith(key, body(json))
                if (r.first == 400 && json) r = sendWith(key, body(false))
                if (r.first != 401 && r.first != 403) break
            }
            // у Яндекса бывают разовые 500/503/429 — пробуем ещё раз через паузу
            if (r.first !in setOf(429, 500, 502, 503, 504)) break
            Thread.sleep(1200L * (attempt + 1))
        }
        if (r.first in 200..299) lastUsed = model
        if (r.first == 401 || r.first == 403) error("YandexGPT: у ключа нет доступа к моделям (роль ai.languageModels.user)")
        if (r.first !in 200..299) error("YandexGPT ответил ${r.first}: ${r.second.take(200)}")
        val j = JSONObject(r.second)
        val text = content(j.getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
        val u = j.optJSONObject("usage") ?: JSONObject()
        return Reply(text, u.optInt("prompt_tokens"), u.optInt("completion_tokens"), 0.0)
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
        if (r.first == 402 || r.second.contains("Insufficient quota", true) || r.second.contains("balance is too low", true))
            { brokeAt = System.currentTimeMillis(); throw NoMoney("на GPTunnel закончился баланс") }
        if (r.first !in 200..299) error("модель «$model» ответила ${r.first}: ${r.second.take(200)}")
        val j = JSONObject(r.second)
        val text = content(j.getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
        val u = j.optJSONObject("usage") ?: JSONObject()
        val apiCost = u.optDouble("total_cost", 0.0).let { if (it.isNaN()) 0.0 else it }
        lastUsed = model
        return Reply(text, u.optInt("prompt_tokens"), u.optInt("completion_tokens"), apiCost)
    }

    /**
     * Потоковый ответ (SSE) — для «быстрой полосы»: текст приходит по кусочкам через onDelta.
     * Возвращает полный собранный текст. Бросает исключение при ошибке сети/провайдера — вызывающий откатится на агента.
     */
    fun stream(model: String, messages: JSONArray, maxTokens: Int, onDelta: (String) -> Unit): String {
        return when {
            isYandex(model) -> {
                val k = YaCloud.llmKey
                if (k.isBlank()) error("нет ключа Yandex")
                val uri = if (model.startsWith("gpt://")) model else {
                    val f = YaCloud.folder; if (f.isBlank()) error("нет ID каталога Yandex")
                    "gpt://$f/${model.removePrefix("yandex:").removePrefix("yc:")}"
                }
                streamSse("https://llm.api.cloud.yandex.net/v1/chat/completions", "Api-Key $k", uri, messages, maxTokens, onDelta)
            }
            isGoogle(model) -> {
                val k = Call.key
                if (k.isBlank()) error("нет ключа Google")
                streamSse("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                    "Bearer $k", model.removePrefix("google:"), messages, maxTokens, onDelta)
            }
            else -> {
                if (key.isBlank()) error("нет ключа gptunnel")
                streamSse("$BASE/chat/completions", if (bearer) "Bearer $key" else key, model, messages, maxTokens, onDelta)
            }
        }
    }

    private fun streamSse(url: String, auth: String, id: String, messages: JSONArray, maxTokens: Int, onDelta: (String) -> Unit): String {
        val body = JSONObject().put("model", id).put("temperature", 0.4).put("max_tokens", maxTokens)
            .put("stream", true).put("messages", messages)
        val req = Request.Builder().url(url).header("Authorization", auth)
            .post(body.toString().toRequestBody(JSON)).build()
        val sb = StringBuilder()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("stream ${resp.code}")
            val src = resp.body?.source() ?: error("пустой поток")
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val delta = runCatching {
                    JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                }.getOrDefault("")
                if (delta.isNotEmpty()) { sb.append(delta); onDelta(delta) }
            }
        }
        return sb.toString()
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

package ru.alim.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Запросы к моделям: AnyModel (OpenAI-формат), Yandex AI Studio, Google AI Studio. GPTunnel убран. */
object Llm {
    fun isChatGpt(model: String) = model.startsWith("chatgpt:")
    private fun chatGpt(model: String, messages: JSONArray, onPart: ((String, String) -> Unit)? = null): Reply {
        val full = StringBuilder()
        val r = ChatGptResponses.chat(ChatGptAccount.access(), model.removePrefix("chatgpt:"), messages) { delta ->
            full.append(delta); onPart?.invoke(full.toString(), "")
        }
        lastUsed = model; whyNotWanted = ""
        return Reply(r.text, r.inputTokens, r.outputTokens, 0.0)
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(200, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()   // тяжёлый ход DeepSeek с большим контекстом думает дольше 2 минут
    private val JSON = "application/json; charset=utf-8".toMediaType()
    /** Рассуждающие модели AnyModel отвечают целиком и долго (минуты) — таймауты длиннее общих. */
    private val anyHttp by lazy { http.newBuilder().readTimeout(600, TimeUnit.SECONDS).callTimeout(900, TimeUnit.SECONDS).build() }
    /** Ключ AnyModel (секрет ANYMODEL_KEY на GitHub, в сборку попадает через BuildConfig — в коде его нет). */
    val anyKey: String get() = BuildConfig.ANYMODEL_KEY.trim()
    /** Модели AnyModel, которые уже отвечали в этом запуске: их «не найдена» — сбой, а не опечатка. */
    private val anyWorked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private const val ANY_URL = "https://anymodel.org/v1/chat/completions"
    /** Модели AnyModel: «cx/gpt-6-astra», «cc/claude-opus-5-5», «ds/…», «ag/…», «kmc/…» или явно «any:название». */
    fun isAny(m: String) = m.startsWith("any:") || Regex("^(cx|cc|ds|ag|kmc)/", RegexOption.IGNORE_CASE).containsMatchIn(m)
    /** Какая модель реально ответила на последний запрос (Google при лимите тихо переключается на соседнюю или запасную). */
    @Volatile var lastUsed = ""
    /** Почему ответила не выбранная модель: «limit», «region» (VPN-сервер в стране, где Gemini нет), «error». */
    @Volatile var whyNotWanted = ""

    class Reply(val text: String, val inTok: Int, val outTok: Int, private val apiCost: Double) {
        /** Цена в рублях: AnyModel — по коэффициенту модели (AnyPrice), остальное — по ценам из настроек. */
        fun cost(model: String): Double = if (apiCost > 0) apiCost else Prefs.price(model, inTok, outTok)
    }

    /** YandexGPT и другие модели Yandex AI Studio: «yandexgpt-5.1/latest» или полный «gpt://каталог/…». */
    fun isYandex(m: String) = m.startsWith("gpt://") || m.startsWith("yc:") || m.startsWith("yandex", true)
    /** Google AI Studio: «google:gemini-3.5-flash-lite», «gemini-…», «gemma-…». Нужен VPN. */
    fun isGoogle(m: String) = m.startsWith("google:") || m.startsWith("gemini", true) || m.startsWith("gemma", true)
    /** Платная ли модель (для подсказок в настройках). */
    fun free(m: String) = isGoogle(m)

    /** Запасная модель, если Google не ответил (нет VPN, лимит): Яндекс. */
    private fun fallback(): String = when {
        YaCloud.llmKey.isNotBlank() -> "yc:aliceai-llm-flash"
        else -> ""
    }

    class GoogleLimit(val daily: Boolean, msg: String) : Exception(msg)
    class GoogleRegion(msg: String) : Exception(msg)
    /** На счёте AnyModel кончились деньги (402) — повтор не поможет, нужна другая модель или пополнение. */
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
        if (r.first in listOf(400, 403) && Regex("location is not supported|not available in your (country|region)|unsupported_country|FAILED_PRECONDITION", RegexOption.IGNORE_CASE).containsMatchIn(r.second))
            throw GoogleRegion("Gemini недоступен с этого VPN-сервера (Google не работает в его стране) — смени сервер VPN")
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
    private fun googleRouted(model: String, messages: JSONArray, maxTokens: Int, json: Boolean, maxWait: Long, stick: Boolean = false): Reply {
        val want = model.removePrefix("google:")
        var last: Exception? = null
        whyNotWanted = ""
        for (i in 0 until (if (stick) 6 else 4)) {
            val id = Quota.pick(want, maxWait, stick) ?: break
            Quota.take(id)
            try { return googleChat(id, messages, maxTokens, json) }
            catch (e: GoogleLimit) {
                Quota.hit(id, e.daily); last = e; whyNotWanted = "limit"
                // минутный лимит в задаче — ждём и пробуем ту же модель, а не соседнюю
                if (stick && !e.daily && id == want) { Thread.sleep(20_000L); continue }
            }
            catch (e: GoogleRegion) { last = e; whyNotWanted = "region"; break }  // VPN не в той стране — соседние Gemini тоже не ответят
            catch (e: Exception) { last = e; whyNotWanted = "error"; break }      // нет VPN или Google сломался — сразу на запасную
        }
        if (whyNotWanted.isEmpty()) whyNotWanted = "limit"
        val fb = fallback()
        if (fb.isBlank()) throw last ?: IllegalStateException("бесплатный лимит Google на сегодня исчерпан")
        return chatRaw(fb, messages, maxTokens, json)
    }

    /**
     * AnyModel (формат OpenAI). Для «рук» просим reasoning_effort=high (по духу — максимум тщательности);
     * не все модели принимают json-режим и reasoning_effort — тогда повторяем голым запросом.
     */
    /** AnyModel не принял поток (stream) — дальше в этом запуске шлём обычным запросом. */
    @Volatile private var anyNoStream = false

    private fun anyChat(model: String, messages: JSONArray, maxTokens: Int, json: Boolean, onPart: ((String, String) -> Unit)? = null): Reply {
        if (anyKey.isBlank()) error("В сборке нет ключа ANYMODEL_KEY — проверь секрет на GitHub и пересобери")
        val id = model.removePrefix("any:")
        val think = model == Prefs.smartModel
        val effort = Prefs.effort.takeIf { it in setOf("low", "medium", "high") } ?: "medium"
        fun body(js: Boolean, reason: Boolean) = JSONObject().put("model", id).put("messages", messages)
            .put("max_tokens", maxTokens + if (reason) (if (effort == "high") 8000 else 4000) else 1500)   // рассуждения тоже считаются в лимит
            .also { if (js) it.put("response_format", JSONObject().put("type", "json_object")) }
            .also { if (reason) it.put("reasoning_effort", effort) }
        fun plain(b: JSONObject) = anyHttp.newCall(Request.Builder().url(ANY_URL).header("Authorization", "Bearer $anyKey")
            .post(b.toString().toRequestBody(JSON)).build()).execute().use { it.code to (it.body?.string() ?: "") }
        // поток: ответ приходит кусочками — мысль видна сразу; в конце собираем обычный JSON-ответ, дальше всё как раньше
        fun streamed(b: JSONObject, cb: (String, String) -> Unit): Pair<Int, String> {
            val sb = JSONObject(b.toString()).put("stream", true).put("stream_options", JSONObject().put("include_usage", true))
            anyHttp.newCall(Request.Builder().url(ANY_URL).header("Authorization", "Bearer $anyKey")
                .post(sb.toString().toRequestBody(JSON)).build()).execute().use { resp ->
                if (!resp.isSuccessful) return resp.code to (resp.body?.string() ?: "")
                val src = resp.body?.source() ?: return resp.code to ""
                if (!resp.header("Content-Type").orEmpty().contains("event-stream", true)) return resp.code to src.readUtf8()
                val content = StringBuilder(); val reason = StringBuilder(); var usage: JSONObject? = null
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val j = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    j.optJSONObject("usage")?.let { usage = it }
                    val d = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    fun str(k: String) = if (d.isNull(k)) "" else d.optString(k, "")
                    val c = str("content"); val r = str("reasoning_content").ifEmpty { str("reasoning") }
                    if (c.isNotEmpty()) content.append(c)
                    if (r.isNotEmpty()) reason.append(r)
                    if (c.isNotEmpty() || r.isNotEmpty()) runCatching { cb(content.toString(), reason.toString()) }
                }
                if (content.isEmpty()) return 599 to "пустой поток"
                return 200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                    JSONObject().put("role", "assistant").put("content", content.toString())))).put("usage", usage ?: JSONObject()).toString()
            }
        }
        fun send(b: JSONObject): Pair<Int, String> {
            if (onPart == null || anyNoStream) return plain(b)
            val r = streamed(b, onPart)
            if (r.first == 599 || r.first == 400) { if (r.first == 599) anyNoStream = true; return plain(b) }
            return r
        }
        var sent = body(json, think)
        var r = send(sent)
        if (r.first == 400) { sent = JSONObject().put("model", id).put("messages", messages); r = send(sent) }
        fun notFound(x: Pair<Int, String>) = x.first !in 200..299 && (x.first == 404 ||
            Regex("model.{0,30}(not found|does not exist|unknown)|unknown model|не найден", RegexOption.IGNORE_CASE).containsMatchIn(x.second))
        // перегрузка, а у уже отвечавшей модели — и «не найдена»: это сбой на стороне AnyModel/провайдера, а не опечатка в названии
        var tries = 0
        while (tries < 3 && (r.first in setOf(429, 500, 502, 503, 504) || (notFound(r) && id in anyWorked))) {
            Thread.sleep(3000L * (tries + 1)); r = send(sent); tries++
        }
        if (r.first == 401 || r.first == 403) error("AnyModel: ключ не принят (${r.first}) — проверь секрет ANYMODEL_KEY")
        if (r.first == 402 || (r.first !in 200..299 && Regex("insufficient|balance|quota", RegexOption.IGNORE_CASE).containsMatchIn(r.second)))
            throw NoMoney("на AnyModel закончился баланс")
        if (notFound(r)) {
            val sim = if (id in anyWorked) emptyList() else similarAny(id)
            error("AnyModel не нашёл модель «$id»" + (if (id in anyWorked) " (раньше отвечала — сбой на их стороне, повторы не помогли)" else "") +
                ". Ответ: ${r.second.take(160)}" + if (sim.isEmpty()) "" else ". Похожие у него: ${sim.joinToString(", ")}")
        }
        if (r.first !in 200..299) error("AnyModel «$id» ответил ${r.first}: ${r.second.take(200)}")
        val j = JSONObject(r.second)
        val text = content(j.getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
        val u = j.optJSONObject("usage") ?: JSONObject()
        lastUsed = model
        anyWorked += id
        return Reply(text, u.optInt("prompt_tokens"), u.optInt("completion_tokens"), 0.0)
    }

    /** Названия из списка моделей AnyModel (GET /v1/models), похожие на то, что не нашлось — чтобы поправить название. */
    fun similarAny(id: String): List<String> = runCatching {
        val r = anyHttp.newCall(Request.Builder().url("https://anymodel.org/v1/models").header("Authorization", "Bearer $anyKey").get().build())
            .execute().use { it.code to (it.body?.string() ?: "") }
        if (r.first !in 200..299) return@runCatching emptyList()
        val a = JSONObject(r.second).optJSONArray("data") ?: return@runCatching emptyList()
        val all = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
        fun words(s: String) = s.lowercase().substringAfter('/').split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }.toSet()
        val want = words(id)
        all.map { it to words(it).intersect(want).size }.filter { it.second >= 1 }.sortedByDescending { it.second }.take(8).map { it.first }
    }.getOrDefault(emptyList())

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
    fun chat(model: String, messages: JSONArray, maxTokens: Int = 1200, json: Boolean = true, patient: Boolean = false,
             onPart: ((content: String, reasoning: String) -> Unit)? = null): Reply {
        if (isGoogle(model)) {
            // фон может подождать окно до 20 с, переписка — не больше 3 с, задача агента — до минуты на своей модели
            val wait = if (patient) 65_000L else if (model == Prefs.bg && model != Prefs.fastModel) 20_000L else 3_000L
            return googleRouted(model, messages, maxTokens, json, wait, patient).also { Online.ok() }
        }
        return try { chatRaw(model, messages, maxTokens, json, onPart).also { Online.ok() } }
        catch (e: java.io.IOException) { Online.fail(); throw e }
    }

    private fun chatRaw(model: String, messages: JSONArray, maxTokens: Int, json: Boolean, onPart: ((String, String) -> Unit)? = null): Reply =
        if (isChatGpt(model)) chatGpt(model, messages, onPart)
        else if (isAny(model)) anyChat(model, messages, maxTokens, json, onPart)
        else if (isYandex(model)) yandexChat(model, messages, maxTokens, json)
        else error("Модель «$model» больше не поддерживается (GPTunnel убран) — выбери модель в настройках")

    /**
     * Потоковый ответ (SSE) — для «быстрой полосы»: текст приходит по кусочкам через onDelta.
     * Возвращает полный собранный текст. Бросает исключение при ошибке сети/провайдера — вызывающий откатится на агента.
     */
    fun stream(model: String, messages: JSONArray, maxTokens: Int, onDelta: (String) -> Unit): String {
        return when {
            isChatGpt(model) -> {
                val r = ChatGptResponses.chat(ChatGptAccount.access(), model.removePrefix("chatgpt:"), messages, onDelta)
                lastStreamTok = r.inputTokens.toLong() + r.outputTokens
                lastUsed = model; whyNotWanted = ""
                r.text
            }
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
            isAny(model) -> {
                if (anyKey.isBlank()) error("нет ключа AnyModel")
                streamSse(ANY_URL, "Bearer $anyKey", model.removePrefix("any:"), messages, maxTokens, onDelta)
            }
            else -> error("модель «$model» не поддерживается")
        }
    }

    /** Сколько токенов ушло на последний поток (быстрая полоса): из usage, а если поток оборвали — оценка по длине. */
    @Volatile var lastStreamTok = 0L

    private fun streamSse(url: String, auth: String, id: String, messages: JSONArray, maxTokens: Int, onDelta: (String) -> Unit): String {
        val body = JSONObject().put("model", id).put("temperature", 0.4).put("max_tokens", maxTokens)
            .put("stream", true).put("messages", messages)
        if (url == ANY_URL) body.put("stream_options", JSONObject().put("include_usage", true))
        val req = Request.Builder().url(url).header("Authorization", auth)
            .post(body.toString().toRequestBody(JSON)).build()
        val sb = StringBuilder()
        var used = 0L
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("stream ${resp.code}")
                val src = resp.body?.source() ?: error("пустой поток")
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val j = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    j.optJSONObject("usage")?.let { u -> used = u.optLong("total_tokens", u.optLong("prompt_tokens") + u.optLong("completion_tokens")) }
                    val d = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val delta = if (d == null || d.isNull("content")) "" else d.optString("content", "")
                    if (delta.isNotEmpty()) { sb.append(delta); onDelta(delta) }
                }
            }
        } finally {
            lastStreamTok = if (used > 0) used else (messages.toString().length + sb.length) / 3L
        }
        return sb.toString()
    }
}

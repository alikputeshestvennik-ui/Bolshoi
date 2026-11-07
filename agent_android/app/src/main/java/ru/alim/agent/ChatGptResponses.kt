package ru.alim.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Responses API for an explicitly authorized ChatGPT-plan access token. */
internal object ChatGptResponses {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS).callTimeout(300, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class Result(val text: String, val inputTokens: Int, val outputTokens: Int)
    data class Model(val id: String, val title: String)

    fun models(token: String): List<Model> {
        require(token.isNotBlank()) { "Нет разрешения на использование подписки ChatGPT" }
        val req = Request.Builder().url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("ChatGPT: список моделей недоступен (${response.code})")
            val array = JSONObject(response.body?.string() ?: "{}").optJSONArray("models") ?: JSONArray()
            return (0 until array.length()).mapNotNull { i ->
                val m = array.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("slug")
                if (m.optString("visibility") != "list" || id.isBlank()) null
                else Model(id, m.optString("display_name", id))
            }
        }
    }

    fun chat(token: String, model: String, messages: JSONArray, onDelta: ((String) -> Unit)? = null): Result {
        require(token.isNotBlank()) { "Нет разрешения на использование подписки ChatGPT" }
        val input = JSONArray()
        val instructions = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val role = message.optString("role")
            val content = message.opt("content")
            if (role == "system" || role == "developer") {
                require(content is String) { "Неподдерживаемый формат инструкции" }
                instructions.add(content)
            } else {
                require(role == "user" || role == "assistant") { "Неподдерживаемая роль: $role" }
                val converted: Any = when (content) {
                    is String -> content
                    is JSONArray -> JSONArray().also { parts ->
                        for (n in 0 until content.length()) {
                            val part = content.getJSONObject(n)
                            when (part.optString("type")) {
                                "text" -> parts.put(JSONObject().put("type", if (role == "assistant") "output_text" else "input_text").put("text", part.getString("text")))
                                "image_url" -> {
                                    require(role == "user")
                                    val img = part.getJSONObject("image_url")
                                    parts.put(JSONObject().put("type", "input_image").put("image_url", img.getString("url")).put("detail", img.optString("detail", "auto")))
                                }
                                else -> error("Этот тип вложения не поддерживается подключением ChatGPT: ${part.optString("type")}")
                            }
                        }
                    }
                    else -> error("Неподдерживаемый формат сообщения")
                }
                input.put(JSONObject().put("role", role).put("content", converted))
            }
        }
        val body = JSONObject().put("model", model).put("input", input).put("store", false).put("stream", true)
        if (instructions.isNotEmpty()) body.put("instructions", instructions.joinToString("\n\n"))
        val request = Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val details = response.body?.string().orEmpty().take(1000)
                error("ChatGPT HTTP ${response.code}: $details")
            }
            val source = response.body?.source() ?: error("Пустой ответ ChatGPT")
            val text = StringBuilder()
            var completed = false
            var inTokens = 0
            var outTokens = 0
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val event = JSONObject(data)
                when (event.optString("type")) {
                    "response.output_text.delta" -> {
                        val delta = event.optString("delta")
                        text.append(delta)
                        onDelta?.invoke(delta)
                    }
                    "response.completed" -> {
                        check(event.getJSONObject("response").optString("status") == "completed") { "ChatGPT не завершил ответ" }
                        completed = true
                        val usage = event.optJSONObject("response")?.optJSONObject("usage")
                        inTokens = usage?.optInt("input_tokens") ?: 0
                        outTokens = usage?.optInt("output_tokens") ?: 0
                    }
                    "response.failed", "response.incomplete", "error" -> {
                        val details = event.optJSONObject("response")?.optJSONObject("error")
                            ?: event.optJSONObject("error")
                        error("ChatGPT: ${details?.optString("code") ?: event.optString("type")}")
                    }
                }
            }
            check(completed) { "Ответ ChatGPT прервался до завершения" }
            check(text.isNotBlank()) { "ChatGPT завершил запрос без текстового ответа" }
            return Result(text.toString(), inTokens, outTokens)
        }
    }
}

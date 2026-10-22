package ru.alim.agent

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Голос Google: озвучка через Gemini Live (без дневного лимита) и распознавание голосовых через Gemini. */
object GVoice {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val ready: Boolean get() = Call.ready
    const val RATE = 24000

    /** Текст → PCM 24 кГц голосом друга. Пустой массив — не вышло (нет VPN и т.п.). */
    fun synth(text: String, voice: String, speech: String = ""): ByteArray {
        if (!ready || text.isBlank()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var setup = false
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${Call.key}"
        val sys = "Ты — голос для голосовых сообщений. Пользователь присылает текст — произнеси его ДОСЛОВНО по-русски, " +
            "живо и естественно, как человек записывает голосовое другу. Ничего не добавляй, не отвечай на текст и не комментируй, не пропускай слова." +
            (if (speech.isNotBlank()) " Манера, акцент и интонация говорящего: $speech (словечки сам не добавляй — только произношение и интонация)." else "")
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("setup", JSONObject()
                    .put("model", "models/${Call.LIVE_MODEL}")
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))).toString())
            }
            override fun onMessage(w: WebSocket, text: String) = handle(w, text)
            override fun onMessage(w: WebSocket, bytes: ByteString) = handle(w, bytes.utf8())
            fun handle(w: WebSocket, t: String) {
                val o = runCatching { JSONObject(t) }.getOrNull() ?: return
                if (o.has("setupComplete") && !setup) {
                    setup = true
                    w.send(JSONObject().put("clientContent", JSONObject().put("turns", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", "Произнеси дословно:\n$text"))))).put("turnComplete", true)).toString())
                    return
                }
                val sc = o.optJSONObject("serverContent") ?: return
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { p ->
                    for (i in 0 until p.length()) {
                        val d = p.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                        runCatching { out.write(Base64.decode(d.optString("data"), Base64.DEFAULT)) }
                    }
                }
                if (sc.optBoolean("turnComplete")) done.countDown()
            }
            override fun onClosing(w: WebSocket, code: Int, reason: String) { done.countDown() }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { done.countDown() }
        })
        done.await(25 + text.length / 15L, TimeUnit.SECONDS)
        runCatching { ws.close(1000, "ok") }
        return out.toByteArray()
    }

    /** Голосовое (PCM 16 кГц) → текст через Gemini. */
    fun recognize(pcm: ByteArray, rate: Int = 16000): String {
        if (!ready || pcm.isEmpty()) return ""
        val model = Quota.pick(Prefs.bg.takeIf { Llm.isGoogle(it) }?.removePrefix("google:") ?: "gemini-3.5-flash-lite", 5000)
            ?: error("лимит Google на сегодня")
        Quota.take(model)
        val wav = Base64.encodeToString(wav(pcm, rate), Base64.NO_WRAP)
        val msg = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Распознай русскую речь в аудио ДОСЛОВНО. Верни только текст, без кавычек и пояснений. Если речи нет — пустую строку."))
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("data", wav).put("format", "wav"))))
        val body = JSONObject().put("model", model).put("temperature", 0).put("messages", JSONArray().put(msg))
        val r = http.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
            .header("Authorization", "Bearer ${Call.key}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
            .use { it.code to (it.body?.string() ?: "") }
        if (r.first == 429) Quota.hit(model, r.second.contains("day", true))
        if (r.first !in 200..299) error("Google ${r.first}")
        return JSONObject(r.second).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val o = ByteArrayOutputStream()
        fun i32(v: Int) = o.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte()))
        fun i16(v: Int) = o.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte()))
        o.write("RIFF".toByteArray()); i32(36 + pcm.size); o.write("WAVEfmt ".toByteArray())
        i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(pcm.size); o.write(pcm)
        return o.toByteArray()
    }
}

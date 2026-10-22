package ru.alim.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * «Глаза» для рук: описывает снимок экрана через Gemini Live (безлимит на бесплатном тарифе).
 * Нужен, только когда по структуре экрана непонятно, что на нём (карта, фото, картинка).
 */
object GEyes {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    val ready: Boolean get() = Call.ready

    /** base64 JPEG (без префикса) → текстовое описание. Пустая строка — не вышло (нет VPN и т.п.). */
    fun describe(b64: String, question: String): String {
        if (!ready || b64.isBlank()) return ""
        val sb = StringBuilder()
        val done = CountDownLatch(1)
        var setup = false
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${Call.key}"
        val sys = "Ты — глаза ассистента, который управляет телефоном. Тебе дают снимок экрана. Опиши по-русски кратко и по делу, " +
            "без воды: что это за приложение/экран, что на нём видно и что можно нажать. Не выдумывай того, чего не видно."
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("setup", JSONObject()
                    .put("model", "models/${Call.LIVE_MODEL}")
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT")))
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))).toString())
            }
            override fun onMessage(w: WebSocket, text: String) = handle(w, text)
            override fun onMessage(w: WebSocket, bytes: ByteString) = handle(w, bytes.utf8())
            fun handle(w: WebSocket, t: String) {
                val o = runCatching { JSONObject(t) }.getOrNull() ?: return
                if (o.has("setupComplete") && !setup) {
                    setup = true
                    val parts = JSONArray()
                        .put(JSONObject().put("text", question))
                        .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
                    w.send(JSONObject().put("clientContent", JSONObject()
                        .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                        .put("turnComplete", true)).toString())
                    return
                }
                val sc = o.optJSONObject("serverContent") ?: return
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { p ->
                    for (i in 0 until p.length()) p.optJSONObject(i)?.optString("text")?.let { sb.append(it) }
                }
                if (sc.optBoolean("turnComplete")) done.countDown()
            }
            override fun onClosing(w: WebSocket, code: Int, reason: String) { done.countDown() }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { done.countDown() }
        })
        done.await(30, TimeUnit.SECONDS)
        runCatching { ws.close(1000, "ok") }
        return sb.toString().trim()
    }
}

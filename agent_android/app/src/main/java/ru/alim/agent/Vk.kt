package ru.alim.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ВКонтакте Алима через новый VK ID (OAuth 2.1 + PKCE, id.vk.ru) и его приложение (VK_APP_ID).
 * Сторонний клиент его же аккаунта — ВК это разрешает. Сейчас только чтение: лента и стены.
 * Личные сообщения ВК сторонним приложениям обычно не отдаёт.
 */
object Vk {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private const val V = "5.199"
    private const val AUTH = "https://id.vk.ru/authorize"
    private const val TOKEN = "https://id.vk.ru/oauth2/auth"

    var token by mutableStateOf(""); private set
    var me by mutableStateOf("")
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    private var app: Context? = null

    val appId: String get() = BuildConfig.VK_APP_ID.trim()
    val configured: Boolean get() = appId.isNotBlank()
    val ready: Boolean get() = token.isNotBlank()

    /** Адрес, который Алим должен добавить в «Доверенные redirect URL» своего ВК-приложения. */
    val redirect: String get() = "vk$appId://vk.com/blank.html"

    private var verifier = ""
    private var state = ""

    val authUrl: String get() {
        verifier = randomStr(64)
        state = randomStr(24)
        val challenge = s256(verifier)
        val scope = "wall groups friends vkid.personal_info"
        return "$AUTH?response_type=code&client_id=$appId" +
            "&redirect_uri=${enc(redirect)}&scope=${enc(scope)}&state=$state" +
            "&code_challenge=$challenge&code_challenge_method=S256&lang_id=0"
    }

    fun init(ctx: Context) { app = ctx.applicationContext; if (token.isBlank()) token = sp().getString("tok", "").orEmpty(); if (ready && me.isBlank()) loadMe() }

    private fun sp() = EncryptedSharedPreferences.create(app!!, "vk_sec",
        MasterKey.Builder(app!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    /** Поймали redirect с кодом — меняем код на токен (в фоне). true — это наш redirect. */
    fun onRedirect(url: String): Boolean {
        if (!url.startsWith("vk$appId://")) return false
        val code = paramFrom(url, "code")
        val gotState = paramFrom(url, "state")
        val deviceId = paramFrom(url, "device_id")
        error = ""
        if (code == null || deviceId == null) { error = paramFrom(url, "error_description") ?: "вход не удался"; return true }
        if (gotState != state) { error = "проверка state не прошла"; return true }
        busy = true
        Thread {
            runCatching {
                val body = FormBody.Builder()
                    .add("grant_type", "authorization_code").add("code", code).add("code_verifier", verifier)
                    .add("client_id", appId).add("device_id", deviceId).add("redirect_uri", redirect).add("state", state).build()
                http.newCall(Request.Builder().url(TOKEN).post(body).build()).execute().use { r ->
                    val j = JSONObject(r.body?.string().orEmpty())
                    val t = j.optString("access_token")
                    if (t.isBlank()) error("VK ID: ${j.optString("error_description", j.optString("error", "нет токена"))}")
                    token = t; sp().edit().putString("tok", t).apply(); loadMe()
                }
            }.onFailure { error = it.message ?: "ошибка входа" }
            busy = false
        }.start()
        return true
    }

    fun logout() { token = ""; me = ""; runCatching { sp().edit().remove("tok").apply() } }

    private fun loadMe() {
        runCatching {
            call("users.get", mapOf()).optJSONArray("response")?.optJSONObject(0)?.let {
                me = listOfNotNull(it.optString("first_name"), it.optString("last_name")).joinToString(" ").trim()
            }
        }
    }

    private fun call(method: String, params: Map<String, String>): JSONObject {
        if (token.isBlank()) error("ВК не подключён")
        val q = (params + mapOf("access_token" to token, "v" to V, "lang" to "ru"))
            .entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        http.newCall(Request.Builder().url("https://api.vk.com/method/$method?$q").build()).execute().use { r ->
            val o = JSONObject(r.body?.string().orEmpty())
            o.optJSONObject("error")?.let { error("ВК: ${it.optString("error_msg")}") }
            return o
        }
    }

    private fun hhmm(ts: Long) = SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(ts * 1000L))

    fun feed(count: Int = 15): String {
        val r = call("newsfeed.get", mapOf("count" to count.coerceIn(1, 40).toString(), "filters" to "post"))
        val resp = r.optJSONObject("response") ?: return "лента пуста"
        val items = resp.optJSONArray("items") ?: return "лента пуста"
        val names = HashMap<Long, String>()
        resp.optJSONArray("groups")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { g -> names[-g.optLong("id")] = g.optString("name") } }
        resp.optJSONArray("profiles")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { p -> names[p.optLong("id")] = (p.optString("first_name") + " " + p.optString("last_name")).trim() } }
        val out = StringBuilder()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val who = names[it.optLong("source_id")] ?: "кто-то"
            val text = it.optString("text").replace('\n', ' ').take(200)
            if (text.isNotBlank()) out.append("• $who (${hhmm(it.optLong("date"))}): $text\n")
        }
        return out.toString().trim().ifBlank { "в ленте только медиа без текста" }
    }

    fun wall(who: String, count: Int = 10): String {
        val domain = who.trim().removePrefix("@").substringAfterLast("/")
        val r = call("wall.get", mapOf("domain" to domain, "count" to count.coerceIn(1, 30).toString()))
        val items = r.optJSONObject("response")?.optJSONArray("items") ?: return "не нашёл стену «$who»"
        if (items.length() == 0) return "на стене «$who» пусто"
        val out = StringBuilder("Стена «$who»:\n")
        for (i in 0 until items.length()) items.optJSONObject(i)?.let { out.append("• ${hhmm(it.optLong("date"))}: ${it.optString("text").replace('\n', ' ').take(220)}\n") }
        return out.toString().trim()
    }

    // ——— PKCE helpers ———
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun randomStr(n: Int): String {
        val cs = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val r = SecureRandom(); return buildString { repeat(n) { append(cs[r.nextInt(cs.length)]) } }
    }
    private fun s256(v: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(h, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }
}

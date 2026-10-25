package ru.alim.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ВКонтакте Алима через официальный VK API и его приложение (VK_APP_ID). Сторонний клиент его аккаунта — ВК это разрешает.
 * Сейчас только чтение: лента, стены, сообщества, друзья. Личные сообщения ВК сторонним приложениям обычно не отдаёт.
 */
object Vk {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private const val V = "5.199"

    var token by mutableStateOf(""); private set
    var me by mutableStateOf("")
    private var app: Context? = null

    val appId: String get() = BuildConfig.VK_APP_ID.trim()
    val configured: Boolean get() = appId.isNotBlank()
    val ready: Boolean get() = token.isNotBlank()

    // вход: implicit flow, токен прилетает в #фрагменте на blank.html
    const val REDIRECT = "https://oauth.vk.com/blank.html"
    val authUrl: String get() = "https://oauth.vk.com/authorize?client_id=$appId" +
        "&scope=friends,wall,groups,offline" +
        "&redirect_uri=$REDIRECT&display=mobile&response_type=token&v=$V&revoke=1"

    fun init(ctx: Context) { app = ctx.applicationContext; if (token.isBlank()) token = sp().getString("tok", "").orEmpty(); if (ready && me.isBlank()) loadMe() }

    private fun sp() = EncryptedSharedPreferences.create(app!!, "vk_sec",
        MasterKey.Builder(app!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    /** Разобрать итоговый URL входа. true — токен получен. */
    fun onRedirect(url: String): Boolean {
        val t = paramFrom(url, "access_token") ?: return false
        token = t
        runCatching { sp().edit().putString("tok", t).apply() }
        loadMe()
        return true
    }

    fun logout() { token = ""; me = ""; runCatching { sp().edit().remove("tok").apply() } }

    private fun loadMe() {
        runCatching {
            val r = call("users.get", mapOf())
            r.optJSONArray("response")?.optJSONObject(0)?.let { me = listOfNotNull(it.optString("first_name"), it.optString("last_name")).joinToString(" ").trim() }
        }
    }

    private fun call(method: String, params: Map<String, String>): JSONObject {
        if (token.isBlank()) error("ВК не подключён")
        val q = (params + mapOf("access_token" to token, "v" to V, "lang" to "ru"))
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        http.newCall(Request.Builder().url("https://api.vk.com/method/$method?$q").build()).execute().use { r ->
            val o = JSONObject(r.body?.string().orEmpty())
            o.optJSONObject("error")?.let { error("ВК: ${it.optString("error_msg")}") }
            return o
        }
    }

    private fun hhmm(ts: Long) = SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(ts * 1000L))

    /** Новости (лента). */
    fun feed(count: Int = 15): String {
        val r = call("newsfeed.get", mapOf("count" to count.coerceIn(1, 40).toString(), "filters" to "post"))
        val items = r.optJSONObject("response")?.optJSONArray("items") ?: return "лента пуста"
        val names = HashMap<Long, String>()
        r.optJSONObject("response")?.optJSONArray("groups")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { g -> names[-g.optLong("id")] = g.optString("name") } }
        r.optJSONObject("response")?.optJSONArray("profiles")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { p -> names[p.optLong("id")] = (p.optString("first_name") + " " + p.optString("last_name")).trim() } }
        val out = StringBuilder()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val who = names[it.optLong("source_id")] ?: "кто-то"
            val text = it.optString("text").replace('\n', ' ').take(200)
            if (text.isNotBlank()) out.append("• $who (${hhmm(it.optLong("date"))}): $text\n")
        }
        return out.toString().trim().ifBlank { "в ленте только медиа без текста" }
    }

    /** Стена пользователя или сообщества по короткому имени/ссылке. */
    fun wall(who: String, count: Int = 10): String {
        val domain = who.trim().removePrefix("@").substringAfterLast("/")
        val r = call("wall.get", mapOf("domain" to domain, "count" to count.coerceIn(1, 30).toString()))
        val items = r.optJSONObject("response")?.optJSONArray("items") ?: return "не нашёл стену «$who»"
        if (items.length() == 0) return "на стене «$who» пусто"
        val out = StringBuilder("Стена «$who»:\n")
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            out.append("• ${hhmm(it.optLong("date"))}: ${it.optString("text").replace('\n', ' ').take(220)}\n")
        }
        return out.toString().trim()
    }
}

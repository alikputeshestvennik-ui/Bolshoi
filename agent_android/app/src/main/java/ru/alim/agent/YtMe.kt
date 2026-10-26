package ru.alim.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Личный YouTube Алима через официальный YouTube Data API v3 (вход в его Google-аккаунт).
 * Новое по подпискам (фильтр по дате и любимым каналам), лайкнутые видео, добавление в свои плейлисты.
 * Всё показывается и играет внутри Пилота (встроенный плеер), без перехода в приложение YouTube. Нужен VPN.
 */
object YtMe {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private const val API = "https://www.googleapis.com/youtube/v3"
    const val REDIRECT = "http://localhost/pilot"

    var token by mutableStateOf(""); private set
    private var refresh = ""
    private var expiresAt = 0L
    var me by mutableStateOf("")
    var busy by mutableStateOf(false); private set
    var lastError by mutableStateOf(""); private set
    private var app: Context? = null

    val clientId: String get() = BuildConfig.YT_CLIENT_ID.trim()
    val clientSecret: String get() = BuildConfig.YT_CLIENT_SECRET.trim()
    val configured: Boolean get() = clientId.isNotBlank()
    val ready: Boolean get() = refresh.isNotBlank() || token.isNotBlank()

    private var verifier = ""
    private var state = ""
    val authUrl: String get() {
        verifier = rnd(64); state = rnd(16)
        val scope = "https://www.googleapis.com/auth/youtube.force-ssl"
        return "https://accounts.google.com/o/oauth2/v2/auth?client_id=$clientId" +
            "&redirect_uri=${enc(REDIRECT)}&response_type=code&scope=${enc(scope)}" +
            "&access_type=offline&prompt=consent&include_granted_scopes=true&state=$state" +
            "&code_challenge=${s256(verifier)}&code_challenge_method=S256"
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (refresh.isBlank()) refresh = sp().getString("rt", "").orEmpty()
        if (ready && me.isBlank()) Thread { runCatching { loadMe() } }.start()
    }

    private fun sp() = EncryptedSharedPreferences.create(app!!, "yt_sec",
        MasterKey.Builder(app!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    fun onRedirect(url: String): Boolean {
        if (!url.startsWith(REDIRECT)) return false
        val code = paramFrom(url, "code")
        lastError = ""
        if (code == null) { lastError = paramFrom(url, "error") ?: "вход не удался"; return true }
        busy = true
        Thread {
            runCatching {
                val body = FormBody.Builder().add("code", code).add("client_id", clientId).add("client_secret", clientSecret)
                    .add("redirect_uri", REDIRECT).add("grant_type", "authorization_code").add("code_verifier", verifier).build()
                http.newCall(Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()).execute().use { r ->
                    val j = JSONObject(r.body?.string().orEmpty())
                    val t = j.optString("access_token")
                    if (t.isBlank()) error("Google: ${j.optString("error_description", j.optString("error", "нет токена"))}")
                    token = t; expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3500) * 1000
                    j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { refresh = it; sp().edit().putString("rt", it).apply() }
                    loadMe()
                }
            }.onFailure { lastError = it.message ?: "ошибка входа" }
            busy = false
        }.start()
        return true
    }

    fun logout() { token = ""; refresh = ""; me = ""; favs = emptyList(); runCatching { sp().edit().clear().apply() } }

    private fun ensure() {
        if (token.isNotBlank() && System.currentTimeMillis() < expiresAt - 30_000) return
        if (refresh.isBlank()) error("YouTube не подключён")
        val body = FormBody.Builder().add("client_id", clientId).add("client_secret", clientSecret)
            .add("refresh_token", refresh).add("grant_type", "refresh_token").build()
        http.newCall(Request.Builder().url("https://oauth2.googleapis.com/token").post(body).build()).execute().use { r ->
            val j = JSONObject(r.body?.string().orEmpty())
            token = j.optString("access_token").ifBlank { error("не обновить токен Google") }
            expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3500) * 1000
        }
    }

    private fun get(path: String): JSONObject {
        ensure()
        http.newCall(Request.Builder().url("$API$path").header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("YouTube ${r.code}: " + runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty())
            return JSONObject(body)
        }
    }

    private fun loadMe() {
        me = get("/channels?part=snippet&mine=true").optJSONArray("items")?.optJSONObject(0)?.optJSONObject("snippet")?.optString("title").orEmpty()
    }

    // ——— любимые каналы ———
    data class Chan(val id: String, val title: String)
    var favs: List<Chan> by mutableStateOf(emptyList()); private set
    fun setFavs(list: List<Chan>) { favs = list; runCatching { sp().edit().putString("favs", org.json.JSONArray().also { a -> list.forEach { a.put(JSONObject().put("id", it.id).put("t", it.title)) } }.toString()).apply() } }
    private fun loadFavs() { runCatching { val a = org.json.JSONArray(sp().getString("favs", "[]")); favs = (0 until a.length()).map { Chan(a.getJSONObject(it).optString("id"), a.getJSONObject(it).optString("t")) } } }

    /** Все подписки — для выбора любимых. */
    fun subscriptions(): List<Chan> {
        val out = ArrayList<Chan>(); var page = ""
        do {
            val j = get("/subscriptions?part=snippet&mine=true&maxResults=50&order=alphabetical" + if (page.isNotBlank()) "&pageToken=$page" else "")
            j.optJSONArray("items")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.optJSONObject("snippet")?.let { s ->
                out += Chan(s.optJSONObject("resourceId")?.optString("channelId").orEmpty(), s.optString("title")) } }
            page = j.optString("nextPageToken")
        } while (page.isNotBlank() && out.size < 200)
        return out
    }

    private fun uploadsPlaylist(channelId: String): String? =
        get("/channels?part=contentDetails&id=$channelId").optJSONArray("items")?.optJSONObject(0)
            ?.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("uploads")?.takeIf { it.isNotBlank() }

    private fun iso(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))!!.time
    }.getOrDefault(0L)

    class Vid(val id: String, val title: String, val channel: String, val date: Long)

    /** Новые видео по подпискам за N дней. Если заданы любимые — только по ним, иначе по всем (до 15 каналов, чтобы не жечь квоту). */
    fun newFromSubs(days: Int, onlyFavs: Boolean): List<Vid> {
        loadFavs()
        val chans = if (onlyFavs && favs.isNotEmpty()) favs else favs.ifEmpty { subscriptions() }.take(15)
        val since = System.currentTimeMillis() - days.coerceIn(1, 90) * 86_400_000L
        val out = ArrayList<Vid>()
        for (ch in chans) {
            val up = runCatching { uploadsPlaylist(ch.id) }.getOrNull() ?: continue
            val j = runCatching { get("/playlistItems?part=snippet&maxResults=10&playlistId=$up") }.getOrNull() ?: continue
            val items = j.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val s = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val d = iso(s.optString("publishedAt"))
                val vidId = s.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                if (d >= since && vidId.isNotBlank()) out += Vid(vidId, s.optString("title"), ch.title, d)
            }
        }
        return out.filter { it.id.isNotBlank() }.sortedByDescending { it.date }.take(40)
    }

    fun liked(count: Int = 20): List<Vid> {
        val j = get("/videos?part=snippet&myRating=like&maxResults=${count.coerceIn(1, 40)}")
        val out = ArrayList<Vid>()
        j.optJSONArray("items")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { v ->
            val s = v.optJSONObject("snippet") ?: return@let
            out += Vid(v.optString("id"), s.optString("title"), s.optString("channelTitle"), iso(s.optString("publishedAt"))) } }
        return out
    }

    fun playlists(): List<Chan> {
        val j = get("/playlists?part=snippet&mine=true&maxResults=50")
        val out = ArrayList<Chan>()
        j.optJSONArray("items")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.let { p ->
            out += Chan(p.optString("id"), p.optJSONObject("snippet")?.optString("title").orEmpty()) } }
        return out
    }

    /** Добавить видео в плейлист по названию (или id). */
    fun addToPlaylist(playlist: String, videoId: String): String {
        val pl = playlists()
        val target = pl.firstOrNull { it.id == playlist } ?: pl.firstOrNull { it.title.contains(playlist.trim(), true) }
            ?: return "не нашёл плейлист «$playlist». Твои: " + pl.joinToString(", ") { it.title }
        val payload = JSONObject().put("snippet", JSONObject().put("playlistId", target.id)
            .put("resourceId", JSONObject().put("kind", "youtube#video").put("videoId", videoId)))
        ensure()
        http.newCall(Request.Builder().url("$API/playlistItems?part=snippet").header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            return if (r.isSuccessful) "добавил в «${target.title}»" else "не вышло: YouTube ${r.code}"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun rnd(n: Int): String { val cs = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"; val r = SecureRandom(); return buildString { repeat(n) { append(cs[r.nextInt(cs.length)]) } } }
    private fun s256(v: String): String = android.util.Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII)),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
}

package ru.alim.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Яндекс Музыка Алима: поиск треков/альбомов, его плейлисты, рекомендации. Токен — через вход в его аккаунт Яндекса.
 * Воспроизведение — открытием в приложении Яндекс Музыки (официальный плеер), чтобы не трогать потоковую выдачу.
 */
object YaMusic {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private const val API = "https://api.music.yandex.net"
    // известный публичный клиент мобильного приложения Яндекс Музыки
    private const val CLIENT = "23cabbbdc6cd418abb4b39c32c41195d"
    const val REDIRECT = "https://music.yandex.ru"
    val authUrl: String get() = "https://oauth.yandex.ru/authorize?response_type=token&client_id=$CLIENT&force_confirm=1"

    var token by mutableStateOf(""); private set
    var uid by mutableStateOf("")
    private var app: Context? = null
    val ready: Boolean get() = token.isNotBlank()

    fun init(ctx: Context) { app = ctx.applicationContext; if (token.isBlank()) token = sp().getString("tok", "").orEmpty(); if (ready && uid.isBlank()) runCatching { loadAccount() } }

    private fun sp() = EncryptedSharedPreferences.create(app!!, "ym_sec",
        MasterKey.Builder(app!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    fun onRedirect(url: String): Boolean {
        val t = paramFrom(url, "access_token") ?: return false
        token = t; runCatching { sp().edit().putString("tok", t).apply() }; runCatching { loadAccount() }
        return true
    }

    fun logout() { token = ""; uid = ""; runCatching { sp().edit().remove("tok").apply() } }

    private fun get(path: String): JSONObject {
        if (token.isBlank()) error("Музыка не подключена")
        http.newCall(Request.Builder().url("$API$path")
            .header("Authorization", "OAuth $token")
            .header("User-Agent", "YandexMusicAndroid/24023231").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("Музыка ${r.code}")
            return JSONObject(body)
        }
    }

    private fun loadAccount() { uid = get("/account/status").optJSONObject("result")?.optJSONObject("account")?.optString("uid").orEmpty() }

    private fun trackLine(t: JSONObject): String {
        val artists = t.optJSONArray("artists")?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.optString("title") } }?.joinToString(", ").orEmpty()
        return "${artists.ifBlank { "?" }} — ${t.optString("title")}"
    }

    /** Поиск. Возвращает список и запоминает первый трек для «поставь». */
    private var lastTrackId = ""
    private var lastAlbumId = ""
    fun search(query: String, count: Int = 8): String {
        val r = get("/search?type=all&page=0&text=" + URLEncoder.encode(query, "UTF-8"))
        val res = r.optJSONObject("result") ?: return "ничего не нашёл"
        val tracks = res.optJSONObject("tracks")?.optJSONArray("results")
        if (tracks != null && tracks.length() > 0) {
            tracks.optJSONObject(0)?.let { lastTrackId = it.optString("id"); it.optJSONArray("albums")?.optJSONObject(0)?.let { a -> lastAlbumId = a.optString("id") } }
            val out = StringBuilder()
            for (i in 0 until minOf(count, tracks.length())) tracks.optJSONObject(i)?.let { out.append("• ${trackLine(it)}\n") }
            return out.toString().trim()
        }
        val albums = res.optJSONObject("albums")?.optJSONArray("results")
        if (albums != null && albums.length() > 0) { lastAlbumId = albums.optJSONObject(0)?.optString("id").orEmpty(); return "Альбом: " + albums.optJSONObject(0)?.optString("title") }
        return "по «$query» ничего не нашёл"
    }

    /** Поставить трек: ищем и открываем в приложении Яндекс Музыки. */
    fun play(ctx: Context, query: String): String {
        val list = runCatching { search(query, 3) }.getOrElse { return it.message ?: "не вышло" }
        if (lastTrackId.isBlank()) return "не нашёл, что поставить"
        val deep = "yandexmusic://track/$lastTrackId"
        val web = "https://music.yandex.ru/track/$lastTrackId/?play=1"
        val ok = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deep)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrElse {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
        return if (ok) "ставлю в Яндекс Музыке: ${list.lineSequence().firstOrNull()?.removePrefix("• ")}" else "не открылась Яндекс Музыка"
    }

    /** Мои плейлисты. */
    fun myPlaylists(): String {
        if (uid.isBlank()) runCatching { loadAccount() }
        if (uid.isBlank()) return "не вижу аккаунт — переподключи Музыку"
        val arr = get("/users/$uid/playlists/list").optJSONArray("result") ?: return "плейлистов нет"
        if (arr.length() == 0) return "плейлистов нет"
        val out = StringBuilder("Твои плейлисты:\n")
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.append("• ${it.optString("title")} (${it.optInt("trackCount")} треков)\n") }
        return out.toString().trim()
    }
}

package ru.alim.agent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object ChatGptAccount {
    private lateinit var prefs: SharedPreferences
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var listener: ServerSocket? = null
    @Volatile var status = ""
        private set
    @Synchronized fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = EncryptedSharedPreferences.create(ctx, "chatgpt_account",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        if (!prefs.contains("host")) prefs.edit().putString("host", "urn:uuid:${UUID.randomUUID()}").commit()
    }
    private fun value(key: String) = prefs.getString(key, "").orEmpty()
    val email get() = if (::prefs.isInitialized) value("email") else ""
    val connected get() = ::prefs.isInitialized && value("access").isNotEmpty()
    val allowed get() = connected && value("scope").split(' ').contains("chatgpt.tokens.use.direct")
    val busy get() = listener != null
    val models: List<String> get() = if (!::prefs.isInitialized || !allowed) emptyList() else value("models").lines().filter { it.isNotBlank() }.map { "chatgpt:$it" }

    private fun fetch(url: String): JSONObject = http.newCall(Request.Builder().url(url).build()).execute().use {
        check(it.isSuccessful) { "OpenAI: не удалось получить настройки (${it.code})" }
        JSONObject(it.body?.string() ?: error("Пустой ответ OpenAI"))
    }
    private fun token(form: FormBody): JSONObject = http.newCall(Request.Builder()
        .url("https://auth.openai.com/api/accounts/oauth/token").post(form).build()).execute().use {
        check(it.isSuccessful) { "OpenAI: вход или обновление сессии не удались (${it.code}). Войди снова." }
        JSONObject(it.body?.string() ?: error("Пустой ответ OpenAI"))
    }
    private fun save(t: JSONObject) {
        require(t.optString("access_token").isNotBlank() && t.optString("token_type").equals("Bearer", true))
        val edit = prefs.edit().putString("access", t.getString("access_token"))
            .putLong("expiry", System.currentTimeMillis() + t.getLong("expires_in") * 1000)
        if (t.has("refresh_token")) edit.putString("refresh", t.getString("refresh_token"))
        if (t.has("scope")) edit.putString("scope", t.getString("scope"))
        if (t.has("id_token")) edit.putString("id", t.getString("id_token"))
        check(edit.commit()) { "Не удалось сохранить подключение" }
    }
    @Synchronized fun access(): String {
        check(allowed) { "Подключи ChatGPT и разреши использование подписки в настройках" }
        if (prefs.getLong("expiry", 0) < System.currentTimeMillis() + 60_000) {
            check(value("refresh").isNotBlank()) { "Войди в ChatGPT снова" }
            val t = token(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", value("client"))
                .add("refresh_token", value("refresh")).add("resource", "https://api.openai.com/v1").build())
            save(t)
            check(allowed) { "Разрешение на использование подписки отозвано" }
        }
        return value("access")
    }
    fun refreshModels() {
        val ids = ChatGptResponses.models(access()).map { it.id }
        prefs.edit().putString("models", ids.joinToString("\n")).commit()
    }
    fun cancel() { listener?.close() }

    suspend fun signIn(ctx: Context) = withContext(Dispatchers.IO) {
        init(ctx.applicationContext)
        check(!busy) { "Вход уже открыт" }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        listener = server
        val state = ChatGptIdentity.random(); val nonce = ChatGptIdentity.random(); val verifier = ChatGptIdentity.random()
        val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
        val client = value("client")
        try {
            val uri = Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon()
                .appendQueryParameter("client_id", client.ifBlank { "dynamic_agent_client" })
                .appendQueryParameter("ext_agent_host_id", value("host"))
                .appendQueryParameter("response_type", "code").appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                .appendQueryParameter("resource", "https://api.openai.com/v1")
                .appendQueryParameter("state", state).appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", ChatGptIdentity.challenge(verifier))
            if (client.isBlank()) uri.appendQueryParameter("agent_name_hint", "Тень")
            else if (value("id").isNotBlank()) uri.appendQueryParameter("id_token_hint", value("id"))
            if (connected && !allowed) uri.appendQueryParameter("prompt", "consent")
            withContext(Dispatchers.Main) { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            status = "Заверши вход в браузере и вернись в Тень"
            val deadline = System.currentTimeMillis() + 300_000
            var callback: Uri? = null
            while (callback == null && System.currentTimeMillis() < deadline) {
                server.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val first = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = first.split(' ').getOrNull(1).orEmpty()
                    val candidate = Uri.parse("http://127.0.0.1$path")
                    val valid = first.startsWith("GET ") && candidate.path == "/auth/callback" && candidate.getQueryParameter("state") == state
                    val text = if (valid) "Sign-in received. Return to Ten." else "Invalid callback."
                    socket.getOutputStream().write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${text.length}\r\n\r\n$text").toByteArray())
                    if (valid) callback = candidate
                }
            }
            val result = callback ?: error("Время входа истекло")
            require(result.getQueryParameter("iss").let { it == null || it == "https://auth.openai.com" }) { "Неверный сервер авторизации" }
            check(result.getQueryParameter("error") == null) { "Вход отменён" }
            val issued = result.getQueryParameter("client_id") ?: client
            require(issued.isNotBlank() && issued != "dynamic_agent_client" && (client.isBlank() || client == issued)) { "Неверная регистрация приложения" }
            prefs.edit().putString("client", issued).commit()
            val t = token(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", issued)
                .add("code", result.getQueryParameter("code") ?: error("Нет кода входа"))
                .add("code_verifier", verifier).add("redirect_uri", redirect).add("resource", "https://api.openai.com/v1").build())
            val claims = ChatGptIdentity.validate(t.getString("id_token"), issued, nonce, fetch("https://auth.openai.com/.well-known/jwks.json"))
            require(value("subject").isBlank() || value("subject") == claims.getString("sub")) { "Вход выполнен в другой аккаунт" }
            require(t.has("scope")) { "OpenAI не вернул разрешения" }
            synchronized(this@ChatGptAccount) {
                prefs.edit().putString("subject", claims.getString("sub")).putString("email", claims.optString("email")).commit()
                save(t)
            }
            status = if (allowed) { refreshModels(); "Подписка подключена" } else "Вход выполнен. Использование подписки не разрешено."
        } finally { server.close(); if (listener === server) listener = null }
    }

    @Synchronized fun signOut(): String {
        cancel()
        var revoked = value("refresh").isBlank()
        try {
            if (!revoked) http.newCall(Request.Builder().url("https://auth.openai.com/api/accounts/oauth/revoke")
                .post(FormBody.Builder().add("token", value("refresh")).add("token_type_hint", "refresh_token").add("client_id", value("client")).build())
                .build()).execute().use { revoked = it.isSuccessful }
        } catch (_: java.io.IOException) {
            revoked = false
        } finally {
            prefs.edit().remove("access").remove("refresh").remove("id").remove("scope").remove("models").remove("expiry").commit()
        }
        return if (revoked) "Отключено" else "Вышли на телефоне. Отзыв сессии не подтверждён — отключи Тень в настройках ChatGPT."
    }
}

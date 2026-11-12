package ru.alim.agent

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated loopback connection to Codex running on the phone. */
object CodexGateway {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(21, TimeUnit.MINUTES).callTimeout(22, TimeUnit.MINUTES).build()
    private fun store(ctx: Context): android.content.SharedPreferences {
        val key = MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(ctx.applicationContext, "codex_gateway", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    fun customUrl(ctx: Context): String = store(ctx).getString("custom_url", "").orEmpty()
    fun saveCustom(ctx: Context, address: String, token: String, model: String) {
        val parsed = address.trim().toHttpUrlOrNull() ?: error("Укажите HTTPS-адрес провайдера")
        require(parsed.isHttps && parsed.query == null && parsed.fragment == null) { "Нужен HTTPS-адрес без параметров" }
        require(Regex("^[a-zA-Z0-9._/:-]{1,128}$").matches(model.trim())) { "Укажите корректный идентификатор модели" }
        val edit=store(ctx).edit().putString("custom_url", parsed.toString().trimEnd('/'))
        if (token.isNotBlank()) edit.putString("custom_token", token.trim())
        edit.apply(); Prefs.compatibleModel = model.trim()
    }
    fun saveLocalToken(ctx: Context, token: String) {
        require(token.trim().length >= 32) { "Неверный ключ локального окружения" }
        store(ctx).edit().putString("local_token", token.trim()).apply()
    }
    fun saveProviderToken(ctx: Context, provider: String, token: String) {
        require(provider == "deepseek" || provider == "any")
        require(token.isNotBlank()) { "Введите ключ" }
        store(ctx).edit().putString("provider_" + provider, token.trim()).apply()
    }
    private fun token(ctx: Context): String {
        val automatic = EmbeddedRuntime.tokenOrEmpty(ctx)
        if (automatic.isNotBlank()) return automatic
        val manual = store(ctx).getString("local_token", "").orEmpty()
        if (manual.isNotBlank()) return manual
        return EmbeddedRuntime.awaitToken(ctx)
    }

    fun status(ctx: Context, taskId: String): JSONObject {
        val token = EmbeddedRuntime.tokenOrEmpty(ctx)
        if (token.isBlank()) return JSONObject().put("status", "preparing")
        val url = "http://127.0.0.1:8766/status?task_id=" + android.net.Uri.encode(taskId)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return http.newCall(request).execute().use { response ->
            val body = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) JSONObject().put("status", "waiting").put("error", body.optString("error"))
            else body
        }
    }

    fun cancel(ctx: Context, taskId: String = "default") {
        val token = token(ctx)
        val body = JSONObject().put("task_id", taskId)
        val request = Request.Builder().url("http://127.0.0.1:8766/cancel")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Не удалось остановить задачу" }
        }
    }
    data class Result(val text: String, val file: Docs.FileRef?)
    fun run(ctx: Context, goal: String, selected: String, taskId: String = "default"): Result {
        require(goal.length <= 16000) { "Задача слишком длинная" }
        EmbeddedRuntime.startAsync(ctx)
        val token = token(ctx)
        require(token.isNotBlank()) { "Локальное окружение ещё готовится" }
        val provider = when {
            Llm.isDeepSeek(selected) -> "deepseek"
            Llm.isAny(selected) -> "any"
            selected.startsWith("compatible:") -> "compatible"
            selected.startsWith("chatgpt:") || Llm.isChatGpt(selected) -> "chatgpt"
            else -> ""
        }
        val providerToken = if (provider == "chatgpt") {
            ChatGptAccount.access()
        } else {
            store(ctx).getString("provider_" + provider, "").orEmpty().ifBlank {
                when (provider) {
                    "deepseek" -> BuildConfig.DEEPSEEK_API.trim()
                    "any" -> Llm.anyKey
                    "compatible" -> store(ctx).getString("custom_token", "").orEmpty()
                    else -> ""
                }
            }
        }
        val body = JSONObject().put("goal", goal).put("selected", selected)
            .put("provider_token", providerToken).put("task_id", taskId)
        if (provider == "compatible") body.put("base_url", customUrl(ctx))
        val ghToken = BuildConfig.GH_PAT.trim()
        if (ghToken.isNotBlank()) body.put("gh_token", ghToken)
        val request = Request.Builder().url("http://127.0.0.1:8766/run")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            val result = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) error(result.optString("error", "Ошибка локального окружения"))
            Result(result.getString("result"), null)
        }
    }
}

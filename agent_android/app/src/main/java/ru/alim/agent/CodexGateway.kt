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
    data class Result(val text: String, val file: Docs.FileRef?)
    fun run(ctx: Context, goal: String, selected: String): Result {
        val token = store(ctx).getString("local_token", "").orEmpty()
        require(token.isNotBlank()) { "Подключи локальное окружение в настройках" }
        require(goal.length <= 16000) { "Задача слишком длинная" }
        val provider = when { Llm.isDeepSeek(selected) -> "deepseek"; Llm.isAny(selected) -> "any"; else -> "" }
        val providerToken = store(ctx).getString("provider_" + provider, "").orEmpty().ifBlank {
            when (provider) { "deepseek" -> BuildConfig.DEEPSEEK_API.trim(); "any" -> Llm.anyKey; else -> "" }
        }
        val request = Request.Builder().url("http://127.0.0.1:8766/run")
            .header("Authorization", "Bearer $token")
            .post(JSONObject().put("goal", goal).put("selected", selected).put("provider_token", providerToken).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            val result = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) error(result.optString("error", "Ошибка локального окружения"))
            Result(result.getString("result"), null)
        }
    }
}

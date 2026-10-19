package ru.alim.agent

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * ЮMoney только на чтение: баланс и история. Вход — OAuth внутри приложения: страница ЮMoney
 * пытается уйти на REDIRECT, мы перехватываем код и меняем его на токен. Права на платежи не просим.
 */
object Yoo {
    const val REDIRECT = "https://github.com/alikputeshestvennik-ui/Bolshoi/yoomoney"
    private const val SCOPE = "account-info operation-history"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    val clientId: String get() = BuildConfig.YOOMONEY_CLIENT_ID.trim()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("yoo", Context.MODE_PRIVATE)
    fun token(ctx: Context): String = sp(ctx).getString("t", "").orEmpty()
    fun connected(ctx: Context) = token(ctx).isNotBlank()
    fun disconnect(ctx: Context) = sp(ctx).edit().remove("t").apply()

    fun authBody(): ByteArray = ("client_id=" + enc(clientId) + "&response_type=code&redirect_uri=" + enc(REDIRECT) +
        "&scope=" + enc(SCOPE)).toByteArray()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Код из редиректа → постоянный токен. */
    fun exchange(ctx: Context, code: String) {
        val body = FormBody.Builder().add("code", code).add("client_id", clientId)
            .add("grant_type", "authorization_code").add("redirect_uri", REDIRECT).build()
        val txt = http.newCall(Request.Builder().url("https://yoomoney.ru/oauth/token").post(body).build()).execute()
            .use { it.body?.string().orEmpty() }
        val j = JSONObject(txt)
        val t = j.optString("access_token")
        if (t.isBlank()) error("ЮMoney не выдал доступ: ${j.optString("error", txt.take(120))}")
        sp(ctx).edit().putString("t", t).apply()
    }

    private fun api(ctx: Context, method: String, form: Map<String, String> = emptyMap()): JSONObject {
        val t = token(ctx)
        if (t.isBlank()) error("ЮMoney не подключён — Алим подключает его в «Подключениях»")
        val b = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(Request.Builder().url("https://yoomoney.ru/api/$method")
            .header("Authorization", "Bearer $t").post(b).build()).execute().use { r ->
            if (r.code == 401) { disconnect(ctx); error("доступ к ЮMoney отозван — подключи заново") }
            if (!r.isSuccessful) error("ЮMoney ответил ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
    }

    fun balance(ctx: Context): String {
        val j = api(ctx, "account-info")
        return "баланс ЮMoney: ${j.optString("balance")} ₽"
    }

    fun history(ctx: Context, days: Int, limit: Int): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val from = iso.format(Date(System.currentTimeMillis() - days * 86_400_000L))
        val j = api(ctx, "operation-history", mapOf("from" to from, "records" to limit.coerceIn(1, 100).toString()))
        val ops = j.optJSONArray("operations") ?: return "операций нет"
        if (ops.length() == 0) return "за $days дн. операций нет"
        var inSum = 0.0; var outSum = 0.0
        val lines = (0 until ops.length()).map { ops.getJSONObject(it) }.map { o ->
            val amt = o.optDouble("amount"); val dirIn = o.optString("direction") == "in"
            if (dirIn) inSum += amt else outSum += amt
            "${o.optString("datetime").take(10)} ${if (dirIn) "+" else "−"}$amt ₽ ${o.optString("title")}"
        }
        return "за $days дн.: пришло ${"%.2f".format(inSum)} ₽, ушло ${"%.2f".format(outSum)} ₽\n" + lines.take(30).joinToString("\n")
    }
}

/** Вход в ЮMoney внутри приложения. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YooScreen(back: () -> Unit) {
    var status by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen("Подключить ЮMoney", back) {
        if (Yoo.clientId.isBlank()) {
            Text("В сборке нет YOOMONEY_CLIENT_ID — добавь секрет и пересобери.", style = body(15, color = Amber))
            return@Screen
        }
        status?.let { Text(it, style = body(14, color = if (done) Teal else Amber)) }
        if (done) { BigButton("Готово", back); return@Screen }
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (!url.startsWith(Yoo.REDIRECT)) return false
                        val code = request.url.getQueryParameter("code")
                        val err = request.url.getQueryParameter("error")
                        if (code.isNullOrBlank()) { status = "ЮMoney отказал: ${err ?: "нет кода"}"; return true }
                        status = "Получаю доступ…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Yoo.exchange(ctx.applicationContext, code) } }
                            r.onSuccess { status = "ЮMoney подключён: агент видит баланс и историю, платить не может."; done = true }
                                .onFailure { status = it.message }
                        }
                        return true
                    }
                }
                postUrl("https://yoomoney.ru/oauth/authorize", Yoo.authBody())
            }
        }, modifier = Modifier.fillMaxSize())
    }
}

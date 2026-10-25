package ru.alim.agent

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Общий экран входа через OAuth (VK, Яндекс и т.п.): открывает страницу авторизации сервиса,
 * ждёт, когда он перенаправит на нужный адрес с токеном (в #фрагменте или ?параметрах), и отдаёт весь URL.
 * Логин и пароль вводятся на официальной странице сервиса — приложение их не видит.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLogin(authUrl: String, redirectPrefix: String, onRedirect: (String) -> Unit, back: () -> Unit) {
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    private fun check(url: String?): Boolean {
                        if (url != null && url.startsWith(redirectPrefix)) { onRedirect(url); return true }
                        return false
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                        check(request?.url?.toString())
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (check(url)) view?.stopLoading() else super.onPageStarted(view, url, favicon)
                    }
                }
                loadUrl(authUrl)
            }
        }, modifier = Modifier.fillMaxSize())
    }
}

/** Достать значение из #фрагмента или ?запроса URL. */
fun paramFrom(url: String, key: String): String? {
    val tail = url.substringAfter('#', "").ifBlank { url.substringAfter('?', "") }
    return tail.split('&').map { it.split('=', limit = 2) }.firstOrNull { it.size == 2 && it[0] == key }
        ?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
}

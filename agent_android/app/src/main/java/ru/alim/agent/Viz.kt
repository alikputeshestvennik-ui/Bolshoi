package ru.alim.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Рисунки Тени: модель пишет маленькую страницу (SVG / canvas / немного JS), мы показываем её живой карточкой в чате.
 * Без сети и без доступа к файлам. Цвета — из текущей темы (переменные CSS и window.T).
 */
object Viz {
    const val MIME = "text/html"
    private val main = Handler(Looper.getMainLooper())

    /** Рисунки этой задачи по порядку — для вставки в PDF «[[рисунок 1]]». */
    val task = ArrayList<Docs.FileRef>()

    private fun hex(c: Color): String = String.format("#%06X", c.toArgb() and 0xFFFFFF)

    /** Серия цветов для графиков: в монохроме — оттенки серого, иначе цвета темы. */
    private fun series(): List<String> = seriesOf(Th.c)
    private fun seriesOf(c: Pal): List<String> {
        return if (c.square) (if (c.dark) listOf("#F2F2F0", "#A8A8A4", "#6E6E6A", "#4A4A47", "#CFCFCB") else listOf("#111111", "#5E5E5E", "#9A9A96", "#C4C4C0", "#383838"))
        else listOf(hex(c.accent), hex(c.violet), hex(c.amber), hex(c.danger), hex(c.muted))
    }

    /** Обёртка: тема, базовые стили, перехват ошибок. Страница модели идёт внутрь как есть. */
    fun wrap(html: String, paper: Boolean = false): String {
        val c0 = Th.c
        // для PDF — всегда светлая бумага
        val c = if (!paper || !c0.dark) c0 else (Th.all.firstOrNull { it.id == "mono" && c0.square } ?: Th.all.firstOrNull { !it.dark } ?: c0)
        val s = if (paper && c0.dark && !c.dark) seriesOf(c) else series()
        val head = """<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>:root{--bg:${hex(c.card)};--page:${hex(c.bg)};--ink:${hex(c.ink)};--muted:${hex(c.muted)};--line:${hex(c.line)};--accent:${if (c.square) hex(c.ink) else hex(c.accent)};
--s1:${s[0]};--s2:${s[1]};--s3:${s[2]};--s4:${s[3]};--s5:${s[4]};--r:${if (c.square) "0px" else "10px"};color-scheme:${if (c.dark) "dark" else "light"}}
html,body{margin:0;padding:0;background:var(--bg);color:var(--ink);font-family:system-ui,-apple-system,Roboto,sans-serif;-webkit-tap-highlight-color:transparent}
*{box-sizing:border-box}${if (c.square) "*{border-radius:0!important}" else ""}
body{padding:12px;overflow-x:hidden}svg{max-width:100%}
button,input,select{font:inherit;color:inherit}</style>
<script>window.T={bg:'${hex(c.card)}',ink:'${hex(c.ink)}',muted:'${hex(c.muted)}',line:'${hex(c.line)}',accent:'${if (c.square) hex(c.ink) else hex(c.accent)}',dark:${c.dark},square:${c.square},series:['${s.joinToString("','")}']};
window.onerror=function(m,u,l){console.error('JS: '+m+' (строка '+l+')')};</script>"""
        val body = html.trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
        return if (Regex("<head[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(body)) body.replaceFirst(Regex("<head[^>]*>", RegexOption.IGNORE_CASE)) { it.value + head }
        else "<!doctype html><html><head><meta charset=\"utf-8\">$head</head><body>$body</body></html>"
    }

    fun save(ctx: Context, title: String, html: String): Docs.FileRef {
        val dir = File(ctx.filesDir, "viz").apply { mkdirs() }
        val f = File(dir, "${System.currentTimeMillis()}.html"); f.writeText(html)
        return Docs.FileRef(title.trim().ifBlank { "Рисунок" }.take(60) + ".html", Uri.fromFile(f), MIME)
    }

    fun read(ref: Docs.FileRef): String = runCatching { File(ref.uri.path!!).readText() }.getOrDefault("<p>рисунок не найден</p>")

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(wv: WebView, onConsole: ((String) -> Unit)? = null) {
        wv.settings.javaScriptEnabled = true
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.blockNetworkLoads = true
        wv.settings.domStorageEnabled = false
        wv.setBackgroundColor(Th.c.card.toArgb())
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", null)
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) onConsole?.invoke(m.message().take(200))
                return true
            }
        }
    }

    class Shot(val bmp: Bitmap?, val errors: List<String>, val heightCss: Int)

    /** Невидимо отрисовать: ошибки JS (для самопроверки модели) и картинка (для PDF). */
    suspend fun shoot(ctx: Context, html: String, widthPx: Int = 1080, needBitmap: Boolean = true): Shot = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val wv = WebView(ctx.applicationContext)
            val errs = ArrayList<String>()
            setup(wv) { errs += it }
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            fun lay(h: Int) {
                wv.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                wv.layout(0, 0, widthPx, h)
            }
            lay(1600)
            var done = false
            fun finish(s: Shot) { if (done) return; done = true; runCatching { wv.destroy() }; if (cont.isActive) cont.resume(s) }
            val dens = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", null)
                override fun onPageFinished(view: WebView, url: String?) {
                    // даём скриптам дорисовать (анимации, canvas)
                    main.postDelayed({
                        wv.evaluateJavascript("Math.ceil(document.documentElement.scrollHeight)") { r ->
                            val hCss = r?.trim('"')?.toDoubleOrNull()?.toInt()?.coerceIn(100, 4000) ?: 500
                            if (!needBitmap) { finish(Shot(null, errs, hCss)); return@evaluateJavascript }
                            val hPx = (hCss * dens).toInt().coerceIn(200, 6000)
                            lay(hPx)
                            main.postDelayed({
                                val bmp = runCatching { Bitmap.createBitmap(widthPx, hPx, Bitmap.Config.ARGB_8888).also { b -> wv.draw(Canvas(b)) } }.getOrNull()
                                finish(Shot(bmp, errs, hCss))
                            }, 500)
                        }
                    }, 1200)
                }
            }
            main.postDelayed({ finish(Shot(null, errs + "не дорисовалось за 12 с", 0)) }, 12_000)
            cont.invokeOnCancellation { main.post { finish(Shot(null, errs, 0)) } }
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
        }
    }

    /** Системная печать → «Сохранить как PDF». */
    fun print(ctx: Context, wv: WebView, title: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        runCatching { pm.print(title, wv.createPrintDocumentAdapter(title), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()) }
    }
}

@Composable
private fun VizWeb(html: String, modifier: Modifier, onReady: (WebView) -> Unit = {}) {
    AndroidView(factory = { c -> WebView(c).also { wv -> Viz.setup(wv); wv.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null); onReady(wv) } },
        update = { }, modifier = modifier)
}

/** Живой рисунок в ленте: заголовок, сама страница, «Развернуть» на весь экран, «В PDF». */
@Composable
fun VizCard(ref: Docs.FileRef) {
    val ctx = LocalContext.current
    val html = remember(ref.uri) { Viz.wrap(Viz.read(ref)) }
    var full by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val title = ref.name.removeSuffix(".html")
    val shape = shp(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Th.c.card).border(1.dp, Th.c.line, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = body(13, FontWeight.SemiBold, Th.c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("В PDF", style = body(12, FontWeight.SemiBold, Th.c.muted), modifier = Modifier.clip(shp(8.dp))
                .clickable(onClickLabel = "Сохранить рисунок в PDF") { web?.let { Viz.print(ctx, it, title) } }.padding(horizontal = 10.dp, vertical = 10.dp))
            Text("Развернуть", style = body(12, FontWeight.SemiBold, Th.c.ink), modifier = Modifier.clip(shp(8.dp))
                .clickable(onClickLabel = "Развернуть рисунок") { full = true }.padding(horizontal = 10.dp, vertical = 10.dp))
        }
        VizWeb(html, Modifier.fillMaxWidth().height(340.dp)) { web = it }
    }
    if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Th.c.card).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = body(15, FontWeight.Bold, Th.c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                Box(Modifier.clip(shp(8.dp)).clickable(onClickLabel = "Закрыть") { full = false }.padding(12.dp)) {
                    Text("Закрыть", style = body(13, FontWeight.SemiBold, Th.c.ink))
                }
            }
            VizWeb(html, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

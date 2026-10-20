package ru.alim.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Невидимый встроенный браузер: открывает страницу как настоящий Chrome (с JS и куками), ждёт загрузки
 * и выполняет скрипт, который вытаскивает нужное. Так читаются сайты, которые не отдают страницу простым запросом.
 */
object WebReader {
    private val main = Handler(Looper.getMainLooper())

    private fun decode(r: String?): String =
        runCatching { JSONArray("[${r ?: "null"}]").optString(0, "") }.getOrDefault("")

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(ctx: Context, url: String, js: String, ready: (String) -> Boolean, maxMs: Long = 30_000): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val wv = WebView(ctx.applicationContext)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.loadsImagesAutomatically = false
                // размер «экрана телефона», иначе часть сайтов не рисует выдачу
                wv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                wv.layout(0, 0, 1080, 2400)
                var done = false
                var last = ""
                fun finish(r: String) {
                    if (done) return
                    done = true
                    runCatching { wv.stopLoading(); wv.destroy() }
                    if (cont.isActive) cont.resume(r)
                }
                // каждые 2 с пробуем вытащить данные; готово — отдаём
                val tick = object : Runnable {
                    override fun run() {
                        if (done) return
                        wv.evaluateJavascript(js) { res ->
                            last = decode(res)
                            if (ready(last)) finish(last) else main.postDelayed(this, 2000)
                        }
                    }
                }
                wv.webViewClient = object : WebViewClient() {}
                main.postDelayed(tick, 3500)
                main.postDelayed({ finish(last) }, maxMs)
                cont.invokeOnCancellation { main.post { finish(last) } }
                wv.loadUrl(url)
            }
        }

    /** Текст любой страницы (для сайтов на JS). */
    suspend fun text(ctx: Context, url: String): String {
        val js = "(function(){return (document.body?document.body.innerText:'').replace(/\\n{3,}/g,'\\n\\n').slice(0,6000)})()"
        return run(ctx, url, js, { it.length > 400 }, 20_000).ifBlank { "страница не загрузилась" }
    }
}

/** Поиск товаров: Ozon через невидимый браузер, Wildberries через их открытый поиск. */
object Shop {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    const val OZON_PKG = "ru.ozon.app.android"
    const val WB_PKG = "com.wildberries.ru"

    class Item(val title: String, val price: String, val url: String, val img: String = "", val store: String = "", var size: String = "")

    private val OZON_JS = """
(function(){
  var out=[], seen={};
  document.querySelectorAll('a[href*="/product/"]').forEach(function(a){
    var h=a.href.split('?')[0]; if(seen[h]) return;
    var box=a.closest('[data-index]')||a.parentElement&&a.parentElement.parentElement||a;
    var t=(box.innerText||'').replace(/\s+/g,' ').trim();
    if(t.length<12) return; seen[h]=1; var im=box.querySelector('img'); out.push({u:h,t:t.slice(0,260),i:im?(im.currentSrc||im.src||''):''});
  });
  var b=document.body?document.body.innerText:'';
  return JSON.stringify({items:out.slice(0,20),blocked:/Доступ ограничен|проверяем|captcha|robot/i.test(b.slice(0,800)),text:b.slice(0,600)});
})()
""".trimIndent()

    suspend fun ozon(ctx: Context, query: String, maxPrice: Int?): List<Item> {
        val price = maxPrice?.let { "&currency_price=0.000%3B$it.000" }.orEmpty()
        val url = "https://www.ozon.ru/search/?text=" + URLEncoder.encode(query, "UTF-8") + "&from_global=true" + price
        val raw = WebReader.run(ctx, url, OZON_JS, { r ->
            runCatching { JSONObject(r).optJSONArray("items")?.length() ?: 0 }.getOrDefault(0) >= 4
        })
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: error("Ozon не открылся")
        val a = j.optJSONArray("items") ?: JSONArray()
        if (a.length() == 0) error(if (j.optBoolean("blocked")) "Ozon показал проверку на робота — открою поиск в приложении" else "Ozon ничего не показал")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val t = o.optString("t")
            val p = Regex("(\\d[\\d\\s  ]*)\\s?₽").find(t)?.value?.replace(Regex("[\\s  ]"), " ").orEmpty()
            Item(t.replace(Regex("(\\d[\\d\\s  ]*\\s?₽\\s*)+"), "").trim().take(150), p, o.optString("u"), o.optString("i"), "Ozon")
        }
    }

    /** Открытый поиск WB: версии API меняются — пробуем несколько. */
    fun wb(query: String, maxPrice: Int?): List<Item> {
        val q = URLEncoder.encode(query, "UTF-8")
        val pr = maxPrice?.let { "&priceU=0;${it * 100}" }.orEmpty()
        for (v in listOf("v13", "v9", "v7", "v5", "v4")) {
            val url = "https://search.wb.ru/exactmatch/ru/common/$v/search?appType=1&curr=rub&dest=-1257786&lang=ru&resultset=catalog&sort=popular&spp=30&query=$q$pr"
            val txt = runCatching {
                http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/126 Mobile").build())
                    .execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            }.getOrDefault("")
            if (txt.isBlank()) continue
            val j = runCatching { JSONObject(txt) }.getOrNull() ?: continue
            val prods = j.optJSONObject("data")?.optJSONArray("products") ?: j.optJSONArray("products") ?: continue
            if (prods.length() == 0) continue
            return (0 until minOf(20, prods.length())).map { prods.getJSONObject(it) }.map { p ->
                val kop = p.optLong("salePriceU").takeIf { it > 0 }
                    ?: p.optJSONArray("sizes")?.optJSONObject(0)?.optJSONObject("price")?.optLong("product")?.takeIf { it > 0 }
                    ?: 0L
                Item("${p.optString("brand")} ${p.optString("name")}".trim(), if (kop > 0) "${kop / 100} ₽" else "",
                    "https://www.wildberries.ru/catalog/${p.optLong("id")}/detail.aspx", "", "WB")
            }
        }
        error("Wildberries не ответил")
    }

    /** WB: остатки по размерам из карточки товара. */
    fun wbSize(url: String, size: String): String {
        val id = Regex("/catalog/(\\d+)/").find(url)?.groupValues?.get(1) ?: return "не понял номер товара"
        for (v in listOf("v4", "v2", "v1")) {
            val txt = runCatching {
                http.newCall(Request.Builder().url("https://card.wb.ru/cards/$v/detail?appType=1&curr=rub&dest=-1257786&spp=30&nm=$id")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/126 Mobile").build()).execute()
                    .use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            }.getOrDefault("")
            val j = runCatching { JSONObject(txt) }.getOrNull() ?: continue
            val p = (j.optJSONObject("data")?.optJSONArray("products") ?: j.optJSONArray("products"))?.optJSONObject(0) ?: continue
            val sizes = p.optJSONArray("sizes") ?: continue
            val all = (0 until sizes.length()).map { sizes.getJSONObject(it) }
            val names = all.map { s -> s.optString("origName").ifBlank { s.optString("name") } to ((s.optJSONArray("stocks")?.length() ?: 0) > 0) }
            val hit = names.firstOrNull { it.first.trim().startsWith(size) || it.first.split('-', '/', ' ').any { x -> x.trim() == size } }
            val avail = names.filter { it.second }.joinToString(", ") { it.first }
            return when {
                hit == null -> "размера $size нет в сетке (есть: ${avail.ifBlank { "ничего" }})"
                hit.second -> "размер $size ЕСТЬ в наличии"
                else -> "размер $size раскупили (в наличии: ${avail.ifBlank { "ничего" }})"
            }
        }
        return "WB не отдал карточку"
    }

    /** Ozon: читаем страницу товара в невидимом браузере, берём кусок про размеры. */
    suspend fun ozonSize(ctx: Context, url: String, size: String): String {
        val js = "(function(){var b=document.body?document.body.innerText:'';var i=b.search(/Размер|РАЗМЕР|Выберите размер/);return i<0?'':b.slice(i,i+700)})()"
        val t = WebReader.run(ctx, url, js, { it.length > 60 }, 25_000).replace(Regex("\\s+"), " ").trim()
        return if (t.length < 20) "Ozon не показал размеры (открой карточку)" else "размеры на странице: ${t.take(400)} (ищи $size)"
    }

    fun format(store: String, items: List<Item>, limit: Int = 8): String =
        "$store, найдено ${items.size}:\n" + items.take(limit).mapIndexed { i, it -> "${i + 1}. ${it.title} — ${it.price.ifBlank { "цена ?" }} — ${it.url}" }.joinToString("\n")
}

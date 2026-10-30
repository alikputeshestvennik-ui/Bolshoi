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

    class Item(val title: String, val price: String, val url: String, val img: String = "", val store: String = "",
               var size: String = "", var seller: String = "", var original: Boolean = false, val badges: String = "")

    fun toJson(l: List<Item>): String = JSONArray().also { a ->
        l.forEach { a.put(JSONObject().put("t", it.title).put("p", it.price).put("u", it.url).put("i", it.img).put("s", it.store)
            .put("z", it.size).put("v", it.seller).put("o", it.original)) }
    }.toString()

    fun fromJson(s: String): List<Item> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.optString("t"), it.optString("p"), it.optString("u"), it.optString("i"), it.optString("s"),
                it.optString("z"), it.optString("v"), it.optBoolean("o"))
        }
    }.getOrDefault(emptyList())

    /** Цена числом (руб.). */
    fun rub(p: String): Int = p.filter { it.isDigit() }.take(7).toIntOrNull() ?: 0

    /** Бренды, у которых оригинал не бывает копеечным. */
    private val BRAND_FLOOR = mapOf("asics" to 4500, "nike" to 4500, "adidas" to 4000, "new balance" to 5000, "salomon" to 6000,
        "hoka" to 7000, "puma" to 3500, "reebok" to 3500, "on running" to 8000, "mizuno" to 5000, "saucony" to 5000,
        "jordan" to 7000, "converse" to 3500, "vans" to 3500, "columbia" to 4000, "the north face" to 6000)
    private val SHOUT = Regex("морков|реплик|копия|люкс|1:1|премиум качеств|ед\\.? осталось|осталось \\d+ ед|[-−]\\s?[7-9]\\d\\s?%", RegexOption.IGNORE_CASE)

    /** Подозрение на подделку: кричащие значки «−91%/осталось N ед» или цена ниже реальной для бренда. Пусто — норм. */
    fun suspect(it: Item, query: String): String {
        val text = (it.title + " " + it.badges).lowercase()
        // «реплика/копия/1:1» — подделка всегда; кричащие скидки и «осталось N ед» — только у брендовых вещей (у безымянных это обычный маркетинг)
        Regex("реплик|копия|1:1", RegexOption.IGNORE_CASE).find(text)?.let { m -> return "значок «${m.value.trim()}»" }
        val r = rub(it.price)
        val q = query.lowercase()
        val floor = BRAND_FLOOR.entries.firstOrNull { q.contains(it.key) || text.contains(it.key) } ?: return ""
        SHOUT.find(text)?.let { m -> return "значок «${m.value.trim()}»" }
        // порог из таблицы — для обуви и курток; мелкие вещи бренда стоят дешевле
        val k = when {
            Regex("носк|кепк|бейсболк|шапк|трус|бель|напульсник|сумк|рюкзак|шнурк|стельк").containsMatchIn(q + " " + text) -> 0.15
            Regex("футболк|майк|поло|шорт|лонгслив|топ\\b|легинс|лосин").containsMatchIn(q + " " + text) -> 0.3
            Regex("худи|толстовк|свитшот|брюк|штан|джогер|олимпийк").containsMatchIn(q + " " + text) -> 0.55
            else -> 1.0
        }
        val min = (floor.value * k).toInt()
        if (r in 1 until min) return "слишком дёшево для ${floor.key} ($r ₽)"
        return ""
    }

    /** Совпадает ли продавец: «Ozon Россия» ~ «OZON», «озон». */
    fun sellerMatch(want: String, have: String): Boolean {
        fun n(x: String) = x.lowercase().replace("озон", "ozon").replace("вайлдберриз", "wildberries").replace(Regex("[«»\"'.,]|ооо|ип "), " ")
        val w = n(want).split(Regex("\\s+")).filter { it.length >= 3 }
        if (w.isEmpty() || have.isBlank()) return false
        return n(have).contains(w.first())
    }

    private val OZON_JS = """
(function(){
  var out=[], seen={};
  document.querySelectorAll('a[href*="/product/"]').forEach(function(a){
    var h=a.href.split('?')[0]; if(seen[h]) return;
    var box=a.closest('[data-index]')||(a.parentElement&&a.parentElement.parentElement)||a;
    var all=(box.innerText||'').replace(/\s+/g,' ').trim();
    if(all.length<12) return;
    var title='';
    box.querySelectorAll('a[href*="/product/"] span, a[href*="/product/"]').forEach(function(e){
      var x=(e.innerText||'').replace(/\s+/g,' ').trim();
      if(x.length>title.length && x.length<300 && x.indexOf('₽')<0 && !/%|осталось|морков|распродажа/i.test(x)) title=x;
    });
    var pm=all.match(/(\d[\d\s\u2009\u00a0]*)\s?₽/);
    seen[h]=1; var im=box.querySelector('img');
    out.push({u:h,t:title,a:all.slice(0,300),p:pm?pm[0]:'',i:im?(im.currentSrc||im.src||''):''});
  });
  var b=document.body?document.body.innerText:'';
  return JSON.stringify({items:out.slice(0,24),blocked:/Доступ ограничен|проверяем|captcha|robot/i.test(b.slice(0,800)),text:b.slice(0,600)});
})()
""".trimIndent()

    private val OZON_ITEM_JS = """
(function(){
  window.scrollBy(0,1400);
  var b=document.body?document.body.innerText:''; var s=[];
  if(/Ozon Россия|OZON Россия/i.test(b)) s.push('Ozon Россия');
  document.querySelectorAll('a[href*="/seller/"]').forEach(function(a){var x=(a.innerText||'').replace(/\s+/g,' ').trim(); if(x&&x.length<60&&s.indexOf(x)<0)s.push(x)});
  var m=b.match(/(?:Продавец|Магазин)\s*\n+\s*([^\n]{2,50})/); if(m&&s.indexOf(m[1].trim())<0) s.push(m[1].trim());
  var i=b.search(/Выберите размер|Размер|РАЗМЕР/);
  var h=document.querySelector('h1'); var pm=b.match(/(\d[\d\s\u2009\u00a0]*)\s?₽/);
  var im=document.querySelector('img[src*="ozone"],img[srcset]');
  return JSON.stringify({seller:s.slice(0,3),orig:/Оригинал/.test(b),sizes:i<0?'':b.slice(i,i+500).replace(/\s+/g,' '),
    title:h?h.innerText.trim():document.title,price:pm?pm[0]:'',img:im?(im.currentSrc||im.src||''):'',n:b.length});
})()
""".trimIndent()

    class OzonInfo(val seller: String, val original: Boolean, val sizes: String, val title: String, val price: String, val img: String)

    /** Страница товара Ozon: продавец, отметка «Оригинал», размеры, цена. */
    suspend fun ozonInfo(ctx: Context, url: String): OzonInfo? {
        val raw = WebReader.run(ctx, url, OZON_ITEM_JS, { r ->
            runCatching { val j = JSONObject(r); j.optInt("n") > 1500 && (j.optJSONArray("seller")?.length() ?: 0) > 0 }.getOrDefault(false)
        }, 16_000)
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (j.optInt("n") < 300) return null
        val sel = j.optJSONArray("seller")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            .map { it.replace(Regex("\\s*(Подписаться|Подписка|Отписаться)\\s*", RegexOption.IGNORE_CASE), " ").trim() }
            .filterNot { it.isBlank() || it.equals("Перейти", true) || it.contains("магазин", true) && it.length < 12 }.distinct()
        val own = sel.any { it.contains("Ozon Россия", true) }
        // товары, которые продаёт сам Ozon, — оригинал по умолчанию, отметку «Оригинал» им не ставят
        return OzonInfo(sel.joinToString(" / "), j.optBoolean("orig") || own, j.optString("sizes"),
            j.optString("title"), j.optString("price").replace(Regex("[\\s\u2009\u00a0]+"), " "), j.optString("img"))
    }

    suspend fun ozon(ctx: Context, query: String, maxPrice: Int?, page: Int = 1): List<Item> {
        val price = maxPrice?.let { "&currency_price=0.000%3B$it.000" }.orEmpty()
        val url = "https://www.ozon.ru/search/?text=" + URLEncoder.encode(query, "UTF-8") + "&from_global=true" + price + if (page > 1) "&page=$page" else ""
        val raw = WebReader.run(ctx, url, OZON_JS, { r ->
            runCatching { JSONObject(r).optJSONArray("items")?.length() ?: 0 }.getOrDefault(0) >= 4
        })
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: error("Ozon не открылся")
        val a = j.optJSONArray("items") ?: JSONArray()
        if (a.length() == 0) error(if (j.optBoolean("blocked")) "Ozon показал проверку на робота — открою поиск в приложении" else "Ozon ничего не показал")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val all = o.optString("a")
            val p = o.optString("p").replace(Regex("[\\s\u2009\u00a0]+"), " ")
            val t = o.optString("t").ifBlank {
                all.replace(Regex("(\\d[\\d\\s\u2009\u00a0]*\\s?₽\\s*)+"), "")
                    .replace(Regex("\\+?\\d+ морков\\S*|распродажа|−?-?\\d+\\s?%|\\d+ ед\\.? осталось|осталось \\d+ ед\\.?", RegexOption.IGNORE_CASE), "")
            }.replace(Regex("\\s+"), " ").trim()
            Item(t.take(150), p, o.optString("u"), o.optString("i"), "Ozon", badges = all)
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
                    "https://www.wildberries.ru/catalog/${p.optLong("id")}/detail.aspx", "", "WB", seller = p.optString("supplier"))
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

    /** Ozon: размер по странице товара. */
    fun ozonSize(info: OzonInfo?, size: String): String {
        val t = info?.sizes.orEmpty()
        if (t.length < 10) return "Ozon не показал размеры (открой карточку)"
        val rx = Regex("(^|[^\\d])${Regex.escape(size)}([^\\d]|$)")
        return if (rx.containsMatchIn(t)) "размер $size есть в размерной сетке (Ozon показывает и распроданные — наличие уточни в карточке): ${t.take(200)}"
        else "размера $size не видно на странице: ${t.take(200)}"
    }

    fun format(store: String, items: List<Item>, limit: Int = 8): String =
        "$store, найдено ${items.size}:\n" + items.take(limit).mapIndexed { i, it -> "${i + 1}. ${it.title} — ${it.price.ifBlank { "цена ?" }}${if (it.seller.isNotBlank()) " — продавец ${it.seller}" else ""} — ${it.url}" }.joinToString("\n")
}

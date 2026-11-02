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
  // размеры на сайте Ozon часто спрятаны в выпадающий список «Выберите размер: 54 RU / XXL ▾» — раскрываем его один раз
  window.__szt=(window.__szt||0)+1;
  if(!window.__szo){
    var all=document.querySelectorAll('div,button,span,input');
    for(var q=0;q<all.length;q++){ var de=all[q]; var dx=(de.innerText||de.value||'').replace(/\s+/g,' ').trim();
      if(/^\d{2}(?:[-–]\d{2})?\s*(?:RU|RUS|EU)?\s*\/\s*[0-9A-Z]{1,4}$/i.test(dx)||/^выберите размер$/i.test(dx)){
        var dr=de.getBoundingClientRect(); if(dr.width<40||dr.width>700) continue;
        var dc=de.closest('[role=button],[role=combobox],[role=listbox],button,[tabindex]')||de;
        try{dc.click();}catch(z){} try{dc.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));}catch(z){}
        window.__szo=1; break; } } }
  // кнопки и строки размеров: неактивная (disabled, полупрозрачная, зачёркнутая, бледнее остальных, «нет в наличии») — раскупили
  var cand=[];
  var sizeRe=/^(\d{2}(?:[-–\/]\d{2})?(?:\s*\(?(?:RU|RUS|EU)\)?)?(?:\s*\/\s*\d?X{0,3}[SML]{1,2}|\s*\/\s*\d{2})?|\d?X{0,3}[SML](?:\s*\(\d{2}(?:[-–]\d{2})?\))?|XXL|XXXL)/i;
  document.querySelectorAll('li,[role=option],div,span,button').forEach(function(e){
    var t=(e.innerText||'').replace(/\s+/g,' ').trim(); if(t.length<4||t.length>70) return;
    var m=t.match(sizeRe); if(!m||m[0].length<2||m[0].length===t.length) return;
    if(/нет в наличии|сообщить о поступлении|законч|распрод|нет на складе/i.test(t)) cand.push({t:m[0].trim(),off:true,lum:0,hard:true});
  });
  document.querySelectorAll('button,div,span,label,a,li').forEach(function(e){
    if(e.children.length>2) return; var t=(e.innerText||'').replace(/\s+/g,' ').trim();
    if(t.length>20) return; var mm=t.match(sizeRe); if(!mm||mm[0].length!==t.length) return;
    var r=e.getBoundingClientRect(); if(r.width<14||r.width>170||r.height<14||r.height>100) return;
    var off=false, op=1, n=e;
    for(var d=0;d<4&&n;d++){ var cs=getComputedStyle(n); op*=parseFloat(cs.opacity||'1');
      if(n.disabled===true||n.getAttribute&&n.getAttribute('aria-disabled')==='true') off=true;
      if(/line-through/.test(cs.textDecorationLine||'')) off=true;
      if(/disabl|unavail|soldout|sold-out|inactive|outofstock/i.test((n.className&&n.className.baseVal!==undefined)?n.className.baseVal:(n.className||''))) off=true;
      n=n.parentElement; }
    if(op<0.6) off=true;
    var tt=(e.getAttribute('title')||'')+(e.parentElement?(e.parentElement.getAttribute('title')||''):'');
    if(/нет в наличии|закончил|распрод/i.test(tt)) off=true;
    var c=getComputedStyle(e).color.match(/\d+/g)||[0,0,0]; var lum=(+c[0])*0.3+(+c[1])*0.59+(+c[2])*0.11;
    cand.push({t:t,off:off,lum:lum});
  });
  // самые бледные надписи среди кнопок размеров — тоже «нет в наличии» (так Ozon рисует раскупленные)
  var soft=cand.filter(function(x){return !x.hard});
  if(soft.length>=3){ var ls=soft.map(function(x){return x.lum}).sort(function(a,b){return a-b}); var med=ls[Math.floor(ls.length/2)];
    soft.forEach(function(x){ if(x.lum-med>70) x.off=true; }); }
  var sz={};
  // «нет в наличии» текстом — решающее; иначе: хоть одна активная кнопка размера — есть
  cand.forEach(function(x){ if(x.hard){ sz[x.t]='off'; return; } if(sz[x.t]==='off'&&cand.some(function(y){return y.hard&&y.t===x.t})) return;
    if(sz[x.t]===undefined||(sz[x.t]==='off'&&!x.off)) sz[x.t]=x.off?'off':'ok'; });
  var szl=[]; for(var k in sz) szl.push(k+':'+sz[k]);
  return JSON.stringify({szt:window.__szt,sz:szl.length>=2?szl:[],seller:s.slice(0,3),orig:/Оригинал/.test(b),sizes:i<0?'':b.slice(i,i+500).replace(/\s+/g,' '),
    title:h?h.innerText.trim():document.title,price:pm?pm[0]:'',img:im?(im.currentSrc||im.src||''):'',n:b.length});
})()
""".trimIndent()

    class OzonInfo(val seller: String, val original: Boolean, val sizes: String, val title: String, val price: String, val img: String,
                   /** Кнопки размеров: «52» → true (можно выбрать) / false (неактивна, раскупили). Пусто — Ozon не показал. */
                   val avail: Map<String, Boolean> = emptyMap())

    /** Товары, уже показанные в этом разговоре (для «покажи ещё»). */
    val shown: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /** Страница товара Ozon: продавец, отметка «Оригинал», размеры, цена. */
    suspend fun ozonInfo(ctx: Context, url: String): OzonInfo? {
        val raw = WebReader.run(ctx, url, OZON_ITEM_JS, { r ->
            // ждём продавца и размеры (после раскрытия списка размеров — ещё пару тиков)
            runCatching { val j = JSONObject(r); j.optInt("n") > 1500 && (j.optJSONArray("seller")?.length() ?: 0) > 0 &&
                ((j.optJSONArray("sz")?.length() ?: 0) > 0 || j.optInt("szt") >= 4) }.getOrDefault(false)
        }, 22_000)
        val j = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (j.optInt("n") < 300) return null
        val sel = j.optJSONArray("seller")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            .map { it.replace(Regex("\\s*(Подписаться|Подписка|Отписаться)\\s*", RegexOption.IGNORE_CASE), " ").trim() }
            .filterNot { it.isBlank() || it.equals("Перейти", true) || it.contains("магазин", true) && it.length < 12 }.distinct()
        val own = sel.any { it.contains("Ozon Россия", true) }
        // товары, которые продаёт сам Ozon, — оригинал по умолчанию, отметку «Оригинал» им не ставят
        return OzonInfo(sel.joinToString(" / "), j.optBoolean("orig") || own, j.optString("sizes"),
            j.optString("title"), j.optString("price").replace(Regex("[\\s\u2009\u00a0]+"), " "), j.optString("img"),
            j.optJSONArray("sz")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).split(':').takeIf { p -> p.size == 2 }?.let { p -> p[0] to (p[1] == "ok") } }.toMap() }.orEmpty())
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
        // кнопки размеров на странице: активная — в наличии, неактивная — раскупили
        info?.avail?.takeIf { it.isNotEmpty() }?.let { av ->
            val hit = av.entries.firstOrNull { (k, _) -> k.equals(size, true) || Regex("\\d{2}").findAll(k).any { it.value == size } || k.substringBefore(' ').equals(size, true) }
            if (hit != null) return if (hit.value) "размер $size В НАЛИЧИИ (кнопка размера активна)" else "размер $size РАСКУПИЛИ (кнопка размера неактивна)"
        }
        val t = info?.sizes.orEmpty()
        if (t.length < 10) return "Ozon не показал размеры (открой карточку)"
        val rx = Regex("(^|[^\\d])${Regex.escape(size)}([^\\d]|$)")
        return if (rx.containsMatchIn(t)) "размер $size есть в размерной сетке (Ozon показывает и распроданные — наличие уточни в карточке): ${t.take(200)}"
        else "размера $size не видно на странице: ${t.take(200)}"
    }

    fun format(store: String, items: List<Item>, limit: Int = 8): String =
        "$store, найдено ${items.size}:\n" + items.take(limit).mapIndexed { i, it -> "${i + 1}. ${it.title} — ${it.price.ifBlank { "цена ?" }}${if (it.seller.isNotBlank()) " — продавец ${it.seller}" else ""} — ${it.url}" }.joinToString("\n")
}

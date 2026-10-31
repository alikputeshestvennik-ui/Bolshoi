package ru.alim.agent

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Богатый ответ на большую задачу: модель кладёт в финальный JSON «report» (маршрут, сравнение, места, погода, источники),
 * агент добавляет «log» — мысли и шаги. Всё живёт в Msg.best, поэтому сохраняется вместе с перепиской.
 */
object Rich {
    fun parse(s: String): JSONObject? = if (s.isBlank() || !s.trimStart().startsWith("{")) null else runCatching { JSONObject(s) }.getOrNull()
    fun hasWinner(o: JSONObject?) = o != null && o.optString("name").isNotBlank() && o.optString("value").isNotBlank()
    fun list(a: JSONArray?): List<JSONObject> = a?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) } }.orEmpty()
    fun strs(a: JSONArray?): List<String> = a?.let { x -> (0 until x.length()).map { x.optString(it).trim() }.filter { it.isNotBlank() } }.orEmpty()
}

private val icBus by lazy { lineIco("bus", "M6 17V6a3 3 0 0 1 3-3h6a3 3 0 0 1 3 3v11M6 11h12M8 17v3M16 17v3M9 14h.01M15 14h.01") }
private val icTrain by lazy { lineIco("train", "M8 3h8a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM5 10h14M8 21l2-4M16 21l-2-4") }
private val icPlane by lazy { lineIco("plane", "M2 16l20-6-3-2-7 2-6-5-2 1 4 6-4 1-2-1-1 1z") }
private val icBed by lazy { lineIco("bed", "M3 19V6M3 14h18v5M21 14v-2a3 3 0 0 0-3-3h-7v5M7 12a1.5 1.5 0 1 0 0-3a1.5 1.5 0 1 0 0 3") }
private val icPin by lazy { lineIco("pin", "M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21zM12 7a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5") }
private val icCar by lazy { lineIco("car", "M5 16V12l2-5h10l2 5v4M4 16h16M7 19v-3M17 19v-3M7.5 13h.01M16.5 13h.01") }
private val icWarn by lazy { lineIco("warn", "M12 3l10 18H2zM12 10v5M12 18v.5") }
private val icClock2 by lazy { lineIco("clock", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 7v5l3 2") }
private val icBulb2 by lazy { lineIco("bulb", "M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.8.7 1 1.5 1 2.5h6c0-1 .2-1.8 1-2.5A6 6 0 0 0 12 3z") }

private fun kindIcon(o: JSONObject): ImageVector {
    // сначала вид, который назвала модель; потом по тексту — автобус раньше самолёта («автобус аэропорт Сочи → Ялта»)
    when (o.optString("kind").lowercase().trim()) {
        "bus" -> return icBus; "train" -> return icTrain; "flight", "avia", "plane" -> return icPlane
        "stay", "hotel" -> return icBed; "taxi", "car" -> return icCar; "walk" -> return icPin
    }
    val k = o.optString("what").lowercase()
    return when {
        Regex("автобус|маршрутк").containsMatchIn(k) -> icBus
        Regex("поезд|сапсан|ласточк|стриж|электрич|«таврия»|таврия").containsMatchIn(k) -> icTrain
        Regex("рейс|самол|перел").containsMatchIn(k) -> icPlane
        Regex("train|поезд|сапсан|ласточк|электрич").containsMatchIn(k) -> icTrain
        Regex("bus|автобус|маршрутк").containsMatchIn(k) -> icBus
        Regex("stay|hotel|отел|ночь|ночёв|хостел|жиль").containsMatchIn(k) -> icBed
        Regex("taxi|такси|car|машин").containsMatchIn(k) -> icCar
        else -> icPin
    }
}

private fun plural(n: Int, one: String, few: String, many: String) =
    if (n % 10 == 1 && n % 100 != 11) one else if (n % 10 in 2..4 && n % 100 !in 12..14) few else many

private fun dur(secs: Long) = if (secs < 60) "$secs сек" else "${(secs + 30) / 60} мин"

private val green: Color get() = Th.c.online
private fun soft(c: Color) = c.copy(alpha = if (Th.c.dark) 0.16f else 0.10f)

@Composable
private fun SecLabel(t: String) = Text(t.uppercase(), style = body(11, FontWeight.Bold, Muted).copy(letterSpacing = 1.2.sp))

/** «Думал 12 мин · 14 шагов ›» над ответом: нажал — раскрываются мысли и шаги, как шли. */
@Composable
fun ThinkChip(p: JSONObject) {
    val log = Rich.list(p.optJSONArray("log"))
    if (log.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Th.c.card.copy(alpha = 0.85f)).border(1.dp, Th.c.line, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = if (open) "Свернуть" else "Как думал") { open = !open }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(rememberVectorPainter(icClock2), null, tint = Muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Думал ${dur(p.optLong("secs"))} · ${log.size} ${plural(log.size, "шаг", "шага", "шагов")}", style = body(12, FontWeight.SemiBold, Muted))
            Spacer(Modifier.width(6.dp))
            Text(if (open) "⌄" else "›", style = body(14, FontWeight.Bold, Muted))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            log.forEach { s ->
                val th = s.optString("t")
                if (th.isNotBlank()) Row {
                    Icon(rememberVectorPainter(icBulb2), null, tint = Muted, modifier = Modifier.padding(top = 2.dp).size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(th, style = body(13, color = Muted), lineHeight = 19.sp)
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Th.c.card).border(1.dp, Th.c.line, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", style = body(12, FontWeight.Bold, green))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.optString("s").replaceFirstChar { it.uppercase() }, style = body(12, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val d = s.optString("d")
                        if (d.isNotBlank()) Text(d, style = body(11, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Маршрут лесенкой: иконка вида транспорта, линия к следующему шагу, когда · что · подробности · источник. */
@Composable
private fun Ladder(steps: List<JSONObject>) {
    Column(Modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, s ->
            val last = i == steps.lastIndex
            val warn = s.optString("warn").isNotBlank()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(if (warn) soft(Amber) else AccentSoft), contentAlignment = Alignment.Center) {
                        Icon(rememberVectorPainter(kindIcon(s)), null, tint = if (warn) Amber else Teal, modifier = Modifier.size(16.dp))
                    }
                    if (!last) Box(Modifier.width(2.dp).weight(1f).background(Th.c.line))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (s.optString("when").isNotBlank()) Text(s.optString("when"), style = body(12, FontWeight.SemiBold, Teal))
                    Text(s.optString("what"), style = body(14, FontWeight.SemiBold))
                    if (s.optString("sub").isNotBlank()) Text(s.optString("sub"), style = body(12, color = Muted), lineHeight = 17.sp)
                    if (warn) Text(s.optString("warn"), style = body(12, FontWeight.SemiBold, Amber))
                }
                if (s.optString("src").isNotBlank()) Text(s.optString("src"), style = body(10, FontWeight.SemiBold, Muted),
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Th.c.bg).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}

private val http by lazy { OkHttpClient.Builder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build() }
private val wikiCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
private object NoImg

/** Фото места из Википедии: сначала статья по названию, иначе поиск; кэш в памяти. */
private suspend fun wikiThumb(q: String): ImageBitmap? = withContext(Dispatchers.IO) {
    wikiCache[q]?.let { return@withContext it as? ImageBitmap }
    fun get(url: String) = http.newCall(Request.Builder().url(url).header("User-Agent", "PilotAgent/1.0 (Android; personal assistant)").build()).execute().use { r ->
        if (r.isSuccessful) r.body?.string() else null
    }
    val img = runCatching {
        val enc = java.net.URLEncoder.encode(q.trim().replace(' ', '_'), "UTF-8")
        var src = get("https://ru.wikipedia.org/api/rest_v1/page/summary/$enc?redirect=true")?.let { JSONObject(it).optJSONObject("thumbnail")?.optString("source") }
        if (src.isNullOrBlank()) {
            val s = java.net.URLEncoder.encode(q, "UTF-8")
            src = get("https://ru.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=$s&gsrlimit=1&prop=pageimages&piprop=thumbnail&pithumbsize=400")
                ?.let { JSONObject(it).optJSONObject("query")?.optJSONObject("pages") }
                ?.let { pages -> pages.keys().asSequence().mapNotNull { pages.optJSONObject(it)?.optJSONObject("thumbnail")?.optString("source") }.firstOrNull() }
        }
        if (src.isNullOrBlank()) null
        else http.newCall(Request.Builder().url(src).header("User-Agent", "PilotAgent/1.0").build()).execute().use { r ->
            val b = r.body?.bytes() ?: return@use null
            BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
        }
    }.getOrNull()
    wikiCache[q] = img ?: NoImg
    img
}

@Composable
private fun WikiPhoto(q: String, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, q) { value = wikiThumb(q) }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, q, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(rememberVectorPainter(icPin), null, tint = Teal, modifier = Modifier.size(22.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
/** Карточка-отчёт вместо «листа»: вывод, маршрут, итоги, сравнение, места с фото, погода, лучший выбор, таблица, источники. */
@Composable
fun ReportCard(m: Msg, p: JSONObject, lit: Boolean, showButtons: Boolean, chat: ChatStore) {
    val r = p.optJSONObject("report") ?: return
    val ctx = LocalContext.current
    var alt by remember { mutableStateOf<JSONObject?>(null) }
    var full by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val hhmm = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru")) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().clip(shape).background(Th.c.card).border(1.dp, if (lit) Teal else Th.c.line, shape)
            .combinedClickable(onClick = {}, onLongClick = { ChatSel.toggle(m.id) }).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ——— вывод ———
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val k = r.optString("kicker").ifBlank { "ОТЧЁТ" }
                    Text(k.uppercase(), style = body(10, FontWeight.Bold, green).copy(letterSpacing = 1.sp),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(soft(green)).padding(horizontal = 8.dp, vertical = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text(hhmm.format(java.util.Date(m.ts)), style = body(12, color = Th.c.meta))
                }
                Row(verticalAlignment = Alignment.Top) {
                    Text(r.optString("title").ifBlank { m.text.lineSequence().first().take(60) }, style = TextStyle(fontFamily = Fonts.display, fontSize = 20.sp, lineHeight = 27.sp, color = Ink),
                        modifier = Modifier.weight(1f))
                    Icon(rememberVectorPainter(icShareUp), "Поделиться", tint = Muted, modifier = Modifier.size(40.dp).clickable { shareText(ctx, m.text) }.padding(9.dp))
                }
                if (r.optString("lead").isNotBlank()) Text(r.optString("lead"), style = body(14, color = Ink.copy(alpha = 0.86f)), lineHeight = 20.sp)
            }

            // ——— маршрут ———
            val route = Rich.list(r.optJSONArray("route"))
            if (route.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { SecLabel("Маршрут"); Ladder(route) }

            // ——— итоги ———
            val totals = Rich.list(r.optJSONArray("totals")).take(3)
            if (totals.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    totals.forEach { t ->
                        val w = t.optString("warn").isNotBlank()
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (w) soft(Amber) else Th.c.bg).padding(12.dp)) {
                            Text(t.optString("label"), style = body(11, color = Muted), maxLines = 2, lineHeight = 14.sp)
                            // значение не обрезаем: длинное («30 мин») мельче, а не «30»
                            val v = t.optString("value")
                            Text(v, style = TextStyle(fontFamily = Fonts.display, fontSize = if (v.length > 7) 14.sp else 17.sp, fontWeight = FontWeight.SemiBold, color = if (w) Amber else Ink), maxLines = 2)
                        }
                    }
                }
                totals.filter { it.optString("warn").isNotBlank() }.forEach { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(soft(Amber)).padding(12.dp)) {
                        Icon(rememberVectorPainter(icWarn), null, tint = Amber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t.optString("warn"), style = body(13, color = Ink), lineHeight = 18.sp)
                    }
                }
            }

            // ——— сравнение: нелучшие нажимаются — «почему нет» и всё равно купить ———
            val cmp = Rich.list(r.optJSONArray("compare"))
            if (cmp.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecLabel("Сравнение")
                cmp.forEach { c ->
                    val ok = c.optBoolean("ok", false)
                    val rs = RoundedCornerShape(14.dp)
                    Row(Modifier.fillMaxWidth().clip(rs).background(if (ok) soft(green) else Th.c.bg).then(if (ok) Modifier.border(1.5.dp, green, rs) else Modifier)
                        .then(if (!ok) Modifier.clickable(onClickLabel = "Почему нет") { alt = c } else Modifier).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(if (ok) green else Th.c.line), contentAlignment = Alignment.Center) {
                            Text(if (ok) "✓" else "✕", style = body(11, FontWeight.Bold, if (ok) Th.c.card else Muted))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.optString("name"), style = body(14, FontWeight.SemiBold, if (ok) Ink else Ink.copy(alpha = 0.8f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = if (ok) c.optString("time") else listOf(c.optString("time"), "почему нет ›").filter { it.isNotBlank() }.joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, style = body(12, color = if (ok) Muted else Teal), maxLines = 1)
                        }
                        Text(c.optString("price"), style = TextStyle(fontFamily = Fonts.display, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (ok) Ink else Muted))
                    }
                }
            }

            // ——— что посмотреть: фото из Википедии ———
            val places = Rich.list(r.optJSONArray("places")).take(5)
            if (places.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { SecLabel("Что посмотреть"); Spacer(Modifier.weight(1f)); Text("фото · Википедия", style = body(10, color = Muted)) }
                places.forEach { pl ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WikiPhoto(pl.optString("wiki").ifBlank { pl.optString("name") }, Modifier.size(72.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(pl.optString("name"), style = body(14, FontWeight.SemiBold))
                            if (pl.optString("info").isNotBlank()) Text(pl.optString("info"), style = body(12, color = Muted), lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (pl.optString("note").isNotBlank()) Text(pl.optString("note"), style = body(12, FontWeight.SemiBold, Teal))
                        }
                    }
                }
            }

            // ——— погода полосой ———
            val wx = Rich.list(r.optJSONArray("weather")).take(7)
            if (wx.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecLabel(r.optString("weather_title").ifBlank { "Погода" })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    wx.forEach { d ->
                        Column(Modifier.width(68.dp).clip(RoundedCornerShape(14.dp)).background(Th.c.bg).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.optString("day"), style = body(11, color = Muted))
                            Text(d.optString("t"), style = TextStyle(fontFamily = Fonts.display, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                            if (d.optString("night").isNotBlank()) Text("ночью ${d.optString("night")}", style = body(10, color = Muted))
                        }
                    }
                }
                if (r.optString("weather_note").isNotBlank()) Text(r.optString("weather_note"), style = body(12, color = Muted), lineHeight = 17.sp)
            }

            // ——— лучший выбор с покупкой ———
            if (Rich.hasWinner(p)) BestBlock(m.best)
            if (m.items.isNotBlank()) ProductCards(Shop.fromJson(m.items))
            if (m.photos.isNotBlank()) PhotoGrid(m.photos.split(',').mapNotNull { it.trim().toLongOrNull() })

            // ——— таблица плашкой ———
            if (m.files.isNotBlank()) FileCards(Chat.filesOf(m))

            // ——— полный текст по запросу ———
            Text(if (full) "Свернуть текст ⌃" else "Полный текст ответа ›", style = body(13, FontWeight.SemiBold, Teal),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { full = !full }.padding(vertical = 4.dp))
            if (full) Text(md(m.text), style = body(14, color = Ink.copy(alpha = 0.86f)), lineHeight = 20.sp)

            // ——— источники ———
            val src = Rich.strs(r.optJSONArray("sources"))
            if (src.isNotEmpty() || m.meta.isNotBlank()) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (src.isNotEmpty()) Text("Источники", style = body(11, FontWeight.SemiBold, Muted))
                src.take(4).forEach { s ->
                    Text(s, style = body(11, FontWeight.SemiBold, Ink.copy(alpha = 0.8f)),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Th.c.bg).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                if (src.size > 4) Text("+${src.size - 4}", style = body(11, FontWeight.SemiBold, Muted))
            }
        }
        // ——— уточнить: кнопки под карточкой ———
        val asks = (Rich.strs(r.optJSONArray("ask")) + m.buttons).distinct().filter { it.length <= 40 }.take(4)
        if (showButtons && asks.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            asks.forEach { b ->
                Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Th.c.inBubble.copy(alpha = 0.85f))
                    .border(1.dp, Teal.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).clickable { chat.send(b) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) { Text(b, style = body(14, FontWeight.SemiBold, Teal)) }
            }
        }
    }
    alt?.let { a -> AltSheet(a, p) { alt = null } }
}

/** Лист альтернативы: почему не он, его маршрут — и всё равно можно купить. */
@Composable
private fun AltSheet(a: JSONObject, p: JSONObject, close: () -> Unit) {
    var buy by remember { mutableStateOf<JSONObject?>(null) }
    // шаги покупки: свои у пункта сравнения, иначе — у похожей альтернативы из «лучшего выбора»
    val withBuy = remember(a) {
        if (Buy.items(a).isNotEmpty()) a
        else Rich.list(p.optJSONArray("alt")).firstOrNull { x ->
            val n1 = x.optString("name").lowercase(); val n2 = a.optString("name").lowercase()
            n1.isNotBlank() && (n1.contains(n2.take(12)) || n2.contains(n1.take(12)))
        }?.takeIf { Buy.items(it).isNotEmpty() } ?: Buy.guess(a)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(Th.c.card).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("✕  " + a.optString("verdict").ifBlank { "НЕ ПОДХОДИТ" }.uppercase(), style = body(11, FontWeight.Bold, Th.c.danger).copy(letterSpacing = 0.8.sp),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(soft(Th.c.danger)).padding(horizontal = 8.dp, vertical = 4.dp))
            Text(a.optString("name"), style = TextStyle(fontFamily = Fonts.display, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(a.optString("price"), style = TextStyle(fontFamily = Fonts.display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                if (a.optString("time").isNotBlank()) Text(a.optString("time"), style = body(13, color = Muted), modifier = Modifier.padding(bottom = 3.dp))
            }
            if (a.optString("why").isNotBlank()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(soft(Amber)).padding(12.dp)) {
                Icon(rememberVectorPainter(icWarn), null, tint = Amber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(a.optString("why"), style = body(13, color = Ink), lineHeight = 19.sp)
            }
            val route = Rich.list(a.optJSONArray("route"))
            if (route.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { SecLabel("Маршрут"); Ladder(route) }
            if (withBuy != null) {
                val n = Buy.items(withBuy).size
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Th.c.accent).clickable { buy = withBuy }.padding(14.dp), contentAlignment = Alignment.Center) {
                    Text("Всё равно купить · $n ${plural(n, "шаг", "шага", "шагов")}", style = body(14, FontWeight.Bold, Th.c.onAccent))
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Th.c.line, RoundedCornerShape(14.dp)).clickable(onClick = close).padding(14.dp),
                contentAlignment = Alignment.Center) { Text("Вернуться к лучшему варианту", style = body(14, FontWeight.SemiBold, Ink)) }
        }
    }
    buy?.let { o -> BuySheet(o) { buy = null } }
}

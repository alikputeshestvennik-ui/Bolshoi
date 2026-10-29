package ru.alim.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран — «приборная панель»: что ждёт Алима, что идёт сейчас, что готово.
 * Переписка целиком — в «Журнале» (кнопка справа вверху).
 */
@Composable
fun DashScreen(go: (Scr) -> Unit) {
    val ctx = LocalContext.current
    val chat = remember { Chat.main }
    val core = chat.core
    val haze = remember { HazeState() }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4000); tick++ } }
    LaunchedEffect(chat.msgs.size) { chat.markRead() }

    Box(Modifier.fillMaxSize().background(Th.c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // ——— шапка ———
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable(onClickLabel = "Профиль") { go(Scr.Profile) }) { Avatar(46.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Пилот", style = TextStyle(fontFamily = Fonts.display, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                    val now = remember(tick / 15) { SimpleDateFormat("EEE, d MMMM · HH:mm", Locale("ru")).format(Date()) }
                    Text(if (core.busy) "работает · " + core.task.take(40) else now, style = body(12, color = if (core.busy) Teal else Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(46.dp).clip(CircleShape).background(Th.c.card).border(1.dp, Line, CircleShape).clickable(onClickLabel = "Меню") { Panel.open = true },
                    contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icList), "Меню", tint = Ink, modifier = Modifier.size(22.dp))
                }
            }

            // ——— панель (края плавно уходят в фон под шапкой и полем ввода) ———
            Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val t = tick   // перечитываем состояние поручений/слежек раз в несколько секунд

                // задача в полёте — одна строка, по нажатию кабина
                if (core.busy) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Th.c.card).clickable { go(Scr.Task) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Teal)); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(core.task.ifBlank { "Пилот работает" }, style = body(14, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(core.steps.lastOrNull()?.text ?: "взлетаю…", style = body(12, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("в кабину ›", style = body(13, FontWeight.SemiBold, Teal))
                }

                // свежий короткий ответ Пилота — чтобы не ходить в журнал
                val lastMe = chat.msgs.lastOrNull { it.me }
                val reply = chat.msgs.lastOrNull { !it.me && !it.note }
                if (reply != null && lastMe != null && reply.ts >= lastMe.ts && System.currentTimeMillis() - reply.ts < 3 * 3600_000L && t >= 0 && reply.files.isNotBlank()) {
                    Text("› " + lastMe.text.ifBlank { "голосовое" }, style = body(12, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ResultCard(reply, compact = true) { ChatJump.to(reply.id); go(Scr.Journal) }
                } else if (reply != null && lastMe != null && reply.ts >= lastMe.ts && System.currentTimeMillis() - reply.ts < 3 * 3600_000L && t >= 0) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Th.c.card).clickable { ChatJump.to(reply.id); go(Scr.Journal) }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("› " + lastMe.text.ifBlank { "голосовое" }, style = body(12, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(reply.text, style = body(15).copy(lineHeight = 21.sp), maxLines = 8, overflow = TextOverflow.Ellipsis)
                        val fl = Chat.filesOf(reply)
                        if (fl.isNotEmpty()) FileCards(fl)
                    }
                }

                // ——— ЖДЁТ ТЕБЯ ———
                val asks = Deleg.items.filter { it.status == "ask" }
                val pend = core.pending
                if (asks.isNotEmpty() || pend != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecTitle("Ждёт тебя · ${asks.size + (if (pend != null) 1 else 0)}", Amber)
                    if (pend != null) ConfirmCard(pend, core)
                    asks.forEach { x -> AskCard(x, go) }
                }

                // ——— ИДЁТ СЕЙЧАС ———
                val watches = Watch.items.filter { it.status == "active" }
                val delegs = Deleg.items.filter { (it.active && it.status != "ask") || it.planned }
                if (watches.isNotEmpty() || delegs.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecTitle("Идёт сейчас", Muted)
                    val tiles = watches.map { w ->
                        Tile(if (w.mode == "feed") "ЛЕНТА" else "СЛЕЖКА", if (w.mode == "feed") Teal else Th.c.online, w.topic,
                            (w.state.ifBlank { if (w.mode == "feed") "смотрю ленту" else "первая проверка" }) + " · до " + Deleg.whenText(w.until).substringAfter(" в "),
                            ChatJump.find(chat, w.topic))
                    } + delegs.map { d ->
                        val (tag, col) = when (d.status) {
                            "template" -> "ПО РАСПИСАНИЮ" to Muted
                            "scheduled" -> "ОТЛОЖЕНО" to Muted
                            "paused" -> "ПАУЗА" to Muted
                            "wait_ref" -> "ЦЕПОЧКА" to Violet
                            else -> "ПЕРЕПИСКА" to Violet
                        }
                        Tile(tag, col, d.who, Deleg.stateText(d), ChatJump.find(chat, d.who))
                    }
                    val open: (Tile) -> Unit = { tl -> ChatJump.to(tl.target); go(Scr.Journal) }
                    if (tiles.size <= 2) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.forEach { tl -> TileBox(tl, Modifier.weight(1f)) { open(tl) } }
                        if (tiles.size == 1) Spacer(Modifier.weight(1f))
                    } else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.forEach { tl -> TileBox(tl, Modifier.width(168.dp)) { open(tl) } }
                    }
                }

                // ——— ГОТОВО ———
                val since = System.currentTimeMillis() - 2 * 86_400_000L
                val done = chat.msgs.filter { !it.me && !it.note && it.ts > since && it.files.isNotBlank() && it.id != reply?.id }.takeLast(3).reversed()
                val doneDeleg = Deleg.items.filter { it.status == "done" && maxOf(it.lastIn, it.lastOut, it.created) > since }.takeLast(2).reversed()
                if (done.isNotEmpty() || doneDeleg.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecTitle("Готово", Muted)
                    done.forEach { m -> ResultCard(m, compact = true) { ChatJump.to(m.id); go(Scr.Journal) } }
                    doneDeleg.forEach { d ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Th.c.card).clickable { ChatJump.to(ChatJump.find(chat, d.who)); go(Scr.Journal) }.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(Th.c.online)); Spacer(Modifier.width(10.dp))
                                Text(d.who + " · поручение", style = body(15, FontWeight.Bold), modifier = Modifier.weight(1f))
                                ShareBtn { shareText(ctx, "${d.who}: ${d.report}") }
                            }
                            Text(d.report.ifBlank { "готово" }, style = body(14, color = Ink.copy(alpha = 0.82f)).copy(lineHeight = 20.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("готово в " + SimpleDateFormat("HH:mm", Locale.US).format(Date(maxOf(d.lastIn, d.lastOut, d.created))), style = body(11, color = Th.c.meta),
                                modifier = Modifier.align(Alignment.End))
                        }
                    }
                }

                if (!core.busy && asks.isEmpty() && pend == null && watches.isEmpty() && delegs.isEmpty() && done.isEmpty() && doneDeleg.isEmpty() && reply == null)
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Пока тихо", style = TextStyle(fontFamily = Fonts.display, fontSize = 17.sp, color = Ink))
                        Text("Дай команду — найду, сравню, поручу или буду следить.", style = body(14, color = Muted))
                    }
            }
            val fade = Th.c.bg
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(22.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(fade, fade.copy(alpha = 0f)))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade))))
            }

            // ——— ввод ———
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 10.dp)) { Composer(haze, chat, chat.friend) }
        }
    }
}

private class Tile(val tag: String, val color: Color, val title: String, val sub: String, val target: Long = 0L)

@Composable
private fun TileBox(t: Tile, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 104.dp).clip(RoundedCornerShape(16.dp)).background(Th.c.card).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(t.color)); Spacer(Modifier.width(6.dp))
            Text(t.tag, style = body(11, FontWeight.Bold, t.color).copy(letterSpacing = 0.6.sp), maxLines = 1)
        }
        Text(t.title, style = body(14, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.sub, style = body(12, color = Muted).copy(lineHeight = 16.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SecTitle(text: String, color: Color) =
    Text(text.uppercase(), style = body(11, FontWeight.Bold, color).copy(letterSpacing = 1.4.sp))

/** Вопрос переговорщика: перенаправление — «Да / Нет», остальное — «Ответить» (в журнале) или «Заверши». */
@Composable
private fun AskCard(x: Deleg.Item, go: (Scr) -> Unit) {
    val ctx = LocalContext.current
    var sent by remember(x.id, x.question) { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Th.c.card).border(1.dp, Amber.copy(alpha = 0.45f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${x.who} · поручение", style = body(15, FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(SimpleDateFormat("HH:mm", Locale.US).format(Date(maxOf(x.lastIn, x.lastOut, x.created))), style = body(12, color = Muted))
        }
        Text(x.question, style = body(14, color = Ink).copy(lineHeight = 20.sp), maxLines = 6, overflow = TextOverflow.Ellipsis)
        if (sent != null) Text(sent!!, style = body(13, color = Muted))
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (x.refer.isNotBlank()) {
                DashBtn("Да, напиши", true, Modifier.weight(1f)) { sent = "↪ " + Deleg.referAnswer(ctx, x.id, "да") }
                DashBtn("Нет", false, Modifier.weight(1f)) { sent = "↪ " + Deleg.referAnswer(ctx, x.id, "нет") }
            } else {
                DashBtn("Ответить", true, Modifier.weight(1f)) { ChatJump.to(ChatJump.find(Chat.main, x.who)); go(Scr.Journal) }
                DashBtn("Заверши", false, Modifier.weight(1f)) { sent = "↪ " + Deleg.finish(ctx, x.id) }
            }
        }
    }
}

@Composable
private fun DashBtn(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.height(46.dp).clip(shape).then(if (primary) Modifier.background(Teal) else Modifier.border(1.dp, Line, shape)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(text, style = body(14, FontWeight.Bold, if (primary) OnAccent else Ink))
    }
}

private val icList by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("list", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.addPathNodes("M4 6h16M4 12h16M4 18h10"), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round)
    }.build()
}


/** Переход из панели в нужное место журнала (а не в его конец) + подсветка сообщения. */
object ChatJump {
    var target by mutableStateOf(0L)
    var glow by mutableStateOf(0L)
    var searchReq by mutableStateOf(false)
    fun to(id: Long) { if (id != 0L) target = id }
    /** Последнее сообщение Пилота, где упоминается тема/человек (отчёты слежки, поручений). */
    fun find(chat: ChatStore, key: String): Long {
        if (key.isBlank()) return 0L
        val k = key.lowercase().take(24)
        return chat.msgs.lastOrNull { !it.me && it.text.lowercase().contains(k) }?.id ?: 0L
    }
}

/** Боковая панель справа (40% экрана): журнал, история, настройки… — вместо всплывающего меню. */
object Panel { var open by mutableStateOf(false) }

@Composable
fun SidePanel(scr: Scr, go: (Scr) -> Unit) {
    val shown = Panel.open
    val a by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, androidx.compose.animation.core.tween(240), label = "panel")
    if (a <= 0.001f && !shown) return
    androidx.activity.compose.BackHandler(enabled = shown) { Panel.open = false }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * a)).clickable(indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { Panel.open = false })
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxOf(maxWidth * 0.4f, 190.dp)
            Column(Modifier.align(Alignment.CenterEnd).width(w).fillMaxSize()
                .graphicsLayer { translationX = (1f - a) * w.toPx() }
                .background(Th.c.card).statusBarsPadding().navigationBarsPadding().padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val items = buildList {
                    if (scr == Scr.Home) add("Журнал" to { go(Scr.Journal) }) else add("Главная" to { go(Scr.Home) })
                    if (scr == Scr.Journal) add("Поиск" to { ChatJump.searchReq = true })
                    add("История и расходы" to { go(Scr.History) })
                    add("Что Пилот помнит" to { go(Scr.Memory) })
                    add("Сводки дня" to { go(Scr.Digest) })
                    add("Сам пишет первым" to { go(Scr.Initiative) })
                    add("Отвечать за меня" to { go(Scr.Rules) })
                    add("Оформление" to { go(Scr.Look) })
                    add("Настройки" to { go(Scr.Settings) })
                }
                items.forEachIndexed { i, (t, f) ->
                    Text(t, style = body(15, if (i == 0) FontWeight.Bold else FontWeight.Medium), modifier = Modifier.fillMaxWidth()
                        .clickable { Panel.open = false; f() }.padding(horizontal = 18.dp, vertical = 13.dp))
                    if (i == 0) Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Line))
                }
            }
        }
    }
}


/**
 * Карточка результата — как в «Бортжурнале»: вывод заголовком, коротко текст, мини-таблица из Excel,
 * файлы квадратными иконками и одна кнопка «отправить». Одна и та же на панели и в журнале.
 */
@Composable
fun ResultCard(m: Msg, compact: Boolean, lit: Boolean = false, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val files = remember(m.files) { Chat.filesOf(m).distinctBy { it.name.substringBeforeLast('.').replace(Regex("\\s*\\(\\d+\\)$"), "") } }
    val parts = remember(m.text) { splitResult(m.text) }
    val preview by androidx.compose.runtime.produceState<List<List<String>>?>(null, m.files) {
        value = files.firstOrNull { it.mime == Docs.XLSX }?.let { f ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { tablePreview(ctx, f.uri) }.getOrNull() }
        }
    }
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Th.c.card).then(if (lit) Modifier.border(1.dp, Teal, shape) else Modifier)
        .clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val xls = files.firstOrNull { it.mime == Docs.XLSX }
        val open: (Docs.FileRef) -> Unit = { f ->
            runCatching { ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        val share: () -> Unit = {
            runCatching {
                val i = if (files.size == 1) android.content.Intent(android.content.Intent.ACTION_SEND).setType(files[0].mime).putExtra(android.content.Intent.EXTRA_STREAM, files[0].uri)
                    else android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
                ctx.startActivity(android.content.Intent.createChooser(i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отправить").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(parts.title.ifBlank { files.firstOrNull()?.name?.substringBeforeLast('.').orEmpty() },
                style = TextStyle(fontFamily = Fonts.display, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = Ink), modifier = Modifier.weight(1f))
            if (files.isNotEmpty()) { Spacer(Modifier.width(10.dp)); ShareBtn(share) }
        }
        if (parts.body.isNotBlank()) Text(parts.body, style = body(14, color = Ink.copy(alpha = 0.82f)).copy(lineHeight = 20.sp),
            maxLines = if (compact) 3 else 40, overflow = TextOverflow.Ellipsis)
        val rows = preview?.takeIf { it.size >= 2 }
        // таблица — сама кнопка: тап открывает Excel
        if (rows != null && xls != null) Box(Modifier.clickable(onClickLabel = "Открыть таблицу") { open(xls) }) { MiniTable(rows) }
        val rest = files.filter { rows == null || it != xls }
        if (rest.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rest.forEach { f ->
                val xl = f.mime == Docs.XLSX
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (xl) Color(0xFF1E7A4C) else Color(0xFF2B579A))
                    .clickable(onClickLabel = "Открыть ${f.name}") { open(f) }, contentAlignment = Alignment.Center) {
                    Text(if (xl) "XLS" else "DOC", style = body(11, FontWeight.Bold, Color.White))
                }
            }
        }
        Text(parts.stamp.ifBlank { SimpleDateFormat("HH:mm", Locale.US).format(Date(m.ts)) }, style = body(11, color = Th.c.meta), maxLines = 1,
            modifier = Modifier.align(Alignment.End))
    }
}

@Composable
private fun ShareBtn(onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).clickable(onClickLabel = "Отправить", onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icPlane), "Отправить", tint = Teal, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun MiniTable(rows: List<List<String>>) {
    val shape = RoundedCornerShape(12.dp)
    val cols = rows.maxOf { it.size }.coerceAtMost(4)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, Line, shape)) {
        rows.take(6).forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(Modifier.fillMaxWidth().background(if (i == 0) Th.c.bg.copy(alpha = 0.6f) else Color.Transparent)) {
                for (c in 0 until cols) {
                    Text(r.getOrNull(c).orEmpty(), style = body(12, if (i == 0) FontWeight.Bold else FontWeight.Normal, if (i == 0 || c == 0) Muted else Ink),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 7.dp))
                }
            }
        }
    }
}

private class ResultParts(val title: String, val body: String, val stamp: String)

/** Вывод — заголовком. «Таблица готова…» — служебное, тогда заголовок — следующая фраза. Строка 🕓 — в подпись. */
private fun splitResult(text: String): ResultParts {
    var t = text.trim()
    var stamp = ""
    Regex("\\n?\\s*(🕓|🕐|⏰)\\s*([^\\n]+)").find(t)?.let { m -> stamp = m.groupValues[2].trim(); t = t.removeRange(m.range).trim() }
    val sentences = Regex("[^.!?\\n]+[.!?]*").findAll(t).map { it.value.trim() }.filter { it.isNotBlank() }.toMutableList()
    if (sentences.size > 1 && Regex("^(таблиц|файл|документ|сравнени|готово|создал|сделал)", RegexOption.IGNORE_CASE).containsMatchIn(sentences[0])
        && Regex("готов|создал|сделал|сохранил", RegexOption.IGNORE_CASE).containsMatchIn(sentences[0])) sentences.removeAt(0)
    val title = sentences.firstOrNull()?.takeIf { it.length in 8..170 }?.trimEnd('.').orEmpty()
    val body = if (title.isNotBlank()) sentences.drop(1).joinToString(" ") else sentences.joinToString(" ")
    return ResultParts(title, body, stamp)
}

/** Первые строки первого листа Excel — для мини-таблицы. Формулы показываем «—». */
private fun tablePreview(ctx: android.content.Context, uri: android.net.Uri): List<List<String>>? {
    val j = ctx.contentResolver.openInputStream(uri)?.use { Docs.readXlsx(it) } ?: return null
    val rows = j.optJSONArray("sheets")?.optJSONObject(0)?.optJSONArray("rows") ?: return null
    fun cell(v: Any?): String = when (v) {
        null -> ""
        is Number -> { val d = v.toDouble(); if (d == Math.floor(d) && kotlin.math.abs(d) < 1e12) "%,d".format(d.toLong()).replace(',', ' ') else "%.2f".format(d) }
        is String -> if (v.startsWith("=")) "—" else v
        else -> v.toString()
    }
    return (0 until minOf(rows.length(), 6)).map { i -> val r = rows.optJSONArray(i); (0 until (r?.length() ?: 0)).map { c -> cell(r?.opt(c)) } }
        .filter { r -> r.any { it.isNotBlank() } }
}

private val icPlane by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("plane", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.addPathNodes("M21 3L10 14M21 3l-7 18-4-7-7-4z"), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }.build()
}

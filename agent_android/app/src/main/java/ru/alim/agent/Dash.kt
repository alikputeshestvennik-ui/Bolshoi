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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.launch
import android.net.Uri
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
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import androidx.compose.ui.layout.onSizeChanged
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

    var headH by remember { mutableStateOf(0) }
    var compH by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxSize().background(Th.c.bg)) {
            // ——— панель (края плавно уходят в фон под шапкой и полем ввода) ———
            Box(Modifier.fillMaxSize().haze(haze, dev.chrisbanes.haze.HazeStyle(tint = Th.c.glass, blurRadius = 24.dp, noiseFactor = 0.05f))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .padding(top = with(androidx.compose.ui.platform.LocalDensity.current) { headH.toDp() } + 12.dp,
                    bottom = with(androidx.compose.ui.platform.LocalDensity.current) { compH.toDp() } + 16.dp),
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
            }

            // ——— края ленты плавно уходят в фон под стеклом, как в журнале ———
            val fade = Th.c.bg
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { headH.toDp() } + 10.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(fade.copy(alpha = 0.9f), fade.copy(alpha = 0.6f), fade.copy(alpha = 0f)))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { compH.toDp() } + 24.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade.copy(alpha = 0.7f), fade.copy(alpha = 0.92f)))))
            // шапка поверх градиента
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { headH = it.height }.statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth().height(60.dp).glassDash(haze, RoundedCornerShape(30.dp)).clickable(onClickLabel = "Профиль") { go(Scr.Profile) }
                    .padding(start = 6.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(46.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Пилот", style = TextStyle(fontFamily = Fonts.display, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                        val now2 = remember(tick / 15) { SimpleDateFormat("EEE, d MMMM · HH:mm", Locale("ru")).format(Date()) }
                        Text(if (core.busy) "работает · " + core.task.take(40) else now2, style = body(12, color = if (core.busy) Teal else Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // ——— низ: четыре кнопки разделов и ввод — плавают, как в журнале ———
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { compH = it.height }
                .navigationBarsPadding().imePadding().padding(horizontal = 10.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NavBar(haze, Scr.Home, go)
                Composer(haze, chat, chat.friend)
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
                    add("Сводки дня" to { go(Scr.Digest) })
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
    // «Лучший выбор» из самой таблицы: столбец «Итого/Всего» — минимум побеждает (не зависит от того, отдала ли модель best)
    val tableBest by androidx.compose.runtime.produceState("", m.files) {
        value = files.firstOrNull { it.mime == Docs.XLSX }?.let { f ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { bestFromTable(ctx, f.uri) }.getOrNull() }
        }.orEmpty()
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
        val best = remember(m.best, m.text, tableBest) { m.best.ifBlank { tableBest.ifBlank { deriveBest(m.text) } } }
        if (best.isNotBlank()) BestBlock(best)
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

/** «Лучший выбор» — как в макете Г: победитель крупнее и с плашкой, остальные меньше и тише. Есть шаги покупки — тап открывает их. */
@Composable
private fun BestBlock(json: String) {
    val b = remember(json) { runCatching { org.json.JSONObject(json) }.getOrNull() } ?: return
    val alt = b.optJSONArray("alt")?.let { a -> (0 until minOf(2, a.length())).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    var sheet by remember { mutableStateOf<org.json.JSONObject?>(null) }
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val ok = Th.c.online
        val shape = RoundedCornerShape(18.dp)
        val wBuy = Buy.items(b)
        Column(Modifier.weight(3f).fillMaxSize().clip(shape).background(ok.copy(alpha = if (Th.c.dark) 0.16f else 0.10f)).border(2.dp, ok, shape)
            .then(if (wBuy.isNotEmpty()) Modifier.clickable(onClickLabel = "Купить") { sheet = b } else Modifier).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
            Text("ЛУЧШИЙ ВЫБОР", style = body(10, FontWeight.Bold, if (Th.c.dark) Color(0xFF062018) else Color.White).copy(letterSpacing = 0.8.sp),
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(ok).padding(horizontal = 7.dp, vertical = 3.dp))
            Text(b.optString("name"), style = body(13, FontWeight.Bold, Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(b.optString("value"), style = TextStyle(fontFamily = Fonts.display, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink), maxLines = 1)
            if (b.optString("note").isNotBlank()) Text(b.optString("note"), style = body(12, color = Muted), maxLines = 2)
            if (wBuy.isNotEmpty()) Text("Купить · ${wBuy.size} ${steps(wBuy.size)} ›", style = body(12, FontWeight.Bold, ok),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ok.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        if (alt.isNotEmpty()) Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            alt.forEach { a ->
                val aBuy = Buy.items(a)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Th.c.bg.copy(alpha = 0.7f))
                    .then(if (aBuy.isNotEmpty()) Modifier.clickable(onClickLabel = "Купить") { sheet = a } else Modifier).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(a.optString("name"), style = body(12, color = Muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(a.optString("value"), style = TextStyle(fontFamily = Fonts.display, fontSize = 14.sp, color = Ink.copy(alpha = 0.8f)), maxLines = 1)
                    if (a.optString("diff").isNotBlank()) Text(a.optString("diff"), style = body(12, color = Muted), maxLines = 1)
                    if (aBuy.isNotEmpty()) Text("Купить ›", style = body(11, FontWeight.Bold, Th.c.accent))
                }
            }
        }
    }
    sheet?.let { o -> BuySheet(o) { sheet = null } }
}

private fun steps(n: Int) = if (n % 10 == 1 && n % 100 != 11) "шаг" else if (n % 10 in 2..4 && n % 100 !in 12..14) "шага" else "шагов"

/** Шаги покупки варианта: каждый — своя ссылка, «Открыть всё» — все вкладки разом (приложение, если оно есть). */
@Composable
private fun BuySheet(o: org.json.JSONObject, close: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = remember(o) { Buy.items(o) }
    val opened = remember { mutableStateListOf<Int>() }
    androidx.compose.ui.window.Dialog(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Th.c.card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(o.optString("name"), style = TextStyle(fontFamily = Fonts.display, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink))
            Text("${o.optString("value")} · ${items.size} ${steps(items.size)} покупки по порядку поездки", style = body(12, color = Muted))
            items.forEachIndexed { k, (kind, label, url) ->
                val done = k in opened
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Th.c.bg).clickable { Buy.open(ctx, url); if (!done) opened += k }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when (kind) { "flight", "avia", "рейс" -> "✈"; "bus" -> "🚌"; "train" -> "🚆"; "hotel" -> "🏨"; else -> "🔗" }, style = body(18))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = body(13, FontWeight.Medium, Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when { url.contains("aviasales") -> "Авиасейлс"; url.contains("yandex.ru/search") -> "поиск — точной ссылки не нашёл"; else -> Uri.parse(url).host.orEmpty().removePrefix("www.") },
                            style = body(11, color = Muted), maxLines = 1)
                    }
                    Text(if (done) "✓" else "›", style = body(16, FontWeight.Bold, if (done) Th.c.online else Th.c.accent))
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Th.c.accent).clickable {
                scope.launch { items.forEachIndexed { k, it -> Buy.open(ctx, it.third); if (k !in opened) opened += k; kotlinx.coroutines.delay(700) } }
            }.padding(14.dp), contentAlignment = Alignment.Center) {
                Text("Открыть всё (${items.size})", style = body(14, FontWeight.Bold, Th.c.onAccent))
            }
            Text("Цены — из поиска; точная будет на сайте при покупке. Платишь сам — Пилот ничего не покупает.", style = body(11, color = Muted))
        }
    }
}

/** Нижние кнопки разделов — одинаковые на главной и в журнале. */
@Composable
fun NavBar(haze: HazeState, here: Scr, go: (Scr) -> Unit, onSearch: (() -> Unit)? = null) {
    val items = buildList {
        add(Triple(if (here == Scr.Home) icChat else icHome, if (here == Scr.Home) "Журнал" else "Главная", if (here == Scr.Home) Scr.Journal else Scr.Home))
        add(Triple(icClock, "История", Scr.History))
        add(Triple(icSun, "Сводки", Scr.Digest))
        add(Triple(icGear, "Настройки", Scr.Settings))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (ic, label, to) -> NavDot(haze, ic, label, Modifier.weight(1f)) { go(to) } }
        if (onSearch != null) NavDot(haze, icSearch, "", Modifier.width(34.dp), onSearch)
    }
}

@Composable
private fun NavDot(haze: HazeState, ic: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(34.dp).glassDash(haze, RoundedCornerShape(17.dp)).clickable(onClickLabel = label.ifBlank { "Поиск" }, onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
        Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(ic), label.ifBlank { "Поиск" }, tint = Ink, modifier = Modifier.size(16.dp))
        if (label.isNotBlank()) Text(label, style = body(11, FontWeight.SemiBold, Ink.copy(alpha = 0.8f)), maxLines = 1, softWrap = false)
    }
}

private val icHome by lazy { ico("home", "M4 11l8-7 8 7M6 9.5V20h12V9.5") }
private val icSearch by lazy { ico("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14M20 20l-4-4") }

private fun Modifier.glassDash(h: HazeState, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.clip(shape).hazeChild(h, shape).background(Th.c.glass.copy(alpha = if (Th.c.dark) 0.35f else 0.30f)).border(1.dp, Th.c.glassLine, shape)

private fun ico(name: String, path: String) = androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    addPath(androidx.compose.ui.graphics.vector.addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
}.build()
private val icChat by lazy { ico("chat", "M4 5h16v11H9l-5 4z") }
private val icClock by lazy { ico("clock", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 7v5l3 2") }
private val icSun by lazy { ico("sun", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4") }
private val icGear by lazy { ico("gear", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3a1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5a1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8a1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5a1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z") }

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
    var t = text.trim().replace("**", "").replace("__", "")
    var stamp = ""
    Regex("\\n?\\s*(🕓|🕐|⏰)\\s*([^\\n]+)").find(t)?.let { m -> stamp = m.groupValues[2].trim(); t = t.removeRange(m.range).trim() }
    // предложения режем только перед пробелом и заглавной — «15.01» и «9.3» целы
    val split = Regex("(?<=[.!?])\\s+(?=[А-ЯЁA-Z«\"(])|\\n+")
    fun sentences(x: String) = x.split(split).map { it.trim() }.filter { it.isNotBlank() }
    // служебные фразы про файл («Обновил файл в Загрузках», «Таблица лежит…») — выкидываем из текста целиком
    val service = Regex("(файл|таблиц|документ|загрузк|xlsx|docx)", RegexOption.IGNORE_CASE)
    val verb = Regex("(готов|создал|сделал|сохранил|обновил|положил|собрал|теперь там|там есть|внёс|внес|лежит|открывается|в загрузк)", RegexOption.IGNORE_CASE)
    sentences(t).filter { service.containsMatchIn(it) && verb.containsMatchIn(it) && it.length < 200 }.forEach { sv -> t = t.replaceFirst(sv, "") }
    t = t.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    val ss = sentences(t)
    // заголовок — вывод «самый выгодный — …», иначе первая фраза; длинный — режем по запятой/тире
    val pick = ss.firstOrNull { Regex("самый выгодн|выгоднее всего|лучший вариант|лучший выбор|оптимальн", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: ss.firstOrNull().orEmpty()
    var title = pick
    if (title.length > 80) {
        val cut = Regex("[,;](\\s)|\\s[—–-]\\s").findAll(title).map { it.range.first }.firstOrNull { it in 20..80 }
        title = if (cut != null) title.substring(0, cut) else title
    }
    title = title.trim().trimEnd('.', ',', ':')
    if (title.length !in 8..120) return ResultParts("", t, stamp)
    // тело — исходный текст с переносами строк, без того, что ушло в заголовок
    var body = t
    if (pick == title || pick.startsWith(title)) {
        val rest = pick.removePrefix(title).trimStart(',', ';', ' ', '—', '-', '.', ':').trim()
        body = body.replaceFirst(pick, if (rest.length > 3) rest.replaceFirstChar { it.uppercase() } else "")
    }
    // хвосты после выноса заголовка: строка из одной точки, «. Если…»
    body = body.lines().map { it.trim().removePrefix(". ").trim() }.filter { it != "." && it != "," }.joinToString("\n")
    body = body.replace(Regex("\\n{3,}"), "\n\n").trim()
    return ResultParts(title, body, stamp)
}

/** Первые строки первого листа Excel — для мини-таблицы. Формулы показываем «—». */
private fun tablePreview(ctx: android.content.Context, uri: android.net.Uri): List<List<String>>? {
    val j = ctx.contentResolver.openInputStream(uri)?.use { Docs.readXlsx(it) } ?: return null
    val rows = j.optJSONArray("sheets")?.optJSONObject(0)?.optJSONArray("rows") ?: return null
    val grid = (0 until rows.length()).map { i -> val r = rows.optJSONArray(i); (0 until (r?.length() ?: 0)).map { c -> r?.opt(c) } }
    fun num(d: Double) = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e12) "%,d".format(d.toLong()).replace(',', ' ') else "%.1f".format(d)
    fun cell(v: Any?, depth: Int = 0): String = when (v) {
        null -> ""
        is Number -> num(v.toDouble())
        is String -> if (v.startsWith("=")) Formula.eval(v.drop(1), grid, depth)?.let { num(it) } ?: "—" else v
        else -> v.toString()
    }
    val all = (0 until minOf(grid.size, 8)).map { i -> grid[i].map { v -> cell(v) } }
        .filter { r -> r.count { it.isNotBlank() } >= 2 }   // строки-примечания (одна ячейка) — не в превью
    if (all.size < 2) return all
    val n = all.maxOf { it.size }
    val body = all.drop(1)
    // столбцы, где во всех строках одно и то же (маршрут, один рейс), и «№» — неинтересны в превью
    val keep = (0 until n).filter { c ->
        val vals = body.map { it.getOrNull(c).orEmpty() }
        val head = all[0].getOrNull(c).orEmpty().trim()
        vals.any { it.isNotBlank() } && vals.distinct().size > 1 && head != "№" && !head.equals("n", true)
    }.ifEmpty { (0 until n).toList() }
    // имя (первый оставшийся) + последние 3 — обычно там итоги
    val cols = if (keep.size <= 4) keep else listOf(keep.first()) + keep.takeLast(3)
    return all.take(6).map { r -> cols.map { c -> r.getOrNull(c).orEmpty() } }
}

private val icPlane by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("plane", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.addPathNodes("M21 3L10 14M21 3l-7 18-4-7-7-4z"), stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }.build()
}


/** Если модель не дала best — достаём из текста «Самый выгодный — Ташкент (≈50 348 ₽)». */
fun deriveBest(text: String): String {
    val head = "(?:самый выгодн\\w*(?: вариант)?|выгоднее всего|лучший (?:вариант|выбор)|оптимальн\\w*(?: вариант)?)\\s*[—:–-]?\\s*"
    val m = Regex(head + "([^(,.;:\\n]{2,40}?)\\s*(?:\\(\\s*|[:—–-]\\s*)(≈?\\s*[\\d\\s ]{3,}\\s*₽)", RegexOption.IGNORE_CASE).find(text)
        ?: return ""
    return org.json.JSONObject().put("name", m.groupValues[1].trim()).put("value", m.groupValues[2].replace(Regex("\\s+"), " ").trim()).toString()
}


/** Мини-вычислитель формул Excel для превью: + − × ÷, скобки, ссылки A1, SUM(A1:B3). Остальное — null («—»). */
object Formula {
    fun eval(expr: String, grid: List<List<Any?>>, depth: Int = 0): Double? = runCatching { P(expr.replace(" ", "").uppercase(), grid, depth).run() }.getOrNull()

    private class P(val s: String, val g: List<List<Any?>>, val depth: Int) {
        var i = 0
        fun run(): Double? { val v = expr(); return if (i == s.length) v else null }
        fun expr(): Double? { var v = term() ?: return null
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term() ?: return null; v = if (op == '+') v + r else v - r }
            return v }
        fun term(): Double? { var v = factor() ?: return null
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = factor() ?: return null; v = if (op == '*') v * r else if (r == 0.0) return null else v / r }
            return v }
        fun factor(): Double? {
            if (i >= s.length) return null
            val c = s[i]
            if (c == '-') { i++; return factor()?.let { -it } }
            if (c == '(') { i++; val v = expr(); if (i < s.length && s[i] == ')') i++ else return null; return v }
            if (s.startsWith("SUM(", i)) {
                i += 4; var total = 0.0
                while (i < s.length && s[i] != ')') {
                    val a = ref() ?: return null
                    val b = if (i < s.length && s[i] == ':') { i++; ref() ?: return null } else a
                    for (r in minOf(a.first, b.first)..maxOf(a.first, b.first)) for (cc in minOf(a.second, b.second)..maxOf(a.second, b.second)) total += value(r, cc) ?: 0.0
                    if (i < s.length && (s[i] == ',' || s[i] == ';')) i++
                }
                if (i < s.length) i++
                return total
            }
            if (c.isDigit() || c == '.') { val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++; return s.substring(st, i).toDoubleOrNull() }
            if (c.isLetter()) { val (r, cc) = ref() ?: return null; return value(r, cc) }
            return null
        }
        /** «D2» → (строка 1, столбец 3), с нуля. */
        fun ref(): Pair<Int, Int>? {
            val m = Regex("\\$?([A-Z]{1,3})\\$?(\\d{1,5})").find(s, i)?.takeIf { it.range.first == i } ?: return null
            i = m.range.last + 1
            val col = m.groupValues[1].fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
            return (m.groupValues[2].toInt() - 1) to col
        }
        fun value(r: Int, c: Int): Double? {
            val v = g.getOrNull(r)?.getOrNull(c) ?: return 0.0
            return when (v) {
                is Number -> v.toDouble()
                is String -> if (v.startsWith("=")) { if (depth > 6) null else eval(v.drop(1), g, depth + 1) } else v.replace(" ", "").replace(',', '.').toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }
    }
}


/** Лучший вариант по таблице: строка с минимальным «Итого» + две следующие с разницей. */
private fun bestFromTable(ctx: android.content.Context, uri: android.net.Uri): String {
    val j = ctx.contentResolver.openInputStream(uri)?.use { Docs.readXlsx(it) } ?: return ""
    val rows = j.optJSONArray("sheets")?.optJSONObject(0)?.optJSONArray("rows") ?: return ""
    val grid = (0 until rows.length()).map { i -> val r = rows.optJSONArray(i); (0 until (r?.length() ?: 0)).map { c -> r?.opt(c) } }
    if (grid.size < 3) return ""
    val head = grid[0].map { it?.toString().orEmpty().lowercase() }
    val totalCol = head.indexOfFirst { Regex("^(итого|всего|итог|total|сумма)").containsMatchIn(it.trim()) || Regex("итого|всего на").containsMatchIn(it) }
    if (totalCol < 0) return ""
    fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        // из «≈15 700 ₽ (7 368 ₽ + 300-500 ₽…)» берём только первое число, а не склейку всех цифр
        is String -> if (v.startsWith("=")) Formula.eval(v.drop(1), grid)
            else Regex("\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?").find(v)?.value
                ?.replace(Regex("[ \\u00A0\\u202F]"), "")?.replace(',', '.')?.toDoubleOrNull()
        else -> null
    }
    val cityCol = head.indexOfFirst { it.contains("город") }
    val data = grid.drop(1).mapNotNull { r ->
        val t = num(r.getOrNull(totalCol)) ?: return@mapNotNull null
        if (t <= 0) return@mapNotNull null
        val name0 = r.getOrNull(0)?.toString().orEmpty().trim()
        val city = if (cityCol > 0) r.getOrNull(cityCol)?.toString().orEmpty().trim() else ""
        val name = listOf(name0, city).filter { it.isNotBlank() && !it.all { ch -> ch.isDigit() } }.distinct().joinToString(" · ")
        if (name.isBlank()) null else name to t
    }
    if (data.size < 2) return ""
    val sorted = data.sortedBy { it.second }
    fun rub(d: Double) = "%,d".format(d.toLong()).replace(',', ' ') + " ₽"
    val win = sorted[0]
    val alt = org.json.JSONArray()
    sorted.drop(1).take(2).forEach { (n, t) -> alt.put(org.json.JSONObject().put("name", n).put("value", rub(t)).put("diff", "+" + rub(t - win.second))) }
    return org.json.JSONObject().put("name", win.first).put("value", rub(win.second)).put("note", grid[0].getOrNull(totalCol)?.toString().orEmpty().lowercase()).put("alt", alt).toString()
}

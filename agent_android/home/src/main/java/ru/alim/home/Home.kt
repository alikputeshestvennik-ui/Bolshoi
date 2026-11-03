package ru.alim.home

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Root(p: Pal, drawer: Boolean, tick: Int, openDrawer: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(emptyList<App>()) }
    var items by remember { mutableStateOf(emptyList<Item>()) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var recents by remember { mutableStateOf(emptyList<App>()) }
    var usageOk by remember { mutableStateOf(false) }
    var ten by remember { mutableStateOf(Apps.TenState(false, "", 0, "", 0.0, false, "", 0, 0)) }
    var version by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<App?>(null) }
    var letterFor by remember { mutableStateOf<App?>(null) }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var folderMenu by remember { mutableStateOf<FolderItem?>(null) }
    var rename by remember { mutableStateOf<FolderItem?>(null) }

    LaunchedEffect(tick, version) {
        val (a, h) = withContext(Dispatchers.IO) { val a = Apps.load(ctx); a to Desk.load(ctx, a) }
        all = a; items = h; hidden = Apps.hidden(ctx)
        usageOk = Recent.granted(ctx)
        recents = withContext(Dispatchers.IO) { Recent.list(ctx, a) }
    }
    LaunchedEffect(tick) { while (true) { ten = withContext(Dispatchers.IO) { Apps.state(ctx) }; delay(20_000) } }

    fun update(l: List<Item>) { items = l; Desk.save(ctx, l) }
    val letters = remember(items, all, version) {
        val shown = Desk.appsIn(items); val custom = Apps.customLetters(ctx)
        Apps.letters(ctx, all).filterKeys { k -> shown.none { it.key == k } }.mapValues { (k, v) -> custom[k] ?: v.take(1) } + Apps.letters(ctx, shown)
    }
    val open: (App) -> Unit = { a -> Recent.note(ctx, a); Apps.launch(ctx, a) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        if (!drawer) HomeScreen(p, items, letters, ten, recents, usageOk,
            onOpenApp = open, onOpenFolder = { openFolder = it.fid },
            onMenu = { when (it) { is AppItem -> menu = it.app; is FolderItem -> folderMenu = it } },
            onMove = { id, to -> update(Desk.move(items, id, to)) },
            onMerge = { d, t -> update(Desk.merge(items, d, t)) },
            openDrawer = { openDrawer(true) })
        else Drawer(p, all, hidden, letters, onOpen = { open(it); openDrawer(false) }, onMenu = { menu = it }, close = { openDrawer(false) })
    }

    menu?.let { a ->
        val onHome = Desk.contains(items, a.key)
        val isHidden = a.key in hidden
        val idx = items.indexOfFirst { it.id == a.key }
        val extra = ArrayList<Pair<String, () -> Unit>>()
        if (idx >= 0) {
            val page = if (idx < Desk.FIRST) 0 else 1 + (idx - Desk.FIRST) / Desk.NEXT
            extra += "На следующий стол →" to { update(Desk.move(items, a.key, Desk.base(page + 1))) }
            if (page > 0) extra += "← На предыдущий стол" to { update(Desk.move(items, a.key, Desk.base(page - 1))) }
        }
        AppMenu(p, a, onHome, isHidden, dismiss = { menu = null },
            toggleHome = {
                if (onHome) update(Desk.remove(items, a.key))
                else if (items.size >= Desk.MAX) Toast.makeText(ctx, "Столы заполнены", Toast.LENGTH_SHORT).show()
                else { update(items + AppItem(a)); Toast.makeText(ctx, "Добавил на стол ${Desk.pages(items + AppItem(a)).size}", Toast.LENGTH_SHORT).show() }
            },
            moveFirst = if (idx >= 0) ({ update(Desk.move(items, a.key, 0)) }) else null,
            letter = { letterFor = a },
            toggleHidden = { Apps.setHidden(ctx, a.key, !isHidden); if (!isHidden && onHome) update(Desk.remove(items, a.key)); version++ },
            info = { Apps.info(ctx, a) }, uninstall = { Apps.uninstall(ctx, a) }, extra = extra)
    }
    letterFor?.let { a ->
        LetterDialog(p, a, letters[a.key].orEmpty(), dismiss = { letterFor = null }, save = { v -> Apps.setLetter(ctx, a.key, v); version++; letterFor = null })
    }
    (items.firstOrNull { it is FolderItem && it.fid == openFolder } as? FolderItem)?.let { f ->
        FolderDialog(p, f, letters, onOpen = { open(it); openFolder = null }, onRename = { rename = f },
            onTakeOut = { app ->
                val l = Desk.remove(items, app.key).toMutableList()
                val at = l.indexOfFirst { it.id == f.id }.let { if (it < 0) l.size else it + 1 }
                l.add(at.coerceAtMost(l.size), AppItem(app)); update(l)
                Toast.makeText(ctx, "«${app.label}» — на столе", Toast.LENGTH_SHORT).show()
            }, dismiss = { openFolder = null })
    }
    folderMenu?.let { f ->
        ListDialog(p, f.name, "папка · ${f.apps.size}", listOf(
            "Открыть" to { openFolder = f.fid },
            "Переименовать" to { rename = f },
            "Расформировать" to { update(items.flatMap { if (it.id == f.id) f.apps.map { a -> AppItem(a) } else listOf(it) }) },
        ), dismiss = { folderMenu = null })
    }
    rename?.let { f ->
        NameDialog(p, f.name, dismiss = { rename = null }, save = { n -> update(items.map { if (it.id == f.id) f.copy(name = n) else it }); rename = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(p: Pal, items: List<Item>, letters: Map<String, String>, ten: Apps.TenState, recents: List<App>, usageOk: Boolean,
               onOpenApp: (App) -> Unit, onOpenFolder: (FolderItem) -> Unit, onMenu: (Item) -> Unit,
               onMove: (String, Int) -> Unit, onMerge: (String, String) -> Unit, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000L * (60 - (value / 1000) % 60)) } }
    val pages = remember(items) { Desk.pages(items) }
    val pager = rememberPagerState { pages.size }
    var drag by remember { mutableFloatStateOf(0f) }

    // «Спроси Тень» — состояние живёт тут, чтобы не терялось при листании столов
    var q by remember { mutableStateOf("") }
    var since by remember { mutableLongStateOf(0L) }
    var reply by remember { mutableStateOf<Apps.Reply?>(null) }
    var asked by remember { mutableStateOf("") }
    val kb = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    fun send() {
        val t = q.trim(); if (t.isEmpty()) return
        q = ""; asked = t; reply = Apps.Reply(true, "", emptyList()); kb?.hide(); focus.clearFocus()
        scope.launch {
            val ts = withContext(Dispatchers.IO) { Apps.ask(ctx, t) }
            if (ts == null) reply = Apps.Reply(false, "", emptyList(), error = "Тень не ответила — открой её один раз и обнови до последней версии")
            else since = ts
        }
    }
    LaunchedEffect(since) {
        if (since == 0L) return@LaunchedEffect
        var quiet = 0
        val stop = System.currentTimeMillis() + 5 * 60_000L
        while (System.currentTimeMillis() < stop) {
            delay(900)
            val r = withContext(Dispatchers.IO) { Apps.reply(ctx, since) } ?: continue
            reply = r
            if (!r.working && r.msgs.isNotEmpty()) { if (++quiet >= 2) break } else quiet = 0
        }
        if (reply?.msgs.isNullOrEmpty()) reply = Apps.Reply(false, "", emptyList(), error = "Ответа пока нет — загляни в чат Тени")
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (drag < -120f) openDrawer() else if (drag > 120f) Apps.shade(ctx)
                drag = 0f
            }) { _, d -> drag += d }
        }) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val slice = pages.getOrElse(page) { emptyList() }
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (page == 0) {
                    // часы и «недавние» рядом
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date(now)).uppercase(), style = body(13, FontWeight.SemiBold, p.muted).copy(letterSpacing = 0.4.sp), maxLines = 1)
                            Text(SimpleDateFormat("HH:mm", Locale("ru")).format(Date(now)), style = TextStyle(fontFamily = Fonts.display, fontSize = 58.sp, lineHeight = 66.sp, fontWeight = FontWeight.SemiBold, color = p.ink), maxLines = 1)
                            Text(when { !ten.ok -> "Тень не отвечает"; ten.busy -> "Тень работает над задачей"; else -> "Тень на связи" }, style = body(13, c = p.muted), maxLines = 1)
                        }
                        Recents(p, recents, usageOk, letters, onOpenApp)
                    }
                    AskField(p, q, { q = it }, ::send)
                    val r = reply
                    if (r != null) ReplyPanel(p, r, asked, close = { reply = null; since = 0L })
                    else TenCards(p, ten)
                } else Text("СТОЛ ${page + 1}", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp))

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val cap = if (page == 0) Desk.FIRST else Desk.NEXT
                    val rows = if (page == 0) ((slice.size + 3) / 4).coerceAtLeast(1) else cap / 4
                    val hint = if (page == 0) 34.dp else 0.dp; val label = 18.dp; val gap = 12.dp
                    val byH = (maxHeight - hint) / rows - gap - label - 6.dp
                    val byW = (maxWidth - 24.dp) / 4
                    val tile = minOf(64.dp, byH, byW).coerceAtLeast(36.dp)
                    Column(Modifier.align(if (page == 0) Alignment.BottomCenter else Alignment.TopCenter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        DeskGrid(p, slice, Desk.base(page), letters, tile, onOpenApp, onOpenFolder, onMenu, onMove, onMerge)
                        if (page == 0) Text("↑  все приложения", style = body(11, FontWeight.SemiBold, p.muted), modifier = Modifier.align(Alignment.CenterHorizontally)
                            .clickable(onClickLabel = "Все приложения") { openDrawer() }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
        if (pages.size > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(Modifier.padding(horizontal = 4.dp).size(if (i == pager.currentPage) 8.dp else 6.dp).background(if (i == pager.currentPage) p.ink else p.line))
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 10.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Dock(p, "Телефон", { Apps.dial(ctx) }) { Glyph.Phone(it) }
                Dock(p, "Сообщения", { Apps.sms(ctx) }) { Glyph.Messages(it) }
                Dock(p, "Тень", { Apps.ten(ctx) }) { TenMark(p, 40.dp, if (p.dark) p.tile else p.onTile, if (p.dark) p.onTile else p.tile) }
                Dock(p, "Интернет", { Apps.browser(ctx) }) { Glyph.Internet(it) }
                Dock(p, "Камера", { Apps.camera(ctx) }) { Glyph.Camera(it) }
            }
        }
    }
}

/** Последние 10 приложений — лентой справа от часов. */
@Composable
fun Recents(p: Pal, recents: List<App>, usageOk: Boolean, letters: Map<String, String>, onOpen: (App) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.width(150.dp).padding(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("НЕДАВНИЕ", style = body(10, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(recents, key = { it.key }) { a ->
                Box(Modifier.clickable(onClickLabel = "Открыть ${a.label}") { onOpen(a) }) { Tile(p, letters[a.key] ?: Apps.section(a), 40.dp, 15.sp) }
            }
            if (!usageOk) item {
                Box(Modifier.size(40.dp).border(1.dp, p.ink).clickable(onClickLabel = "Дать доступ к недавним") { Recent.ask(ctx) }, contentAlignment = Alignment.Center) {
                    Text("+", style = body(20, FontWeight.SemiBold, p.ink))
                }
            }
        }
    }
}

@Composable
private fun AskField(p: Pal, q: String, set: (String) -> Unit, send: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).background(p.card).border(1.dp, p.ink).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TenMark(p, 36.dp, p.ink, p.card)
        BasicTextField(q, set, singleLine = true, textStyle = body(16, c = p.ink), cursorBrush = SolidColor(p.ink), modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner -> Box { if (q.isEmpty()) Text("Спроси Тень", style = body(16, c = p.muted)); inner() } })
        if (q.isNotBlank()) Box(Modifier.size(48.dp).background(p.ink).clickable(onClickLabel = "Отправить") { send() }, contentAlignment = Alignment.Center) {
            Text("→", style = body(22, FontWeight.Bold, p.bg))
        }
    }
}

@Composable
private fun ReplyPanel(p: Pal, r: Apps.Reply, asked: String, close: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).background(p.card).border(1.dp, p.line).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (r.working) "ТЕНЬ · ДУМАЕТ" else "ТЕНЬ · ОТВЕТ", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp), modifier = Modifier.weight(1f))
            Text("×", style = body(22, c = p.muted), modifier = Modifier.clickable(onClickLabel = "Закрыть ответ") { close() }.padding(horizontal = 6.dp))
        }
        Text("› $asked", style = body(13, c = p.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (r.error.isNotBlank()) Text(r.error, style = body(15, c = p.ink))
        if (r.msgs.isEmpty() && r.error.isBlank()) Text(r.step.ifBlank { "…" }, style = body(15, c = p.muted))
        r.msgs.forEach { m -> Text(m.text.replace("**", ""), style = body(15, c = p.ink).copy(lineHeight = 21.sp)) }
        if (r.working && r.msgs.isNotEmpty() && r.step.isNotBlank()) Text(r.step, style = body(12, c = p.muted))
        if (r.msgs.any { it.rich }) Text("Карточки и файлы — в чате Тени ›", style = body(13, FontWeight.SemiBold, p.ink), modifier = Modifier.clickable { Apps.ten(ctx) }.padding(vertical = 4.dp))
    }
}

@Composable
private fun TenCards(p: Pal, ten: Apps.TenState) {
    val ctx = LocalContext.current
    val hm = SimpleDateFormat("HH:mm", Locale("ru"))
    if (ten.gameTitle.isNotBlank() || ten.gameLimit > 0) Card(p, "ТЕНЬ · ИГРА", "", ten.gameTitle.ifBlank { "Игра ждёт тебя" }, "Ход ${ten.gameTurns} из ${ten.gameLimit}") { Apps.ten(ctx) }
    else if (ten.lastText.isNotBlank()) Card(p, "ТЕНЬ" + (ten.lastMeta.substringBefore(" · ").takeIf { it.isNotBlank() && it.length < 24 }?.let { " · ${it.uppercase()}" } ?: ""),
        if (ten.lastTs > 0) hm.format(Date(ten.lastTs)) else "", ten.lastText.replace("**", "").lines().first { it.isNotBlank() }.take(140), null) { Apps.ten(ctx) }
    if (ten.ok) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Small(p, "АКТИВНО", if (ten.busy) "Идёт задача" else "Нет задач", Modifier.weight(1f))
        Small(p, "ПОТРАЧЕНО СЕГОДНЯ", String.format(Locale("ru"), "%.2f ₽", ten.spent), Modifier.weight(1f))
    }
}

/**
 * Сетка стола 4 в ряд. Долгое нажатие и тянуть — переставить; держать над серединой другой плитки — папка;
 * отпустить, не двигая, — меню.
 */
@Composable
fun DeskGrid(p: Pal, items: List<Item>, base: Int, letters: Map<String, String>, tile: Dp, onOpenApp: (App) -> Unit, onOpenFolder: (FolderItem) -> Unit,
             onMenu: (Item) -> Unit, onMove: (String, Int) -> Unit, onMerge: (String, String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val bounds = remember { HashMap<String, Rect>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOff by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableIntStateOf(-1) }
    var merge by remember { mutableStateOf(false) }
    var suppress by remember { mutableStateOf(false) }
    val big = (tile.value * 0.34f).sp
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(4).forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { ci, item ->
                    val idx = ri * 4 + ci
                    val dragging = dragId == item.id
                    val label = when (item) { is AppItem -> item.app.label; is FolderItem -> item.name }
                    Column(Modifier.weight(1f)
                        .onGloballyPositioned { c -> if (dragId != item.id) bounds[item.id] = c.boundsInRoot() }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { if (dragging) { translationX = dragOff.x; translationY = dragOff.y; scaleX = 1.08f; scaleY = 1.08f } }
                        // нажатие — снаружи, перетаскивание — внутри: так тянущий жест первым забирает палец и не отменяется
                        .clickable(onClickLabel = "Открыть $label") {
                            if (suppress) suppress = false
                            else when (item) { is AppItem -> onOpenApp(item.app); is FolderItem -> onOpenFolder(item) }
                        }
                        .pointerInput(item.id, items) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); suppress = true; dragId = item.id; dragOff = Offset.Zero; target = idx; merge = false },
                                onDrag = { ch, d ->
                                    ch.consume(); dragOff += d
                                    val b = bounds[item.id] ?: return@detectDragGesturesAfterLongPress
                                    val center = b.center + dragOff
                                    val t = items.indexOfFirst { x -> x.id != item.id && bounds[x.id]?.contains(center) == true }
                                    if (t >= 0) {
                                        val tb = bounds[items[t].id]!!
                                        val inner = Rect(tb.left + tb.width * 0.22f, tb.top + tb.height * 0.18f, tb.right - tb.width * 0.22f, tb.bottom - tb.height * 0.3f)
                                        merge = item is AppItem && inner.contains(center)
                                        target = t
                                    } else { target = idx; merge = false }
                                },
                                onDragEnd = {
                                    val moved = dragOff.getDistance() > 24f
                                    val t = target; val m = merge
                                    dragId = null; dragOff = Offset.Zero; target = -1; merge = false
                                    when {
                                        !moved -> onMenu(item)
                                        m && t >= 0 && t < items.size -> onMerge(item.id, items[t].id)
                                        t >= 0 && t != idx -> onMove(item.id, base + t)
                                    }
                                },
                                onDragCancel = { dragId = null; dragOff = Offset.Zero; target = -1; merge = false; suppress = false })
                        },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val hl = dragId != null && !dragging && target == idx
                        Box(Modifier.then(when {
                            hl && merge -> Modifier.background(p.soft).border(3.dp, p.ink).padding(5.dp)
                            hl -> Modifier.border(2.dp, p.ink).padding(3.dp)
                            else -> Modifier
                        })) {
                            when (item) {
                                is AppItem -> Tile(p, letters[item.app.key].orEmpty(), tile, big)
                                is FolderItem -> FolderTile(p, tile, item.apps.size)
                            }
                        }
                        Text(label, style = body(12, FontWeight.SemiBold, p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Папка — тот же квадрат, внутри контур папки и число приложений. */
@Composable
fun FolderTile(p: Pal, size: Dp, count: Int) {
    Box(Modifier.size(size).background(p.tile).then(if (p.dark) Modifier.border(1.dp, p.tileLine) else Modifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.62f)) {
            val w = this.size.width
            val path = Path().apply {
                moveTo(0.06f * w, 0.2f * w); lineTo(0.4f * w, 0.2f * w); lineTo(0.5f * w, 0.32f * w); lineTo(0.94f * w, 0.32f * w)
                lineTo(0.94f * w, 0.84f * w); lineTo(0.06f * w, 0.84f * w); close()
            }
            drawPath(path, p.onTile, style = Stroke(w * 0.075f, join = StrokeJoin.Miter))
        }
        Text("$count", style = TextStyle(fontFamily = Fonts.display, fontSize = (size.value * 0.2f).sp, fontWeight = FontWeight.SemiBold, color = p.onTile),
            modifier = Modifier.padding(top = size * 0.12f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderDialog(p: Pal, f: FolderItem, letters: Map<String, String>, onOpen: (App) -> Unit, onRename: () -> Unit, onTakeOut: (App) -> Unit, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(20.dp).fillMaxWidth().background(p.card).border(1.dp, p.ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = TextStyle(fontFamily = Fonts.display, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = p.ink),
                    modifier = Modifier.weight(1f).clickable(onClickLabel = "Переименовать папку") { onRename() })
                Text("×", style = body(24, c = p.muted), modifier = Modifier.clickable(onClickLabel = "Закрыть") { dismiss() }.padding(horizontal = 8.dp))
            }
            f.apps.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a ->
                        Column(Modifier.weight(1f).combinedClickable(onClickLabel = "Открыть ${a.label}", onLongClick = { onTakeOut(a) }) { onOpen(a) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tile(p, letters[a.key].orEmpty(), 56.dp, 19.sp)
                            Text(a.label, style = body(12, FontWeight.SemiBold, p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text("Долгое нажатие — вынуть на стол. Тап по названию — переименовать.", style = body(12, c = p.muted))
        }
    }
}

@Composable
fun ListDialog(p: Pal, title: String, sub: String, rows: List<Pair<String, () -> Unit>>, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(280.dp).background(p.card).border(1.dp, p.ink)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(title, style = body(16, FontWeight.Bold, p.ink), maxLines = 2)
                Text(sub, style = body(11, c = p.muted))
            }
            rows.forEach { (t, f) ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.soft))
                Text(t, style = body(15, c = p.ink), modifier = Modifier.fillMaxWidth().clickable { dismiss(); f() }.padding(horizontal = 14.dp, vertical = 14.dp))
            }
        }
    }
}

@Composable
fun NameDialog(p: Pal, current: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var v by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(300.dp).background(p.card).border(1.dp, p.ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Название папки", style = body(16, FontWeight.Bold, p.ink))
            Box(Modifier.fillMaxWidth().height(52.dp).border(1.dp, p.ink).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(v, { v = it.take(24) }, singleLine = true, textStyle = body(17, FontWeight.SemiBold, p.ink), cursorBrush = SolidColor(p.ink))
            }
            Text("Сохранить", style = body(14, FontWeight.SemiBold, p.bg), modifier = Modifier.background(p.ink).clickable { save(v.trim().ifBlank { "Папка" }) }.padding(horizontal = 14.dp, vertical = 12.dp))
        }
    }
}

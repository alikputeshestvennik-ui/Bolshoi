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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
    var pages by remember { mutableStateOf(listOf(emptyList<Item>())) }
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
        all = a; pages = h; hidden = Apps.hidden(ctx)
        usageOk = Recent.granted(ctx)
        recents = withContext(Dispatchers.IO) { Recent.list(ctx, a) }
    }
    LaunchedEffect(tick) { while (true) { ten = withContext(Dispatchers.IO) { Apps.state(ctx) }; delay(20_000) } }

    fun update(l: List<List<Item>>) { pages = l; Desk.save(ctx, l) }
    val letters = remember(pages, all, version) {
        val shown = Desk.appsIn(pages); val custom = Apps.customLetters(ctx)
        Apps.letters(ctx, all).filterKeys { k -> shown.none { it.key == k } }.mapValues { (k, v) -> custom[k] ?: v.take(1) } + Apps.letters(ctx, shown)
    }
    val open: (App) -> Unit = { a -> Recent.note(ctx, a); Apps.launch(ctx, a) }

    // главный экран под списком: при открытии списка он чуть отъезжает вглубь и гаснет, список выплывает снизу
    val homeScale by animateFloatAsState(if (drawer) 0.94f else 1f, tween(340, easing = FastOutSlowInEasing), label = "homeScale")
    val homeAlpha by animateFloatAsState(if (drawer) 0f else 1f, tween(280, easing = FastOutSlowInEasing), label = "homeAlpha")
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = homeScale; scaleY = homeScale; alpha = homeAlpha }
            .then(if (drawer) Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } } else Modifier)) {
            HomeScreen(p, pages, letters, ten, recents, usageOk,
                onOpenApp = open, onOpenFolder = { openFolder = it.fid },
                onMenu = { when (it) { is AppItem -> menu = it.app; is FolderItem -> folderMenu = it } },
                onMoveTo = { id, pg, idx -> update(Desk.moveTo(pages, id, pg, idx)) },
                onMerge = { d, t -> update(Desk.merge(pages, d, t)) },
                openDrawer = { openDrawer(true) })
        }
        AnimatedVisibility(drawer,
            enter = slideInVertically(tween(360, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(p.bg).pointerInput(Unit) { detectTapGestures { } }) {
                Drawer(p, all, hidden, letters, onOpen = { open(it); openDrawer(false) }, onMenu = { menu = it }, close = { openDrawer(false) })
            }
        }
    }

    menu?.let { a ->
        val onHome = Desk.contains(pages, a.key)
        val isHidden = a.key in hidden
        val at = Desk.find(pages, a.key)
        val extra = ArrayList<Pair<String, () -> Unit>>()
        if (at != null) {
            if (at.first < Desk.PAGES - 1) extra += "На следующий стол →" to { update(Desk.moveTo(pages, a.key, at.first + 1, 0)) }
            if (at.first > 0) extra += "← На предыдущий стол" to { update(Desk.moveTo(pages, a.key, at.first - 1, -1)) }
        }
        AppMenu(p, a, onHome, isHidden, dismiss = { menu = null },
            toggleHome = {
                if (onHome) update(Desk.remove(pages, a.key))
                else { val n = Desk.add(pages, AppItem(a)); update(n); Toast.makeText(ctx, "Добавил на стол ${Desk.find(n, a.key)?.first?.plus(1) ?: n.size}", Toast.LENGTH_SHORT).show() }
            },
            moveFirst = if (at != null) ({ update(Desk.moveTo(pages, a.key, 0, 0)) }) else null,
            letter = { letterFor = a },
            toggleHidden = { Apps.setHidden(ctx, a.key, !isHidden); if (!isHidden && onHome) update(Desk.remove(pages, a.key)); version++ },
            info = { Apps.info(ctx, a) }, uninstall = { Apps.uninstall(ctx, a) }, extra = extra)
    }
    letterFor?.let { a ->
        LetterDialog(p, a, letters[a.key].orEmpty(), dismiss = { letterFor = null }, save = { v -> Apps.setLetter(ctx, a.key, v); version++; letterFor = null })
    }
    openFolder?.let { fid -> Desk.folder(pages, fid) }?.let { f ->
        FolderDialog(p, f, letters, onOpen = { open(it); openFolder = null }, onRename = { rename = f },
            onTakeOut = { app ->
                update(Desk.insertAfter(Desk.remove(pages, app.key), f.id, AppItem(app)))
                Toast.makeText(ctx, "«${app.label}» — на столе", Toast.LENGTH_SHORT).show()
            }, dismiss = { openFolder = null })
    }
    folderMenu?.let { f ->
        ListDialog(p, f.name, "папка · ${f.apps.size}", listOf(
            "Открыть" to { openFolder = f.fid },
            "Переименовать" to { rename = f },
            "Расформировать" to { update(Desk.replace(pages, f.id, f.apps.map { a -> AppItem(a) })) },
        ), dismiss = { folderMenu = null })
    }
    rename?.let { f ->
        NameDialog(p, f.name, dismiss = { rename = null }, save = { n -> update(Desk.replace(pages, f.id, listOf(f.copy(name = n)))); rename = null })
    }
}

/** Что тянут сейчас: откуда, где палец держит плитку, размер. */
class Drag(val item: Item, val fromPage: Int, val fromIdx: Int, val grab: Offset, val start: Offset, val w: Float, val tile: Dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(p: Pal, pages: List<List<Item>>, letters: Map<String, String>, ten: Apps.TenState, recents: List<App>, usageOk: Boolean,
               onOpenApp: (App) -> Unit, onOpenFolder: (FolderItem) -> Unit, onMenu: (Item) -> Unit,
               onMoveTo: (String, Int, Int) -> Unit, onMerge: (String, String) -> Unit, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000L * (60 - (value / 1000) % 60)) } }
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    // ——— перетаскивание: одно на весь экран, плитка летит поверх столов ———
    var drag by remember { mutableStateOf<Drag?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var hover by remember { mutableStateOf<Pair<Int, Int>?>(null) }      // стол и место под пальцем (-1 — пусто, в конец)
    var hoverInner by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<Int, Int>?>(null) }    // куда уже раздвинуты плитки
    var merge by remember { mutableStateOf(false) }
    var edge by remember { mutableIntStateOf(0) }
    var settle by remember { mutableStateOf(false) }                      // только что поставили — соседи без «фантомной» анимации
    var landing by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var suppress by remember { mutableStateOf(false) }
    val slots = remember { HashMap<Pair<Int, Int>, Rect>() }
    val areas = remember { HashMap<Int, Rect>() }

    val count = pages.size + if (drag != null && pages.size < Desk.PAGES) 1 else 0   // при переносе справа ждёт пустой стол
    val pager = rememberPagerState { count }
    val scope = rememberCoroutineScope()

    fun orderFor(pg: Int): List<String> {
        val ids = pages.getOrElse(pg) { emptyList() }.map { it.id }
        val d = drag ?: return ids
        val pv = preview ?: return ids
        val l = ids.filter { it != d.item.id }.toMutableList()
        if (pv.first == pg) l.add((if (pv.second < 0) l.size else pv.second).coerceIn(0, l.size), d.item.id)
        return l
    }
    // над серединой чужой плитки — папка, над краем или пустым — раздвинуть
    LaunchedEffect(hover, hoverInner, drag) {
        merge = false
        val d = drag ?: return@LaunchedEffect
        val h = hover ?: return@LaunchedEffect
        val occupant = orderFor(h.first).getOrNull(h.second)
        if (hoverInner && occupant != null && occupant != d.item.id && d.item is AppItem) {
            delay(450); merge = true; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else { delay(170); preview = h }
    }
    // держишь у края — листаем стол
    LaunchedEffect(edge) {
        while (edge != 0 && drag != null) {
            delay(650)
            val t = pager.currentPage + edge
            if (t in 0 until count) { hover = null; pager.animateScrollToPage(t) } else break
        }
    }
    fun track() {
        val cur = pager.currentPage
        val inPage = slots.entries.firstOrNull { (k, r) -> k.first == cur && k.second < (pages.getOrNull(cur)?.size ?: 0) && r.contains(finger) }?.key
        val h = inPage ?: if (areas[cur]?.contains(finger) == true) cur to -1 else null
        if (h != hover) hover = h
        val inner = inPage != null && slots[inPage]!!.let { r -> Rect(r.left + r.width * 0.25f, r.top + r.height * 0.1f, r.right - r.width * 0.25f, r.top + r.height * 0.6f).contains(finger) }
        if (inner != hoverInner) hoverInner = inner
        val e = with(density) { 28.dp.toPx() }
        edge = when { finger.x < e -> -1; finger.x > screenW - e -> 1; else -> 0 }
    }
    val grip = GripCallbacks(
        start = { item, pg, idx, local, rect, tile ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); suppress = true; settle = false; landing = null
            finger = rect.topLeft + local
            drag = Drag(item, pg, idx, local, finger, rect.width, tile); hover = null; preview = null; merge = false
        },
        move = { delta -> finger += delta; track() },
        end = {
            val d = drag
            if (d != null) {
                val moved = (finger - d.start).getDistance() > 24f
                val h = hover; val pv = preview; val m = merge
                val occupant = h?.let { orderFor(it.first).getOrNull(it.second) }
                val dropAt = finger - d.grab
                drag = null; hover = null; preview = null; merge = false; edge = 0; hoverInner = false
                when {
                    !moved -> onMenu(d.item)
                    m && occupant != null && occupant != d.item.id -> onMerge(d.item.id, occupant)
                    else -> {
                        val t = h ?: pv
                        if (t != null && !(t.first == d.fromPage && t.second == d.fromIdx)) {
                            settle = true; landing = d.item.id to dropAt
                            onMoveTo(d.item.id, t.first, t.second)
                        } else landing = d.item.id to dropAt
                    }
                }
            }
        },
        cancel = { drag = null; hover = null; preview = null; merge = false; edge = 0; hoverInner = false; suppress = false })

    // «Спроси Тень» — состояние живёт тут, чтобы не терялось при листании столов
    var q by remember { mutableStateOf("") }
    var since by remember { mutableLongStateOf(0L) }
    var reply by remember { mutableStateOf<Apps.Reply?>(null) }
    var asked by remember { mutableStateOf("") }
    val kb = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
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

    var vdrag by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { vdrag = 0f }, onDragEnd = {
                    if (vdrag < -120f) openDrawer() else if (vdrag > 120f) Apps.shade(ctx)
                    vdrag = 0f
                }) { _, d -> vdrag += d }
            }) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = Desk.PAGES, userScrollEnabled = drag == null) { page ->
                val slice = pages.getOrElse(page) { emptyList() }
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (page == 0) {
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
                    } else Text(if (slice.isEmpty()) "НОВЫЙ СТОЛ — ОТПУСТИ ЗДЕСЬ" else "СТОЛ ${page + 1}", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp))

                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { areas[page] = it.boundsInRoot() }) {
                        val rows = if (page == 0) ((slice.size + 3) / 4).coerceAtLeast(1) else Desk.NEXT / 4
                        val hint = if (page == 0) 34.dp else 0.dp; val label = 18.dp; val gap = 12.dp
                        val byH = (maxHeight - hint) / rows - gap - label - 6.dp
                        val byW = (maxWidth - 24.dp) / 4
                        val tile = minOf(64.dp, byH, byW).coerceAtLeast(36.dp)
                        Column(Modifier.align(if (page == 0) Alignment.BottomCenter else Alignment.TopCenter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                            DeskGrid(p, page, slice, orderFor(page), drag?.item?.id, settle, landing, slots, letters, tile,
                                mergeTarget = if (merge) hover?.let { h -> if (h.first == page) orderFor(page).getOrNull(h.second) else null } else null,
                                onOpen = { item -> if (suppress) suppress = false else when (item) { is AppItem -> onOpenApp(item.app); is FolderItem -> onOpenFolder(item) } },
                                grip = grip)
                            if (page == 0) Text("↑  все приложения", style = body(11, FontWeight.SemiBold, p.muted), modifier = Modifier.align(Alignment.CenterHorizontally)
                                .clickable(onClickLabel = "Все приложения") { openDrawer() }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            if (count > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                repeat(count) { i ->
                    val sz by animateDpAsState(if (i == pager.currentPage) 8.dp else 6.dp, label = "dot")
                    Box(Modifier.padding(horizontal = 4.dp).size(sz).then(if (i >= pages.size) Modifier.border(1.dp, p.line) else Modifier.background(if (i == pager.currentPage) p.ink else p.line)))
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

        // плитка в руке — поверх всего, следует за пальцем через столы
        drag?.let { d ->
            val wDp = with(density) { d.w.toDp() }
            Column(Modifier.offset { IntOffset((finger.x - d.grab.x).roundToInt(), (finger.y - d.grab.y).roundToInt()) }.width(wDp)
                .graphicsLayer { scaleX = 1.1f; scaleY = 1.1f }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (val it = d.item) {
                    is AppItem -> Tile(p, letters[it.app.key].orEmpty(), d.tile, (d.tile.value * 0.34f).sp)
                    is FolderItem -> FolderTile(p, d.tile, it.apps.size)
                }
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
    Column(Modifier.fillMaxWidth().animateContentSize(tween(260, easing = FastOutSlowInEasing)).heightIn(max = 360.dp).background(p.card).border(1.dp, p.line).verticalScroll(rememberScrollState()).padding(14.dp),
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

/** Связь плитки с перетаскиванием на уровне всего экрана. */
class GripCallbacks(val start: (Item, Int, Int, Offset, Rect, Dp) -> Unit, val move: (Offset) -> Unit, val end: () -> Unit, val cancel: () -> Unit)

/**
 * Сетка стола 4 в ряд. Плитку в руке рисует HomeScreen поверх всего; здесь — место под неё и соседи,
 * которые плавно раздвигаются. После того как плитку поставили, соседи встают сразу, без «фантомного» хода.
 */
@Composable
fun DeskGrid(p: Pal, page: Int, items: List<Item>, order: List<String>, dragId: String?, settle: Boolean, landing: Pair<String, Offset>?,
             slots: HashMap<Pair<Int, Int>, Rect>, letters: Map<String, String>, tile: Dp, mergeTarget: String?,
             onOpen: (Item) -> Unit, grip: GripCallbacks) {
    val big = (tile.value * 0.34f).sp
    // жест на плитке живёт долго — берём всегда свежие колбэки (после переноса столы уже другие)
    val g by rememberUpdatedState(grip)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(4).forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { ci, item ->
                    key(item.id) {
                        val idx = ri * 4 + ci
                        val dragging = dragId == item.id
                        val label = when (item) { is AppItem -> item.app.label; is FolderItem -> item.name }
                        val shownAt = order.indexOf(item.id)
                        val target = if (dragId != null && !dragging && shownAt >= 0 && shownAt != idx) {
                            val from = slots[page to idx]; val to = slots[page to shownAt]
                            if (from != null && to != null) to.topLeft - from.topLeft else Offset.Zero
                        } else Offset.Zero
                        val overflow = dragId != null && !dragging && shownAt >= Desk.cap(page)
                        val anim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
                        val isLanding = landing?.first == item.id
                        LaunchedEffect(target, settle, dragId) {
                            if (isLanding) return@LaunchedEffect
                            if (settle && dragId == null) anim.snapTo(Offset.Zero) else anim.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 500f))
                        }
                        // поставленная плитка мягко «садится» с того места, где отпустил палец
                        LaunchedEffect(landing) {
                            val l = landing ?: return@LaunchedEffect
                            if (l.first != item.id) return@LaunchedEffect
                            val s = slots[page to idx] ?: return@LaunchedEffect
                            anim.snapTo(l.second - s.topLeft); anim.animateTo(Offset.Zero, spring(dampingRatio = 0.75f, stiffness = 420f))
                        }
                        val press = remember { MutableInteractionSource() }
                        val pressed by press.collectIsPressedAsState()
                        val scale by animateFloatAsState(if (pressed && dragId == null) 0.94f else 1f, spring(stiffness = 700f), label = "scale")
                        Column(Modifier.weight(1f)
                            .onGloballyPositioned { c -> slots[page to idx] = c.boundsInRoot() }
                            .graphicsLayer {
                                translationX = anim.value.x; translationY = anim.value.y; scaleX = scale; scaleY = scale
                                alpha = when { dragging -> 0f; overflow -> 0.25f; else -> 1f }
                            }
                            // нажатие — снаружи, перетаскивание — внутри: тянущий жест первым забирает палец и не отменяется
                            .clickable(interactionSource = press, indication = null, onClickLabel = "Открыть $label") { onOpen(item) }
                            .pointerInput(item.id, page, idx) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { local -> slots[page to idx]?.let { r -> g.start(item, page, idx, local, r, tile) } },
                                    onDrag = { ch, d -> ch.consume(); g.move(d) },
                                    onDragEnd = { g.end() },
                                    onDragCancel = { g.cancel() })
                            },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val ring by animateDpAsState(if (mergeTarget == item.id) 3.dp else 0.dp, label = "ring")
                            Box(Modifier.then(if (ring > 0.dp) Modifier.border(ring, p.ink).padding(ring + 2.dp) else Modifier)) {
                                when (item) {
                                    is AppItem -> Tile(p, letters[item.app.key].orEmpty(), tile, big)
                                    is FolderItem -> FolderTile(p, tile, item.apps.size)
                                }
                            }
                            Text(label, style = body(12, FontWeight.SemiBold, p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (items.isEmpty()) Box(Modifier.fillMaxWidth().height(tile + 24.dp).border(1.dp, p.line), contentAlignment = Alignment.Center) {
            Text("Пусто", style = body(13, c = p.muted))
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
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { Pop {
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
    } }
}

/** Мягкое появление окна: чуть увеличивается и проявляется. */
@Composable
fun Pop(content: @Composable () -> Unit) {
    val st = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(st, enter = scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.92f) + fadeIn(tween(180))) { content() }
}

@Composable
fun ListDialog(p: Pal, title: String, sub: String, rows: List<Pair<String, () -> Unit>>, dismiss: () -> Unit) {
    Dialog(onDismissRequest = dismiss) { Pop {
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
    } }
}

@Composable
fun NameDialog(p: Pal, current: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var v by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = dismiss) { Pop {
        Column(Modifier.width(300.dp).background(p.card).border(1.dp, p.ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Название папки", style = body(16, FontWeight.Bold, p.ink))
            Box(Modifier.fillMaxWidth().height(52.dp).border(1.dp, p.ink).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(v, { v = it.take(24) }, singleLine = true, textStyle = body(17, FontWeight.SemiBold, p.ink), cursorBrush = SolidColor(p.ink))
            }
            Text("Сохранить", style = body(14, FontWeight.SemiBold, p.bg), modifier = Modifier.background(p.ink).clickable { save(v.trim().ifBlank { "Папка" }) }.padding(horizontal = 14.dp, vertical = 12.dp))
        }
    } }
}

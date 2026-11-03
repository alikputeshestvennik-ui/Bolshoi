package ru.alim.home

import android.content.res.AssetManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Палитра — как у темы «Монохром» в Тени. */
class Pal(val dark: Boolean, val bg: Color, val card: Color, val line: Color, val soft: Color, val ink: Color, val muted: Color,
          val tile: Color, val onTile: Color, val tileLine: Color, val shadow: Color)

val DAY = Pal(false, Color(0xFFF6F6F4), Color(0xFFFFFFFF), Color(0xFFD6D6D2), Color(0xFFE4E4E0), Color(0xFF111111), Color(0xFF5E5E5E),
    Color(0xFF111111), Color(0xFFF6F6F4), Color(0xFF111111), Color(0xFF9A9A96))
val NIGHT = Pal(true, Color(0xFF0E0E0E), Color(0xFF161616), Color(0xFF2E2E2E), Color(0xFF242424), Color(0xFFF2F2F0), Color(0xFFA8A8A4),
    Color(0xFF1C1C1B), Color(0xFFF2F2F0), Color(0xFF2E2E2E), Color(0xFF6E6E6A))

object Fonts {
    var body: FontFamily = FontFamily.SansSerif
    var display: FontFamily = FontFamily.SansSerif
    fun load(am: AssetManager) {
        val have = runCatching { am.list("fonts")?.toSet() }.getOrNull() ?: emptySet()
        runCatching { if ("manrope.ttf" in have) body = FontFamily(Font("fonts/manrope.ttf", am, FontWeight.Normal), Font("fonts/manrope.ttf", am, FontWeight.SemiBold), Font("fonts/manrope.ttf", am, FontWeight.Bold)) }
        runCatching { if ("unbounded.ttf" in have) display = FontFamily(Font("fonts/unbounded.ttf", am, FontWeight.SemiBold)) }
    }
}

class HomeActivity : ComponentActivity() {
    private var drawer by mutableStateOf(false)
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Fonts.load(assets)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (drawer) drawer = false }
        })
        setContent {
            val p = if (isSystemInDarkTheme()) NIGHT else DAY
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !p.dark; isAppearanceLightNavigationBars = !p.dark
                }
            }
            Root(p, drawer, tick, openDrawer = { drawer = it })
        }
    }

    // кнопка «Домой» — всегда на главный, список закрываем
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); drawer = false }
    override fun onResume() { super.onResume(); tick++ }
}

private fun body(size: Int, w: FontWeight = FontWeight.Normal, c: Color) = TextStyle(fontFamily = Fonts.body, fontSize = size.sp, fontWeight = w, color = c)

@Composable
fun Root(p: Pal, drawer: Boolean, tick: Int, openDrawer: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(emptyList<App>()) }
    var home by remember { mutableStateOf(emptyList<App>()) }
    var hidden by remember { mutableStateOf(emptySet<String>()) }
    var ten by remember { mutableStateOf(Apps.TenState(false, "", 0, "", 0.0, false, "", 0, 0)) }
    var version by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<App?>(null) }
    var letterFor by remember { mutableStateOf<App?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick, version) {
        val (a, h) = withContext(Dispatchers.IO) { val a = Apps.load(ctx); a to Apps.home(ctx, a) }
        all = a; home = h; hidden = Apps.hidden(ctx)
    }
    // данные Тени: при возврате на экран и раз в 20 с
    LaunchedEffect(tick) {
        while (true) { ten = withContext(Dispatchers.IO) { Apps.state(ctx) }; delay(20_000) }
    }
    val letters = remember(home, all, version) { Apps.letters(ctx, home) + Apps.letters(ctx, all).filterKeys { k -> home.none { it.key == k } }.mapValues { (k, v) -> Apps.customLetters(ctx)[k] ?: v.take(1) } }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        if (!drawer) HomeScreen(p, home, letters, ten, onOpen = { Apps.launch(ctx, it) }, onMenu = { menu = it },
            onMove = { a, to -> val l = home.filter { it.key != a.key }.toMutableList(); l.add(to.coerceIn(0, l.size), a); home = l; Apps.setHome(ctx, l) },
            openDrawer = { openDrawer(true) })
        else Drawer(p, all, hidden, letters, onOpen = { Apps.launch(ctx, it); openDrawer(false) }, onMenu = { menu = it }, close = { openDrawer(false) })
    }

    menu?.let { a ->
        val onHome = home.any { it.key == a.key }
        val isHidden = a.key in hidden
        AppMenu(p, a, onHome, isHidden, dismiss = { menu = null },
            toggleHome = {
                val next = if (onHome) home.filter { it.key != a.key } else home + a
                if (!onHome && home.size >= Apps.HOME_MAX) Toast.makeText(ctx, "На главном максимум ${Apps.HOME_MAX}", Toast.LENGTH_SHORT).show()
                else { Apps.setHome(ctx, next); version++ }
            },
            moveFirst = if (onHome) ({ Apps.setHome(ctx, listOf(a) + home.filter { it.key != a.key }); version++ }) else null,
            letter = { letterFor = a },
            toggleHidden = { Apps.setHidden(ctx, a.key, !isHidden); if (!isHidden && onHome) Apps.setHome(ctx, home.filter { it.key != a.key }); version++ },
            info = { Apps.info(ctx, a) }, uninstall = { Apps.uninstall(ctx, a) })
    }
    letterFor?.let { a ->
        LetterDialog(p, a, letters[a.key].orEmpty(), dismiss = { letterFor = null }, save = { v -> Apps.setLetter(ctx, a.key, v); version++; letterFor = null })
    }
}

/** Плитка с буквой: первая — крупно, вторая — мельче. */
@Composable
fun Tile(p: Pal, letter: String, size: Dp, big: TextUnit) {
    Box(Modifier.size(size).background(p.tile).then(if (p.dark) Modifier.border(1.dp, p.tileLine) else Modifier), contentAlignment = Alignment.Center) {
        Text(buildAnnotatedString {
            append(letter.take(1))
            if (letter.length > 1) withStyle(SpanStyle(fontSize = big * 0.68f)) { append(letter.drop(1).take(1)) }
        }, style = TextStyle(fontFamily = Fonts.display, fontSize = big, fontWeight = FontWeight.SemiBold, color = p.onTile))
    }
}

@Composable
fun TenMark(p: Pal, size: Dp, ink: Color, paper: Color) {
    val u = size / 40f
    Box(Modifier.size(size)) {
        Box(Modifier.offset(u * 8, u * 8).size(u * 28).background(p.shadow))
        Box(Modifier.offset(u * 4, u * 4).size(u * 28).background(ink).border(u * 2, paper))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(p: Pal, home: List<App>, letters: Map<String, String>, ten: Apps.TenState, onOpen: (App) -> Unit, onMenu: (App) -> Unit,
               onMove: (App, Int) -> Unit, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000L * (60 - (value / 1000) % 60)) } }
    var drag by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (drag < -120f) openDrawer() else if (drag > 120f) Apps.shade(ctx)
                drag = 0f
            }) { _, d -> drag += d }
        }
        .padding(horizontal = 20.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date(now)).uppercase(), style = body(13, FontWeight.SemiBold, p.muted).copy(letterSpacing = 0.4.sp))
            Text(SimpleDateFormat("HH:mm", Locale("ru")).format(Date(now)), style = TextStyle(fontFamily = Fonts.display, fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
            Text(when { !ten.ok -> "Тень не отвечает — обнови её до последней версии"; ten.busy -> "Тень работает над задачей"; else -> "Тень на связи" },
                style = body(13, c = p.muted))
        }

        // «Спроси Тень» — пишешь прямо здесь, ответ появляется под полем
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
                if (ts == null) { reply = Apps.Reply(false, "", emptyList(), error = "Тень не ответила — открой её один раз и обнови до последней версии"); return@launch }
                since = ts
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
                // закончила: не работает и ответ не меняется пару опросов подряд
                if (!r.working && r.msgs.isNotEmpty()) { if (++quiet >= 2) break } else quiet = 0
            }
            if (reply?.msgs.isNullOrEmpty()) reply = Apps.Reply(false, "", emptyList(), error = "Ответа пока нет — загляни в чат Тени")
        }
        Row(Modifier.fillMaxWidth().height(56.dp).background(p.card).border(1.dp, p.ink).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TenMark(p, 36.dp, p.ink, p.card)
            BasicTextField(q, { q = it }, singleLine = true, textStyle = body(16, c = p.ink), cursorBrush = SolidColor(p.ink), modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Спроси Тень", style = body(16, c = p.muted)); inner() } })
            if (q.isNotBlank()) Box(Modifier.size(48.dp).background(p.ink).clickable(onClickLabel = "Отправить") { send() }, contentAlignment = Alignment.Center) {
                Text("→", style = body(22, FontWeight.Bold, p.bg))
            }
        }

        val r = reply
        if (r != null) Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).background(p.card).border(1.dp, p.line).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.working) "ТЕНЬ · ДУМАЕТ" else "ТЕНЬ · ОТВЕТ", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp), modifier = Modifier.weight(1f))
                Text("×", style = body(22, c = p.muted), modifier = Modifier.clickable(onClickLabel = "Закрыть ответ") { reply = null; since = 0L }.padding(horizontal = 6.dp))
            }
            Text("› $asked", style = body(13, c = p.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.error.isNotBlank()) Text(r.error, style = body(15, c = p.ink))
            if (r.msgs.isEmpty() && r.error.isBlank()) Text(r.step.ifBlank { "…" }, style = body(15, c = p.muted))
            r.msgs.forEach { m -> Text(m.text.replace("**", ""), style = body(15, c = p.ink).copy(lineHeight = 21.sp)) }
            if (r.working && r.msgs.isNotEmpty() && r.step.isNotBlank()) Text(r.step, style = body(12, c = p.muted))
            if (r.msgs.any { it.rich }) Text("Карточки и файлы — в чате Тени ›", style = body(13, FontWeight.SemiBold, p.ink), modifier = Modifier.clickable { Apps.ten(ctx) }.padding(vertical = 4.dp))
        } else {
            // последнее от Тени (или игра, которая ждёт)
            val hm = SimpleDateFormat("HH:mm", Locale("ru"))
            if (ten.gameTitle.isNotBlank() || ten.gameLimit > 0) Card(p, "ТЕНЬ · ИГРА", "", ten.gameTitle.ifBlank { "Игра ждёт тебя" }, "Ход ${ten.gameTurns} из ${ten.gameLimit}") { Apps.ten(ctx) }
            else if (ten.lastText.isNotBlank()) Card(p, "ТЕНЬ" + (ten.lastMeta.substringBefore(" · ").takeIf { it.isNotBlank() && it.length < 24 }?.let { " · ${it.uppercase()}" } ?: ""),
                if (ten.lastTs > 0) hm.format(Date(ten.lastTs)) else "", ten.lastText.replace("**", "").lines().first { it.isNotBlank() }.take(140), null) { Apps.ten(ctx) }
            if (ten.ok) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small(p, "АКТИВНО", if (ten.busy) "Идёт задача" else "Нет задач", Modifier.weight(1f))
                Small(p, "ПОТРАЧЕНО СЕГОДНЯ", String.format(Locale("ru"), "%.2f ₽", ten.spent), Modifier.weight(1f))
            }
        }

        // сетка главного экрана: занимает всё, что осталось над нижним рядом; плитки ужимаются, если рядов много
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val rows = ((home.size + 3) / 4).coerceAtLeast(1)
            val hint = 34.dp; val label = 18.dp; val gap = 12.dp
            val byH = (maxHeight - hint) / rows - gap - label - 6.dp
            val byW = (maxWidth - 24.dp) / 4
            val tile = minOf(64.dp, byH, byW).coerceAtLeast(36.dp)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                HomeGrid(p, home, letters, tile, onOpen, onMenu, onMove)
                Text("↑  все приложения", style = body(11, FontWeight.SemiBold, p.muted), modifier = Modifier.align(Alignment.CenterHorizontally)
                    .clickable(onClickLabel = "Все приложения") { openDrawer() }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }

        // нижний ряд — значки, не буквы
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Dock(p, "Телефон", { Apps.dial(ctx) }) { Glyph.Phone(it) }
            Dock(p, "Сообщения", { Apps.sms(ctx) }) { Glyph.Messages(it) }
            Dock(p, "Тень", { Apps.ten(ctx) }) { TenMark(p, 40.dp, if (p.dark) p.tile else p.onTile, if (p.dark) p.onTile else p.tile) }
            Dock(p, "Интернет", { Apps.browser(ctx) }) { Glyph.Internet(it) }
            Dock(p, "Камера", { Apps.camera(ctx) }) { Glyph.Camera(it) }
        }
    }
}

/** Сетка 4 в ряд. Долгое нажатие и тянуть — переставить; отпустить на месте — меню. */
@Composable
fun HomeGrid(p: Pal, home: List<App>, letters: Map<String, String>, tile: Dp, onOpen: (App) -> Unit, onMenu: (App) -> Unit, onMove: (App, Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val bounds = remember { HashMap<String, Rect>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOff by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableIntStateOf(-1) }
    // после долгого нажатия отпускание пальца не должно ещё и открывать приложение
    var suppress by remember { mutableStateOf(false) }
    val big = (tile.value * 0.34f).sp
    fun targetOf(center: Offset): Int = home.indexOfFirst { a -> bounds[a.key]?.contains(center) == true }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        home.chunked(4).forEachIndexed { ri, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { ci, a ->
                    val idx = ri * 4 + ci
                    val dragging = dragKey == a.key
                    Column(Modifier.weight(1f)
                        .onGloballyPositioned { if (!dragging) bounds[a.key] = it.boundsInRoot() }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { if (dragging) { translationX = dragOff.x; translationY = dragOff.y; scaleX = 1.08f; scaleY = 1.08f } }
                        .pointerInput(a.key, home) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); suppress = true; dragKey = a.key; dragOff = Offset.Zero; target = idx },
                                onDrag = { ch, d -> ch.consume(); dragOff += d
                                    bounds[a.key]?.let { b -> val t = targetOf(b.center + dragOff); if (t >= 0) target = t } },
                                onDragEnd = {
                                    val moved = dragOff.getDistance() > 24f
                                    val t = target
                                    dragKey = null; dragOff = Offset.Zero; target = -1
                                    if (!moved) onMenu(a) else if (t >= 0 && t != idx) onMove(a, t)
                                },
                                onDragCancel = { dragKey = null; dragOff = Offset.Zero; target = -1; suppress = false })
                        }
                        .clickable(onClickLabel = "Открыть ${a.label}") { if (suppress) suppress = false else onOpen(a) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.then(if (target == idx && dragKey != null && !dragging) Modifier.border(2.dp, p.ink).padding(3.dp) else Modifier)) {
                            Tile(p, letters[a.key].orEmpty(), tile, big)
                        }
                        Text(a.label, style = body(12, FontWeight.SemiBold, p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Card(p: Pal, label: String, time: String, title: String, sub: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(p.card).border(1.dp, p.line).clickable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { Text(label, style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp), modifier = Modifier.weight(1f)); Text(time, style = body(11, FontWeight.Bold, p.muted)) }
        Text(title, style = body(15, FontWeight.Bold, p.ink).copy(lineHeight = 20.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = body(13, c = p.muted)) }
    }
}

@Composable
private fun Small(p: Pal, label: String, value: String, m: Modifier) {
    Column(m.background(p.card).border(1.dp, p.line).padding(12.dp)) {
        Text(label, style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp), maxLines = 1)
        Text(value, style = body(15, FontWeight.Bold, p.ink), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Dock(p: Pal, label: String, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    Box(Modifier.size(64.dp).background(if (p.dark) p.onTile else p.tile).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        glyph(if (p.dark) p.tile else p.onTile)
    }
}

// ——— список приложений ———

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Drawer(p: Pal, all: List<App>, hidden: Set<String>, letters: Map<String, String>, onOpen: (App) -> Unit, onMenu: (App) -> Unit, close: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    val list = remember(all, hidden, q, showHidden) {
        all.filter { (showHidden || it.key !in hidden) && (q.isBlank() || it.label.contains(q.trim(), true)) }
    }
    // строки: заголовок буквы + приложения
    val rows = remember(list) { buildList { var last = ""; list.forEach { a -> val s = Apps.section(a); if (s != last) { add(s); last = s }; add(a) } } }
    val sections = remember(rows) { rows.withIndex().filter { it.value is String }.map { it.value as String to it.index } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler { close() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable(onClickLabel = "Назад") { close() }, contentAlignment = Alignment.Center) {
                Text("←", style = body(26, FontWeight.SemiBold, p.ink))
            }
            Text("Приложения", style = TextStyle(fontFamily = Fonts.display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.ink), modifier = Modifier.weight(1f))
            Text("${all.size - hidden.size}" + if (hidden.isNotEmpty()) " · скрыто ${hidden.size}" else "", style = body(13, c = p.muted))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(52.dp).background(p.card).border(1.dp, p.ink).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(q, { q = it }, singleLine = true, textStyle = body(16, c = p.ink), cursorBrush = SolidColor(p.ink), modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Найти приложение", style = body(16, c = p.muted)); inner() } })
            if (q.isNotEmpty()) Text("×", style = body(22, c = p.muted), modifier = Modifier.clickable(onClickLabel = "Очистить") { q = "" }.padding(horizontal = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f)) {
            LazyColumn(state = state, modifier = Modifier.weight(1f).padding(start = 20.dp)) {
                items(rows, key = { if (it is App) it.key else "h:$it" }) { r ->
                    if (r is String) Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
                        Text(r, style = TextStyle(fontFamily = Fonts.display, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.muted))
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(p.line))
                    } else if (r is App) Row(Modifier.fillMaxWidth().height(68.dp).combinedClickable(onClickLabel = "Открыть ${r.label}", onLongClick = { onMenu(r) }) { onOpen(r) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Tile(p, letters[r.key] ?: Apps.section(r), 52.dp, 19.sp)
                        Text(r.label, style = body(17, FontWeight.SemiBold, if (r.key in hidden) p.muted else p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                if (hidden.isNotEmpty() && q.isBlank()) item {
                    Text(if (showHidden) "Убрать скрытые из списка" else "Показать скрытые (${hidden.size})", style = body(14, FontWeight.SemiBold, p.muted),
                        modifier = Modifier.clickable { showHidden = !showHidden }.padding(vertical = 20.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            // полоса букв: тап — к разделу
            Column(Modifier.width(40.dp).fillMaxHeight().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sections.forEach { (s, idx) ->
                    Text(s, style = body(13, FontWeight.Bold, p.muted), modifier = Modifier.clickable(onClickLabel = "К букве $s") { scope.launch { state.scrollToItem(idx) } }.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
    }
}

// ——— меню долгого нажатия ———

@Composable
fun AppMenu(p: Pal, a: App, onHome: Boolean, isHidden: Boolean, dismiss: () -> Unit, toggleHome: () -> Unit, moveFirst: (() -> Unit)?, letter: () -> Unit,
            toggleHidden: () -> Unit, info: () -> Unit, uninstall: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(280.dp).background(p.card).border(1.dp, p.ink)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(a.label, style = body(16, FontWeight.Bold, p.ink), maxLines = 2)
                Text(a.pkg, style = body(11, c = p.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            fun row(t: String, f: () -> Unit): @Composable () -> Unit = {
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.soft))
                Text(t, style = body(15, c = p.ink), modifier = Modifier.fillMaxWidth().clickable { dismiss(); f() }.padding(horizontal = 14.dp, vertical = 14.dp))
            }
            row(if (onHome) "Убрать с главного" else "На главный экран", toggleHome)()
            moveFirst?.let { row("Поставить первым", it)() }
            row("Сменить букву", letter)()
            row(if (isHidden) "Вернуть из скрытых" else "Скрыть из списка", toggleHidden)()
            row("О приложении", info)()
            row("Удалить", uninstall)()
        }
    }
}

@Composable
fun LetterDialog(p: Pal, a: App, current: String, dismiss: () -> Unit, save: (String?) -> Unit) {
    var v by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = dismiss) {
        Column(Modifier.width(280.dp).background(p.card).border(1.dp, p.ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Буква для «${a.label}»", style = body(16, FontWeight.Bold, p.ink))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(p, v.ifBlank { "?" }, 64.dp, 22.sp)
                Box(Modifier.weight(1f).height(52.dp).border(1.dp, p.ink).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(v, { v = it.take(2) }, singleLine = true, textStyle = body(20, FontWeight.SemiBold, p.ink), cursorBrush = SolidColor(p.ink))
                }
            }
            Text("Один-два символа. Пусто — как по названию.", style = body(12, c = p.muted))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Сбросить", style = body(14, FontWeight.SemiBold, p.ink), modifier = Modifier.border(1.dp, p.line).clickable { save(null) }.padding(horizontal = 14.dp, vertical = 12.dp))
                Text("Сохранить", style = body(14, FontWeight.SemiBold, p.bg), modifier = Modifier.background(p.ink).clickable { save(v.trim().ifBlank { null }) }.padding(horizontal = 14.dp, vertical = 12.dp))
            }
        }
    }
}

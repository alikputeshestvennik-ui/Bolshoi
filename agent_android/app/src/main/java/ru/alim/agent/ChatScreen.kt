package ru.alim.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val hhmm = SimpleDateFormat("HH:mm", Locale("ru"))

@Composable
fun ChatScreen(fid: String, go: (Scr) -> Unit) {
    val chat = remember(fid) { Chat.of(fid) }
    val core = chat.core
    val f = chat.friend
    LaunchedEffect(fid, chat.msgs.size) { chat.markRead(); runCatching { androidx.core.app.NotificationManagerCompat.from(Chat.app).cancel(chat.notifId()) } }
    val ctx = LocalContext.current
    val haze = remember { HazeState() }
    val list = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    val msgs = chat.msgs
    val shown = remember(msgs.size, search, msgs.lastOrNull()) {
        val q = search?.trim().orEmpty()
        if (q.isEmpty()) msgs.toList() else msgs.filter { it.text.contains(q, true) }
    }
    // новые сообщения — прокрутка вниз
    LaunchedEffect(msgs.size, core.busy) { if (search == null) list.animateScrollToItem(0) }
    var tick by remember { mutableStateOf(0) }
    var composerH by remember { mutableStateOf(220) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    androidx.activity.compose.BackHandler(enabled = ChatSel.on || search != null) { if (ChatSel.on) ChatSel.ids.clear() else search = null }
    Box(Modifier.fillMaxSize().background(Th.c.bg)) {
        // ——— фон и лента (под стеклом) ———
        Box(Modifier.fillMaxSize().haze(haze, HazeStyle(tint = Th.c.glass, blurRadius = 24.dp, noiseFactor = 0.05f))) {
            Wallpaper()
            // лента идёт во весь экран — под шторкой и нижней полосой, отступы только внутри
            val bars = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = top + 80.dp, bottom = bars + with(LocalDensity.current) { composerH.toDp() } + 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                core.pending?.let { p -> item(key = "confirm") { ConfirmCard(p, core) } }
                if (core.busy && core.pending == null) item(key = "typing") { Typing(core) }
                val rev = shown.asReversed()
                rev.forEachIndexed { i, m ->
                    item(key = m.id) { MsgRow(m, chat = chat, f = f, showButtons = Prefs.quickButtons && i == 0 && !m.me && !core.busy && search == null) }
                    val prev = rev.getOrNull(i + 1)
                    if (prev == null || !sameDay(prev.ts, m.ts)) item(key = "d${m.id}") { DateChip(m.ts) }
                }
                if (msgs.isEmpty()) item(key = "hello") { Hello(f) }
            }
        }

        // ——— затемнение сверху и снизу: лента плавно уходит в фон под плашками ———
        val fade = Th.c.wall.first()
        val fadeBottom = Th.c.wall.last()
        val topH = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 96.dp
        val botH = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding() +
            with(LocalDensity.current) { composerH.toDp() } + 34.dp
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topH)
            .background(Brush.verticalGradient(listOf(fade.copy(alpha = 0.95f), fade.copy(alpha = 0.75f), fade.copy(alpha = 0f)))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(botH)
            .background(Brush.verticalGradient(listOf(fadeBottom.copy(alpha = 0f), fadeBottom.copy(alpha = 0.75f), fadeBottom.copy(alpha = 0.95f)))))

        // ——— шапка ———
        if (ChatSel.on) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(48.dp).glass(haze, CircleShape).clickable(onClickLabel = "Снять выделение") { ChatSel.ids.clear() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Снять выделение", tint = Ink)
            }
            Box(Modifier.weight(1f).height(48.dp).glass(haze, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                Text("Выбрано ${ChatSel.ids.size}", style = body(17, FontWeight.SemiBold))
            }
            Row(Modifier.height(48.dp).glass(haze, RoundedCornerShape(24.dp)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icCopy), "Копировать", tint = Ink, modifier = Modifier.size(44.dp).clickable {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("text", ChatSel.asText(chat))); ChatSel.ids.clear()
                }.padding(10.dp))
                Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icForward), "Переслать", tint = Ink, modifier = Modifier.size(44.dp).clickable {
                    shareText(ctx, ChatSel.asText(chat)); ChatSel.ids.clear()
                }.padding(10.dp))
                Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icTrash), "Удалить", tint = Ink, modifier = Modifier.size(44.dp).clickable {
                    chat.delete(ChatSel.ids.toList()); ChatSel.ids.clear()
                }.padding(10.dp))
            }
        } else Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (search != null) {
                Row(Modifier.weight(1f).height(52.dp).glass(haze, RoundedCornerShape(26.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = Muted, modifier = Modifier.size(20.dp)); HGap(10)
                    BasicTextField(search.orEmpty(), { search = it }, Modifier.weight(1f), singleLine = true,
                        textStyle = body(16), cursorBrush = SolidColor(Teal),
                        decorationBox = { inner -> if (search.isNullOrEmpty()) Text("Поиск по переписке", style = body(16, color = Muted)); inner() })
                    Icon(Icons.Filled.Close, "Закрыть поиск", tint = Muted, modifier = Modifier.size(22.dp).clickable { search = null })
                }
            } else {
                Row(Modifier.weight(1f).height(56.dp).glass(haze, RoundedCornerShape(28.dp)).clickable(onClickLabel = "Профиль") { go(Scr.Profile) }
                    .padding(start = 6.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(44.dp, online = Online.online && tick >= 0, f = f)
                    HGap(10)
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = body(17, FontWeight.Bold), maxLines = 1)
                        val st = Online.status(core, f)
                        Text(if (core.busy) shortStatus(st) else st, style = body(if (core.busy) 12 else 13, color = if (Online.online && !core.busy) Teal else Muted),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HGap(8)
            Box {
                Box(Modifier.size(48.dp).glass(haze, CircleShape).clickable(onClickLabel = "Меню") { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MoreVert, "Меню", tint = Ink)
                }
                DropdownMenu(menu, { menu = false }, Modifier.background(Th.c.card)) {
                    listOf(
                        "История и расходы" to { go(Scr.History) },
                        "Что ${f.name} помнит" to { go(Scr.Memory) },
                        "Сводки дня" to { go(Scr.Digest) },
                        "Сам пишет первым" to { go(Scr.Initiative) },
                        "Отвечать за меня" to { go(Scr.Rules) },
                        "Оформление" to { go(Scr.Look) },
                        "Настройки" to { go(Scr.Settings) },
                        "Поиск по переписке" to { search = "" },
                    ).forEach { (t, f) ->
                        DropdownMenuItem(text = { Text(t, style = body(15)) }, onClick = { menu = false; f() })
                    }
                }
            }
        }

        // ——— ввод ———
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 10.dp).padding(bottom = 10.dp)
            .onSizeChanged { composerH = it.height }) {
            if (ChatSel.on) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val one = ChatSel.ids.size == 1
                Row(Modifier.weight(1f).height(52.dp).glass(haze, RoundedCornerShape(26.dp))
                    .clickable(enabled = one) { chat.replyTo = ChatSel.picked(chat).firstOrNull(); ChatSel.ids.clear() },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text("Ответить", style = body(16, FontWeight.SemiBold, if (one) Ink else Muted)); HGap(8)
                    Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icReply), null, tint = if (one) Ink else Muted, modifier = Modifier.size(22.dp))
                }
                Row(Modifier.weight(1f).height(52.dp).glass(haze, RoundedCornerShape(26.dp))
                    .clickable { shareText(ctx, ChatSel.asText(chat)); ChatSel.ids.clear() },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icForward), null, tint = Ink, modifier = Modifier.size(22.dp)); HGap(8)
                    Text("Переслать", style = body(16, FontWeight.SemiBold))
                }
            } else Composer(haze, chat, f)
        }

        // ——— кнопка «вниз»: над микрофоном, того же размера ———
        val farFromBottom by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 1 } }
        if (farFromBottom && !ChatSel.on) {
            val scope = rememberCoroutineScope()
            val lift = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding() +
                with(LocalDensity.current) { composerH.toDp() } + 18.dp
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = lift).size(46.dp).glass(haze, CircleShape)
                .clickable(onClickLabel = "Вниз") { scope.launch { list.animateScrollToItem(0) } }, contentAlignment = Alignment.Center) {
                Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icDown), "Вниз", tint = Ink, modifier = Modifier.size(24.dp))
            }
        }
    }
}

private fun Modifier.glass(h: HazeState, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.clip(shape).hazeChild(h, shape).background(Th.c.glass.copy(alpha = if (Th.c.dark) 0.35f else 0.30f)).border(1.dp, Th.c.glassLine, shape)

fun shareText(ctx: android.content.Context, text: String) {
    runCatching {
        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "Переслать")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Шаг модели в шапке — коротко: первые 3 слова. */
private fun shortStatus(s: String): String {
    val w = s.trimEnd('…', '.').split(' ').filter { it.isNotBlank() }
    return if (w.size <= 3) s else w.take(3).joinToString(" ") + "…"
}

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }; val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DateChip(ts: Long) {
    val now = System.currentTimeMillis()
    val t = when {
        sameDay(ts, now) -> "Сегодня"
        sameDay(ts, now - 86_400_000L) -> "Вчера"
        else -> SimpleDateFormat("d MMMM", Locale("ru")).format(Date(ts))
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(t, style = body(13, FontWeight.SemiBold, Color.White), modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .background(Th.c.accent.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun Hello(f: Friend) {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(84.dp, online = Online.online, f = f)
        Text(f.name, style = body(20, FontWeight.Bold))
        Text("Напиши или запиши голосовое — найду, напишу, напомню, или просто поболтаем.", style = body(14, color = Muted),
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Th.c.inBubble.copy(alpha = 0.8f)).padding(12.dp))
    }
}

// ———————————————— сообщение ————————————————

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MsgRow(m: Msg, showButtons: Boolean, chat: ChatStore, f: Friend) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var dx by remember { mutableFloatStateOf(0f) }
    val anim by animateFloatAsState(dx, label = "swipe")
    var menu by remember { mutableStateOf(false) }
    val shape = if (m.me) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp)
    val bg = if (m.me) Th.c.outBubble else Th.c.inBubble
    val ink = if (m.me) Th.c.outInk else Th.c.ink
    val meta = if (m.me) Th.c.outMeta else Th.c.meta
    val hasCards = m.items.isNotBlank() || m.files.isNotBlank() || m.photos.isNotBlank() || m.tgPhotos.isNotBlank()

    if (m.note) {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(md(m.text), style = body(13, color = Ink), lineHeight = 18.sp, modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(12.dp))
                .background(Th.c.inBubble.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 6.dp))
            val cards = m.photos.isNotBlank() || m.tgPhotos.isNotBlank() || m.items.isNotBlank() || m.files.isNotBlank()
            if (cards) Column(Modifier.widthIn(max = 330.dp).clip(RoundedCornerShape(18.dp)).background(Th.c.inBubble).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (m.photos.isNotBlank()) PhotoGrid(m.photos.split(',').mapNotNull { it.trim().toLongOrNull() })
                if (m.tgPhotos.isNotBlank()) TgPhotoGrid(m.tgPhotos.split('|').filter { it.isNotBlank() })
                if (m.items.isNotBlank()) ProductCards(Shop.fromJson(m.items))
                if (m.files.isNotBlank()) FileCards(Chat.filesOf(m))
            }
        }
        return
    }
    val picked = m.id in ChatSel.ids
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (picked) Teal.copy(alpha = 0.16f) else Color.Transparent)
        .combinedClickable(enabled = ChatSel.on, onClick = { ChatSel.toggle(m.id) })) {
    if (ChatSel.on) Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(22.dp).clip(CircleShape)
        .background(if (picked) Teal else Color.Transparent).border(2.dp, if (picked) Teal else Muted, CircleShape),
        contentAlignment = Alignment.Center) { if (picked) Text("✓", style = body(13, FontWeight.Bold, OnAccent)) }
    Column(Modifier.fillMaxWidth().padding(start = if (ChatSel.on) 34.dp else 0.dp),
        horizontalAlignment = if (m.me) Alignment.End else Alignment.Start) {
        Box(Modifier.offset { IntOffset(anim.roundToInt(), 0) }
            .pointerInput(m.id) {
                val limit = with(density) { 72.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragEnd = { if (dx > limit * 0.8f) chat.replyTo = m; dx = 0f },
                    onDragCancel = { dx = 0f },
                ) { _, d -> dx = (dx + d).coerceIn(0f, limit) }
            }) {
            Column(Modifier.widthIn(max = if (hasCards) 320.dp else 285.dp).clip(shape).background(bg)
                .combinedClickable(onClick = { if (ChatSel.on) ChatSel.toggle(m.id) }, onLongClick = { ChatSel.toggle(m.id) })
                .padding(horizontal = if (hasCards) 6.dp else 9.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (m.replyText.isNotBlank()) Quote(if (m.replyMe) "Алим" else f.name, m.replyText, m.me)
                if (m.voice.isNotBlank()) VoiceRow(m, meta)
                val text = m.text
                if (text.isNotBlank() && !(m.voice.isNotBlank() && m.me)) Text(md(text), style = body(15, color = if (m.failed) Danger else ink), lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = if (hasCards) 4.dp else 2.dp))
                if (m.voice.isNotBlank() && m.me && text.isNotBlank())
                    Text(text, style = body(13, color = meta), modifier = Modifier.padding(horizontal = 2.dp))
                if (m.att.isNotBlank()) Text("📎 ${m.att}", style = body(13, color = meta), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (m.photos.isNotBlank()) PhotoGrid(m.photos.split(',').mapNotNull { it.trim().toLongOrNull() })
                if (m.tgPhotos.isNotBlank()) TgPhotoGrid(m.tgPhotos.split('|').filter { it.isNotBlank() })
                if (m.items.isNotBlank()) ProductCards(Shop.fromJson(m.items))
                if (m.files.isNotBlank()) FileCards(Chat.filesOf(m))
                Row(Modifier.align(Alignment.End).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (m.meta.isNotBlank()) { Text(m.meta, style = body(11, color = meta), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)); HGap(8) }
                    Text(hhmm.format(Date(m.ts)), style = body(11, color = meta))
                    if (m.me) { HGap(4); Text(when (m.status) { "pending" -> "◷"; "sent" -> "✓"; else -> "✓✓" }, style = body(11, color = meta)) }
                }
            }
        }
        if (showButtons && m.buttons.isNotEmpty()) {
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.buttons.forEach { b ->
                    Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Th.c.inBubble.copy(alpha = 0.85f))
                        .border(1.dp, Teal.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).clickable { chat.send(b) }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center) { Text(b, style = body(14, FontWeight.SemiBold, Teal)) }
                }
            }
        }
    }
    }
}

@Composable
private fun Quote(who: String, text: String, mine: Boolean) {
    val c = if (mine) Th.c.outInk else Teal
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.10f))) {
        Box(Modifier.width(3.dp).height(40.dp).background(c))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(who, style = body(13, FontWeight.Bold, c))
            Text(text, style = body(13, color = if (mine) Th.c.outMeta else Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VoiceRow(m: Msg, meta: Color) {
    val wave = remember(m.voice) { VoiceMsg.wave(m.voice) }
    val playing = VoiceMsg.playing == m.voice
    val acc = if (m.me) Th.c.outMeta else Teal
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(2.dp)) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(acc).clickable(onClickLabel = if (playing) "Пауза" else "Слушать") { VoiceMsg.toggle(m.voice) },
            contentAlignment = Alignment.Center) {
            if (playing) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(4.dp, 14.dp).background(Th.c.inBubble)); Box(Modifier.size(4.dp, 14.dp).background(Th.c.inBubble))
            } else Icon(Icons.Filled.PlayArrow, null, tint = if (m.me) Th.c.outBubble else OnAccent, modifier = Modifier.size(24.dp))
        }
        HGap(10)
        Column {
            Canvas(Modifier.width(140.dp).height(26.dp)) {
                val n = wave.size; val w = size.width / n
                wave.forEachIndexed { i, v ->
                    val h = size.height * v
                    val done = playing && i.toFloat() / n <= VoiceMsg.progress
                    drawRoundRect(if (done || !playing) acc else acc.copy(alpha = 0.35f),
                        topLeft = Offset(i * w + w * 0.2f, (size.height - h) / 2), size = androidx.compose.ui.geometry.Size(w * 0.6f, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.3f))
                }
            }
            val s = m.dur / 1000
            Text("%d:%02d".format(s / 60, s % 60), style = body(12, color = meta))
        }
    }
}

@Composable
private fun Typing(core: Agent.Core) {
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(350); phase = (phase + 1) % 3 } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)).background(Th.c.inBubble).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i -> Box(Modifier.size(8.dp).clip(CircleShape).background(Muted.copy(alpha = if (i == phase) 1f else 0.35f))) }
        }
        var long by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(30_000); long = true }
        if (long) HGap(8)
        if (long) Text("стоп", style = body(13, FontWeight.SemiBold, Muted), modifier = Modifier.clip(RoundedCornerShape(14.dp))
            .background(Th.c.inBubble.copy(alpha = 0.8f)).clickable(onClickLabel = "Остановить") { core.stop() }.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
fun ConfirmCard(p: Agent.Pending, core: Agent.Core) {
    val text = p.text
    val act = LocalContext.current as? android.app.Activity
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Th.c.inBubble).border(1.dp, Amber.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Нужно твоё ОК", style = body(12, FontWeight.Bold, Amber))
        Text(text, style = body(16), lineHeight = 22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(if (p.secure) "Подтвердить отпечатком" else "Да, делай", {
                if (p.secure && act != null) Bio.auth(act, "Подтверди перевод") { ok -> core.answer(ok) } else core.answer(true)
            }, modifier = Modifier.weight(1f))
            BigButton("Нет", { core.answer(false) }, bg = Th.c.card, fg = Ink, border = Line, modifier = Modifier.weight(1f))
        }
    }
}

// ———————————————— ввод и голосовое ————————————————

@Composable
private fun Composer(haze: HazeState, chat: ChatStore, f: Friend) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var text by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var busyStt by remember { mutableStateOf(false) }
    val attach = rememberAttachPicker()
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hint = if (ok) "Теперь зажми микрофон и говори" else "Нужен доступ к микрофону"
    }
    val rec = VoiceMsg.recording

    fun finishVoice(cancel: Boolean) {
        locked = false; dragX = 0f; dragY = 0f
        scope.launch {
            val pcm = VoiceMsg.stop(cancel) ?: return@launch
            busyStt = true
            val (path, dur) = withContext(Dispatchers.IO) { VoiceMsg.saveWav(ctx, pcm, 16000) }
            val t = VoiceMsg.transcribe(ctx, pcm)
            busyStt = false
            if (t.isBlank()) hint = "Не разобрал голосовое — попробуй ещё раз или напиши"
            chat.send(t, path, dur)
        }
    }

    chat.replyTo?.let { r ->
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).glass(haze, RoundedCornerShape(18.dp)).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(34.dp).background(Teal)); HGap(8)
            Column(Modifier.weight(1f)) {
                Text("Ответ ${if (r.me) "себе" else f.name}", style = body(13, FontWeight.Bold, Teal))
                Text(r.text.ifBlank { "Голосовое" }, style = body(13, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.Close, "Убрать ответ", tint = Muted, modifier = Modifier.size(36.dp).clickable { chat.replyTo = null }.padding(8.dp))
        }
    }
    hint?.let { Text(it, style = body(13, color = Amber), modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) }
    if (busyStt) Text("Распознаю голосовое…", style = body(13, color = Muted), modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
    AttachChips()
    Spacer(Modifier.height(4.dp))

    Box(Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
        // поле — растёт вместе с текстом (до 6 строк)
        Row(Modifier.fillMaxWidth().padding(end = 54.dp).heightIn(min = 46.dp).glass(haze, RoundedCornerShape(23.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (rec) {
                var blink by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { while (true) { delay(500); blink = !blink } }
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5A5F).copy(alpha = if (blink) 1f else 0.3f))); HGap(10)
                val e = VoiceMsg.elapsed
                Text("%d:%02d,%d".format(e / 60000, e / 1000 % 60, e / 100 % 10), style = body(16, FontWeight.SemiBold)); HGap(14)
                if (locked) Text("Отмена", style = body(15, FontWeight.Bold, Teal), modifier = Modifier.clickable { finishVoice(true) }.padding(6.dp))
                else Text("‹ Влево — отмена", style = body(15, color = Muted), modifier = Modifier.offset { IntOffset(dragX.roundToInt() / 2, 0) })
            } else {
                BasicTextField(text, { text = it; hint = null }, Modifier.weight(1f), textStyle = body(16), cursorBrush = SolidColor(Teal), maxLines = 6,
                    decorationBox = { inner -> if (text.isEmpty()) Text("Сообщение", style = body(16, color = Muted)); inner() })
                HGap(8)
                Icon(painterClip(), "Прикрепить файл", tint = Muted, modifier = Modifier.size(24.dp).clickable { attach() })
            }
        }
        // кнопка: отправить / микрофон
        val canSend = text.isNotBlank() || Attach.pending.isNotEmpty()
        val big = rec && !locked
        val sc by animateFloatAsState(if (big) 1.7f else 1f, label = "mic")
        if (rec && !locked) {
            // замок над кнопкой
            Column(Modifier.align(Alignment.BottomEnd).offset(y = (-110).dp).width(46.dp).height(84.dp)
                .glass(haze, RoundedCornerShape(24.dp)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                Icon(Icons.Filled.Lock, "Запись без рук", tint = Ink, modifier = Modifier.size(20.dp).offset { IntOffset(0, (dragY / 3).roundToInt()) })
                Text("︿", style = body(14, color = Muted))
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).size(46.dp).scale(sc).clip(CircleShape).background(Teal)
            .then(
                if (canSend && !rec) Modifier.clickable(onClickLabel = "Отправить") { chat.send(text); text = "" }
                else if (locked) Modifier.clickable(onClickLabel = "Отправить голосовое") { finishVoice(false) }
                else Modifier.pointerInput(Unit) {
                    val cancelPx = with(density) { 110.dp.toPx() }
                    val lockPx = with(density) { 80.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micPerm.launch(Manifest.permission.RECORD_AUDIO); return@awaitEachGesture
                        }
                        hint = null
                        VoiceMsg.stopPlay()
                        if (!VoiceMsg.start()) { hint = "Микрофон занят"; return@awaitEachGesture }
                        dragX = 0f; dragY = 0f
                        var outcome = 0 // 0 отправить, 1 отмена, 2 закрепить
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            dragX = (ch.position.x - down.position.x).coerceAtMost(0f)
                            dragY = (ch.position.y - down.position.y).coerceAtMost(0f)
                            if (-dragX > cancelPx) { outcome = 1; break }
                            if (-dragY > lockPx) { outcome = 2; break }
                            if (!ch.pressed) break
                        }
                        when (outcome) {
                            1 -> finishVoice(true)
                            2 -> { locked = true; dragX = 0f; dragY = 0f }
                            else -> finishVoice(false)
                        }
                    }
                }
            ), contentAlignment = Alignment.Center) {
            if ((canSend && !rec) || locked) Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = OnAccent, modifier = Modifier.size(21.dp))
            else MicIcon(OnAccent, 22.dp)
        }
    }
}

@Composable
private fun painterClip() = androidx.compose.ui.graphics.vector.rememberVectorPainter(clipIcon)

private val clipIcon by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("clip", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.addPathNodes(
            "M21 12.5l-8.5 8.5a5 5 0 0 1-7-7L14 5.5a3.5 3.5 0 0 1 5 5L10.5 19a2 2 0 0 1-3-3L15 8.5"),
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round)
    }.build()
}

// ———————————————— фон и аватар ————————————————

@Composable
fun Wallpaper() {
    val ctx = LocalContext.current
    val p = Th.c
    val photo = Prefs.wallpaper == "photo"
    val ver = WallState.version
    val bmp = remember(photo, ver) { if (photo) loadBitmap(java.io.File(ctx.filesDir, "wallpaper.jpg").path, 1600) else null }
    if (bmp != null) {
        Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background((if (p.dark) Color.Black else Color.White).copy(alpha = Prefs.wallDim / 100f)))
    } else {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.linearGradient(p.wall, start = Offset(0f, 0f), end = Offset(size.width * 0.6f, size.height)))
            val r = java.util.Random(7)
            val step = 46.dp.toPx()
            var y = 0f
            while (y < size.height + step) {
                var x = 0f
                while (x < size.width + step) {
                    val k = r.nextInt(3)
                    drawCircle(p.dots[k], radius = (1.4f + r.nextFloat() * 1.6f).dp.toPx(),
                        center = Offset(x + r.nextFloat() * step * 0.7f, y + r.nextFloat() * step * 0.7f))
                    x += step
                }
                y += step
            }
        }
    }
}

object WallState { var version by mutableStateOf(0) }

/** Выделенные сообщения (долгое нажатие) — как в Telegram: копировать, переслать, удалить, ответить. */
object ChatSel {
    val ids = androidx.compose.runtime.mutableStateListOf<Long>()
    val on: Boolean get() = ids.isNotEmpty()
    fun toggle(id: Long) { if (id in ids) ids.remove(id) else ids.add(id) }
    fun picked(chat: ChatStore): List<Msg> = chat.msgs.filter { it.id in ids }.sortedBy { it.ts }
    fun asText(chat: ChatStore): String {
        val f = chat.friend
        val l = picked(chat)
        return if (l.size == 1) l[0].text else l.joinToString("\n") { "[${hhmm.format(Date(it.ts))}] ${if (it.me) "Алим" else f.name}: ${it.text}" }
    }
}

private fun vec(name: String, path: String, w: Float = 1.9f) =
    androidx.compose.ui.graphics.vector.ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = w,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }.build()

private val icCopy by lazy { vec("copy", "M9 9h10v12H9zM5 15V3h10") }
private val icReply by lazy { vec("reply", "M10 6L4 12l6 6M4 12h10a6 6 0 0 1 6 6v1") }
private val icForward by lazy { vec("fwd", "M14 6l6 6-6 6M20 12H10a6 6 0 0 0-6 6v1") }
private val icTrash by lazy { vec("trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6") }
private val icDown by lazy { vec("down", "M6 9l6 6 6-6", 2.2f) }

@Composable
fun Avatar(size: androidx.compose.ui.unit.Dp, online: Boolean = false, f: Friend = Friends.main) {
    val ver = WallState.version
    val path = f.avatar
    val bmp = remember(path, ver) { if (path.isNotBlank()) loadBitmap(path, 400) else null }
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(f.name.take(1).uppercase(), style = body((size.value * 0.42f).toInt(), FontWeight.Bold, OnAccent))
        }
        if (online) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.28f).clip(CircleShape).background(Th.c.card).padding(2.dp)
            .clip(CircleShape).background(Th.c.online))
    }
}

fun loadBitmap(path: String, max: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, o)
    var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > max) s *= 2
    android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.let {
        it.asImageBitmap()
    }
}.getOrNull()

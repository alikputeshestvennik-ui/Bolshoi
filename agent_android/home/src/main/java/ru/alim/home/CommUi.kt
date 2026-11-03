@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package ru.alim.home

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ——— общее ———

/** Просим разрешения один раз; пока не дали — понятная плашка с кнопкой. */
@Composable
private fun NeedPerms(p: Pal, perms: Array<String>, why: String, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(Comm.has(ctx, *perms)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = Comm.has(ctx, *perms) }
    LaunchedEffect(Unit) { if (!ok) ask.launch(perms) }
    if (ok) content() else Column(Modifier.fillMaxWidth().padding(top = 24.dp).background(p.card).border(1.dp, p.ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(why, style = body(15, c = p.ink))
        Text("Разрешить", style = body(14, FontWeight.Bold, p.bg), modifier = Modifier.background(p.ink).clickable { ask.launch(perms) }.padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

/** Перечитывать при изменениях системного журнала (новое СМС, звонок). */
@Composable
private fun watch(uri: android.net.Uri): Int {
    val ctx = LocalContext.current
    var n by remember { mutableIntStateOf(0) }
    DisposableEffect(uri) {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { n++ } }
        runCatching { ctx.contentResolver.registerContentObserver(uri, true, o) }
        onDispose { runCatching { ctx.contentResolver.unregisterContentObserver(o) } }
    }
    return n
}

private fun first(s: String) = (s.firstOrNull { it.isLetterOrDigit() } ?: '+').uppercaseChar().let { if (it.isDigit()) "+" else it.toString() }

private fun dayLabel(ts: Long): String {
    val c = Calendar.getInstance(); val d = Calendar.getInstance().apply { timeInMillis = ts }
    val same = { a: Calendar, b: Calendar -> a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) }
    if (same(c, d)) return "СЕГОДНЯ"
    c.add(Calendar.DAY_OF_YEAR, -1); if (same(c, d)) return "ВЧЕРА"
    return SimpleDateFormat("d MMMM", Locale("ru")).format(Date(ts)).uppercase()
}
private fun shortTime(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        dayLabel(ts) == "СЕГОДНЯ" -> SimpleDateFormat("HH:mm", Locale("ru")).format(Date(ts))
        dayLabel(ts) == "ВЧЕРА" -> "вчера"
        now - ts < 6 * 86_400_000L -> SimpleDateFormat("EE", Locale("ru")).format(Date(ts))
        else -> SimpleDateFormat("d.MM", Locale("ru")).format(Date(ts))
    }
}

@Composable
private fun Tabs(p: Pal, titles: List<String>, sel: Int, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, p.ink)) {
        titles.forEachIndexed { i, t ->
            if (i > 0) Box(Modifier.width(1.dp).height(44.dp).background(p.ink))
            Box(Modifier.weight(1f).height(44.dp).background(if (i == sel) p.ink else p.card).clickable { pick(i) }, contentAlignment = Alignment.Center) {
                Text(t, style = body(14, if (i == sel) FontWeight.Bold else FontWeight.SemiBold, if (i == sel) p.bg else p.ink))
            }
        }
    }
}

@Composable
private fun Chip(p: Pal, t: String, on: Boolean, click: () -> Unit) {
    Box(Modifier.height(34.dp).then(if (on) Modifier.background(p.ink) else Modifier.border(1.dp, p.line)).clickable(onClick = click).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(t, style = body(13, if (on) FontWeight.Bold else FontWeight.SemiBold, if (on) p.bg else p.ink))
    }
}

@Composable
private fun Letter(p: Pal, s: String, size: Int) {
    Box(Modifier.size(size.dp).background(p.tile), contentAlignment = Alignment.Center) {
        Text(s, style = TextStyle(fontFamily = Fonts.display, fontSize = (size * 0.36f).sp, fontWeight = FontWeight.SemiBold, color = p.onTile))
    }
}

@Composable
private fun TenHint(p: Pal, text: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(p.card).border(1.dp, p.ink).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TenMark(p, 28.dp, p.ink, p.card)
        Text(text, style = body(13, c = p.ink).copy(lineHeight = 18.sp), modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = body(13, FontWeight.Bold, p.bg), modifier = Modifier.background(p.ink).clickable(onClick = onAction).padding(horizontal = 10.dp, vertical = 9.dp))
    }
}

// ——— ТЕЛЕФОН ———

@Composable
fun PhoneScreen(p: Pal, close: () -> Unit, openThread: (String, String) -> Unit) {
    val ctx = LocalContext.current
    BackHandler { close() }
    var tab by remember { mutableIntStateOf(0) }
    val tick = watch(CallLog.Calls.CONTENT_URI)
    var contacts by remember { mutableStateOf(emptyList<Comm.Contact>()) }
    var calls by remember { mutableStateOf(emptyList<Comm.Call>()) }
    var granted by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick, granted) {
        contacts = withContext(Dispatchers.IO) { Comm.contacts(ctx) }
        calls = withContext(Dispatchers.IO) { Comm.calls(ctx) }
    }
    val names = remember(contacts) { Comm.names(contacts) }
    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", style = body(26, FontWeight.SemiBold, p.ink), modifier = Modifier.clickable(onClickLabel = "Назад") { close() }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp))
            Text("Телефон", style = TextStyle(fontFamily = Fonts.display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
        }
        Spacer(Modifier.height(12.dp))
        Tabs(p, listOf("Набор", "Журнал", "Контакты"), tab) { tab = it }
        NeedPerms(p, Comm.PHONE_PERMS, "Чтобы показать журнал и контакты и звонить в одно касание, Тень · Дом нужен доступ к звонкам и контактам.") {
            LaunchedEffect(Unit) { granted++ }
            when (tab) {
                0 -> Dialer(p, contacts)
                1 -> CallList(p, calls, names, openThread)
                else -> ContactList(p, contacts, openThread)
            }
        }
    }
}

@Composable
private fun Dialer(p: Pal, contacts: List<Comm.Contact>) {
    val ctx = LocalContext.current
    var num by remember { mutableStateOf("") }
    val digits = num.filter { it.isDigit() }
    val like = remember(digits, contacts) { if (digits.length < 3) emptyList() else contacts.filter { it.number.filter { c -> c.isDigit() }.contains(digits) }.take(2) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (like.isNotEmpty()) Text("ПОХОЖИЕ", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp))
            like.forEach { c ->
                Row(Modifier.fillMaxWidth().height(56.dp).background(p.card).border(1.dp, p.line).clickable { num = c.number }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Letter(p, first(c.name), 40)
                    Column { Text(c.name, style = body(15, FontWeight.Bold, p.ink), maxLines = 1); Text(c.number, style = body(12, c = p.muted)) }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(num.ifEmpty { " " }, style = TextStyle(fontFamily = Fonts.display, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = p.ink, textAlign = TextAlign.Center), maxLines = 1)
            Spacer(Modifier.height(16.dp))
        }
        val keys = listOf("1" to "", "2" to "АБВГ", "3" to "ДЕЖЗ", "4" to "ИЙКЛ", "5" to "МНОП", "6" to "РСТУ", "7" to "ФХЦЧ", "8" to "ШЩЪЫ", "9" to "ЬЭЮЯ", "*" to "", "0" to "+", "#" to "")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (d, l) ->
                    Column(Modifier.weight(1f).height(64.dp).background(p.card).border(1.dp, p.line)
                        .combinedClickable(onClickLabel = d, onLongClick = { if (d == "0") num += "+" }) { if (num.length < 20) num += d },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(d, style = TextStyle(fontFamily = Fonts.display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
                        Text(l, style = body(10, FontWeight.Bold, p.muted).copy(letterSpacing = 1.4.sp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(64.dp).border(1.dp, p.line).clickable(onClickLabel = "Спросить Тень, чей номер") {
                if (digits.length >= 5) { val n = num; Thread { Apps.ask(ctx, "Чей это номер: $n? Поищи в переписке, памяти и интернете.") }.start(); Toast.makeText(ctx, "Спросил Тень — ответ в её чате", Toast.LENGTH_SHORT).show() }
            }, contentAlignment = Alignment.Center) { TenMark(p, 32.dp, p.ink, p.bg) }
            Row(Modifier.width(140.dp).height(64.dp).background(p.ink).clickable(onClickLabel = "Позвонить") { if (digits.isNotEmpty()) Comm.call(ctx, num) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text("Вызов", style = body(17, FontWeight.Bold, p.bg))
            }
            Box(Modifier.size(64.dp).border(1.dp, p.line).combinedClickable(onClickLabel = "Стереть", onLongClick = { num = "" }) { num = num.dropLast(1) }, contentAlignment = Alignment.Center) {
                Text("⌫", style = body(24, c = p.ink))
            }
        }
    }
}

@Composable
private fun CallList(p: Pal, calls: List<Comm.Call>, names: Map<String, String>, openThread: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var missedOnly by remember { mutableStateOf(false) }
    val list = if (missedOnly) calls.filter { it.missed } else calls
    // пропущенный за последние 2 часа, на который ещё не перезванивали — Тень предлагает ответить СМС
    val pending = calls.firstOrNull()?.takeIf { it.missed && System.currentTimeMillis() - it.date < 2 * 3600_000L }
    val nm = { c: Comm.Call -> c.name.ifBlank { names[Comm.key(c.number)] ?: c.number } }
    Column(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pending != null && Comm.isPhone(pending.number)) TenHint(p, "Пропущенный от «${nm(pending)}» в ${SimpleDateFormat("HH:mm", Locale("ru")).format(Date(pending.date))}. Ответить СМС «Перезвоню через 10 минут»?", "Да") {
            val err = Comm.send(ctx, pending.number, "Перезвоню через 10 минут")
            Toast.makeText(ctx, err ?: "Отправил", Toast.LENGTH_SHORT).show()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(p, "Все", !missedOnly) { missedOnly = false }
            Chip(p, "Пропущенные · ${calls.count { it.missed && System.currentTimeMillis() - it.date < 86_400_000L }}", missedOnly) { missedOnly = true }
        }
        LazyColumn(Modifier.weight(1f)) {
            var lastDay = ""
            list.forEach { c ->
                val d = dayLabel(c.date)
                if (d != lastDay) { lastDay = d; item(key = "d$d${c.date}") {
                    Text(d, style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                } }
                item(key = "c${c.date}${c.number}") {
                    Row(Modifier.fillMaxWidth().height(64.dp).combinedClickable(onClickLabel = "Позвонить", onLongClick = { openThread(c.number, nm(c)) }) { Comm.call(ctx, c.number) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Letter(p, first(nm(c)), 44)
                        Column(Modifier.weight(1f)) {
                            Text(nm(c), style = body(15, if (c.missed) FontWeight.Bold else FontWeight.SemiBold, p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.kind + if (c.dur > 0) " · ${(c.dur + 59) / 60} мин" else "", style = body(12, c = p.muted))
                        }
                        Text(SimpleDateFormat("HH:mm", Locale("ru")).format(Date(c.date)), style = body(12, c = p.muted))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.soft))
                }
            }
            item { Text("Пропущенные — жирным. Тап — позвонить, долгое — сообщения.", style = body(12, c = p.muted), modifier = Modifier.padding(vertical = 16.dp)) }
        }
    }
}

@Composable
private fun ContactList(p: Pal, contacts: List<Comm.Contact>, openThread: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val list = remember(contacts, q) { if (q.isBlank()) contacts else contacts.filter { it.name.contains(q, true) || it.number.filter { c -> c.isDigit() }.contains(q.filter { c -> c.isDigit() }.ifBlank { "#" }) } }
    Column(Modifier.fillMaxSize().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp).background(p.card).border(1.dp, p.ink).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(q, { q = it }, singleLine = true, textStyle = body(15, c = p.ink), cursorBrush = SolidColor(p.ink), modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Найти контакт", style = body(15, c = p.muted)); inner() } })
        }
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.name + it.number }) { c ->
                Row(Modifier.fillMaxWidth().height(60.dp).combinedClickable(onClickLabel = "Позвонить", onLongClick = { openThread(c.number, c.name) }) { Comm.call(ctx, c.number) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Letter(p, first(c.name), 40)
                    Column(Modifier.weight(1f)) { Text(c.name, style = body(15, FontWeight.SemiBold, p.ink), maxLines = 1); Text(c.number, style = body(12, c = p.muted)) }
                    Text("✉", style = body(18, c = p.ink), modifier = Modifier.clickable(onClickLabel = "Написать") { openThread(c.number, c.name) }.padding(10.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.soft))
            }
        }
    }
}

// ——— СООБЩЕНИЯ ———

@Composable
fun MessagesScreen(p: Pal, close: () -> Unit, openThread: (String, String) -> Unit) {
    val ctx = LocalContext.current
    BackHandler { close() }
    val tick = watch(Telephony.Sms.CONTENT_URI)
    var threads by remember { mutableStateOf(emptyList<Comm.Thread>()) }
    var names by remember { mutableStateOf(emptyMap<String, String>()) }
    var granted by remember { mutableIntStateOf(0) }
    var filter by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(tick, granted) {
        threads = withContext(Dispatchers.IO) { Comm.threads(ctx) }
        names = withContext(Dispatchers.IO) { Comm.names(Comm.contacts(ctx)) }
    }
    val nm = { t: Comm.Thread -> names[Comm.key(t.addr)] ?: t.addr }
    val day = System.currentTimeMillis() - 86_400_000L
    val shown = threads.filter { t ->
        val f = when (filter) { 0 -> !Comm.isService(t.addr) && !Comm.isCode(t.last.body); 1 -> Comm.isCode(t.last.body) && t.last.date > day; else -> Comm.isService(t.addr) && !Comm.isCode(t.last.body) }
        f && (q.isBlank() || nm(t).contains(q, true) || t.last.body.contains(q, true))
    }
    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", style = body(26, FontWeight.SemiBold, p.ink), modifier = Modifier.clickable(onClickLabel = "Назад") { close() }.padding(end = 12.dp, top = 4.dp, bottom = 4.dp))
            Text("Сообщения", style = TextStyle(fontFamily = Fonts.display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = p.ink), modifier = Modifier.weight(1f))
        }
        NeedPerms(p, Comm.SMS_PERMS, "Чтобы показывать переписки и отвечать отсюда, Тень · Дом нужен доступ к СМС и контактам.") {
            LaunchedEffect(Unit) { granted++ }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).background(p.card).border(1.dp, p.ink).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = body(15, c = p.ink), cursorBrush = SolidColor(p.ink), modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner -> Box { if (q.isEmpty()) Text("Найти в сообщениях", style = body(15, c = p.muted)); inner() } })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(p, "Люди", filter == 0) { filter = 0 }
                    Chip(p, "Коды · ${threads.count { Comm.isCode(it.last.body) && it.last.date > day }}", filter == 1) { filter = 1 }
                    Chip(p, "Банки и сервисы", filter == 2) { filter = 2 }
                }
                if (filter == 1) TenHint(p, "Здесь только коды за последние сутки. Старые не удаляются — просто не мешают.", null) {}
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().height(76.dp).clickable { openThread(t.addr, nm(t)) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Letter(p, first(nm(t)), 48)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row { Text(nm(t), style = body(15, if (t.unread > 0) FontWeight.Bold else FontWeight.SemiBold, p.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(shortTime(t.last.date), style = body(12, c = p.muted)) }
                                Text((if (t.last.me) "Вы: " else "") + t.last.body.replace('\n', ' '), style = body(13, c = p.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (t.unread > 0) Box(Modifier.height(22.dp).widthIn(min = 22.dp).background(p.ink).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                Text("${t.unread}", style = body(12, FontWeight.Bold, p.bg))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.soft))
                    }
                    if (shown.isEmpty()) item { Text("Пусто", style = body(14, c = p.muted), modifier = Modifier.padding(vertical = 24.dp)) }
                }
            }
        }
    }
}

@Composable
fun ThreadScreen(p: Pal, addr: String, name: String, close: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { close() }
    val tick = watch(Telephony.Sms.CONTENT_URI)
    var msgs by remember { mutableStateOf(emptyList<Comm.Sms>()) }
    var text by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(false) }
    var ho by remember { mutableStateOf(Comm.HandoffState(false, "")) }
    var hoTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(tick, addr) {
        msgs = withContext(Dispatchers.IO) {
            val th = Comm.threads(ctx).firstOrNull { Comm.key(it.addr) == Comm.key(addr) || it.addr == addr }
            if (th != null) Comm.messages(ctx, th.id) else emptyList()
        }
        if (msgs.isNotEmpty()) list.scrollToItem(msgs.size - 1)
    }
    LaunchedEffect(addr, hoTick, tick) { ho = withContext(Dispatchers.IO) { Comm.handoffState(ctx, addr) } }
    fun send(t: String) {
        val s = t.trim(); if (s.isEmpty()) return
        scope.launch {
            val err = withContext(Dispatchers.IO) { Comm.send(ctx, addr, s) }
            if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show() else text = ""
        }
    }
    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("←", style = body(26, FontWeight.SemiBold, p.ink), modifier = Modifier.clickable(onClickLabel = "Назад") { close() }.padding(8.dp))
                Letter(p, first(name), 40)
                Column(Modifier.weight(1f)) { Text(name, style = body(16, FontWeight.Bold, p.ink), maxLines = 1); if (name != addr) Text(addr, style = body(12, c = p.muted)) }
                if (Comm.isPhone(addr)) Box(Modifier.size(44.dp).border(1.dp, p.line).clickable(onClickLabel = "Позвонить") { Comm.call(ctx, addr) }, contentAlignment = Alignment.Center) { Text("☏", style = body(20, c = p.ink)) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            if (ho.active) Row(Modifier.fillMaxWidth().background(p.ink).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TenMark(p, 22.dp, p.bg, p.ink)
                Text("Переписку ведёт Тень · ${ho.state}", style = body(13, FontWeight.SemiBold, p.bg), modifier = Modifier.weight(1f), maxLines = 2)
                Text("Забрать", style = body(13, FontWeight.Bold, p.ink), modifier = Modifier.background(p.bg).clickable {
                    scope.launch { val m = withContext(Dispatchers.IO) { Comm.takeBack(ctx, addr) }; if (m.isNotBlank()) Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show(); hoTick++ }
                }.padding(horizontal = 10.dp, vertical = 7.dp))
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Spacer(Modifier.height(8.dp)) }
                var lastDay = ""
                msgs.forEach { m ->
                    val d = dayLabel(m.date)
                    if (d != lastDay) { lastDay = d; item(key = "d${m.id}") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text(d, style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp)) } } }
                    item(key = m.id) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (m.me) Alignment.CenterEnd else Alignment.CenterStart) {
                            Column(Modifier.fillMaxWidth(0.8f).wrapContentWidth(if (m.me) Alignment.End else Alignment.Start)
                                .then(if (m.me) Modifier.background(p.ink) else Modifier.background(p.card).border(1.dp, p.line)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                                Text(m.body, style = body(15, c = if (m.me) p.bg else p.ink).copy(lineHeight = 21.sp))
                                Text(SimpleDateFormat("HH:mm", Locale("ru")).format(Date(m.date)) + if (m.failed) " · не отправлено" else "",
                                    style = body(11, c = if (m.me) p.muted else p.muted), modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            // быстрые ответы и «Передать Тени»
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Comm.isPhone(addr) && !ho.active) Row(Modifier.height(38.dp).background(p.ink).clickable(onClickLabel = "Передать Тени") { sheet = true }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TenMark(p, 18.dp, p.bg, p.ink)
                    Text("Передать Тени", style = body(13, FontWeight.Bold, p.bg))
                }
                listOf("Перезвоню через 10 минут", "Еду, всё хорошо", "Ок").forEach { r ->
                    Box(Modifier.height(38.dp).border(1.dp, p.line).clickable { send(r) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text(r, style = body(13, FontWeight.SemiBold, p.ink)) }
                }
            }
            Row(Modifier.fillMaxWidth().background(p.card).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 48.dp).border(1.dp, p.ink).padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(text, { text = it }, textStyle = body(15, c = p.ink), cursorBrush = SolidColor(p.ink), maxLines = 5, modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner -> Box { if (text.isEmpty()) Text("Сообщение", style = body(15, c = p.muted)); inner() } })
                }
                Box(Modifier.size(48.dp).background(p.ink).clickable(onClickLabel = "Отправить") { send(text) }, contentAlignment = Alignment.Center) { Text("→", style = body(22, FontWeight.Bold, p.bg)) }
            }
        }
        // окно «Передать Тени» выезжает снизу поверх переписки
        AnimatedVisibility(sheet, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            Box(Modifier.fillMaxSize().background(p.ink.copy(alpha = 0.38f)).pointerInput(Unit) { detectTapGestures { sheet = false } })
        }
        AnimatedVisibility(sheet, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(180))) {
            HandoffSheet(p, name, close = { sheet = false }) { goal, notes, confirm, until, manual ->
                scope.launch {
                    val m = withContext(Dispatchers.IO) { Comm.handoff(ctx, name, addr, msgs, goal, notes, confirm, until, manual) }
                    Toast.makeText(ctx, m, Toast.LENGTH_LONG).show(); sheet = false; hoTick++
                }
            }
        }
    }
}

@Composable
private fun HandoffSheet(p: Pal, name: String, close: () -> Unit, go: (String, String, Boolean, Long, Boolean) -> Unit) {
    var goal by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var finish by remember { mutableIntStateOf(0) }   // 0 — цель достигнута, 1 — через час, 2 — когда скажу
    Column(Modifier.fillMaxWidth().background(p.bg).pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.ink))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TenMark(p, 36.dp, p.ink, p.bg)
            Column(Modifier.weight(1f)) {
                Text("Передать Тени", style = TextStyle(fontFamily = Fonts.display, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = p.ink))
                Text("Тень прочтёт всю переписку с «$name» и продолжит за тебя", style = body(12, c = p.muted))
            }
            Text("×", style = body(24, c = p.muted), modifier = Modifier.clickable(onClickLabel = "Закрыть") { close() }.padding(6.dp))
        }
        Text("ЧЕГО ДОБИТЬСЯ", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp))
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card).border(1.dp, p.ink).padding(12.dp)) {
            BasicTextField(goal, { goal = it }, textStyle = body(15, c = p.ink), cursorBrush = SolidColor(p.ink), modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner -> Box { if (goal.isEmpty()) Text("например: успокоить, сказать, что буду к 20:00", style = body(15, c = p.muted)); inner() } })
        }
        Text("УКАЗАНИЯ ТЕНИ", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp))
        Box(Modifier.fillMaxWidth().heightIn(min = 84.dp).background(p.card).border(1.dp, p.ink).padding(12.dp)) {
            BasicTextField(notes, { notes = it }, textStyle = body(14, c = p.ink), cursorBrush = SolidColor(p.ink), modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner -> Box { if (notes.isEmpty()) Text("как писать, о чём молчать, что можно обещать", style = body(14, c = p.muted)); inner() } })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("коротко, без смайлов", "ничего не обещай", "срочное — сразу мне", "спроси, что купить").forEach { t ->
                Box(Modifier.height(32.dp).border(1.dp, p.line).clickable { notes = (notes.trim().trimEnd('.') + (if (notes.isBlank()) "" else ". ") + t.replaceFirstChar { it.uppercase() }).trim() }
                    .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text("+ $t", style = body(12, FontWeight.SemiBold, p.ink)) }
            }
        }
        Text("КАК ОТПРАВЛЯТЬ", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp))
        Tabs(p, listOf("Сама, сразу", "Показывать мне"), if (confirm) 1 else 0) { confirm = it == 1 }
        Text("КОГДА ЗАКОНЧИТЬ", style = body(11, FontWeight.Bold, p.muted).copy(letterSpacing = 1.2.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(p, "Цель достигнута", finish == 0) { finish = 0 }
            Chip(p, "Через час", finish == 1) { finish = 1 }
            Chip(p, "Когда скажу", finish == 2) { finish = 2 }
        }
        Box(Modifier.fillMaxWidth().height(56.dp).background(p.ink).clickable {
            go(goal.trim(), notes.trim(), confirm, if (finish == 1) System.currentTimeMillis() + 3600_000L else 0L, finish == 2)
        }, contentAlignment = Alignment.Center) { Text("Передать", style = body(16, FontWeight.Bold, p.bg)) }
        Text("Поручение появится в Тени · «Активные». Забрать — там же или здесь кнопкой «Забрать».", style = body(12, c = p.muted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

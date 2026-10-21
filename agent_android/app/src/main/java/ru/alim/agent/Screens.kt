package ru.alim.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val hm = SimpleDateFormat("HH:mm", Locale("ru"))

// ————————————————— Главный —————————————————

@Composable
fun HomeScreen(voice: Voice, go: (Scr) -> Unit) {
    val ctx = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val listen: () -> Unit = {
        hint = null
        voice.listen(onText = { Agent.start(it) }, onError = { hint = it })
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else hint = "Нужен доступ к микрофону"
    }
    val onMic: () -> Unit = {
        if (voice.listening) voice.stopListen()
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    val send: () -> Unit = { if (input.isNotBlank() || Attach.pending.isNotEmpty()) { Agent.start(input); input = ""; typing = false } }
    val attach = rememberAttachPicker()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Агент", style = TextStyle(fontFamily = Fonts.display, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink))
            Spacer(Modifier.weight(1f))
            IconTile(Icons.Filled.Settings, "Подключения", { go(Scr.Settings) })
        }

        if (!typing) Column(Modifier.fillMaxWidth().weight(1f).padding(top = 14.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
            Guardian(Idle, if (voice.listening) 3 else 0, 290.dp, voice.level, voice.speaking,
                Modifier.clickable(onClickLabel = "Говорить", onClick = onMic))
            Text(
                when {
                    voice.listening -> voice.partial.ifBlank { "Слушаю…" }
                    else -> "Скажи, что сделать"
                },
                style = body(20, FontWeight.SemiBold), textAlign = TextAlign.Center,
            )
            Text(hint ?: if (voice.listening) "Говори, я записываю" else "Нажми на микрофон или на Стража",
                style = body(14, color = if (hint != null) Amber else Muted), textAlign = TextAlign.Center)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Погода на завтра", "Последние сообщения", "Сколько потратил?", "Будильник на 7:00").forEach { s ->
                    Chip(s) { Agent.start(s) }
                }
            }
        }

        Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("Сводка", Scr.Digest, Amber), Triple("Память", Scr.Memory, Violet), Triple("Автоответ", Scr.Rules, Teal)).forEach { (t, s, c) ->
                Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(Card).clickable { go(s) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Dot(c); HGap(8); Text(t, style = body(13, FontWeight.SemiBold), maxLines = 1, softWrap = false)
                }
            }
        }

        if (typing) Spacer(Modifier.weight(1f))

        if (typing) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f).height(58.dp), placeholder = { Text("Что сделать?", style = body(15, color = Muted)) },
                    textStyle = body(15), singleLine = true, shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                    colors = fieldColors())
                HGap(8)
                IconTile(Icons.AutoMirrored.Filled.Send, "Отправить", send, 52.dp)
            }
        }

        AttachChips()
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.Add, "Прикрепить файл", attach, 52.dp)
            HGap(12)
            IconTile(Icons.Filled.Edit, "Ввести текстом", { typing = !typing }, 52.dp)
            HGap(16)
            Box(Modifier.size(76.dp).clip(RoundedCornerShape(38.dp)).background(if (voice.listening) Danger else Teal)
                .clickable(onClickLabel = if (voice.listening) "Остановить запись" else "Говорить", onClick = onMic),
                contentAlignment = Alignment.Center) {
                if (voice.listening) Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(Bg))
                else MicIcon(Bg, 34.dp)
            }
            HGap(16)
            IconTile(Icons.Filled.DateRange, "История", { go(Scr.History) }, 52.dp)
        }
    }
}

@Composable
fun TaskRow(r: TaskRec, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).clickable(onClickLabel = "Открыть", onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (r.ok) AccentSoft else DangerBg),
            contentAlignment = Alignment.Center) {
            if (r.ok) Icon(Icons.Filled.Check, null, tint = Teal, modifier = Modifier.size(18.dp))
            else Text("!", style = body(16, FontWeight.Bold, Danger))
        }
        HGap(12)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = body(15, FontWeight.SemiBold, if (r.background) Muted else Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${hm.format(Date(r.ts))} · ${r.model}", style = body(12, color = Muted))
        }
        HGap(8)
        Text(rub(r.cost), style = body(14, FontWeight.SemiBold, if (r.cost > 0) Ink else Muted))
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Teal, unfocusedBorderColor = Line, focusedContainerColor = Card, unfocusedContainerColor = Card,
    cursorColor = Teal, focusedTextColor = Ink, unfocusedTextColor = Ink,
)

// ————————————————— Задача —————————————————

@Composable
fun TaskScreen(voice: Voice, back: () -> Unit) {
    val attach = rememberAttachPicker()
    var reply by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val phase = Agent.phase
    val running = phase == Agent.Phase.WORKING
    val pal = when (phase) {
        Agent.Phase.CONFIRM -> Ask
        Agent.Phase.WORKING -> Work
        else -> if (Agent.failed) Ask else Idle
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) {
        while (Agent.busy) { now = System.currentTimeMillis(); delay(500) }
        now = System.currentTimeMillis()
    }
    val secs = (((if (Agent.endedAt > 0) Agent.endedAt else now) - Agent.startedAt) / 1000).coerceAtLeast(0)

    Screen(Agent.task, back) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Guardian(pal, when (phase) { Agent.Phase.WORKING -> 1; Agent.Phase.CONFIRM -> 2; else -> 0 }, 112.dp, voice.level, voice.speaking)
            HGap(14)
            Column {
                Label(when (phase) {
                    Agent.Phase.WORKING -> "Думаю…"
                    Agent.Phase.CONFIRM -> "Нужно твоё ОК"
                    else -> if (Agent.failed) "Не вышло" else "Готово"
                }, pal.accent)
                Gap(4)
                Text("%d:%02d".format(secs / 60, secs % 60), style = TextStyle(fontFamily = Fonts.display, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                Text("время", style = body(12, color = Muted))
            }
        }

        Label("Шаги")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(Agent.steps) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (s.done) Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = Teal, modifier = Modifier.size(14.dp))
                    } else Box(Modifier.size(26.dp).border(2.dp, Violet, RoundedCornerShape(13.dp)))
                    HGap(12)
                    Column(Modifier.weight(1f)) {
                        Text(s.text, style = body(15, if (s.done) FontWeight.Normal else FontWeight.SemiBold))
                        if (s.detail.isNotBlank()) Text(s.detail, style = body(12, color = Muted), maxLines = 5)
                    }
                }
            }
            Agent.pending?.let { p ->
                item {
                    CardBox(border = WarnLine, bg = WarnBg) {
                        Text(p.text, style = body(16, FontWeight.SemiBold), lineHeight = 22.sp)
                        Gap(6)
                        BigButton("Да, делай", { Agent.answer(true) })
                        BigButton("Нет", { Agent.answer(false) }, bg = Card, fg = Ink, border = Line)
                    }
                }
            }
            if (Agent.photos.isNotEmpty()) item { PhotoGrid(Agent.photos.toList()) }
            if (Agent.products.isNotEmpty()) item { ProductCards(Agent.products.toList()) }
            if (Agent.files.isNotEmpty()) item { FileCards(Agent.files.toList()) }
            if (phase == Agent.Phase.DONE) item {
                CardBox(bg = if (Agent.failed) DangerBg else AccentSoft) {
                    Text(md(Agent.result), style = body(16, color = if (Agent.failed) Danger else Ink), lineHeight = 22.sp)
                }
            }
        }

        if (Agent.canFollowUp) {
            if (voice.listening) Text(voice.partial.ifBlank { "Слушаю…" }, style = body(15, FontWeight.SemiBold, Teal))
            hint?.let { Text(it, style = body(13, color = Amber)) }
            AttachChips()
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Filled.Add, "Прикрепить файл", attach, 52.dp)
                HGap(8)
                OutlinedTextField(reply, { reply = it }, Modifier.weight(1f).height(58.dp), placeholder = { Text("Ответить агенту…", style = body(15, color = Muted)) },
                    textStyle = body(15), singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (reply.isNotBlank()) { Agent.followUp(reply); reply = "" } }))
                HGap(8)
                if (reply.isNotBlank() || Attach.pending.isNotEmpty()) IconTile(Icons.AutoMirrored.Filled.Send, "Отправить", { Agent.followUp(reply); reply = "" }, 52.dp)
                else Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(if (voice.listening) Danger else Teal)
                    .clickable(onClickLabel = "Ответить голосом") {
                        hint = null
                        if (voice.listening) voice.stopListen()
                        else voice.listen(onText = { Agent.followUp(it) }, onError = { hint = it })
                    }, contentAlignment = Alignment.Center) { MicIcon(Bg, 26.dp) }
            }
        }
        if (Agent.busy) BigButton("Остановить", { Agent.stop() }, bg = DangerBg, fg = Danger, border = DangerLine)
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = body(12, color = Muted))
        Text(value, style = body(17, FontWeight.Bold))
    }
}

// ————————————————— История и расходы —————————————————

@Composable
fun HistoryScreen(back: () -> Unit, open: (TaskRec) -> Unit) {
    val ctx = LocalContext.current
    var period by remember { mutableStateOf(0) } // 0 день, 1 неделя, 2 месяц
    var showBg by remember { mutableStateOf(false) }
    val jv = Journal.version.intValue
    val from = when (period) { 0 -> Journal.startOfDay(); 1 -> Journal.startOfWeek(); else -> Journal.startOfMonth() }
    val recs = remember(jv, period) { Journal.since(ctx, from) }
    val total = recs.sumOf { it.cost }
    val list = recs.filter { showBg || !it.background }.reversed()
    val df = SimpleDateFormat("d MMMM", Locale("ru"))

    Screen("История", back) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(4.dp)) {
                    listOf("Сегодня", "Неделя", "Месяц").forEachIndexed { i, t ->
                        Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                            .background(if (period == i) Line else Color.Transparent).clickable { period = i },
                            contentAlignment = Alignment.Center) {
                            Text(t, style = body(14, FontWeight.SemiBold, if (period == i) Ink else Muted))
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                    Text(rub(total), style = TextStyle(fontFamily = Fonts.display, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                    HGap(10)
                    Text("потрачено · ${recs.count { !it.background }} запросов", style = body(13, color = Muted), modifier = Modifier.padding(bottom = 6.dp))
                }
                if (period == 2) {
                    val limit = Prefs.num(Prefs.monthLimit)
                    if (limit > 0) {
                        val f = (total / limit).toFloat().coerceIn(0f, 1f)
                        Gap(8)
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Track)) {
                            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (f > 0.9f) Amber else Teal))
                        }
                        Gap(4)
                        Text("лимит ${rub(limit)} · осталось ${rub((limit - total).coerceAtLeast(0.0))}", style = body(12, color = Muted))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Запросы"); Spacer(Modifier.weight(1f))
                    Text(if (showBg) "скрыть фоновые" else "показать фоновые", style = body(13, FontWeight.SemiBold, Teal),
                        modifier = Modifier.clickable { showBg = !showBg }.padding(6.dp))
                }
            }
            if (list.isEmpty()) item { Text("За этот период запросов нет.", style = body(14, color = Muted)) }
            var lastDay = ""
            list.forEach { r ->
                val d = df.format(Date(r.ts))
                if (period != 0 && d != lastDay) { lastDay = d; item { Text(d, style = body(13, FontWeight.SemiBold, Muted), modifier = Modifier.padding(top = 6.dp)) } }
                item { TaskRow(r) { open(r) } }
            }
            if (period != 0 && recs.isNotEmpty()) item { DayBars(recs, if (period == 1) 7 else Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) }
            if (recs.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("По моделям")
                    recs.groupBy { it.model }.entries.sortedByDescending { e -> e.value.sumOf { it.cost } }.forEach { (m, l) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("$m · ${l.size}", style = body(14), modifier = Modifier.weight(1f))
                            Text(rub(l.sumOf { it.cost }), style = body(14, FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DayBars(recs: List<TaskRec>, days: Int) {
    val cal = Calendar.getInstance()
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -(days - 1))
    }.timeInMillis
    val sums = DoubleArray(days)
    recs.forEach { r ->
        val i = ((r.ts - start) / 86_400_000L).toInt()
        if (i in 0 until days) sums[i] += r.cost
    }
    val max = (sums.maxOrNull() ?: 0.0).coerceAtLeast(0.01)
    CardBox {
        Label("По дням"); Gap(8)
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            sums.forEachIndexed { i, v ->
                val f = (v / max).toFloat().coerceIn(0.03f, 1f)
                Box(Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(3.dp))
                    .background(if (i == days - 1) Teal else if (v == sums.max() && v > 0) Violet else Teal.copy(alpha = 0.45f)))
            }
        }
        Row {
            Text(SimpleDateFormat("d MMM", Locale("ru")).format(Date(start)), style = body(12, color = Muted))
            Spacer(Modifier.weight(1f))
            Text("сегодня, ${cal.get(Calendar.DAY_OF_MONTH)}", style = body(12, color = Muted))
        }
    }
}

// ————————————————— Автоответчик —————————————————

@Composable
fun RulesScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.autoReply) }
    var rules by remember { mutableStateOf(Prefs.rules) }
    var access by remember { mutableStateOf(NotifStore.accessGranted(ctx)) }
    LaunchedEffect(Unit) { while (true) { access = NotifStore.accessGranted(ctx); delay(1500) } }
    val nv = NotifStore.version.intValue
    val recent = remember(nv) { NotifStore.recent().takeLast(6).reversed() }

    Screen("Автоответчик", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(AccentSoft).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Отвечать без меня", style = body(16, FontWeight.Bold))
                    Text("Telegram, WhatsApp, MAX, SMS — через уведомления", style = body(13, color = AccentSoftInk))
                }
                Toggle(on) { on = it; Prefs.autoReply = it }
            }
            var known by remember { mutableStateOf(Prefs.replyKnown) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Отвечать и тем, кто в контактах", style = body(15, FontWeight.SemiBold))
                    Text("Выключено — знакомым агент не отвечает, только сообщает тебе", style = body(12, color = Muted))
                }
                Toggle(known) { known = it; Prefs.replyKnown = it }
            }
            if (!access) CardBox(border = WarnLine, bg = WarnBg) {
                Text("Нужен доступ к уведомлениям — иначе агент не видит сообщения.", style = body(14, color = WarnInk))
                Gap(4)
                BigButton("Открыть настройки", {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, bg = Amber)
            }
            Label("Правила — своими словами")
            OutlinedTextField(rules, { rules = it; Prefs.rules = it }, Modifier.fillMaxWidth().height(220.dp),
                textStyle = body(14), shape = RoundedCornerShape(16.dp), colors = fieldColors())
            Text("Агент читает каждое входящее, решает по этим правилам и присылает тебе уведомление, что ответил. Группы не трогает.",
                style = body(13, color = Muted), lineHeight = 18.sp)
            CardBox(border = WarnLine, bg = WarnBg) {
                Text("Коды из SMS, пароли и сообщения про деньги агент не видит и не отвечает на них. Это не отключается.",
                    style = body(13, color = WarnInk), lineHeight = 18.sp)
            }
            Label("Последние входящие")
            if (recent.isEmpty()) Text(if (access) "Пока ничего не пришло." else "Появятся после доступа к уведомлениям.", style = body(14, color = Muted))
            recent.forEach { m ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp)) {
                    Text("${m.app} · ${m.from} · ${m.time()}", style = body(12, color = Muted))
                    Text(NotifStore.safe(m.text), style = body(14), maxLines = 3)
                }
            }
        }
    }
}

// ————————————————— Подключения —————————————————

@Composable
fun SettingsScreen(back: () -> Unit, askPerms: () -> Unit, openYoo: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var fast by remember { mutableStateOf(Prefs.fastModel) }
    var fastIn by remember { mutableStateOf(Prefs.fastIn) }
    var fastOut by remember { mutableStateOf(Prefs.fastOut) }
    var smart by remember { mutableStateOf(Prefs.smartModel) }
    var smartIn by remember { mutableStateOf(Prefs.smartIn) }
    var smartOut by remember { mutableStateOf(Prefs.smartOut) }
    var city by remember { mutableStateOf(Prefs.homeCity) }
    var limit by remember { mutableStateOf(Prefs.monthLimit) }
    var speak by remember { mutableStateOf(Prefs.speak) }
    var check by remember { mutableStateOf<String?>(null) }
    var balance by remember { mutableStateOf(Prefs.lastBalance) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    LaunchedEffect(Unit) {
        val b = withContext(Dispatchers.IO) { runCatching { Llm.balance() }.getOrNull() }
        if (b != null) { balance = rub(b); Prefs.lastBalance = balance }
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    Screen("Настройки", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // баланс — сразу на виду
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AccentSoft).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Баланс GPTunnel", style = body(13, color = AccentSoftInk))
                    Text(balance.ifBlank { "—" }, style = TextStyle(fontFamily = Fonts.display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                }
                IconTile(Icons.Filled.Refresh, "Обновить баланс", {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Llm.balance() }.getOrNull() }?.let { balance = rub(it); Prefs.lastBalance = balance }
                    }
                })
            }

            Section("Модели", "${Agent.short(fast)} · ${Agent.short(smart)}") {
                if (Llm.key.isBlank()) Text("Нет ключа GPTUNNEL_KEY — Claude недоступен", style = body(13, color = Amber))
                Text("Модели Яндекса бесплатны (цена 0) и идут без VPN. Цена — ₽ за 1000 токенов.", style = body(12, color = Muted))
                ModelEditor("Быстрая — простые команды", "fast", fast, fastIn, fastOut,
                    { fast = it; Prefs.fastModel = it }, { fastIn = it; Prefs.fastIn = it }, { fastOut = it; Prefs.fastOut = it })
                ModelEditor("Сильная — сложные задачи", "smart", smart, smartIn, smartOut,
                    { smart = it; Prefs.smartModel = it }, { smartIn = it; Prefs.smartIn = it }, { smartOut = it; Prefs.smartOut = it })
                BigButton("Проверить связь", {
                    check = "Проверяю…"
                    scope.launch {
                        check = withContext(Dispatchers.IO) {
                            runCatching {
                                val r = Llm.chat(Prefs.fastModel, org.json.JSONArray().put(org.json.JSONObject().put("role", "user")
                                    .put("content", "Ответь JSON {\"ok\": true}")), 50)
                                "${Agent.short(Prefs.fastModel)} отвечает (${rub(r.cost(Prefs.fastModel))})"
                            }.getOrElse { "быстрая: ${it.message}" }
                        }
                        fast = Prefs.fastModel; smart = Prefs.smartModel
                        if (Prefs.smartModel.isNotBlank()) withContext(Dispatchers.IO) {
                            runCatching { Llm.chat(Prefs.smartModel, org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "ok")), 5, false) }
                        }.onSuccess { smart = Prefs.smartModel; check = "$check\n${Agent.short(Prefs.smartModel)} отвечает" }
                            .onFailure { check = "$check\nсильная: ${it.message}" }
                    }
                }, bg = Card, fg = Ink, border = Line)
                check?.let { Text(it, style = body(13, color = Muted), lineHeight = 18.sp) }
            }

            Section("Голос", (if (Prefs.sttEngine == "yandex") "распознаёт Яндекс" else "распознаёт телефон") + " · " +
                    (if (Prefs.ttsEngine == "yandex") "озвучка Яндекс" else "озвучка телефон") + if (speak) " · вслух" else "") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Озвучивать ответы", style = body(15, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Toggle(speak) { speak = it; Prefs.speak = it }
                }
                VoiceSettings()
            }

            Section("Мониторинг", if (Prefs.monitorOn && tick >= 0) "раз в ${Prefs.interval} мин · итог в ${Prefs.digestHour}:00" else "выключен",
                statusColor = if (Prefs.monitorOn) Muted else Amber) { MonitorSettings() }

            val mails = listOf(1, 2).count { Mail.hasPass(ctx, it) && tick >= 0 }
            Section("Почта Gmail", if (mails > 0) "подключено ящиков: $mails" else "не подключена",
                statusColor = if (mails > 0) Muted else Amber) { MailSettings() }

            val yc = Yoo.connected(ctx) && tick >= 0
            Section("ЮMoney", if (yc) "подключено · только просмотр" else "не подключено", Violet) {
                Text("Агент видит баланс и историю, платить не может.", style = body(12, color = Muted))
                if (yc) BigButton("Отключить", { Yoo.disconnect(ctx); tick++ }, bg = Card, fg = Ink, border = Line)
                else BigButton("Подключить ЮMoney", openYoo, bg = Violet, fg = Bg)
            }

            Section("Поиск в интернете", if (YaSearch.ready) "Яндекс Поиск" else "запасной: DuckDuckGo",
                statusColor = if (YaSearch.ready) Muted else Amber) {
                if (!YaSearch.ready) Text("Яндекс: ${YaSearch.missing()}", style = body(13, color = Amber))
                Text(YaCloud.info(), style = body(12, color = Muted), lineHeight = 17.sp)
            }

            Section("Прочее", "${city.ifBlank { "город не задан" }} · лимит ${limit.ifBlank { "—" }} ₽") {
                Field("Город для погоды", city) { city = it; Prefs.homeCity = it }
                Field("Лимит расходов в месяц, ₽", limit, KeyboardType.Number) { limit = it; Prefs.monthLimit = it }
            }

            val t = tick // перечитать статусы, когда пользователь вернулся из настроек
            val rows = listOf(
                "Микрофон" to granted(Manifest.permission.RECORD_AUDIO),
                "Контакты" to granted(Manifest.permission.READ_CONTACTS),
                "Звонки" to granted(Manifest.permission.CALL_PHONE),
                "SMS: отправка" to granted(Manifest.permission.SEND_SMS),
                "SMS: чтение" to granted(Manifest.permission.READ_SMS),
                "Фото" to granted(if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE),
                "Уведомления" to (NotifStore.accessGranted(ctx) && t >= 0),
            )
            val missing = rows.filter { !it.second }.map { it.first }
            Section("Разрешения", if (missing.isEmpty()) "всё выдано" else "нет: ${missing.joinToString(", ")}",
                if (missing.isEmpty()) Teal else Amber, if (missing.isEmpty()) Muted else Amber) {
                rows.forEach { (name, ok) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(name, style = body(14), modifier = Modifier.weight(1f))
                        Text(if (ok) "выдано" else "нет", style = body(13, FontWeight.Bold, if (ok) Teal else Amber))
                    }
                }
                BigButton("Выдать разрешения", askPerms)
                BigButton("Доступ к уведомлениям", {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, bg = Card, fg = Ink, border = Line)
            }
            Gap(8)
        }
    }
}

@Composable
fun ModelEditor(title: String, slot: String, model: String, pin: String, pout: String,
                onModel: (String) -> Unit, onIn: (String) -> Unit, onOut: (String) -> Unit) {
    // Готовые варианты: Claude через gptunnel или модели Yandex AI Studio (ключ Yandex Cloud, без VPN)
    val presets = if (slot == "fast") listOf(
        Triple("Claude Haiku", "claude-5.5-haiku", "0.03" to "0.15"),
        Triple("Alice AI Flash", "yc:aliceai-llm-flash", "0" to "0"),
        Triple("YandexGPT 5.1 Pro", "yandexgpt-5.1/latest", "0" to "0"),
        Triple("DeepSeek V4.1 Flash", "yc:deepseek-v4.1-flash", "0" to "0"),
        Triple("Qwen3.6 35B", "yc:qwen3.6-35b-a3b", "0" to "0"),
    ) else listOf(
        Triple("Claude Sonnet", "claude-5.5-sonnet", "0.4" to "2"),
        Triple("Alice AI", "yc:aliceai-llm", "0" to "0"),
        Triple("YandexGPT 5.1 Pro", "yandexgpt-5.1/latest", "0" to "0"),
        Triple("DeepSeek V4.1 Flash", "yc:deepseek-v4.1-flash", "0" to "0"),
        Triple("Qwen3.6 35B", "yc:qwen3.6-35b-a3b", "0" to "0"),
    )
    CardBox {
        Text(title, style = body(14, FontWeight.SemiBold))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (label, m, p) ->
                val on = model.equals(m, true) || (m.startsWith("claude") && !Llm.isYandex(model) && model.contains(m.substringAfterLast('-'), true))
                Box(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Line else Bg)
                    .clickable { onModel(m); onIn(p.first); onOut(p.second) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = body(13, FontWeight.SemiBold, if (on) Ink else Muted))
                }
            }
        }
        OutlinedTextField(model, onModel, Modifier.fillMaxWidth(), label = { Text("Модель") }, singleLine = true,
            textStyle = body(14), shape = RoundedCornerShape(12.dp), colors = fieldColors())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(pin, onIn, Modifier.weight(1f), label = { Text("вход ₽/1K") }, singleLine = true,
                textStyle = body(14), shape = RoundedCornerShape(12.dp), colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(pout, onOut, Modifier.weight(1f), label = { Text("выход ₽/1K") }, singleLine = true,
                textStyle = body(14), shape = RoundedCornerShape(12.dp), colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
}

@Composable
fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        textStyle = body(14), shape = RoundedCornerShape(14.dp), colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = type))
}

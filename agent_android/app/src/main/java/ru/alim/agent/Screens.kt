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
    var balance by remember { mutableStateOf(Prefs.lastBalance) }
    val jv = Journal.version.intValue
    val today = remember(jv) { Journal.since(ctx, Journal.startOfDay()) }

    LaunchedEffect(Unit) {
        val b = withContext(Dispatchers.IO) { runCatching { Llm.balance() }.getOrNull() }
        if (b != null) { balance = rub(b); Prefs.lastBalance = balance }
    }

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
    val send: () -> Unit = { if (input.isNotBlank()) { Agent.start(input); input = ""; typing = false } }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Агент", style = TextStyle(fontFamily = Fonts.display, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink))
            Spacer(Modifier.weight(1f))
            if (balance.isNotBlank()) {
                Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Card).clickable { go(Scr.History) }
                    .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text("Баланс $balance", style = body(13, FontWeight.SemiBold, Teal))
                }
                HGap(8)
            }
            IconTile(Icons.Filled.Notifications, "Автоответчик", { go(Scr.Rules) })
            HGap(8)
            IconTile(Icons.Filled.Settings, "Подключения", { go(Scr.Settings) })
        }

        if (!typing) Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LayerStack(Idle, if (voice.listening) 1600 else 4000, 140.dp, voice.level,
                Modifier.clickable(onClickLabel = "Говорить", onClick = onMic))
            Text(
                when {
                    voice.listening -> voice.partial.ifBlank { "Слушаю…" }
                    else -> "Скажи, что сделать"
                },
                style = body(20, FontWeight.SemiBold), textAlign = TextAlign.Center,
            )
            Text(hint ?: if (voice.listening) "Говори, я записываю" else "Нажми на микрофон или на стопку",
                style = body(14, color = if (hint != null) Amber else Muted), textAlign = TextAlign.Center)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Погода на завтра", "Последние сообщения", "Сколько потратил?", "Будильник на 7:00").forEach { s ->
                    Chip(s) { Agent.start(s) }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Label("Сегодня")
            Spacer(Modifier.weight(1f))
            Text("Потрачено: ${rub(today.sumOf { it.cost })}", style = body(13, color = Muted))
        }
        Gap(10)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (today.isEmpty()) item {
                Text("Пока пусто. Попроси что-нибудь — задачи появятся здесь.", style = body(14, color = Muted))
            }
            items(today.reversed()) { r -> TaskRow(r) }
        }

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

        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
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
fun TaskRow(r: TaskRec) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (r.ok) Color(0xFF12302C) else Color(0xFF3A1A1A)),
            contentAlignment = Alignment.Center) {
            if (r.ok) Icon(Icons.Filled.Check, null, tint = Teal, modifier = Modifier.size(18.dp))
            else Text("!", style = body(16, FontWeight.Bold, Danger))
        }
        HGap(12)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = body(15, FontWeight.SemiBold), maxLines = 1)
            Text("${r.steps} шаг. · ${r.model} · ${rub(r.cost)} · ${hm.format(Date(r.ts))}", style = body(13, color = Muted))
        }
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

    Screen("Задача · ${Agent.short(Agent.model)}", back) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LayerStack(pal, if (running) 1300 else 2600, 88.dp)
            HGap(14)
            Column {
                Label(when (phase) {
                    Agent.Phase.WORKING -> "Думаю…"
                    Agent.Phase.CONFIRM -> "Нужно твоё ОК"
                    else -> if (Agent.failed) "Не вышло" else "Готово"
                }, pal.accent)
                Gap(4)
                Text("«${Agent.task}»", style = body(18, FontWeight.SemiBold), maxLines = 3)
            }
        }

        Label("Шаги")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(Agent.steps) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (s.done) Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF12302C)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = Teal, modifier = Modifier.size(14.dp))
                    } else Box(Modifier.size(26.dp).border(2.dp, Violet, RoundedCornerShape(13.dp)))
                    HGap(12)
                    Column(Modifier.weight(1f)) {
                        Text(s.text, style = body(15, if (s.done) FontWeight.Normal else FontWeight.SemiBold))
                        if (s.detail.isNotBlank()) Text(s.detail, style = body(12, color = Muted), maxLines = 2)
                    }
                }
            }
            Agent.pending?.let { p ->
                item {
                    CardBox(border = Color(0xFF4A3A16), bg = Color(0xFF1A160C)) {
                        Text(p.text, style = body(16, FontWeight.SemiBold), lineHeight = 22.sp)
                        Gap(6)
                        BigButton("Да, делай", { Agent.answer(true) })
                        BigButton("Нет", { Agent.answer(false) }, bg = Card, fg = Ink, border = Line)
                    }
                }
            }
            if (phase == Agent.Phase.DONE) item {
                CardBox(bg = if (Agent.failed) Color(0xFF251516) else Color(0xFF12302C)) {
                    Text(Agent.result, style = body(16, color = if (Agent.failed) Danger else Color(0xFFD8FFF8)), lineHeight = 22.sp)
                }
            }
        }

        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp)) {
            Stat("Шагов", "${Agent.steps.size}", Modifier.weight(1f))
            Stat("Время", "%d:%02d".format(secs / 60, secs % 60), Modifier.weight(1f))
            Stat("Стоимость", "≈ ${rub(Agent.cost)}", Modifier.weight(1f))
        }
        if (Agent.canFollowUp) {
            if (voice.listening) Text(voice.partial.ifBlank { "Слушаю…" }, style = body(15, FontWeight.SemiBold, Teal))
            hint?.let { Text(it, style = body(13, color = Amber)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(reply, { reply = it }, Modifier.weight(1f).height(58.dp), placeholder = { Text("Ответить агенту…", style = body(15, color = Muted)) },
                    textStyle = body(15), singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (reply.isNotBlank()) { Agent.followUp(reply); reply = "" } }))
                HGap(8)
                if (reply.isNotBlank()) IconTile(Icons.AutoMirrored.Filled.Send, "Отправить", { Agent.followUp(reply); reply = "" }, 52.dp)
                else Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(if (voice.listening) Danger else Teal)
                    .clickable(onClickLabel = "Ответить голосом") {
                        hint = null
                        if (voice.listening) voice.stopListen()
                        else voice.listen(onText = { Agent.followUp(it) }, onError = { hint = it })
                    }, contentAlignment = Alignment.Center) { MicIcon(Bg, 26.dp) }
            }
        }
        if (Agent.busy) BigButton("Остановить", { Agent.stop() }, bg = Color(0xFF251516), fg = Danger, border = Color(0xFF5A2A2A))
        else BigButton("Готово", back)
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
fun HistoryScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var period by remember { mutableStateOf(2) } // 0 день, 1 неделя, 2 месяц
    var balance by remember { mutableStateOf(Prefs.lastBalance) }
    var balErr by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = {
        scope.launch {
            balErr = null
            val r = withContext(Dispatchers.IO) { runCatching { Llm.balance() } }
            r.onSuccess { balance = rub(it); Prefs.lastBalance = balance }.onFailure { balErr = it.message }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val jv = Journal.version.intValue
    val from = when (period) { 0 -> Journal.startOfDay(); 1 -> Journal.startOfWeek(); else -> Journal.startOfMonth() }
    val recs = remember(jv, period) { Journal.since(ctx, from) }
    val total = recs.sumOf { it.cost }

    Screen("История", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CardBox(bg = Color(0xFF12302C)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Баланс GPTunnel", style = body(13, color = Color(0xFFA8DCD3)))
                        Text(balance.ifBlank { "—" }, style = TextStyle(fontFamily = Fonts.display, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                        balErr?.let { Text(it, style = body(12, color = Amber)) }
                    }
                    IconTile(Icons.Filled.Refresh, "Обновить баланс", refresh)
                }
            }

            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(4.dp)) {
                listOf("День", "Неделя", "Месяц").forEachIndexed { i, s ->
                    Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (period == i) Line else Color.Transparent).clickable { period = i },
                        contentAlignment = Alignment.Center) {
                        Text(s, style = body(14, FontWeight.SemiBold, if (period == i) Ink else Muted))
                    }
                }
            }

            Column {
                Text(when (period) { 0 -> "Потрачено сегодня"; 1 -> "Потрачено за 7 дней"; else -> "Потрачено в этом месяце" }, style = body(13, color = Muted))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(rub(total), style = TextStyle(fontFamily = Fonts.display, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = Ink))
                    HGap(10)
                    Text("· ${recs.size} задач", style = body(14, color = Muted), modifier = Modifier.padding(bottom = 6.dp))
                }
                if (period == 2) {
                    val limit = Prefs.num(Prefs.monthLimit)
                    if (limit > 0) {
                        val f = (total / limit).toFloat().coerceIn(0f, 1f)
                        Gap(10)
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1B2129))) {
                            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (f > 0.9f) Amber else Teal))
                        }
                        Gap(6)
                        Row { Text("Лимит: ${rub(limit)}", style = body(12, color = Muted)); Spacer(Modifier.weight(1f))
                            Text("осталось ${rub((limit - total).coerceAtLeast(0.0))}", style = body(12, color = Muted)) }
                    }
                }
            }

            if (period != 0) DayBars(recs, if (period == 1) 7 else Calendar.getInstance().get(Calendar.DAY_OF_MONTH))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("По моделям")
                recs.groupBy { it.model }.entries.sortedByDescending { e -> e.value.sumOf { it.cost } }.forEach { (m, l) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (m.contains("Sonnet")) Violet else Teal); HGap(12)
                        Column(Modifier.weight(1f)) {
                            Text(m, style = body(15, FontWeight.SemiBold))
                            Text("${l.size} задач", style = body(12, color = Muted))
                        }
                        Text(rub(l.sumOf { it.cost }), style = body(15, FontWeight.Bold))
                    }
                }
                if (recs.isEmpty()) Text("Задач за этот период нет.", style = body(14, color = Muted))
            }

            if (recs.isNotEmpty()) Column {
                Label("Самые дорогие"); Gap(6)
                recs.sortedByDescending { it.cost }.take(5).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(r.title, style = body(14), modifier = Modifier.weight(1f), maxLines = 1)
                        Text(rub(r.cost), style = body(14, FontWeight.SemiBold))
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
                    .background(if (i == days - 1) Teal else if (v == sums.max() && v > 0) Violet else Color(0xFF1B7F86)))
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
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF12302C)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Отвечать без меня", style = body(16, FontWeight.Bold))
                    Text("Telegram, WhatsApp, MAX, SMS — через уведомления", style = body(13, color = Color(0xFFA8DCD3)))
                }
                Toggle(on) { on = it; Prefs.autoReply = it }
            }
            if (!access) CardBox(border = Color(0xFF4A3A16), bg = Color(0xFF1A160C)) {
                Text("Нужен доступ к уведомлениям — иначе агент не видит сообщения.", style = body(14, color = Color(0xFFE3D3B0)))
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
            CardBox(border = Color(0xFF4A3A16), bg = Color(0xFF1A160C)) {
                Text("Коды из SMS, пароли и сообщения про деньги агент не видит и не отвечает на них. Это не отключается.",
                    style = body(13, color = Color(0xFFE3D3B0)), lineHeight = 18.sp)
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
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    Screen("Подключения", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("Мозг · GPTunnel", Teal)
            CardBox {
                Text(if (Llm.key.isNotBlank()) "Ключ GPTUNNEL_KEY в сборке есть" else "Ключа нет — добавь секрет GPTUNNEL_KEY и пересобери",
                    style = body(14, FontWeight.SemiBold, if (Llm.key.isNotBlank()) Teal else Amber))
                Text("Названия моделей и цены (₽ за 1000 токенов) — со страницы модели на gptunnel.", style = body(12, color = Muted))
            }
            ModelEditor("Быстрая (простые команды)", fast, fastIn, fastOut,
                { fast = it; Prefs.fastModel = it }, { fastIn = it; Prefs.fastIn = it }, { fastOut = it; Prefs.fastOut = it })
            ModelEditor("Сильная (сложные задачи)", smart, smartIn, smartOut,
                { smart = it; Prefs.smartModel = it }, { smartIn = it; Prefs.smartIn = it }, { smartOut = it; Prefs.smartOut = it })
            BigButton("Проверить связь и баланс", {
                check = "Проверяю…"
                scope.launch {
                    check = withContext(Dispatchers.IO) {
                        val bal = runCatching { "баланс ${rub(Llm.balance())}" }.getOrElse { "баланс: ${it.message}" }
                        val m = runCatching {
                            val r = Llm.chat(Prefs.fastModel, org.json.JSONArray().put(org.json.JSONObject().put("role", "user")
                                .put("content", "Ответь JSON {\"ok\": true}")), 50)
                            "${Prefs.fastModel} отвечает (${rub(r.cost(Prefs.fastModel))})"
                        }.getOrElse { "модель: ${it.message}" }
                        "$m\n$bal"
                    }
                    // модель могла подобраться автоматически — показать новые названия
                    fast = Prefs.fastModel; smart = Prefs.smartModel
                    if (Prefs.smartModel.isNotBlank()) withContext(Dispatchers.IO) {
                        runCatching { Llm.chat(Prefs.smartModel, org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "ok")), 5, false) }
                    }.onSuccess { smart = Prefs.smartModel; check = "$check\n${Prefs.smartModel} отвечает" }
                        .onFailure { check = "$check\nсильная модель: ${it.message}" }
                }
            }, bg = Card, fg = Ink, border = Line)
            check?.let { Text(it, style = body(13, color = Muted), lineHeight = 18.sp) }

            Label("ЮMoney · только просмотр", Violet)
            CardBox {
                val yc = Yoo.connected(ctx) && tick >= 0
                Text(if (yc) "Подключено: агент видит баланс и историю, платить не может" else "Не подключено",
                    style = body(14, FontWeight.SemiBold, if (yc) Teal else Muted))
                Gap(4)
                if (yc) BigButton("Отключить", { Yoo.disconnect(ctx); tick++ }, bg = Card, fg = Ink, border = Line)
                else BigButton("Подключить ЮMoney", openYoo, bg = Violet, fg = Bg)
            }

            Label("Прочее")
            Field("Город для погоды", city) { city = it; Prefs.homeCity = it }
            Field("Лимит расходов в месяц, ₽", limit, KeyboardType.Number) { limit = it; Prefs.monthLimit = it }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Озвучивать ответы", style = body(15, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Toggle(speak) { speak = it; Prefs.speak = it }
            }

            Label("Разрешения телефона")
            val t = tick // перечитать статусы, когда пользователь вернулся из настроек
            val rows = listOf(
                "Микрофон" to granted(Manifest.permission.RECORD_AUDIO),
                "Контакты" to granted(Manifest.permission.READ_CONTACTS),
                "Звонки" to granted(Manifest.permission.CALL_PHONE),
                "SMS: отправка" to granted(Manifest.permission.SEND_SMS),
                "SMS: чтение" to granted(Manifest.permission.READ_SMS),
                "Фото" to granted(if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE),
                "Уведомления (мессенджеры)" to (NotifStore.accessGranted(ctx) && t >= 0),
            )
            rows.forEach { (name, ok) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = body(14, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Text(if (ok) "выдано" else "нет", style = body(13, FontWeight.Bold, if (ok) Teal else Amber))
                }
            }
            BigButton("Выдать разрешения", askPerms)
            BigButton("Доступ к уведомлениям", {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, bg = Card, fg = Ink, border = Line)
            Gap(8)
        }
    }
}

@Composable
fun ModelEditor(title: String, model: String, pin: String, pout: String,
                onModel: (String) -> Unit, onIn: (String) -> Unit, onOut: (String) -> Unit) {
    CardBox {
        Text(title, style = body(14, FontWeight.SemiBold))
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

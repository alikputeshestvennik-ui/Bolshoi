package ru.alim.agent

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dm = SimpleDateFormat("d MMM HH:mm", Locale("ru"))

// ————————————————— Память —————————————————

@Composable
fun MemoryScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val v = Memory.version.intValue
    val facts = remember(v) { Memory.all(ctx).reversed() }
    var input by remember { mutableStateOf("") }
    val add: () -> Unit = { if (input.isNotBlank()) { Memory.add(ctx, input, "ты"); input = "" } }

    Screen("Память", back) {
        Text("Что агент знает о тебе. «Догадки» он вывел сам из уведомлений и писем — подтверди или удали.",
            style = body(13, color = Muted), lineHeight = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f).height(58.dp), singleLine = true,
                placeholder = { Text("Например: Денис — мой брат", style = body(14, color = Muted)) },
                textStyle = body(14), shape = RoundedCornerShape(16.dp), colors = fieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }))
            HGap(8)
            IconTile(Icons.Filled.Add, "Запомнить", add, 52.dp)
        }
        Label("${facts.size} записей")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (facts.isEmpty()) item { Text("Пока пусто. Скажи агенту «запомни, что…» или добавь сам.", style = body(14, color = Muted)) }
            items(facts, key = { it.id }) { f ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.text, style = body(15))
                        Text("${when (f.source) { "авто" -> "догадка"; "агент" -> "записал агент"; else -> "ты сказал" }} · ${dm.format(Date(f.ts))}",
                            style = body(12, color = if (f.source == "авто") Amber else Muted))
                    }
                    if (f.source == "авто") Box(Modifier.size(44.dp).clickable(onClickLabel = "Подтвердить") { Memory.confirm(ctx, f.id) },
                        contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, "Подтвердить", tint = Teal, modifier = Modifier.size(20.dp)) }
                    Box(Modifier.size(44.dp).clickable(onClickLabel = "Удалить") { Memory.remove(ctx, f.id) },
                        contentAlignment = Alignment.Center) { Icon(Icons.Filled.Delete, "Удалить", tint = Muted, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

// ————————————————— Сводка —————————————————

@Composable
fun DigestScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val dv = Digests.version.intValue
    val ev = Events.version.intValue
    val digests = remember(dv) { Digests.all(ctx).reversed() }
    val urgent = remember(ev) { Events.since(ctx, Journal.startOfDay()).filter { it.urgent }.reversed() }
    val counts = remember(ev) { Events.since(ctx, Journal.startOfDay()).groupingBy { it.kind }.eachCount() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(dv) { busy = false }

    Screen("Сводка", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Сегодня увидел: уведомлений ${counts["notif"] ?: 0}, сообщений ${counts["msg"] ?: 0}, SMS ${counts["sms"] ?: 0}, писем ${counts["mail"] ?: 0}",
                style = body(13, color = Muted))
            Label("Важное сегодня", Amber)
            if (urgent.isEmpty()) Text("Ничего срочного.", style = body(14, color = Muted))
            urgent.forEach { e ->
                CardBox(border = Color(0xFF4A3A16), bg = Color(0xFF1A160C)) {
                    Text(e.note.ifBlank { e.title.ifBlank { e.text.take(80) } }, style = body(15, FontWeight.SemiBold))
                    Text("${e.source} · ${e.from} · ${SimpleDateFormat("HH:mm", Locale("ru")).format(Date(e.ts))}", style = body(12, color = Muted))
                }
            }
            Label("Итог дня")
            digests.firstOrNull()?.let { d ->
                CardBox(bg = Color(0xFF12302C)) {
                    Text(dm.format(Date(d.ts)), style = body(12, color = Color(0xFFA8DCD3)))
                    Text(d.text, style = body(15, color = Color(0xFFD8FFF8)), lineHeight = 21.sp)
                }
            } ?: Text("Итог собирается сам вечером, в ${Prefs.digestHour}:00. Можно собрать сейчас.", style = body(14, color = Muted))
            BigButton(if (busy) "Собираю…" else "Собрать итог сейчас", { if (!busy) { busy = true; Monitor.runNow(ctx, digest = true) } },
                bg = Card, fg = Ink, border = Line)
            if (digests.size > 1) {
                Label("Прошлые дни")
                digests.drop(1).forEach { d ->
                    CardBox {
                        Text(dm.format(Date(d.ts)), style = body(12, color = Muted))
                        Text(d.text, style = body(14), lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

// ————————————————— Голос —————————————————

@Composable
fun VoiceSettings() {
    val scope = rememberCoroutineScope()
    var stt by remember { mutableStateOf(Prefs.sttEngine) }
    var tts by remember { mutableStateOf(Prefs.ttsEngine) }
    var voice by remember { mutableStateOf(Prefs.ttsVoice) }
    var res by remember { mutableStateOf<String?>(null) }

    @Composable
    fun Pick(options: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, label) ->
                Box(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (cur == id) Line else Card)
                    .clickable { set(id) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = body(13, FontWeight.SemiBold, if (cur == id) Ink else Muted))
                }
            }
        }
    }

    if (!YaSpeech.ready) Text("Для голоса Яндекса нужен ключ и каталог Yandex Cloud.", style = body(12, color = Amber))
    Text("Распознавание речи", style = body(13, color = Muted))
    Pick(listOf("android" to "Телефон (Google/Samsung)", "yandex" to "Яндекс SpeechKit"), stt) { stt = it; Prefs.sttEngine = it }
    Text("Озвучка ответов", style = body(13, color = Muted))
    Pick(listOf("android" to "Телефон", "yandex" to "Яндекс SpeechKit"), tts) { tts = it; Prefs.ttsEngine = it }
    if (tts == "yandex") {
        Pick(YaSpeech.VOICES, voice) { voice = it; Prefs.ttsVoice = it }
        BigButton("Проверить голос", {
            res = "Синтезирую…"
            scope.launch {
                res = withContext(Dispatchers.IO) {
                    runCatching { YaSpeech.play(YaSpeech.synth("Слушаю, Алим. Что делаем?", voice)); null }.getOrElse { it.message ?: "ошибка" }
                }
            }
        }, bg = Card, fg = Ink, border = Line)
    }
    res?.let { Text(it, style = body(13, color = Amber)) }
}

// ————————————————— Мониторинг и почта (часть «Подключений») —————————————————

@SuppressLint("BatteryLife")
@Composable
fun MonitorSettings() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.monitorOn) }
    var interval by remember { mutableIntStateOf(Prefs.interval) }
    var hour by remember { mutableStateOf(Prefs.digestHour.toString()) }
    var status by remember { mutableStateOf(Prefs.monitorStatus) }
    LaunchedEffect(Unit) { while (true) { delay(2000); status = Prefs.monitorStatus } }
    val pm = ctx.getSystemService(PowerManager::class.java)
    var battOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LaunchedEffect(Unit) { while (true) { delay(2000); battOk = pm.isIgnoringBatteryOptimizations(ctx.packageName) } }

    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Следить за уведомлениями, SMS и почтой", style = body(15, FontWeight.SemiBold))
            Text("Важное — сразу уведомлением, вечером итог дня", style = body(12, color = Muted))
        }
        Toggle(on) { on = it; Prefs.monitorOn = it; Monitor.schedule(ctx, replace = true) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60).forEach { m ->
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (interval == m) Line else Card)
                .clickable { interval = m; Prefs.interval = m; Monitor.schedule(ctx, replace = true) }, contentAlignment = Alignment.Center) {
                Text("раз в $m мин", style = body(13, FontWeight.SemiBold, if (interval == m) Ink else Muted))
            }
        }
    }
    Field("Итог дня в (час, 0–23)", hour, KeyboardType.Number) { hour = it; it.toIntOrNull()?.takeIf { h -> h in 0..23 }?.let { h -> Prefs.digestHour = h } }
    if (!battOk) BigButton("Не давать Samsung усыплять агента", {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }, bg = Amber)
    else Text("Ограничения батареи сняты", style = body(13, color = Teal))
    var yt by remember { mutableStateOf(Prefs.ytPicks) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Подборка YouTube по интересам", style = body(15, FontWeight.SemiBold))
            Text("Раз в день, вместе с итогом: свежие ролики по тому, что агент знает о тебе", style = body(12, color = Muted))
        }
        Toggle(yt) { yt = it; Prefs.ytPicks = it }
    }
    if (status.isNotBlank()) Text("Последняя проверка: $status", style = body(12, color = Muted), lineHeight = 17.sp)
    BigButton("Проверить сейчас", { Monitor.runNow(ctx) }, bg = Card, fg = Ink, border = Line)

}

@Composable
fun MailSettings() {
    Text("Нужен пароль приложения Google: Безопасность → Двухэтапная проверка → Пароли приложений.",
        style = body(12, color = Muted), lineHeight = 17.sp)
    MailAccount(1)
    MailAccount(2)
}

@Composable
private fun MailAccount(n: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(Mail.email(ctx, n)) }
    var pass by remember { mutableStateOf("") }
    var has by remember { mutableStateOf(Mail.hasPass(ctx, n)) }
    var res by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(!(has && email.isNotBlank())) }
    if (!edit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(email, style = body(14, FontWeight.SemiBold), maxLines = 1)
                Text("подключён", style = body(12, color = Teal))
            }
            Text("Изменить", style = body(13, FontWeight.SemiBold, Teal), modifier = Modifier.clickable { edit = true }.padding(6.dp))
        }
        return
    }
    CardBox {
        Text("Ящик $n", style = body(14, FontWeight.SemiBold))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("адрес @gmail.com") }, singleLine = true,
            textStyle = body(14), shape = RoundedCornerShape(12.dp), colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text(if (has) "пароль приложения (сохранён)" else "пароль приложения") },
            singleLine = true, textStyle = body(14), shape = RoundedCornerShape(12.dp), colors = fieldColors(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Сохранить и проверить", {
                Mail.save(ctx, n, email, pass); pass = ""; has = Mail.hasPass(ctx, n); res = "Проверяю…"
                scope.launch { res = withContext(Dispatchers.IO) { runCatching { Mail.check(ctx, n) }.getOrElse { it.message ?: "ошибка" } } }
            }, modifier = Modifier.weight(1f))
            if (has || email.isNotBlank()) BigButton("Убрать", { Mail.remove(ctx, n); email = ""; has = false; res = null },
                bg = Card, fg = Ink, border = Line, modifier = Modifier.weight(0.5f))
        }
        res?.let { Text(it, style = body(13, color = if (it.startsWith("подключено")) Teal else Amber)) }
    }
}

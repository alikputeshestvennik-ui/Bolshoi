package ru.alim.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ———————————————— Список чатов ————————————————

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(open: (String) -> Unit, add: () -> Unit, profile: (String) -> Unit) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); tick++ } }
    val rows = Friends.list.toList().map { it to Chat.of(it.id) }
        .sortedByDescending { (f, c) -> if (f.main) Long.MAX_VALUE else c.msgs.lastOrNull()?.ts ?: 0L }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Чаты", style = TextStyle(fontFamily = Fonts.display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = { it.first.id }) { (f, c) ->
                    val last = c.msgs.lastOrNull()
                    val busy = c.core.busy
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = { open(f.id) }, onLongClick = { profile(f.id) }).padding(horizontal = 6.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Avatar(56.dp, online = Online.online && tick >= 0, f = f)
                        HGap(12)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, style = body(16, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (f.main) { HGap(6); Text("главный", style = body(11, FontWeight.SemiBold, Teal),
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AccentSoft).padding(horizontal = 6.dp, vertical = 1.dp)) }
                                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                                last?.let { Text(timeLabel(it.ts), style = body(12, color = if (c.unread > 0) Teal else Muted)) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val preview = when {
                                    busy -> shortLine(Online.status(c.core, f))
                                    last == null -> f.role.ifBlank { "Напиши первым" }
                                    last.voice.isNotBlank() -> (if (last.me) "Вы: " else "") + "🎤 Голосовое"
                                    else -> (if (last.me) "Вы: " else "") + last.text.replace('\n', ' ')
                                }
                                Text(preview, style = body(14, color = if (busy) Th.c.violet else Muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                if (c.unread > 0) { HGap(8); Box(Modifier.size(22.dp).clip(CircleShape).background(Teal), contentAlignment = Alignment.Center) {
                                    Text(if (c.unread > 99) "99" else "${c.unread}", style = body(11, FontWeight.Bold, OnAccent)) } }
                            }
                        }
                    }
                }
                item { Text("Долгое нажатие — профиль друга. Главный пишет первым и делает всё в телефоне, остальные просят его.",
                    style = body(12, color = Muted), modifier = Modifier.padding(top = 10.dp, start = 6.dp, end = 6.dp)) }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(18.dp).height(56.dp).clip(RoundedCornerShape(28.dp)).background(Teal)
            .clickable(onClickLabel = "Добавить друга", onClick = add).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("+  Друг", style = body(16, FontWeight.Bold, OnAccent))
        }
    }
}

private fun shortLine(s: String) = s.split(' ').take(4).joinToString(" ").let { if (it.length < s.length) "$it…" else it }

private fun timeLabel(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }; val n = Calendar.getInstance()
    return when {
        c.get(Calendar.YEAR) == n.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale("ru")).format(Date(ts))
        System.currentTimeMillis() - ts < 6 * 86_400_000L -> SimpleDateFormat("EE", Locale("ru")).format(Date(ts))
        else -> SimpleDateFormat("d MMM", Locale("ru")).format(Date(ts))
    }
}

// ———————————————— Профиль друга ————————————————

@Composable
fun ProfileScreen(fid: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var f by remember(fid) { mutableStateOf(Friends.get(fid)) }
    fun upd(n: Friend) { f = n; Friends.save(n) }
    var gen by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) copyImage(ctx, uri, "avatar_${fid}_${System.currentTimeMillis()}.jpg")?.let { upd(f.copy(avatar = it)); WallState.version++ }
    }

    Screen("Профиль", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(112.dp, online = Online.online, f = f)
                Text(f.name.ifBlank { "Без имени" }, style = body(22, FontWeight.Bold))
                Text(if (f.main) "главный · пишет первым, делает всё в телефоне" else Online.status(Chat.of(fid).core, f),
                    style = body(13, color = if (Online.online) Teal else Muted))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Chip("Буква") { upd(f.copy(avatar = "")); WallState.version++ }
                Chip("Фото из галереи") { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                Chip("Нарисовать") { gen = !gen }
            }
            if (gen) CardBox {
                Field("Каким нарисовать аватар", prompt) { prompt = it }
                BigButton(if (msg == "…") "Рисую…" else "Нарисовать аватар", {
                    if (prompt.isBlank() || msg == "…") return@BigButton
                    msg = "…"
                    scope.launch {
                        msg = withContext(Dispatchers.IO) {
                            runCatching {
                                val id = YaArt.generate(ctx, "Аватар для чата, портрет по центру, крупно: $prompt", "1:1")
                                copyImage(ctx, photoUri(id), "avatar_${fid}_${System.currentTimeMillis()}.jpg")?.let { p -> withContext(Dispatchers.Main) { upd(f.copy(avatar = p)) } }
                                null
                            }.getOrElse { it.message ?: "не получилось" }
                        }
                        WallState.version++
                    }
                })
                msg?.takeIf { it != "…" }?.let { Text(it, style = body(13, color = Amber)) }
            }
            CardBox {
                Field("Имя", f.name) { upd(f.copy(name = it)) }
                Text("Пол", style = body(12, color = Muted))
                Seg(listOf("m" to "Мужской", "f" to "Женский"), if (f.male) "m" else "f") { upd(f.copy(male = it == "m")) }
                Text("Манера общения", style = body(12, color = Muted))
                Seg(listOf("buddy" to "Кореш", "secretary" to "Секретарь", "short" to "Коротко"), f.style) { upd(f.copy(style = it)) }
                Text(Persona.styleSample(f.style, f.male), style = body(15), lineHeight = 21.sp, modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp)).background(Th.c.inBubble).border(1.dp, Line, RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp)).padding(12.dp))
                Text("так ${f.name.ifBlank { "он" }} будет писать", style = body(12, color = Muted))
            }
            if (!f.main) CardBox {
                Field("О чём с ним говорить", f.role) { upd(f.copy(role = it)) }
                Text("Например: «просто поболтать», «помощь с кодом», «спорт и питание». Действия в телефоне он попросит у ${Friends.main.name}.",
                    style = body(12, color = Muted))
                Text("Модель", style = body(12, color = Muted))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "Как в настройках", "claude-5.5-sonnet" to "Claude Sonnet", "claude-5.5-haiku" to "Claude Haiku",
                        "yc:aliceai-llm" to "Alice AI", "yandexgpt-5.1/latest" to "YandexGPT", "yc:deepseek-v4.1-flash" to "DeepSeek", "yc:qwen3.6-35b-a3b" to "Qwen",
                        "google:gemini-3.5-flash-lite" to "Gemini Lite · бесплатно")
                        .forEach { (id, label) ->
                            val on = f.model == id
                            Box(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) AccentSoft else Th.c.bg)
                                .border(1.dp, if (on) Teal else Line, RoundedCornerShape(12.dp)).clickable { upd(f.copy(model = id)) }.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center) { Text(label, style = body(13, FontWeight.SemiBold, if (on) Teal else Muted)) }
                        }
                }
            }
            if (!f.main) BigButton("Удалить друга", { askDelete = true }, bg = DangerBg, fg = Danger, border = DangerLine)
        }
    }
    if (askDelete) AlertDialog(onDismissRequest = { askDelete = false },
        confirmButton = { TextButton({ askDelete = false; Friends.remove(fid); back() }) { Text("Удалить", color = Danger) } },
        dismissButton = { TextButton({ askDelete = false }) { Text("Отмена") } },
        title = { Text("Удалить ${f.name}?") }, text = { Text("Переписка с ним удалится. Память о тебе останется.") })
}

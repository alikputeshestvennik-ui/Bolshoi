package ru.alim.agent

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Скопировать картинку (из галереи или сгенерированную) к себе в файлы. */
fun copyImage(ctx: Context, uri: Uri, name: String): String? = runCatching {
    val f = File(ctx.filesDir, name)
    ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: return null
    f.absolutePath
}.getOrNull()

@Composable
fun Seg(options: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Track).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (id, label) ->
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp)).background(if (cur == id) Th.c.card else Th.c.card.copy(alpha = 0f))
                .clickable { set(id) }, contentAlignment = Alignment.Center) {
                Text(label, style = body(14, FontWeight.SemiBold, if (cur == id) Ink else Muted))
            }
        }
    }
}

// ———————————————— Оформление ————————————————

@Composable
fun LookScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var wall by remember { mutableStateOf(Prefs.wallpaper) }
    var dim by remember { mutableIntStateOf(Prefs.wallDim) }
    var prompt by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && copyImage(ctx, uri, "wallpaper.jpg") != null) { Prefs.wallpaper = "photo"; wall = "photo"; WallState.version++ }
    }

    Screen("Оформление", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Цвет")
            var auto by remember { mutableStateOf(Th.auto) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Th.c.card).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Авто: день и ночь", style = body(15, FontWeight.SemiBold))
                    Text("С 7:00 до 19:00 — «Рассвет», вечером и ночью — «Ночной рейс».", style = body(12, color = Muted))
                }
                Toggle(auto) { on -> auto = on; if (on) Th.set("auto") else Th.set(Th.c.id) }
            }
            Th.all.chunked(4).forEach { rowPals -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPals.forEach { p ->
                    val on = !auto && Th.c.id == p.id
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Th.c.card)
                        .border(if (on) 2.dp else 1.dp, if (on) Teal else Line, RoundedCornerShape(16.dp)).clickable { auto = false; Th.set(p.id) }.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(11.dp))
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(p.wall)).padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                            Box(Modifier.size(46.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(p.inBubble))
                            Box(Modifier.align(Alignment.End).size(38.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(p.outBubble))
                            Box(Modifier.size(30.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(p.inBubble))
                        }
                        Text(p.title, style = body(12, FontWeight.SemiBold), modifier = Modifier.align(Alignment.CenterHorizontally), maxLines = 1)
                    }
                }
                repeat(4 - rowPals.size) { Spacer(Modifier.weight(1f)) }
            } }
            Label("Подложка")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pattern" to "Узор", "gallery" to "Из галереи", "gen" to "Нарисовать").forEach { (id, t) ->
                    val on = (id == "pattern" && wall == "pattern") || (id != "pattern" && wall == "photo" && id == "gallery")
                    Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp)).background(Th.c.card)
                        .border(if (on) 2.dp else 1.dp, if (on) Teal else Line, RoundedCornerShape(16.dp))
                        .clickable {
                            when (id) {
                                "pattern" -> { Prefs.wallpaper = "pattern"; wall = "pattern"; WallState.version++ }
                                "gallery" -> pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                else -> msg = msg ?: ""
                            }
                        }, contentAlignment = Alignment.Center) { Text(t, style = body(14, FontWeight.SemiBold)) }
                }
            }
            if (msg != null) CardBox {
                Field("Что нарисовать для подложки", prompt) { prompt = it }
                BigButton(if (msg == "…") "Рисую… (до минуты)" else "Нарисовать подложку", {
                    if (prompt.isBlank() || msg == "…") return@BigButton
                    msg = "…"
                    scope.launch {
                        msg = withContext(Dispatchers.IO) {
                            runCatching {
                                val id = YaArt.generate(ctx, "Фон для чата мессенджера, спокойный, без текста, без людей в центре: $prompt", "9:16")
                                copyImage(ctx, photoUri(id), "wallpaper.jpg") ?: error("не сохранилось")
                                Prefs.wallpaper = "photo"
                                ""
                            }.getOrElse { it.message ?: "не получилось" }
                        }
                        if (msg == "") wall = "photo"
                        WallState.version++
                    }
                })
                msg?.takeIf { it.isNotBlank() && it != "…" }?.let { Text(it, style = body(13, color = Amber)) }
            }
            var qb by remember { mutableStateOf(Prefs.quickButtons) }
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Подсказки-кнопки", style = body(15, FontWeight.SemiBold))
                        Text("варианты ответа под сообщениями: «Дешевле», «Открыть первую»…", style = body(12, color = Muted))
                    }
                    Toggle(qb) { qb = it; Prefs.quickButtons = it }
                }
            }
            if (wall == "photo") CardBox {
                Row { Text("Приглушить фото", style = body(14), modifier = Modifier.weight(1f)); Text("$dim%", style = body(14, color = Muted)) }
                Slider(dim.toFloat(), { dim = it.toInt(); Prefs.wallDim = dim; WallState.version++ }, valueRange = 0f..70f,
                    colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal, inactiveTrackColor = Track))
            }
        }
    }
}

// ———————————————— Сам пишет первым ————————————————

@Composable
fun InitiativeScreen(back: () -> Unit) {
    var morning by remember { mutableStateOf(Prefs.initMorning) }
    var mh by remember { mutableStateOf(Prefs.morningHour.toString()) }
    var important by remember { mutableStateOf(Prefs.initImportant) }
    var chatty by remember { mutableIntStateOf(Prefs.chatty) }
    var yt by remember { mutableStateOf(Prefs.ytPicks) }
    var voice by remember { mutableStateOf(Prefs.voiceReplies) }
    var qf by remember { mutableStateOf(Prefs.quietFrom.toString()) }
    var qt by remember { mutableStateOf(Prefs.quietTo.toString()) }
    var dh by remember { mutableStateOf(Prefs.digestHour.toString()) }

    @Composable
    fun Sw(title: String, sub: String, v: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(title, style = body(15, FontWeight.SemiBold)); if (sub.isNotBlank()) Text(sub, style = body(12, color = Muted)) }
            Toggle(v, set)
        }
    }

    Screen("${Persona.name} пишет первым", back) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardBox {
                Sw("Доброе утро", "погода, кто писал ночью", morning) { morning = it; Prefs.initMorning = it }
                if (morning) Field("Во сколько (час)", mh, KeyboardType.Number) { mh = it; it.toIntOrNull()?.takeIf { h -> h in 5..12 }?.let { h -> Prefs.morningHour = h } }
                Sw("Важные сообщения", "сразу пишет, если кто-то ждёт ответа", important) { important = it; Prefs.initImportant = it }
                Sw("Ролики по интересам", "раз в день, по тому, что о тебе знает", yt) { yt = it; Prefs.ytPicks = it }
                Field("Итог дня в (час)", dh, KeyboardType.Number) { dh = it; it.toIntOrNull()?.takeIf { h -> h in 0..23 }?.let { h -> Prefs.digestHour = h } }
            }
            CardBox {
                Text("Просто поболтать", style = body(15, FontWeight.SemiBold))
                Text("«Привет, чем занят?», смешной ролик, вспомнит о чём-то из вашей переписки", style = body(12, color = Muted))
                Seg(listOf("0" to "Никогда", "1" to "Редко", "2" to "Иногда", "3" to "Часто"), chatty.toString()) { chatty = it.toInt(); Prefs.chatty = chatty }
            }
            CardBox {
                Sw("Голосовые от ${Persona.name}", "отвечает голосом на твои голосовые и иногда сам — до 3 за разговор", voice) { voice = it; Prefs.voiceReplies = it }
            }
            CardBox {
                Text("Не беспокоить", style = body(15, FontWeight.SemiBold))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("с (час)", qf, KeyboardType.Number) { qf = it; it.toIntOrNull()?.takeIf { h -> h in 0..23 }?.let { h -> Prefs.quietFrom = h } } }
                    Box(Modifier.weight(1f)) { Field("до (час)", qt, KeyboardType.Number) { qt = it; it.toIntOrNull()?.takeIf { h -> h in 0..23 }?.let { h -> Prefs.quietTo = h } } }
                }
                Text("В это время пишет только по-настоящему срочное.", style = body(12, color = Muted))
            }
        }
    }
}

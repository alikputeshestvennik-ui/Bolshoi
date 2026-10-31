package ru.alim.agent

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Что сейчас идёт: тяжёлая задача, поручения, слежка. 0 — кнопки «Активные» нет. */
object Activity {
    fun heavy(): Boolean = Agent.main.busy
    fun delegs(): List<Deleg.Item> = Deleg.items.filter { it.active || it.planned || it.status == "ask" }
    fun watches(): List<Watch.Item> = Watch.items.filter { it.status == "active" }
    fun count(): Int = (if (heavy()) 1 else 0) + delegs().size + watches().size
}

/** Кнопка над полем ввода — только когда что-то идёт; дышит, как «думающий» ИИ. */
@Composable
fun ActiveButton(go: (Scr) -> Unit) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val n = remember(tick, Agent.main.busy, Deleg.items.size, Watch.items.size) { Activity.count() }
    if (n == 0) return
    val inf = rememberInfiniteTransition(label = "halo")
    val halo by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "halo")
    val acc = Teal
    val soft = AccentSoft
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.drawBehind {
                val g = (6.dp.toPx()) * halo
                drawRoundRect(soft, topLeft = androidx.compose.ui.geometry.Offset(-g, -g),
                    size = androidx.compose.ui.geometry.Size(size.width + 2 * g, size.height + 2 * g), cornerRadius = CornerRadius(size.height / 2 + g))
            }
            .height(40.dp).clip(RoundedCornerShape(20.dp)).background(Card).border(1.5.dp, acc, RoundedCornerShape(20.dp))
            .clickable(onClickLabel = "Активные") { go(Scr.Active) }.padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ThinkDots(acc)
            Spacer(Modifier.width(10.dp))
            Text("Активные · $n", style = body(13, FontWeight.Bold, acc))
        }
    }
}

/** Страница «Активные»: блоки появляются, только если такой процесс сейчас идёт. */
@Composable
fun ActiveScreen(go: (Scr) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val core = Agent.main
    val t = tick
    val delegs = remember(t, Deleg.items.size) { Activity.delegs() }
    val watches = remember(t, Watch.items.size) { Activity.watches() }
    val total = (if (core.busy) 1 else 0) + delegs.size + watches.size
    Column(Modifier.fillMaxSize().background(Th.c.bg)) {
        Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Активные", style = TextStyle(fontFamily = Fonts.display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink), modifier = Modifier.weight(1f))
            Text(if (total == 0) "ничего не идёт" else "$total ${proc(total)}", style = body(12, color = Muted))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = LocalBarPad.current).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (core.busy || core.pending != null) Block("Тяжёлая задача", 1) {
                core.pending?.let { ConfirmCard(it, core) }
                if (core.busy) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).border(1.5.dp, Teal, RoundedCornerShape(20.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThinkDots(); Spacer(Modifier.width(10.dp))
                        Text(core.task.ifBlank { "Задача" }, style = body(15, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val s = ((System.currentTimeMillis() - core.startedAt) / 1000).coerceAtLeast(0) + t * 0
                        Text("%d:%02d".format(s / 60, s % 60), style = body(12, color = Muted))
                    }
                    val last = core.steps.lastOrNull()
                    Text(last?.think?.ifBlank { last.text } ?: "разбираюсь, с чего начать…", style = body(13, color = Muted), lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillBtn("Смотреть, как думает", true, Modifier.weight(1f)) { go(Scr.Home) }
                        PillBtn("Стоп", false, Modifier) { core.stop() }
                    }
                }
            }
            if (delegs.isNotEmpty()) Block("Поручения", delegs.size) {
                delegs.forEach { d ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, if (d.status == "ask") Amber.copy(alpha = 0.6f) else Line, RoundedCornerShape(18.dp))
                        .clickable { ChatJump.to(ChatJump.find(Chat.main, d.who)); go(Scr.Home) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Th.c.bg).border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                                Text(d.who.take(1).uppercase(), style = body(14, FontWeight.Bold, Ink.copy(alpha = 0.8f)))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.who, style = body(14, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(d.goal, style = body(12, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (d.status == "ask") {
                            Text("Нужен твой ответ", style = body(12, FontWeight.SemiBold, Amber))
                            Text(d.question, style = body(13, color = Ink.copy(alpha = 0.86f)), lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Th.c.bg).padding(horizontal = 10.dp, vertical = 8.dp))
                            var sent by remember(d.id, d.question) { mutableStateOf<String?>(null) }
                            if (sent != null) Text(sent!!, style = body(13, color = Muted))
                            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (d.refer.isNotBlank()) {
                                    PillBtn("Да", true, Modifier.weight(1f)) { sent = "↪ " + Deleg.referAnswer(ctx, d.id, "да") }
                                    PillBtn("Нет", false, Modifier.weight(1f)) { sent = "↪ " + Deleg.referAnswer(ctx, d.id, "нет") }
                                } else {
                                    PillBtn("Ответить", true, Modifier.weight(1f)) { ChatJump.to(ChatJump.find(Chat.main, d.who)); go(Scr.Home) }
                                    PillBtn("Заверши", false, Modifier.weight(1f)) { sent = "↪ " + Deleg.finish(ctx, d.id) }
                                }
                            }
                        } else Text(Deleg.stateText(d), style = body(12, FontWeight.SemiBold, Muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (watches.isNotEmpty()) Block("Слежка", watches.size) {
                watches.forEach { w ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(18.dp))
                        .clickable { ChatJump.to(ChatJump.find(Chat.main, w.topic)); go(Scr.Home) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icEye), null, tint = Teal, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.topic, style = body(14, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (w.mode == "feed") "лента канала" else "проверяю") + " · до " + Deleg.whenText(w.until).substringAfter(" в "), style = body(12, color = Muted))
                            Text(w.state.ifBlank { if (w.mode == "feed") "смотрю ленту" else "первая проверка" }, style = body(12, color = Ink.copy(alpha = 0.86f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (total == 0 && core.pending == null) Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Сейчас ничего не идёт", style = TextStyle(fontFamily = Fonts.display, fontSize = 17.sp, color = Ink))
                PillBtn("В чат", true, Modifier) { go(Scr.Home) }
            }
        }
    }
}

private val icEye by lazy { lineIco("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6") }
private fun proc(n: Int) = if (n % 10 == 1 && n % 100 != 11) "процесс" else if (n % 10 in 2..4 && n % 100 !in 12..14) "процесса" else "процессов"

@Composable
private fun Block(title: String, n: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = body(11, FontWeight.Bold, Muted).copy(letterSpacing = 1.4.sp))
            Spacer(Modifier.width(8.dp))
            Text("$n", style = body(11, FontWeight.Bold, Teal), modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AccentSoft).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        content()
    }
}

@Composable
fun PillBtn(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.height(44.dp).clip(shape).then(if (primary) Modifier.background(Teal) else Modifier.border(1.dp, Line, shape)).clickable(onClick = onClick)
        .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = body(13, FontWeight.Bold, if (primary) OnAccent else Ink))
    }
}

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal fun lineIco(name: String, path: String, w: Float = 1.9f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    addPath(androidx.compose.ui.graphics.vector.addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = w,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
}.build()

/** «Поделиться»: стрелка поднимается от основания и поворачивает направо — без рамки. */
internal val icShareUp by lazy { lineIco("share", "M5 21v-7a5 5 0 0 1 5-5h10M15 4l5 5-5 5", 2f) }
private val icBulb by lazy { lineIco("bulb", "M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.8.7 1 1.5 1 2.5h6c0-1 .2-1.8 1-2.5A6 6 0 0 0 12 3z") }
private val icCheck by lazy { lineIco("check", "M5 12l5 5 9-10", 2.5f) }
private val icToolSearch by lazy { lineIco("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14M20 20l-4-4") }
private val icToolPlane by lazy { lineIco("plane", "M2 16l20-6-3-2-7 2-6-5-2 1 4 6-4 1-2-1-1 1z") }
private val icToolRead by lazy { lineIco("read", "M4 5h7a3 3 0 0 1 3 3v12a2 2 0 0 0-2-2H4zM20 5h-6M20 5v13h-6") }
private val icToolFile by lazy { lineIco("file", "M6 3h9l4 4v14H6zM14 3v5h5M9 13h7M9 17h7") }
private val icToolGear by lazy { lineIco("tool", "M14 7l3-3 3 3-3 3M3 21l9-9M12 12l3 3") }

/** Иконка плашки по тексту шага (сам инструмент в шаге не хранится). */
private fun stepIcon(s: Agent.Step): ImageVector {
    val t = (s.text + " " + s.detail.take(80)).lowercase()
    return when {
        Regex("рейс|авиа|перел|самол").containsMatchIn(t) -> icToolPlane
        Regex("табли|файл|xlsx|документ").containsMatchIn(t) -> icToolFile
        Regex("чита|страниц|сайт|https?://").containsMatchIn(t) -> icToolRead
        Regex("ищу|поиск|найд|ищем").containsMatchIn(t) -> icToolSearch
        else -> icToolGear
    }
}

/**
 * Окно мыслей прямо в чате, пока идёт задача: прозрачный бабл ≈1/5 экрана, новое появляется снизу, старое уходит вверх и тает —
 * последнее всегда видно, листать не нужно. Мысли — серым, шаги — плашками, последняя мысль печатается с курсором.
 */
@Composable
fun ThinkPanel(core: Agent.Core) {
    // окно втрое меньше прежнего; прозрачный бабл вырастает из центра своей области
    val h = (LocalConfiguration.current.screenHeightDp * 0.21f).dp
    val born = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { born.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 260f)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val secs = ((now - core.startedAt) / 1000).coerceAtLeast(0)
    val inf = rememberInfiniteTransition(label = "think")
    val caret by inf.animateFloat(1f, 0f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "caret")
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThinkDots()
            Spacer(Modifier.width(10.dp))
            Text("думаю · шаг ${core.steps.size.coerceAtLeast(1)} · %d:%02d".format(secs / 60, secs % 60), style = body(12, FontWeight.SemiBold, Teal), modifier = Modifier.weight(1f))
            Text("стоп", style = body(13, FontWeight.SemiBold, Muted), modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .border(1.dp, Line, RoundedCornerShape(14.dp)).clickable(onClickLabel = "Остановить") { core.stop() }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        // старое уходит вверх и тает — прозрачностью, а не плашкой цвета фона (фон у нас с узором)
        Box(Modifier.fillMaxWidth().height(h)
            .graphicsLayer { val v = born.value; scaleX = 0.55f + 0.45f * v; scaleY = 0.25f + 0.75f * v; alpha = v.coerceIn(0f, 1f) }
            .clip(RoundedCornerShape(22.dp)).background(Th.c.card.copy(alpha = if (Th.c.dark) 0.30f else 0.42f))
            .border(1.dp, Th.c.line.copy(alpha = 0.7f), RoundedCornerShape(22.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Box(Modifier.fillMaxSize().clipToBounds()
            .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.22f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            }) {
            // unbounded: длинная лента не обрезается снизу — низ (самое новое) всегда виден, лишнее уходит за верх
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val list = core.steps.toList()
                list.forEachIndexed { i, s ->
                    val last = i == list.lastIndex
                    if (s.think.isNotBlank()) Row {
                        Icon(rememberVectorPainter(icBulb), null, tint = if (last) Teal else Muted, modifier = Modifier.padding(top = 1.dp).size(13.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(buildAnnotatedString {
                            append(s.think)
                            if (last && !s.done) withStyle(SpanStyle(color = Teal.copy(alpha = caret), fontWeight = FontWeight.Bold)) { append(" ▍") }
                        }, style = body(11, color = if (last) Ink.copy(alpha = 0.86f) else Muted), lineHeight = 16.sp)
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card.copy(alpha = 0.75f)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) {
                            Icon(rememberVectorPainter(stepIcon(s)), null, tint = Teal, modifier = Modifier.size(12.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.text.replaceFirstChar { it.uppercase() }, style = body(12, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = s.detail.lines().firstOrNull { it.isNotBlank() }.orEmpty()
                            if (sub.isNotBlank()) Text(sub, style = body(11, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (s.done) Icon(rememberVectorPainter(icCheck), null, tint = Th.c.online, modifier = Modifier.size(16.dp))
                        else Box(Modifier.size(8.dp).alpha(caret).clip(CircleShape).background(Teal))
                    }
                }
                if (list.isEmpty()) Text("Разбираюсь, с чего начать…", style = body(11, color = Muted))
            }
        }
        }
    }
}

/** Три пульсирующие точки — «ИИ думает». */
@Composable
fun ThinkDots(color: Color = Teal) {
    val inf = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(6.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}

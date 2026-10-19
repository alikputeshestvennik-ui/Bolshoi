package ru.alim.agent

import android.content.res.AssetManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Цвета из макетов
val Bg = Color(0xFF0E1116)
val Card = Color(0xFF151A21)
val Line = Color(0xFF262C36)
val Ink = Color(0xFFE8EAED)
val Muted = Color(0xFF9AA3AE)
val Teal = Color(0xFF3DD9C1)
val Violet = Color(0xFF8B7CFF)
val Amber = Color(0xFFF5B83D)
val Danger = Color(0xFFFF8A80)

/** Четыре слоя стопки снизу вверх и цвет сканера — одна палитра на состояние. */
class Palette(val layers: List<Color>, val accent: Color)
val Idle = Palette(listOf(Color(0xE612506A), Color(0xD91B7F86), Color(0xBF3DD9C1), Color(0x99DFFFF9)), Teal)
val Work = Palette(listOf(Color(0xE62E2875), Color(0xD94B3FB3), Color(0xBF8B7CFF), Color(0x99EEE9FF)), Violet)
val Ask = Palette(listOf(Color(0xE66A3A12), Color(0xD9A9651C), Color(0xBFF5B83D), Color(0x99FFF5DA)), Amber)

object Fonts {
    var body: FontFamily = FontFamily.SansSerif; private set
    var display: FontFamily = FontFamily.SansSerif; private set

    /** Шрифты кладёт сборка в assets/fonts; нет файла — остаётся системный. */
    @OptIn(ExperimentalTextApi::class)
    fun load(am: AssetManager) {
        val have = runCatching { am.list("fonts")?.toSet() }.getOrNull() ?: emptySet()
        runCatching {
            if ("manrope.ttf" in have) body = FontFamily(
                Font("fonts/manrope.ttf", am, FontWeight.Normal),
                Font("fonts/manrope.ttf", am, FontWeight.Medium),
                Font("fonts/manrope.ttf", am, FontWeight.SemiBold),
                Font("fonts/manrope.ttf", am, FontWeight.Bold),
            )
        }
        runCatching {
            if ("unbounded.ttf" in have) display = FontFamily(Font("fonts/unbounded.ttf", am, FontWeight.SemiBold))
        }
    }
}

/** Неровный «живой» слой в перспективе: окружность с волной радиуса, повёрнута и сплющена. */
private fun blob(cx: Float, cy: Float, r: Float, seed: Int): Path {
    val p = Path()
    val n = 64
    val a = (38.0 * PI / 180.0)
    for (k in 0..n) {
        val th = 2 * PI * k / n
        val rr = r * (1 + 0.07 * sin(3 * th + seed * 1.3) + 0.05 * cos(2 * th + seed * 2.1))
        val x0 = rr * cos(th); val y0 = rr * sin(th)
        val xr = x0 * cos(a) - y0 * sin(a)
        val yr = x0 * sin(a) + y0 * cos(a)
        val x = (cx + xr).toFloat(); val y = (cy + yr * 0.47).toFloat()
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/** Стопка из четырёх светящихся слоёв, покачивается волной. level — громкость голоса: слои расходятся. */
@Composable
fun LayerStack(p: Palette, periodMs: Int, size: Dp, level: Float = 0f, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "stack")
    val t by tr.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "t")
    val lift by animateFloatAsState(level, tween(120), label = "lift")
    val c0 by animateColorAsState(p.layers[0], tween(600), label = "c0")
    val c1 by animateColorAsState(p.layers[1], tween(600), label = "c1")
    val c2 by animateColorAsState(p.layers[2], tween(600), label = "c2")
    val c3 by animateColorAsState(p.layers[3], tween(600), label = "c3")
    val glow by animateColorAsState(p.accent, tween(600), label = "glow")
    val cols = listOf(c0, c1, c2, c3)
    Canvas(modifier.size(size)) {
        val u = this.size.width / 160f
        // тень под стопкой
        drawOval(Brush.radialGradient(listOf(glow.copy(alpha = 0.28f), Color.Transparent),
            center = Offset(80 * u, 148 * u), radius = 48 * u),
            topLeft = Offset(32 * u, 140 * u), size = Size(96 * u, 16 * u))
        for (i in 0 until 4) {
            val wave = 0.5f + 0.5f * sin(t - i * 0.6f)
            val dy = -(10f * wave + lift * 7f * i) * u
            val path = blob(80 * u, (116 - 18 * i) * u + dy, 50 * u, i)
            for (g in 3 downTo 1) drawPath(path, glow.copy(alpha = 0.05f + 0.02f * i), style = Stroke(width = g * 7 * u))
            drawPath(path, cols[i])
            drawPath(path, Color.White.copy(alpha = 0.25f + 0.15f * i), style = Stroke(width = 1.3f * u))
        }
    }
}

/** Световая полоса: огонёк со шлейфом бегает туда-обратно по сегментам. */
@Composable
fun Scanner(color: Color, periodMs: Int, modifier: Modifier = Modifier) {
    val c by animateColorAsState(color, tween(600), label = "scan")
    val tr = rememberInfiniteTransition(label = "scanner")
    val x by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "x")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val pos = -0.05f * w + x * 1.1f * w
        val dot = h * 5f
        // свечение вокруг полосы
        drawOval(Brush.radialGradient(listOf(c.copy(alpha = 0.35f), Color.Transparent), center = Offset(pos, h / 2), radius = dot * 1.6f),
            topLeft = Offset(pos - dot * 1.6f, h / 2 - h * 2f), size = Size(dot * 3.2f, h * 4f))
        val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(h / 2, h / 2))) }
        drawPath(shape, Color(0xFF07080B))
        clipPath(shape) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = 0.5f), Color.Transparent),
                startX = pos - dot * 2.4f, endX = pos + dot * 2.4f), size = Size(w, h))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c, Color.White, c, Color.Transparent),
                startX = pos - dot, endX = pos + dot), size = Size(w, h))
            val seg = h * 1.8f
            var sx = seg
            while (sx < w) { drawRect(Color(0xFF07080B), Offset(sx, 0f), Size(h * 0.2f, h)); sx += seg }
        }
        drawPath(shape, Color(0xFF222831), style = Stroke(width = 1f))
    }
}

/** Значок микрофона (в базовом наборе иконок его нет). */
@Composable
fun MicIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width; val sw = s * 0.085f
        drawRoundRect(color, Offset(s * 0.36f, s * 0.1f), Size(s * 0.28f, s * 0.48f), CornerRadius(s * 0.14f), style = Stroke(sw))
        drawArc(color, 0f, 180f, false, Offset(s * 0.2f, s * 0.22f), Size(s * 0.6f, s * 0.5f), style = Stroke(sw, cap = StrokeCap.Round))
        drawLine(color, Offset(s / 2, s * 0.72f), Offset(s / 2, s * 0.9f), sw, StrokeCap.Round)
    }
}

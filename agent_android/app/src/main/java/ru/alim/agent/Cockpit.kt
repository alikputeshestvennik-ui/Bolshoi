package ru.alim.agent

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Тихие тактильные отклики кабины: отрыв, запрос капитану, посадка. Только вибро, без звука. */
object Haptics {
    private fun vib(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private fun wave(ctx: Context, timings: LongArray, amps: IntArray) {
        if (!Prefs.haptics) return
        val v = vib(ctx) ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, amps, -1)) }
    }
    private fun one(ctx: Context, ms: Long, amp: Int) {
        if (!Prefs.haptics) return
        val v = vib(ctx) ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, amp)) }
    }

    /** Отрыв — чёткий щелчок тумблера: передал управление. */
    fun takeoff(ctx: Context) = one(ctx, 45, 255)
    /** Запрос капитану — мягкая двойная нотка. */
    fun ask(ctx: Context) = wave(ctx, longArrayOf(0, 35, 110, 45), intArrayOf(0, 200, 0, 255))
    /** Посадка — нарастающая отдача и касание земли. */
    fun landing(ctx: Context) = wave(ctx, longArrayOf(0, 30, 50, 70), intArrayOf(0, 140, 0, 255))
    /** Лёгкий тик для мелких подтверждений. */
    fun tick(ctx: Context) = one(ctx, 20, 160)
}

/**
 * Со-пилот: светлый стеклянный шарик с тёмным визором и светящимися глазами-дужками.
 * state: 0 — спокоен/готово (улыбается), 1 — в полёте (глаза сканируют), 2 — просит капитана (тёплые круглые глаза).
 */
@Composable
fun CopilotOrb(state: Int, size: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val scan by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "scan")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    val blinkT by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "blink")
    val eye = when (state) { 2 -> Color(0xFFFFC98A); else -> Color(0xFF8FEAFF) }
    val glow = when (state) { 1 -> Sky.glowWork; 2 -> Sky.glowAsk; else -> Sky.glowCalm }
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension * 0.36f
        // мягкое свечение вокруг
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f + 0.2f * breath), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
        // стеклянная сфера
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF4F8FD), Color(0xFFB9CCE2), Color(0xFF6E89AA)),
            Offset(c.x - r * 0.35f, c.y - r * 0.4f), r * 1.5f), r, c)
        // блик
        drawCircle(Color.White.copy(alpha = 0.55f), r * 0.16f, Offset(c.x - r * 0.42f, c.y - r * 0.48f))
        // визор
        val vw = r * 1.42f; val vh = r * 0.86f
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF14233A), Color(0xFF070D18)), c.y - vh / 2, c.y + vh / 2),
            Offset(c.x - vw / 2, c.y - vh / 2 + r * 0.06f), androidx.compose.ui.geometry.Size(vw, vh),
            androidx.compose.ui.geometry.CornerRadius(vh / 2, vh / 2))
        // глаза
        val dx = if (state == 1) scan * r * 0.12f else 0f
        val blink = if (blinkT > 0.96f) 0.15f else 1f
        val ey = c.y + r * 0.06f
        val ex = r * 0.34f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        for (s in listOf(-1f, 1f)) {
            val cx = c.x + s * ex + dx
            if (state == 2) {
                drawCircle(eye, r * 0.1f * blink.coerceAtLeast(0.4f), Offset(cx, ey))
            } else {
                // дужка-улыбка «^»
                val w = r * 0.3f; val h = r * 0.26f * blink
                drawArc(eye, 200f, 140f, false, Offset(cx - w / 2, ey - h / 2), androidx.compose.ui.geometry.Size(w, h.coerceAtLeast(1f)), style = stroke)
            }
            drawCircle(eye.copy(alpha = 0.18f), r * 0.22f, Offset(cx, ey)) // свечение глаза
        }
    }
}

/** Палитра кабины — рассвет над морем. Не зависит от темы чата: задача всегда «в кабине». */
object Sky {
    val top = Color(0xFF0B1422)      // предрассветное небо
    val mid = Color(0xFF173149)      // синь к горизонту
    val glowCalm = Color(0xFF3E6D92) // спокойный горизонт
    val glowWork = Color(0xFF4A5BB0) // в работе — холоднее/фиолетовее
    val glowAsk = Color(0xFFB9803E)  // запрос — тёплый янтарь
    val sea = Color(0xFF0A1420)      // море под горизонтом
    val ink = Color(0xFFEAF2FB)      // текст на кабине
    val inkMuted = Color(0xFF9DB2C8)
}

/** Фон кабины: небо, полоса горизонта по состоянию, море. В полёте небо «летит»; при посадке штрихи тормозят, горизонт вспыхивает (flash 0..1). */
@Composable
fun CockpitSky(glow: Color, modifier: Modifier = Modifier, working: Boolean = false, flash: Float = 0f) {
    val t = rememberInfiniteTransition(label = "sky")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    val fly by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "fly")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    // скорость: плавно разгоняется при отрыве и тормозит при посадке
    val speed by androidx.compose.animation.core.animateFloatAsState(if (working) 1f else 0f, tween(if (working) 700 else 1400, easing = EaseOutCubic), label = "speed")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val horizon = h * (0.6f + 0.02f * shift)
        val glowA = (0.32f + 0.25f * pulse * speed + 0.6f * flash).coerceAtMost(1f)
        drawRect(Brush.verticalGradient(0f to Sky.top, 0.55f to Sky.mid, 1f to Sky.mid, endY = horizon), size = androidx.compose.ui.geometry.Size(w, horizon))
        if (speed > 0.01f) {
            val streaks = 9
            for (i in 0 until streaks) {
                val p = (fly + i.toFloat() / streaks) % 1f
                val y = h * 0.08f + (horizon - h * 0.08f) * p
                val a = (1f - p) * 0.3f * speed
                val half = w * (0.1f + 0.32f * p)
                val cx = w * (0.18f + 0.64f * ((i * 0.37f) % 1f))
                drawRect(glow.copy(alpha = a), topLeft = Offset(cx - half, y), size = androidx.compose.ui.geometry.Size(half * 2, 1.8f))
            }
        }
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to glow.copy(alpha = glowA), startY = horizon - h * 0.14f, endY = horizon),
            topLeft = Offset(0f, horizon - h * 0.14f), size = androidx.compose.ui.geometry.Size(w, h * 0.14f))
        drawRect(Brush.verticalGradient(0f to glow.copy(alpha = 0.12f + 0.3f * flash), 0.2f to Sky.sea, 1f to Sky.top, startY = horizon, endY = h),
            topLeft = Offset(0f, horizon), size = androidx.compose.ui.geometry.Size(w, h - horizon))
        drawRect(glow.copy(alpha = (0.55f + 0.3f * pulse * speed + 0.45f * flash).coerceAtMost(1f)), topLeft = Offset(0f, horizon - 1.2f - 2f * flash),
            size = androidx.compose.ui.geometry.Size(w, 2.4f + 4f * flash))
    }
}

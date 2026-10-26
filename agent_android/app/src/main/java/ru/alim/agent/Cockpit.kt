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

/** Фон кабины: небо, тёплая полоса горизонта по состоянию, море. В полёте небо «летит» и горизонт пульсирует. */
@Composable
fun CockpitSky(glow: Color, modifier: Modifier = Modifier, working: Boolean = false) {
    val t = rememberInfiniteTransition(label = "sky")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    val fly by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "fly")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val horizon = h * (0.58f + 0.03f * shift)
        val glowA = if (working) 0.55f + 0.35f * pulse else 0.55f
        drawRect(Brush.verticalGradient(0f to Sky.top, 0.5f to Sky.mid, 1f to Sky.mid, endY = horizon), size = androidx.compose.ui.geometry.Size(w, horizon))
        // «полёт»: световые штрихи уходят к горизонту (ощущение движения вперёд)
        if (working) {
            val streaks = 7
            for (i in 0 until streaks) {
                val p = (fly + i.toFloat() / streaks) % 1f
                val y = h * 0.1f + (horizon - h * 0.1f) * p       // сверху вниз, к горизонту
                val a = (1f - p) * 0.22f
                val half = w * (0.12f + 0.3f * p)                  // к горизонту штрихи длиннее
                val cx = w * (if (i % 2 == 0) 0.32f else 0.7f)
                drawRect(glow.copy(alpha = a), topLeft = Offset(cx - half, y), size = androidx.compose.ui.geometry.Size(half * 2, 1.6f))
            }
        }
        // тёплая/холодная полоса у самого горизонта
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to glow.copy(alpha = glowA), startY = horizon - h * 0.16f, endY = horizon),
            topLeft = Offset(0f, horizon - h * 0.16f), size = androidx.compose.ui.geometry.Size(w, h * 0.16f))
        // море
        drawRect(Brush.verticalGradient(0f to glow.copy(alpha = 0.18f), 0.25f to Sky.sea, 1f to Sky.top, startY = horizon, endY = h),
            topLeft = Offset(0f, horizon), size = androidx.compose.ui.geometry.Size(w, h - horizon))
        // линия горизонта — тонкий свет, в полёте ярче пульсом
        drawRect(glow.copy(alpha = if (working) 0.7f + 0.3f * pulse else 0.8f), topLeft = Offset(0f, horizon - 1.2f), size = androidx.compose.ui.geometry.Size(w, 2.4f))
    }
}

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

    /** Отрыв — короткий щелчок тумблера: передал управление. */
    fun takeoff(ctx: Context) = one(ctx, 18, 150)
    /** Запрос капитану — мягкая двойная нотка. */
    fun ask(ctx: Context) = wave(ctx, longArrayOf(0, 16, 90, 16), intArrayOf(0, 120, 0, 160))
    /** Посадка — нарастающая отдача и мягкое касание. */
    fun landing(ctx: Context) = wave(ctx, longArrayOf(0, 22, 40, 34), intArrayOf(0, 90, 0, 190))
    /** Лёгкий тик для мелких подтверждений. */
    fun tick(ctx: Context) = one(ctx, 12, 90)
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

/** Фон кабины: небо сверху, тёплая полоса горизонта по состоянию, море снизу — тихо дышит. */
@Composable
fun CockpitSky(glow: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "sky")
    val shift by t.animateFloat(0f, 1f,
        infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    Canvas(modifier) {
        val h = size.height
        val horizon = h * (0.58f + 0.03f * shift)
        drawRect(Brush.verticalGradient(0f to Sky.top, 0.5f to Sky.mid, 1f to Sky.mid, endY = horizon), size = androidx.compose.ui.geometry.Size(size.width, horizon))
        // тёплая/холодная полоса у самого горизонта
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to glow.copy(alpha = 0.55f), startY = horizon - h * 0.16f, endY = horizon),
            topLeft = Offset(0f, horizon - h * 0.16f), size = androidx.compose.ui.geometry.Size(size.width, h * 0.16f))
        // море
        drawRect(Brush.verticalGradient(0f to glow.copy(alpha = 0.18f), 0.25f to Sky.sea, 1f to Sky.top, startY = horizon, endY = h),
            topLeft = Offset(0f, horizon), size = androidx.compose.ui.geometry.Size(size.width, h - horizon))
        // сама линия горизонта — тонкий свет
        drawRect(glow.copy(alpha = 0.8f), topLeft = Offset(0f, horizon - 1.2f), size = androidx.compose.ui.geometry.Size(size.width, 2.4f))
    }
}

package ru.alim.agent

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Погода для неба кабины: облачность, дождь, день/ночь. Open-Meteo, бесплатно, не чаще раза в час. */
object SkyWeather {
    data class W(val cloud: Int, val rain: Boolean, val day: Boolean)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    var now by mutableStateOf(W(35, false, clockDay()))
        private set

    private fun clockDay(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 7..18

    fun load() {
        runCatching {
            val j = JSONObject(Prefs.skyWx)
            now = W(j.optInt("c", 35), j.optBoolean("r"), if (System.currentTimeMillis() - j.optLong("t") < 3 * 3600_000L) j.optBoolean("d", clockDay()) else clockDay())
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        load()
        val old = runCatching { JSONObject(Prefs.skyWx).optLong("t") }.getOrDefault(0L)
        if (System.currentTimeMillis() - old < 3600_000L) return@withContext
        runCatching {
            val city = Prefs.homeCity.ifBlank { "Ялта" }
            val g = http.newCall(Request.Builder().url("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" +
                URLEncoder.encode(city, "UTF-8")).build()).execute().use { JSONObject(it.body?.string().orEmpty()) }
            val p = g.optJSONArray("results")?.optJSONObject(0) ?: return@runCatching
            val f = http.newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=${p.optDouble("latitude")}&longitude=${p.optDouble("longitude")}" +
                "&current=cloud_cover,precipitation,is_day,weather_code").build()).execute().use { JSONObject(it.body?.string().orEmpty()) }
            val c = f.optJSONObject("current") ?: return@runCatching
            val code = c.optInt("weather_code")
            val rain = c.optDouble("precipitation") > 0.05 || code in 51..67 || code in 80..82 || code in 95..99
            val w = W(c.optInt("cloud_cover", 35), rain, c.optInt("is_day", 1) == 1)
            Prefs.skyWx = JSONObject().put("c", w.cloud).put("r", w.rain).put("d", w.day).put("t", System.currentTimeMillis()).toString()
            now = w
        }
        Unit
    }
}

/** Куда плывут облака на стоянке: новое случайное направление при каждом возвращении в приложение. */
object SkyDrift {
    var vx by mutableStateOf(0.6f); private set
    var vz by mutableStateOf(0.9f); private set
    fun reroll() {
        val a = Random.nextFloat() * 2f * PI.toFloat()
        vx = kotlin.math.cos(a) * 1.4f; vz = sin(a) * 1.4f
    }
}

private class Cloud(var x: Float, var y: Float, var z: Float, var r: Float, val seed: Int)

/** Состояние полёта, живёт между кадрами (не Compose-состояние — перерисовку двигает tick). */
private class FlightSim {
    enum class St { PARKED, TAKEOFF, CRUISE, LANDING }
    var st = St.PARKED; var t = 0f; var clock = 0f
    var s = 0f            // пройденный путь — двигает землю
    var speed = 0f
    var alt = 1.6f        // высота глаз над землёй
    var pitch = 0f        // наклон: >0 — нос вверх (горизонт ниже)
    var rw = -20f         // где начинается ВПП относительно нас (вперёд — плюс)
    var landQueued = false
    var startAlt = 40f
    var lifted = false; var touched = false
    var clouds = ArrayList<Cloud>()
    var cloudN = -1
    val stars = List(70) { Triple(Random.nextFloat(), Random.nextFloat() * 0.55f, Random.nextFloat()) }

    fun ensureClouds(n: Int) {
        if (n == cloudN) return
        cloudN = n
        val rnd = Random(42)
        clouds = ArrayList(List(n) { i ->
            val high = i % 4 == 3
            Cloud(rnd.nextFloat() * 320f - 160f, if (high) 70f + rnd.nextFloat() * 25f else 22f + rnd.nextFloat() * 8f,
                30f + rnd.nextFloat() * 580f, if (high) 14f + rnd.nextFloat() * 10f else 9f + rnd.nextFloat() * 9f, i)
        })
    }

    private fun ease(p: Float) = (p * p * (3 - 2 * p)).coerceIn(0f, 1f)

    /** onLift — отрыв от полосы, onTouch — касание при посадке. */
    fun step(dt: Float, flying: Boolean, onLift: () -> Unit, onTouch: () -> Unit) {
        clock += dt; t += dt
        when (st) {
            St.PARKED -> {
                speed = 0f; alt = 1.6f; pitch = 0f
                if (flying) { st = St.TAKEOFF; t = 0f; lifted = false; landQueued = false }
            }
            St.TAKEOFF -> {
                if (!flying) landQueued = true
                if (t < 2.8f) { speed = 72f * (t / 2.8f).pow(1.5f); alt = 1.6f; pitch = 0f }
                else {
                    if (!lifted) { lifted = true; onLift() }
                    val p = ((t - 2.8f) / 3.4f).coerceIn(0f, 1f)
                    speed = 72f; alt = 1.6f + 38.4f * ease(p); pitch = 0.09f * sin(PI.toFloat() * min(1f, p * 1.1f))
                }
                if (t >= 6.2f) { st = if (landQueued || !flying) St.LANDING else St.CRUISE; t = 0f; startAlt = alt; if (st == St.LANDING) beginLanding() }
            }
            St.CRUISE -> {
                speed = 72f; alt = 40f + 1.4f * sin(clock * 0.45f); pitch = 0.012f * sin(clock * 0.7f)
                if (!flying) { st = St.LANDING; t = 0f; startAlt = alt; beginLanding() }
            }
            St.LANDING -> {
                if (t < 4.2f) {
                    val p = t / 4.2f
                    speed = 72f - 28f * p
                    alt = startAlt + (1.6f - startAlt) * ease(p)
                    pitch = if (p < 0.8f) -0.035f * sin(PI.toFloat() * p / 0.8f) else 0.03f * sin(PI.toFloat() * (p - 0.8f) / 0.2f)
                } else {
                    if (!touched) { touched = true; onTouch() }
                    val p = ((t - 4.2f) / 2.8f).coerceIn(0f, 1f)
                    speed = 44f * (1f - p).pow(1.6f); alt = 1.6f; pitch = 0f
                    if (p >= 1f) { st = St.PARKED; t = 0f; speed = 0f }
                }
            }
        }
        s += speed * dt
        rw -= speed * dt
        val parked = st == St.PARKED
        for (c in clouds) {
            if (parked) { c.x += SkyDrift.vx * dt; c.z += SkyDrift.vz * dt } else c.z -= speed * dt
            if (c.z < 8f) { c.z += 600f; c.x = Random.nextFloat() * 320f - 160f }
            if (c.z > 640f) c.z -= 600f
            if (c.x > 170f) c.x -= 340f else if (c.x < -170f) c.x += 340f
        }
        // полоса ушла далеко назад — на стоянке «приезжаем» на свою (при посадке её ставит beginLanding)
        if (st == St.CRUISE && rw < -400f) rw = -400f
    }

    private fun beginLanding() {
        touched = false
        // путь до касания: ∫(72-28p) за 4.2 с ≈ 243 — ставим порог полосы ровно туда
        rw = 243f + 6f
    }
}

/**
 * Сцена кабины: небо, облака по погоде, земля в перспективе, ВПП.
 * flying=false — самолёт стоит на полосе, плывут облака; flying=true — разбег, отрыв, полёт; снова false — посадка.
 */
@Composable
fun FlightScene(flying: Boolean, glow: Color, modifier: Modifier = Modifier, flash: Float = 0f,
                onLift: () -> Unit = {}, onTouch: () -> Unit = {}) {
    val sim = remember { FlightSim() }
    var tick by remember { mutableIntStateOf(0) }
    val fly by rememberUpdatedState(flying)
    val lift by rememberUpdatedState(onLift)
    val touch by rememberUpdatedState(onTouch)
    val wx = SkyWeather.now
    LaunchedEffect(Unit) {
        var last = 0L; var acc = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                sim.step(dt, fly, lift, touch)
                // стоянка — 30 кадров/с (бережём батарею), в полёте — каждый кадр
                acc += dt
                if (sim.st != FlightSim.St.PARKED || acc >= 0.033f) { acc = 0f; tick++ }
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val k = tick
        sim.ensureClouds((3 + wx.cloud / 7).coerceIn(3, 17))
        drawFlight(sim, wx, glow, flash)
    }
}

private fun hash(a: Int, b: Int): Int { var h = a * 73856093 xor b * 19349663; h = h xor (h ushr 13); h *= 1274126177; return h xor (h ushr 16) }

private fun DrawScope.drawFlight(sim: FlightSim, wx: SkyWeather.W, glow: Color, flash: Float) {
    val w = size.width; val h = size.height
    val cx = w / 2f
    val f = min(w, h) * 0.95f
    val hy = h * 0.58f + sim.pitch * h
    val night = !wx.day
    val skyTop = if (night) Color(0xFF050A14) else Sky.top
    val skyMid = if (night) Color(0xFF0E1D33) else Sky.mid
    val glowA = (if (night) 0.28f else 0.42f) + 0.5f * flash

    // ——— небо ———
    drawRect(Brush.verticalGradient(0f to skyTop, 0.6f to skyMid, 1f to lerp(skyMid, glow, 0.35f), startY = 0f, endY = hy), size = Size(w, max(0f, hy)))
    if (night) for ((sx, sy, tw) in sim.stars) {
        val y = sy * hy; if (y > hy - 10f) continue
        val a = 0.35f + 0.45f * (0.5f + 0.5f * sin(sim.clock * (0.8f + tw * 1.7f) + tw * 9f))
        drawCircle(Color.White.copy(alpha = a), 1.1f + tw * 1.2f, Offset(sx * w, y))
    }
    drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to glow.copy(alpha = glowA), startY = hy - h * 0.12f, endY = hy),
        topLeft = Offset(0f, hy - h * 0.12f), size = Size(w, h * 0.12f))

    // ——— земля ———
    if (hy < h) {
        drawRect(Brush.verticalGradient(0f to lerp(Color(0xFF243B47), glow, 0.25f), 0.25f to Color(0xFF1A2E2A), 1f to Color(0xFF0C1A16), startY = hy, endY = h),
            topLeft = Offset(0f, hy), size = Size(w, h - hy))
        val alt = sim.alt
        val cell = 14f
        val path = Path()
        val k0 = floor((sim.s + 0.6f) / cell).toInt()
        val fields = if (night) listOf(0xFF0E1D18, 0xFF132320, 0xFF101A1F, 0xFF16241C, 0xFF0F1F24) else listOf(0xFF2E4A34, 0xFF3A5437, 0xFF4A5A38, 0xFF2C4540, 0xFF55603F, 0xFF34503F)
        // клетки полей от ближних к дальним
        for (row in 0 until 45) {
            val k = k0 + row
            val z1 = k * cell - sim.s; val z2 = z1 + cell
            if (z2 <= 0.6f) continue
            val za = max(z1, 0.6f)
            val y1 = hy + f * alt / za; val y2 = hy + f * alt / z2
            if (y1 - y2 < 0.7f) break
            if (y2 > h) continue
            val cols = 9
            for (c in -cols until cols) {
                val x1 = c * cell; val x2 = x1 + cell
                val hc = hash(k, c)
                val col = Color(fields[(hc and 0x7fffffff) % fields.size])
                path.reset()
                path.moveTo(cx + f * x1 / za, y1); path.lineTo(cx + f * x2 / za, y1)
                path.lineTo(cx + f * x2 / z2, y2); path.lineTo(cx + f * x1 / z2, y2); path.close()
                drawPath(path, col)
                // огоньки посёлков ночью / домики днём
                if ((hc ushr 8) % 9 == 0) {
                    val lx = cx + f * (x1 + cell * 0.5f) / ((za + z2) / 2f); val ly = (y1 + y2) / 2f
                    val rr = max(0.8f, f * 0.35f / ((za + z2) / 2f))
                    drawCircle(if (night) Color(0xFFFFD58A).copy(alpha = 0.85f) else Color(0xFFE9E2D0).copy(alpha = 0.7f), rr, Offset(lx, ly))
                }
            }
        }
        // дорога вдоль полёта
        run {
            val rx1 = 40f; val rx2 = 46f; val zn = 0.6f; val yn = hy + f * alt / zn
            path.reset(); path.moveTo(cx + f * rx1 / zn, min(yn, h * 4)); path.lineTo(cx + f * rx2 / zn, min(yn, h * 4)); path.lineTo(cx, hy); path.close()
            drawPath(path, Color(0xFF2B3138).copy(alpha = if (night) 0.9f else 0.8f))
        }
        // ——— ВПП ———
        drawRunway(sim, f, cx, hy, h, night)
        // дымка у горизонта
        drawRect(Brush.verticalGradient(0f to lerp(skyMid, glow, 0.45f).copy(alpha = 0.85f), 1f to Color.Transparent, startY = hy, endY = hy + (h - hy) * 0.35f),
            topLeft = Offset(0f, hy), size = Size(w, (h - hy) * 0.35f))
    }
    // линия горизонта
    drawRect(glow.copy(alpha = (0.45f + 0.5f * flash).coerceAtMost(1f)), topLeft = Offset(0f, hy - 1f - 2f * flash), size = Size(w, 2f + 4f * flash))

    // ——— облака ———
    drawClouds(sim, wx, f, cx, hy, w, h)
    if (wx.rain) {
        val n = 40
        for (i in 0 until n) {
            val x = ((i * 97 + sim.clock * 160f * (1 + i % 3)) % (w + 80f)) - 40f
            val y = ((i * 211 + sim.clock * 900f) % (h + 60f)) - 30f
            drawLine(Color(0xFFB8CBE0).copy(alpha = 0.18f), Offset(x, y), Offset(x - 6f, y + 22f), 1.4f)
        }
    }
}

private fun DrawScope.drawRunway(sim: FlightSim, f: Float, cx: Float, hy: Float, h: Float, night: Boolean) {
    val L = 170f; val half = 7f; val alt = sim.alt
    val zs = sim.rw; val ze = sim.rw + L
    if (ze <= 0.6f || zs > 1200f) return
    val z1 = max(zs, 0.6f)
    fun y(z: Float) = hy + f * alt / z
    fun x(X: Float, z: Float) = cx + f * X / z
    val p = Path()
    p.moveTo(x(-half, z1), y(z1)); p.lineTo(x(half, z1), y(z1)); p.lineTo(x(half, ze), y(ze)); p.lineTo(x(-half, ze), y(ze)); p.close()
    drawPath(p, Color(0xFF2A323D))
    // кромки
    for (side in listOf(-1f, 1f)) {
        val X = side * (half - 0.4f)
        drawLine(Color.White.copy(alpha = 0.75f), Offset(x(X, z1), y(z1)), Offset(x(X, ze), y(ze)), max(1f, f * 0.25f / max(z1, 4f)))
    }
    // осевая — прерывистая
    var z = floor((z1 - zs) / 12f) * 12f + zs
    while (z < ze) {
        val a = max(z, 0.6f); val b = min(z + 6f, ze)
        if (b > a) {
            val q = Path()
            q.moveTo(x(-0.25f, a), y(a)); q.lineTo(x(0.25f, a), y(a)); q.lineTo(x(0.25f, b), y(b)); q.lineTo(x(-0.25f, b), y(b)); q.close()
            drawPath(q, Color.White.copy(alpha = 0.85f))
        }
        z += 12f
    }
    // «зебра» порога
    if (zs + 4f > 0.6f) for (i in -5..5) {
        if (i == 0) continue
        val X = i * 1.15f; val a = max(zs + 1f, 0.6f); val b = zs + 5f
        if (b <= a) continue
        val q = Path()
        q.moveTo(x(X - 0.35f, a), y(a)); q.lineTo(x(X + 0.35f, a), y(a)); q.lineTo(x(X + 0.35f, b), y(b)); q.lineTo(x(X - 0.35f, b), y(b)); q.close()
        drawPath(q, Color.White.copy(alpha = 0.8f))
    }
    // огни вдоль полосы
    var lz = floor(max(zs, 0.6f) / 10f) * 10f
    while (lz < ze) {
        if (lz > 0.6f) for (side in listOf(-1f, 1f)) {
            val r = max(0.9f, f * (if (night) 0.32f else 0.2f) / lz)
            val c = if (night) Color(0xFFFFE6A8) else Color(0xFFFFF4D6)
            drawCircle(c.copy(alpha = if (night) 0.95f else 0.6f), r, Offset(x(side * (half + 0.8f), lz), y(lz)))
        }
        lz += 10f
    }
}

private fun DrawScope.drawClouds(sim: FlightSim, wx: SkyWeather.W, f: Float, cx: Float, hy: Float, w: Float, h: Float) {
    val base = when {
        wx.rain -> Color(0xFF6E7C8C)
        !wx.day -> Color(0xFF3A4C66)
        else -> Color(0xFFF2F6FB)
    }
    val alpha = if (wx.rain) 0.75f else if (!wx.day) 0.55f else 0.62f
    for (c in sim.clouds.sortedByDescending { it.z }) {
        val z = c.z
        val sx = cx + f * c.x / z
        val sy = hy - f * (c.y - sim.alt) / z
        val r = f * c.r / z
        if (sx < -r * 3 || sx > w + r * 3 || sy < -r * 3 || sy > h + r * 3) continue
        val near = ((60f - z) / 52f).coerceIn(0f, 1f)        // совсем близко — облако «обволакивает» и тает
        val a = alpha * (1f - near * 0.85f) * (0.55f + 0.45f * min(1f, z / 120f).let { 1f - it * 0.3f })
        val rnd = Random(c.seed)
        for (i in 0 until 5) {
            val ox = (rnd.nextFloat() - 0.5f) * 2.2f * r; val oy = (rnd.nextFloat() - 0.6f) * 0.7f * r
            val rr = r * (0.55f + rnd.nextFloat() * 0.6f)
            drawCircle(Brush.radialGradient(listOf(base.copy(alpha = a), base.copy(alpha = 0f)), Offset(sx + ox, sy + oy), rr), rr, Offset(sx + ox, sy + oy))
        }
    }
}

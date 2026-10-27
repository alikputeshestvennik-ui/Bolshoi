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
import androidx.compose.ui.graphics.toArgb
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
fun FlightScene2D(flying: Boolean, glow: Color, modifier: Modifier = Modifier, flash: Float = 0f,
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
        val alt = sim.alt
        val zn = 2f                                   // ближняя плоскость отсечения (мир)
        val baseNear = if (night) Color(0xFF0F2026) else Color(0xFF1F3A2A)
        val baseFar = if (night) lerp(Color(0xFF1B3346), glow, 0.25f) else lerp(Color(0xFF5E7A86), glow, 0.2f)
        drawRect(Brush.verticalGradient(0f to baseFar, 0.35f to lerp(baseFar, baseNear, 0.6f), 1f to baseNear, startY = hy, endY = h),
            topLeft = Offset(0f, hy), size = Size(w, h - hy))
        val cell = 14f; val gap = 0.7f
        val path = Path()
        val k0 = floor((sim.s + zn) / cell).toInt()
        val fields = if (night) listOf(0xFF1E3A3F, 0xFF24433C, 0xFF1C3446, 0xFF2A4638, 0xFF213D4A, 0xFF2D4A42)
                     else listOf(0xFF3F6B45, 0xFF4E7A47, 0xFF7A8450, 0xFF3A6458, 0xFF8C8656, 0xFF587F4C, 0xFF9C9460)
        val road = if (night) Color(0xFF141C26) else Color(0xFF3A3F45)
        fun quad(x1: Float, x2: Float, za: Float, zb: Float, col: Color) {
            if (zb <= zn) return
            val z1 = max(za, zn)
            val y1 = hy + f * alt / z1; val y2 = hy + f * alt / zb
            if (y2 > h + 2f || y1 - y2 < 0.4f) return
            path.reset()
            path.moveTo(cx + f * x1 / z1, y1); path.lineTo(cx + f * x2 / z1, y1)
            path.lineTo(cx + f * x2 / zb, y2); path.lineTo(cx + f * x1 / zb, y2); path.close()
            drawPath(path, col)
        }
        for (row in 0 until 48) {
            val k = k0 + row
            val z1 = k * cell - sim.s; val z2 = z1 + cell
            if (z2 <= zn) continue
            if (hy + f * alt / max(z1, zn) - (hy + f * alt / z2) < 0.6f) break
            val roadRow = Math.floorMod(k, 7) == 0
            for (c in -10 until 10) {
                val x1 = c * cell; val x2 = x1 + cell
                if (roadRow) { quad(x1, x2, z1 + 4f, z1 + 7f, road); continue }
                if (Math.floorMod(c, 6) == 2) { quad(x1 + 5f, x1 + 8f, z1, z2, road); continue }
                val hc = hash(k, c)
                quad(x1 + gap, x2 - gap, z1 + gap, z2 - gap, Color(fields[(hc and 0x7fffffff) % fields.size]))
                // посёлки: ночью тёплые огни, днём светлые крыши
                if ((hc ushr 9) % 7 == 0) for (j in 0 until 3) {
                    val lx0 = x1 + 3f + ((hc ushr (j * 3)) and 7) * 1.1f; val lz = z1 + 3f + ((hc ushr (j * 5 + 2)) and 7) * 1.1f
                    if (lz <= zn) continue
                    val lx = cx + f * lx0 / lz; val ly = hy + f * alt / lz
                    if (ly > h || lx < -10f || lx > w + 10f) continue
                    val rr = max(1.1f, f * 0.45f / lz)
                    if (night) {
                        drawCircle(Color(0xFFFFC46B).copy(alpha = 0.25f), rr * 3.2f, Offset(lx, ly))
                        drawCircle(Color(0xFFFFE2A8), rr, Offset(lx, ly))
                    } else drawCircle(Color(0xFFEDE6D6).copy(alpha = 0.85f), rr * 1.2f, Offset(lx, ly))
                }
            }
        }
        // ——— ВПП ———
        drawRunway(sim, f, cx, hy, h, w, night)
        // лёгкая дымка у самого горизонта
        drawRect(Brush.verticalGradient(0f to lerp(skyMid, glow, 0.4f).copy(alpha = 0.6f), 1f to Color.Transparent, startY = hy, endY = hy + (h - hy) * 0.12f),
            topLeft = Offset(0f, hy), size = Size(w, (h - hy) * 0.12f))
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

private fun DrawScope.drawRunway(sim: FlightSim, f: Float, cx: Float, hy: Float, h: Float, w: Float, night: Boolean) {
    val L = 170f; val half = 7f; val alt = sim.alt; val zn = 2f
    val zs = sim.rw; val ze = sim.rw + L
    if (ze <= zn || zs > 1400f) return
    val p = Path()
    fun quad(x1: Float, x2: Float, za: Float, zb: Float, col: Color) {
        if (zb <= zn) return
        val z1 = max(za, zn)
        val y1 = hy + f * alt / z1; val y2 = hy + f * alt / zb
        if (y2 > h + 2f) return
        p.reset()
        p.moveTo(cx + f * x1 / z1, y1); p.lineTo(cx + f * x2 / z1, y1)
        p.lineTo(cx + f * x2 / zb, y2); p.lineTo(cx + f * x1 / zb, y2); p.close()
        drawPath(p, col)
    }
    val white = Color(0xFFF2F5F8).copy(alpha = if (night) 0.8f else 0.9f)
    quad(-half - 1.2f, half + 1.2f, zs, ze, if (night) Color(0xFF1A222C) else Color(0xFF55606A))   // обочина
    quad(-half, half, zs, ze, if (night) Color(0xFF262F3B) else Color(0xFF3C444F))               // асфальт
    quad(-half + 0.5f, -half + 0.9f, zs, ze, white); quad(half - 0.9f, half - 0.5f, zs, ze, white) // кромки
    var z = zs + 12f
    while (z < ze - 6f) { quad(-0.3f, 0.3f, z, z + 6f, white); z += 12f }                          // осевая
    for (i in 0 until 8) { val x = -half + 1.4f + i * 1.7f + (if (i >= 4) 1.3f else 0f); quad(x, x + 0.9f, zs + 1.5f, zs + 7.5f, white) } // «зебра»
    quad(-half + 1.4f, -half + 3.2f, zs + 30f, zs + 36f, white); quad(half - 3.2f, half - 1.4f, zs + 30f, zs + 36f, white)       // зона касания
    // огни: по кромкам и огни подхода перед порогом
    fun light(X: Float, Z: Float, col: Color, k: Float) {
        if (Z <= zn) return
        val x = cx + f * X / Z; val y = hy + f * alt / Z
        if (y > h || x < -20f || x > w + 20f) return
        val r = max(1.2f, f * k / Z)
        if (night) drawCircle(col.copy(alpha = 0.22f), r * 3.5f, Offset(x, y))
        drawCircle(col.copy(alpha = if (night) 1f else 0.75f), r, Offset(x, y))
    }
    var lz = floor(zs / 10f) * 10f + 10f
    while (lz < ze) { light(-half - 0.8f, lz, Color(0xFFFFE6A8), 0.16f); light(half + 0.8f, lz, Color(0xFFFFE6A8), 0.16f); lz += 10f }
    for (i in 1..6) light(0f, zs - i * 12f, Color.White, 0.22f)
}

private fun DrawScope.drawClouds(sim: FlightSim, wx: SkyWeather.W, f: Float, cx: Float, hy: Float, w: Float, h: Float) {
    val base = when {
        wx.rain -> Color(0xFF6E7C8C)
        !wx.day -> Color(0xFF6C80A0)
        else -> Color(0xFFF2F6FB)
    }
    val alpha = if (wx.rain) 0.8f else if (!wx.day) 0.7f else 0.75f
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


/** Настоящая 3D-сцена (three.js во встроенном WebView). Не потянул WebGL или упал — сам переключается на плоскую FlightScene2D. */
object Flight3D {
    var failed by mutableStateOf(false)
    /** Сцена хоть раз дорисовала кадр в этом запуске. */
    var ok by mutableStateOf(false)
    /** Что сцена сообщила о себе (размер, GPU, ошибки) — показывается в настройках. */
    var info by mutableStateOf("")
    /** Вызывается при старте приложения: если прошлый запуск 3D не дошёл до «готово» — значит упали, выключаем 3D. */
    fun checkCrash() {
        if (Prefs.flight3dStarting) { Prefs.flight3dStarting = false; Prefs.flight3d = false; failed = true }
    }
    fun fallback() { Prefs.flight3dStarting = false; failed = true }
}

private class FlightBridge {
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    var liftCb: () -> Unit = {}; var touchCb: () -> Unit = {}; var readyCb: () -> Unit = {}
    @android.webkit.JavascriptInterface fun ready() { h.post { Prefs.flight3dStarting = false; Flight3D.ok = true; readyCb() } }
    @android.webkit.JavascriptInterface fun onLift() { h.post { liftCb() } }
    @android.webkit.JavascriptInterface fun onTouch() { h.post { touchCb() } }
    @android.webkit.JavascriptInterface fun fail() { h.post { Flight3D.fallback() } }
    @android.webkit.JavascriptInterface fun diag(m: String) { h.post { Flight3D.info = (Flight3D.info + "\n" + m).trim().takeLast(400) } }
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
fun FlightScene(flying: Boolean, glow: Color, modifier: Modifier = Modifier, flash: Float = 0f,
                onLift: () -> Unit = {}, onTouch: () -> Unit = {}) {
    if (Flight3D.failed || !Prefs.flight3d) { FlightScene2D(flying, glow, modifier, flash, onLift, onTouch); return }
    val lift by rememberUpdatedState(onLift)
    val touch by rememberUpdatedState(onTouch)
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    val bridge = remember { FlightBridge() }
    bridge.liftCb = { lift() }; bridge.touchCb = { touch() }; bridge.readyCb = { ready = true }
    // сцена не поднялась за 12 с — тихо уходим на плоскую
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(12_000); if (!ready) Flight3D.fallback() }
    val wx = SkyWeather.now
    fun js(code: String) { runCatching { web?.evaluateJavascript(code, null) } }
    LaunchedEffect(ready, flying) { if (ready) js("setFlying($flying)") }
    LaunchedEffect(ready, wx) { if (ready) js("setWeather(${wx.cloud}, ${wx.rain}, ${wx.day})") }
    LaunchedEffect(ready, SkyDrift.vx, SkyDrift.vz) { if (ready) js("setDrift(${SkyDrift.vx * 9f}, ${SkyDrift.vz * 9f})") }
    LaunchedEffect(ready, glow) {
        if (ready) js("setGlow('#" + String.format(java.util.Locale.US, "%06X", 0xFFFFFF and glow.toArgb()) + "')")
    }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> runCatching { web?.onPause() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> runCatching { web?.onResume() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            try {
                Prefs.flight3dStarting = true
                android.webkit.WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    setBackgroundColor(0xFF0B1422.toInt())
                    isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                    addJavascriptInterface(bridge, "PilotBridge")
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onRenderProcessGone(view: android.webkit.WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            Flight3D.fallback(); return true
                        }
                    }
                    loadUrl("file:///android_asset/flight/index.html")
                    web = this
                }
            } catch (t: Throwable) {
                Flight3D.fallback(); android.view.View(c)
            }
        },
        onRelease = { v ->
            web = null
            if (v is android.webkit.WebView) runCatching { v.stopLoading(); v.removeJavascriptInterface("PilotBridge"); v.destroy() }
            if (!ready) Prefs.flight3dStarting = false
        },
        modifier = modifier)
}

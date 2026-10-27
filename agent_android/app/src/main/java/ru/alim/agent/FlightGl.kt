package ru.alim.agent

import android.opengl.GLES30 as G
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Нативная 3D-сцена кабины (OpenGL ES 3.0) — перенос flight.js без браузера.
 * Низкополигональная земля кусками по 400 м, аэропорт с огнями, облака, дождь, небо с солнцем/луной и звёздами.
 * Управление: flying, setWeather, setDrift, setGlow. Обратно: onLift (отрыв), onTouch (касание), onReady, onFail.
 */
class FlightGl : GLSurfaceView.Renderer {
    // ——— управление (из UI-потока) ———
    @Volatile var flying = false
    @Volatile private var wxCloud = 35; @Volatile private var wxRain = false; @Volatile private var wxDay = true
    @Volatile private var lookDirty = true
    @Volatile var driftX = 3f; @Volatile var driftZ = 2f
    @Volatile private var glowR = 0x3e / 255f; @Volatile private var glowG = 0x6d / 255f; @Volatile private var glowB = 0x92 / 255f
    @Volatile var dpr = 1.8f
    var onLift: () -> Unit = {}; var onTouch: () -> Unit = {}; var onReady: (String) -> Unit = {}; var onFail: (String) -> Unit = {}

    fun setWeather(cloud: Int, rain: Boolean, day: Boolean) { wxCloud = cloud.coerceIn(0, 100); wxRain = rain; wxDay = day; lookDirty = true }
    fun setGlow(r: Float, g: Float, b: Float) { glowR = r; glowG = g; glowB = b; glowDirty = true }
    @Volatile private var glowDirty = true

    // ——— палитры ———
    private class Pal(val top: Int, val hor: Int, val hemiSky: Int, val hemiGround: Int, val hemiI: Float, val sun: Int, val sunI: Float,
                      val fields: IntArray, val base: Int, val tree: Int, val house: Int, val roof: Int, val road: Int, val grass: Int,
                      val apron: Int, val cloud: Int, val cloudO: Float, val star: Boolean)
    private val DAY = Pal(0x2b5f8f, 0xb7d0e2, 0xdfeaf6, 0x4a5a3a, 0.9f, 0xfff0d2, 0.95f,
        intArrayOf(0x5f8f4e, 0x6f9a52, 0x9aa35e, 0x4f8661, 0xb0a46a, 0x7da55a, 0x8a9a54, 0xa8915e), 0x456f3f,
        0x2f5a34, 0xe2d8c6, 0xa04e3c, 0x5a5e63, 0x6f9658, 0x8d939a, 0xf6f8fb, 0.96f, false)
    private val NIGHT = Pal(0x050a14, 0x1b3346, 0x7f97c4, 0x16241c, 0.85f, 0xa9c1ef, 0.4f,
        intArrayOf(0x2d4f4b, 0x355a4a, 0x2a4758, 0x3a5c47, 0x30525e, 0x405f52), 0x203a38,
        0x132a20, 0x46536a, 0x2c3240, 0x10171f, 0x23402f, 0x2a313b, 0x8193b2, 0.88f, true)
    private var P = DAY

    // ——— GL ———
    private var progLit = 0; private var progPts = 0; private var progSky = 0; private var progLine = 0
    private val U = HashMap<String, Int>()
    private fun u(p: Int, n: String): Int = U.getOrPut("$p/$n") { G.glGetUniformLocation(p, n) }
    private var bufW = 1; private var bufH = 1
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private val camW = FloatArray(16); private val viewRot = FloatArray(16); private val vpRot = FloatArray(16); private val invRot = FloatArray(16)
    private var dead = false; private var readySent = false

    // ——— состояние мира ———
    private val CZ = 400f; private val NCH = 8; private val HALFW = 800f; private val CELL = 50f
    private val ROAD_X = floatArrayOf(-330f, 420f)
    private val RW_LEN = 900f; private val RW_W = 45f
    private var airZ = 120f
    private val EYE = 5.5f; private val CRUISE_ALT = 250f; private val PARK_YAW = -0.6f
    private var camZ = 0f
    private val SUN = norm(-0.45f, 0.22f, -1f)

    private enum class St { PARKED, TAKEOFF, CRUISE, LANDING }
    private var st = St.PARKED; private var t = 0f; private var clock = 0f; private var speed = 0f; private var alt = EYE
    private var pitch = 0f; private var roll = 0f; private var yaw = PARK_YAW
    private var landQ = false; private var lifted = false; private var touched = false; private var startAlt = CRUISE_ALT; private var v0 = 110f

    // освещение/туман (вычисляются в applyLook)
    private var top = FloatArray(3); private var hor = FloatArray(3); private val glow = FloatArray(3)
    private var fogC = FloatArray(3); private var fogN = 300f; private var fogF = 2700f
    private var hemiSky = FloatArray(3); private var hemiGround = FloatArray(3); private var sunC = FloatArray(3)
    private var cloudC = FloatArray(3); private var cloudEm = FloatArray(3); private var cloudA = 0.96f
    private var starA = 0f; private var orbOn = true; private var orbC = FloatArray(3); private var orbR = 0.06f
    private var winA = 0f; private var haloA = 0.15f; private var glassEm = FloatArray(3)

    private class Chunk(val geo: Vbo, val wins: Vbo) { var k: Int? = null }
    private val chunks = ArrayList<Chunk>()
    private val air = Vbo(9); private val glass = Vbo(9); private val cores = Vbo(7); private val halos = Vbo(7)
    private val stars = Vbo(7); private val rainV = Vbo(3)
    private class Cloud(val vbo: Vbo, var x: Float, var y: Float, var z: Float)
    private val clouds = ArrayList<Cloud>()
    private val freeCloudVbos = ArrayList<Vbo>()
    private val geo = Geo(); private val pts = Geo()

    private var last = 0L

    // ===================== GLSurfaceView.Renderer =====================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            U.clear()
            progLit = prog(LIT_VS, LIT_FS); progPts = prog(PTS_VS, PTS_FS); progSky = prog(SKY_VS, SKY_FS); progLine = prog(LINE_VS, LINE_FS)
            for (v in listOf(air, glass, cores, halos, stars, rainV)) v.id = 0
            chunks.clear(); repeat(NCH) { chunks += Chunk(Vbo(9), Vbo(7)) }
            clouds.clear(); freeCloudVbos.clear()
            buildStars(); buildRain()
            lookDirty = true; glowDirty = true
            last = System.nanoTime()
        } catch (e: Throwable) { die("init: ${e.message}") }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        bufW = max(1, w); bufH = max(1, h)
        G.glViewport(0, 0, bufW, bufH)
        Matrix.perspectiveM(proj, 0, 60f, bufW.toFloat() / bufH, 0.3f, 6000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (dead) { G.glClearColor(0.043f, 0.078f, 0.133f, 1f); G.glClear(G.GL_COLOR_BUFFER_BIT); return }
        try {
            val now = System.nanoTime()
            val dt = min(0.05f, (now - last) / 1e9f); last = now
            if (glowDirty) { glowDirty = false; glow[0] = glowR; glow[1] = glowG; glow[2] = glowB; lookDirty = true }
            if (lookDirty) { lookDirty = false; applyLook() }
            step(dt)
            for (c in clouds) {
                if (st == St.PARKED) { c.x += driftX * dt; c.z += driftZ * dt }
                if (c.z > camZ + 250) { c.z -= 2700; c.x = ((Math.random() - 0.5) * 1800).toFloat() }
                if (c.z < camZ - 2800) c.z += 2700
                if (c.x > 1000) c.x -= 2000 else if (c.x < -1000) c.x += 2000
            }
            updateChunks(false)
            render()
            if (!readySent) {
                readySent = true
                val info = "gl " + (G.glGetString(G.GL_RENDERER) ?: "?") + " · ${bufW}x$bufH"
                onReady(info)
            }
            // на стоянке хватит ~30 кадров/с — бережём батарею
            if (st == St.PARKED) {
                val spent = (System.nanoTime() - now) / 1_000_000
                if (spent < 30) Thread.sleep(30 - spent)
            }
        } catch (e: InterruptedException) {
        } catch (e: Throwable) { die("frame: ${e.message}") }
    }

    private fun die(m: String) { if (!dead) { dead = true; onFail(m) } }

    // ===================== симуляция (перенос step из flight.js) =====================

    private fun ease(x: Float): Float { val k = x.coerceIn(0f, 1f); return k * k * (3 - 2 * k) }
    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun step(dt: Float) {
        clock += dt; t += dt
        when (st) {
            St.PARKED -> {
                speed = 0f; alt = EYE; pitch = 0.004f * sin(clock * 0.6f); roll = 0f; yaw = PARK_YAW
                if (flying) { st = St.TAKEOFF; t = 0f; lifted = false; landQ = false }
            }
            St.TAKEOFF -> {
                if (!flying) landQ = true
                yaw = PARK_YAW * (1 - ease(t / 1.6f))
                if (t < 3.4f) { speed = 85f * (t / 3.4f).pow(1.5f); alt = EYE; pitch = 0f }
                else {
                    if (!lifted) { lifted = true; onLift() }
                    val p = min(1f, (t - 3.4f) / 4.4f)
                    speed = lerp(85f, 110f, p); alt = EYE + (CRUISE_ALT - EYE) * ease(p)
                    pitch = 0.17f * sin(PI.toFloat() * min(1f, p * 1.05f))
                    roll = 0.04f * sin(PI.toFloat() * p)
                }
                if (t >= 7.8f) {
                    st = if (landQ || !flying) St.LANDING else St.CRUISE; t = 0f
                    if (st == St.LANDING) beginLanding()
                }
            }
            St.CRUISE -> {
                speed = 110f; alt = CRUISE_ALT + 6 * sin(clock * 0.4f); pitch = 0.015f * sin(clock * 0.7f); roll = 0.05f * sin(clock * 0.33f)
                if (!flying) { st = St.LANDING; t = 0f; beginLanding() }
            }
            St.LANDING -> {
                if (t < 5.5f) {
                    val q = t / 5.5f
                    speed = lerp(v0, 72f, q)
                    alt = startAlt + (EYE - startAlt) * (1 - (1 - q).pow(2.2f))
                    pitch = if (q < 0.82f) -0.06f * sin(PI.toFloat() * q / 0.82f) else 0.05f * sin(PI.toFloat() * (q - 0.82f) / 0.18f)
                    roll = if (q < 0.7f) 0.03f * sin(PI.toFloat() * q) else 0f
                } else {
                    if (!touched) { touched = true; onTouch() }
                    val u = min(1f, (t - 5.5f) / 3.6f)
                    yaw = PARK_YAW * ease((u - 0.55f) / 0.45f)
                    speed = 72f * (1 - u).pow(1.7f)
                    alt = EYE + (if (u < 0.25f) 0.35f * max(0f, sin(u * 22)) * (1 - u) * (1 - u) else 0f)
                    pitch = 0f; roll = 0f
                    if (u >= 1f) { st = St.PARKED; t = 0f; speed = 0f }
                }
            }
        }
        camZ -= speed * dt
    }

    private fun beginLanding() {
        touched = false; startAlt = alt; v0 = max(speed, 80f)
        val dist = (v0 + 72f) / 2 * 5.5f
        placeAirport(camZ - dist + 300)
    }

    private fun placeAirport(z: Float) { airZ = z; refillOverlapping() }
    private fun inAirport(x: Float, z: Float) = x > -300 && x < 340 && z < airZ + 360 && z > airZ - RW_LEN - 300

    // ===================== внешний вид =====================

    private fun applyLook() {
        P = if (wxDay) DAY else NIGHT
        top = rgb(P.top); hor = rgb(P.hor)
        if (wxRain) { top = mix(top, rgb(0x3c4a58), 0.6f); hor = mix(hor, rgb(0x7d8996), 0.5f) }
        fogC = mix(hor, glow, 0.15f)
        fogN = if (wxRain) 120f else 300f; fogF = if (wxRain) 1600f else 2700f
        val hi = P.hemiI * (if (wxRain) 0.85f else 1f)
        hemiSky = scale(rgb(P.hemiSky), hi); hemiGround = scale(rgb(P.hemiGround), hi)
        sunC = scale(rgb(P.sun), P.sunI * (if (wxRain) 0.5f else 1f))
        var cc = rgb(P.cloud); if (wxRain) cc = mix(cc, rgb(0x707d8c), 0.6f)
        cloudC = cc; cloudA = P.cloudO; cloudEm = scale(cc, if (wxDay) 0.45f else 0.25f)
        starA = if (P.star && !wxRain) 0.85f else 0f
        orbC = rgb(if (wxDay) 0xfff1d0 else 0xdfe8ff); orbOn = !wxRain && wxCloud < 85
        orbR = atan((if (wxDay) 210f else 130f) / 3500f)
        winA = if (wxDay) 0f else 0.95f
        haloA = if (wxDay) 0.15f else 0.75f
        glassEm = if (wxDay) floatArrayOf(0f, 0f, 0f) else rgb(0x8a6a2c)
        buildAirport()
        updateChunks(true)
        buildClouds()
    }

    // ===================== земля =====================

    private fun updateChunks(force: Boolean) {
        val first = floor(-camZ / CZ).toInt() - 1
        val need = HashSet<Int>(); for (k in first until first + NCH) need += k
        val free = ArrayList<Chunk>()
        for (c in chunks) { val k = c.k; if (k == null || k !in need) free += c else need -= k }
        for (k in need) { val ch = free.removeLastOrNull() ?: break; fill(ch, k) }
        if (force) for (c in chunks) c.k?.let { fill(c, it) }
    }

    private fun refillOverlapping() {
        for (c in chunks) {
            val k = c.k ?: continue
            val z1 = -(k + 1) * CZ; val z2 = -k * CZ
            if (z2 > airZ - RW_LEN - 300 && z1 < airZ + 360) fill(c, k)
        }
    }

    private fun fill(ch: Chunk, k: Int) {
        ch.k = k
        val zc = -(k + 0.5f) * CZ
        val rnd = java.util.Random(k * 7919L + 13)
        fun r() = rnd.nextFloat()
        geo.clear(); pts.clear()
        geo.quadUp(-HALFW, HALFW, zc - CZ / 2, zc + CZ / 2, 0f, P.base, 1f)
        val gap = 2.2f
        var cx = -HALFW
        while (cx < HALFW) {
            var cz = -CZ / 2
            while (cz < CZ / 2) {
                val c = P.fields[(r() * P.fields.size).toInt().coerceAtMost(P.fields.size - 1)]
                geo.quadUp(cx + gap, cx + CELL - gap, zc + cz + gap, zc + cz + CELL - gap, 0.15f, c, 1f)
                cz += CELL
            }
            cx += CELL
        }
        val crossZ = -CZ / 2 + 40 + r() * (CZ - 80)
        geo.quadUp(-HALFW, HALFW, zc + crossZ - 5, zc + crossZ + 5, 0.25f, P.road, 1f)
        for (rx in ROAD_X) geo.quadUp(rx - 5, rx + 5, zc - CZ / 2, zc + CZ / 2, 0.32f, P.road, 1f)
        // деревья
        var nt = 0
        fun tree(x: Float, z: Float) {
            if (nt >= 260 || inAirport(x, zc + z) || abs(x - ROAD_X[0]) < 9 || abs(x - ROAD_X[1]) < 9 || abs(z - crossZ) < 8) return
            val s = 0.7f + r() * 0.8f; val rot = r() * 6.28f; val sy = s * (0.8f + r() * 0.5f); val l = 1f + (r() - 0.5f) * 0.16f
            geo.mesh(TREE, x, 0f, zc + z, rot, s, sy, s, P.tree, l, 4f); nt++
        }
        repeat(3) {
            val px = -HALFW + 60 + r() * (HALFW * 2 - 120); val pz = -CZ / 2 + 30 + r() * (CZ - 60)
            repeat(55) { tree(px + (r() - 0.5f) * 120, pz + (r() - 0.5f) * 90) }
        }
        val lineZ = -CZ / 2 + r() * CZ
        var lx = -HALFW
        while (lx < HALFW) { if (r() < 0.75f) tree(lx, lineZ + (r() - 0.5f) * 3); lx += 9 }
        // посёлки
        var nh = 0
        repeat(2) {
            val vx = -HALFW + 80 + r() * (HALFW * 2 - 160); val vz = -CZ / 2 + 50 + r() * (CZ - 100)
            for (hh in 0 until 14) {
                if (nh >= 60) break
                val hx = vx + (hh % 4) * 26 + (r() - 0.5f) * 8; val hz = vz + (hh / 4) * 28 + (r() - 0.5f) * 8
                if (inAirport(hx, zc + hz) || abs(hx - ROAD_X[0]) < 12 || abs(hx - ROAD_X[1]) < 12) continue
                val hs = 0.8f + r() * 0.5f; val rot = (if (r() < 0.5f) 0f else (PI / 2).toFloat()) + (r() - 0.5f) * 0.2f
                val sy = hs * (0.9f + r() * 0.6f)
                geo.mesh(HOUSE, hx, 0f, zc + hz, rot, hs, sy, hs, P.house, 1f + (r() - 0.5f) * 0.16f, 3.5f)
                geo.mesh(ROOF, hx, 0f, zc + hz, rot, hs, sy, hs, P.roof, 1f + (r() - 0.5f) * 0.2f, 8f)
                if (r() < 0.8f) pts.pt(hx + (r() - 0.5f) * 4, 4 * hs, zc + hz + (r() - 0.5f) * 4, 0xffd08a, 7f)
                nh++
            }
        }
        ch.geo.set(geo.a, geo.n); ch.wins.set(pts.a, pts.n)
    }

    // ===================== аэропорт =====================

    private fun buildAirport() {
        geo.clear()
        geo.quadUp(60f - 280, 60f + 280, -RW_LEN / 2 + 50 - (RW_LEN + 700) / 2, -RW_LEN / 2 + 50 + (RW_LEN + 700) / 2, 0.45f, P.grass, 1f)
        geo.quadUp(-RW_W / 2, RW_W / 2, -RW_LEN, 0f, 0.62f, 0x3d444d, 1f)
        // разметка (как на текстуре 128 px поперёк полосы)
        fun px(p: Float) = p * RW_W / 128f - RW_W / 2
        fun mark(p1: Float, p2: Float, d1: Float, d2: Float) = geo.quadUp(px(p1), px(p2), -d2, -d1, 0.7f, 0xeef2f6, 1f)
        mark(6f, 9f, 0f, RW_LEN); mark(119f, 122f, 0f, RW_LEN)
        var d = 60f; while (d < RW_LEN - 60) { mark(62f, 66f, d, d + 30); d += 50 }
        for (d0 in floatArrayOf(6f, RW_LEN - 36)) for (kk in 0 until 8) { val x = 14f + kk * 13 + (if (kk >= 4) 8 else 0); mark(x, x + 7, d0, d0 + 30) }
        for (d0 in floatArrayOf(150f, 300f, RW_LEN - 172, RW_LEN - 322)) { mark(24f, 38f, d0, d0 + 22); mark(90f, 104f, d0, d0 + 22) }
        geo.quadUp(64f, 76f, -RW_LEN / 2 - 260, -RW_LEN / 2 + 260, 0.55f, P.apron, 1f)     // рулёжка
        geo.quadUp(85f, 235f, -460f, -200f, 0.55f, P.apron, 1f)                            // перрон
        val bld = 0xc9d3dc
        geo.mesh(boxT(34f, 16f, 170f), 250f, 0f, -330f, 0f, 1f, 1f, 1f, bld, 1f, 8f)
        geo.mesh(frustumT(4.5f, 3.5f, 42f, 8, 0f), 120f, 0f, -150f, 0f, 1f, 1f, 1f, bld, 1f, 14f)
        for (h in 0 until 2) geo.mesh(VAULT, 215f + h * 52, 0f, -560f, 0f, 1f, 1f, 1f, 0x9aa6b2, 1f, 8f)
        air.set(geo.a, geo.n)
        geo.clear()
        geo.mesh(boxT(35f, 5f, 160f), 250f, 6.5f, -330f, 0f, 1f, 1f, 1f, 0x2b4a63, 1f, 2.5f)
        geo.mesh(frustumT(7f, 9f, 8f, 8, 0f), 120f, 42f, -150f, 0f, 1f, 1f, 1f, 0x2b4a63, 1f, 3f)
        glass.set(geo.a, geo.n)
        // огни: кромки тёплые, порог зелёный, торец красный, подход белый
        pts.clear(); val hl = Geo()
        fun lamp(x: Float, z: Float, c: Int) { pts.pt(x, 1.0f, z, c, 1.5f); hl.pt(x, 1.3f, z, c, 10f) }
        var z = 0f; while (z <= RW_LEN) { lamp(-RW_W / 2 - 1, -z, 0xffe2a0); lamp(RW_W / 2 + 1, -z, 0xffe2a0); z += 30 }
        var x = -21f; while (x <= 21f) { lamp(x, 1.5f, 0x6dff9a); lamp(x, -RW_LEN - 1.5f, 0xff5a4a); x += 3.5f }
        var a = 30f; while (a <= 330f) { lamp(0f, a, 0xffffff); a += 30 }
        var xb = -18f; while (xb <= 18f) { lamp(xb, 150f, 0xffffff); xb += 4.5f }
        cores.set(pts.a, pts.n); halos.set(hl.a, hl.n)
    }

    // ===================== облака, звёзды, дождь =====================

    private fun buildClouds() {
        for (c in clouds) freeCloudVbos += c.vbo
        clouds.clear()
        val n = (3 + wxCloud / 5.5f).roundToInt()
        val rnd = java.util.Random(99); fun r() = rnd.nextFloat()
        val col = (((cloudC[0] * 255).toInt() shl 16) or ((cloudC[1] * 255).toInt() shl 8) or (cloudC[2] * 255).toInt())
        repeat(n) {
            geo.clear()
            val parts = 4 + (r() * 5).toInt(); val big = 0.7f + r() * 0.9f
            for (p in 0 until parts) {
                val s = (18 + r() * 26) * big
                val sx = s * (1.2f + r() * 0.6f); val sy = s * (0.55f + r() * 0.3f); val sz = s * (1f + r() * 0.5f)
                val x = (p - parts / 2f) * 26 * big + (r() - 0.5f) * 18; val y = (r() - 0.3f) * 10; val z = (r() - 0.5f) * 30
                geo.mesh(ICO, x, y, z, r() * 3, sx, sy, sz, col, 1f, 0f)
            }
            val v = freeCloudVbos.removeLastOrNull() ?: Vbo(9)
            v.set(geo.a, geo.n)
            clouds += Cloud(v, (r() - 0.5f) * 1800, 150 + r() * 60, camZ - 150 - r() * 2500)
        }
    }

    private fun buildStars() {
        val rnd = java.util.Random(7); pts.clear()
        repeat(600) {
            val th = rnd.nextFloat() * 2 * PI.toFloat(); val ph = 0.04f + rnd.nextFloat() * 1.3f
            pts.pt(cos(th) * cos(ph) * 3800, sin(ph) * 3800, sin(th) * cos(ph) * 3800, 0xffffff, 2.2f)
        }
        stars.set(pts.a, pts.n)
    }

    private fun buildRain() {
        val a = FloatArray(500 * 6); var i = 0
        repeat(500) {
            val rx = ((Math.random() - 0.5) * 80).toFloat(); val ry = (Math.random() * 50).toFloat(); val rz = (-Math.random() * 80).toFloat()
            a[i++] = rx; a[i++] = ry; a[i++] = rz; a[i++] = rx - 0.4f; a[i++] = ry - 2.2f; a[i++] = rz
        }
        rainV.set(a, a.size)
    }

    // ===================== отрисовка =====================

    private fun render() {
        // камера: порядок поворотов YXZ, как в three.js
        Matrix.setIdentityM(camW, 0)
        Matrix.translateM(camW, 0, 0f, alt, camZ)
        val basePitch = if (st == St.PARKED) -0.03f else if (alt > 60) -0.11f else -0.04f
        Matrix.rotateM(camW, 0, deg(yaw), 0f, 1f, 0f)
        Matrix.rotateM(camW, 0, deg(pitch + basePitch), 1f, 0f, 0f)
        Matrix.rotateM(camW, 0, deg(roll), 0f, 0f, 1f)
        Matrix.invertM(view, 0, camW, 0)
        Matrix.perspectiveM(proj, 0, 60f, bufW.toFloat() / bufH, if (alt > 30) 2f else 0.3f, 6000f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        System.arraycopy(view, 0, viewRot, 0, 16); viewRot[12] = 0f; viewRot[13] = 0f; viewRot[14] = 0f
        Matrix.multiplyMM(vpRot, 0, proj, 0, viewRot, 0)
        Matrix.invertM(invRot, 0, vpRot, 0)

        G.glClearColor(fogC[0], fogC[1], fogC[2], 1f)
        G.glClear(G.GL_COLOR_BUFFER_BIT or G.GL_DEPTH_BUFFER_BIT)
        G.glDisable(G.GL_CULL_FACE)

        // небо
        G.glDisable(G.GL_DEPTH_TEST); G.glDepthMask(false); G.glDisable(G.GL_BLEND)
        for (i in 0..2) G.glDisableVertexAttribArray(i)
        G.glUseProgram(progSky)
        G.glUniformMatrix4fv(u(progSky, "uInvRot"), 1, false, invRot, 0)
        v3(progSky, "uTop", top); v3(progSky, "uHor", hor); v3(progSky, "uGlow", glow); v3(progSky, "uSunDir", SUN); v3(progSky, "uOrb", orbC)
        G.glUniform1f(u(progSky, "uOrbOn"), if (orbOn) 1f else 0f); G.glUniform1f(u(progSky, "uOrbR"), orbR)
        G.glDrawArrays(G.GL_TRIANGLES, 0, 3)

        // звёзды
        if (starA > 0f) {
            G.glEnable(G.GL_BLEND); G.glBlendFunc(G.GL_ONE, G.GL_ONE_MINUS_SRC_ALPHA)
            drawPts(stars, 0f, 0f, false, dpr, 1f, starA, false, false)
        }

        // непрозрачное
        G.glEnable(G.GL_DEPTH_TEST); G.glDepthFunc(G.GL_LEQUAL); G.glDepthMask(true); G.glDisable(G.GL_BLEND)
        litBegin()
        for (c in chunks) if (c.k != null) drawLit(c.geo, 0f, 0f, 0f, ZERO, 1f)
        drawLit(air, 0f, 0f, airZ, ZERO, 1f)
        drawLit(glass, 0f, 0f, airZ, glassEm, 1f)

        // огни: ядра ламп
        G.glEnable(G.GL_BLEND); G.glBlendFunc(G.GL_ONE, G.GL_ONE_MINUS_SRC_ALPHA)
        drawPts(cores, 0f, airZ, true, bufH / 2f, 1.5f, 1f, true, true)

        // облака (полупрозрачные, дальние сначала)
        G.glBlendFunc(G.GL_SRC_ALPHA, G.GL_ONE_MINUS_SRC_ALPHA)
        litBegin()
        for (c in clouds.sortedBy { it.z }) drawLit(c.vbo, c.x, c.y, c.z, cloudEm, cloudA)

        // ореолы и окна — сложение света
        G.glDepthMask(false); G.glBlendFunc(G.GL_ONE, G.GL_ONE)
        if (winA > 0f) for (c in chunks) if (c.k != null) drawPts(c.wins, 0f, 0f, true, bufH / 2f, 1f, winA, false, true)
        drawPts(halos, 0f, airZ, true, bufH / 2f, 1f, haloA, false, true)

        // дождь
        if (wxRain) {
            G.glBlendFunc(G.GL_SRC_ALPHA, G.GL_ONE_MINUS_SRC_ALPHA)
            G.glUseProgram(progLine)
            G.glUniformMatrix4fv(u(progLine, "uVP"), 1, false, vp, 0)
            G.glUniform3f(u(progLine, "uOff"), 0f, alt - 10 + ((clock * 60) % 25), camZ)
            G.glUniform4f(u(progLine, "uCol"), 0xb8 / 255f, 0xcb / 255f, 0xe0 / 255f, 0.35f)
            bind(rainV); G.glEnableVertexAttribArray(0); G.glVertexAttribPointer(0, 3, G.GL_FLOAT, false, 12, 0)
            G.glDisableVertexAttribArray(1); G.glDisableVertexAttribArray(2)
            G.glDrawArrays(G.GL_LINES, 0, rainV.count)
        }
        G.glDepthMask(true)
    }

    private fun litBegin() {
        G.glUseProgram(progLit)
        G.glUniformMatrix4fv(u(progLit, "uVP"), 1, false, vp, 0)
        G.glUniform3f(u(progLit, "uCam"), 0f, alt, camZ)
        v3(progLit, "uSky", hemiSky); v3(progLit, "uGround", hemiGround); v3(progLit, "uSunDir", SUN); v3(progLit, "uSunCol", sunC)
        v3(progLit, "uFog", fogC); G.glUniform1f(u(progLit, "uFogN"), fogN); G.glUniform1f(u(progLit, "uFogF"), fogF)
    }

    private fun drawLit(v: Vbo, x: Float, y: Float, z: Float, em: FloatArray, alpha: Float) {
        if (v.id == 0 || v.count == 0) return
        G.glUniform3f(u(progLit, "uOff"), x, y, z); v3(progLit, "uEmis", em); G.glUniform1f(u(progLit, "uAlpha"), alpha)
        bind(v)
        for (i in 0..2) G.glEnableVertexAttribArray(i)
        G.glVertexAttribPointer(0, 3, G.GL_FLOAT, false, 36, 0)
        G.glVertexAttribPointer(1, 3, G.GL_FLOAT, false, 36, 12)
        G.glVertexAttribPointer(2, 3, G.GL_FLOAT, false, 36, 24)
        G.glDrawArrays(G.GL_TRIANGLES, 0, v.count)
    }

    /** Точки: ореолы, окна, ядра ламп, звёзды. offY/offZ — сдвиг (аэропорт, камера для звёзд). */
    private fun drawPts(v: Vbo, offY: Float, offZ: Float, atten: Boolean, scale: Float, minPx: Float, alpha: Float, hard: Boolean, fog: Boolean) {
        if (v.id == 0 || v.count == 0) return
        G.glUseProgram(progPts)
        G.glUniformMatrix4fv(u(progPts, "uVP"), 1, false, vp, 0)
        G.glUniform3f(u(progPts, "uOff"), 0f, offY, offZ)
        G.glUniform3f(u(progPts, "uCam"), 0f, alt, camZ)
        G.glUniform1f(u(progPts, "uScale"), scale); G.glUniform1f(u(progPts, "uAtt"), if (atten) 1f else 0f)
        G.glUniform1f(u(progPts, "uMinPx"), minPx); G.glUniform1f(u(progPts, "uAlpha"), alpha)
        G.glUniform1f(u(progPts, "uHard"), if (hard) 1f else 0f); G.glUniform1f(u(progPts, "uFogOn"), if (fog) 1f else 0f)
        G.glUniform1f(u(progPts, "uFogN"), fogN); G.glUniform1f(u(progPts, "uFogF"), fogF)
        bind(v)
        for (i in 0..2) G.glEnableVertexAttribArray(i)
        G.glVertexAttribPointer(0, 3, G.GL_FLOAT, false, 28, 0)
        G.glVertexAttribPointer(1, 3, G.GL_FLOAT, false, 28, 12)
        G.glVertexAttribPointer(2, 1, G.GL_FLOAT, false, 28, 24)
        G.glDrawArrays(G.GL_POINTS, 0, v.count)
    }

    private fun bind(v: Vbo) = G.glBindBuffer(G.GL_ARRAY_BUFFER, v.id)
    private fun v3(p: Int, n: String, c: FloatArray) = G.glUniform3f(u(p, n), c[0], c[1], c[2])

    private fun prog(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = G.glCreateShader(type); G.glShaderSource(s, src); G.glCompileShader(s)
            val ok = IntArray(1); G.glGetShaderiv(s, G.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("shader: " + G.glGetShaderInfoLog(s).take(200))
            return s
        }
        val p = G.glCreateProgram()
        G.glAttachShader(p, sh(G.GL_VERTEX_SHADER, vs)); G.glAttachShader(p, sh(G.GL_FRAGMENT_SHADER, fs))
        G.glLinkProgram(p)
        val ok = IntArray(1); G.glGetProgramiv(p, G.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("link: " + G.glGetProgramInfoLog(p).take(200))
        return p
    }

    // ===================== геометрия =====================

    private class Vbo(val stride: Int) {
        var id = 0; var count = 0
        fun set(a: FloatArray, n: Int) {
            if (id == 0) { val t = IntArray(1); G.glGenBuffers(1, t, 0); id = t[0] }
            val fb = ByteBuffer.allocateDirect(max(n, 1) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(a, 0, n); fb.position(0)
            G.glBindBuffer(G.GL_ARRAY_BUFFER, id)
            G.glBufferData(G.GL_ARRAY_BUFFER, max(n, 1) * 4, fb, G.GL_DYNAMIC_DRAW)
            count = n / stride
        }
    }

    /** Накопитель вершин: для освещённых — x y z nx ny nz r g b; для точек — x y z r g b size. */
    private class Geo {
        var a = FloatArray(9 * 4096); var n = 0
        fun clear() { n = 0 }
        private fun ensure(k: Int) { if (n + k > a.size) a = a.copyOf(max(a.size * 2, n + k)) }
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: Int, l: Float) {
            ensure(9)
            a[n++] = x; a[n++] = y; a[n++] = z; a[n++] = nx; a[n++] = ny; a[n++] = nz
            a[n++] = min(1f, ((c shr 16) and 255) / 255f * l); a[n++] = min(1f, ((c shr 8) and 255) / 255f * l); a[n++] = min(1f, (c and 255) / 255f * l)
        }
        fun pt(x: Float, y: Float, z: Float, c: Int, size: Float) {
            ensure(7)
            a[n++] = x; a[n++] = y; a[n++] = z
            a[n++] = ((c shr 16) and 255) / 255f; a[n++] = ((c shr 8) and 255) / 255f; a[n++] = (c and 255) / 255f; a[n++] = size
        }
        fun quadUp(x1: Float, x2: Float, z1: Float, z2: Float, y: Float, c: Int, l: Float) {
            v(x1, y, z1, 0f, 1f, 0f, c, l); v(x2, y, z1, 0f, 1f, 0f, c, l); v(x2, y, z2, 0f, 1f, 0f, c, l)
            v(x1, y, z1, 0f, 1f, 0f, c, l); v(x2, y, z2, 0f, 1f, 0f, c, l); v(x1, y, z2, 0f, 1f, 0f, c, l)
        }
        /** Треугольник с плоской нормалью, развёрнутой наружу от центра (cx, cy, cz). */
        fun tri(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float,
                c: Int, l: Float, cx: Float, cy: Float, cz: Float) {
            val ux = x2 - x1; val uy = y2 - y1; val uz = z2 - z1; val wx = x3 - x1; val wy = y3 - y1; val wz = z3 - z1
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val len = sqrt(nx * nx + ny * ny + nz * nz); if (len < 1e-9f) return
            nx /= len; ny /= len; nz /= len
            val mx = (x1 + x2 + x3) / 3 - cx; val my = (y1 + y2 + y3) / 3 - cy; val mz = (z1 + z2 + z3) / 3 - cz
            if (nx * mx + ny * my + nz * mz < 0) { nx = -nx; ny = -ny; nz = -nz }
            v(x1, y1, z1, nx, ny, nz, c, l); v(x2, y2, z2, nx, ny, nz, c, l); v(x3, y3, z3, nx, ny, nz, c, l)
        }
        /** Шаблон (тройки вершин треугольников) → масштаб, поворот вокруг Y, перенос. cy — высота центра шаблона (для нормалей). */
        fun mesh(t: FloatArray, tx: Float, ty: Float, tz: Float, rot: Float, sx: Float, sy: Float, sz: Float, c: Int, l: Float, cy: Float) {
            val co = cos(rot); val si = sin(rot)
            val p = FloatArray(9)
            var i = 0
            while (i + 8 < t.size) {
                for (j in 0 until 3) {
                    val lx = t[i + j * 3] * sx; val ly = t[i + j * 3 + 1] * sy; val lz = t[i + j * 3 + 2] * sz
                    p[j * 3] = lx * co + lz * si + tx; p[j * 3 + 1] = ly + ty; p[j * 3 + 2] = -lx * si + lz * co + tz
                }
                tri(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], c, l, tx, ty + cy * sy, tz)
                i += 9
            }
        }
    }

    companion object {
        private val ZERO = floatArrayOf(0f, 0f, 0f)
        private fun deg(r: Float) = (r * 180.0 / PI).toFloat()
        private fun norm(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }
        private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
        private fun mix(a: FloatArray, b: FloatArray, k: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k)
        private fun scale(a: FloatArray, k: Float) = floatArrayOf(a[0] * k, a[1] * k, a[2] * k)

        /** Коробка w×h×d, низ на y=0, центр по x/z. */
        fun boxT(w: Float, h: Float, d: Float): FloatArray {
            val x0 = -w / 2; val x1 = w / 2; val z0 = -d / 2; val z1 = d / 2
            val q = ArrayList<Float>()
            fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float) {
                q.addAll(listOf(ax, ay, az, bx, by, bz, cx, cy, cz, ax, ay, az, cx, cy, cz, dx, dy, dz))
            }
            quad(x0, 0f, z1, x1, 0f, z1, x1, h, z1, x0, h, z1); quad(x0, 0f, z0, x1, 0f, z0, x1, h, z0, x0, h, z0)
            quad(x0, 0f, z0, x0, 0f, z1, x0, h, z1, x0, h, z0); quad(x1, 0f, z0, x1, 0f, z1, x1, h, z1, x1, h, z0)
            quad(x0, h, z0, x1, h, z0, x1, h, z1, x0, h, z1); quad(x0, 0f, z0, x1, 0f, z0, x1, 0f, z1, x0, 0f, z1)
            return q.toFloatArray()
        }

        /** Усечённый конус (rTop=0 — конус), низ на y=0; углы как в three.js: x = r·sin θ, z = r·cos θ. */
        fun frustumT(rBot: Float, rTop: Float, h: Float, sides: Int, angOff: Float): FloatArray {
            val q = ArrayList<Float>()
            for (i in 0 until sides) {
                val a0 = angOff + i * 2 * PI.toFloat() / sides; val a1 = angOff + (i + 1) * 2 * PI.toFloat() / sides
                val bx0 = rBot * sin(a0); val bz0 = rBot * cos(a0); val bx1 = rBot * sin(a1); val bz1 = rBot * cos(a1)
                val tx0 = rTop * sin(a0); val tz0 = rTop * cos(a0); val tx1 = rTop * sin(a1); val tz1 = rTop * cos(a1)
                q.addAll(listOf(bx0, 0f, bz0, bx1, 0f, bz1, tx1, h, tz1))
                if (rTop > 0f) { q.addAll(listOf(bx0, 0f, bz0, tx1, h, tz1, tx0, h, tz0)); q.addAll(listOf(0f, h, 0f, tx0, h, tz0, tx1, h, tz1)) }
                q.addAll(listOf(0f, 0f, 0f, bx1, 0f, bz1, bx0, 0f, bz0))
            }
            return q.toFloatArray()
        }

        private fun liftT(t: FloatArray, dy: Float, sz: Float = 1f) = FloatArray(t.size) { i -> when (i % 3) { 1 -> t[i] + dy; 2 -> t[i] * sz; else -> t[i] } }

        val TREE = frustumT(4f, 0f, 13f, 6, 0f)
        val HOUSE = boxT(10f, 7f, 12f)
        val ROOF = liftT(frustumT(9f, 0f, 5f, 4, (PI / 4).toFloat()), 7f, 1.25f)

        /** Полуцилиндр-ангар: ось вдоль z, радиус 20, длина 46. */
        val VAULT: FloatArray = run {
            val q = ArrayList<Float>(); val R = 20f; val L = 23f; val n = 10
            for (i in 0 until n) {
                val f0 = i * PI.toFloat() / n; val f1 = (i + 1) * PI.toFloat() / n
                val x0 = R * cos(f0); val y0 = R * sin(f0); val x1 = R * cos(f1); val y1 = R * sin(f1)
                q.addAll(listOf(x0, y0, -L, x1, y1, -L, x1, y1, L, x0, y0, -L, x1, y1, L, x0, y0, L))
                q.addAll(listOf(0f, 0f, -L, x0, y0, -L, x1, y1, -L)); q.addAll(listOf(0f, 0f, L, x1, y1, L, x0, y0, L))
            }
            q.toFloatArray()
        }

        /** Икосфера радиуса 1, одно деление (80 граней) — части облаков. */
        val ICO: FloatArray = run {
            val p = ((1 + sqrt(5.0)) / 2).toFloat()
            val vs = arrayOf(floatArrayOf(-1f, p, 0f), floatArrayOf(1f, p, 0f), floatArrayOf(-1f, -p, 0f), floatArrayOf(1f, -p, 0f),
                floatArrayOf(0f, -1f, p), floatArrayOf(0f, 1f, p), floatArrayOf(0f, -1f, -p), floatArrayOf(0f, 1f, -p),
                floatArrayOf(p, 0f, -1f), floatArrayOf(p, 0f, 1f), floatArrayOf(-p, 0f, -1f), floatArrayOf(-p, 0f, 1f))
            val fs = arrayOf(intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
                intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
                intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
                intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1))
            fun nz(v: FloatArray): FloatArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }
            fun mid(a: FloatArray, b: FloatArray) = nz(floatArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2))
            val q = ArrayList<Float>()
            for (f in fs) {
                val a = nz(vs[f[0]]); val b = nz(vs[f[1]]); val c = nz(vs[f[2]])
                val ab = mid(a, b); val bc = mid(b, c); val ca = mid(c, a)
                for (t in listOf(listOf(a, ab, ca), listOf(ab, b, bc), listOf(ca, bc, c), listOf(ab, bc, ca))) for (v in t) q.addAll(v.toList())
            }
            q.toFloatArray()
        }

        private const val LIT_VS = """#version 300 es
uniform mat4 uVP; uniform vec3 uOff; uniform vec3 uCam;
layout(location=0) in vec3 aP; layout(location=1) in vec3 aN; layout(location=2) in vec3 aC;
out vec3 vN; out vec3 vC; out float vD;
void main(){ vec3 w = aP + uOff; vN = aN; vC = aC; vD = length(w - uCam); gl_Position = uVP * vec4(w, 1.0); }"""

        private const val LIT_FS = """#version 300 es
precision highp float;
uniform vec3 uSky; uniform vec3 uGround; uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uFog; uniform vec3 uEmis;
uniform float uFogN; uniform float uFogF; uniform float uAlpha;
in vec3 vN; in vec3 vC; in float vD; out vec4 o;
void main(){
  vec3 n = normalize(vN);
  vec3 li = mix(uGround, uSky, n.y * 0.5 + 0.5) + uSunCol * max(dot(n, uSunDir), 0.0);
  vec3 c = vC * li + uEmis;
  float f = clamp((vD - uFogN) / (uFogF - uFogN), 0.0, 1.0);
  o = vec4(mix(c, uFog, f), uAlpha);
}"""

        private const val PTS_VS = """#version 300 es
uniform mat4 uVP; uniform vec3 uOff; uniform vec3 uCam; uniform float uScale; uniform float uAtt; uniform float uMinPx;
layout(location=0) in vec3 aP; layout(location=1) in vec3 aC; layout(location=2) in float aS;
out vec3 vC; out float vD;
void main(){
  vec3 w = aP + uOff + (uAtt > 0.5 ? vec3(0.0) : uCam);
  vD = length(w - uCam); vC = aC;
  gl_Position = uVP * vec4(w, 1.0);
  float s = uAtt > 0.5 ? aS * uScale / max(vD, 0.1) : aS * uScale;
  gl_PointSize = clamp(max(s, uMinPx), 1.0, 256.0);
}"""

        private const val PTS_FS = """#version 300 es
precision highp float;
uniform float uAlpha; uniform float uHard; uniform float uFogN; uniform float uFogF; uniform float uFogOn;
in vec3 vC; in float vD; out vec4 o;
void main(){
  float r = length(gl_PointCoord - vec2(0.5)) * 2.0;
  if (r > 1.0) discard;
  float a = uHard > 0.5 ? 1.0 : (r < 0.28 ? 1.0 : (r < 0.36 ? mix(0.95, 0.35, (r - 0.28) / 0.08) : 0.35 * (1.0 - (r - 0.36) / 0.64)));
  float f = uFogOn > 0.5 ? clamp((vD - uFogN) / (uFogF - uFogN), 0.0, 1.0) : 0.0;
  a *= uAlpha * (1.0 - f);
  o = vec4(vC * a, a);
}"""

        private const val SKY_VS = """#version 300 es
out vec2 vNdc;
void main(){ vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)) * 2.0 - 1.0; vNdc = p; gl_Position = vec4(p, 0.0, 1.0); }"""

        private const val SKY_FS = """#version 300 es
precision highp float;
uniform mat4 uInvRot; uniform vec3 uTop; uniform vec3 uHor; uniform vec3 uGlow; uniform vec3 uSunDir; uniform vec3 uOrb;
uniform float uOrbOn; uniform float uOrbR;
in vec2 vNdc; out vec4 o;
void main(){
  vec4 q = uInvRot * vec4(vNdc, 1.0, 1.0);
  vec3 d = normalize(q.xyz / q.w);
  float h = d.y;
  vec3 c = mix(uHor, uTop, smoothstep(0.0, 0.45, h));
  c = mix(c, uGlow, 0.45 * exp(-abs(h) * 14.0));
  if (h < 0.0) c = mix(uHor, uGlow, 0.2);
  if (uOrbOn > 0.5) {
    float r = acos(clamp(dot(d, uSunDir), -1.0, 1.0)) / uOrbR;
    if (r < 1.0) { float a = r < 0.28 ? 1.0 : (r < 0.36 ? mix(0.95, 0.35, (r - 0.28) / 0.08) : 0.35 * (1.0 - (r - 0.36) / 0.64)); c = mix(c, uOrb, a); }
  }
  o = vec4(c, 1.0);
}"""

        private const val LINE_VS = """#version 300 es
uniform mat4 uVP; uniform vec3 uOff;
layout(location=0) in vec3 aP;
void main(){ gl_Position = uVP * vec4(aP + uOff, 1.0); }"""

        private const val LINE_FS = """#version 300 es
precision mediump float;
uniform vec4 uCol; out vec4 o;
void main(){ o = uCol; }"""
    }
}

/** Конфигурация EGL: ES 3, глубина 24 (или 16), 4× сглаживание — если нет, без него. */
class FlightEglChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, d: EGLDisplay): EGLConfig {
        fun pick(samples: Int, depth: Int): EGLConfig? {
            val base = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, depth,
                EGL10.EGL_RENDERABLE_TYPE, 0x40)
            val ms = if (samples > 0) intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples) else IntArray(0)
            val attrs = base + ms + intArrayOf(EGL10.EGL_NONE)
            val num = IntArray(1); val cfgs = arrayOfNulls<EGLConfig>(1)
            return if (egl.eglChooseConfig(d, attrs, cfgs, 1, num) && num[0] > 0) cfgs[0] else null
        }
        return pick(4, 24) ?: pick(0, 24) ?: pick(4, 16) ?: pick(0, 16) ?: throw IllegalStateException("нет подходящей конфигурации EGL")
    }
}

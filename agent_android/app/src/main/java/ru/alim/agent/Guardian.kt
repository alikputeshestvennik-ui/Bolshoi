package ru.alim.agent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Страж — низкополигональная бронированная голова, настоящий 3D: вершины поворачиваются, проецируются в перспективе,
 * грани освещаются и сортируются по глубине. Рисуется прямо в Canvas, без 3D-движков.
 */

private class V(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V(x * k, y * k, z * k)
    fun dot(o: V) = x * o.x + y * o.y + z * o.z
    fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): V { val l = sqrt(dot(this)).coerceAtLeast(1e-6f); return V(x / l, y / l, z / l) }
}

/** mat: 0 металл, 1 тёмное (визор, решётка), 2 свечение, 3 металл челюсти. part: 0 голова, 1 челюсть, 2 глаза, 3 огонёк. */
private class Tri(val a: V, val b: V, val c: V, val mat: Int, val part: Int)

private object Mesh {
    const val METAL = 0; const val DARK = 1; const val GLOW = 2; const val JAW = 3
    const val EYE_Y = 0.31f

    val tris: List<Tri> by lazy { build() }

    private fun ring(y: Float, w: Float, df: Float, db: Float, n: Int, from: Double, to: Double): List<V> =
        (0..n).map { i ->
            val th = from + (to - from) * i / n
            val c = cos(th).toFloat()
            V(w * sin(th).toFloat(), y, if (c >= 0) -df * c else -db * c)
        }

    /** Оболочка по сечениям; нормали разворачиваются наружу от оси. */
    private fun loft(out: MutableList<Tri>, rings: List<FloatArray>, n: Int, mat: Int, part: Int,
                     from: Double = 0.0, to: Double = 2 * PI, capTop: Boolean = true, capBottom: Boolean = true) {
        val rs = rings.map { ring(it[0], it[1], it[2], it[3], n, from, to) }
        val cy = rings.map { it[0] }.average().toFloat()
        fun add(a: V, b: V, c: V) {
            val nrm = (b - a).cross(c - a)
            val mid = (a + b + c) * (1f / 3f)
            val outward = V(mid.x, mid.y - cy, mid.z)
            if (nrm.dot(outward) >= 0) out += Tri(a, b, c, mat, part) else out += Tri(a, c, b, mat, part)
        }
        for (i in 0 until rs.size - 1) for (j in 0 until n) {
            val p00 = rs[i][j]; val p01 = rs[i][j + 1]; val p10 = rs[i + 1][j]; val p11 = rs[i + 1][j + 1]
            add(p00, p10, p11); add(p00, p11, p01)
        }
        if (capTop) { val top = rs.first(); val c = V(0f, top[0].y + 0.04f, 0f); for (j in 0 until n) add(c, top[j], top[j + 1]) }
        if (capBottom) { val bot = rs.last(); val c = V(0f, bot[0].y - 0.03f, 0f); for (j in 0 until n) add(c, bot[j + 1], bot[j]) }
    }

    /** Плоский многоугольник на лицевой поверхности, смотрит на зрителя (−z). */
    private fun facePoly(out: MutableList<Tri>, pts: List<Pair<Float, Float>>, zOf: (Float, Float) -> Float, mat: Int, part: Int) {
        val vs = pts.map { (x, y) -> V(x, y, zOf(x, y)) }
        for (i in 1 until vs.size - 1) {
            val a = vs[0]; val b = vs[i]; val c = vs[i + 1]
            val nrm = (b - a).cross(c - a)
            if (nrm.z <= 0) out += Tri(a, b, c, mat, part) else out += Tri(a, c, b, mat, part)
        }
    }

    private fun build(): List<Tri> {
        val t = ArrayList<Tri>()
        // Внешняя броня головы: от гребня до скул
        loft(t, listOf(
            floatArrayOf(1.06f, 0.14f, 0.18f, 0.22f),
            floatArrayOf(0.88f, 0.50f, 0.56f, 0.60f),
            floatArrayOf(0.56f, 0.70f, 0.70f, 0.72f),
            floatArrayOf(0.18f, 0.74f, 0.70f, 0.70f),
            floatArrayOf(-0.14f, 0.62f, 0.64f, 0.60f),
            floatArrayOf(-0.34f, 0.46f, 0.54f, 0.46f),
        ), 10, METAL, 0)
        // Гребень по центру черепа
        loft(t, listOf(
            floatArrayOf(1.12f, 0.03f, 0.10f, 0.30f),
            floatArrayOf(0.70f, 0.05f, 0.74f, 0.80f),
        ), 4, METAL, 0, capBottom = false)
        // Тёмная полоса визора поверх лица
        loft(t, listOf(
            floatArrayOf(0.44f, 0.71f, 0.78f, 0.1f),
            floatArrayOf(0.20f, 0.73f, 0.77f, 0.1f),
        ), 8, DARK, 0, from = -1.15, to = 1.15, capTop = false, capBottom = false)
        // Глаза — злой наклон: внутренний край ниже
        val eyeZ = { x: Float, _: Float -> -0.79f * sqrt((1f - (x / 0.73f).pow(2)).coerceAtLeast(0.05f)) - 0.012f }
        facePoly(t, listOf(0.08f to 0.27f, 0.40f to 0.36f, 0.39f to 0.28f, 0.11f to 0.23f), eyeZ, GLOW, 2)
        facePoly(t, listOf(-0.08f to 0.27f, -0.11f to 0.23f, -0.39f to 0.28f, -0.40f to 0.36f), eyeZ, GLOW, 2)
        // Челюсть — отдельная, двигается
        loft(t, listOf(
            floatArrayOf(-0.30f, 0.44f, 0.58f, 0.40f),
            floatArrayOf(-0.58f, 0.34f, 0.50f, 0.30f),
            floatArrayOf(-0.80f, 0.18f, 0.34f, 0.18f),
        ), 8, JAW, 1)
        // Решётка
        val jawZ = { x: Float, y: Float -> -(0.50f + (y + 0.58f) * 0.3f) * sqrt((1f - (x / 0.36f).pow(2)).coerceAtLeast(0.1f)) - 0.015f }
        for (yy in listOf(-0.42f, -0.50f, -0.58f)) facePoly(t, listOf(-0.2f to yy, 0.2f to yy, 0.2f to yy - 0.035f, -0.2f to yy - 0.035f), jawZ, DARK, 1)
        // Огонёк под решёткой (горит, когда нужен ответ)
        facePoly(t, listOf(-0.05f to -0.66f, 0.05f to -0.66f, 0.05f to -0.72f, -0.05f to -0.72f), jawZ, GLOW, 3)
        return t
    }
}

private class Pose { var t = 0f; var yaw = 0f; var pitch = 0f; var roll = 0f; var drag = 0f; var dragging = false; var jaw = 0f; var glow = 1f; var lean = 0f }

/** mode: 0 покой, 1 работает, 2 нужен ты, 3 слушает. */
@Composable
fun Guardian(p: Palette, mode: Int, size: Dp, level: Float = 0f, speaking: Boolean = false, modifier: Modifier = Modifier) {
    val pose = remember { Pose() }
    val frame = remember { mutableIntStateOf(0) }
    val m by rememberUpdatedState(mode)
    val lv by rememberUpdatedState(level)
    val sp by rememberUpdatedState(speaking)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            pose.t += dt
            val t = pose.t
            val (ty, tp, tr) = when (m) {
                1 -> Triple(sin(t * 1.7f) * 0.5f, -0.02f + sin(t * 2.3f) * 0.06f, sin(t * 1.1f) * 0.04f)
                2 -> Triple(sin(t * 0.8f) * 0.06f, -0.14f, 0.10f + sin(t * 1.3f) * 0.03f)
                3 -> Triple(sin(t * 0.6f) * 0.08f, 0.06f, 0f)
                else -> Triple(sin(t * 0.45f) * 0.32f, -0.04f + sin(t * 0.37f) * 0.05f, sin(t * 0.29f) * 0.03f)
            }
            val k = (dt * 3f).coerceIn(0f, 1f)
            pose.yaw += (ty - pose.yaw) * k; pose.pitch += (tp - pose.pitch) * k; pose.roll += (tr - pose.roll) * k
            pose.lean += ((if (m == 3) 1f else 0f) - pose.lean) * k
            if (!pose.dragging) pose.drag *= (1f - (dt * 2.5f).coerceIn(0f, 1f))
            val jt = if (sp) 0.05f + 0.05f * abs(sin(t * 13f)) else lv * 0.06f
            pose.jaw += (jt - pose.jaw) * (dt * 12f).coerceIn(0f, 1f)
            pose.glow = when (m) {
                1 -> 0.65f + 0.35f * (0.5f + 0.5f * sin(t * 6f))
                2 -> 0.75f + 0.25f * (0.5f + 0.5f * sin(t * 4f))
                3 -> 0.8f + lv * 0.6f
                else -> 0.8f + 0.12f * sin(t * 1.6f)
            }
            frame.intValue++
        }
    }
    val ar = p.accent.red; val ag = p.accent.green; val ab = p.accent.blue
    Canvas(modifier.size(size).pointerInput(Unit) {
        detectDragGestures(onDragStart = { pose.dragging = true }, onDragEnd = { pose.dragging = false }, onDragCancel = { pose.dragging = false }) { ch, d ->
            ch.consume(); pose.drag = (pose.drag + d.x * 0.012f).coerceIn(-1.4f, 1.4f)
        }
    }) {
        frame.intValue // перерисовка каждый кадр
        val w = this.size.width; val h = this.size.height
        val f = w * 1.22f; val dist = 3.4f
        val cx = w / 2f; val cy = h * 0.47f
        val t = pose.t
        val yaw = pose.yaw + pose.drag; val pitch = pose.pitch; val roll = pose.roll
        val bob = sin(t * 1.25f) * 0.035f
        val sy = sin(yaw); val cyw = cos(yaw); val sx = sin(pitch); val cx2 = cos(pitch); val sz = sin(roll); val cz = cos(roll)
        val blink = (t % 5.2f) < 0.11f
        val eyeK = if (blink) 0.12f else 1f
        val scale = 1f + pose.lean * 0.04f

        fun xf(v0: V, part: Int): V {
            var x = v0.x; var y = v0.y; var z = v0.z
            if (part == 1) { y -= pose.jaw; z -= pose.jaw * 0.4f }
            if (part == 2) y = Mesh.EYE_Y + (y - Mesh.EYE_Y) * eyeK
            x *= scale; y = y * scale + bob - 0.12f; z *= scale
            // рысканье (Y), тангаж (X), крен (Z)
            val x1 = x * cyw + z * sy; val z1 = -x * sy + z * cyw
            val y2 = y * cx2 - z1 * sx; val z2 = y * sx + z1 * cx2
            val x3 = x1 * cz - y2 * sz; val y3 = x1 * sz + y2 * cz
            return V(x3, y3, z2 - pose.lean * 0.08f)
        }
        fun proj(v: V) = Offset(cx + f * v.x / (v.z + dist), cy - f * v.y / (v.z + dist))

        // тень-свечение под головой
        drawOval(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.22f), Color.Transparent), center = Offset(cx, h * 0.93f), radius = w * 0.32f),
            topLeft = Offset(cx - w * 0.32f, h * 0.89f), size = Size(w * 0.64f, h * 0.08f))

        val cam = V(0f, 0f, -dist)
        val light = V(-0.45f, 0.75f, -0.55f).norm()
        class D(val path: Path, val depth: Float, val color: Color, val edge: Color?)
        val list = ArrayList<D>(Mesh.tris.size)
        val glowK = pose.glow * (if (mode == 2 || mode == 3) 1.1f else 1f)
        for (tr in Mesh.tris) {
            if (tr.part == 3 && mode != 2) continue
            val a = xf(tr.a, tr.part); val b = xf(tr.b, tr.part); val c = xf(tr.c, tr.part)
            val n = (b - a).cross(c - a).norm()
            val mid = (a + b + c) * (1f / 3f)
            val view = (cam - mid).norm()
            if (n.dot(view) <= 0f) continue
            val color = when (tr.mat) {
                Mesh.GLOW -> {
                    val g = glowK.coerceIn(0f, 1.4f)
                    Color((ar * g + 0.25f * (g - 0.8f).coerceAtLeast(0f)).coerceIn(0f, 1f), (ag * g + 0.25f * (g - 0.8f).coerceAtLeast(0f)).coerceIn(0f, 1f),
                        (ab * g + 0.25f * (g - 0.8f).coerceAtLeast(0f)).coerceIn(0f, 1f))
                }
                else -> {
                    val base = when (tr.mat) { Mesh.DARK -> floatArrayOf(0.035f, 0.04f, 0.05f); Mesh.JAW -> floatArrayOf(0.24f, 0.27f, 0.32f); else -> floatArrayOf(0.34f, 0.38f, 0.44f) }
                    val dif = n.dot(light).coerceAtLeast(0f)
                    val refl = (light * -1f + n * (2f * n.dot(light))).norm()
                    val spec = refl.dot(view).coerceAtLeast(0f).pow(14f) * (if (tr.mat == Mesh.DARK) 0.25f else 0.55f)
                    val rim = (1f - abs(n.dot(view))).pow(2.2f) * 0.55f
                    val k = 0.22f + 0.85f * dif
                    Color((base[0] * k + spec + ar * rim).coerceIn(0f, 1f), (base[1] * k + spec + ag * rim).coerceIn(0f, 1f), (base[2] * k + spec + ab * rim).coerceIn(0f, 1f))
                }
            }
            val pa = proj(a); val pb = proj(b); val pc = proj(c)
            val path = Path().apply { moveTo(pa.x, pa.y); lineTo(pb.x, pb.y); lineTo(pc.x, pc.y); close() }
            list += D(path, mid.z, color, if (tr.mat == Mesh.GLOW) null else Color.Black.copy(alpha = 0.28f))
        }
        list.sortByDescending { it.depth }
        val edgeW = (w / 260f).coerceAtLeast(0.6f)
        for (d in list) {
            drawPath(d.path, d.color)
            d.edge?.let { drawPath(d.path, it, style = Stroke(edgeW)) }
        }
        // ореол вокруг глаз (и огонька)
        val halo = p.accent.copy(alpha = (0.22f * pose.glow).coerceIn(0f, 0.5f))
        for (ex in listOf(0.24f, -0.24f)) {
            val e = xf(V(ex, Mesh.EYE_Y, -0.8f), 0)
            val pe = proj(e)
            drawCircle(Brush.radialGradient(listOf(halo, Color.Transparent), center = pe, radius = w * 0.16f), radius = w * 0.16f, center = pe, blendMode = BlendMode.Plus)
        }
        if (mode == 2) {
            val pe = proj(xf(V(0f, -0.69f, -0.5f), 1))
            drawCircle(Brush.radialGradient(listOf(halo, Color.Transparent), center = pe, radius = w * 0.08f), radius = w * 0.08f, center = pe, blendMode = BlendMode.Plus)
        }
    }
}

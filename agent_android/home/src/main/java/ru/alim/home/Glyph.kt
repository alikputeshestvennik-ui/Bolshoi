package ru.alim.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Значки нижнего ряда — прямые линии, без скруглений (сетка 30×30, как в макете). */
object Glyph {
    private fun DrawScope.u() = size.width / 30f
    private fun DrawScope.box(c: Color, x0: Float, y0: Float, x1: Float, y1: Float) {
        val u = u()
        drawRect(c, Offset(x0 * u, y0 * u), Size((x1 - x0) * u, (y1 - y0) * u), style = Stroke(2.2f * u, join = StrokeJoin.Miter))
    }
    private fun DrawScope.fill(c: Color, x: Float, y: Float, w: Float, h: Float) { val u = u(); drawRect(c, Offset(x * u, y * u), Size(w * u, h * u)) }
    private fun DrawScope.line(c: Color, vararg p: Float) {
        val u = u(); val path = Path().apply { moveTo(p[0] * u, p[1] * u); var i = 2; while (i < p.size) { lineTo(p[i] * u, p[i + 1] * u); i += 2 } }
        drawPath(path, c, style = Stroke(2.2f * u, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
    }

    @Composable fun Phone(c: Color) = Canvas(Modifier.size(30.dp)) {
        for (i in 0..2) for (j in 0..2) fill(c, 5f + i * 7.5f, 4f + j * 7.5f, 5f, 5f)
    }
    @Composable fun Messages(c: Color) = Canvas(Modifier.size(30.dp)) {
        line(c, 5f, 6f, 25f, 6f, 25f, 19f, 11f, 19f, 7f, 23f, 7f, 19f, 5f, 19f, 5f, 6f)
    }
    @Composable fun Internet(c: Color) = Canvas(Modifier.size(30.dp)) {
        box(c, 5f, 5f, 25f, 25f); line(c, 15f, 5f, 15f, 25f); line(c, 5f, 15f, 25f, 15f)
    }
    @Composable fun Camera(c: Color) = Canvas(Modifier.size(30.dp)) {
        box(c, 4f, 9f, 26f, 24f); box(c, 11.5f, 13f, 18.5f, 20f); fill(c, 7f, 6f, 5f, 3f)
    }
}

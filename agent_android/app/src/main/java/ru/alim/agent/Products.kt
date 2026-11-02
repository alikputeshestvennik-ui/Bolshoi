package ru.alim.agent

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val imgHttp = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

/** Карточки найденных товаров: фото, название, цена, магазин, размер. Нажатие — открыть в приложении магазина. */
@Composable
fun ProductCards(items: List<Shop.Item>) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, it ->
            Row(Modifier.fillMaxWidth().clip(shp(16.dp)).background(Card).clickable(onClickLabel = "Открыть товар") {
                val pkg = when { it.store == "Ozon" -> Shop.OZON_PKG; it.store == "WB" -> Shop.WB_PKG; it.store.startsWith("YouTube") -> YouTube.PKG; else -> null }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(Intent(intent).apply { pkg?.let { p -> setPackage(p) } }) }
                    .onFailure { runCatching { ctx.startActivity(intent) } }
            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(shp(12.dp)).background(Track), contentAlignment = Alignment.Center) {
                    if (it.img.startsWith("http")) NetImage(it.img)
                    else Text("${i + 1}", style = body(18, FontWeight.Bold, Muted))
                }
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = body(14, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it.price.ifBlank { "цена ?" }, style = body(15, FontWeight.Bold, Teal))
                        HGap(8)
                        Text("${i + 1} · ${it.store}", style = body(12, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (it.size.isNotBlank()) {
                            HGap(8)
                            // без цвета: в наличии — жирно, в сетке — серым, нет — серым зачёркнуто
                            val none = it.size.contains("нет")
                            Text(it.size, style = body(12, if (!none && (it.size.contains("наличии") || it.size.contains("есть"))) FontWeight.ExtraBold else FontWeight.SemiBold,
                                if (none || it.size.contains("сетке")) Muted else Ink).copy(textDecoration = if (none) androidx.compose.ui.text.style.TextDecoration.LineThrough else null))
                        }
                    }
                    if (it.seller.isNotBlank() || it.original) Row(verticalAlignment = Alignment.CenterVertically) {
                        if (it.original) { Text(if (it.seller.contains("Ozon Россия", true)) "✓ продаёт Ozon" else "✓ Оригинал", style = body(12, FontWeight.SemiBold, Teal)); HGap(8) }
                        if (it.seller.isNotBlank()) Text(it.seller, style = body(12, color = Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Значок файла: тип и цвет (в монохроме — чернилами темы). */
fun fileBadge(mime: String): Pair<String, Color> = when (mime) {
    Docs.XLSX -> "XLS" to if (Th.c.square) Th.c.ink else Color(0xFF1D6F42)
    Docs.PDF -> "PDF" to if (Th.c.square) Th.c.ink else Color(0xFFB3261E)
    else -> "DOC" to if (Th.c.square) Th.c.ink else Color(0xFF2B579A)
}

/** Файлы, которые сделал агент: нажатие — открыть в Word/Excel/Документах, «Отправить» — поделиться. */
@Composable
fun FileCards(files: List<Docs.FileRef>) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { f ->
            if (f.mime == Viz.MIME) { VizCard(f); return@forEach }
            Row(Modifier.fillMaxWidth().clip(shp(16.dp)).background(Card).clickable(onClickLabel = "Открыть файл") {
                runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val (tag, bg) = fileBadge(f.mime)
                Box(Modifier.size(48.dp).clip(shp(12.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Text(tag, style = body(13, FontWeight.Bold, if (Th.c.square) Th.c.bg else Color.White))
                }
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = body(14, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Загрузки/Свои · нажми, чтобы открыть", style = body(12, color = Muted))
                }
                Box(Modifier.size(44.dp).clip(shp(22.dp)).clickable(onClickLabel = "Поделиться") {
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(f.mime).putExtra(Intent.EXTRA_STREAM, f.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Отправить").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.ui.graphics.vector.rememberVectorPainter(icShareUp), "Поделиться", tint = Teal, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun NetImage(url: String) {
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                imgHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val b = r.body?.bytes() ?: return@use null
                    val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(b, 0, b.size, o)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
}

/** Лёгкая разметка ответа: **жирный**, списки «- », заголовки «#». */
fun md(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.trim().lines()
    lines.forEachIndexed { li, raw ->
        var line = raw.trimEnd()
        var heading = false
        when {
            line.trimStart().startsWith("#") -> { line = line.trimStart().trimStart('#').trim(); heading = true }
            Regex("^\\s*[-*•]\\s+").containsMatchIn(line) -> line = "•  " + line.replace(Regex("^\\s*[-*•]\\s+"), "")
        }
        if (heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.replace("**", "")) }
        else {
            val parts = line.split("**")
            parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p) }
        }
        if (li < lines.size - 1) append("\n")
    }
}


private val icSend by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("send", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.addPathNodes("M21 3L10 14M21 3l-7 18-4-7-7-4z"), stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.9f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }.build()
}

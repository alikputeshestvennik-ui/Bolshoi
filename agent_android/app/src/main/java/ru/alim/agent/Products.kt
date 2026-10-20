package ru.alim.agent

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).clickable(onClickLabel = "Открыть товар") {
                val pkg = when (it.store) { "Ozon" -> Shop.OZON_PKG; "WB" -> Shop.WB_PKG; else -> null }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(Intent(intent).apply { pkg?.let { p -> setPackage(p) } }) }
                    .onFailure { runCatching { ctx.startActivity(intent) } }
            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E242C)), contentAlignment = Alignment.Center) {
                    if (it.img.startsWith("http")) NetImage(it.img)
                    else Text("${i + 1}", style = body(18, FontWeight.Bold, Muted))
                }
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Text(it.title, style = body(14, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it.price.ifBlank { "цена ?" }, style = body(15, FontWeight.Bold, Teal))
                        HGap(8)
                        Text("${i + 1} · ${it.store}", style = body(12, color = Muted))
                        if (it.size.isNotBlank()) {
                            HGap(8)
                            Text(it.size, style = body(12, FontWeight.SemiBold, if (it.size.endsWith("есть")) Teal else Amber))
                        }
                    }
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
            parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(p) } else append(p) }
        }
        if (li < lines.size - 1) append("\n")
    }
}

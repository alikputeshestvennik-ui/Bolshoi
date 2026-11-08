package ru.alim.agent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Показываем только ссылки, присутствующие в ответе, без догадок о дате. */
@Composable
fun SourceBadges(text: String, metadata: String = "") {
    val ctx = LocalContext.current
    val sources = remember(text, metadata) {
        val found = LinkedHashMap<String, String>()
        runCatching { org.json.JSONObject(metadata).optJSONArray("sources")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> val url = x.optString("url"); if (url.startsWith("https://") || url.startsWith("http://")) found[url] = x.optString("published") } } }
        found.entries.take(8).map { it.key to it.value }
    }
    if (sources.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Источники", style = body(11, color = Muted))
        sources.forEach { (url, publication) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val host = remember(url) { runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("") }
                val label = host.substringBefore('.').take(3).uppercase(Locale.ROOT).ifBlank { "↗" }
                Box(Modifier.size(36.dp).clip(circ).background(AccentSoft).clickable(onClickLabel = "Открыть источник $host") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, contentAlignment = Alignment.Center) { Text(label, style = body(10, FontWeight.Bold, Teal)) }
                val formatted = remember(publication) { runCatching { val d = java.time.OffsetDateTime.parse(publication); d.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru"))) }.getOrElse { runCatching { java.time.LocalDate.parse(publication.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))) }.getOrDefault("") } }
                Text(if (formatted.isBlank()) "Дата публикации не указана" else "Опубликовано · $formatted", style = body(11, FontWeight.SemiBold, OnAccent), modifier = Modifier.clip(shp(8.dp)).background(Teal).padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
    }
}

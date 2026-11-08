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

fun notificationText(text: String): String = text.replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1")
    .replace("**", "").replace("__", "").replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")

private fun recordedSources(metadata: String): LinkedHashMap<String, String> {
    val found = LinkedHashMap<String, String>()
    runCatching { org.json.JSONObject(metadata).optJSONArray("sources")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> val url = x.optString("url"); if (url.startsWith("https://") || url.startsWith("http://")) found[url] = x.optString("published") } } }
    return found
}

/** Убираем текстовую строку источника только если та же ссылка доступна кнопкой. */
fun answerWithoutSourceLines(text: String, metadata: String): String {
    val urls = recordedSources(metadata).keys
    if (urls.isEmpty()) return text
    val lines = text.lines().toMutableList()
    val drop = mutableSetOf<Int>()
    lines.forEachIndexed { i, line ->
        if (line.trimStart().startsWith("Источник", ignoreCase = true)) {
            val combined = line + " " + lines.getOrElse(i + 1) { "" }
            if (urls.any { combined.contains(it) }) {
                drop += i
                if (urls.any { lines.getOrElse(i + 1) { "" }.trim().startsWith(it) }) drop += i + 1
            }
        }
    }
    return lines.filterIndexed { i, _ -> i !in drop }.joinToString("\n").trim()
}

/** Используем подтверждённые ссылки и данные, сохранённые при поиске. */
@Composable
fun SourceBadges(text: String, metadata: String = "") {
    val ctx = LocalContext.current
    val sources = remember(text, metadata) {
        val found = recordedSources(metadata)
        found.entries.take(8).map { it.key to it.value }
    }
    if (sources.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Источники", style = body(11, color = Muted))
        sources.forEach { (url, publication) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val host = remember(url) { runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("") }
                val label = host.split('.').let { parts -> parts.getOrNull(parts.size - 2).orEmpty().take(3) }.uppercase(Locale.ROOT).ifBlank { "↗" }
                Box(Modifier.size(36.dp).clip(circ).background(AccentSoft).clickable(onClickLabel = "Открыть источник $host") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, contentAlignment = Alignment.Center) { Text(label, style = body(10, FontWeight.Bold, Teal)) }
                val formatted = remember(publication) { runCatching { val d = java.time.OffsetDateTime.parse(publication); d.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru"))) }.getOrElse { runCatching { java.time.LocalDateTime.parse(publication).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru"))) }.getOrElse { runCatching { java.time.LocalDate.parse(publication.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))) }.getOrDefault("") } } }
                Text(if (formatted.isBlank()) "Дата публикации не указана" else "Опубликовано · $formatted", style = body(11, FontWeight.SemiBold, OnAccent), modifier = Modifier.clip(shp(8.dp)).background(Teal).padding(horizontal = 8.dp, vertical = 5.dp))
            }
        }
    }
}

package ru.alim.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

val LocalNavigate = staticCompositionLocalOf<(Scr) -> Unit> { {} }

/** One visible entry point for navigation; no persistent toolbar above the composer. */
@Composable
fun PageNavigation(title: String, modifier: Modifier = Modifier, search: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val go = LocalNavigate.current
    Row(modifier.fillMaxWidth().background(Bg).statusBarsPadding().padding(horizontal = 16.dp)
        .height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.clickable(onClickLabel = "Открыть разделы") { expanded = true }
                .padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("☰", style = body(22, color = Teal))
                Spacer(Modifier.width(14.dp))
                Text(title, style = body(18, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded, { expanded = false }) {
                listOf("Чат" to Scr.Home, "История" to Scr.History, "Важное" to Scr.Digest,
                    "Активные задачи" to Scr.Active, "Настройки" to Scr.Settings).forEach { (label, screen) ->
                    DropdownMenuItem(text = { Text(label, style = body(15)) },
                        onClick = { expanded = false; go(screen) })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (search != null) Text("Поиск", style = body(13, FontWeight.Medium, Teal),
            modifier = Modifier.clickable(onClick = search).padding(12.dp))
    }
}

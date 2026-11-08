package ru.alim.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
                Icon(Icons.Filled.Menu, "Разделы", tint = Teal, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(title, style = body(18, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded, { expanded = false }) {
                listOf("Чат" to Scr.Home, "История и важное" to Scr.History,
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

/** Requests and important events are two views of the same journal. */
@Composable
fun HistoryTabs(important: Boolean) {
    val go = LocalNavigate.current
    Row(Modifier.fillMaxWidth().clip(shp(16.dp)).background(Card).padding(4.dp)) {
        listOf("Запросы" to Scr.History, "Важное" to Scr.Digest).forEachIndexed { index, (label, destination) ->
            val selected = important == (index == 1)
            Box(Modifier.weight(1f).height(44.dp).clip(shp(12.dp))
                .background(if (selected) AccentSoft else androidx.compose.ui.graphics.Color.Transparent)
                .selectable(selected = selected, role = Role.Tab, onClick = { go(destination) }), contentAlignment = Alignment.Center) {
                Text(label, style = body(14, FontWeight.SemiBold, if (selected) Teal else Muted))
            }
        }
    }
}

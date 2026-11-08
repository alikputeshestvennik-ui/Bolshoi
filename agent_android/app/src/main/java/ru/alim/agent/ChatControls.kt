package ru.alim.agent

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

/** Compact controls; detailed role configuration remains in Settings. */
@Composable
fun ChatControls(go: (Scr) -> Unit) {
    var modelsOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(Prefs.fastModel) }
    var effort by remember { mutableStateOf(Prefs.effort) }
    val models = (ChatGptAccount.models + Prefs.CHAT_MODELS + model).distinct()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Text("Чат · " + Agent.short(model) + " ⌄", style = body(12, FontWeight.SemiBold), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClickLabel = "Модель чата") { modelsOpen = true }.padding(vertical = 12.dp))
            DropdownMenu(modelsOpen, { modelsOpen = false }) {
                models.forEach { id ->
                    DropdownMenuItem(text = { Text(Agent.short(id) + if (Llm.isChatGpt(id)) " · ChatGPT" else "", style = body(13)) },
                        onClick = { model = id; Prefs.fastModel = id; modelsOpen = false })
                }
                DropdownMenuItem(text = { Text("Модели для задач и фона…", style = body(13, color = Teal)) },
                    onClick = { modelsOpen = false; go(Scr.Settings) })
            }
        }
        Spacer(Modifier.width(10.dp))
        if (Llm.isAny(Prefs.smartModel)) Box {
            val levels = listOf("low" to "Быстро", "medium" to "Средне", "high" to "Тщательно")
            Text((levels.firstOrNull { it.first == effort }?.second ?: "Средне") + " ⌄", style = body(12, color = Muted),
                modifier = Modifier.clickable(onClickLabel = "Тщательность задач") { effortOpen = true }.padding(12.dp))
            DropdownMenu(effortOpen, { effortOpen = false }) {
                levels.forEach { (id, title) -> DropdownMenuItem(text = { Text(title, style = body(14)) },
                    onClick = { effort = id; Prefs.effort = id; effortOpen = false }) }
            }
        }
        Text("Токены", style = body(12, FontWeight.Medium, Teal),
            modifier = Modifier.clickable { go(Scr.History) }.padding(vertical = 12.dp))
    }
}

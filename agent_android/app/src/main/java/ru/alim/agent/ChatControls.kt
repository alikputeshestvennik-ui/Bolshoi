package ru.alim.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

internal fun visibleModels(other: List<String>): List<String> =
    if (ChatGptAccount.connected) (ChatGptAccount.models + if (Prefs.showOtherModels) other else emptyList()).distinct()
    else other.distinct()

private val brainIcon by lazy { lineIco("brain", "M12 5a3 3 0 0 0-5.5-1.6A3.4 3.4 0 0 0 4 9.1a3.8 3.8 0 0 0 .7 6.5A3.5 3.5 0 0 0 12 18V5M12 5a3 3 0 0 1 5.5-1.6A3.4 3.4 0 0 1 20 9.1a3.8 3.8 0 0 1-.7 6.5A3.5 3.5 0 0 1 12 18M8 7c0 1.5.8 2.3 2 2.5M16 7c0 1.5-.8 2.3-2 2.5M7 14c1.4 0 2.2.7 2.5 2M17 14c-1.4 0-2.2.7-2.5 2") }
private val bulbIcon by lazy { lineIco("bulb-picker", "M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.8.7 1 1.5 1 2.5h6c0-1 .2-1.8 1-2.5A6 6 0 0 0 12 3z") }

@Composable
private fun PickerButton(icon: ImageVector, label: String, accessibility: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.height(46.dp).clip(shp(12.dp)).background(AccentSoft).border(1.dp, Line, shp(12.dp))
        .clickable(onClickLabel = accessibility, onClick = onClick).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(rememberVectorPainter(icon), null, tint = Teal, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = body(12, FontWeight.SemiBold, Teal), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("⌄", style = body(14, color = Teal))
    }
}

@Composable
fun ChatControls(go: (Scr) -> Unit) {
    var modelsOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(Prefs.fastModel) }
    var effort by remember { mutableStateOf(Prefs.effort) }
    val models = visibleModels(Prefs.CHAT_MODELS)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            PickerButton(brainIcon, (if (ChatGptAccount.connected && !Llm.isChatGpt(model)) "Сторонняя · " else "") + Agent.short(model), "Выбрать модель", Modifier.fillMaxWidth()) { modelsOpen = true }
            DropdownMenu(modelsOpen, { modelsOpen = false }, modifier = Modifier.heightIn(max = 280.dp), shape = shp(16.dp), containerColor = Card,
                properties = PopupProperties(focusable = false)) {
                if (models.isEmpty()) DropdownMenuItem(text = { Text("Обновить список моделей в настройках", style = body(13, color = Muted)) }, onClick = { modelsOpen = false; go(Scr.Settings) })
                models.forEach { id ->
                    val selected = id == model
                    DropdownMenuItem(text = { Text(Agent.short(id) + if (Llm.isChatGpt(id)) " · ChatGPT" else "", style = body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal, if (selected) Teal else Ink)) },
                        modifier = Modifier.padding(horizontal = 6.dp).clip(shp(10.dp)).background(if (selected) AccentSoft else Color.Transparent),
                        trailingIcon = if (selected) ({ Icon(Icons.Filled.Check, "Выбрана", tint = Teal, modifier = Modifier.size(18.dp)) }) else null,
                        onClick = { model = id; Prefs.fastModel = id; modelsOpen = false })
                }
                DropdownMenuItem(text = { Text("Модели для задач и фона…", style = body(13, color = Teal)) }, onClick = { modelsOpen = false; go(Scr.Settings) })
            }
        }
        if (Llm.isChatGpt(model) || Llm.isAny(Prefs.smartModel)) Box(Modifier.weight(1f)) {
            val levels = listOf("low" to "Быстро", "medium" to "Средне", "high" to "Тщательно")
            PickerButton(bulbIcon, levels.firstOrNull { it.first == effort }?.second ?: "Средне", "Уровень размышлений", Modifier.fillMaxWidth()) { effortOpen = true }
            DropdownMenu(effortOpen, { effortOpen = false }, shape = shp(16.dp), containerColor = Card, properties = PopupProperties(focusable = false)) {
                levels.forEach { (id, title) ->
                    val selected = effort == id
                    DropdownMenuItem(text = { Text(title, style = body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal, if (selected) Teal else Ink)) },
                        modifier = Modifier.padding(horizontal = 6.dp).clip(shp(10.dp)).background(if (selected) AccentSoft else Color.Transparent),
                        trailingIcon = if (selected) ({ Icon(Icons.Filled.Check, "Выбрано", tint = Teal, modifier = Modifier.size(18.dp)) }) else null,
                        onClick = { effort = id; Prefs.effort = id; effortOpen = false })
                }
            }
        }
    }
}

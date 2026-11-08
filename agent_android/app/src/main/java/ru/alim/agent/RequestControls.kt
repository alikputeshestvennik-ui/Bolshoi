package ru.alim.agent

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun RequestControls() {
    val ctx = LocalContext.current
    var model by remember { mutableStateOf(Prefs.chatGptModel) }
    var effort by remember { mutableStateOf(Prefs.reasoningLevel) }
    var modelsOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    val models = ChatGptAccount.models
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                val activeModel = if (Prefs.developerModels) Prefs.fastModel else model
                Text(activeModel.removePrefix("chatgpt:") + "  ▾", modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Bg).clickable { modelsOpen = true }.padding(10.dp), style = body(13, FontWeight.SemiBold))
                DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                    if (models.isEmpty()) DropdownMenuItem(text = { Text("Подключите ChatGPT в настройках") }, onClick = { modelsOpen = false })
                    models.forEach { m -> DropdownMenuItem(text = { Text(m.removePrefix("chatgpt:")) }, onClick = {
                        model = m; Prefs.chatGptModel = m; Prefs.developerModels = false; modelsOpen = false
                    }) }
                }
            }
            Box {
                val label = when (effort) { "low" -> "Быстро"; "high" -> "Глубоко"; else -> "Средне" }
                Text("$label  ▾", modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Bg)
                    .clickable { effortOpen = true }.padding(10.dp), style = body(13, FontWeight.SemiBold))
                DropdownMenu(expanded = effortOpen, onDismissRequest = { effortOpen = false }) {
                    listOf("low" to "Быстро", "medium" to "Средне", "high" to "Глубоко").forEach { (v, title) ->
                        DropdownMenuItem(text = { Text(title) }, onClick = { effort = v; Prefs.reasoningLevel = v; effortOpen = false })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (Prefs.developerModels) "Модели разработчика включены" else "Используется подписка ChatGPT",
                style = body(12, color = Muted), modifier = Modifier.weight(1f))
            Text("Расход ▾", style = body(12, FontWeight.SemiBold, Teal), modifier = Modifier.clickable { usageOpen = !usageOpen })
        }
        if (usageOpen) {
            Text("Токены завершённых запросов в Тени с момента обновления: вход ${Prefs.usageInput}, выход ${Prefs.usageOutput}.", style = body(12, color = Muted))
            Text("Остаток общего лимита проверь в ChatGPT", style = body(12, FontWeight.SemiBold, Teal),
                modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) })
        }
    }
}

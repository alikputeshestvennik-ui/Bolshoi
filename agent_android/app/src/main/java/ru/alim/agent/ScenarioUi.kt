package ru.alim.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ScenarioList(onNew: () -> Unit, onEdit: (Scenarios.Item) -> Unit, go: (Scr) -> Unit) {
    val ctx = LocalContext.current
    var remove by remember { mutableStateOf<Scenarios.Item?>(null) }
    var notice by remember { mutableStateOf("") }
    if (notice.isNotBlank()) Text(notice, style = body(13, color = Muted))
    if (Scenarios.items.isEmpty()) CardBox { Text("Сценариев пока нет", style = body(15, FontWeight.SemiBold)); Text("Сохрани поручение, чтобы запускать его снова.", style = body(13, color = Muted)) }
    Scenarios.items.forEach { item ->
        CardBox {
            Text(item.title, style = body(16, FontWeight.SemiBold))
            Text(item.instruction, style = body(13, color = Muted))
            val whenText = when (item.schedule) { "daily" -> "Каждый день"; "weekdays" -> "По будням"; "custom" -> listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").filterIndexed { i, _ -> item.days and (1 shl i) != 0 }.joinToString(", "); else -> "Вручную" }
            Text(whenText + if (item.schedule == "manual") "" else " · %02d:%02d".format(item.hour, item.minute) + if (item.paused) " · пауза" else "", style = body(12, color = Muted))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillBtn("Запустить", true, Modifier.weight(1f)) { if (Scenarios.run(item)) go(Scr.Home) else notice = "Нет сети или Тень занят. Попробуй позже." }
                PillBtn("Изменить", false, Modifier.weight(1f)) { onEdit(item) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (item.schedule != "manual") Text(if (item.paused) "Возобновить" else "Пауза", style = body(13, FontWeight.Medium, Teal), modifier = Modifier.clickable { Scenarios.save(ctx, item.copy(paused = !item.paused, nextAt = if (item.paused) Scenarios.next(item.schedule, item.hour, item.minute, days = item.days) else 0L)) }.padding(vertical = 6.dp))
                Text("Удалить", style = body(13, FontWeight.Medium, Muted), modifier = Modifier.clickable { remove = item }.padding(vertical = 6.dp))
            }
        }
    }
    PillBtn("+ Новый сценарий", false, Modifier.fillMaxWidth(), onNew)
    remove?.let { item -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Удалить сценарий?") }, text = { Text(item.title) }, confirmButton = { TextButton(onClick = { Scenarios.delete(ctx, item.id); remove = null }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Отмена") } }) }
}

@Composable
fun ScenarioEditor(item: Scenarios.Item?, onBack: () -> Unit, onSave: (Scenarios.Item) -> Unit) {
    var title by remember(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var instruction by remember(item?.id) { mutableStateOf(item?.instruction.orEmpty()) }
    var schedule by remember(item?.id) { mutableStateOf(item?.schedule ?: "manual") }
    var hour by remember(item?.id) { mutableStateOf((item?.hour ?: 9).toString()) }
    var minute by remember(item?.id) { mutableStateOf("%02d".format(item?.minute ?: 0)) }
    var days by remember(item?.id) { mutableIntStateOf(item?.days ?: 127) }
    var changesOnly by remember(item?.id) { mutableStateOf(item?.changesOnly ?: false) }
    var error by remember { mutableStateOf("") }
    Screen(if (item == null) "Новый сценарий" else "Изменить сценарий", onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = LocalTopPad.current, bottom = LocalBarPad.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
            OutlinedTextField(instruction, { instruction = it }, Modifier.fillMaxWidth(), label = { Text("Что делать") }, minLines = 3)
            Text("Запуск", style = body(14, FontWeight.SemiBold))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("manual" to "Вручную", "daily" to "Ежедневно", "weekdays" to "Будни", "custom" to "Дни").forEach { (id, label) ->
                    Box(Modifier.weight(1f).clip(shp(12.dp)).background(if (schedule == id) AccentSoft else Card).clickable { schedule = id }.padding(vertical = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(label, style = body(12, FontWeight.Medium, if (schedule == id) Teal else Muted)) }
                }
            }
            if (schedule == "custom") {
                Text("Дни недели", style = body(14, FontWeight.SemiBold))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { i, label ->
                        val selected = days and (1 shl i) != 0
                        Box(Modifier.weight(1f).clip(shp(10.dp)).background(if (selected) AccentSoft else Card).clickable { days = days xor (1 shl i) }.padding(vertical = 10.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(label, style = body(12, FontWeight.Medium, if (selected) Teal else Muted)) }
                    }
                }
            }
            if (schedule != "manual") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("Часы") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("Минуты") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
            if (schedule != "manual") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Уведомлять только при изменениях", style = body(13, FontWeight.Medium))
                        Text("Сравниваются полные ответы; результат остаётся в чате.", style = body(11, color = Muted))
                    }
                    androidx.compose.material3.Switch(checked = changesOnly, onCheckedChange = { changesOnly = it })
                }
            }
            if (error.isNotBlank()) Text(error, style = body(13, color = Muted))
            PillBtn("Сохранить", true, Modifier.fillMaxWidth()) {
                val h = hour.toIntOrNull(); val m = minute.toIntOrNull()
                if (title.isBlank() || instruction.isBlank()) error = "Укажи название и поручение."
                else if (schedule != "manual" && (h == null || h !in 0..23 || m == null || m !in 0..59)) error = "Укажи время от 00:00 до 23:59."
                else if (schedule == "custom" && days == 0) error = "Выбери хотя бы один день."
                else { val hh = h ?: 9; val mm = m ?: 0; val paused = item?.paused ?: false; onSave(Scenarios.Item(item?.id ?: System.currentTimeMillis(), title.trim(), instruction.trim(), schedule, hh, mm, paused, if (paused) 0 else Scenarios.next(schedule, hh, mm, days = days), days, changesOnly, if (item?.instruction == instruction.trim() && item.changesOnly == changesOnly) item.lastResult else "")) }
            }
        }
    }
}

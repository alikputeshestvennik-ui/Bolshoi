package ru.alim.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LightTaskScreen(back: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()
        .padding(horizontal = 20.dp).padding(bottom = LocalBarPad.current),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Ink,
                modifier = Modifier.size(42.dp).clickable(onClickLabel = "Назад") { back() }.padding(10.dp))
            Spacer(Modifier.width(8.dp))
            Text("Задача", style = body(20, FontWeight.SemiBold))
        }
        CardBox {
            Text(when (Agent.phase) {
                Agent.Phase.WORKING -> "Выполняю"
                Agent.Phase.CONFIRM -> "Нужно подтверждение"
                Agent.Phase.DONE -> if (Agent.failed) "Не удалось завершить" else "Готово"
                else -> "Задача"
            }, style = body(12, FontWeight.Medium, Muted))
            Text(Agent.task, style = body(19, FontWeight.SemiBold, Ink), lineHeight = 26.sp)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp)) {
            items(Agent.steps) { step ->
                CardBox {
                    Text((if (step.done) "✓  " else "·  ") + step.text, style = body(15, FontWeight.Medium, Ink))
                    if (step.think.isNotBlank()) Text(step.think, style = body(13, color = Muted), lineHeight = 19.sp)
                    if (step.detail.isNotBlank()) Text(step.detail, style = body(12, color = Muted), maxLines = 4)
                }
            }
            Agent.pending?.let { pending -> item {
                CardBox {
                    Text(pending.text, style = body(15, FontWeight.SemiBold, Ink))
                    BigButton("Да, делай", { Agent.answer(true) }, fg = OnAccent)
                    BigButton("Нет", { Agent.answer(false) }, bg = Card, fg = Ink, border = Line)
                }
            } }
            if (Agent.photos.isNotEmpty()) item { PhotoGrid(Agent.photos.toList()) }
            if (Agent.ytVideos.isNotEmpty()) item { YtGrid(Agent.ytVideos.toList()) }
            if (Agent.products.isNotEmpty()) item { ProductCards(Agent.products.toList()) }
            if (Agent.files.isNotEmpty()) item { FileCards(Agent.files.toList()) }
            if (Agent.phase == Agent.Phase.DONE) item {
                CardBox { Text(md(Agent.result), style = body(16, color = Ink), lineHeight = 23.sp) }
            }
            if (Agent.phase == Agent.Phase.DONE && Agent.main.lastButtons.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Agent.main.lastButtons.forEach { text -> Chip(text) { Chat.userEcho(text); Agent.followUp(text) } }
                }
            }
        }
        if (Agent.busy) BigButton("Остановить", { Agent.stop() }, bg = Card, fg = Ink, border = Line)
    }
}

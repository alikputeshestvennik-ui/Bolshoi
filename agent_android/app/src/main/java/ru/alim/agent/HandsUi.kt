package ru.alim.agent

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun HandsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val on = Hands.ready && tick >= 0
    var task by remember { mutableStateOf("") }
    var hands by remember { mutableStateOf(Prefs.handsModel) }

    Screen("Руки на телефоне", back) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Доступ к экрану", if (on) "включён" else "выключен", statusColor = if (on) Muted else Amber) {
                Text("${Friends.main.name} сможет сам открывать приложения и нажимать на экране, когда ты попросишь, например «закажи такси домой» или «ответь Егору, что опоздаю».",
                    style = body(13, color = Muted), lineHeight = 18.sp)
                if (!on) {
                    Text("Включи: Настройки → Спец. возможности → «Свои» → Включить. На Samsung сначала может понадобиться: Приложения → «Свои» → ⋮ → «Разрешить ограниченные настройки».",
                        style = body(13, color = Amber), lineHeight = 18.sp)
                    BigButton("Открыть спец. возможности", {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                } else {
                    BigButton("Настройки спец. возможностей", {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, bg = Card, fg = Ink, border = Line)
                }
            }

            if (on) Section("Передать телефон", "по задаче") {
                Text("Опиши, что сделать. ${Friends.main.name} возьмёт телефон, а ты будешь видеть плашку сверху с кнопкой «Стоп».", style = body(13, color = Muted), lineHeight = 18.sp)
                OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth(), label = { Text("Что сделать") },
                    textStyle = body(14), shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), minLines = 1, maxLines = 3)
                BigButton(if (HandsAgent.active) "${Friends.main.name} сейчас управляет…" else "Передать телефон", {
                    if (!HandsAgent.active && task.isNotBlank()) { HandsAgent.start(ctx, Friends.MAIN, task); back() }
                })
                Text("Проще — просто написать это в чат: ${Friends.main.name} сам поймёт, что нужны руки.", style = body(12, color = Muted))
            }

            Section("Безопасность", "как это защищено") {
                listOf(
                    "Работает только по твоей просьбе — сам, по уведомлениям, не лезет.",
                    "Отправить, заказать, оформить, удалить — только после твоего «Подтвердить».",
                    "Переводы денег внутри банков не делает. Посмотреть баланс и траты — может.",
                    "PIN, пароли, коды из SMS не вводит и не зачитывает — останавливается и просит тебя.",
                    "Данные карты не вводит: доводит до оплаты, платишь сам.",
                ).forEach { Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•  ", style = body(14, FontWeight.Bold, Teal)); Text(it, style = body(13), lineHeight = 18.sp)
                } }
            }

            Section("Модель для рук", Agent.short(hands)) {
                Text("Нужна зоркая модель (видит экран). Gemini 3.8 — бесплатно (нужен VPN), запасной — Claude Haiku.", style = body(12, color = Muted))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("google:gemini-3.8-flash" to "Gemini 3.8 · free", "google:gemini-3.5-flash-lite" to "Gemini Lite · free",
                        "claude-5.5-sonnet" to "Claude Sonnet", "claude-5.5-haiku" to "Claude Haiku").forEach { (id, label) ->
                        Chip2(label, hands == id) { hands = id; Prefs.handsModel = id }
                    }
                }
            }
            Gap(8)
        }
    }
}

@Composable
private fun Chip2(label: String, on: Boolean, click: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) AccentSoft else Card)
            .border(1.dp, if (on) Teal else Line, RoundedCornerShape(12.dp)).clickable { click() }
            .padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, style = body(13, FontWeight.SemiBold, if (on) Teal else Ink))
    }
}

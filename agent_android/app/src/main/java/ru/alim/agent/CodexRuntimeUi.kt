package ru.alim.agent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun CodexRuntimePanel() {
    val ctx = LocalContext.current
    val state = EmbeddedRuntime.state
    val accent = when (state) {
        EmbeddedRuntime.State.READY -> Teal
        EmbeddedRuntime.State.ERROR -> Danger
        EmbeddedRuntime.State.PREPARING, EmbeddedRuntime.State.DOWNLOADING, EmbeddedRuntime.State.STARTING -> Amber
        EmbeddedRuntime.State.OFF -> Muted
    }
    val title = when (state) {
        EmbeddedRuntime.State.READY -> "Готово"
        EmbeddedRuntime.State.ERROR -> "Ошибка"
        EmbeddedRuntime.State.PREPARING -> "Подготовка"
        EmbeddedRuntime.State.DOWNLOADING -> "Загрузка Codex"
        EmbeddedRuntime.State.STARTING -> "Запуск"
        EmbeddedRuntime.State.OFF -> "Не запущено"
    }
    var manual by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Локальное окружение Codex", style = body(15, FontWeight.SemiBold))
        Text("$title · ${EmbeddedRuntime.detail}", style = body(12, color = accent))
        if (state == EmbeddedRuntime.State.PREPARING || state == EmbeddedRuntime.State.DOWNLOADING || state == EmbeddedRuntime.State.STARTING) {
            LinearProgressIndicator(progress = { EmbeddedRuntime.progress }, modifier = Modifier.fillMaxWidth().height(6.dp), color = Teal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(
                if (state == EmbeddedRuntime.State.READY) "Перезапустить" else "Запустить окружение",
                {
                    if (state == EmbeddedRuntime.State.READY) EmbeddedRuntime.restart(ctx) else EmbeddedRuntime.startAsync(ctx)
                },
                modifier = Modifier.weight(1f),
            )
            if (state == EmbeddedRuntime.State.READY || state == EmbeddedRuntime.State.ERROR) {
                BigButton("Остановить", { EmbeddedRuntime.stop() }, bg = Card, fg = Ink, border = Line, modifier = Modifier.weight(1f))
            }
        }
        Text(
            "Первый запуск распаковывает Linux, ставит Python и Git и скачивает Codex (~110 МБ). " +
                "Дальше всё работает офлайн, а после перезагрузки окружение поднимается само.",
            style = body(11, color = Muted),
        )
        Text(
            if (ChatGptAccount.connected) "ChatGPT: подключён${if (ChatGptAccount.email.isBlank()) "" else " · ${ChatGptAccount.email}"}"
            else "GPT: войди в ChatGPT в разделе «Подключения» — ключ подхватится автоматически.",
            style = body(11, color = if (ChatGptAccount.connected) Muted else Amber),
        )
        var floating by remember { mutableStateOf(Prefs.codexOverlay) }
        val canOverlay = Settings.canDrawOverlays(ctx)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Плавающее PiP-окно", style = body(14, FontWeight.SemiBold))
                Text("Reasoning и последний ответ поверх других приложений.", style = body(11, color = Muted))
            }
            Toggle(floating) { on ->
                floating = on
                Prefs.codexOverlay = on
                if (on) {
                    if (Settings.canDrawOverlays(ctx)) CodexOverlayService.start(ctx, Prefs.codexActiveTask)
                    else runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                } else {
                    CodexOverlayService.stop(ctx)
                }
            }
        }
        if (floating && !canOverlay) {
            BigButton("Разрешить окно поверх приложений", {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }, bg = Card, fg = Ink, border = Line)
        } else if (floating) {
            BigButton("Показать окно сейчас", { CodexOverlayService.start(ctx, Prefs.codexActiveTask) }, bg = Card, fg = Ink, border = Line)
        }
        TextButton({ manual = !manual }) {
            Text(if (manual) "Скрыть внешний runtime" else "Внешний runtime и ручной ключ", style = body(12, color = Teal))
        }
        if (manual) {
            Text("Нужно только если Codex запущен отдельно на Linux-машине или в Termux. Для встроенного окружения ключ не требуется.", style = body(11, color = Muted))
            OutlinedTextField(
                token,
                { token = it },
                Modifier.fillMaxWidth(),
                label = { Text("Ключ локального окружения") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            BigButton("Подключить вручную", {
                message = runCatching {
                    CodexGateway.saveLocalToken(ctx, token); token = ""; "Ключ сохранён"
                }.getOrElse { it.message.orEmpty() }
            }, bg = Card, fg = Ink, border = Line)
            if (message.isNotBlank()) Text(message, style = body(11, color = Muted))
        }
    }
}

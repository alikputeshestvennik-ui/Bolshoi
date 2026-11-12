package ru.alim.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp

@Composable
fun CodexSettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { back() }
    Screen("Настройки Codex", back) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(top = LocalTopPad.current, bottom = LocalBarPad.current),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CodexRuntimePanel()
            CardBox {
                Text("Провайдеры", style = body(16, FontWeight.SemiBold))
                Text(
                    if (ChatGptAccount.connected) "GPT подключён через аккаунт ChatGPT${if (ChatGptAccount.email.isBlank()) "" else " · ${ChatGptAccount.email}"}."
                    else "GPT подключается через вход в ChatGPT в разделе «Подключения».",
                    style = body(12, color = if (ChatGptAccount.connected) Muted else Amber),
                )
                listOf("deepseek" to "DeepSeek", "any" to "AnyModel").forEach { (id, name) ->
                    var key by remember(id) { mutableStateOf("") }
                    var status by remember(id) { mutableStateOf("") }
                    Text(name, style = body(14, FontWeight.SemiBold))
                    OutlinedTextField(
                        key,
                        { key = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Ключ $name") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    BigButton("Сохранить ключ $name", {
                        status = runCatching {
                            CodexGateway.saveProviderToken(ctx, id, key)
                            key = ""
                            "Сохранено"
                        }.getOrElse { it.message.orEmpty() }
                    }, bg = Card, fg = Ink, border = Line)
                    if (status.isNotBlank()) Text(status, style = body(12, color = Muted))
                }
            }
            CardBox {
                Text("Другой OpenAI-совместимый API", style = body(16, FontWeight.SemiBold))
                var customUrl by remember { mutableStateOf(runCatching { CodexGateway.customUrl(ctx) }.getOrDefault("")) }
                var customModel by remember { mutableStateOf(Prefs.compatibleModel) }
                var customKey by remember { mutableStateOf("") }
                var customStatus by remember { mutableStateOf("") }
                OutlinedTextField(customUrl, { customUrl = it }, Modifier.fillMaxWidth(), label = { Text("Базовый HTTPS-адрес, например https://api.example.com/v1") }, singleLine = true)
                OutlinedTextField(customModel, { customModel = it }, Modifier.fillMaxWidth(), label = { Text("Идентификатор модели") }, singleLine = true)
                OutlinedTextField(customKey, { customKey = it }, Modifier.fillMaxWidth(), label = { Text("Ключ провайдера") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
                BigButton("Сохранить провайдера", {
                    customStatus = runCatching {
                        CodexGateway.saveCustom(ctx, customUrl, customKey, customModel)
                        customKey = ""
                        "Сохранено"
                    }.getOrElse { it.message ?: "Ошибка" }
                }, bg = Card, fg = Ink, border = Line)
                if (customStatus.isNotBlank()) Text(customStatus, style = body(12, color = Muted))
                Text(
                    "Нужен HTTPS-адрес API (обычно с /v1) и модель с поддержкой инструментов. Ключ хранится зашифрованным на телефоне.",
                    style = body(12, color = Muted), lineHeight = 16.sp,
                )
            }
            Text(
                "Codex работает на телефоне: Python, Git, ripgrep, архивы и файлы доступны без Termux. GitHub-токен уже передаётся из секретов сборки.",
                style = body(12, color = Muted), lineHeight = 17.sp,
            )
        }
    }
}

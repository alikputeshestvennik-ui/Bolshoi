package ru.alim.agent

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun ChatGptSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun action(block: suspend () -> String) {
        if (running) return
        running = true
        scope.launch {
            try { message = block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Не удалось подключить ChatGPT" }
            finally { running = false }
        }
    }
    Text("Подключи свой аккаунт и разреши Тени использовать подписку. Вход откроется в браузере. После входа выбери модель ChatGPT в разделах «Чат», «Руки» или «Мониторинг».", style = body(12, color = Muted))
    if (message.isNotBlank()) Text(message, style = body(12))
    if (running) Text("Ожидание… Если открыт браузер, заверши вход и вернись сюда.", style = body(12))
    BigButton("Continue with ChatGPT", { action { ChatGptAccount.signIn(ctx); ChatGptAccount.status } })
    if (running) BigButton("Отменить вход", { ChatGptAccount.cancel() })
    if (ChatGptAccount.connected) {
        Text(if (ChatGptAccount.allowed) "Использование подписки разрешено" else "Использование подписки не разрешено", style = body(12))
        BigButton("Обновить список моделей", { action { withContext(Dispatchers.IO) {
            ChatGptAccount.refreshModels(); "Получено моделей: ${ChatGptAccount.models.size}"
        } } })
        BigButton("Выйти", { action { withContext(Dispatchers.IO) { ChatGptAccount.signOut() } } })
    }
    BigButton("Открыть ChatGPT — лимиты и доступ", {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")))
    })
}

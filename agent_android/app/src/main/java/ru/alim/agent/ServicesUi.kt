package ru.alim.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VkScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var login by remember { mutableStateOf(false) }
    if (login && !Vk.ready) {
        WebLogin(Vk.authUrl, Vk.REDIRECT, onRedirect = { url -> if (Vk.onRedirect(url)) { login = false } }, back = { login = false })
        return
    }
    Screen("ВКонтакте", back) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("ВКонтакте", if (Vk.ready) "подключён${if (Vk.me.isNotBlank()) " · ${Vk.me}" else ""}" else "не подключён",
                statusColor = if (Vk.ready) Muted else Amber) {
                Text("Пилот покажет твою ленту ВК и стены людей и сообществ. Личные сообщения ВК сторонним приложениям обычно не отдаёт — это ограничение самого ВК.",
                    style = body(13, color = Muted), lineHeight = 18.sp)
                if (!Vk.configured) Text("В сборке нет ключей ВК (VK_APP_ID / VK_SECURE_KEY).", style = body(13, color = Amber))
                else if (Vk.ready) BigButton("Выйти из ВК", { Vk.logout() }, bg = Card, fg = Ink, border = Line)
                else BigButton("Подключить ВКонтакте", { login = true })
            }
        }
    }
}

@Composable
fun MusicScreen(back: () -> Unit) {
    var login by remember { mutableStateOf(false) }
    if (login && !YaMusic.ready) {
        WebLogin(YaMusic.authUrl, YaMusic.REDIRECT, onRedirect = { url -> if (YaMusic.onRedirect(url)) { login = false } }, back = { login = false })
        return
    }
    Screen("Яндекс Музыка", back) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Яндекс Музыка", if (YaMusic.ready) "подключена" else "не подключена",
                statusColor = if (YaMusic.ready) Muted else Amber) {
                Text("Пилот найдёт трек или альбом, покажет твои плейлисты и поставит музыку в приложении Яндекс Музыки. Вход — через твой аккаунт Яндекса (тот, где подписка).",
                    style = body(13, color = Muted), lineHeight = 18.sp)
                if (YaMusic.ready) BigButton("Выйти", { YaMusic.logout() }, bg = Card, fg = Ink, border = Line)
                else BigButton("Подключить Яндекс Музыку", { login = true })
            }
        }
    }
}

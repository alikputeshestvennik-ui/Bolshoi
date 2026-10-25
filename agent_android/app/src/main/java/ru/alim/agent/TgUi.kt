package ru.alim.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TgScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { Tg.start(ctx) }

    Screen("Telegram", back) {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val st = Tg.state
            val status = when (st) {
                Tg.St.READY -> "подключён${if (Tg.me.isNotBlank()) " · ${Tg.me}" else ""}"
                Tg.St.OFF, Tg.St.STARTING -> "запуск…"
                Tg.St.NO_KEYS -> "нет ключей"
                Tg.St.WAIT_PHONE -> "нужен номер"
                Tg.St.WAIT_CODE -> "нужен код"
                Tg.St.WAIT_PASSWORD -> "нужен пароль"
                Tg.St.ERROR -> "ошибка"
            }
            Section("Личный Telegram", status, statusColor = if (st == Tg.St.READY) Muted else Amber) {
                Text("Пилот подключается к твоему Telegram как сторонний клиент (Telegram это разрешает) на твоих ключах. " +
                    "Сможет показывать твои чаты и искать по переписке, а писать — только по твоей просьбе и с подтверждением. " +
                    "Коды входа он не читает и не пересылает.", style = body(13, color = Muted), lineHeight = 18.sp)

                if (st == Tg.St.NO_KEYS) Text("В сборке нет ключей Telegram. Добавь в секреты GitHub TG_API_ID и TG_API_HASH (с my.telegram.org) и пересобери.",
                    style = body(13, color = Amber), lineHeight = 18.sp)
                if (st == Tg.St.ERROR) Text(Tg.error, style = body(13, color = Amber))

                when (st) {
                    Tg.St.WAIT_PHONE -> {
                        Field("Номер телефона (+7…)", phone, KeyboardType.Phone) { phone = it }
                        BigButton("Получить код", { if (phone.isNotBlank()) Tg.sendPhone(phone) })
                    }
                    Tg.St.WAIT_CODE -> {
                        if (Tg.hint.isNotBlank()) Text(Tg.hint, style = body(13, color = Muted))
                        Field("Код из Telegram", code, KeyboardType.Number) { code = it }
                        BigButton("Подтвердить", { if (code.isNotBlank()) Tg.sendCode(code) })
                    }
                    Tg.St.WAIT_PASSWORD -> {
                        Text("Облачный пароль (двухэтапная проверка)" + (if (Tg.hint.isNotBlank()) " · подсказка: ${Tg.hint}" else ""),
                            style = body(13, color = Muted))
                        OutlinedTextField(pwd, { pwd = it }, Modifier.fillMaxWidth(), label = { Text("Пароль") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(), textStyle = body(14),
                            shape = RoundedCornerShape(14.dp), colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                        BigButton("Войти", { if (pwd.isNotBlank()) Tg.sendPassword(pwd) })
                    }
                    Tg.St.READY -> {
                        Text("Готово. Теперь спрашивай в чате: «что нового в телеге?», «что писал Егор?», «найди в телеге адрес склада».",
                            style = body(13, color = Muted), lineHeight = 18.sp)
                        BigButton("Выйти из Telegram", { Tg.logout() }, bg = Card, fg = Ink, border = Line)
                    }
                    else -> {}
                }
            }
        }
    }
}

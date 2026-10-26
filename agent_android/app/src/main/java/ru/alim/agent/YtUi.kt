package ru.alim.agent

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Миниатюра видео по URL превью. */
@Composable
private fun netThumb(id: String): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val c = java.net.URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                c.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    return state.value
}

/** Сетка видео YouTube: превью + название, по тапу открывает видео в приложении YouTube (или в браузере). */
@Composable
fun YtGrid(entries: List<String>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { e ->
            val p = e.split('\t')
            val id = p.getOrNull(0).orEmpty()
            val title = p.getOrNull(1).orEmpty()
            val chan = p.getOrNull(2).orEmpty()
            if (id.isBlank()) return@forEach
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card).clickable { openYtVideo(ctx, id) }
                .padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(8.dp)).background(Bg), contentAlignment = Alignment.Center) {
                    netThumb(id)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Text("▶", style = body(15, color = Color.White))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = body(13, FontWeight.Medium), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (chan.isNotBlank()) Text(chan, style = body(11, color = Muted), maxLines = 1)
                }
            }
        }
    }
}

/** Открыть видео в приложении YouTube, если не вышло — в браузере. */
fun openYtVideo(ctx: android.content.Context, id: String) {
    val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id"))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id"))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(app) }.onFailure { runCatching { ctx.startActivity(web) } }
}

@Composable
fun YtScreen(back: () -> Unit) {
    var login by remember { mutableStateOf(false) }
    if (login && !YtMe.ready) {
        WebLogin(YtMe.authUrl, YtMe.REDIRECT, onRedirect = { url -> if (YtMe.onRedirect(url)) login = false }, back = { login = false })
        return
    }
    var pickFavs by remember { mutableStateOf(false) }
    Screen("YouTube", back) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val st = when { !YtMe.configured -> "нет ключей (YT_CLIENT_ID)"; YtMe.ready -> YtMe.me.ifBlank { "подключён" }; else -> "не подключён" }
            Section("YouTube", st, statusColor = if (YtMe.ready) Muted else Amber) {
                Text("Пилот покажет новое из подписок, твои лайкнутые видео и добавит ролик в твой плейлист — всё прямо здесь, без перехода в приложение YouTube. Вход — через твой Google-аккаунт. Нужен VPN.",
                    style = body(13, color = Muted), lineHeight = 18.sp)
                if (!YtMe.configured) Text("Добавь секреты YT_CLIENT_ID и YT_CLIENT_SECRET в сборку и обнови приложение.", style = body(12, color = Amber))
                else if (YtMe.ready) {
                    Text(if (YtMe.favs.isEmpty()) "любимые каналы не выбраны — показываю новое по всем подпискам" else "любимых каналов: ${YtMe.favs.size}", style = body(12, color = Muted))
                    BigButton("Выбрать любимые каналы", { pickFavs = true }, bg = Card, fg = Ink, border = Line)
                    BigButton("Выйти", { YtMe.logout() }, bg = Card, fg = Ink, border = Line)
                } else BigButton("Подключить YouTube", { login = true })
                if (YtMe.lastError.isNotBlank()) Text(YtMe.lastError, style = body(12, color = Amber))
            }
        }
    }
    if (pickFavs) FavPicker { pickFavs = false }
}

/** Выбор любимых каналов из подписок Алима. */
@Composable
private fun FavPicker(close: () -> Unit) {
    val subs = produceState<List<YtMe.Chan>?>(null) { value = withContext(Dispatchers.IO) { runCatching { YtMe.subscriptions() }.getOrNull() } }
    val chosen = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(YtMe.favs.map { it.id }) } }
    Dialog(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Любимые каналы", style = body(16, FontWeight.SemiBold))
            val list = subs.value
            when {
                list == null -> Text("загружаю подписки…", style = body(13, color = Muted))
                list.isEmpty() -> Text("подписок не нашёл", style = body(13, color = Muted))
                else -> Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    list.forEach { ch ->
                        val on = chosen.contains(ch.id)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            if (on) chosen.remove(ch.id) else chosen.add(ch.id)
                        }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Teal else Bg), contentAlignment = Alignment.Center) {
                                if (on) Text("✓", style = body(13, color = Bg))
                            }
                            Text(ch.title, style = body(13), maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (list != null) BigButton("Сохранить", {
                YtMe.saveFavs(list.filter { chosen.contains(it.id) }); close()
            })
        }
    }
}

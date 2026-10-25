package ru.alim.agent

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon as AIcon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Быстрая шторка поверх всего: открывается из выделенного текста («Пилот» в меню рядом с «Копировать»),
 * из «Поделиться», по боковой кнопке (если Пилот — помощник по умолчанию) и из плитки в шторке.
 * Клавиатура сразу открыта, ответ — карточкой, смахнул — ты там же, где был. Переписка та же, что в приложении.
 */
class QuickActivity : ComponentActivity() {
    private var kind by mutableStateOf("ask")
    private var source by mutableStateOf("")
    private var files by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        Prefs.init(this); Th.load()
        Agent.app = applicationContext
        Chat.init(this); Online.init(this); Fonts.load(assets)
        read(intent)
        setContent { QuickSheet(kind, source, files, close = { finish() }, expand = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); read(intent) }
    override fun onResume() { super.onResume(); AppState.foreground = true }
    override fun onPause() { AppState.foreground = false; super.onPause() }

    private fun read(i: Intent?) {
        kind = "ask"; source = ""; files = 0
        when (i?.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                source = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty().trim()
                if (source.isNotBlank()) kind = "text"
            }
            Intent.ACTION_SEND -> {
                val t = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n").trim()
                @Suppress("DEPRECATION") val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) { Attach.add(this, listOf(uri)); files = 1; kind = "file" }
                if (t.isNotBlank()) { source = t; if (kind == "ask") kind = "text" }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION") val uris = i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                if (uris.isNotEmpty()) { Attach.add(this, uris); files = uris.size; kind = "file" }
            }
        }
    }
}

@Composable
private fun QuickSheet(kind: String, source: String, files: Int, close: () -> Unit, expand: () -> Unit) {
    val chat = remember { Chat.main }
    val core = chat.core
    val f = Friends.main
    val startId = remember { chat.msgs.lastOrNull()?.id ?: 0L }
    var text by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() }; kb?.show() }

    fun send(t: String) {
        val q = t.trim()
        if (q.isEmpty() && files == 0) return
        val full = when {
            kind == "text" && source.isNotBlank() -> "${q.ifBlank { "Посмотри" }}\n\n«${source.take(4000)}»"
            else -> q.ifBlank { "Что это?" }
        }
        chat.send(full); text = ""; asked = true
    }

    val chips = when (kind) {
        "text" -> listOf("Объясни", "Переведи", "Перескажи коротко", "Что ответить?", "Исправь ошибки")
        "file" -> listOf("Что это?", "Найди дешевле", "Что ответить?", "Перескажи")
        else -> emptyList()
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Th.c.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .navigationBarsPadding().imePadding().padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(34.dp, online = Online.online, f = f)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = body(16, FontWeight.Bold))
                    Text(if (core.busy) (core.steps.lastOrNull()?.text ?: "думаю…") else "Пилот", style = body(12, color = if (core.busy) Teal else Muted),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Открыть чат", style = body(13, FontWeight.SemiBold, Teal), modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { expand() }.padding(8.dp))
            }

            // что Алим выделил или чем поделился
            if (!asked && (source.isNotBlank() || files > 0)) {
                Spacer(Modifier.size(10.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Th.c.card).padding(12.dp)) {
                    if (files > 0) Text("📎 Вложение: $files", style = body(13, FontWeight.SemiBold))
                    if (source.isNotBlank()) Text(source, style = body(14, color = Muted), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }

            // ответы этой сессии
            val mine = chat.msgs.filter { it.id > startId }
            if (mine.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState(), reverseScrolling = true),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    mine.takeLast(6).forEach { m ->
                        if (m.me) Text(m.text.substringBefore("\n\n«").take(200), style = body(13, color = Muted),
                            modifier = Modifier.align(Alignment.End).padding(start = 40.dp))
                        else if (!m.note) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Th.c.inBubble)
                            .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(12.dp)) {
                            Text(m.text.replace("**", ""), style = body(15), lineHeight = 21.sp)
                            val extra = listOfNotNull(
                                if (m.items.isNotBlank()) "карточки товаров" else null,
                                if (m.photos.isNotBlank()) "фото" else null,
                                if (m.files.isNotBlank()) "файл" else null)
                            if (extra.isNotEmpty()) Text("Открыть в чате: ${extra.joinToString(", ")} →", style = body(13, FontWeight.SemiBold, Teal),
                                modifier = Modifier.padding(top = 6.dp).clickable { expand() })
                        }
                    }
                }
            }

            if (!asked && chips.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { c ->
                        Text(c, style = body(13, FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(16.dp))
                            .background(AccentSoft).clickable { send(c) }.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            }

            Spacer(Modifier.size(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Th.c.card).border(1.dp, Line, RoundedCornerShape(24.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (text.isEmpty()) Text(if (asked) "Ещё вопрос…" else "Спроси ${f.name}…", style = body(16, color = Muted))
                    BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), textStyle = body(16),
                        cursorBrush = SolidColor(Teal), maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(text) }))
                }
                Box(Modifier.size(42.dp).clip(CircleShape).background(if (text.isNotBlank() || files > 0) Teal else Line)
                    .clickable { send(text) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = OnAccent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Плитка «Спросить Пилота» в быстрых настройках. */
class QuickTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { label = "Спросить"; state = Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        val i = Intent(this, QuickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 9, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    companion object {
        /** Android 13+: система сама предложит добавить плитку в шторку. */
        fun requestAdd(ctx: Context, done: (String) -> Unit) {
            if (Build.VERSION.SDK_INT < 33) { done("Опусти шторку → карандаш → перетащи «Спросить»"); return }
            val sbm = ctx.getSystemService(StatusBarManager::class.java) ?: return done("не получилось")
            runCatching {
                sbm.requestAddTileService(ComponentName(ctx, QuickTile::class.java), "Спросить",
                    AIcon.createWithResource(ctx, R.drawable.ic_launcher_mono), ctx.mainExecutor) { r ->
                    done(when (r) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Плитка в шторке"
                        else -> "Не добавлено"
                    })
                }
            }.onFailure { done("Опусти шторку → карандаш → перетащи «Спросить»") }
        }
    }
}

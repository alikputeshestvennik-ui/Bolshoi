package ru.alim.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val codexBrainIcon by lazy { lineIco("codex-brain", "M12 5a3 3 0 0 0-5.5-1.6A3.4 3.4 0 0 0 4 9.1a3.8 3.8 0 0 0 .7 6.5A3.5 3.5 0 0 0 12 18V5M12 5a3 3 0 0 1 5.5-1.6A3.4 3.4 0 0 1 20 9.1a3.8 3.8 0 0 1-.7 6.5A3.5 3.5 0 0 1 12 18M8 7c0 1.5.8 2.3 2 2.5M16 7c0 1.5-.8 2.3-2 2.5M7 14c1.4 0 2.2.7 2.5 2M17 14c-1.4 0-2.2.7-2.5 2") }
private val codexProviderIcon by lazy { lineIco("codex-provider", "M7 18a4 4 0 0 1-.6-7.96A6 6 0 0 1 18 9.5a3.5 3.5 0 1 1 .5 6.96H7z") }
private val codexExitIcon by lazy { lineIco("codex-exit", "M4 5h8v14H4zM12 12h8M17 8l4 4-4 4") }
private val codexSettingsIcon by lazy { lineIco("codex-settings", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3a1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5a1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8a1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5a1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z") }
private val codexTrashIcon by lazy { lineIco("codex-trash", "M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14M10 11v6M14 11v6") }
private val codexPlusIcon by lazy { lineIco("codex-plus", "M12 5v14M5 12h14", 2.2f) }
private val codexStopIcon by lazy { lineIco("codex-stop", "M7 7h10v10H7z", 2f) }

data class CodexActivity(
    val running: Boolean = false,
    val reasoning: String = "",
    val answer: String = "",
)

/** Одно живое состояние Codex-задачи для шапки, баббла и кнопки остановки. */
@Composable
fun rememberCodexActivity(fid: String, enabled: Boolean = true): CodexActivity {
    var state by remember(fid) { mutableStateOf(CodexActivity()) }
    LaunchedEffect(fid, enabled) {
        if (!enabled) {
            state = CodexActivity()
            return@LaunchedEffect
        }
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { CodexGateway.status(Chat.app, fid) }.getOrNull()
            }
            if (snapshot != null) {
                state = CodexActivity(
                    running = snapshot.optString("status") in setOf("queued", "running"),
                    reasoning = snapshot.optString("reasoning").trim(),
                    answer = snapshot.optString("answer").trim(),
                )
            }
            delay(900)
        }
    }
    return state
}

@Composable
private fun CodexBarButton(icon: ImageVector, label: String, size: Int = 40, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(circ).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(rememberVectorPainter(icon), label, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun CodexTopBar(fid: String, go: (Scr) -> Unit, onDelete: () -> Unit, running: Boolean = false) {
    val chats = Friends.list.filter { Friends.isCodex(it.id) }
    val strip = rememberLazyListState()
    LaunchedEffect(fid, chats.size) {
        val index = chats.indexOfFirst { it.id == fid }
        if (index >= 0) runCatching { strip.animateScrollToItem(index) }
    }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)
            .height(48.dp).clip(shp(24.dp)).background(Card.copy(alpha = 0.95f))
            .border(1.dp, Line, shp(24.dp)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CodexBarButton(codexExitIcon, "Выйти из Codex") {
            Friends.open(Friends.MAIN)
            go(Scr.Home)
        }
        LazyRow(
            Modifier.weight(1f).height(36.dp),
            state = strip,
            contentPadding = PaddingValues(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(chats, key = { it.id }) { chat ->
                val selected = chat.id == fid
                Box(
                    Modifier.height(32.dp).clip(shp(16.dp))
                        .background(if (selected) AccentSoft else Color.Transparent)
                        .border(1.dp, if (selected) Teal.copy(alpha = 0.55f) else Color.Transparent, shp(16.dp))
                        .clickable(onClickLabel = "Открыть ${chat.name}") { Friends.open(chat.id) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(chat.name, style = body(12, FontWeight.SemiBold, if (selected) Teal else Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item(key = "new-codex") {
                CodexBarButton(codexPlusIcon, "Новый диалог Codex", 32) { Friends.newCodex() }
            }
        }
        if (running) CodexBarButton(codexStopIcon, "Остановить Codex", 36) {
            Chat.of(fid).stopCodex()
        }
        CodexBarButton(codexSettingsIcon, "Настройки Codex") { go(Scr.CodexSettings) }
        CodexBarButton(codexTrashIcon, "Удалить диалог") { onDelete() }
    }
}

@Composable
fun CodexKeyboardBar(fid: String, go: (Scr) -> Unit) {
    var model by remember(fid) { mutableStateOf(Prefs.codexModel(fid)) }
    val providers = listOf("codex" to "GPT", "deepseek" to "DeepSeek", "anymodel" to "AnyModel", "compatible" to "Другой API")
    val providerOrder = providers.map { it.first }
    var provider by remember(fid) {
        mutableStateOf(Prefs.codexProvider(fid).takeIf { it in providerOrder }
            ?: modelProvider(model.ifBlank { Llm.codexModel(Prefs.smartModel) }).let { id ->
                if (id in providerOrder) id else "anymodel"
            })
    }
    val allModels = requestModels(provider, ChatGptAccount.models).distinct()
        .filter { Llm.codexModel(it) == it }
        .sortedBy { modelLabel(it).lowercase() }
    val autoModel = allModels.firstOrNull().orEmpty()
    var providerOpen by remember(fid) { mutableStateOf(false) }
    var modelOpen by remember(fid) { mutableStateOf(false) }

    fun pickProvider(id: String) {
        provider = id
        providerOpen = false
        modelOpen = false
        val next = requestModels(id, ChatGptAccount.models).firstOrNull().orEmpty()
        model = next
        Prefs.setCodexProvider(fid, id)
        Prefs.setCodexModel(fid, next)
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(0.9f)) {
                PickerButton(codexProviderIcon, providers.firstOrNull { it.first == provider }?.second ?: "Провайдер", "Выбрать провайдера", Modifier.fillMaxWidth()) { providerOpen = true }
                DropdownMenu(providerOpen, { providerOpen = false }, modifier = Modifier.heightIn(max = 320.dp), shape = shp(16.dp), containerColor = Card,
                    properties = PopupProperties(focusable = false)) {
                    providers.forEach { (id, title) ->
                        val selected = id == provider
                        DropdownMenuItem(
                            text = { Text(title, style = body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal, if (selected) Teal else Ink)) },
                            trailingIcon = if (selected) ({ Icon(Icons.Filled.Check, "Выбран", tint = Teal, modifier = Modifier.size(18.dp)) }) else null,
                            onClick = { pickProvider(id) },
                        )
                    }
                    DropdownMenuItem(text = { Text("Добавить провайдера в настройках Codex…", style = body(13, color = Teal)) }, onClick = { providerOpen = false; go(Scr.CodexSettings) })
                }
            }
            Box(Modifier.weight(1.1f)) {
                PickerButton(codexBrainIcon, if (model.isBlank()) {
                    if (autoModel.isBlank()) "Авто · нет модели" else "Авто · ${modelLabel(autoModel)}"
                } else modelLabel(model), "Выбрать модель", Modifier.fillMaxWidth()) { modelOpen = true }
                DropdownMenu(modelOpen, { modelOpen = false }, modifier = Modifier.heightIn(max = 280.dp), shape = shp(16.dp), containerColor = Card,
                    properties = PopupProperties(focusable = false)) {
                    DropdownMenuItem(
                        text = { Text(if (autoModel.isBlank()) "Авто · нет модели" else "Авто · ${modelLabel(autoModel)}", style = body(13, if (model.isBlank()) FontWeight.SemiBold else FontWeight.Normal, if (model.isBlank()) Teal else Ink)) },
                        trailingIcon = if (model.isBlank()) ({ Icon(Icons.Filled.Check, "Выбрано", tint = Teal, modifier = Modifier.size(18.dp)) }) else null,
                        onClick = { model = ""; Prefs.setCodexProvider(fid, provider); Prefs.setCodexModel(fid, ""); modelOpen = false },
                    )
                    allModels.forEach { id ->
                        val selected = id == model
                        DropdownMenuItem(
                            text = { Text(modelLabel(id), style = body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal, if (selected) Teal else Ink)) },
                            trailingIcon = if (selected) ({ Icon(Icons.Filled.Check, "Выбрана", tint = Teal, modifier = Modifier.size(18.dp)) }) else null,
                            onClick = {
                                model = id
                                provider = modelProvider(id).let { if (it in providerOrder) it else "anymodel" }
                                Prefs.setCodexProvider(fid, provider)
                                Prefs.setCodexModel(fid, id)
                                modelOpen = false
                            },
                        )
                    }
                    if (allModels.isEmpty()) DropdownMenuItem(text = { Text("Нет доступных моделей", style = body(13, color = Muted)) }, onClick = { modelOpen = false })
                }
            }
        }
    }
}

/** Прозрачный баббл над полем ввода: только пока задача реально идёт. */
@Composable
fun CodexRunBubble(activity: CodexActivity) {
    AnimatedVisibility(
        visible = activity.running,
        enter = fadeIn(tween(180)) + expandVertically(tween(180)),
        exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shp(12.dp))
                .background(Th.c.inBubble.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThinkDots()
                Text("Codex думает", style = body(11, FontWeight.SemiBold, Teal))
            }
            Text(
                activity.reasoning.ifBlank { "Codex обдумывает задачу…" },
                style = body(13, color = if (activity.reasoning.isBlank()) Muted else Ink), lineHeight = 18.sp,
                maxLines = 5, overflow = TextOverflow.Ellipsis,
            )
            if (activity.answer.isNotBlank()) {
                Text("Последний ответ", style = body(11, FontWeight.SemiBold, Muted))
                Text(activity.answer, style = body(13, color = Ink), lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


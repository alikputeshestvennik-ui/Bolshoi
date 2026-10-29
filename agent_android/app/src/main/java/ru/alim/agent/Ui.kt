package ru.alim.agent

import kotlinx.coroutines.launch

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Scr { Home, Journal, Task, Tg, Music, Yt, History, Rules, Settings, YooMoney, Memory, Digest, Profile, Look, Initiative }

fun neededPerms(): Array<String> {
    val l = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CONTACTS)
    if (Build.VERSION.SDK_INT >= 33) { l += Manifest.permission.READ_MEDIA_IMAGES; l += Manifest.permission.POST_NOTIFICATIONS }
    else l += Manifest.permission.READ_EXTERNAL_STORAGE
    return l.toTypedArray()
}

@Composable
fun App(voice: Voice) {
    var scr by remember { mutableStateOf(Scr.Home) }
    var taskBack by remember { mutableStateOf(Scr.Home) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (!Prefs.askedPerms) { Prefs.askedPerms = true; perms.launch(neededPerms()) }
    }
    // при каждом возвращении в приложение — только новые ЛИЧНЫЕ сообщения Telegram, которые ещё не показывали
    val tgCtx = androidx.compose.ui.platform.LocalContext.current
    val tgScope = androidx.compose.runtime.rememberCoroutineScope()
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    // после установки новой версии процесс стартует заново — строки «скачиваю/скачал сборку» больше не нужны
    val verCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Updater.cleaned) return@LaunchedEffect
        Updater.cleaned = true
        val v = runCatching { verCtx.packageManager.getPackageInfo(verCtx.packageName, 0).let { pi -> if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong() } }.getOrDefault(0L)
        if (v != 0L && Prefs.lastVer != v) { Prefs.lastVer = v; Chat.main.dropUpd() }
    }
    androidx.compose.runtime.DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                SkyDrift.reroll(); Th.tick(); tgScope.launch { SkyWeather.refresh() }
                tgScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { BuildWatch.check(tgCtx) }.getOrNull()?.let { m -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Chat.main.noteUpd(m) } }
                }
            }
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && Tg.configured && Prefs.tgDigest) tgScope.launch {
                Tg.start(tgCtx)
                var w = 0; while (Tg.state != Tg.St.READY && w++ < 20) kotlinx.coroutines.delay(500)
                runCatching { Tg.newPrivate() }.getOrNull()?.let { Chat.main.note("💬 Личные в Telegram:\n$it") }
            }
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); Th.tick() } }
    // кабина задачи — всегда в цветах «Кабины» (небо, полёт), панель и журнал — в выбранной теме
    androidx.compose.runtime.SideEffect { Th.forceCockpit = scr == Scr.Task }
    val phase = Agent.phase
    val open = Nav.open
    val uiCtx = androidx.compose.ui.platform.LocalContext.current
    // Задача ощущается как рейс: отрыв (уходим из чата в кабину) → запрос → посадка, с тихим вибро.
    var prevPhase by remember { mutableStateOf(Agent.Phase.IDLE) }
    LaunchedEffect(phase) {
        when {
            phase == Agent.Phase.WORKING && prevPhase != Agent.Phase.WORKING -> {
                Haptics.takeoff(uiCtx)
                if (scr == Scr.Home || scr == Scr.Journal) { taskBack = scr; scr = Scr.Task }
            }
            phase == Agent.Phase.CONFIRM && prevPhase != Agent.Phase.CONFIRM -> Haptics.ask(uiCtx)
        }
        prevPhase = phase
    }
    LaunchedEffect(open) {
        if (open == "digest") { scr = Scr.Digest; Nav.open = "" }
        if (open == "update") { scr = Scr.Home; Nav.open = ""; Updater.run(Agent.app) }
        if (open.startsWith("chat:")) { scr = Scr.Home; Nav.open = "" }
        if (open == "yt") {
            Nav.open = ""
            Journal.all(uiCtx).lastOrNull { it.title.startsWith("Подборка YouTube") }?.let { Agent.showPast(it); taskBack = Scr.Home; scr = Scr.Task }
        }
    }

    // из чата «назад» — выход; остальные экраны — в чат
    BackHandler(enabled = scr != Scr.Home) {
        when (scr) {
            Scr.Task -> { Agent.close(); scr = taskBack; taskBack = Scr.Home }
            else -> scr = Scr.Home
        }
    }
    val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.SideEffect {
        act?.window?.let { w ->
            // во весь экран: шторка и нижняя полоса прозрачные, цвет значков — под тему
            w.statusBarColor = android.graphics.Color.TRANSPARENT; w.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) { w.isNavigationBarContrastEnforced = false; w.isStatusBarContrastEnforced = false }
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = !Th.c.dark && scr != Scr.Task; isAppearanceLightNavigationBars = !Th.c.dark && scr != Scr.Task
            }
        }
    }
    MaterialTheme(colorScheme = if (Th.c.dark) darkColorScheme(primary = Teal, onPrimary = OnAccent, background = Bg, surface = Card, onSurface = Ink) else androidx.compose.material3.lightColorScheme(primary = Teal, onPrimary = OnAccent, background = Bg, surface = Card, onSurface = Ink)) {
        Box(Modifier.fillMaxSize().background(Bg).then(if (scr == Scr.Home || scr == Scr.Journal || scr == Scr.Task) Modifier else Modifier.systemBarsPadding().imePadding())) {
            val go: (Scr) -> Unit = { scr = it }
            val home: () -> Unit = { if (scr == Scr.Task) { Agent.close(); scr = taskBack; taskBack = Scr.Home } else scr = Scr.Home }
            // одна сцена полёта под чатом и задачей: из чата в задачу — тот же самолёт на той же полосе
            val dash = Prefs.dashboard
            if ((scr == Scr.Task || ((scr == Scr.Journal || (scr == Scr.Home && !dash)) && Th.cockpit)) && Prefs.wallpaper != "photo") {
                val sctx = androidx.compose.ui.platform.LocalContext.current
                val onTask = scr == Scr.Task
                FlightScene(flying = onTask && FlightBus.flying, glow = if (onTask) FlightBus.glow else Sky.glowCalm,
                    modifier = Modifier.fillMaxSize(), flash = if (onTask) FlightBus.flash else 0f,
                    onLift = { Haptics.tick(sctx) }, onTouch = { FlightBus.touchN++ })
            }
            when (scr) {
                Scr.Home -> if (dash) DashScreen(go) else ChatScreen(Friends.MAIN, go)
                Scr.Journal -> ChatScreen(Friends.MAIN, go)
                Scr.Profile -> ProfileScreen(Friends.MAIN, home)
                Scr.Look -> LookScreen(home)
                Scr.Tg -> TgScreen(home)
                Scr.Music -> MusicScreen(home)
                Scr.Yt -> YtScreen(home)
                Scr.Initiative -> InitiativeScreen(home)
                Scr.Task -> TaskScreen(voice, home)
                Scr.History -> HistoryScreen(home) { r -> Agent.showPast(r); taskBack = Scr.History; scr = Scr.Task }
                Scr.Rules -> RulesScreen(home)
                Scr.Settings -> SettingsScreen(home, { perms.launch(neededPerms()) }, { scr = Scr.YooMoney }, { scr = Scr.Tg }, { scr = Scr.Music }, { scr = Scr.Yt })
                Scr.YooMoney -> YooScreen { scr = Scr.Settings }
                Scr.Memory -> MemoryScreen(home)
                Scr.Digest -> DigestScreen(home)
            }
        }
    }
}

// ——— общие детали, как в макетах ———

fun body(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Ink) =
    TextStyle(fontFamily = Fonts.body, fontSize = size.sp, fontWeight = weight, color = color)

@Composable
fun Label(text: String, color: Color = Muted, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = body(13, FontWeight.Bold, color).copy(letterSpacing = 1.sp))

@Composable
fun Screen(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.AutoMirrored.Filled.ArrowBack, "Назад", back)
            Text(title, style = TextStyle(fontFamily = Fonts.display, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

/** Раздел настроек: заголовок + короткий статус, по нажатию раскрывается. */
@Composable
fun Section(title: String, status: String, color: Color = Teal, statusColor: Color = Muted, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Card)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(color); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = body(15, FontWeight.SemiBold))
                if (status.isNotBlank()) Text(status, style = body(12, color = statusColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(if (open) "−" else "+", style = body(20, FontWeight.Bold, Muted))
        }
        if (open) Column(Modifier.fillMaxWidth().background(Bg.copy(alpha = 0.35f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun IconTile(icon: ImageVector, label: String, onClick: () -> Unit, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(14.dp))
        .clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, border: Color? = null, bg: Color = Card, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(bg)
        .let { if (border != null) it.border(BorderStroke(1.dp, border), shape) else it }
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, bg: Color = Teal, fg: Color = Bg, border: Color? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().height(52.dp).clip(shape).background(bg)
        .let { if (border != null) it.border(1.dp, border, shape) else it }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = body(16, FontWeight.Bold, fg))
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) =
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Bg,
        uncheckedTrackColor = Line, uncheckedThumbColor = Muted, uncheckedBorderColor = Line))

@Composable
fun Chip(text: String, onClick: () -> Unit) {
    Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = body(13, FontWeight.Medium, ChipInk))
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int) = Spacer(Modifier.width(w.dp))

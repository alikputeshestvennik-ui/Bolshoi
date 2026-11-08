package ru.alim.agent

import kotlinx.coroutines.launch

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import dev.chrisbanes.haze.haze
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.layout.navigationBarsPadding
import dev.chrisbanes.haze.hazeChild
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
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

enum class Scr { Home, Journal, Active, Task, Tg, Music, Yt, History, Rules, Settings, YooMoney, Memory, Digest, Profile, Look, Initiative }

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
    var subFrom by remember { mutableStateOf(Scr.Home) }
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
    androidx.compose.runtime.SideEffect { Th.forceCockpit = scr == Scr.Task && Prefs.theme != "light_minimal" }
    val phase = Agent.phase
    val open = Nav.open
    val uiCtx = androidx.compose.ui.platform.LocalContext.current
    // Задача ощущается как рейс: отрыв (уходим из чата в кабину) → запрос → посадка, с тихим вибро.
    var prevPhase by remember { mutableStateOf(Agent.Phase.IDLE) }
    LaunchedEffect(phase) {
        when {
            phase == Agent.Phase.WORKING && prevPhase != Agent.Phase.WORKING -> {
                Haptics.takeoff(uiCtx)
                // задача идёт прямо в чате (окно мыслей), без перехода в отдельный экран
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
            Scr.Memory, Scr.Initiative, Scr.Rules, Scr.Look -> { scr = subFrom; subFrom = Scr.Home }
            else -> scr = Scr.Home
        }
    }
    val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.SideEffect {
        act?.window?.let { w ->
            // во весь экран: шторка и нижняя полоса прозрачные, цвет значков — под тему
            w.statusBarColor = android.graphics.Color.TRANSPARENT; w.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) { w.isNavigationBarContrastEnforced = false; w.isStatusBarContrastEnforced = false }
            // в горизонтальном режиме рисуем и в зоне выреза камеры — без чёрной полосы сбоку
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val mode = if (android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (w.attributes.layoutInDisplayCutoutMode != mode) w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = mode }
            }
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = !Th.c.dark; isAppearanceLightNavigationBars = !Th.c.dark
            }
        }
    }
    MaterialTheme(colorScheme = if (Th.c.dark) darkColorScheme(primary = Teal, onPrimary = OnAccent, background = Bg, surface = Card, onSurface = Ink) else androidx.compose.material3.lightColorScheme(primary = Teal, onPrimary = OnAccent, background = Bg, surface = Card, onSurface = Ink)) {
        Box(Modifier.fillMaxSize().background(Bg).then(if (scr == Scr.Home || scr == Scr.Journal || scr == Scr.Task) Modifier else Modifier.navigationBarsPadding().imePadding())) {
            val go: (Scr) -> Unit = { scr = it }
            val home: () -> Unit = { if (scr == Scr.Task) { Agent.close(); scr = taskBack; taskBack = Scr.Home } else scr = Scr.Home }
            // одна сцена полёта под чатом и задачей: из чата в задачу — тот же самолёт на той же полосе
            // кабина и 3D-полёт убраны: главный экран — Чат, идущие дела — на странице «Активные»
            // кнопки разделов и ввод — на всех экранах (в чате и задаче они свои)
            val withBar = scr != Scr.Home && scr != Scr.Journal
            var barH by remember { mutableStateOf(0) }
            val barDp = with(androidx.compose.ui.platform.LocalDensity.current) { barH.toDp() }
            // экран идёт под кнопками, как в чате: вокруг кнопок прозрачно и размыто, в конце списков — запас на высоту кнопок
            val barHaze = remember { dev.chrisbanes.haze.HazeState() }
            Box(Modifier.fillMaxSize().then(if (withBar) Modifier.then(if (scr == Scr.Task) Modifier else Modifier.background(Bg)).haze(barHaze, dev.chrisbanes.haze.HazeStyle(tint = Th.c.glass, blurRadius = 24.dp, noiseFactor = 0.05f)) else Modifier)) {
              androidx.compose.runtime.CompositionLocalProvider(LocalBarPad provides (if (withBar) barDp else 0.dp), LocalBarHaze provides (if (withBar) barHaze else null)) {
            when (scr) {
                Scr.Home, Scr.Journal -> ChatScreen(Friends.MAIN, go)
                Scr.Active -> ActiveScreen(go)
                Scr.Profile -> ProfileScreen(Friends.MAIN, home)
                Scr.Look -> LookScreen { scr = subFrom; subFrom = Scr.Home }
                Scr.Tg -> TgScreen(home)
                Scr.Music -> MusicScreen(home)
                Scr.Yt -> YtScreen(home)
                Scr.Initiative -> InitiativeScreen { scr = subFrom; subFrom = Scr.Home }
                Scr.Task -> if (Prefs.theme == "light_minimal") LightTaskScreen(home) else TaskScreen(voice, home)
                Scr.History -> HistoryScreen(home) { r -> Agent.showPast(r); taskBack = Scr.History; scr = Scr.Task }
                Scr.Rules -> RulesScreen { scr = subFrom; subFrom = Scr.Home }
                Scr.Settings -> SettingsScreen(home, { perms.launch(neededPerms()) }, { scr = Scr.YooMoney }, { scr = Scr.Tg }, { scr = Scr.Music }, { scr = Scr.Yt }) { s -> subFrom = Scr.Settings; scr = s }
                Scr.YooMoney -> YooScreen { scr = Scr.Settings }
                Scr.Memory -> MemoryScreen { scr = subFrom; subFrom = Scr.Home }
                Scr.Digest -> DigestScreen(home)
            }
            }
              }
            if (withBar) GlobalBar(scr, go, barHaze, Modifier.align(Alignment.BottomCenter).then(if (scr == Scr.Task) Modifier.navigationBarsPadding().imePadding() else Modifier).onSizeChanged { barH = it.height })
            if (Panel.open) Panel.open = false
        }
    }
}

// ——— общие детали, как в макетах ———

fun body(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Ink) =
    TextStyle(fontFamily = Fonts.body, fontSize = size.sp, fontWeight = weight, color = color)

@Composable
fun Label(text: String, color: Color = Muted, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = body(13, FontWeight.Bold, color).copy(letterSpacing = 1.sp))

/** Отступ сверху под общую шапку (строка состояния + бабл): списки начинают с него и уезжают под шапку. */
val LocalTopPad = androidx.compose.runtime.compositionLocalOf { 0.dp }
/** Размытие под стеклянными плашками (общий фон экранов с нижней панелью). */
val LocalBarHaze = androidx.compose.runtime.compositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * Шапка страницы — как в чате: стеклянный бабл с аватаром Пилота и названием. На вложенных страницах
 * стрелка «назад» выезжает из аватара и сдвигает бабл вправо.
 */
@Composable
fun ScreenHeader(title: String, back: (() -> Unit)?, trailing: String = "", haze: dev.chrisbanes.haze.HazeState? = null) {
    val glassBg = Th.c.glass.copy(alpha = if (haze != null) (if (Th.c.dark) 0.35f else 0.30f) else (if (Th.c.dark) 0.55f else 0.62f))
    fun Modifier.gl(shape: androidx.compose.ui.graphics.Shape): Modifier = this.clip(shape)
        .then(if (haze != null) Modifier.hazeChild(haze, shape) else Modifier).background(glassBg).border(1.dp, Th.c.glassLine, shape)
    val vis = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    vis.targetState = back != null
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.animation.AnimatedVisibility(vis,
            enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(320), expandFrom = Alignment.Start) +
                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(320)) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)),
            exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(280), shrinkTowards = Alignment.Start) +
                androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280)) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200))) {
            Row {
                Box(Modifier.size(56.dp).gl(circ).clickable(onClickLabel = "Назад") { back?.invoke() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Ink, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        Row(Modifier.weight(1f).height(56.dp).gl(shp(16.dp)).padding(start = 10.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(shp(11.dp)).background(Teal), contentAlignment = Alignment.Center) {
                Text("Т", style = body(17, FontWeight.SemiBold, OnAccent))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = body(17, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (trailing.isNotBlank()) Text(trailing, style = body(12, color = Muted), maxLines = 1)
        }
    }
}

/** Страница с общей шапкой: содержимое идёт под строку состояния и шапку, сверху мягкое затемнение, как в чате. */
@Composable
fun Screen(title: String, back: () -> Unit, showBack: Boolean = true, trailing: String = "", content: @Composable ColumnScope.() -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // своё размытие у страницы: шапка лежит ВНЕ размываемого слоя (иначе размывается она сама), под ней — содержимое
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Th.c.bg).haze(haze, dev.chrisbanes.haze.HazeStyle(tint = Th.c.glass, blurRadius = 24.dp, noiseFactor = 0.05f))) {
            androidx.compose.runtime.CompositionLocalProvider(LocalTopPad provides top + 76.dp) {
                Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
        // затемнение сверху вместе со строкой состояния: лента уходит под шапку плавно, как в чате
        Box(Modifier.fillMaxWidth().height(top + 96.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Th.c.bg.copy(alpha = 0.95f), Th.c.bg.copy(alpha = 0.75f), Th.c.bg.copy(alpha = 0f)))))
        ScreenHeader(title, if (showBack) back else null, trailing, haze)
    }
}

/** Навигация внутри настроек: раздел открывается своей страницей, а не раскрывается на месте. */
val LocalBarPad = androidx.compose.runtime.compositionLocalOf { 0.dp }

class SetNav { var page by mutableStateOf<String?>(null) }
val LocalSetNav = androidx.compose.runtime.staticCompositionLocalOf<SetNav?> { null }

/** Группа строк настроек одной карточкой (как четыре верхние), с подписью сверху. На странице раздела — прозрачна. */
@Composable
fun SetGroup(label: String = "", content: @Composable ColumnScope.() -> Unit) {
    val nav = LocalSetNav.current
    if (nav?.page != null) { Column(Modifier.fillMaxWidth(), content = content); return }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank()) Text(label.uppercase(), style = body(11, FontWeight.Bold, Muted).copy(letterSpacing = 1.2.sp), modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        Column(Modifier.fillMaxWidth().clip(shp(16.dp)).background(Card), content = content)
    }
}

/** Строка настроек: название, подпись, стрелка. */
@Composable
fun SetRow(title: String, sub: String, subColor: Color = Muted, first: Boolean = false, onClick: () -> Unit) {
    if (!first) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Line))
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = body(15, FontWeight.SemiBold))
            if (sub.isNotBlank()) Text(sub, style = body(12, color = subColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", style = body(20, color = Muted))
    }
}

/** Раздел настроек. В настройках (есть SetNav) — строка, по нажатию своя страница; в других экранах — раскрывается на месте. */
@Composable
fun Section(title: String, status: String, color: Color = Teal, statusColor: Color = Muted, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val nav = LocalSetNav.current
    if (nav != null) {
        when (nav.page) {
            title -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            null -> SetRow(title, status, statusColor, first) { nav.page = title }
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    val shape = shp(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Card)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = body(15, FontWeight.SemiBold))
                if (status.isNotBlank()) Text(status, style = body(12, color = statusColor), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(if (open) "⌄" else "›", style = body(20, color = Muted))
        }
        if (open) Column(Modifier.fillMaxWidth().background(Bg.copy(alpha = 0.35f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun IconTile(icon: ImageVector, label: String, onClick: () -> Unit, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(shp(14.dp)).background(Card).border(1.dp, Line, shp(14.dp))
        .clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, border: Color? = null, bg: Color = Card, content: @Composable ColumnScope.() -> Unit) {
    val shape = shp(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(bg)
        .let { if (border != null) it.border(BorderStroke(1.dp, border), shape) else it }
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, bg: Color = Teal, fg: Color = Bg, border: Color? = null, modifier: Modifier = Modifier) {
    val shape = shp(14.dp)
    Box(modifier.fillMaxWidth().height(52.dp).clip(shape).background(bg)
        .let { if (border != null) it.border(1.dp, border, shape) else it }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = body(15, FontWeight.SemiBold, fg))
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) =
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Bg,
        uncheckedTrackColor = Line, uncheckedThumbColor = Muted, uncheckedBorderColor = Line))

@Composable
fun Chip(text: String, onClick: () -> Unit) {
    Box(Modifier.height(36.dp).clip(shp(18.dp)).background(Card).border(1.dp, Line, shp(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = body(13, FontWeight.Medium, ChipInk))
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(shp(5.dp)).background(color))

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int) = Spacer(Modifier.width(w.dp))

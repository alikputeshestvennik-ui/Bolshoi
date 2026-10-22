package ru.alim.agent

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private fun icon(name: String, path: String, w: Float = 1.9f) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes(path), stroke = SolidColor(Color.Black), strokeLineWidth = w,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }.build()

val icPhone by lazy { icon("phone", "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2") }
private val icHang by lazy { icon("hang", "M3 15.5c5-5 13-5 18 0l-2.5 2.5-3.5-1.5v-3a10 10 0 0 0-6 0v3L5.5 18z", 2f) }
private val icMic by lazy { icon("mic", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3") }
private val icMicOff by lazy { icon("micoff", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3M4 4l16 16") }
private val icSpeaker by lazy { icon("spk", "M4 9h4l5-4v14l-5-4H4zM16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12") }

@Composable
fun CallScreen(fid: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val f = Friends.get(fid)
    val core = Chat.of(fid).core
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Call.start(ctx, fid) else back()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) Call.start(ctx, fid)
        else perm.launch(Manifest.permission.RECORD_AUDIO)
    }
    val act = ctx as? android.app.Activity
    DisposableEffect(Unit) {
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Call.end(); Call.reset() }
    }
    BackHandler { Call.end(); back() }
    // звонок закончился — через пару секунд обратно в чат
    LaunchedEffect(Call.state) {
        if (Call.state == Call.St.ENDED) { delay(if (Call.error.isBlank()) 1200 else 3500); back() }
    }
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); now = System.currentTimeMillis() } }

    val dark = Color(0xFF0E2A27)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1F6B60), Color(0xFF15463F), dark)))) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(70.dp))
            // аватар «дышит», когда друг говорит
            val pulse = rememberInfiniteTransition(label = "p")
            val wave by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "w")
            val lvl by animateFloatAsState(if (Call.talking) 1f + Call.level * 0.35f else 1f, tween(120), label = "l")
            val connecting = Call.state == Call.St.CONNECTING
            Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(170.dp).scale(if (connecting) wave else lvl + 0.08f).clip(CircleShape).background(Color.White.copy(alpha = 0.07f)))
                Box(Modifier.size(170.dp).scale(if (connecting) (wave + 1f) / 2f else (lvl + 1f) / 2f + 0.02f).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
                Avatar(150.dp, f = f)
            }
            Spacer(Modifier.height(18.dp))
            Text(f.name, style = body(30, FontWeight.Bold, Color.White), textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            val secs = if (Call.startedAt > 0) ((now - Call.startedAt) / 1000).coerceAtLeast(0) else 0
            val status = when (Call.state) {
                Call.St.CONNECTING, Call.St.IDLE -> "вызов…"
                Call.St.LIVE -> when {
                    Call.doing.isNotBlank() -> Call.doing
                    else -> "%d:%02d".format(secs / 60, secs % 60)
                }
                Call.St.ENDED -> Call.error.ifBlank { "звонок завершён" }
            }
            Text(status, style = body(16, color = Color.White.copy(alpha = 0.75f)), textAlign = TextAlign.Center, maxLines = 3)
            Spacer(Modifier.height(26.dp))
            Spacer(Modifier.weight(1f))
            core.pending?.let { p -> ConfirmCard(p, core); Spacer(Modifier.height(16.dp)) }
            Row(Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundBtn(icSpeaker, "Динамик", Call.speaker) { Call.toggleSpeaker() }
                RoundBtn(if (Call.muted) icMicOff else icMic, if (Call.muted) "Включить" else "Микрофон", Call.muted) { Call.muted = !Call.muted }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFE5484D)).clickable(onClickLabel = "Завершить") { Call.end(); back() },
                        contentAlignment = Alignment.Center) {
                        Icon(rememberVectorPainter(icHang), "Завершить", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Завершить", style = body(13, color = Color.White.copy(alpha = 0.8f)))
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(ic: ImageVector, label: String, on: Boolean, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.16f)).clickable(onClickLabel = label) { click() },
            contentAlignment = Alignment.Center) {
            Icon(rememberVectorPainter(ic), label, tint = if (on) Color(0xFF15463F) else Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = body(13, color = Color.White.copy(alpha = 0.8f)))
    }
}

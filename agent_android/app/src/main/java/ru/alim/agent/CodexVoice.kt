package ru.alim.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.math.sin

/** Dedicated Codex dictation: never consults the chat model or STT routing. */
internal class CodexVoiceSession {
    @Volatile var stop = false
    @Volatile var cancelled = false
    @Volatile var level = 0f
    @Volatile var connection: HttpURLConnection? = null
    fun cancel() { cancelled = true; stop = true; connection?.disconnect() }

    @SuppressLint("MissingPermission")
    fun record(): ByteArray {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "Микрофон недоступен" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Микрофон занят" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Не удалось включить микрофон" }
            val pcm = ByteArrayOutputStream()
            val samples = ShortArray(1024)
            val start = SystemClock.elapsedRealtime()
            var lastSpeech = start
            var heardSpeech = false
            // Keep below the provider's 120-second limit, even with one extra read.
            while (!stop && !cancelled && pcm.size() < rate * 2 * 119) {
                val n = recorder.read(samples, 0, samples.size)
                check(n > 0) { "Запись прервана" }
                var energy = 0.0
                for (i in 0 until n) {
                    val v = samples[i].toInt()
                    energy += v.toDouble() * v
                    pcm.write(v and 255); pcm.write((v shr 8) and 255)
                }
                val rms = sqrt(energy / n)
                level = (rms / 3000).toFloat().coerceIn(0f, 1f)
                val now = SystemClock.elapsedRealtime()
                if (rms > 450) { heardSpeech = true; lastSpeech = now }
                if (heardSpeech && now - lastSpeech >= 1800) break
                if (!heardSpeech && now - start >= 10000) break
            }
            if (cancelled) throw CancellationException()
            check(heardSpeech && pcm.size() >= rate) { "Речь не слышна — попробуй ещё раз" }
            return wav(pcm.toByteArray())
        } finally {
            runCatching { recorder.stop() }; recorder.release(); level = 0f
        }
    }

    fun transcribe(wav: ByteArray, key: String): String {
        if (cancelled) throw CancellationException()
        val boundary = "codex-asr-${java.util.UUID.randomUUID()}"
        val conn = URL("https://api.deepseek.com/v1/tools/asr").openConnection() as HttpURLConnection
        connection = conn
        try {
            if (cancelled) throw CancellationException()
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000; conn.readTimeout = 60000; conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                out.write(wav); out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            check(code in 200..299) {
                when (code) {
                    401, 403 -> "DeepSeek: проверь API-ключ и доступ к ASR"
                    402 -> "DeepSeek: недостаточно средств"
                    429 -> "DeepSeek занят — повтори позже"
                    else -> "DeepSeek: ошибка распознавания ($code)"
                }
            }
            val result = conn.inputStream.bufferedReader().use { JSONObject(it.readText()).optString("text").trim() }
            if (cancelled) throw CancellationException()
            check(result.isNotBlank()) { "Не удалось разобрать речь" }
            return result
        } finally { conn.disconnect(); connection = null }
    }

    private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CodexVoiceInput(onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestOnText by rememberUpdatedState(onText)
    var session by remember { mutableStateOf<CodexVoiceSession?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var listening by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        error = if (it) "Теперь удерживай микрофон" else "Разреши доступ к микрофону"
    }
    fun cancel() { session?.cancel(); job?.cancel(); listening = false }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancel() }
    }
    LaunchedEffect(listening) {
        while (listening) { level = session?.level ?: 0f; delay(50) }
        level = 0f
    }
    val amplitude by animateFloatAsState(level, label = "speech level")
    Column(horizontalAlignment = Alignment.End) {
        error?.let { Text(it, style = body(13, color = Amber), modifier = Modifier.padding(8.dp)) }
        if (busy && !listening) Text("DeepSeek · распознаю…", style = body(13, color = Muted))
        AnimatedVisibility(listening,
            enter = scaleIn(spring(dampingRatio = 0.62f, stiffness = 260f), initialScale = 0.12f, transformOrigin = TransformOrigin(1f, 1f)) + fadeIn(),
            exit = scaleOut(targetScale = 0.12f, transformOrigin = TransformOrigin(1f, 1f)) + fadeOut()) {
            Column(Modifier.padding(bottom = 8.dp).width(260.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFFB8E9FF), Color(0xFF65C6F5))), androidx.compose.foundation.shape.RoundedCornerShape(36.dp))
                .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Слушаю…", color = Color(0xFF123F59))
                Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                    for (i in 0 until 25) {
                        val height = 5.dp.toPx() + size.height * amplitude * (0.25f + 0.65f * kotlin.math.abs(sin(i * 1.7f)))
                        val x = size.width * (i + 0.5f) / 25
                        drawLine(Color.White, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                Row {
                    TextButton(onClick = { cancel() }) { Text("Отмена", color = Color(0xFF123F59)) }
                    TextButton(onClick = { session?.stop = true; listening = false }) { Text("Готово", color = Color(0xFF123F59)) }
                }
            }
        }
        Box(Modifier.size(48.dp).background(Color(0xFF87CEEB), CircleShape).combinedClickable(
            onClickLabel = "Голосовой ввод: удерживай для записи",
            onClick = { if (listening) { session?.stop = true; listening = false } else if (!busy) error = "Удерживай микрофон и говори" },
            onLongClickLabel = "Начать диктовку",
            onLongClick = {
                if (!busy) {
                    val key = runCatching { CodexGateway.deepSeekAsrKey(ctx) }.getOrDefault("")
                    if (key.isBlank()) error = "Не настроен API-ключ DeepSeek"
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
                    else {
                        val current = CodexVoiceSession(); session = current
                        error = null; busy = true; listening = true
                        job = scope.launch {
                            try {
                                val wav = withContext(Dispatchers.IO) { current.record() }
                                listening = false
                                val transcript = withContext(Dispatchers.IO) { current.transcribe(wav, key) }
                                ensureActive(); latestOnText(transcript)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Не удалось распознать речь" }
                            finally { listening = false; busy = false; session = null }
                        }
                    }
                }
            }), contentAlignment = Alignment.Center) { MicIcon(Color(0xFF123F59), 22.dp) }
    }
}

package ru.alim.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Голосовые сообщения: запись (PCM 16 кГц → .wav), распознавание (распознаватель телефона по записи
 * на Android 13+, иначе Yandex SpeechKit), голос Артура (SpeechKit → .wav) и проигрывание.
 */
object VoiceMsg {
    private const val RATE = 16000

    // ——— запись ———
    var recording by mutableStateOf(false); private set
    var level by mutableFloatStateOf(0f); private set
    var elapsed by mutableLongStateOf(0L); private set
    @Volatile private var stopFlag = false
    @Volatile private var cancelFlag = false
    private var pcm: ByteArrayOutputStream? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096)) }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        stopFlag = false; cancelFlag = false
        val out = ByteArrayOutputStream(); pcm = out
        val t0 = System.currentTimeMillis()
        recording = true; elapsed = 0
        val main = Handler(Looper.getMainLooper())
        thread = Thread {
            val buf = ShortArray(1024); val bytes = ByteArray(2048)
            runCatching { rec.startRecording() }
            while (!stopFlag && System.currentTimeMillis() - t0 < 5 * 60_000L) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toInt(); sum += v * v; bytes[2 * i] = (v and 0xff).toByte(); bytes[2 * i + 1] = (v shr 8 and 0xff).toByte() }
                out.write(bytes, 0, n * 2)
                val lv = (sqrt(sum / n) / 3000.0).toFloat().coerceIn(0f, 1f)
                main.post { level = lv; elapsed = System.currentTimeMillis() - t0 }
            }
            runCatching { rec.stop() }; rec.release()
            main.post { recording = false; level = 0f }
        }.apply { start() }
        return true
    }

    /** Остановить запись. Возвращает PCM или null (отмена / слишком коротко). */
    suspend fun stop(cancel: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        cancelFlag = cancel; stopFlag = true
        thread?.join(1500); thread = null
        val data = pcm?.toByteArray(); pcm = null
        if (cancel || data == null || data.size < RATE * 2 * 6 / 10) null else data   // меньше 0,6 с — не считаем
    }

    // ——— файлы ———
    private fun dir(ctx: Context) = File(ctx.filesDir, "voice").apply { mkdirs() }

    fun saveWav(ctx: Context, pcm: ByteArray, rate: Int): Pair<String, Int> {
        val f = File(dir(ctx), "v${System.currentTimeMillis()}.wav")
        RandomAccessFile(f, "rw").use { w ->
            fun i32(v: Int) { w.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte(), (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte())) }
            fun i16(v: Int) { w.write(byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())) }
            w.writeBytes("RIFF"); i32(36 + pcm.size); w.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            w.writeBytes("data"); i32(pcm.size); w.write(pcm)
        }
        return f.absolutePath to (pcm.size / 2 * 1000L / rate).toInt()
    }

    /** Волна для баббла: 32 столбика из файла. */
    fun wave(path: String, bars: Int = 32): List<Float> = runCatching {
        val b = File(path).readBytes()
        if (b.size <= 44) return@runCatching List(bars) { 0.2f }
        val n = (b.size - 44) / 2
        val step = maxOf(1, n / bars)
        val res = (0 until bars).map { k ->
            var s = 0.0; var c = 0
            var i = k * step
            while (i < minOf(n, (k + 1) * step)) { val lo = b[44 + 2 * i].toInt() and 0xff; val hi = b[45 + 2 * i].toInt(); val v = (hi shl 8) or lo; s += v.toDouble() * v; c++; i += 4 }
            if (c == 0) 0.0 else sqrt(s / c)
        }
        val mx = (res.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        res.map { (it / mx).toFloat().coerceIn(0.12f, 1f) }
    }.getOrDefault(List(bars) { 0.3f })

    // ——— распознавание ———
    suspend fun transcribe(ctx: Context, pcm: ByteArray): String {
        if (Prefs.sttEngine != "yandex" && Build.VERSION.SDK_INT >= 33) {
            val t = runCatching { androidFromAudio(ctx, pcm) }.getOrNull()
            if (!t.isNullOrBlank()) return t
        }
        if (YaSpeech.ready) {
            val t = runCatching { withContext(Dispatchers.IO) { yandexLong(pcm) } }.getOrNull()
            if (!t.isNullOrBlank()) return t
        }
        if (Build.VERSION.SDK_INT >= 33 && Prefs.sttEngine == "yandex") {
            val t = runCatching { androidFromAudio(ctx, pcm) }.getOrNull()
            if (!t.isNullOrBlank()) return t
        }
        return ""
    }

    /** SpeechKit v1 принимает до 30 с — длинное режем на куски по 25 с. */
    private fun yandexLong(pcm: ByteArray): String {
        val chunk = RATE * 2 * 25
        return (0 until pcm.size step chunk).map { s -> YaSpeech.recognize(pcm.copyOfRange(s, minOf(pcm.size, s + chunk))) }
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Распознаватель телефона по готовой записи (Android 13+: EXTRA_AUDIO_SOURCE). */
    private suspend fun androidFromAudio(ctx: Context, pcm: ByteArray): String? {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(ctx)) return null
        val res = CompletableDeferred<String?>()
        val pipe = ParcelFileDescriptor.createPipe()
        withContext(Dispatchers.Main) {
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) { res.complete(null); r.destroy() }
                override fun onResults(b: Bundle?) {
                    res.complete(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()); r.destroy()
                }
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            runCatching { r.startListening(i) }.onFailure { res.complete(null); r.destroy() }
        }
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { o ->
                    o.write(pcm); o.write(ByteArray(RATE)) // полсекунды тишины в конце — чтобы распознаватель понял, что фраза кончилась
                }
            }
        }.start()
        return withTimeoutOrNull(25_000) { res.await() }.also { runCatching { pipe[0].close() } }
    }

    // ——— голос Артура ———
    /** Синтез ответа в .wav (SpeechKit, PCM 48 кГц). */
    fun speakToFile(ctx: Context, text: String, male: Boolean = Prefs.botMale): Pair<String, Int>? {
        val clean = text.replace(Regex("\\*\\*|[#•]|https?://\\S+"), "").replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
        if (clean.isBlank()) return null
        val pcm = YaSpeech.synth(clean.take(800), voiceFor(male))
        if (pcm.isEmpty()) return null
        return saveWav(ctx, pcm, 48000)
    }

    /** Голос под пол Артура: выбранный в настройках, если подходит, иначе Филипп / Алёна. */
    fun voiceFor(male: Boolean = Prefs.botMale): String {
        val v = Prefs.ttsVoice
        val female = v in setOf("alena", "jane")
        return when {
            male && !female && v.isNotBlank() -> v
            !male && female -> v
            male -> "filipp"
            else -> "alena"
        }
    }

    // ——— проигрывание ———
    var playing by mutableStateOf("")
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    private var player: MediaPlayer? = null
    private val ticker = Handler(Looper.getMainLooper())

    fun toggle(path: String) {
        if (playing == path) { stopPlay(); return }
        stopPlay()
        val mp = runCatching { MediaPlayer().apply { setDataSource(path); prepare(); start() } }.getOrNull() ?: return
        player = mp; playing = path; progress = 0f
        mp.setOnCompletionListener { stopPlay() }
        val tick = object : Runnable {
            override fun run() {
                val p = player ?: return
                runCatching { progress = p.currentPosition.toFloat() / maxOf(1, p.duration) }
                ticker.postDelayed(this, 80)
            }
        }
        ticker.post(tick)
    }

    fun stopPlay() {
        ticker.removeCallbacksAndMessages(null)
        runCatching { player?.stop(); player?.release() }
        player = null; playing = ""; progress = 0f
    }

    var voiceSentCount by mutableIntStateOf(0)
}

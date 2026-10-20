package ru.alim.agent

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Yandex SpeechKit: распознавание (v1, короткие фразы до 30 с) и синтез речи (v1, PCM 48 кГц).
 * Ключ — общий ключ Yandex Cloud; нужны роли ai.speechkit-stt.user и ai.speechkit-tts.user.
 */
object YaSpeech {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()

    val ready: Boolean get() = YaCloud.llmKey.isNotBlank() && YaCloud.folder.isNotBlank()
    private val keys: List<String> get() = listOf(YaCloud.llmKey, YaCloud.searchKey).filter { it.isNotBlank() }.distinct()

    val VOICES = listOf("filipp" to "Филипп", "ermil" to "Ермил", "zahar" to "Захар", "madirus" to "Мадирус", "alena" to "Алёна", "jane" to "Джейн")

    /** Запрос с перебором ключей: если первый без нужной роли — пробуем второй. */
    private fun call(build: (String) -> Request): okhttp3.Response {
        var last: okhttp3.Response? = null
        for (k in keys) {
            last?.close()
            val r = http.newCall(build(k)).execute()
            if (r.code != 401 && r.code != 403) return r
            last = r
        }
        return last ?: error("нет ключа Yandex Cloud")
    }

    // ——— распознавание ———

    private const val SR = 16000

    /** Пишет с микрофона, пока человек говорит: стоп после 1,2 с тишины, 25 с максимум. Пустой массив — речи не было. */
    @SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit, cancelled: () -> Boolean): ByteArray = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error("микрофон занят") }
        val out = ByteArrayOutputStream()
        val buf = ShortArray(SR / 10) // 100 мс
        var speech = false; var silent = 0; var total = 0
        var floor = 0.0; var floorN = 0
        rec.startRecording()
        try {
            while (!cancelled() && total < 25_000) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val s = buf[i].toInt(); sum += s * s; out.write(s and 0xff); out.write((s shr 8) and 0xff) }
                val rms = sqrt(sum / n)
                if (floorN < 3) { floor += rms; floorN++ }
                val thr = maxOf(700.0, floor / maxOf(1, floorN) * 3)
                onLevel((rms / 6000.0).toFloat().coerceIn(0f, 1f))
                total += 100
                if (rms > thr) { speech = true; silent = 0 } else if (speech) silent += 100
                if (speech && silent >= 1200) break
                if (!speech && total >= 7000) break
            }
        } finally { runCatching { rec.stop() }; rec.release(); onLevel(0f) }
        if (speech) out.toByteArray() else ByteArray(0)
    }

    fun recognize(pcm: ByteArray): String {
        val url = "https://stt.api.cloud.yandex.net/speech/v1/stt:recognize?folderId=${YaCloud.folder}&lang=ru-RU&format=lpcm&sampleRateHertz=$SR&topic=general"
        call { k -> Request.Builder().url(url).header("Authorization", "Api-Key $k")
            .post(pcm.toRequestBody("application/octet-stream".toMediaType())).build() }.use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) error("SpeechKit: у ключа нет роли ai.speechkit-stt.user")
            if (!r.isSuccessful) error("SpeechKit ответил ${r.code}: ${t.take(120)}")
            return JSONObject(t).optString("result").trim()
        }
    }

    // ——— озвучка ———

    @Volatile private var track: AudioTrack? = null

    fun synth(text: String, voice: String): ByteArray {
        val body = FormBody.Builder().add("text", text.take(4800)).add("lang", "ru-RU").add("voice", voice)
            .add("format", "lpcm").add("sampleRateHertz", "48000").add("folderId", YaCloud.folder).build()
        call { k -> Request.Builder().url("https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize")
            .header("Authorization", "Api-Key $k").post(body).build() }.use { r ->
            if (r.code == 401 || r.code == 403) error("SpeechKit: у ключа нет роли ai.speechkit-tts.user")
            if (!r.isSuccessful) error("SpeechKit ответил ${r.code}: ${r.body?.string().orEmpty().take(120)}")
            return r.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Проигрывает PCM 48 кГц, блокирует до конца или до stop(). */
    fun play(pcm: ByteArray) {
        stop()
        val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, 48000)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        try {
            t.play()
            var off = 0
            while (off < pcm.size && track === t) {
                val n = t.write(pcm, off, minOf(8192, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            // дождаться, пока доиграет буфер
            val frames = pcm.size / 2
            while (track === t && t.playbackHeadPosition < frames - 100) Thread.sleep(40)
        } finally {
            runCatching { t.stop() }; t.release()
            if (track === t) track = null
        }
    }

    fun stop() { val t = track; track = null; runCatching { t?.pause(); t?.flush() } }
}

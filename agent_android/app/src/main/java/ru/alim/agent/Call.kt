package ru.alim.agent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Голосовой звонок другу через Gemini Live: голос туда и обратно в реальном времени, можно перебивать.
 * «Руки» — через функцию task: просьба уходит обычному агенту этого друга, ответ возвращается в разговор.
 */
object Call {
    enum class St { IDLE, CONNECTING, LIVE, ENDED }

    var state by mutableStateOf(St.IDLE); private set
    var fid by mutableStateOf(Friends.MAIN); private set
    var startedAt by mutableStateOf(0L); private set
    var error by mutableStateOf(""); private set
    var muted by mutableStateOf(false)
    var speaker by mutableStateOf(true); private set
    /** Друг сейчас говорит. */
    var talking by mutableStateOf(false); private set
    /** Громкость голоса друга 0..1 — для «дыхания» аватарки. */
    var level by mutableFloatStateOf(0f); private set
    var micLevel by mutableFloatStateOf(0f); private set
    /** Что друг делает руками («ищет на Ozon…»). */
    var doing by mutableStateOf(""); private set
    /** Последняя реплика (субтитры). */
    var caption by mutableStateOf(""); private set

    val key: String get() = BuildConfig.GOOGLE_API.trim()
    val ready: Boolean get() = key.isNotBlank()

    private val MODELS = listOf("gemini-3.8-live", "gemini-2.5-flash-native-audio-preview-12-2025")
    private const val IN_RATE = 16000
    private const val OUT_RATE = 24000

    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var scope: CoroutineScope? = null
    private var ws: WebSocket? = null
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var play = Channel<ByteArray>(Channel.UNLIMITED)
    private var micJob: Job? = null
    private var setupDone = false
    private var modelIdx = 0
    private var lastAudioAt = 0L
    private var app: Context? = null
    private var prox: android.os.PowerManager.WakeLock? = null
    /** Друг договаривает фразу, которую Алим оборвал «стой» — её звук выкидываем. */
    @Volatile private var dropping = false
    @Volatile private var ringing = false
    private var dialAt = 0L
    private var ringMin = 3000L
    private val STOP = Regex("(^|[^а-яё])(сто[йп]|погоди|подожди|перебью|перебиваю|секунд(у|очку)|хватит|замолчи|тихо|послушай|слушай,|минутку)", RegexOption.IGNORE_CASE)
    /** Расшифровка разговора — для итога в чате. */
    private val log = StringBuilder()
    private var curIn = StringBuilder()
    private var curOut = StringBuilder()

    // ——— старт / конец ———

    fun start(ctx: Context, friendId: String) {
        if (state == St.CONNECTING || state == St.LIVE) return
        app = ctx.applicationContext
        fid = friendId; error = ""; muted = false; doing = ""; caption = ""; talking = false; level = 0f
        log.clear(); curIn.clear(); curOut.clear()
        if (!ready) { error = "Нет ключа Google (секрет GOOGLE_API)"; state = St.ENDED; return }
        state = St.CONNECTING; modelIdx = 0; startedAt = 0L; dropping = false
        dialAt = System.currentTimeMillis(); ringMin = (1800..5500).random().toLong()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // как у телефона: у уха экран гаснет
        runCatching {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                prox = pm.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "svoi:call").apply { acquire(3 * 3600_000L) }
        }
        audioOn(ctx)
        ring()
        connect()
    }

    fun end(reason: String = "") {
        if (state == St.IDLE || state == St.ENDED) return
        val wasLive = state == St.LIVE
        val dur = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0
        state = St.ENDED
        if (reason.isNotBlank()) error = reason
        runCatching { ws?.close(1000, "bye") }; ws = null
        audioOff()
        scope?.cancel(); scope = null
        talking = false; level = 0f; micLevel = 0f; doing = ""
        flushLine()
        prox?.let { runCatching { it.release(android.os.PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) } }; prox = null
        if (wasLive) { val c = Chat.of(fid); c.note("📞 Звонок · " + if (dur < 60) "$dur сек" else "%d:%02d".format(dur / 60, dur % 60)) }
    }

    /** Экран звонка закрыт — всё сброшено. */
    fun reset() { if (state == St.ENDED) state = St.IDLE }

    fun toggleSpeaker() { speaker = !speaker; route() }

    // ——— связь ———

    private fun connect() {
        setupDone = false
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$key"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup().toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closed(webSocket, code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed(webSocket, response?.code ?: -1, t.message ?: "ошибка сети")
            }
        })
    }

    private fun closed(w: WebSocket, code: Int, reason: String) {
        if (w !== ws) return
        if (state != St.CONNECTING && state != St.LIVE) return
        // модель не подошла — пробуем запасную
        if (!setupDone && modelIdx < MODELS.size - 1 && code != -1) { modelIdx++; connect(); return }
        val msg = when {
            !setupDone && code == -1 -> "Не дозвонился до Google. Проверь, что VPN включён."
            reason.contains("location", true) || reason.contains("region", true) -> "Google не пускает из этой страны — включи VPN."
            reason.contains("quota", true) || reason.contains("exhausted", true) -> "Лимит Google на сегодня закончился."
            reason.contains("key", true) -> "Google не принял ключ (GOOGLE_API)."
            state == St.LIVE -> "Связь оборвалась"
            else -> "Не получилось позвонить: ${reason.take(120)}"
        }
        scope?.launch(Dispatchers.Main) { end(msg) }
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun voiceFor(f: Friend): String {
        val male = listOf("Puck", "Orus", "Charon", "Fenrir")
        val female = listOf("Aoede", "Leda", "Kore", "Zephyr")
        val l = if (f.male) male else female
        return if (f.main) l[0] else l[(f.id.hashCode() and 0x7fffffff) % l.size]
    }

    private fun setup(): JSONObject {
        val f = Friends.get(fid)
        val ctx = app!!
        val chat = Chat.of(fid)
        val sys = buildString {
            append(Persona.describe(f))
            append("\n\nСЕЙЧАС АЛИМ ТЕБЕ ПОЗВОНИЛ — вы говорите ПО ТЕЛЕФОНУ, это обычный телефонный звонок, а не переписка. ")
            append("Веди себя как живой человек на том конце трубки: начинаешь с «Алло?», говоришь по-русски, естественно, коротко (1-3 фразы), " +
                "с живыми телефонными словечками («ага», «слушай», «да-да», «угу»), без списков, ссылок, эмодзи. ")
            append("Если Алим говорит «алло», «ты тут?», «слышишь меня?» — отвечай как по телефону: «Да-да, слышу тебя», «Алло, тут я». ")
            append("Если в трубке слышно что-то непонятное, обрывки чужого разговора, телевизор или фоновый шум — это не тебе: не отвечай на это, просто жди Алима. ")
            append("Если не расслышал — переспроси, как по телефону: «Алло, плохо слышно, повтори?». ")
            append("Не читай вслух длинные перечни — назови главное. ")
            append("Когда нужно что-то СДЕЛАТЬ или УЗНАТЬ (поиск, погода, Ozon, сообщения, фото, маршрут, YouTube, файлы, напоминание и т.п.) — " +
                "сначала скажи коротко «сейчас гляну», затем вызови функцию task с понятной просьбой. Результат перескажи коротко своими словами; " +
                "карточки и фото Алим увидит в вашем чате после звонка. Не выдумывай результат, не вызвав task. ")
            append("Переводы денег по звонку не делай — скажи, что переведёшь, когда Алим напишет сумму в чате. ")
            append("Когда Алим прощается («давай», «пока», «на связи», «всё, давай») — попрощайся одной короткой фразой («Давай, на связи!») и вызови hang_up.\n")
            append("Сейчас: ${SimpleDateFormat("EEEE, d MMMM, HH:mm", Locale("ru")).format(Date())}.\n")
            append("Что ты знаешь об Алиме:\n${runCatching { Memory.forPrompt(ctx, 30) }.getOrDefault("")}\n")
            append("Последняя переписка в чате:\n${chat.recentForPrompt(20).ifBlank { "(пусто)" }}")
        }
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(JSONObject().put("name", "task")
                .put("description", "Поручить дело: всё, что требует действий на телефоне, поиска в интернете, данных или приложений. Возвращает результат текстом.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("request", JSONObject().put("type", "STRING").put("description", "Просьба Алима целиком, своими словами")))
                    .put("required", JSONArray().put("request"))))
            .put(JSONObject().put("name", "hang_up").put("description", "Положить трубку, когда разговор закончен и вы попрощались."))))
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/${MODELS[modelIdx]}")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceFor(f))))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("tools", tools)
            .put("realtimeInputConfig", JSONObject()
                .put("activityHandling", "NO_INTERRUPTION")
                .put("automaticActivityDetection", JSONObject().put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW").put("prefixPaddingMs", 200)))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject())))
    }

    private fun handle(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (o.has("setupComplete")) {
            setupDone = true
            scope?.launch(Dispatchers.Main) {
                // пусть погудит, как настоящий звонок, потом «снимает трубку»
                val wait = dialAt + ringMin - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                if (state != St.CONNECTING) return@launch
                ringing = false; delay(250)
                state = St.LIVE; startedAt = System.currentTimeMillis()
                startMic()
                send(JSONObject().put("realtimeInput", JSONObject().put("text", "(Звонит Алим. Ты сняла/снял трубку — скажи только «Алло?» (можно «Алло, да?») и жди, что он скажет.)")))
            }
            return
        }
        o.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted")) { clearPlayback(); flushOut() }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
                if (curOut.isNotEmpty()) flushOut()
                curIn.append(t); caption = curIn.toString().takeLast(160)
                // перебить можно только явным «стой», «погоди», «перебью»… — остальные голоса вокруг не мешают
                if ((talking || curOut.isNotEmpty()) && !dropping && STOP.containsMatchIn(curIn)) { dropping = true; clearPlayback() }
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { t ->
                if (curIn.isNotEmpty()) flushIn()
                curOut.append(t); caption = curOut.toString().takeLast(160)
            }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    if (!d.optString("mimeType").startsWith("audio") || dropping) continue
                    runCatching { Base64.decode(d.optString("data"), Base64.DEFAULT) }.getOrNull()?.let { play.trySend(it) }
                }
            }
            if (sc.optBoolean("turnComplete")) { flushOut(); dropping = false }
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                scope?.launch { runTool(c.optString("id"), c.optString("name"), c.optJSONObject("args") ?: JSONObject()) }
            }
        }
        if (o.has("goAway")) scope?.launch(Dispatchers.Main) { delay(4000); end("Google завершил сессию — перезвони") }
    }

    private fun flushIn() { if (curIn.isNotBlank()) log.append("Алим: ").append(curIn.trim()).append('\n'); curIn.clear() }
    private fun flushOut() { if (curOut.isNotBlank()) log.append(Friends.get(fid).name).append(": ").append(curOut.trim()).append('\n'); curOut.clear() }
    private fun flushLine() { flushIn(); flushOut() }

    // ——— руки ———

    private suspend fun runTool(id: String, name: String, args: JSONObject) {
        val result = when (name) {
            "hang_up" -> { scope?.launch(Dispatchers.Main) { delay(2500); end() }; "ok" }
            "task" -> doTask(args.optString("request"))
            else -> "нет такой функции"
        }
        send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
            JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result.take(3000)))))))
    }

    private suspend fun doTask(req: String): String {
        if (req.isBlank()) return "пустая просьба"
        val chat = Chat.of(fid)
        val core = chat.core
        // ждём, если друг ещё занят прошлым
        withTimeoutOrNull(60_000) { while (core.busy) delay(300) } ?: return "${Friends.get(fid).name} сейчас занят другим делом, попробуй чуть позже"
        val before = chat.msgs.lastOrNull()?.id ?: 0L
        doing = "делает: ${req.take(60)}"
        kotlinx.coroutines.withContext(Dispatchers.Main) { chat.send("📞 $req") }
        val reply: Msg? = withTimeoutOrNull(150_000) {
            delay(800)
            var m: Msg? = null
            while (m == null) {
                core.steps.lastOrNull()?.let { doing = it.text.take(70) }
                m = chat.msgs.lastOrNull { !it.me && !it.note && it.id > before }?.takeIf { !core.busy }
                if (m == null) delay(400)
            }
            m
        }
        doing = ""
        return when {
            reply == null -> "не успел за 2,5 минуты — результат придёт в чат"
            else -> buildString {
                append(reply.text)
                if (reply.items.isNotBlank()) append("\n(карточки товаров — в чате)")
                if (reply.photos.isNotBlank()) append("\n(фото — в чате)")
                if (reply.files.isNotBlank()) append("\n(файл — в чате)")
            }
        }
    }

    // ——— звук ———

    private fun audioOn(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        speaker = false; route()
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, OUT_RATE)).setTransferMode(AudioTrack.MODE_STREAM).build()
        }.getOrNull()
        track?.play()
        play = Channel(Channel.UNLIMITED)
        val t = track
        val ch = play
        scope?.launch {
            for (buf in ch) {
                if (!isActive) break
                talking = true; lastAudioAt = System.currentTimeMillis()
                level = (rms(buf) * 3f).coerceIn(0f, 1f)
                t?.write(buf, 0, buf.size)
            }
        }
        // «говорит» гаснет через полсекунды тишины
        scope?.launch {
            while (isActive) {
                delay(200)
                if (talking && System.currentTimeMillis() - lastAudioAt > 600) { talking = false; level = 0f }
            }
        }
    }

    /** Длинные гудки (425 Гц: 1 с звук, 4 с тишина), пока друг не снял трубку. */
    private fun ring() {
        ringing = true
        val t = track ?: return
        val chunk = OUT_RATE / 10
        scope?.launch {
            var ms = 0
            while (ringing && isActive) {
                val on = ms % 5000 < 1000
                val b = tone(chunk, if (on) 0.18 else 0.0, ms * OUT_RATE / 1000)
                t.write(b, 0, b.size)
                ms += 100
            }
        }
    }

    private fun tone(n: Int, amp: Double, from: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (Math.sin(2 * Math.PI * 425 * (from + i) / OUT_RATE) * amp * 32767).toInt()
            b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = (v shr 8 and 0xff).toByte()
        }
        return b
    }

    private fun clearPlayback() {
        while (play.tryReceive().isSuccess) { }
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
        talking = false; level = 0f
    }

    private fun route() {
        val am = app?.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val want = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                am.availableCommunicationDevices.firstOrNull { it.type == want }?.let { am.setCommunicationDevice(it) }
            } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = speaker }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        }.getOrNull()
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); end("Микрофон недоступен"); return }
        rec = r
        if (AcousticEchoCanceler.isAvailable()) aec = runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (NoiseSuppressor.isAvailable()) ns = runCatching { NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        r.startRecording()
        micJob = scope?.launch {
            val buf = ByteArray(3200) // 100 мс
            while (isActive) {
                var n = 0
                while (n < buf.size && isActive) { val k = r.read(buf, n, buf.size - n); if (k <= 0) break; n += k }
                if (n <= 0) continue
                if (muted) { micLevel = 0f; continue }
                micLevel = (rms(buf) * 4f).coerceIn(0f, 1f)
                val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                send(JSONObject().put("realtimeInput", JSONObject().put("audio",
                    JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=$IN_RATE"))))
            }
        }
    }

    private fun audioOff() {
        micJob?.cancel(); micJob = null
        rec?.let { runCatching { it.stop() }; it.release() }; rec = null
        aec?.release(); aec = null; ns?.release(); ns = null
        ringing = false
        play.close()
        // короткие гудки «пи-пи-пи» — трубку положили
        track?.let { t ->
            Thread {
                runCatching {
                    t.pause(); t.flush(); t.play()
                    repeat(3) { val on = tone(OUT_RATE * 35 / 100, 0.16, 0); t.write(on, 0, on.size); val off = tone(OUT_RATE * 35 / 100, 0.0, 0); t.write(off, 0, off.size) }
                    Thread.sleep(400)
                }
                runCatching { t.stop() }; t.release()
            }.start()
        }
        track = null
        app?.getSystemService(AudioManager::class.java)?.let { am ->
            runCatching { if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false } }
            runCatching { am.mode = AudioManager.MODE_NORMAL }
        }
    }

    private fun rms(b: ByteArray): Float {
        var s = 0.0; var i = 0; var n = 0
        while (i + 1 < b.size) { val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort() / 32768.0; s += v * v; i += 8; n++ }
        return if (n == 0) 0f else Math.sqrt(s / n).toFloat()
    }
}

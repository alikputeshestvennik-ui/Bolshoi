package ru.alim.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Голос: распознавание и озвучка. Движок выбирается в «Подключениях»:
 * Android (системное распознавание и TTS) или Yandex SpeechKit. Яндекс не ответил — откат на Android.
 */
class Voice(private val ctx: Context) {
    var listening by mutableStateOf(false); private set
    var partial by mutableStateOf(""); private set
    /** Громкость голоса 0..1 — стопка/маскот реагирует. */
    var level by mutableFloatStateOf(0f); private set
    var speaking by mutableStateOf(false); private set

    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recJob: Job? = null
    @Volatile private var recCancel = false
    private var sayJob: Job? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale("ru", "RU"); ttsReady = true }
        }
    }

    private val yaStt get() = Prefs.sttEngine == "yandex" && YaSpeech.ready
    private val yaTts get() = Prefs.ttsEngine == "yandex" && YaSpeech.ready

    fun available() = yaStt || SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listen(onText: (String) -> Unit, onError: (String) -> Unit) {
        stopListen(); hush()
        if (yaStt) { listenYandex(onText, onError); return }
        listenAndroid(onText, onError)
    }

    private fun listenYandex(onText: (String) -> Unit, onError: (String) -> Unit) {
        recCancel = false
        listening = true; partial = ""
        recJob = scope.launch {
            try {
                val pcm = YaSpeech.record(onLevel = { level = it }, cancelled = { recCancel })
                if (recCancel) return@launch
                if (pcm.isEmpty()) { onError("Не расслышал, попробуй ещё"); return@launch }
                partial = "Распознаю…"
                val text = withContext(Dispatchers.IO) { YaSpeech.recognize(pcm) }
                if (text.isNotBlank()) onText(text) else onError("Не расслышал, попробуй ещё")
            } catch (e: Exception) {
                onError((e.message ?: "ошибка распознавания").take(120))
            } finally {
                listening = false; level = 0f; partial = ""
            }
        }
    }

    private fun listenAndroid(onText: (String) -> Unit, onError: (String) -> Unit, retry: Boolean = true) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onError("На телефоне нет службы распознавания речи"); return }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx); sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(error: Int) {
                listening = false; level = 0f; partial = ""
                // Обрыв связи с сервером (часто из-за VPN) — тихо пробуем ещё раз, по возможности офлайн
                if (retry && error in setOf(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, 11)) {
                    runCatching { sr?.destroy() }; sr = null
                    scope.launch { kotlinx.coroutines.delay(200); listenAndroid(onText, onError, retry = false) }
                    return
                }
                onError(when (error) {
                    11 -> "Сервер распознавания отключился — попробуй ещё или включи «Яндекс» в настройках"
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал, попробуй ещё"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, ещё раз"
                    else -> "Ошибка распознавания ($error)"
                })
            }
            override fun onResults(results: Bundle?) {
                listening = false; level = 0f
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                partial = ""
                if (t.isNotBlank()) onText(t) else onError("Не расслышал, попробуй ещё")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .apply { if (!retry) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) })
        listening = true
    }

    fun stopListen() {
        recCancel = true; recJob?.cancel(); recJob = null
        runCatching { sr?.destroy() }; sr = null
        listening = false; level = 0f
    }

    fun say(text: String) {
        hush()
        if (yaTts) {
            sayJob = scope.launch {
                speaking = true
                try {
                    withContext(Dispatchers.IO) { YaSpeech.play(YaSpeech.synth(text, Prefs.ttsVoice)) }
                } catch (e: Exception) {
                    sayAndroid(text) // Яндекс не ответил — говорим системным голосом
                } finally { speaking = false }
            }
            return
        }
        sayAndroid(text)
    }

    private fun sayAndroid(text: String) { if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent") }

    fun hush() {
        sayJob?.cancel(); sayJob = null
        YaSpeech.stop(); tts?.stop(); speaking = false
    }

    fun release() { stopListen(); hush(); tts?.shutdown(); tts = null }
}

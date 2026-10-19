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
import java.util.Locale

/** Голос: системное распознавание речи (сервис, выбранный в телефоне) и озвучка ответов. */
class Voice(private val ctx: Context) {
    var listening by mutableStateOf(false); private set
    var partial by mutableStateOf(""); private set
    /** Громкость голоса 0..1 — стопка слоёв раздвигается в такт. */
    var level by mutableFloatStateOf(0f); private set

    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale("ru", "RU"); ttsReady = true }
        }
    }

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listen(onText: (String) -> Unit, onError: (String) -> Unit) {
        stopListen(); tts?.stop()
        if (!available()) { onError("На телефоне нет службы распознавания речи"); return }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx); sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(error: Int) {
                listening = false; level = 0f; partial = ""
                onError(when (error) {
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
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        listening = true
    }

    fun stopListen() {
        runCatching { sr?.destroy() }; sr = null
        listening = false; level = 0f
    }

    fun say(text: String) { if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent") }
    fun hush() { tts?.stop() }

    fun release() { stopListen(); tts?.shutdown(); tts = null }
}

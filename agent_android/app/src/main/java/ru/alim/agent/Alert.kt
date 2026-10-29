package ru.alim.agent

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Сигналы помимо системного уведомления: голос, вспышка, пробуждение экрана (на сложенном Flip — внешнего).
 * Уровни: QUIET — ничего; NORMAL — одно мигание + экран; IMPORTANT — голос + 3 мигания + экран.
 * «Не беспокоить» — молчим полностью. Беззвучный/вибро — без голоса (если нет наушников).
 */
object Alert {
    const val QUIET = 0
    const val NORMAL = 1
    const val IMPORTANT = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = ArrayList<String>()

    fun fire(ctx: Context, level: Int, speech: String = "") {
        if (level <= QUIET) return
        val c = ctx.applicationContext
        if (dnd(c)) return
        if (Prefs.alertWake) wake(c)
        if (Prefs.alertFlash) flash(c, if (level >= IMPORTANT) 3 else 1)
        if (level >= IMPORTANT && Prefs.alertVoice && speech.isNotBlank() && canSpeak(c)) say(c, speech)
    }

    /** Кнопка «Проверить» в настройках — всё сразу, мимо уровней. */
    fun test(ctx: Context) {
        val c = ctx.applicationContext
        wake(c); flash(c, 3); say(c, "Пилот на связи. Так звучат важные уведомления.")
    }

    private fun dnd(c: Context): Boolean = runCatching {
        c.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
    }.getOrDefault(false)

    private fun headphones(am: AudioManager): Boolean = runCatching {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }.getOrDefault(false)

    private fun canSpeak(c: Context): Boolean {
        val am = c.getSystemService(AudioManager::class.java) ?: return false
        if (headphones(am)) return true
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return false
        return true
    }

    // ——— голос ———

    private fun say(c: Context, text: String) {
        val t = text.replace(Regex("[\\p{So}\\p{Cn}]"), "").replace(Regex("https?://\\S+"), "").trim().take(220)
        if (t.isBlank()) return
        scope.launch(Dispatchers.Main) {
            if (tts == null) {
                pending += t
                tts = TextToSpeech(c) { st ->
                    ttsReady = st == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        tts?.language = Locale("ru", "RU")
                        tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        pending.forEach { p -> speak(c, p) }
                    }
                    pending.clear()
                }
            } else if (ttsReady) speak(c, t)
        }
    }

    private fun speak(c: Context, t: String) {
        // голос просит короткий «фокус» — музыка приглушится и вернётся
        val am = c.getSystemService(AudioManager::class.java)
        @Suppress("DEPRECATION") runCatching { am?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) }
        tts?.speak(t, TextToSpeech.QUEUE_ADD, Bundle(), "pilot-${System.nanoTime()}")
        scope.launch { delay(1000L + t.length * 90L); @Suppress("DEPRECATION") runCatching { am?.abandonAudioFocus(null) } }
    }

    // ——— вспышка ———

    private fun flash(c: Context, times: Int) {
        val cm = c.getSystemService(CameraManager::class.java) ?: return
        val id = runCatching {
            cm.cameraIdList.firstOrNull { i ->
                val ch = cm.getCameraCharacteristics(i)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull() ?: return
        scope.launch {
            repeat(times) { k ->
                if (runCatching { cm.setTorchMode(id, true) }.isFailure) return@launch   // камера занята / фонарик включён — не мешаем
                delay(110)
                runCatching { cm.setTorchMode(id, false) }
                if (k < times - 1) delay(160)
            }
        }
    }

    // ——— экран ———

    /** Будим экран на пару секунд: на сложенном Flip загорается внешний, там видно уведомление. */
    @Suppress("DEPRECATION")
    private fun wake(c: Context) {
        val pm = c.getSystemService(PowerManager::class.java) ?: return
        if (pm.isInteractive) return
        runCatching {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "pilot:alert")
                .acquire(4000)
        }
    }
}

package ru.alim.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Куда открыть приложение из уведомления. */
object Nav { var open by mutableStateOf("") }

class MainActivity : ComponentActivity() {
    private lateinit var voice: Voice

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // во весь экран: приложение рисует и под шторкой, и под нижней полосой
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        Prefs.init(this)
        SkyWeather.load()
        Th.load()
        Agent.app = applicationContext
        Chat.init(this)
        Online.init(this)
        YaMusic.init(this)
        YtMe.init(this)
        Fonts.load(assets)
        voice = Voice(this)
        Agent.onSpeak = { voice.say(it) }
        Nav.open = intent?.getStringExtra("open").orEmpty()
        Monitor.schedule(this)
        ArturService.start(this)
        setContent { App(voice) }
    }

    override fun onResume() { super.onResume(); AppState.foreground = true
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).cancel(7777) } }
    override fun onPause() { AppState.foreground = false; super.onPause() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Nav.open = intent.getStringExtra("open").orEmpty()
    }

    override fun onDestroy() {
        Agent.onSpeak = null
        voice.release()
        super.onDestroy()
    }
}

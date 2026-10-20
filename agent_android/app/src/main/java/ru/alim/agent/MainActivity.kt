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
        Prefs.init(this)
        Agent.app = applicationContext
        Fonts.load(assets)
        voice = Voice(this)
        Agent.onSpeak = { voice.say(it) }
        Nav.open = intent?.getStringExtra("open").orEmpty()
        Monitor.schedule(this)
        setContent { App(voice) }
    }

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

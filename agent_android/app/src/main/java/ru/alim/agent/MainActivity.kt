package ru.alim.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private lateinit var voice: Voice

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Agent.app = applicationContext
        Fonts.load(assets)
        voice = Voice(this)
        Agent.onSpeak = { voice.say(it) }
        setContent { App(voice) }
    }

    override fun onDestroy() {
        Agent.onSpeak = null
        voice.release()
        super.onDestroy()
    }
}

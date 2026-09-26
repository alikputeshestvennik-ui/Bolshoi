package com.example.speechfilter.wear

import android.content.Intent
import com.google.android.gms.wearable.WearableListenerService
import androidx.core.content.ContextCompat

class ControlService : WearableListenerService() {
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        when (event.path) {
            "/control/start" -> ContextCompat.startForegroundService(
                this, Intent(this, WatchRecordingService::class.java)
            )
            "/control/stop" -> stopService(Intent(this, WatchRecordingService::class.java))
        }
    }
}

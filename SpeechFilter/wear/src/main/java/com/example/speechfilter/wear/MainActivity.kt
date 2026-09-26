package com.example.speechfilter.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val status = findViewById<TextView>(R.id.status)
        findViewById<Button>(R.id.start).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 20)
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, WatchRecordingService::class.java))
            status.text = "RECORDING"
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            stopService(Intent(this, WatchRecordingService::class.java))
            status.text = "STOPPED"
        }
    }
}

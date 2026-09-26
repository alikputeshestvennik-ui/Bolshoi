package com.example.speechfilter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var recordingsContainer: LinearLayout
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        button = findViewById(R.id.startStop)
        recordingsContainer = findViewById(R.id.recordingsContainer)
        playerView = findViewById(R.id.playerView)
        findViewById<RadioButton>(R.id.autoSource).isChecked = true
        button.setOnClickListener { if (!running) startRecording() else stopRecording() }
        refreshRecordings()
    }

    override fun onResume() {
        super.onResume()
        refreshRecordings()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        val source = when {
            findViewById<RadioButton>(R.id.watchSource).isChecked -> "WATCH"
            findViewById<RadioButton>(R.id.phoneSource).isChecked -> "PHONE"
            else -> "AUTO"
        }
        ContextCompat.startForegroundService(
            this, Intent(this, RecordingService::class.java).putExtra("source", source)
        )
        running = true
        button.text = "STOP"
        status.text = "Запись запущена"
    }

    private fun stopRecording() {
        stopService(Intent(this, RecordingService::class.java))
        running = false
        button.text = "START"
        status.text = "Запись завершена"
        window.decorView.postDelayed({ refreshRecordings() }, 250)
    }

    private fun recordingsDir(): File = File(getExternalFilesDir(null), "SpeechFilter")

    private fun refreshRecordings() {
        recordingsContainer.removeAllViews()
        val files = recordingsDir().listFiles { f -> f.isFile && f.extension.equals("wav", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            recordingsContainer.addView(TextView(this).apply {
                text = "Записей пока нет"
                textSize = 16f
                setPadding(0, 12, 0, 12)
            })
            return
        }

        files.forEach { file -> addRecordingRow(file) }
    }

    private fun addRecordingRow(file: File) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val title = TextView(this).apply {
            text = file.name.removeSuffix(".wav")
            textSize = 17f
        }
        val meta = TextView(this).apply {
            text = "${formatSize(file.length())} • ${formatDate(file.lastModified())}"
            textSize = 13f
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val play = Button(this).apply { text = "▶ Прослушать" }
        val share = Button(this).apply { text = "Экспорт" }
        play.setOnClickListener { playFile(file) }
        share.setOnClickListener { shareFile(file) }
        buttons.addView(play, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(share, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(title)
        row.addView(meta)
        row.addView(buttons)
        recordingsContainer.addView(row)
    }

    private fun playFile(file: File) {
        releasePlayer()
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exo.prepare()
            exo.play()
        }
        status.text = "Воспроизведение: ${file.name}"
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Экспорт WAV"))
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    }

    private fun formatDate(time: Long): String = android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", time).toString()
}

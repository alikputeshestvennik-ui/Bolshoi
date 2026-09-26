package com.example.speechfilter

import android.app.*
import android.content.Intent
import android.media.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.speechfilter.shared.*
import com.google.android.gms.wearable.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class RecordingService : Service(), ChannelClient.ChannelCallback {
    private val io = Executors.newSingleThreadExecutor()
    private var source = "AUTO"
    @Volatile private var stopping = false
    private var localThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var wav: WavWriter? = null
    private var engine: SpeechEngine? = null
    private var watchStarted = false
    private var channelClient: ChannelClient? = null
    private var channel: ChannelClient.Channel? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(42, notification("Ожидание источника"))
        channelClient = Wearable.getChannelClient(this)
        channelClient?.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        source = intent?.getStringExtra("source") ?: "AUTO"
        stopPipelines()
        if (source == "PHONE") startPhone()
        else startWatchOrFallback()
        return START_NOT_STICKY
    }

    private fun startWatchOrFallback() {
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            val nearby = nodes.firstOrNull { it.isNearby }
            if (nearby == null) {
                startPhone()
                return@addOnSuccessListener
            }
            watchStarted = true
            Wearable.getMessageClient(this)
                .sendMessage(nearby.id, "/control/start", byteArrayOf())
                .addOnFailureListener { startPhone() }
            updateNotification("Galaxy Watch 8: ожидание аудиоканала")
        }.addOnFailureListener { startPhone() }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != "/speech-audio") return
        this.channel = channel
        Wearable.getChannelClient(this).getInputStream(channel)
            .addOnSuccessListener { input ->
                updateNotification("Galaxy Watch 8: поток речи")
                thread(name = "watch-pcm-reader") {
                    val parser = AudioPacket.Parser { _, pcm ->
                        ensureWav().appendPcm16(pcm)
                    }
                    val buf = ByteArray(16 * 1024)
                    try {
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            parser.push(buf.copyOf(n))
                        }
                    } catch (_: Exception) {
                        if (!stopping) startPhone()
                    } finally {
                        try { input.close() } catch (_: Exception) {}
                    }
                }
            }
            .addOnFailureListener { startPhone() }
    }

    override fun onChannelClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        if (!stopping && channel.path == "/speech-audio") startPhone()
    }

    override fun onChannelOpened(channel: ChannelClient.Channel, p1: Throwable?) {}

    private fun startPhone() {
        if (localThread?.isAlive == true) return
        watchStarted = false
        updateNotification("Телефон: обработка микрофона")
        localThread = thread(name = "phone-audio") {
            try {
                val min = AudioRecord.getMinBufferSize(
                    AudioConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, AudioConfig.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    (min * 2).coerceAtLeast(AudioConfig.FRAME_SAMPLES * 4 * 2)
                )
                audioRecord = record
                val models = ModelFiles
                val vad = SileroVad(models.silero(this))
                val yam = YamnetClassifier(models.yamnet(this), models.labels(this))
                val e = SpeechEngine(vad, yam) { pcm -> ensureWav().appendPcm16(pcm) }
                engine = e
                record.startRecording()
                val buf = ShortArray(AudioConfig.FRAME_SAMPLES)
                while (!stopping && record.read(buf, 0, buf.size) > 0) e.acceptPcm16(buf)
                record.stop(); record.release()
                vad.close(); yam.close()
            } catch (t: Throwable) {
                updateNotification("Ошибка: ${t.message ?: "audio"}")
            }
        }
    }

    private fun ensureWav(): WavWriter {
        wav?.let { return it }
        val dir = File(getExternalFilesDir(null), "SpeechFilter").apply { mkdirs() }
        val file = File(dir, "speech_${System.currentTimeMillis()}.wav")
        return WavWriter(file).also { it.start(); wav = it }
    }

    private fun stopPipelines() {
        stopping = true
        localThread?.interrupt()
        localThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord = null
        try { channel?.close() } catch (_: Exception) {}
        channel = null
        wav?.close()
        wav = null
    }

    override fun onDestroy() {
        stopPipelines()
        channelClient?.removeListener(this)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("speech", "Speech Filter", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String) =
        NotificationCompat.Builder(this, "speech").setContentTitle("Speech Filter")
            .setContentText(text).setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(42, notification(text))
    }
}

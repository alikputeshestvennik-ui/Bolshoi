package com.example.speechfilter.wear

import android.app.*
import android.content.Intent
import android.media.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.speechfilter.shared.*
import com.google.android.gms.wearable.*\nimport com.google.android.gms.tasks.Tasks
import java.io.OutputStream
import kotlin.concurrent.thread

class WatchRecordingService : Service() {
    @Volatile private var stopping = false
    private var worker: Thread? = null
    private var record: AudioRecord? = null
    private var output: OutputStream? = null
    private var channel: ChannelClient.Channel? = null
    private var sequence = 0

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch_speech", "Speech Filter", NotificationManager.IMPORTANCE_LOW))
        startForeground(7, notification("Galaxy Watch 8: preparing"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        worker?.interrupt()
        worker = thread(name = "watch-audio") { runCapture() }
        return START_NOT_STICKY
    }

    private fun runCapture() {
        try {
            val phone = Wearable.getNodeClient(this).connectedNodes
                .result?.firstOrNull { it.isNearby }
                ?: throw IllegalStateException("Phone is not connected")

            channel = Tasks.await(Wearable.getChannelClient(this).openChannel(phone.id, "/speech-audio"))
            output = Tasks.await(Wearable.getChannelClient(this).getOutputStream(channel))

            val models = ModelFiles
            val vad = SileroVad(models.silero(this))
            val yam = YamnetClassifier(models.yamnet(this), models.labels(this))
            val engine = SpeechEngine(vad, yam) { pcm ->
                output?.write(AudioPacket.encode(sequence++, pcm))
                output?.flush()
            }

            val min = AudioRecord.getMinBufferSize(
                AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                (min * 2).coerceAtLeast(AudioConfig.FRAME_SAMPLES * 4 * 2)
            )
            record = r
            update("RECORDING • VAD + YAMNet")
            r.startRecording()
            val buf = ShortArray(AudioConfig.FRAME_SAMPLES)
            while (!stopping) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) engine.acceptPcm16(if (n == buf.size) buf else buf.copyOf(n))
            }
            r.stop(); r.release()
            vad.close(); yam.close()
        } catch (t: Throwable) {
            update("ERROR: ${t.message ?: "audio"}")
        } finally {
            try { output?.close() } catch (_: Exception) {}
            try { channel?.close() } catch (_: Exception) {}
            output = null; channel = null
        }
    }

    override fun onDestroy() {
        stopping = true
        try { record?.stop() } catch (_: Exception) {}
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String) =
        NotificationCompat.Builder(this, "watch_speech")
            .setContentTitle("Speech Filter").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()

    private fun update(text: String) =
        getSystemService(NotificationManager::class.java).notify(7, notification(text))
}

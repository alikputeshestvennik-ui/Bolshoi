package com.example.speechfilter.shared

import java.io.RandomAccessFile

class WavWriter(
    private val file: java.io.File,
    private val sampleRate: Int = AudioConfig.SAMPLE_RATE,
    private val channels: Int = AudioConfig.CHANNELS
) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        raf.setLength(0)
        val header = ByteArray(44)
        raf.write(header)
    }

    @Synchronized fun appendPcm16(samples: ShortArray) {
        if (!started) start()
        val bytes = ByteArray(samples.size * 2)
        var p = 0
        for (s in samples) {
            bytes[p++] = (s.toInt() and 0xff).toByte()
            bytes[p++] = ((s.toInt() ushr 8) and 0xff).toByte()
        }
        raf.seek(raf.length())
        raf.write(bytes)
        dataBytes += bytes.size
    }

    fun close() {
        if (!started) return
        val byteRate = sampleRate * channels * 2
        raf.seek(0)
        raf.writeBytes("RIFF")
        writeLE32((36 + dataBytes).toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeLE32(16)
        writeLE16(1)
        writeLE16(channels)
        writeLE32(sampleRate)
        writeLE32(byteRate)
        writeLE16(channels * 2)
        writeLE16(16)
        raf.writeBytes("data")
        writeLE32(dataBytes.toInt())
        raf.close()
    }

    private fun writeLE16(v: Int) { raf.write(v and 255); raf.write((v ushr 8) and 255) }
    private fun writeLE32(v: Int) {
        raf.write(v and 255); raf.write((v ushr 8) and 255)
        raf.write((v ushr 16) and 255); raf.write((v ushr 24) and 255)
    }
}

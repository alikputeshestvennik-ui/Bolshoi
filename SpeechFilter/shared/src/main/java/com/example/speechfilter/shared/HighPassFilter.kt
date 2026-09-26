package com.example.speechfilter.shared

class HighPassFilter(private val sampleRate: Float = 16000f, cutoffHz: Float = 90f) {
    private val alpha: Float
    private var prevX = 0f
    private var prevY = 0f

    init {
        val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
        val dt = 1f / sampleRate
        alpha = rc / (rc + dt)
    }

    fun process(samples: ShortArray): ShortArray {
        for (i in samples.indices) {
            val x = samples[i].toFloat()
            val y = alpha * (prevY + x - prevX)
            samples[i] = y.coerceIn(-32768f, 32767f).toInt().toShort()
            prevX = x
            prevY = y
        }
        return samples
    }

    fun reset() { prevX = 0f; prevY = 0f }
}

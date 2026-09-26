package com.example.speechfilter.shared

import kotlin.math.min

class SpeechEngine(
    private val vad: SileroVad,
    private val yamnet: YamnetClassifier,
    private val onSpeechPcm: (ShortArray) -> Unit
) {
    private val hp = HighPassFilter()\n    private val preRoll = ShortRingBuffer(AudioConfig.SAMPLE_RATE * AudioConfig.PRE_ROLL_MS / 1000)
    private val candidate = ArrayList<ShortArray>()
    private val yamnetBuffer = ShortArray(AudioConfig.YAMNET_WINDOW_SAMPLES)
    private var yamnetSize = 0
    private var inSpeech = false
    private var confirmed = false
    private var silenceSamples = 0
    private val postRollSamples = AudioConfig.SAMPLE_RATE * AudioConfig.POST_ROLL_MS / 1000

    fun reset() {
        preRoll.clear()
        candidate.clear()
        yamnetSize = 0
        inSpeech = false
        confirmed = false
        silenceSamples = 0
        vad.reset()
        hp.reset()
    }

    fun acceptPcm16(samples: ShortArray) {
        val filtered = samples.copyOf()
        HighPassFilter().process(filtered)
        preRoll.add(filtered)

        // AudioConfig.FRAME_SAMPLES == 512 at 16 kHz.
        var offset = 0
        while (offset + 512 <= filtered.size) {
            val frame = filtered.copyOfRange(offset, offset + 512)
            offset += 512
            val f = FloatArray(512) { frame[it] / 32768f }
            val p = vad.probability(f)
            val speech = p >= AudioConfig.VAD_THRESHOLD

            if (!inSpeech && speech) {
                inSpeech = true
                confirmed = false
                silenceSamples = 0
                candidate.clear()
                yamnetSize = 0
                if (previous.isNotEmpty()) candidate.add(previous)
            }

            if (inSpeech) {
                candidate.add(frame)
                if (yamnetSize < yamnetBuffer.size) {
                    val n = min(frame.size, yamnetBuffer.size - yamnetSize)
                    frame.copyInto(yamnetBuffer, yamnetSize, 0, n)
                    yamnetSize += n
                }

                if (!confirmed && yamnetSize >= yamnetBuffer.size) {
                    confirmed = yamnet.isHumanSpeech(yamnetBuffer.copyOf(), 0.18f)
                    if (confirmed) flushCandidate()
                    else {
                        // Keep listening; another YAMNet window can confirm the candidate.
                        yamnetSize = 0
                    }
                }

                if (!speech) silenceSamples += frame.size else silenceSamples = 0

                if (silenceSamples >= postRollSamples) {
                    if (confirmed) {
                        flushCandidate()
                    }
                    candidate.clear()
                    yamnetSize = 0
                    inSpeech = false
                    confirmed = false
                    silenceSamples = 0
                }
            }
        }
        preRoll.add(filtered)
    }

    private fun flushCandidate() {
        if (candidate.isEmpty()) return
        for (part in candidate) onSpeechPcm(part)
        candidate.clear()
    }
}

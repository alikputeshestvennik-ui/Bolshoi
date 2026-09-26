package com.example.speechfilter.shared

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_MS = 32
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    const val PRE_ROLL_MS = 500
    const val POST_ROLL_MS = 700
    const val VAD_THRESHOLD = 0.50f
    const val YAMNET_WINDOW_SAMPLES = 15_600 // 0.975 s @ 16 kHz
    const val SPEECH_GAP_MERGE_MS = 700
}

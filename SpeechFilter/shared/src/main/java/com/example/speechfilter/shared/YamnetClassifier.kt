package com.example.speechfilter.shared

import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class YamnetClassifier(modelFile: File, labelsFile: File) : AutoCloseable {
    private val interpreter = Interpreter(map(modelFile), Interpreter.Options().apply { setNumThreads(2) })
    private val labels = labelsFile.readLines().mapNotNull { line ->
        val p = line.split(',', limit = 3)
        if (p.size >= 3) p[2].trim().removeSurrounding(""") else line.trim()
    }
    private val speechIndices = labels.mapIndexedNotNull { i, label ->
        if (label.contains("speech", true) ||
            label.contains("conversation", true) ||
            label.contains("narration", true) ||
            label.contains("monologue", true)) i else null
    }

    fun isHumanSpeech(samples: FloatArray, threshold: Float = 0.18f): Boolean {
        if (samples.size != AudioConfig.YAMNET_WINDOW_SAMPLES) return false
        val input = arrayOf(samples)
        val output = Array(1) { FloatArray(521) }
        interpreter.run(input, output)
        val score = if (speechIndices.isEmpty()) {
            output[0].maxOrNull() ?: 0f
        } else {
            speechIndices.sumOf { output[0].getOrElse(it) { 0f }.toDouble() }.toFloat()
        }
        return score >= threshold
    }

    override fun close() = interpreter.close()

    private fun map(file: File): MappedByteBuffer =
        FileInputStream(file).channel.use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) }
}

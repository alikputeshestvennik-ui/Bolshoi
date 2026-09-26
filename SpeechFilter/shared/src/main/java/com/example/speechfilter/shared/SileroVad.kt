package com.example.speechfilter.shared

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

class SileroVad(private val modelFile: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var state = Array(2) { Array(1) { FloatArray(128) } }
    private var context = FloatArray(64)

    init {
        val opts = OrtSession.SessionOptions().apply {
            setInterOpNumThreads(1)
            setIntraOpNumThreads(1)
            addCPU(true)
        }
        session = env.createSession(modelFile.absolutePath, opts)
    }

    @Synchronized
    fun probability(frame512: FloatArray): Float {
        require(frame512.size == 512) { "Silero v5/v6 16 kHz expects 512 samples per step" }
        val input = FloatArray(576)
        System.arraycopy(context, 0, input, 0, 64)
        System.arraycopy(frame512, 0, input, 64, 512)

        val inputTensor = OnnxTensor.createTensor(env, arrayOf(input))
        val stateTensor = OnnxTensor.createTensor(env, state)
        val srTensor = OnnxTensor.createTensor(env, longArrayOf(16000))
        val result = session.run(mapOf("input" to inputTensor, "state" to stateTensor, "sr" to srTensor))
        try {
            val out = result[0].value as Array<FloatArray>
            state = result[1].value as Array<Array<FloatArray>>
            System.arraycopy(input, input.size - 64, context, 0, 64)
            return out[0][0]
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
            result.close()
        }
    }

    fun reset() {
        state = Array(2) { Array(1) { FloatArray(128) } }
        context = FloatArray(64)
    }

    override fun close() { session.close() }
}

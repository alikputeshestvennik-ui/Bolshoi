package com.example.speechfilter.shared

import android.content.Context
import java.io.File

object ModelFiles {
    private fun copyIfMissing(context: Context, name: String): File {
        val out = File(context.filesDir, name)
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }

    fun silero(context: Context) = copyIfMissing(context, "silero_vad.onnx")
    fun yamnet(context: Context) = copyIfMissing(context, "yamnet.tflite")
    fun labels(context: Context) = copyIfMissing(context, "yamnet_class_map.csv")
}

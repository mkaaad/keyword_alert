package com.keyword.keyword_alert

import android.content.Context
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import io.flutter.plugin.common.EventChannel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AsrStreamHandler(
    private val audioCapture: AudioCapture,
    private val context: Context
) {
    private var sink: EventChannel.EventSink? = null
    private var thread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var recognizer: OfflineRecognizer? = null

    fun setSink(sink: EventChannel.EventSink?) {
        this.sink = sink
        if (sink != null && !isRunning.get()) start()
    }

    private fun ensureModelFiles(): File {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()
        val modelFile = File(modelDir, "model.int8.onnx")
        if (!modelFile.exists()) {
            context.assets.open("models/model.int8.onnx").use { i ->
                modelFile.outputStream().use { o -> i.copyTo(o) }
            }
        }
        return modelDir
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            val modelPath = File(ensureModelFiles(), "model.int8.onnx").absolutePath
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelPath,
                        useInverseTextNormalization = true
                    ),
                    numThreads = 1
                )
            )
            recognizer = OfflineRecognizer(null, config)

            val samplesPerChunk = (16000 * 3000L / 1000).toInt()

            while (isRunning.get()) {
                if (audioCapture.audioBuffer.size < samplesPerChunk) {
                    Thread.sleep(200); continue
                }
                val chunk: FloatArray = synchronized(audioCapture.audioBuffer) {
                    val size = minOf(audioCapture.audioBuffer.size, samplesPerChunk)
                    val arr = FloatArray(size)
                    for (i in 0 until size) arr[i] = audioCapture.audioBuffer[i].toFloat() / 32768f
                    repeat(size) { audioCapture.audioBuffer.removeAt(0) }
                    arr
                }
                val stream = recognizer!!.createStream()
                stream.acceptWaveform(chunk, 16000)
                recognizer!!.decode(stream)
                val text = recognizer!!.getResult(stream).text
                stream.release()
                if (text.isNotEmpty()) sink?.success(text)
            }
            recognizer?.release(); recognizer = null
        }.apply { name = "AsrStream"; start() }
    }

    fun stop() {
        isRunning.set(false); thread?.interrupt(); thread = null
        recognizer?.release(); recognizer = null
    }
}

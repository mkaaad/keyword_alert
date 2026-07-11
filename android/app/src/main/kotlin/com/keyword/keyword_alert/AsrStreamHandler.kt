package com.keyword.keyword_alert

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    companion object {
        private const val TAG = "KeywordAlert"
        private const val SAMPLE_RATE = 16000
        /** ~3s of audio per decode chunk */
        private const val CHUNK_MS = 3000L
        /** Cap buffer to ~30s to avoid OOM when ASR falls behind */
        private const val MAX_BUFFER_SAMPLES = SAMPLE_RATE * 30
        private val FLUTTER_ASSET_PREFIXES = listOf(
            "flutter_assets/assets/models/",
            "assets/models/",
            "models/",
        )
    }

    private var sink: EventChannel.EventSink? = null
    private var thread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var recognizer: OfflineRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setSink(sink: EventChannel.EventSink?) {
        this.sink = sink
        if (sink != null && !isRunning.get() && audioCapture.isActive()) {
            start()
        }
    }

    /** Copy model assets into app filesDir; returns model directory. */
    private fun ensureModelFiles(): File {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()

        copyAssetIfNeeded("model.int8.onnx", File(modelDir, "model.int8.onnx"))
        copyAssetIfNeeded("tokens.txt", File(modelDir, "tokens.txt"))

        val modelFile = File(modelDir, "model.int8.onnx")
        val tokensFile = File(modelDir, "tokens.txt")
        if (!modelFile.exists() || modelFile.length() == 0L) {
            throw IllegalStateException("ASR model missing: ${modelFile.absolutePath}")
        }
        if (!tokensFile.exists() || tokensFile.length() == 0L) {
            throw IllegalStateException("ASR tokens missing: ${tokensFile.absolutePath}")
        }
        return modelDir
    }

    private fun copyAssetIfNeeded(name: String, dest: File) {
        if (dest.exists() && dest.length() > 0L) return
        val assetPath = resolveAssetPath(name)
            ?: throw IllegalStateException("Asset not found for $name (checked $FLUTTER_ASSET_PREFIXES)")
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "Copied asset $assetPath -> ${dest.absolutePath} (${dest.length()} bytes)")
    }

    private fun resolveAssetPath(name: String): String? {
        for (prefix in FLUTTER_ASSET_PREFIXES) {
            val path = prefix + name
            try {
                context.assets.open(path).use { return path }
            } catch (_: Exception) {
                // try next
            }
        }
        // Fallback: search asset tree one level deep
        return try {
            findAssetRecursive(context.assets.list("")?.toList().orEmpty(), name, "")
        } catch (_: Exception) {
            null
        }
    }

    private fun findAssetRecursive(entries: List<String>, name: String, prefix: String): String? {
        for (entry in entries) {
            val path = if (prefix.isEmpty()) entry else "$prefix/$entry"
            try {
                val children = context.assets.list(path)
                if (children != null && children.isNotEmpty()) {
                    val found = findAssetRecursive(children.toList(), name, path)
                    if (found != null) return found
                } else if (entry == name || path.endsWith("/$name")) {
                    context.assets.open(path).use { return path }
                }
            } catch (_: Exception) {
                // continue
            }
        }
        return null
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            try {
                val modelDir = ensureModelFiles()
                val modelPath = File(modelDir, "model.int8.onnx").absolutePath
                val tokensPath = File(modelDir, "tokens.txt").absolutePath
                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = modelPath,
                            language = "zh",
                            useInverseTextNormalization = true
                        ),
                        tokens = tokensPath,
                        numThreads = 2,
                        debug = false,
                    )
                )
                recognizer = OfflineRecognizer(null, config)
                Log.i(TAG, "OfflineRecognizer ready (SenseVoice)")

                val samplesPerChunk = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()

                while (isRunning.get()) {
                    // Drop oldest samples if buffer is too large
                    synchronized(audioCapture.audioBuffer) {
                        val overflow = audioCapture.audioBuffer.size - MAX_BUFFER_SAMPLES
                        if (overflow > 0) {
                            repeat(overflow) { audioCapture.audioBuffer.removeAt(0) }
                            Log.w(TAG, "Audio buffer overflow, dropped $overflow samples")
                        }
                    }

                    if (audioCapture.audioBuffer.size < samplesPerChunk) {
                        try {
                            Thread.sleep(200)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }

                    val chunk: FloatArray = synchronized(audioCapture.audioBuffer) {
                        val size = minOf(audioCapture.audioBuffer.size, samplesPerChunk)
                        val arr = FloatArray(size)
                        for (i in 0 until size) {
                            arr[i] = audioCapture.audioBuffer[i].toFloat() / 32768f
                        }
                        repeat(size) { audioCapture.audioBuffer.removeAt(0) }
                        arr
                    }

                    val stream = recognizer!!.createStream()
                    try {
                        stream.acceptWaveform(chunk, SAMPLE_RATE)
                        recognizer!!.decode(stream)
                        val text = recognizer!!.getResult(stream).text.trim()
                        if (text.isNotEmpty()) {
                            // EventChannel.EventSink must be invoked on the main thread
                            val s = sink
                            if (s != null) {
                                mainHandler.post {
                                    try {
                                        s.success(text)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to emit ASR text", e)
                                    }
                                }
                            }
                        }
                    } finally {
                        stream.release()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ASR thread failed", e)
                val s = sink
                if (s != null) {
                    mainHandler.post {
                        try {
                            s.error("ASR_ERROR", e.message, null)
                        } catch (_: Exception) {
                        }
                    }
                }
            } finally {
                recognizer?.release()
                recognizer = null
                isRunning.set(false)
            }
        }.apply {
            name = "AsrStream"
            start()
        }
    }

    fun stop() {
        isRunning.set(false)
        thread?.interrupt()
        try {
            thread?.join(2000)
        } catch (_: InterruptedException) {
        }
        thread = null
        recognizer?.release()
        recognizer = null
    }
}

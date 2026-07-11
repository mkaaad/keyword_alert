package com.keyword.keyword_alert

import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Process
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : FlutterActivity() {
    private var audioCapture: AudioCapture? = null
    private var asrStreamHandler: AsrStreamHandler? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/audio_capture"
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCapture" -> {
                    Thread {
                        try {
                            val ok = startCaptureWithRoot()
                            runOnUiThread { result.success(ok) }
                        } catch (e: Exception) {
                            Log.e("KeywordAlert", "startCapture failed", e)
                            runOnUiThread { result.success(false) }
                        }
                    }.start()
                }
                "stopCapture" -> {
                    audioCapture?.stop()
                    asrStreamHandler?.stop()
                    result.success(true)
                }
                "isActive" -> result.success(audioCapture?.isActive() ?: false)
                else -> result.notImplemented()
            }
        }

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/asr_stream"
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                asrStreamHandler?.setSink(events)
            }
            override fun onCancel(arguments: Any?) {
                asrStreamHandler?.stop()
            }
        })
    }

    private fun startCaptureWithRoot(): Boolean {
        Log.i("KeywordAlert", "Requesting root...")
        if (!execSu("id")) {
            Log.e("KeywordAlert", "Root denied")
            return false
        }
        Log.i("KeywordAlert", "Root granted")
        val pkg = packageName
        execSu("pm grant $pkg android.permission.RECORD_AUDIO")
        execSu("pm grant $pkg android.permission.CAPTURE_AUDIO_OUTPUT")
        audioCapture = AudioCapture()
        val ok = audioCapture!!.start()
        if (ok) asrStreamHandler = AsrStreamHandler(audioCapture!!, this@MainActivity)
        Log.i("KeywordAlert", if (ok) "AudioCapture started" else "AudioCapture failed")
        return ok
    }

    private fun execSu(command: String): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
        } catch (e: Exception) {
            Log.w("KeywordAlert", "su failed: $command", e)
            false
        }
    }

    override fun onDestroy() {
        audioCapture?.stop()
        asrStreamHandler?.stop()
        super.onDestroy()
    }
}

class AudioCapture {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    val audioBuffer = mutableListOf<Short>()
    @Volatile var hasNewData = false

    fun start(): Boolean {
        if (isRunning.get()) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)

        audioRecord = try {
            Log.i("KeywordAlert", "Trying REMOTE_SUBMIX (source=7)...")
            AudioRecord.Builder()
                .setAudioSource(7)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL).build())
                .setBufferSizeInBytes(bufSize)
                .build()
        } catch (e: Exception) {
            Log.w("KeywordAlert", "REMOTE_SUBMIX failed, trying DEFAULT", e)
            try {
                AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(ENCODING).setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL).build())
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } catch (e2: Exception) {
                Log.e("KeywordAlert", "All AudioRecord failed", e2); null
            }
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("KeywordAlert", "AudioRecord not init, state=${audioRecord?.state}")
            audioRecord?.release(); audioRecord = null; return false
        }

        try { audioRecord!!.startRecording() } catch (e: Exception) {
            Log.e("KeywordAlert", "startRecording failed", e)
            audioRecord?.release(); audioRecord = null; return false
        }

        isRunning.set(true)
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(SAMPLE_RATE / 10)
            while (isRunning.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    synchronized(audioBuffer) { for (i in 0 until read) audioBuffer.add(buf[i]) }
                    hasNewData = true
                }
            }
        }.apply { name = "AudioCapture"; start() }
        return true
    }

    fun stop() {
        isRunning.set(false); thread?.interrupt(); thread = null
        audioRecord?.apply { stop(); release() }; audioRecord = null
        synchronized(audioBuffer) { audioBuffer.clear() }
    }

    fun isActive() = isRunning.get()
}

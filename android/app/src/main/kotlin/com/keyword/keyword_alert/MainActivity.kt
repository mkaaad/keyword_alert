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
    companion object {
        private const val TAG = "KeywordAlert"
    }

    private var audioCapture: AudioCapture? = null
    private var asrStreamHandler: AsrStreamHandler? = null
    /** Kept across start/stop so EventChannel re-use still delivers results. */
    private var eventSink: EventChannel.EventSink? = null

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
                            Log.e(TAG, "startCapture failed", e)
                            runOnUiThread { result.success(false) }
                        }
                    }.start()
                }
                "stopCapture" -> {
                    stopCapture()
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
                eventSink = events
                asrStreamHandler?.setSink(events)
            }

            override fun onCancel(arguments: Any?) {
                // Do not stop capture here — Flutter may cancel when no active listeners.
                // Only detach the sink so we don't send events to a disposed channel.
                asrStreamHandler?.setSink(null)
                eventSink = null
            }
        })
    }

    private fun startCaptureWithRoot(): Boolean {
        // Stop previous session cleanly before starting a new one
        stopCapture()

        Log.i(TAG, "Requesting root...")
        if (!execSu("id")) {
            Log.e(TAG, "Root denied")
            return false
        }
        Log.i(TAG, "Root granted")
        val pkg = packageName
        execSu("pm grant $pkg android.permission.RECORD_AUDIO")
        execSu("pm grant $pkg android.permission.CAPTURE_AUDIO_OUTPUT")
        execSu("pm grant $pkg android.permission.POST_NOTIFICATIONS")

        val capture = AudioCapture()
        val ok = capture.start()
        if (!ok) {
            Log.e(TAG, "AudioCapture failed")
            return false
        }
        audioCapture = capture
        val handler = AsrStreamHandler(capture, this@MainActivity)
        asrStreamHandler = handler
        // Re-attach existing EventChannel sink (important for restart)
        handler.setSink(eventSink)
        // If Dart already has a subscription, start ASR immediately
        if (eventSink != null) {
            handler.start()
        }
        Log.i(TAG, "AudioCapture started")
        return true
    }

    private fun stopCapture() {
        asrStreamHandler?.stop()
        asrStreamHandler = null
        audioCapture?.stop()
        audioCapture = null
    }

    private fun execSu(command: String): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0
        } catch (e: Exception) {
            Log.w(TAG, "su failed: $command", e)
            false
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}

class AudioCapture {
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val TAG = "KeywordAlert"
    }

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    val audioBuffer = mutableListOf<Short>()
    @Volatile var hasNewData = false

    fun start(): Boolean {
        if (isRunning.get()) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid min buffer size: $minBuf")
            return false
        }
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)

        audioRecord = try {
            Log.i(TAG, "Trying REMOTE_SUBMIX (source=7)...")
            AudioRecord.Builder()
                .setAudioSource(7) // MediaRecorder.AudioSource.REMOTE_SUBMIX
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "REMOTE_SUBMIX failed, trying UNPROCESSED/DEFAULT", e)
            tryCreateFallbackRecord(bufSize)
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not init, state=${audioRecord?.state}")
            audioRecord?.release()
            audioRecord = null
            return false
        }

        try {
            audioRecord!!.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }

        isRunning.set(true)
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(SAMPLE_RATE / 10)
            while (isRunning.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    synchronized(audioBuffer) {
                        for (i in 0 until read) audioBuffer.add(buf[i])
                    }
                    hasNewData = true
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error: $read")
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply {
            name = "AudioCapture"
            start()
        }
        return true
    }

    private fun tryCreateFallbackRecord(bufSize: Int): AudioRecord? {
        val sources = listOf(
            android.media.MediaRecorder.AudioSource.UNPROCESSED,
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            android.media.MediaRecorder.AudioSource.DEFAULT,
            android.media.MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Fallback AudioRecord source=$source OK")
                    return record
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Fallback source=$source failed", e)
            }
        }
        return null
    }

    fun stop() {
        isRunning.set(false)
        thread?.interrupt()
        try {
            thread?.join(1000)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        synchronized(audioBuffer) { audioBuffer.clear() }
    }

    fun isActive() = isRunning.get()
}

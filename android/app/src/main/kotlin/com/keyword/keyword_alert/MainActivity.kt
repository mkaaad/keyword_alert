package com.keyword.keyword_alert

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "KeywordAlert"
        private const val REQ_MEDIA_PROJECTION = 1001
    }

    private var audioCapture: AudioCapture? = null
    private var asrStreamHandler: AsrStreamHandler? = null
    private var eventSink: EventChannel.EventSink? = null

    private var pendingStartResult: MethodChannel.Result? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionManager: MediaProjectionManager? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/audio_capture"
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCapture" -> {
                    // MediaProjection permission UI must run on main thread.
                    if (pendingStartResult != null) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    pendingStartResult = result
                    requestCapturePermissionOrStart()
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
                asrStreamHandler?.setSink(null)
                eventSink = null
            }
        })
    }

    private fun requestCapturePermissionOrStart() {
        // Prefer AudioPlaybackCapture (captures Bilibili / Meeting speaker audio).
        // Requires one-time MediaProjection consent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projectionManager != null) {
            try {
                val intent = projectionManager!!.createScreenCaptureIntent()
                startActivityForResult(intent, REQ_MEDIA_PROJECTION)
                return
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjection intent failed, fallback to REMOTE_SUBMIX", e)
            }
        }
        Thread {
            val ok = startCaptureInternal(projection = null)
            finishStart(ok)
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MEDIA_PROJECTION) return

        if (resultCode != Activity.RESULT_OK || data == null || projectionManager == null) {
            Log.e(TAG, "MediaProjection denied by user")
            // Still try REMOTE_SUBMIX / mic fallback
            Thread {
                val ok = startCaptureInternal(projection = null)
                finishStart(ok)
            }.start()
            return
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaProjection = projectionManager!!.getMediaProjection(resultCode, data)
            // Some OEMs keep the projection session only while a VirtualDisplay exists.
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "keyword_alert_cap",
                2,
                2,
                1,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null as Surface?,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            mediaProjection = null
        }
        Thread {
            val ok = startCaptureInternal(projection = mediaProjection)
            finishStart(ok)
        }.start()
    }

    private fun finishStart(ok: Boolean) {
        runOnUiThread {
            pendingStartResult?.success(ok)
            pendingStartResult = null
        }
    }

    private fun startCaptureInternal(projection: MediaProjection?): Boolean {
        stopCaptureKeepProjection()

        Log.i(TAG, "Requesting root (optional for grants)...")
        if (execSu("id")) {
            Log.i(TAG, "Root granted")
            val pkg = packageName
            execSu("pm grant $pkg android.permission.RECORD_AUDIO")
            execSu("pm grant $pkg android.permission.CAPTURE_AUDIO_OUTPUT")
            execSu("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        } else {
            Log.w(TAG, "Root denied — MediaProjection path may still work")
        }

        val capture = AudioCapture()
        val ok = capture.start(projection)
        if (!ok) {
            Log.e(TAG, "AudioCapture failed")
            return false
        }
        audioCapture = capture
        val handler = AsrStreamHandler(capture, this@MainActivity)
        asrStreamHandler = handler
        handler.setSink(eventSink)
        if (eventSink != null) {
            handler.start()
        } else {
            // ASR thread starts when EventChannel listens; also start optimistically
            handler.start()
        }
        Log.i(TAG, "AudioCapture started (projection=${projection != null})")
        return true
    }

    private fun stopCaptureKeepProjection() {
        asrStreamHandler?.stop()
        asrStreamHandler = null
        audioCapture?.stop()
        audioCapture = null
    }

    private fun stopCapture() {
        stopCaptureKeepProjection()
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
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
        // MediaRecorder.AudioSource.REMOTE_SUBMIX == 8 (NOT 7 = VOICE_COMMUNICATION)
        private const val SOURCE_REMOTE_SUBMIX = MediaRecorder.AudioSource.REMOTE_SUBMIX
    }

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    val audioBuffer = mutableListOf<Short>()
    @Volatile var hasNewData = false
    @Volatile var lastRms: Double = 0.0
    @Volatile var totalFrames: Long = 0

    fun start(projection: MediaProjection? = null): Boolean {
        if (isRunning.get()) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid min buffer size: $minBuf")
            return false
        }
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)

        audioRecord = createPlaybackCaptureRecord(projection, bufSize)
            ?: createRemoteSubmixRecord(bufSize)
            ?: tryCreateFallbackRecord(bufSize)

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
        totalFrames = 0
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(SAMPLE_RATE / 10)
            var logCounter = 0
            while (isRunning.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    var sum = 0.0
                    synchronized(audioBuffer) {
                        for (i in 0 until read) {
                            val s = buf[i]
                            audioBuffer.add(s)
                            sum += s * s
                        }
                    }
                    lastRms = kotlin.math.sqrt(sum / read)
                    totalFrames += read
                    hasNewData = true
                    if (++logCounter % 50 == 0) {
                        Log.i(TAG, "Audio RMS=$lastRms frames=$totalFrames buf=${audioBuffer.size}")
                    }
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

    /** Capture other apps' playback (Bilibili, Meeting, etc.). Needs MediaProjection. */
    private fun createPlaybackCaptureRecord(
        projection: MediaProjection?,
        bufSize: Int,
    ): AudioRecord? {
        if (projection == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            Log.i(TAG, "Trying AudioPlaybackCapture (MediaProjection)...")
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioPlaybackCapture OK")
                record
            } else {
                Log.w(TAG, "AudioPlaybackCapture not initialized, state=${record.state}")
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioPlaybackCapture failed", e)
            null
        }
    }

    /** System remote submix — only audio routed to remote_submix (cast), needs CAPTURE_AUDIO_OUTPUT. */
    private fun createRemoteSubmixRecord(bufSize: Int): AudioRecord? {
        return try {
            Log.i(TAG, "Trying REMOTE_SUBMIX (source=$SOURCE_REMOTE_SUBMIX)...")
            val record = AudioRecord.Builder()
                .setAudioSource(SOURCE_REMOTE_SUBMIX)
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
                Log.i(TAG, "REMOTE_SUBMIX OK")
                record
            } else {
                Log.w(TAG, "REMOTE_SUBMIX not initialized, state=${record.state}")
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "REMOTE_SUBMIX failed", e)
            null
        }
    }

    private fun tryCreateFallbackRecord(bufSize: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC,
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
                    Log.i(TAG, "Fallback AudioRecord source=$source OK (mic path — not system audio)")
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

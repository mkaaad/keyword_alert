package com.keyword.keyword_alert

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        private const val REQ_MEDIA_PROJECTION = 1001
        private const val PROJECTION_WAIT_MS = 2500L
    }

    private var audioCapture: AudioCapture? = null
    private var asrStreamHandler: AsrStreamHandler? = null
    private var eventSink: EventChannel.EventSink? = null

    private var pendingStartResult: MethodChannel.Result? = null
    private var projectionManager: MediaProjectionManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handledProjection = AtomicBoolean(false)

    private val projectionReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PROJECTION_READY) return
            val ok = intent.getBooleanExtra(EXTRA_OK, false)
            AppLog.i("PROJECTION_READY ok=$ok")
            mainHandler.removeCallbacks(projectionTimeout)
            continueAfterProjection(ok)
        }
    }

    private val projectionTimeout = Runnable {
        AppLog.w("Projection ready timeout ready=${CaptureForegroundService.ready}")
        continueAfterProjection(CaptureForegroundService.ready)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                projectionReadyReceiver,
                IntentFilter(ACTION_PROJECTION_READY),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(projectionReadyReceiver, IntentFilter(ACTION_PROJECTION_READY))
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/audio_capture"
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "startCapture" -> {
                    if (pendingStartResult != null) {
                        result.success(mapOf("ok" to false, "mode" to "none", "error" to "busy"))
                        return@setMethodCallHandler
                    }
                    pendingStartResult = result
                    handledProjection.set(false)
                    requestCapturePermissionOrStart()
                }
                "stopCapture" -> {
                    stopCapture()
                    result.success(true)
                }
                "isActive" -> result.success(audioCapture?.isActive() ?: false)
                "captureMode" -> result.success(audioCapture?.captureMode ?: "none")
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

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/debug_log"
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                AppLog.setSink(events)
                AppLog.i("debug_log channel attached")
            }

            override fun onCancel(arguments: Any?) {
                AppLog.setSink(null)
            }
        })
    }

    private fun requestCapturePermissionOrStart() {
        // Never start mediaProjection FGS before consent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projectionManager != null) {
            try {
                startActivityForResult(
                    projectionManager!!.createScreenCaptureIntent(),
                    REQ_MEDIA_PROJECTION,
                )
                return
            } catch (e: Exception) {
                AppLog.w("MediaProjection intent failed", e)
            }
        }
        // No projection available (API < 29): do not pretend mic is OK for "system audio"
        finishStart(ok = false, mode = "none", error = "no_media_projection")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MEDIA_PROJECTION) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            AppLog.e("MediaProjection denied by user")
            finishStart(ok = false, mode = "none", error = "projection_denied")
            return
        }

        // User accepted → start FGS + getMediaProjection inside service (API 34 order).
        CaptureForegroundService.startWithProjection(this, resultCode, data)
        mainHandler.postDelayed(projectionTimeout, PROJECTION_WAIT_MS)
    }

    private fun continueAfterProjection(projectionOk: Boolean) {
        if (!handledProjection.compareAndSet(false, true)) {
            AppLog.i("Projection result already handled")
            return
        }
        mainHandler.removeCallbacks(projectionTimeout)

        val projection =
            if (projectionOk) CaptureForegroundService.currentProjection else null
        if (projection == null) {
            AppLog.e("No MediaProjection — refusing mic fallback")
            finishStart(ok = false, mode = "none", error = "projection_failed")
            return
        }

        Thread {
            val capture = AudioCapture()
            // Only system playback path; no silent mic fallback.
            val ok = capture.startPlaybackOnly(projection)
            if (!ok) {
                AppLog.e("AudioPlaybackCapture failed")
                runOnUiThread {
                    finishStart(ok = false, mode = "none", error = "playback_capture_failed")
                }
                return@Thread
            }
            runOnUiThread {
                stopCaptureKeepService()
                audioCapture = capture
                val handler = AsrStreamHandler(capture, this@MainActivity)
                asrStreamHandler = handler
                handler.setSink(eventSink)
                handler.start()
                AppLog.i("AudioCapture started mode=${capture.captureMode}")
                finishStart(ok = true, mode = capture.captureMode, error = null)
            }
        }.start()
    }

    private fun finishStart(ok: Boolean, mode: String, error: String?) {
        val map = hashMapOf<String, Any?>(
            "ok" to ok,
            "mode" to mode,
            "error" to error,
        )
        pendingStartResult?.success(map)
        pendingStartResult = null
    }

    private fun stopCaptureKeepService() {
        asrStreamHandler?.stop()
        asrStreamHandler = null
        audioCapture?.stop()
        audioCapture = null
    }

    private fun stopCapture() {
        handledProjection.set(false)
        mainHandler.removeCallbacks(projectionTimeout)
        stopCaptureKeepService()
        CaptureForegroundService.clearProjection()
        CaptureForegroundService.stop(this)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(projectionReadyReceiver)
        } catch (_: Exception) {
        }
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
    @Volatile var lastRms: Double = 0.0
    @Volatile var totalFrames: Long = 0
    @Volatile var captureMode: String = "none"

    /**
     * System-app playback only (Bilibili / Meeting). Never falls back to microphone.
     */
    fun startPlaybackOnly(projection: MediaProjection): Boolean {
        if (isRunning.get()) return captureMode == "playback"
        stop()

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            AppLog.e("Invalid min buffer size: $minBuf")
            return false
        }
        val bufSize = maxOf(minBuf * 2, SAMPLE_RATE * 2)

        // Prefer 16 kHz mono; if OEM rejects, try 48 kHz and we'll still feed ASR at 16k
        // (AudioPlaybackCapture resamples when possible).
        audioRecord = createPlaybackCaptureRecord(projection, bufSize, SAMPLE_RATE)
            ?: createPlaybackCaptureRecord(projection, bufSize, 48000)
        if (audioRecord == null) {
            captureMode = "none"
            AppLog.e("AudioPlaybackCapture unavailable")
            return false
        }
        captureMode = "playback"
        AppLog.i("Capture mode: playback (system audio)")

        try {
            audioRecord!!.startRecording()
        } catch (e: Exception) {
            AppLog.e("startRecording failed", e)
            audioRecord?.release()
            audioRecord = null
            captureMode = "none"
            return false
        }

        val recordSampleRate = audioRecord!!.sampleRate
        isRunning.set(true)
        totalFrames = 0
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(recordSampleRate / 10)
            var logCounter = 0
            while (isRunning.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    var sum = 0.0
                    // Downsample 48k → 16k if needed (keep every 3rd sample)
                    val step = if (recordSampleRate >= 48000) 3 else 1
                    synchronized(audioBuffer) {
                        var i = 0
                        while (i < read) {
                            val s = buf[i]
                            audioBuffer.add(s)
                            sum += s * s
                            i += step
                        }
                    }
                    val n = (read + step - 1) / step
                    lastRms = if (n > 0) kotlin.math.sqrt(sum / n) else 0.0
                    totalFrames += n
                    hasNewData = true
                    if (++logCounter % 50 == 0) {
                        AppLog.i(
                            "Audio mode=$captureMode rate=$recordSampleRate RMS=$lastRms frames=$totalFrames buf=${audioBuffer.size}",
                        )
                    }
                } else if (read < 0) {
                    AppLog.w("AudioRecord.read error: $read")
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

    private fun createPlaybackCaptureRecord(
        projection: MediaProjection,
        bufSize: Int,
        sampleRate: Int,
    ): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            AppLog.i("Trying AudioPlaybackCapture sampleRate=$sampleRate...")
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                AppLog.i("AudioPlaybackCapture OK rate=$sampleRate")
                record
            } else {
                AppLog.w("AudioPlaybackCapture not initialized state=${record.state} rate=$sampleRate")
                record.release()
                null
            }
        } catch (e: Exception) {
            AppLog.w("AudioPlaybackCapture failed rate=$sampleRate", e)
            null
        }
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
        captureMode = "none"
    }

    fun isActive() = isRunning.get()
}

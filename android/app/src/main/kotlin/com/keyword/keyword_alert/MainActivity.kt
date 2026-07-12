package com.keyword.keyword_alert

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        // Service settles VirtualDisplay + AudioRecord retries; allow extra time on slow OEMs.
        private const val PROJECTION_WAIT_MS = 5000L
    }

    private var audioCapture: AudioCapture? = null
    private var asrStreamHandler: AsrStreamHandler? = null
    private var eventSink: EventChannel.EventSink? = null

    private var pendingStartResult: MethodChannel.Result? = null
    private var projectionManager: MediaProjectionManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handledProjection = AtomicBoolean(false)
    /** From Flutter startCapture args — full-screen OCR when true. */
    private var pendingOcrEnabled: Boolean = false

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
                    val args = call.arguments as? Map<*, *>
                    pendingOcrEnabled = args?.get("ocrEnabled") == true
                    AppLog.i("startCapture ocrEnabled=$pendingOcrEnabled")
                    requestCapturePermissionOrStart()
                }
                "stopCapture" -> {
                    stopCapture()
                    result.success(true)
                }
                "isActive" -> {
                    val audio = CaptureForegroundService.audioCapture?.isActive() == true
                    val ocr = CaptureForegroundService.screenOcr?.isActive == true
                    result.success(audio || ocr)
                }
                "captureMode" -> result.success(CaptureForegroundService.captureMode)
                else -> result.notImplemented()
            }
        }

        // System default alarm ringtone (TYPE_ALARM / USAGE_ALARM).
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/alarm",
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "playSystemAlarm" -> {
                    val ok = AlarmPlayer.play(this)
                    result.success(ok)
                }
                "stopSystemAlarm" -> {
                    AlarmPlayer.stop()
                    result.success(true)
                }
                "isSystemAlarmPlaying" -> result.success(AlarmPlayer.isPlaying())
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
                CaptureForegroundService.onOcrText = { text ->
                    mainHandler.post {
                        try {
                            eventSink?.success("[OCR] $text")
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                asrStreamHandler?.setSink(null)
                CaptureForegroundService.onOcrText = null
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
        // RECORD_AUDIO is still required for AudioPlaybackCapture on many devices.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w("Requesting RECORD_AUDIO permission")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
            // Will continue after onRequestPermissionsResult — store pending already set
            return
        }
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


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1002) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            AppLog.i("RECORD_AUDIO granted, opening MediaProjection dialog")
            requestCapturePermissionOrStart()
        } else {
            AppLog.e("RECORD_AUDIO denied")
            finishStart(ok = false, mode = "none", error = "record_audio_denied")
        }
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
        CaptureForegroundService.startWithProjection(
            this,
            resultCode,
            data,
            enableOcr = pendingOcrEnabled,
        )
        mainHandler.postDelayed(projectionTimeout, PROJECTION_WAIT_MS)
    }

    private fun continueAfterProjection(projectionOk: Boolean) {
        if (!handledProjection.compareAndSet(false, true)) {
            AppLog.i("Projection result already handled")
            return
        }
        mainHandler.removeCallbacks(projectionTimeout)

        val projection = CaptureForegroundService.currentProjection
        val capture = CaptureForegroundService.audioCapture
        val ocr = CaptureForegroundService.screenOcr
        val mode = CaptureForegroundService.captureMode
        val audioOk = capture != null && capture.isActive()
        val ocrOk = ocr != null && ocr.isActive

        if (!projectionOk || projection == null || (!audioOk && !ocrOk)) {
            AppLog.e(
                "Capture not ready ok=$projectionOk projection=${projection != null} " +
                    "audio=$audioOk ocr=$ocrOk mode=$mode",
            )
            finishStart(ok = false, mode = "none", error = "capture_failed")
            return
        }

        // Wire OCR → Flutter (also set in EventChannel onListen)
        CaptureForegroundService.onOcrText = { text ->
            mainHandler.post {
                try {
                    eventSink?.success("[OCR] $text")
                } catch (_: Exception) {
                }
            }
        }

        // ASR only when system audio path is running.
        asrStreamHandler?.stop()
        asrStreamHandler = null
        audioCapture = capture
        if (audioOk && capture != null) {
            val handler = AsrStreamHandler(capture, this@MainActivity)
            asrStreamHandler = handler
            handler.setSink(eventSink)
            handler.start()
            AppLog.i("ASR attached mode=${capture.captureMode}")
        } else {
            AppLog.i("ASR skipped — OCR-only mode")
        }

        AppLog.i("Capture ready mode=$mode audio=$audioOk ocr=$ocrOk")
        finishStart(ok = true, mode = mode, error = null)
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

    private fun stopCapture() {
        handledProjection.set(false)
        mainHandler.removeCallbacks(projectionTimeout)
        asrStreamHandler?.stop()
        asrStreamHandler = null
        audioCapture = null
        CaptureForegroundService.onOcrText = null
        CaptureForegroundService.clearProjection()
        CaptureForegroundService.stop(this)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(projectionReadyReceiver)
        } catch (_: Exception) {
        }
        AlarmPlayer.stop()
        stopCapture()
        super.onDestroy()
    }
}

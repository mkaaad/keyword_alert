package com.keyword.keyword_alert

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : FlutterActivity() {
    companion object {
        private const val REQ_MEDIA_PROJECTION = 1001
        private const val PROJECTION_WAIT_MS = 6000L
    }

    private var audioCapture: AudioCapture? = null
    private var asrStreamHandler: AsrStreamHandler? = null
    private var asrSink: EventChannel.EventSink? = null
    private var ocrSink: EventChannel.EventSink? = null

    private var pendingStartResult: MethodChannel.Result? = null
    private var projectionManager: MediaProjectionManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handledProjection = AtomicBoolean(false)
    private var pendingOcrEnabled: Boolean = false
    private var pendingAudioEnabled: Boolean = true

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
            "com.keyword/audio_capture",
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
                    // Default audio on unless explicitly false
                    pendingAudioEnabled = args?.get("audioEnabled") != false
                    if (!pendingOcrEnabled && !pendingAudioEnabled) {
                        finishStart(ok = false, mode = "none", error = "nothing_enabled")
                        return@setMethodCallHandler
                    }
                    AppLog.i(
                        "startCapture ocr=$pendingOcrEnabled audio=$pendingAudioEnabled",
                    )
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

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/alarm",
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "playSystemAlarm" -> result.success(AlarmPlayer.play(this))
                "stopSystemAlarm" -> {
                    AlarmPlayer.stop()
                    result.success(true)
                }
                "isSystemAlarmPlaying" -> result.success(AlarmPlayer.isPlaying())
                else -> result.notImplemented()
            }
        }

        // Audio ASR only
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/asr_stream",
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                asrSink = events
                asrStreamHandler?.setSink(events)
                AppLog.i("asr_stream attached")
            }

            override fun onCancel(arguments: Any?) {
                asrStreamHandler?.setSink(null)
                asrSink = null
            }
        })

        // Screen OCR only (independent of ASR)
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/ocr_stream",
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                ocrSink = events
                wireOcrCallback()
                AppLog.i("ocr_stream attached")
            }

            override fun onCancel(arguments: Any?) {
                ocrSink = null
            }
        })

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.keyword/debug_log",
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

    private fun wireOcrCallback() {
        CaptureForegroundService.onOcrText = { text ->
            mainHandler.post {
                try {
                    val sink = ocrSink
                    if (sink == null) {
                        AppLog.w("OCR sink null, drop: ${text.take(40)}")
                    } else {
                        sink.success(text)
                    }
                } catch (e: Exception) {
                    AppLog.w("OCR sink emit failed: $e")
                }
            }
        }
    }

    private fun requestCapturePermissionOrStart() {
        // RECORD_AUDIO only when audio path is requested.
        if (pendingAudioEnabled &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w("Requesting RECORD_AUDIO permission (audio path)")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
            return
        }
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
            AppLog.i("RECORD_AUDIO granted")
            requestCapturePermissionOrStart()
        } else if (pendingOcrEnabled) {
            // Audio denied but OCR still possible
            AppLog.w("RECORD_AUDIO denied — continue with OCR only")
            pendingAudioEnabled = false
            requestCapturePermissionOrStart()
        } else {
            AppLog.e("RECORD_AUDIO denied and OCR disabled")
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

        CaptureForegroundService.startWithProjection(
            this,
            resultCode,
            data,
            enableOcr = pendingOcrEnabled,
            enableAudio = pendingAudioEnabled,
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

        wireOcrCallback()

        asrStreamHandler?.stop()
        asrStreamHandler = null
        audioCapture = capture
        if (audioOk && capture != null) {
            val handler = AsrStreamHandler(capture, this@MainActivity)
            asrStreamHandler = handler
            handler.setSink(asrSink)
            handler.start()
            AppLog.i("ASR attached mode=${capture.captureMode}")
        } else {
            AppLog.i("ASR not started (audio path off or failed)")
        }

        AppLog.i("Capture ready mode=$mode audio=$audioOk ocr=$ocrOk")
        finishStart(ok = true, mode = mode, error = null)
    }

    private fun finishStart(ok: Boolean, mode: String, error: String?) {
        val map = hashMapOf<String, Any?>(
            "ok" to ok,
            "mode" to mode,
            "error" to error,
            "ocr" to (mode == "ocr" || mode.startsWith("ocr")),
            "audio" to (mode.contains("playback") || mode.contains("remote_submix")),
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

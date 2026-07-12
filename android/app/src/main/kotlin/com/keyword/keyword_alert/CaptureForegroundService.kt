package com.keyword.keyword_alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * After the user accepts screen capture, this service:
 * 1) startForeground(MEDIA_PROJECTION)
 * 2) getMediaProjection + full-screen VirtualDisplay
 * 3) Screen OCR (whole screen) + optional system audio capture
 *
 * Never start this service before the consent dialog — that crashes on API 34/ColorOS.
 */
class CaptureForegroundService : Service() {
    companion object {
        private const val TAG = "KeywordAlert"
        const val CHANNEL_ID = "keyword_alert_capture"
        const val NOTIF_ID = 10042
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_OCR_ENABLED = "ocr_enabled"
        const val EXTRA_AUDIO_ENABLED = "audio_enabled"

        @Volatile
        var currentProjection: MediaProjection? = null
            private set

        @Volatile
        var ready: Boolean = false
            private set

        /** Modes: ocr | playback | remote_submix | ocr+playback | … */
        @Volatile
        var captureMode: String = "none"
            private set

        @Volatile
        var ocrEnabled: Boolean = false
            private set

        @Volatile
        var audioEnabled: Boolean = true
            private set

        @Volatile
        var audioCapture: AudioCapture? = null
            private set

        @Volatile
        var screenOcr: ScreenOcr? = null
            private set

        /** Flutter listens for OCR lines on a dedicated channel. */
        @Volatile
        var onOcrText: ((String) -> Unit)? = null

        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var projectionCallback: MediaProjection.Callback? = null

        fun startWithProjection(
            context: Context,
            resultCode: Int,
            data: Intent,
            enableOcr: Boolean = false,
            enableAudio: Boolean = true,
        ) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_OCR_ENABLED, enableOcr)
                putExtra(EXTRA_AUDIO_ENABLED, enableAudio)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureForegroundService::class.java))
        }

        fun clearProjection() {
            try {
                screenOcr?.stop()
            } catch (_: Exception) {
            }
            screenOcr = null
            try {
                audioCapture?.stop()
            } catch (_: Exception) {
            }
            audioCapture = null
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            virtualDisplay = null
            try {
                imageReader?.close()
            } catch (_: Exception) {
            }
            imageReader = null
            val proj = currentProjection
            val cb = projectionCallback
            if (proj != null && cb != null) {
                try {
                    proj.unregisterCallback(cb)
                } catch (_: Exception) {
                }
            }
            projectionCallback = null
            try {
                proj?.stop()
            } catch (_: Exception) {
            }
            currentProjection = null
            captureMode = "none"
            ready = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasProjectionExtras = intent?.hasExtra(EXTRA_RESULT_CODE) == true &&
            intent.hasExtra(EXTRA_RESULT_DATA)

        promoteToForeground(withMediaProjectionType = hasProjectionExtras)

        if (!hasProjectionExtras || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return START_STICKY
        }

        val resultCode = intent!!.getIntExtra(EXTRA_RESULT_CODE, 0)
        val enableOcr = intent.getBooleanExtra(EXTRA_OCR_ENABLED, false)
        val enableAudio = intent.getBooleanExtra(EXTRA_AUDIO_ENABLED, true)
        ocrEnabled = enableOcr
        audioEnabled = enableAudio
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || data == null) {
            AppLog.e("Missing projection result extras")
            ready = false
            broadcastReady(false)
            return START_STICKY
        }

        if (!enableOcr && !enableAudio) {
            AppLog.e("Both OCR and audio disabled")
            ready = false
            broadcastReady(false)
            return START_STICKY
        }

        val mainLooper = Handler(Looper.getMainLooper())
        mainLooper.post {
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                clearProjection()
                ocrEnabled = enableOcr
                audioEnabled = enableAudio
                val projection = mgr.getMediaProjection(resultCode, data)
                    ?: throw IllegalStateException("getMediaProjection returned null")

                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        AppLog.w("MediaProjection.onStop — user revoked or session ended")
                        clearProjection()
                        broadcastReady(false)
                    }
                }
                projection.registerCallback(cb, mainLooper)
                projectionCallback = cb
                AppLog.i(
                    "MediaProjection.registerCallback OK ocr=$enableOcr audio=$enableAudio",
                )

                // OCR needs full-screen frames; audio-only uses tiny VD to keep session.
                val (width, height, density) = if (enableOcr) {
                    screenSize()
                } else {
                    Triple(2, 2, resources.displayMetrics.densityDpi.coerceAtLeast(160))
                }
                AppLog.i("VirtualDisplay ${width}x${height} dpi=$density ocr=$enableOcr")

                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                imageReader = reader
                virtualDisplay = try {
                    // Prefer PUBLIC|AUTO_MIRROR — better frame delivery on some OEMs.
                    projection.createVirtualDisplay(
                        "keyword_alert_cap",
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                        reader.surface,
                        null,
                        mainLooper,
                    )
                } catch (e: Exception) {
                    AppLog.w("VD PUBLIC failed, fallback AUTO_MIRROR: $e")
                    projection.createVirtualDisplay(
                        "keyword_alert_cap",
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface,
                        null,
                        mainLooper,
                    )
                }
                currentProjection = projection
                AppLog.i("VirtualDisplay OK — starting independent paths")

                // Give the mirror a moment to paint before starting OCR.
                mainLooper.postDelayed({
                    if (currentProjection !== projection) {
                        AppLog.w("Projection changed before capture start — abort")
                        return@postDelayed
                    }

                    var ocrOk = false
                    if (enableOcr) {
                        val ocr = ScreenOcr { text ->
                            val cbText = onOcrText
                            if (cbText == null) {
                                AppLog.w("OCR text dropped — no Flutter listener yet: ${text.take(40)}")
                            } else {
                                cbText.invoke(text)
                            }
                        }
                        ocrOk = ocr.start(reader)
                        if (ocrOk) {
                            screenOcr = ocr
                            AppLog.i("ScreenOcr path active (independent of audio)")
                        } else {
                            AppLog.e("ScreenOcr failed to start")
                        }
                    } else {
                        AppLog.i("ScreenOcr skipped (user disabled)")
                    }

                    var audioMode = "none"
                    var audioOk = false
                    if (enableAudio) {
                        val capture = AudioCapture()
                        audioOk = capture.startPlaybackOnly(this@CaptureForegroundService, projection)
                        if (audioOk) {
                            audioCapture = capture
                            audioMode = capture.captureMode
                            AppLog.i("Audio path active mode=$audioMode")
                        } else {
                            AppLog.w("Audio capture unavailable")
                        }
                    } else {
                        AppLog.i("Audio path skipped (user disabled)")
                    }

                    if (!ocrOk && !audioOk) {
                        AppLog.e("No capture path (ocr=$enableOcr/$ocrOk audio=$enableAudio/$audioOk)")
                        clearProjection()
                        ready = false
                        broadcastReady(false)
                        return@postDelayed
                    }

                    captureMode = when {
                        ocrOk && audioOk -> "ocr+$audioMode"
                        ocrOk -> "ocr"
                        else -> audioMode
                    }
                    ready = true
                    AppLog.i("CaptureForegroundService ready mode=$captureMode")
                    broadcastReady(true)
                }, 400)
            } catch (e: Exception) {
                AppLog.e("getMediaProjection in service failed", e)
                clearProjection()
                ready = false
                broadcastReady(false)
            }
        }

        return START_STICKY
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val metrics = DisplayMetrics()
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                val density = resources.displayMetrics.densityDpi.coerceAtLeast(160)
                // Cap extreme resolutions to limit ImageReader memory (~ARGB).
                val maxEdge = 1920
                var w = bounds.width().coerceAtLeast(2)
                var h = bounds.height().coerceAtLeast(2)
                val edge = maxOf(w, h)
                if (edge > maxEdge) {
                    val s = maxEdge.toFloat() / edge
                    w = (w * s).toInt().coerceAtLeast(2)
                    h = (h * s).toInt().coerceAtLeast(2)
                }
                return Triple(w, h, density)
            }
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        } catch (e: Exception) {
            AppLog.w("screenSize fallback: $e")
            metrics.setTo(resources.displayMetrics)
        }
        val density = metrics.densityDpi.coerceAtLeast(160)
        var w = metrics.widthPixels.coerceAtLeast(2)
        var h = metrics.heightPixels.coerceAtLeast(2)
        val maxEdge = 1920
        val edge = maxOf(w, h)
        if (edge > maxEdge) {
            val s = maxEdge.toFloat() / edge
            w = (w * s).toInt().coerceAtLeast(2)
            h = (h * s).toInt().coerceAtLeast(2)
        }
        return Triple(w, h, density)
    }

    private fun broadcastReady(ok: Boolean) {
        sendBroadcast(
            Intent(ACTION_PROJECTION_READY).setPackage(packageName).apply {
                putExtra(EXTRA_OK, ok)
                putExtra("mode", captureMode)
            },
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "关键词监控采集",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "全屏录屏 OCR + 系统音频捕获"
                setShowBadge(false)
            },
        )
    }

    private fun promoteToForeground(withMediaProjectionType: Boolean) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(this, 0, launch, piFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle("关键词监控")
            .setContentText(
                if (withMediaProjectionType) "全屏 OCR + 内录运行中…" else "监控服务运行中…",
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && withMediaProjectionType) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIF_ID, notification, type)
                AppLog.i("startForeground mediaProjection|microphone OK")
                return
            } catch (e: Exception) {
                AppLog.e("startForeground MEDIA_PROJECTION failed", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                return
            } catch (e: Exception) {
                AppLog.w("startForeground microphone failed", e)
            }
        }
        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        AppLog.i("CaptureForegroundService destroyed")
        clearProjection()
        super.onDestroy()
    }
}

const val ACTION_PROJECTION_READY = "com.keyword.keyword_alert.PROJECTION_READY"
const val EXTRA_OK = "ok"

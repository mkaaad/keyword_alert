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
import android.util.Log

/**
 * After the user accepts screen capture, this service:
 * 1) startForeground(MEDIA_PROJECTION)
 * 2) getMediaProjection + VirtualDisplay (keeps session alive)
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

        @Volatile
        var currentProjection: MediaProjection? = null
            private set

        @Volatile
        var ready: Boolean = false
            private set

        /** Shared playback capture started in this service (main thread). */
        @Volatile
        var audioCapture: AudioCapture? = null
            private set

        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        private var projectionCallback: MediaProjection.Callback? = null

        fun startWithProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                // Intent is Parcelable; copy to survive process/service start
                putExtra(EXTRA_RESULT_DATA, data)
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

        // Always enter foreground immediately (startForegroundService deadline).
        promoteToForeground(withMediaProjectionType = hasProjectionExtras)

        if (!hasProjectionExtras || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return START_STICKY
        }

        val resultCode = intent!!.getIntExtra(EXTRA_RESULT_CODE, 0)
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

        // getMediaProjection must run after startForeground(MEDIA_PROJECTION).
        // Post to main looper — some OEMs require main thread.
        val mainLooper = Handler(Looper.getMainLooper())
        mainLooper.post {
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                clearProjection()
                val projection = mgr.getMediaProjection(resultCode, data)
                    ?: throw IllegalStateException("getMediaProjection returned null")

                // API 34+: must registerCallback BEFORE createVirtualDisplay / audio capture,
                // else IllegalStateException: Must register a callback before starting capture
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        AppLog.w("MediaProjection.onStop — user revoked or session ended")
                        clearProjection()
                        broadcastReady(false)
                    }
                }
                projection.registerCallback(cb, mainLooper)
                projectionCallback = cb
                AppLog.i("MediaProjection.registerCallback OK")

                // Real Surface required on many devices (null surface → silent/broken capture).
                val reader = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2)
                imageReader = reader
                virtualDisplay = projection.createVirtualDisplay(
                    "keyword_alert_cap",
                    2,
                    2,
                    resources.displayMetrics.densityDpi.coerceAtLeast(160),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null,
                )
                currentProjection = projection
                AppLog.i("VirtualDisplay OK — starting AudioPlaybackCapture on main thread")

                // Build AudioRecord HERE on main thread with Service context.
                // Creating it later on a worker thread / without Context often fails with:
                // "Error: could not register audio policy" on Android 14/OEM.
                // Brief settle so AudioFlinger sees an active projection session.
                mainLooper.postDelayed({
                    if (currentProjection !== projection) {
                        AppLog.w("Projection changed before audio start — abort")
                        return@postDelayed
                    }
                    val capture = AudioCapture()
                    val audioOk = capture.startPlaybackOnly(this@CaptureForegroundService, projection)
                    if (!audioOk) {
                        AppLog.e("AudioPlaybackCapture failed inside service")
                        clearProjection()
                        ready = false
                        broadcastReady(false)
                        return@postDelayed
                    }
                    audioCapture = capture
                    ready = true
                    AppLog.i("CaptureForegroundService: projection+audio ready mode=${capture.captureMode}")
                    broadcastReady(true)
                }, 150)
            } catch (e: Exception) {
                AppLog.e("getMediaProjection in service failed", e)
                clearProjection()
                ready = false
                broadcastReady(false)
            }
        }

        return START_STICKY
    }

    private fun broadcastReady(ok: Boolean) {
        sendBroadcast(
            Intent(ACTION_PROJECTION_READY).setPackage(packageName).apply {
                putExtra(EXTRA_OK, ok)
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
                description = "系统音频捕获运行中"
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
            .setContentText(if (withMediaProjectionType) "系统内录运行中…" else "监控服务运行中…")
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
                // Fall through — may still try plain (getMediaProjection will fail without type)
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

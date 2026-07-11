package com.keyword.keyword_alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface

/**
 * Holds the active [MediaProjection] for the app process.
 * Must only be started **after** the user accepts the screen-capture dialog.
 *
 * Android 14+: call [startForeground] with MEDIA_PROJECTION type, then
 * [MediaProjectionManager.getMediaProjection] in that order inside this service.
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

        private var virtualDisplay: VirtualDisplay? = null

        fun startWithProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureForegroundService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
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
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            virtualDisplay = null
            try {
                currentProjection?.stop()
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
        // 1) Become FGS immediately (required within time limit of startForegroundService)
        promoteToForeground(withMediaProjectionType = intent?.hasExtra(EXTRA_RESULT_CODE) == true)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && data != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                clearProjection()
                // FGS already running with mediaProjection type → getMediaProjection allowed
                val projection = mgr.getMediaProjection(resultCode, data)
                currentProjection = projection
                virtualDisplay = projection?.createVirtualDisplay(
                    "keyword_alert_cap",
                    2,
                    2,
                    1,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null as Surface?,
                    null,
                    null,
                )
                ready = projection != null
                Log.i(TAG, "CaptureForegroundService: projection ready=$ready")
                // Notify activity if waiting
                sendBroadcast(Intent(ACTION_PROJECTION_READY).setPackage(packageName).apply {
                    putExtra(EXTRA_OK, ready)
                })
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection in service failed", e)
                ready = false
                sendBroadcast(Intent(ACTION_PROJECTION_READY).setPackage(packageName).apply {
                    putExtra(EXTRA_OK, false)
                })
            }
        }

        return START_STICKY
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
            .setContentText("正在捕获系统音频…")
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
                Log.i(TAG, "startForeground mediaProjection|microphone OK")
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground typed failed, plain fallback", e)
            }
        }

        // Before consent / fallback: microphone only (or plain) — never mediaProjection alone pre-consent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground microphone failed", e)
            }
        }
        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        Log.i(TAG, "CaptureForegroundService destroyed")
        clearProjection()
        super.onDestroy()
    }
}

const val ACTION_PROJECTION_READY = "com.keyword.keyword_alert.PROJECTION_READY"
const val EXTRA_OK = "ok"

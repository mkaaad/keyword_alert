package com.keyword.keyword_alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Android 14+ requires a running FGS with type mediaProjection before
 * MediaProjectionManager.getMediaProjection() is allowed.
 */
class CaptureForegroundService : Service() {
    companion object {
        private const val TAG = "KeywordAlert"
        const val CHANNEL_ID = "keyword_alert_capture"
        const val NOTIF_ID = 10042

        fun start(context: Context) {
            val intent = Intent(context, CaptureForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        promoteToForeground()
        Log.i(TAG, "CaptureForegroundService created (mediaProjection FGS)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "关键词监控采集",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "系统音频捕获运行中"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun promoteToForeground() {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIF_ID, notification, type)
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with types failed, plain fallback", e)
            }
        }
        startForeground(NOTIF_ID, notification)
    }

    override fun onDestroy() {
        Log.i(TAG, "CaptureForegroundService destroyed")
        super.onDestroy()
    }
}

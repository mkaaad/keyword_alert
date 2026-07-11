package com.keyword.keyword_alert

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build

/**
 * Plays the device default alarm ringtone (TYPE_ALARM) on the alarm stream.
 */
object AlarmPlayer {
    private var ringtone: Ringtone? = null

    @Synchronized
    fun play(context: Context): Boolean {
        stop()
        return try {
            val appCtx = context.applicationContext
            var uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            if (uri == null) {
                AppLog.e("No default alarm/notification ringtone URI")
                return false
            }
            val rt = RingtoneManager.getRingtone(appCtx, uri)
            if (rt == null) {
                AppLog.e("RingtoneManager.getRingtone returned null for $uri")
                return false
            }
            rt.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                rt.isLooping = true
            }
            rt.play()
            ringtone = rt
            AppLog.i("System alarm ringtone playing uri=$uri")
            true
        } catch (e: Exception) {
            AppLog.e("System alarm ringtone failed", e)
            ringtone = null
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
    }

    @Synchronized
    fun isPlaying(): Boolean = try {
        ringtone?.isPlaying == true
    } catch (_: Exception) {
        false
    }
}

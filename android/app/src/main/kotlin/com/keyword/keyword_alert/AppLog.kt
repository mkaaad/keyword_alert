package com.keyword.keyword_alert

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.EventChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dual log: Android logcat + push lines to Flutter UI via EventChannel.
 */
object AppLog {
    private const val TAG = "KeywordAlert"
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val ring = CopyOnWriteArrayList<String>()
    private const val MAX = 200

    @Volatile
    private var sink: EventChannel.EventSink? = null

    fun setSink(s: EventChannel.EventSink?) {
        sink = s
        // Replay recent lines so UI isn't empty after reconnect
        if (s != null) {
            val snap = ring.toList()
            main.post {
                for (line in snap) {
                    try {
                        s.success(line)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun i(msg: String) = emit("I", msg)
    fun w(msg: String, t: Throwable? = null) {
        if (t != null) {
            Log.w(TAG, msg, t)
            emit("W", "$msg | ${t.javaClass.simpleName}: ${t.message}")
        } else {
            emit("W", msg)
        }
    }
    fun e(msg: String, t: Throwable? = null) {
        if (t != null) {
            Log.e(TAG, msg, t)
            emit("E", "$msg | ${t.javaClass.simpleName}: ${t.message}")
        } else {
            Log.e(TAG, msg)
            emit("E", msg)
        }
    }

    fun d(msg: String) {
        Log.d(TAG, msg)
        // Still show important-ish debug in UI (RMS / skip)
        emit("D", msg)
    }

    private fun emit(level: String, msg: String) {
        when (level) {
            "I" -> Log.i(TAG, msg)
            "W" -> Log.w(TAG, msg)
            "E" -> { /* already logged in e() */ }
            else -> Log.d(TAG, msg)
        }
        val line = "${timeFmt.format(Date())} $level $msg"
        ring.add(line)
        while (ring.size > MAX) {
            try {
                ring.removeAt(0)
            } catch (_: Exception) {
                break
            }
        }
        val s = sink
        if (s != null) {
            main.post {
                try {
                    s.success(line)
                } catch (_: Exception) {
                }
            }
        }
    }
}

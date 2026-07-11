package com.keyword.keyword_alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System playback capture only (Bilibili / Meeting). Never falls back to mic.
 */
class AudioCapture {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
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
     * Must be called on main thread after MediaProjection.registerCallback + VirtualDisplay.
     * [context] is required on API 31+ so AudioPolicy can register (attribution).
     */
    fun startPlaybackOnly(context: Context, projection: MediaProjection): Boolean {
        if (isRunning.get()) return captureMode == "playback"
        stop()

        // Prefer Service/Activity context for opPackageName attribution; fall back to app ctx.
        val contexts = listOf(context, context.applicationContext).distinct()
        // 48k first on many OEMs (policy rejects 16k), then 44.1k, then 16k for ASR.
        val rates = intArrayOf(48000, 44100, SAMPLE_RATE)
        for (ctx in contexts) {
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
                if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                    AppLog.w("getMinBufferSize invalid for rate=$rate: $minBuf")
                    continue
                }
                val bufSize = maxOf(minBuf * 2, rate * 2)
                // Try with/without VOICE_COMMUNICATION (no sleep — may run on main thread).
                var rec = createPlaybackCaptureRecord(ctx, projection, bufSize, rate, matchVoice = true)
                if (rec == null) {
                    rec = createPlaybackCaptureRecord(ctx, projection, bufSize, rate, matchVoice = false)
                }
                if (rec != null) {
                    audioRecord = rec
                    break
                }
            }
            if (audioRecord != null) break
        }

        if (audioRecord == null) {
            captureMode = "none"
            AppLog.e("AudioPlaybackCapture unavailable at all sample rates")
            return false
        }
        captureMode = "playback"
        AppLog.i("Capture mode: playback (system audio) rate=${audioRecord!!.sampleRate}")

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
            val buf = ShortArray((recordSampleRate / 10).coerceAtLeast(1600))
            var logCounter = 0
            while (isRunning.get()) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: -1
                if (read > 0) {
                    var sum = 0.0
                    // Downsample to ~16 kHz for SenseVoice when capturing at 44.1/48k
                    val step = when {
                        recordSampleRate >= 48000 -> 3
                        recordSampleRate >= 44100 -> 3 // approx
                        else -> 1
                    }
                    synchronized(audioBuffer) {
                        var i = 0
                        while (i < read) {
                            val s = buf[i]
                            audioBuffer.add(s)
                            sum += s * s.toDouble()
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
        context: Context,
        projection: MediaProjection,
        bufSize: Int,
        sampleRate: Int,
        matchVoice: Boolean,
    ): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            AppLog.i(
                "Trying AudioPlaybackCapture rate=$sampleRate voice=$matchVoice " +
                    "ctx=${context.javaClass.simpleName}",
            )
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            if (matchVoice) {
                // Some OEMs reject VOICE_COMMUNICATION in the capture policy.
                configBuilder.addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            }
            val config = configBuilder.build()

            // API 31+: Builder(Context) sets opPackageName — required for AudioPolicy registration
            // on many Android 14 / OEM builds ("could not register audio policy").
            // Use reflection so older compileSdk / Flutter stubs still compile.
            val builder = newAudioRecordBuilder(context)

            val record = builder
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNEL)
                        .build(),
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                AppLog.i("AudioPlaybackCapture OK rate=$sampleRate voice=$matchVoice")
                record
            } else {
                AppLog.w("AudioPlaybackCapture not initialized state=${record.state} rate=$sampleRate")
                record.release()
                null
            }
        } catch (e: Exception) {
            AppLog.w("AudioPlaybackCapture failed rate=$sampleRate voice=$matchVoice | $e")
            null
        }
    }

    private fun newAudioRecordBuilder(context: Context): AudioRecord.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val ctor = AudioRecord.Builder::class.java.getConstructor(Context::class.java)
                return ctor.newInstance(context)
            } catch (e: Exception) {
                AppLog.w("AudioRecord.Builder(Context) unavailable, falling back: $e")
            }
        }
        return AudioRecord.Builder()
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

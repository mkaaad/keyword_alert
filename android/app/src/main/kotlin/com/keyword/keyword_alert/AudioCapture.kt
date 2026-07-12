package com.keyword.keyword_alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System audio capture for media apps and VoIP (e.g. Tencent Meeting).
 * Never falls back to the microphone.
 *
 * Paths (in order):
 * 1) AudioPlaybackCapture with media + voice usages (needs MediaProjection)
 * 2) REMOTE_SUBMIX — Magisk/priv-app with CAPTURE_AUDIO_OUTPUT; better for
 *    conference apps that play remote audio on the voice/call stream
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
        if (isRunning.get()) {
            return captureMode == "playback" || captureMode == "remote_submix" ||
                captureMode == "playback_voice"
        }
        stop()

        val contexts = listOf(context, context.applicationContext).distinct()
        // 48k first on many OEMs; Tencent Meeting / VoIP often at 48k or 16k.
        val rates = intArrayOf(48000, 44100, SAMPLE_RATE)

        // 1) Prefer voice-inclusive playback capture (会议远端常走 VOICE_COMMUNICATION).
        for (ctx in contexts) {
            for (rate in rates) {
                val bufSize = bufferSizeFor(rate) ?: continue
                val rec = createPlaybackCaptureRecord(ctx, projection, bufSize, rate, Profile.VOICE)
                    ?: createPlaybackCaptureRecord(ctx, projection, bufSize, rate, Profile.MEDIA)
                if (rec != null) {
                    audioRecord = rec
                    captureMode = "playback"
                    break
                }
            }
            if (audioRecord != null) break
        }

        // 2) Magisk / system priv-app: REMOTE_SUBMIX can include call/VoIP mix that
        //    AudioPlaybackCapture cannot (apps set ALLOW_CAPTURE_BY_NONE or phone path).
        if (audioRecord == null) {
            AppLog.w("AudioPlaybackCapture failed — trying REMOTE_SUBMIX (needs CAPTURE_AUDIO_OUTPUT)")
            for (rate in rates) {
                val bufSize = bufferSizeFor(rate) ?: continue
                val rec = createRemoteSubmixRecord(bufSize, rate)
                if (rec != null) {
                    audioRecord = rec
                    captureMode = "remote_submix"
                    break
                }
            }
        } else {
            // Even if playback capture registered, 腾讯会议 may still opt out.
            // Prefer remote_submix when available for better meeting coverage.
            val submix = tryPreferRemoteSubmix(rates)
            if (submix != null) {
                try {
                    audioRecord?.release()
                } catch (_: Exception) {
                }
                audioRecord = submix
                captureMode = "remote_submix"
                AppLog.i("Switched to REMOTE_SUBMIX for VoIP/meeting capture")
            }
        }

        if (audioRecord == null) {
            captureMode = "none"
            AppLog.e("No capture path available (playback + remote_submix both failed)")
            return false
        }

        AppLog.i("Capture mode: $captureMode rate=${audioRecord!!.sampleRate}")

        try {
            audioRecord!!.startRecording()
        } catch (e: Exception) {
            AppLog.e("startRecording failed mode=$captureMode", e)
            audioRecord?.release()
            audioRecord = null
            captureMode = "none"
            return false
        }

        startReadLoop(audioRecord!!.sampleRate)
        return true
    }

    /**
     * Prefer REMOTE_SUBMIX when privileged — better for 腾讯会议 voice path.
     * Only switch if we can actually open it; keep playback capture otherwise.
     */
    private fun tryPreferRemoteSubmix(rates: IntArray): AudioRecord? {
        for (rate in rates) {
            val bufSize = bufferSizeFor(rate) ?: continue
            val rec = createRemoteSubmixRecord(bufSize, rate)
            if (rec != null) return rec
        }
        return null
    }

    private fun bufferSizeFor(rate: Int): Int? {
        val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            AppLog.w("getMinBufferSize invalid for rate=$rate: $minBuf")
            return null
        }
        return maxOf(minBuf * 2, rate * 2)
    }

    private enum class Profile { MEDIA, VOICE }

    private fun createPlaybackCaptureRecord(
        context: Context,
        projection: MediaProjection,
        bufSize: Int,
        sampleRate: Int,
        profile: Profile,
    ): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            AppLog.i(
                "Trying AudioPlaybackCapture rate=$sampleRate profile=$profile " +
                    "ctx=${context.javaClass.simpleName}",
            )
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

            if (profile == Profile.VOICE) {
                // Tencent Meeting / Zoom / Teams remote audio often uses these.
                configBuilder
                    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                    .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            } else {
                configBuilder.addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            }

            val config = configBuilder.build()
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
                AppLog.i("AudioPlaybackCapture OK rate=$sampleRate profile=$profile")
                record
            } else {
                AppLog.w("AudioPlaybackCapture not initialized state=${record.state} rate=$sampleRate")
                record.release()
                null
            }
        } catch (e: Exception) {
            AppLog.w("AudioPlaybackCapture failed rate=$sampleRate profile=$profile | $e")
            null
        }
    }

    /**
     * Privileged path (Magisk priv-app + CAPTURE_AUDIO_OUTPUT).
     * Captures hardware remote submix — closer to "phone/VoIP" mix than media-only APC.
     */
    private fun createRemoteSubmixRecord(bufSize: Int, sampleRate: Int): AudioRecord? {
        return try {
            AppLog.i("Trying REMOTE_SUBMIX sampleRate=$sampleRate...")
            @Suppress("DEPRECATION")
            val record = AudioRecord(
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                sampleRate,
                CHANNEL,
                ENCODING,
                bufSize,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                AppLog.i("REMOTE_SUBMIX OK rate=$sampleRate")
                record
            } else {
                AppLog.w("REMOTE_SUBMIX not initialized state=${record.state}")
                record.release()
                null
            }
        } catch (e: SecurityException) {
            AppLog.w("REMOTE_SUBMIX denied (need CAPTURE_AUDIO_OUTPUT / Magisk): $e")
            null
        } catch (e: Exception) {
            AppLog.w("REMOTE_SUBMIX failed rate=$sampleRate | $e")
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

    private fun startReadLoop(recordSampleRate: Int) {
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
                    val step = when {
                        recordSampleRate >= 48000 -> 3
                        recordSampleRate >= 44100 -> 3
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
                            "Audio mode=$captureMode rate=$recordSampleRate RMS=$lastRms " +
                                "frames=$totalFrames buf=${audioBuffer.size}",
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

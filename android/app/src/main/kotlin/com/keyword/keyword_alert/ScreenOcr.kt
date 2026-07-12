package com.keyword.keyword_alert

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen MediaProjection frames → Chinese OCR (ML Kit).
 * Throttled to avoid burning CPU; emits text via [onText].
 */
class ScreenOcr(
    private val onText: (String) -> Unit,
) {
    companion object {
        /** Min interval between OCR runs (ms). */
        private const val OCR_INTERVAL_MS = 1500L
        /** Max edge for OCR bitmap (full screen is captured; we scale for speed). */
        private const val MAX_OCR_EDGE = 1280
        private const val MIN_TEXT_LEN = 1
    }

    private var recognizer: TextRecognizer? = null
    private var reader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val ocrBusy = AtomicBoolean(false)
    private var lastOcrAt = 0L
    private var lastEmitted = ""
    private var frameCount = 0L

    @Volatile
    var isActive: Boolean = false
        private set

    fun start(imageReader: ImageReader): Boolean {
        stop()
        return try {
            recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )
            val thread = HandlerThread("ScreenOcr").apply { start() }
            workerThread = thread
            workerHandler = Handler(thread.looper)
            reader = imageReader
            running.set(true)
            isActive = true
            imageReader.setOnImageAvailableListener({ r ->
                onImageAvailable(r)
            }, workerHandler)
            AppLog.i(
                "ScreenOcr started size=${imageReader.width}x${imageReader.height} " +
                    "interval=${OCR_INTERVAL_MS}ms",
            )
            true
        } catch (e: Exception) {
            AppLog.e("ScreenOcr start failed", e)
            stop()
            false
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            frameCount++
            if (!running.get()) return
            val now = System.currentTimeMillis()
            if (now - lastOcrAt < OCR_INTERVAL_MS) return
            if (!ocrBusy.compareAndSet(false, true)) return
            lastOcrAt = now

            val bitmap = imageToBitmap(image) ?: run {
                ocrBusy.set(false)
                return
            }
            image.close()
            image = null

            val scaled = scaleForOcr(bitmap)
            if (scaled !== bitmap) {
                bitmap.recycle()
            }

            val input = InputImage.fromBitmap(scaled, 0)
            val rec = recognizer
            if (rec == null) {
                scaled.recycle()
                ocrBusy.set(false)
                return
            }
            rec.process(input)
                .addOnSuccessListener { result ->
                    try {
                        val text = result.text?.trim().orEmpty()
                        if (text.length >= MIN_TEXT_LEN && text != lastEmitted) {
                            lastEmitted = text
                            AppLog.i("OCR text (${text.length} chars): ${text.take(120)}")
                            mainHandler.post { onText(text) }
                        } else if (frameCount % 20L == 0L) {
                            AppLog.i("OCR empty/same frame#$frameCount")
                        }
                    } finally {
                        scaled.recycle()
                        ocrBusy.set(false)
                    }
                }
                .addOnFailureListener { e ->
                    AppLog.w("OCR failed: $e")
                    scaled.recycle()
                    ocrBusy.set(false)
                }
        } catch (e: Exception) {
            AppLog.w("ScreenOcr frame error: $e")
            ocrBusy.set(false)
        } finally {
            try {
                image?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(
                width + if (pixelStride > 0) rowPadding / pixelStride else 0,
                height,
                Bitmap.Config.ARGB_8888,
            )
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            if (bitmap.width == width) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (cropped !== bitmap) bitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            AppLog.w("imageToBitmap failed: $e")
            null
        }
    }

    private fun scaleForOcr(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val maxEdge = maxOf(w, h)
        if (maxEdge <= MAX_OCR_EDGE) return src
        val scale = MAX_OCR_EDGE.toFloat() / maxEdge
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    fun stop() {
        running.set(false)
        isActive = false
        try {
            reader?.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }
        reader = null
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            workerThread?.quitSafely()
        } catch (_: Exception) {
        }
        workerThread = null
        workerHandler = null
        ocrBusy.set(false)
        lastEmitted = ""
    }
}

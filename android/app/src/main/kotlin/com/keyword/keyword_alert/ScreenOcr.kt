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
 * Independent of audio ASR. Emits plain text via [onText].
 */
class ScreenOcr(
    private val onText: (String) -> Unit,
) {
    companion object {
        private const val OCR_INTERVAL_MS = 1200L
        private const val MAX_OCR_EDGE = 1600
        private const val MIN_TEXT_LEN = 1
        /** Skip near-black frames (common before VirtualDisplay paints). */
        private const val MIN_MEAN_LUMA = 8.0
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
    private var ocrAttempts = 0L
    private var startedAt = 0L

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
            startedAt = System.currentTimeMillis()
            lastOcrAt = 0L
            imageReader.setOnImageAvailableListener({ r ->
                onImageAvailable(r, from = "listener")
            }, workerHandler)
            // ColorOS/MediaProjection sometimes never fires listener — poll as backup.
            workerHandler?.post(pollRunnable)
            AppLog.i(
                "ScreenOcr started size=${imageReader.width}x${imageReader.height} " +
                    "interval=${OCR_INTERVAL_MS}ms (listener+poll)",
            )
            true
        } catch (e: Exception) {
            AppLog.e("ScreenOcr start failed", e)
            stop()
            false
        }
    }

    private val pollRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val r = reader
            if (r != null) {
                onImageAvailable(r, from = "poll")
                if (frameCount == 0L && System.currentTimeMillis() - startedAt > 3000L) {
                    AppLog.w(
                        "OCR still no frames after 3s " +
                            "(VirtualDisplay may not be delivering to ImageReader)",
                    )
                }
            }
            workerHandler?.postDelayed(this, 500L)
        }
    }

    private fun onImageAvailable(reader: ImageReader, from: String) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) {
                if (from == "poll" && frameCount == 0L &&
                    System.currentTimeMillis() - startedAt > 1500L &&
                    (System.currentTimeMillis() - startedAt).toInt() % 2000 < 600
                ) {
                    AppLog.w("OCR poll: acquireLatestImage=null (no frame yet)")
                }
                return
            }
            frameCount++
            if (frameCount == 1L) {
                AppLog.i("OCR first frame via $from ${image.width}x${image.height}")
            }
            if (!running.get()) return

            val now = System.currentTimeMillis()
            if (now - lastOcrAt < OCR_INTERVAL_MS) return
            if (!ocrBusy.compareAndSet(false, true)) return
            lastOcrAt = now
            ocrAttempts++

            val bitmap = imageToBitmap(image)
            // close in finally below
            try {
                image.close()
            } catch (_: Exception) {
            }
            image = null

            if (bitmap == null) {
                AppLog.w("OCR frame#$frameCount imageToBitmap=null via=$from")
                ocrBusy.set(false)
                return
            }

            val mean = meanLuma(bitmap)
            if (mean < MIN_MEAN_LUMA) {
                if (ocrAttempts <= 5L || ocrAttempts % 10L == 0L) {
                    AppLog.w(
                        "OCR skip black/empty frame#$frameCount " +
                            "${bitmap.width}x${bitmap.height} meanLuma=${"%.1f".format(mean)}",
                    )
                }
                bitmap.recycle()
                ocrBusy.set(false)
                return
            }

            val scaled = scaleForOcr(bitmap)
            if (scaled !== bitmap) bitmap.recycle()

            if (ocrAttempts <= 3L || ocrAttempts % 8L == 0L) {
                AppLog.i(
                    "OCR run#$ocrAttempts ${scaled.width}x${scaled.height} " +
                        "meanLuma=${"%.1f".format(mean)}",
                )
            }

            val rec = recognizer
            if (rec == null) {
                scaled.recycle()
                ocrBusy.set(false)
                return
            }

            val input = InputImage.fromBitmap(scaled, 0)
            rec.process(input)
                .addOnSuccessListener { result ->
                    try {
                        val text = normalizeOcrText(result.text)
                        if (text.length >= MIN_TEXT_LEN) {
                            if (text != lastEmitted) {
                                lastEmitted = text
                                AppLog.i("OCR text (${text.length}): ${text.take(160)}")
                                mainHandler.post {
                                    try {
                                        onText(text)
                                    } catch (e: Exception) {
                                        AppLog.w("OCR onText callback failed: $e")
                                    }
                                }
                            }
                        } else {
                            if (ocrAttempts <= 8L || ocrAttempts % 10L == 0L) {
                                AppLog.i(
                                    "OCR empty text run#$ocrAttempts " +
                                        "meanLuma=${"%.1f".format(mean)} " +
                                        "(screen may be FLAG_SECURE or no glyphs)",
                                )
                            }
                        }
                    } finally {
                        scaled.recycle()
                        ocrBusy.set(false)
                    }
                }
                .addOnFailureListener { e ->
                    AppLog.e("OCR ML Kit failed run#$ocrAttempts", e)
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

    private fun normalizeOcrText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    /**
     * Correct RGBA_8888 Image → ARGB_8888 Bitmap (handles rowStride padding).
     * copyPixelsFromBuffer alone often yields garbage/empty OCR on OEMs.
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            buffer.rewind()
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return null

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)

            // Fast path: tightly packed RGBA
            if (pixelStride == 4 && rowStride == width * 4 && buffer.remaining() >= width * height * 4) {
                var i = 0
                while (i < pixels.size) {
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    val a = buffer.get().toInt() and 0xFF
                    pixels[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                return bitmap
            }

            // General path with row padding / unusual stride
            val row = ByteArray(rowStride)
            var out = 0
            for (y in 0 until height) {
                val rowStart = y * rowStride
                if (rowStart + width * pixelStride > buffer.capacity()) break
                buffer.position(rowStart)
                val toRead = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, toRead)
                var x = 0
                var px = 0
                while (x < width) {
                    val r = row[px].toInt() and 0xFF
                    val g = row[px + 1].toInt() and 0xFF
                    val b = row[px + 2].toInt() and 0xFF
                    val a = if (pixelStride >= 4) row[px + 3].toInt() and 0xFF else 0xFF
                    pixels[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    px += pixelStride
                    x++
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            AppLog.w("imageToBitmap failed: $e")
            null
        }
    }

    private fun meanLuma(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0.0
        // Subsample for speed
        val stepX = maxOf(1, w / 64)
        val stepY = maxOf(1, h / 64)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n > 0) sum / n else 0.0
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
            workerHandler?.removeCallbacks(pollRunnable)
        } catch (_: Exception) {
        }
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
        frameCount = 0
        ocrAttempts = 0
    }
}

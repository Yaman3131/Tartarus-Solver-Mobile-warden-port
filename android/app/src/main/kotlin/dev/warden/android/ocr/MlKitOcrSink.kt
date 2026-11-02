package dev.warden.android.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.warden.android.native.OcrSink
import org.json.JSONArray
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * ML Kit's Latin text recognizer as the Rust reader's OCR engine. The Rust
 * side calls this on its own worker thread, so blocking on the ML Kit task
 * is fine. The regions of one scan are recognized in parallel on
 * [WORKERS] threads, each with its own recognizer (a client is not meant to
 * be used from two threads at once).
 *
 * The reader hands over crops upscaled for Windows' OCR; ML Kit is much
 * slower per pixel, so anything wider than [MAX_WIDTH] is downscaled first
 * and the boxes are scaled back.
 */
class MlKitOcrSink(@Suppress("unused") context: Context) : OcrSink {
    private val workers = Executors.newFixedThreadPool(WORKERS) { r ->
        Thread(r, "warden-ocr").apply { isDaemon = true }
    }

    private val recognizer = ThreadLocal.withInitial {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Loads the model on every worker now rather than on the first real
     * read: a cold read took 12 s with the game running.
     */
    fun warmUp() {
        repeat(WORKERS) {
            workers.submit(Callable { runCatching { lines(ByteArray(4 * 32 * 16), 32, 16) } })
        }
    }

    override fun recognizeBatch(images: Array<ByteArray>, widths: IntArray, heights: IntArray): String {
        val started = System.currentTimeMillis()
        val jobs = images.indices.map { i ->
            workers.submit(Callable { lines(images[i], widths[i], heights[i]) })
        }
        val out = JSONArray()
        for (job in jobs) out.put(job.get())
        android.util.Log.i("warden", "ocr ${images.size} regions in ${System.currentTimeMillis() - started}ms")
        return out.toString()
    }

    /** One region's lines, `[["text", [x, y, w, h]], ...]`. */
    private fun lines(rgba: ByteArray, w: Int, h: Int): JSONArray {
        val out = JSONArray()
        if (w <= 0 || h <= 0) return out
        val (bitmap, scale) = toImage(rgba, w, h) ?: return out
        val result = try {
            Tasks.await(recognizer.get().process(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Throwable) {
            android.util.Log.w("warden", "ocr ${w}x$h failed: ${e.message}")
            return out
        }
        val back = 1f / scale
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val b = line.boundingBox ?: continue
                if (line.text.isBlank()) continue
                out.put(
                    JSONArray()
                        .put(line.text)
                        .put(
                            JSONArray()
                                .put(b.left * back)
                                .put(b.top * back)
                                .put(b.width() * back)
                                .put(b.height() * back),
                        ),
                )
            }
        }
        return out
    }

    /**
     * RGBA_8888 bytes (MediaProjection's order) to an ARGB_8888 bitmap,
     * downscaled to [MAX_WIDTH]; the scale factor comes with it.
     */
    private fun toImage(rgba: ByteArray, w: Int, h: Int): Pair<Bitmap, Float>? {
        val n = w * h
        if (n <= 0 || rgba.size < n * 4) return null
        val pixels = IntArray(n)
        var o = 0
        for (i in 0 until n) {
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            val a = rgba[o + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            o += 4
        }
        val full = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (w <= MAX_WIDTH) return full to 1f
        val scale = MAX_WIDTH.toFloat() / w
        val scaled = Bitmap.createScaledBitmap(
            full,
            MAX_WIDTH,
            (h * scale).toInt().coerceAtLeast(1),
            true,
        )
        return scaled to scale
    }

    private companion object {
        /** OCR threads: the scan's fourteen regions finish in about four rounds. */
        const val WORKERS = 4

        /** Nothing wider is worth ML Kit's time; text stays legible. */
        const val MAX_WIDTH = 1400
    }
}

package dev.warden.android.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import dev.warden.android.Core
import java.nio.ByteBuffer

/**
 * MediaProjection frames into the solver. One reusable direct buffer, rows
 * copied out of the reader's stride padding; at ~4 Hz the frame is
 * fingerprinted and the watch says whether it changed enough to read.
 *
 * Everything runs on its own thread; the Rust session is mutexed, so the
 * UI can poll at the same time.
 */
class CaptureEngine(
    private val context: Context,
    private val projection: MediaProjection,
) {
    private val thread = HandlerThread("warden-capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var buffer: ByteBuffer? = null
    private var row: ByteBuffer? = null
    private var size = 0
    private var logged = false
    private var lastSample = 0L

    fun start() {
        val m = context.resources.displayMetrics
        val (w, h) = displaySize()
        buffer = ByteBuffer.allocateDirect(w * h * 4)
        size = w * h * 4
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ onFrame(it, w, h) }, handler)
        }
        display = projection.createVirtualDisplay(
            "warden",
            w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler,
        )
        Log.i(TAG, "capture started at ${w}x$h")
    }

    /** The screen's real pixels — the game is landscape on a landscape panel. */
    private fun displaySize(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    private fun onFrame(reader: ImageReader, w: Int, h: Int) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            return
        } ?: return
        // ~4 samples a second: the fingerprint walks the whole frame, and the
        // watch only needs to see a screen settle.
        val now = System.currentTimeMillis()
        if (now - lastSample < SAMPLE_MS) {
            image.close()
            return
        }
        lastSample = now
        try {
            val plane = image.planes[0]
            val src = plane.buffer
            val stride = plane.rowStride
            if (!logged) {
                logged = true
                Log.i(TAG, "frame ${image.width}x${image.height} stride=$stride cap=${src.capacity()} rem=${src.remaining()}")
            }
            val buf = buffer ?: return
            buf.clear()
            if (stride == w * 4) {
                buf.put(src)
            } else {
                // Rows are padded to `stride`: copy each one through a row
                // buffer (absolute bulk get is not on Android's ByteBuffer).
                val dst = row ?: ByteBuffer.allocate(w * 4).also { row = it }
                val dup = src.duplicate()
                for (y in 0 until h) {
                    dup.clear()
                    dup.position(y * stride)
                    dup.limit(y * stride + w * 4)
                    dst.clear()
                    dst.put(dup)
                    dst.flip()
                    buf.put(dst)
                }
            }
            buf.rewind()

            if (Core.wantsReread()) Core.readNext()
            // The sample also serves the watch's state; auto read off means
            // only a read the player asked for goes through.
            if (Core.sample(buf, w, h) && (Core.autoRead || Core.takeManual())) {
                Log.i(TAG, "reading frame")
                Core.readFrame(buf, w, h, true)
            }
        } catch (e: Throwable) {
            // A throw on this thread would take the whole app down.
            Log.w(TAG, "frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    fun stop() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection.stop()
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "warden"

        /** Minimum gap between samples: 4 a second. */
        const val SAMPLE_MS = 250L
    }
}

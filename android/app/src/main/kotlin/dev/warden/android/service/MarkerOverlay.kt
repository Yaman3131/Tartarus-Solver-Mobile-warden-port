package dev.warden.android.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The mark on the game: gold corner brackets around the advised card and an
 * arrow above it, on a full-screen layer that never takes a touch — the
 * Android port of `src/app/mark.rs`, in the same reference-pixel sizes.
 */
class MarkerOverlay(context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = MarkView(context)
    private var added = false

    /** `rect`: centre x, y and half sizes in screen px, `unit` px per reference px. */
    fun show(rect: Rect?) {
        if (rect == null) {
            if (added) runCatching { wm.removeView(view) }
            added = false
            view.mark = null
            return
        }
        // The window is the mark's own box, not the screen: a full-screen
        // layer next to a game swallows its touches on this device.
        val box = box(rect)
        val params = WindowManager.LayoutParams(
            box.width,
            box.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = box.left
            y = box.top
            // Third-party overlays are "untrusted": Android 12+ drops every
            // touch that passes through one whose opacity is above 0.8, even
            // with FLAG_NOT_TOUCHABLE. Stay under the threshold so the card
            // under the mark can still be pressed.
            alpha = 0.75f
        }
        view.mark = rect
        view.centre = (rect.x - box.left) to (rect.y - box.top)
        if (!added) {
            runCatching { wm.addView(view, params) }.onSuccess { added = true }
        } else {
            runCatching { wm.updateViewLayout(view, params) }
        }
        view.invalidate()
    }

    /** The window the mark needs: the padded box, the stroke and the arrow. */
    private fun box(m: Rect): Box {
        val side = (PAD + STROKE / 2f) * m.unit + 2f
        val right = m.halfW + side
        val below = m.halfH + side
        val above = below + (ARROW_GAP + ARROW_H) * m.unit
        return Box(
            left = (m.x - right).toInt(),
            top = (m.y - above).toInt(),
            width = (2f * right).toInt().coerceAtLeast(1),
            height = (above + below).toInt().coerceAtLeast(1),
        )
    }

    private data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    fun release() {
        if (added) {
            runCatching { wm.removeView(view) }
            added = false
        }
    }

    data class Rect(val x: Float, val y: Float, val halfW: Float, val halfH: Float, val unit: Float)

    /** Brackets and the arrow, drawn with the desktop's proportions. */
    private class MarkView(context: Context) : View(context) {
        var mark: Rect? = null
        /** The mark's centre in this view's own pixels. */
        var centre: Pair<Float, Float>? = null

        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            color = Color.argb(180, 20, 16, 8)
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            color = Color.rgb(255, 205, 110)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(255, 205, 110)
        }
        private val path = Path()

        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            val m = mark ?: return
            val u = m.unit
            val stroke = STROKE * u
            val edge = EDGE * u
            val arm = (ARM * u).coerceAtMost(0.6f * minOf(m.halfW, m.halfH))
            val bx = m.halfW + PAD * u
            val by = m.halfH + PAD * u
            val cx = centre?.first ?: m.x
            val cy = centre?.second ?: m.y

            // The four corners mirror each other: two right angles each.
            for (sx in intArrayOf(-1, 1)) {
                for (sy in intArrayOf(-1, 1)) {
                    val cornerX = cx + sx * bx
                    val cornerY = cy + sy * by
                    path.reset()
                    path.moveTo(cornerX - sx * arm, cornerY)
                    path.lineTo(cornerX, cornerY)
                    path.lineTo(cornerX, cornerY - sy * arm)
                    rim.strokeWidth = stroke
                    canvas.drawPath(path, rim)
                    core.strokeWidth = (stroke - 2 * edge).coerceAtLeast(1f)
                    canvas.drawPath(path, core)
                }
            }

            // The arrow above the box, tip pointing down at it.
            val tipY = cy - by - stroke / 2f - ARROW_GAP * u
            val halfArrow = ARROW_W * u / 2f
            val h = ARROW_H * u
            path.reset()
            path.moveTo(cx, tipY)
            path.lineTo(cx + halfArrow, tipY - h)
            path.lineTo(cx - halfArrow, tipY - h)
            path.close()
            canvas.drawPath(path, fill)
        }
    }

    private companion object {
        // Reference px (1080p), as in `mark.rs`.
        const val STROKE = 5f
        const val EDGE = 1.5f
        const val PAD = 8f
        const val ARM = 70f
        const val ARROW_W = 46f
        const val ARROW_H = 34f
        const val ARROW_GAP = 10f
    }
}

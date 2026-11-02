package dev.warden.android.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * The bubble over the game: it shows the advised move, a tap asks for a read
 * (the desktop's read hotkey), and a long press turns auto read on or off.
 * Drag it anywhere; it stays put across shows.
 */
class Balloon(context: Context, private val onTap: () -> Unit, private val onToggleAuto: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = TextView(context).apply {
        setTextColor(Color.rgb(255, 205, 110))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(28, 16, 28, 16)
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.argb(210, 24, 20, 14))
            setStroke(2, Color.rgb(255, 205, 110))
        }
        text = "Tartarus Solver"
        isClickable = true
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_TOUCH_MODAL is what lets the game keep every tap outside the
        // pill: without it a touchable window takes them all.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 40
        y = 220
    }

    private var added = false

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                startX = params.x
                startY = params.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (kotlin.math.hypot(dx, dy) > 12) moved = true
                if (moved) {
                    params.x = (startX + dx).toInt()
                    params.y = (startY + dy).toInt()
                    runCatching { wm.updateViewLayout(view, params) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    // A tap reads now; a long press toggles auto instead.
                    if (ev.eventTime - ev.downTime >= 600) onToggleAuto() else onTap()
                }
                return true
            }
        }
        return false
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    fun show() {
        if (added) return
        view.setOnTouchListener { _, ev -> handleTouch(ev) }
        runCatching { wm.addView(view, params) }.onSuccess { added = true }
    }

    /** The bubble's line: the advice, or why there is none. */
    fun setLabel(text: String) {
        if (!added) return
        view.text = text
        view.visibility = View.VISIBLE
    }

    fun hide() {
        if (added) {
            runCatching { wm.removeView(view) }
            added = false
        }
    }

    fun release() = hide()
}

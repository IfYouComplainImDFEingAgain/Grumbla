package app.notmumla.service

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.ContextCompat
import app.notmumla.R
import app.notmumla.data.FloatingPttMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * The floating talk button (BACKLOG 3.3): a small `TYPE_APPLICATION_OVERLAY` window drawn over other
 * apps while connected. [VoiceService] decides when it shows; this class owns the window, the touch
 * handling and the push-to-talk state it drives through [onPtt]. Main thread only.
 *
 * Safety rules, because a stuck button transmits to the whole channel:
 * - Hiding the button (app reopened, disconnect, setting off, muted) releases any press or latch it owns.
 * - In hold mode, ACTION_CANCEL releases like ACTION_UP.
 * - Any one transmission it starts is capped at [MAX_TRANSMIT_MS], then released with a buzz.
 * - No haptics on press/release: the phone's mic picks up the buzz and sends it. The only buzz is
 *   the timeout's, which comes after transmission has stopped.
 */
class FloatingPttOverlay(
    private val context: Context,
    private val onPtt: (Boolean) -> Unit,
    /** Called once a drag ends, with the snapped window position (persist it). */
    private val onMoved: (x: Int, y: Int) -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val sizePx = (BUTTON_DP * density).toInt()
    private val marginPx = (EDGE_MARGIN_DP * density).toInt()

    private var view: ButtonView? = null
    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "Grumbla talk button"
    }

    var mode: FloatingPttMode = FloatingPttMode.HOLD
        set(v) { if (field != v) { release(); field = v } }

    /** Self-muted/deafened or the mic is yielded: presses do nothing and the button is dimmed. */
    var disabled: Boolean = false
        set(v) {
            field = v
            if (v) release()
            view?.alpha = if (v) 0.5f else 1f
            view?.invalidate()
        }

    /** Whether the engine is actually sending (drives the green "live" look). */
    var transmitting: Boolean = false
        set(v) { field = v; view?.invalidate() }

    /** We currently hold PTT (pressed in hold mode, or latched in toggle mode). */
    private var owning = false
    private val _latched = MutableStateFlow(false)
    /** Toggle mode is latched on. The notification offers "Stop talking" while it is. */
    val latched: StateFlow<Boolean> = _latched.asStateFlow()

    private val timeout = Runnable {
        android.util.Log.w(TAG, "Floating PTT held for ${MAX_TRANSMIT_MS / 1000}s, releasing")
        release()
        // Buzz once the engine has sent its last frame, so the mic doesn't pick it up.
        handler.postDelayed({ view?.performHapticFeedback(HapticFeedbackConstants.REJECT) }, 300)
    }

    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            // Rotation or a fold changes the bounds: put the button back on an edge.
            handler.post { if (view != null) { snapToEdge(); onMoved(params.x, params.y) } }
        }
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {}
    }

    val isShowing: Boolean get() = view != null

    /** Add the window at the saved position (or a default one). No-op when already showing. */
    fun show(savedX: Int, savedY: Int) {
        if (view != null) return
        val area = usableArea()
        if (savedX < 0 || savedY < 0) {
            params.x = area.right - sizePx
            params.y = area.top + (area.height() * 0.4f).toInt()
        } else {
            params.x = savedX
            params.y = savedY
        }
        clamp(area)
        val v = ButtonView(context).also { it.alpha = if (disabled) 0.5f else 1f }
        try {
            wm.addView(v, params)
        } catch (t: Throwable) {
            // Permission revoked between the check and here, or a bad token: just don't show.
            android.util.Log.w(TAG, "addView failed", t)
            return
        }
        view = v
        context.registerComponentCallbacks(configCallbacks)
    }

    fun hide() {
        release()
        val v = view ?: return
        view = null
        runCatching { context.unregisterComponentCallbacks(configCallbacks) }
        runCatching { wm.removeViewImmediate(v) }
    }

    /** Stop any transmission this button started (press or latch). Safe to call any time. */
    fun release() {
        handler.removeCallbacks(timeout)
        _latched.value = false
        if (owning) {
            owning = false
            onPtt(false)
        }
        view?.invalidate()
    }

    private fun press() {
        if (owning || disabled) return
        owning = true
        onPtt(true)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_TRANSMIT_MS)
        view?.invalidate()
    }

    private fun toggle() {
        if (_latched.value) { release(); return }
        if (disabled) return
        press()
        _latched.value = owning
    }

    /** Screen area the button may sit in: the window bounds minus system bars and cutouts, plus a margin. */
    private fun usableArea(): Rect {
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val b = metrics.bounds
        return Rect(
            b.left + insets.left + marginPx, b.top + insets.top + marginPx,
            b.right - insets.right - marginPx, b.bottom - insets.bottom - marginPx,
        )
    }

    private fun clamp(area: Rect = usableArea()) {
        params.x = params.x.coerceIn(area.left, maxOf(area.left, area.right - sizePx))
        params.y = params.y.coerceIn(area.top, maxOf(area.top, area.bottom - sizePx))
    }

    private fun snapToEdge() {
        val area = usableArea()
        params.x = if (params.x + sizePx / 2 < area.centerX()) area.left else area.right - sizePx
        clamp(area)
        view?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    @SuppressLint("ViewConstructor")
    private inner class ButtonView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val mic = ContextCompat.getDrawable(ctx, R.drawable.ic_ptt_mic)!!.mutate()
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f

        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var startX = 0
        private var startY = 0
        private var dragging = false

        init {
            contentDescription = "Push to talk"
            isHapticFeedbackEnabled = true
        }

        override fun onDraw(canvas: Canvas) {
            fill.color = if (owning || transmitting) COLOR_LIVE else COLOR_IDLE
            ring.color = when {
                _latched.value -> Color.WHITE
                disabled -> COLOR_MUTED
                else -> COLOR_RING
            }
            val r = width / 2f
            canvas.drawCircle(r, r, r - ring.strokeWidth, fill)
            canvas.drawCircle(r, r, r - ring.strokeWidth, ring)
            val inset = width / 4
            mic.setBounds(inset, inset, width - inset, height - inset)
            mic.draw(canvas)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downAt = SystemClock.uptimeMillis()
                    startX = params.x; startY = params.y
                    dragging = false
                    // Talk starts on touch, not after a delay: PTT latency clips the first word.
                    if (mode == FloatingPttMode.HOLD) press()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        // In hold mode only a quick flick moves the button; once someone has been
                        // talking for a moment, a wobbling finger must not cut them off.
                        val canDrag = mode == FloatingPttMode.TOGGLE ||
                            SystemClock.uptimeMillis() - downAt < HOLD_DRAG_WINDOW_MS
                        if (canDrag) {
                            dragging = true
                            if (mode == FloatingPttMode.HOLD) release()
                        }
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(this, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    when {
                        dragging -> { snapToEdge(); onMoved(params.x, params.y) }
                        mode == FloatingPttMode.HOLD -> release()
                        else -> { toggle(); performClick() }
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (mode == FloatingPttMode.HOLD) release()
                    if (dragging) { snapToEdge(); onMoved(params.x, params.y) }
                    dragging = false
                }
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }

    companion object {
        private const val TAG = "FloatingPtt"
        private const val BUTTON_DP = 60
        private const val EDGE_MARGIN_DP = 6
        /** Hard cap on one transmission from the button (stuck touch, forgotten latch). */
        const val MAX_TRANSMIT_MS = 5 * 60 * 1000L
        /** In hold mode, a drag must start within this long after touch-down. */
        private const val HOLD_DRAG_WINDOW_MS = 250L
        private const val COLOR_IDLE = 0xE61D2024.toInt() // dark surfContainer, slightly see-through
        private const val COLOR_RING = 0xFF8E9099.toInt() // outline
        private const val COLOR_LIVE = 0xFF1F8A5B.toInt() // speaking green
        private const val COLOR_MUTED = 0xFFFFB4AB.toInt() // muted red
    }
}

package com.camscroll.service

import android.content.Context
import android.graphics.PixelFormat
import android.view.*
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.camscroll.R
import com.camscroll.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manages the floating status dot that appears on top of all apps.
 *
 * FIX BUG-11: Load position before adding view to windowManager.
 * FIX BUG-16: Properly cancel CoroutineScope on hide to prevent leak.
 */
class OverlayManager(private val context: Context) {

    enum class Status { ACTIVE, PAUSED }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var currentStatus = Status.ACTIVE
    private var scope = CoroutineScope(Dispatchers.Main)

    fun show() {
        if (isShowing) return
        isShowing = true
        
        scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            // FIX BUG-11: Load position BEFORE configuring layout params
            val (savedX, savedY) = UserPreferences.overlayPosition(context).first()

            val layout = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX
                y = savedY
            }
            params = layout

            val dot = buildDotView()
            overlayView = dot
            
            if (isShowing) { // Check if hide was called while waiting
                windowManager.addView(dot, layout)
                setStatus(currentStatus)
                setupDrag(dot, layout)
            }
        }
    }

    fun hide() {
        isShowing = false
        scope.cancel() // FIX BUG-16: prevent coroutine leak
        
        val view = overlayView ?: return
        if (view.isAttachedToWindow) {
            windowManager.removeView(view)
        }
        overlayView = null
    }

    fun setStatus(status: Status) {
        currentStatus = status
        overlayView?.let { dot ->
            val color = when (status) {
                Status.ACTIVE -> ContextCompat.getColor(context, R.color.overlay_dot_active)
                Status.PAUSED -> ContextCompat.getColor(context, R.color.overlay_dot_paused)
            }
            (dot as? ImageView)?.setColorFilter(color)
        }
    }

    // --- Private helpers ---

    private fun buildDotView(): View {
        val size = (context.resources.displayMetrics.density * 14).toInt() // 14dp
        val iv = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setImageResource(R.drawable.ic_overlay_dot)
            setColorFilter(ContextCompat.getColor(context, R.color.overlay_dot_active))
            alpha = 0.85f
        }
        return iv
    }

    private fun setupDrag(view: View, layoutParams: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) isDragging = true
                    if (isDragging) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(view, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap: toggle pause
                        val intent = android.content.Intent(FaceTrackingService.BROADCAST_GESTURE).apply {
                            putExtra(
                                FaceTrackingService.EXTRA_GESTURE_TYPE,
                                if (currentStatus == Status.ACTIVE) "TrackingPaused" else "TrackingResumed"
                            )
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(intent)
                    } else {
                        // Save new position
                        scope.launch {
                            UserPreferences.setOverlayPosition(context, layoutParams.x, layoutParams.y)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }
}

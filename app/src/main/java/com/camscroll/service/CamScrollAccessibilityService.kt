package com.camscroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager

private const val TAG = "A11yService"

/**
 * Receives GestureEvents from FaceTrackingService and converts them to real Android actions.
 *
 * Two scroll methods — tries AccessibilityNodeInfo first (more reliable),
 * falls back to dispatchGesture (works everywhere).
 */
class CamScrollAccessibilityService : AccessibilityService() {

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val type = intent.getStringExtra(FaceTrackingService.EXTRA_GESTURE_TYPE) ?: return
            handleGestureType(type)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(FaceTrackingService.BROADCAST_GESTURE)
        registerReceiver(gestureReceiver, filter)
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(gestureReceiver)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // We don't need to monitor app events — we only inject gestures
    }

    private fun handleGestureType(type: String) {
        when (type) {
            "ScrollUp" -> scroll(up = true)
            "ScrollDown" -> scroll(up = false)
            "Tap" -> tapCenter()
            "Back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "NextItem" -> scroll(up = false)
            "PrevItem" -> scroll(up = true)
            "PausePlay" -> tapCenter()
        }
    }

    /**
     * Scroll using AccessibilityNodeInfo action (preferred — works in RecyclerViews, WebViews).
     * Falls back to dispatchGesture swipe if no scrollable node found.
     */
    private fun scroll(up: Boolean) {
        val root = rootInActiveWindow
        val scrollable = findScrollableNode(root)

        if (scrollable != null) {
            val action = if (up) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                         else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val success = scrollable.performAction(action)
            scrollable.recycle()
            if (success) return
        }

        // Fallback: simulate swipe gesture
        dispatchScrollGesture(up)
    }

    /**
     * Find the first scrollable node in the view tree.
     */
    private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val found = findScrollableNode(root.getChild(i))
            if (found != null) return found
        }
        return null
    }

    /**
     * Simulate a swipe gesture using screen coordinates.
     * Uses screen height dynamically — never hardcoded pixels.
     */
    private fun dispatchScrollGesture(up: Boolean) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        val centerX = screenWidth / 2f

        val (startY, endY) = if (up) {
            Pair(screenHeight * 0.35f, screenHeight * 0.65f)   // swipe down = scroll up
        } else {
            Pair(screenHeight * 0.65f, screenHeight * 0.35f)   // swipe up = scroll down
        }

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Tap the center of the screen.
     */
    private fun tapCenter() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f

        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}

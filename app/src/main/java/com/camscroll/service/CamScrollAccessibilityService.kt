package com.camscroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

private const val TAG = "A11yService"

/**
 * Receives GestureEvents from FaceTrackingService and converts them to real Android actions.
 *
 * FIX BUG-04/05: Use RECEIVER_NOT_EXPORTED on Android 13+.
 * FIX BUG-15: Recycle AccessibilityNodeInfo objects to prevent memory leak.
 * FIX BUG-19: Update getMetrics to support API 30+ without deprecation.
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gestureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gestureReceiver, filter)
        }
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

    private fun scroll(up: Boolean) {
        val root = rootInActiveWindow
        val scrollable = findScrollableNode(root)

        if (scrollable != null) {
            val action = if (up) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                         else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val success = scrollable.performAction(action)
            scrollable.recycle()
            if (root != null && root !== scrollable) {
                root.recycle()
            }
            if (success) return
        } else {
            root?.recycle()
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
            val child = root.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun getScreenSize(): Pair<Float, Float> {
        val wm = getSystemService(android.view.WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().toFloat(), bounds.height().toFloat())
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
        }
    }

    private fun dispatchScrollGesture(up: Boolean) {
        val (screenWidth, screenHeight) = getScreenSize()
        val centerX = screenWidth / 2f

        val (startY, endY) = if (up) {
            Pair(screenHeight * 0.35f, screenHeight * 0.65f)
        } else {
            Pair(screenHeight * 0.65f, screenHeight * 0.35f)
        }

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun tapCenter() {
        val (screenWidth, screenHeight) = getScreenSize()
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f

        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}

package com.camscroll.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.camscroll.R
import com.camscroll.camera.CameraController
import com.camscroll.camera.FaceAnalyzer
import com.camscroll.camera.HandAnalyzer
import com.camscroll.data.UserPreferences
import com.camscroll.gesture.FastQuitGesture
import com.camscroll.gesture.GestureConfig
import com.camscroll.gesture.GestureEngine
import com.camscroll.gesture.GestureEvent
import com.camscroll.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "FaceTrackingService"
private const val NOTIF_ID = 1001
private const val CHANNEL_ID = "camscroll_active"

/**
 * Core foreground service. Runs while CamScroll is active.
 *
 * Responsibilities:
 * - Open camera (CameraX, front, 320×240, 15fps)
 * - Run MediaPipe face + hand inference
 * - Feed results to GestureEngine
 * - Broadcast GestureEvents to CamScrollAccessibilityService
 * - Show/hide overlay dot
 * - Handle FastQuit → stop self
 */
class FaceTrackingService : LifecycleService() {

    private lateinit var gestureEngine: GestureEngine
    private lateinit var overlayManager: OverlayManager
    private lateinit var faceAnalyzer: FaceAnalyzer
    private lateinit var handAnalyzer: HandAnalyzer
    private lateinit var cameraController: CameraController
    private var config: GestureConfig = GestureConfig()

    companion object {
        var isRunning = false
            private set

        const val ACTION_STOP = "com.camscroll.ACTION_STOP"
        const val BROADCAST_GESTURE = "com.camscroll.GESTURE"
        const val EXTRA_GESTURE_TYPE = "gesture_type"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "Service created")

        gestureEngine = GestureEngine()
        overlayManager = OverlayManager(this)

        faceAnalyzer = FaceAnalyzer(
            context = this,
            onFaceResult = { blendshapes, yaw ->
                gestureEngine.processFaceResult(blendshapes, yaw)
            },
            onNoFace = {
                gestureEngine.onNoFaceDetected()
            }
        )

        handAnalyzer = HandAnalyzer(
            context = this,
            onHandResult = { landmarks ->
                if (config.fastQuitGesture != FastQuitGesture.DISABLED) {
                    gestureEngine.processHandResult(landmarks)
                }
            },
            onNoHand = {
                gestureEngine.onNoHandDetected()
            }
        )

        cameraController = CameraController(this, this, faceAnalyzer, handAnalyzer)

        // Observe user config changes live
        lifecycleScope.launch {
            UserPreferences.gestureConfig(this@FaceTrackingService).collectLatest { newConfig ->
                config = newConfig
                gestureEngine.updateConfig(newConfig)
            }
        }

        // Collect gesture events and broadcast to AccessibilityService
        lifecycleScope.launch {
            gestureEngine.events.collect { event ->
                handleGestureEvent(event)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground IMMEDIATELY (within 5 seconds — Android requirement)
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else 0
        )

        cameraController.start()
        overlayManager.show()

        Log.d(TAG, "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        cameraController.stop()
        overlayManager.hide()
        gestureEngine.reset()
        Log.d(TAG, "Service destroyed")
    }

    private fun handleGestureEvent(event: GestureEvent) {
        when (event) {
            is GestureEvent.FastQuit -> {
                Log.d(TAG, "FastQuit triggered — stopping service")
                stopSelf()
            }
            is GestureEvent.TrackingPaused -> overlayManager.setStatus(OverlayManager.Status.PAUSED)
            is GestureEvent.TrackingResumed -> overlayManager.setStatus(OverlayManager.Status.ACTIVE)
            else -> broadcastGesture(event)
        }
    }

    private fun broadcastGesture(event: GestureEvent) {
        val intent = Intent(BROADCAST_GESTURE).apply {
            putExtra(EXTRA_GESTURE_TYPE, event::class.simpleName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FaceTrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_tile, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}

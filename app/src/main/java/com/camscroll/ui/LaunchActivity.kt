package com.camscroll.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.camscroll.service.FaceTrackingService

/**
 * Transparent trampoline activity.
 *
 * Required by Android 14+: a camera foreground service cannot be started
 * while the app is in the background. The QS Tile calls startActivityAndCollapse()
 * which surfaces this invisible activity, which starts the service, then finishes.
 */
class LaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, FaceTrackingService::class.java))
        finish()
    }
}

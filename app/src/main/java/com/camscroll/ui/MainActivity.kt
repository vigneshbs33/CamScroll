package com.camscroll.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.camscroll.R
import com.camscroll.data.UserPreferences
import com.camscroll.service.FaceTrackingService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvScrollGesture: TextView
    private lateinit var tvFastQuit: TextView
    private lateinit var statusDot: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btn_toggle)
        tvStatus = findViewById(R.id.tv_status)
        tvScrollGesture = findViewById(R.id.tv_scroll_gesture)
        tvFastQuit = findViewById(R.id.tv_fast_quit)
        statusDot = findViewById(R.id.status_dot_main)

        // Toggle start/stop
        btnToggle.setOnClickListener { toggleService() }

        // Edit gesture shortcut
        findViewById<View>(R.id.btn_edit_gesture).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Settings icon
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Calibrate
        findViewById<View>(R.id.btn_calibrate).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        // Gesture settings
        findViewById<View>(R.id.btn_gesture_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Observe config for display
        lifecycleScope.launch {
            UserPreferences.gestureConfig(this@MainActivity).collectLatest { config ->
                tvScrollGesture.text = "${config.scrollUpGesture.displayName} → Scroll Up\n${config.scrollDownGesture.displayName} → Scroll Down"
                tvFastQuit.text = "${config.fastQuitGesture.displayName}${if (config.fastQuitGesture.name == "CLOSE_FIST") " (hold 1s)" else ""}"
            }
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun toggleService() {
        if (FaceTrackingService.isRunning) {
            stopService(Intent(this, FaceTrackingService::class.java))
        } else {
            // Guard: check camera permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                return
            }
            // Guard: check overlay permission
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Draw over apps permission required", Toast.LENGTH_SHORT).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
                return
            }
            startForegroundService(Intent(this, FaceTrackingService::class.java))
        }
        // Small delay to let service state update
        btnToggle.postDelayed({ updateUI() }, 300)
    }

    private fun updateUI() {
        val running = FaceTrackingService.isRunning
        btnToggle.text = if (running) getString(R.string.btn_stop) else getString(R.string.btn_start)
        tvStatus.text = if (running) getString(R.string.status_active) else getString(R.string.status_ready)
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (running) getColor(R.color.accent_active) else getColor(R.color.accent_inactive)
        )
    }
}

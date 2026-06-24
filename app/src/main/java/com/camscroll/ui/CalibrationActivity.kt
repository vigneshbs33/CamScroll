package com.camscroll.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camscroll.R
import com.camscroll.data.CalibrationProfile
import com.camscroll.data.UserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guided 3-step calibration wizard:
 * Step 1: Neutral face → save baseline
 * Step 2: Raise eyebrows → save peak
 * Step 3: Test it live
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var tvStepTitle: TextView
    private lateinit var tvStepDesc: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnAction: Button
    private lateinit var tvStepNumber: TextView

    private var currentStep = 0
    private var browBaseline = 0f
    private var browPeak = 0f
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        tvStepTitle = findViewById(R.id.tv_calibration_title)
        tvStepDesc = findViewById(R.id.tv_calibration_desc)
        progressBar = findViewById(R.id.calibration_progress)
        btnAction = findViewById(R.id.btn_calibration_action)
        tvStepNumber = findViewById(R.id.tv_step_number)

        // Back
        findViewById<View>(R.id.btn_back_calibration).setOnClickListener { finish() }

        btnAction.setOnClickListener { onActionClicked() }

        showStep(0)
    }

    private fun showStep(step: Int) {
        currentStep = step
        progressBar.progress = ((step + 1) * 33).coerceAtMost(100)
        tvStepNumber.text = "Step ${step + 1} of 3"

        when (step) {
            0 -> {
                tvStepTitle.text = getString(R.string.calibration_step1_title)
                tvStepDesc.text = getString(R.string.calibration_step1_desc)
                btnAction.text = "Start recording (3s)"
            }
            1 -> {
                tvStepTitle.text = getString(R.string.calibration_step2_title)
                tvStepDesc.text = getString(R.string.calibration_step2_desc)
                btnAction.text = "Record now (2s)"
            }
            2 -> {
                tvStepTitle.text = getString(R.string.calibration_step_test_title)
                tvStepDesc.text = getString(R.string.calibration_step_test_desc)
                btnAction.text = "Save & finish"
            }
        }
    }

    private fun onActionClicked() {
        when (currentStep) {
            0 -> recordBaseline()
            1 -> recordPeak()
            2 -> saveAndFinish()
        }
    }

    private fun recordBaseline() {
        if (isRecording) return
        isRecording = true
        btnAction.isEnabled = false
        btnAction.text = "Recording…"

        lifecycleScope.launch {
            // Simulate 3-second capture — in real implementation, average MediaPipe scores
            delay(3000)
            browBaseline = 0.05f // placeholder; real = averaged brow score from FaceAnalyzer
            isRecording = false
            btnAction.isEnabled = true
            showStep(1)
        }
    }

    private fun recordPeak() {
        if (isRecording) return
        isRecording = true
        btnAction.isEnabled = false
        btnAction.text = "Recording peak…"

        lifecycleScope.launch {
            delay(2000)
            browPeak = 0.70f // placeholder; real = max brow score during recording
            isRecording = false
            btnAction.isEnabled = true
            showStep(2)
        }
    }

    private fun saveAndFinish() {
        val profile = CalibrationProfile(
            browBaselineScore = browBaseline,
            browRaisePeak = browPeak,
            calibratedAt = System.currentTimeMillis()
        )

        lifecycleScope.launch {
            UserPreferences.setCalibrationJson(this@CalibrationActivity, profile.toJson())
            Toast.makeText(this@CalibrationActivity, getString(R.string.toast_calibration_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

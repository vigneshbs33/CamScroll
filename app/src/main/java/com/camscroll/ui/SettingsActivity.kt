package com.camscroll.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.camscroll.R
import com.camscroll.data.UserPreferences
import com.camscroll.gesture.FastQuitGesture
import com.camscroll.gesture.ScrollGesture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var spinnerScrollUp: Spinner
    private lateinit var spinnerScrollDown: Spinner
    private lateinit var spinnerFastQuit: Spinner
    private lateinit var seekSensitivity: SeekBar
    private lateinit var tvSensitivityValue: TextView

    private val scrollGestures = ScrollGesture.values().toList()
    private val fastQuitGestures = FastQuitGesture.values().toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        spinnerScrollUp = findViewById(R.id.spinner_scroll_up)
        spinnerScrollDown = findViewById(R.id.spinner_scroll_down)
        spinnerFastQuit = findViewById(R.id.spinner_fast_quit)
        seekSensitivity = findViewById(R.id.seek_sensitivity)
        tvSensitivityValue = findViewById(R.id.tv_sensitivity_value)

        // Back button
        findViewById<View>(R.id.btn_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Calibrate button
        findViewById<View>(R.id.btn_calibrate).setOnClickListener {
            startActivity(android.content.Intent(this, CalibrationActivity::class.java))
        }

        setupScrollUpSpinner()
        setupScrollDownSpinner()
        setupFastQuitSpinner()
        setupSensitivity()
        loadCurrentValues()

        // Cooldown chips
        val cooldowns = listOf(400L, 600L, 800L, 1000L)
        val chipIds = listOf(R.id.chip_400, R.id.chip_600, R.id.chip_800, R.id.chip_1000)
        chipIds.forEachIndexed { i, id ->
            findViewById<com.google.android.material.chip.Chip>(id).setOnClickListener {
                lifecycleScope.launch {
                    UserPreferences.setCooldown(this@SettingsActivity, cooldowns[i])
                    Toast.makeText(this@SettingsActivity, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupScrollUpSpinner() {
        val names = scrollGestures.map { it.displayName }
        spinnerScrollUp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerScrollUp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                lifecycleScope.launch {
                    UserPreferences.setScrollUpGesture(this@SettingsActivity, scrollGestures[pos])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupScrollDownSpinner() {
        val names = scrollGestures.map { it.displayName }
        spinnerScrollDown.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerScrollDown.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                lifecycleScope.launch {
                    UserPreferences.setScrollDownGesture(this@SettingsActivity, scrollGestures[pos])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFastQuitSpinner() {
        val names = fastQuitGestures.map { it.displayName }
        spinnerFastQuit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinnerFastQuit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                lifecycleScope.launch {
                    UserPreferences.setFastQuitGesture(this@SettingsActivity, fastQuitGestures[pos])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSensitivity() {
        // SeekBar 0-100 mapped to 0.5x-2.0x
        seekSensitivity.max = 100
        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.5f + (progress / 100f) * 1.5f
                tvSensitivityValue.text = String.format("%.1fx", value)
                if (fromUser) {
                    lifecycleScope.launch { UserPreferences.setSensitivity(this@SettingsActivity, value) }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun loadCurrentValues() {
        lifecycleScope.launch {
            val config = UserPreferences.gestureConfig(this@SettingsActivity).first()

            val upIdx = scrollGestures.indexOf(config.scrollUpGesture).coerceAtLeast(0)
            val downIdx = scrollGestures.indexOf(config.scrollDownGesture).coerceAtLeast(0)
            val quitIdx = fastQuitGestures.indexOf(config.fastQuitGesture).coerceAtLeast(0)

            spinnerScrollUp.setSelection(upIdx)
            spinnerScrollDown.setSelection(downIdx)
            spinnerFastQuit.setSelection(quitIdx)

            val sensitivityProgress = ((config.sensitivity - 0.5f) / 1.5f * 100).toInt()
            seekSensitivity.progress = sensitivityProgress
        }
    }
}

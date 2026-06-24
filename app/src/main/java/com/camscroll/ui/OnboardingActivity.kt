package com.camscroll.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.camscroll.R
import com.camscroll.data.UserPreferences
import com.camscroll.gesture.ScrollGesture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: Button
    private lateinit var btnSkip: Button

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> updatePermissionScreen() }
    private val requestNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> updatePermissionScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FIX BUG-06: Use .first() and set content only if not skipping
        lifecycleScope.launch {
            val done = UserPreferences.onboardingDone(this@OnboardingActivity).first()
            if (done) {
                goToMain()
                return@launch
            }

            setContentView(R.layout.activity_onboarding)
            viewPager = findViewById(R.id.view_pager)
            btnNext = findViewById(R.id.btn_next)
            btnSkip = findViewById(R.id.btn_skip)

            viewPager.isUserInputEnabled = false
            viewPager.adapter = OnboardingPagerAdapter()

            btnNext.setOnClickListener { advance() }
            btnSkip.setOnClickListener { finishOnboarding() }
        }
    }

    private fun advance() {
        val current = viewPager.currentItem
        if (current < 3) {
            viewPager.currentItem = current + 1
            if (current == 2) {
                // Last page
                btnNext.text = getString(R.string.btn_done)
                btnSkip.visibility = View.GONE
            }
        } else {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        lifecycleScope.launch {
            UserPreferences.setOnboardingDone(this@OnboardingActivity)
            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun updatePermissionScreen() {
        // FIX BUG-20: Refresh page 2
        if (::viewPager.isInitialized) {
            viewPager.adapter?.notifyItemChanged(1)
        }
    }

    inner class OnboardingPagerAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<OnboardingPagerAdapter.PageHolder>() {

        override fun getItemCount() = 4

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = layoutInflater.inflate(R.layout.item_onboarding_page, parent, false)
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            when (position) {
                0 -> holder.bind(
                    title = getString(R.string.onboarding_title_1),
                    subtitle = getString(R.string.onboarding_subtitle_1),
                    iconRes = R.drawable.ic_tile
                )
                1 -> holder.bindPermissions()
                2 -> holder.bindGesturePicker()
                3 -> holder.bind(
                    title = getString(R.string.onboarding_title_4),
                    subtitle = getString(R.string.onboarding_subtitle_4),
                    iconRes = R.drawable.ic_tile
                )
            }
        }

        inner class PageHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            fun bind(title: String, subtitle: String, iconRes: Int) {
                itemView.findViewById<TextView>(R.id.tv_page_title).text = title
                itemView.findViewById<TextView>(R.id.tv_page_subtitle).text = subtitle
                itemView.findViewById<ImageView>(R.id.iv_page_icon).setImageResource(iconRes)
                itemView.findViewById<View>(R.id.perm_container).visibility = View.GONE
                itemView.findViewById<View>(R.id.gesture_container).visibility = View.GONE
            }

            fun bindPermissions() {
                itemView.findViewById<TextView>(R.id.tv_page_title).text = getString(R.string.onboarding_title_2)
                itemView.findViewById<View>(R.id.iv_page_icon).visibility = View.GONE
                itemView.findViewById<View>(R.id.tv_page_subtitle).visibility = View.GONE
                itemView.findViewById<View>(R.id.gesture_container).visibility = View.GONE
                itemView.findViewById<View>(R.id.perm_container).visibility = View.VISIBLE

                // Camera permission row
                itemView.findViewById<Button>(R.id.btn_perm_camera).apply {
                    val granted = ContextCompat.checkSelfPermission(this@OnboardingActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    text = if (granted) "✓ Granted" else getString(R.string.perm_camera_title)
                    isEnabled = !granted
                    setOnClickListener { requestCamera.launch(Manifest.permission.CAMERA) }
                }

                // Overlay permission row
                itemView.findViewById<Button>(R.id.btn_perm_overlay).apply {
                    val granted = Settings.canDrawOverlays(this@OnboardingActivity)
                    text = if (granted) "✓ Granted" else getString(R.string.perm_overlay_title)
                    isEnabled = !granted
                    setOnClickListener {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                }

                // Notification permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    itemView.findViewById<Button>(R.id.btn_perm_notification).apply {
                        visibility = View.VISIBLE
                        val granted = ContextCompat.checkSelfPermission(this@OnboardingActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        text = if (granted) "✓ Granted" else getString(R.string.perm_notification_title)
                        isEnabled = !granted
                        setOnClickListener { requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }

                // Accessibility
                itemView.findViewById<Button>(R.id.btn_perm_accessibility).setOnClickListener {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }

            fun bindGesturePicker() {
                itemView.findViewById<TextView>(R.id.tv_page_title).text = getString(R.string.onboarding_title_3)
                itemView.findViewById<TextView>(R.id.tv_page_subtitle).text = getString(R.string.onboarding_subtitle_3)
                itemView.findViewById<View>(R.id.iv_page_icon).visibility = View.GONE
                itemView.findViewById<View>(R.id.perm_container).visibility = View.GONE
                itemView.findViewById<View>(R.id.gesture_container).visibility = View.VISIBLE

                val gestures = ScrollGesture.values().filter { it != ScrollGesture.NONE }
                val gestureNames = gestures.map { it.displayName }

                // Simplified: just show a spinner for scroll up gesture
                val spinner = itemView.findViewById<android.widget.Spinner>(R.id.spinner_scroll_gesture)
                spinner.adapter = ArrayAdapter(this@OnboardingActivity, android.R.layout.simple_spinner_dropdown_item, gestureNames)

                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        lifecycleScope.launch {
                            UserPreferences.setScrollUpGesture(this@OnboardingActivity, gestures[pos])
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
        }
    }
}

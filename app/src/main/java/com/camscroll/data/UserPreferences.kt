package com.camscroll.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.camscroll.gesture.FastQuitGesture
import com.camscroll.gesture.GestureConfig
import com.camscroll.gesture.ScrollGesture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Single DataStore instance for the app
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camscroll_prefs")

/**
 * All user preferences stored in DataStore.
 * Provides typed Flows for each setting and suspend writers.
 */
object UserPreferences {

    // --- Keys ---
    private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    private val KEY_SCROLL_UP_GESTURE = stringPreferencesKey("scroll_up_gesture")
    private val KEY_SCROLL_DOWN_GESTURE = stringPreferencesKey("scroll_down_gesture")
    private val KEY_FAST_QUIT_GESTURE = stringPreferencesKey("fast_quit_gesture")
    private val KEY_SENSITIVITY = floatPreferencesKey("sensitivity")
    private val KEY_COOLDOWN_MS = longPreferencesKey("cooldown_ms")
    private val KEY_OVERLAY_POSITION_X = intPreferencesKey("overlay_x")
    private val KEY_OVERLAY_POSITION_Y = intPreferencesKey("overlay_y")
    private val KEY_CALIBRATION_JSON = stringPreferencesKey("calibration_json")

    // --- Flows ---

    fun onboardingDone(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { it[KEY_ONBOARDING_DONE] ?: false }

    fun gestureConfig(ctx: Context): Flow<GestureConfig> =
        ctx.dataStore.data.map { prefs ->
            GestureConfig(
                scrollUpGesture = try {
                    ScrollGesture.valueOf(prefs[KEY_SCROLL_UP_GESTURE] ?: ScrollGesture.EYEBROW_RAISE.name)
                } catch (e: IllegalArgumentException) {
                    ScrollGesture.EYEBROW_RAISE
                },
                scrollDownGesture = try {
                    ScrollGesture.valueOf(prefs[KEY_SCROLL_DOWN_GESTURE] ?: ScrollGesture.EYEBROW_LOWER.name)
                } catch (e: IllegalArgumentException) {
                    ScrollGesture.EYEBROW_LOWER
                },
                fastQuitGesture = try {
                    FastQuitGesture.valueOf(prefs[KEY_FAST_QUIT_GESTURE] ?: FastQuitGesture.CLOSE_FIST.name)
                } catch (e: IllegalArgumentException) {
                    FastQuitGesture.CLOSE_FIST
                },
                sensitivity = prefs[KEY_SENSITIVITY] ?: 1.0f,
                cooldownMs = prefs[KEY_COOLDOWN_MS] ?: 800L
            )
        }

    fun overlayPosition(ctx: Context): Flow<Pair<Int, Int>> =
        ctx.dataStore.data.map { prefs ->
            Pair(prefs[KEY_OVERLAY_POSITION_X] ?: 16, prefs[KEY_OVERLAY_POSITION_Y] ?: 100)
        }

    fun calibrationJson(ctx: Context): Flow<String?> =
        ctx.dataStore.data.map { it[KEY_CALIBRATION_JSON] }

    // --- Writers ---

    suspend fun setOnboardingDone(ctx: Context) {
        ctx.dataStore.edit { it[KEY_ONBOARDING_DONE] = true }
    }

    suspend fun setScrollUpGesture(ctx: Context, gesture: ScrollGesture) {
        ctx.dataStore.edit { it[KEY_SCROLL_UP_GESTURE] = gesture.name }
    }

    suspend fun setScrollDownGesture(ctx: Context, gesture: ScrollGesture) {
        ctx.dataStore.edit { it[KEY_SCROLL_DOWN_GESTURE] = gesture.name }
    }

    suspend fun setFastQuitGesture(ctx: Context, gesture: FastQuitGesture) {
        ctx.dataStore.edit { it[KEY_FAST_QUIT_GESTURE] = gesture.name }
    }

    suspend fun setSensitivity(ctx: Context, value: Float) {
        ctx.dataStore.edit { it[KEY_SENSITIVITY] = value }
    }

    suspend fun setCooldown(ctx: Context, ms: Long) {
        ctx.dataStore.edit { it[KEY_COOLDOWN_MS] = ms }
    }

    suspend fun setOverlayPosition(ctx: Context, x: Int, y: Int) {
        ctx.dataStore.edit {
            it[KEY_OVERLAY_POSITION_X] = x
            it[KEY_OVERLAY_POSITION_Y] = y
        }
    }

    suspend fun setCalibrationJson(ctx: Context, json: String) {
        ctx.dataStore.edit { it[KEY_CALIBRATION_JSON] = json }
    }
}

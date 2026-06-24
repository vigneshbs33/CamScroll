package com.camscroll.gesture

/**
 * Per-blendshape filter that smooths raw MediaPipe scores and prevents false triggers.
 *
 * Pipeline:
 *   Raw score → EMA smoothing → Baseline subtraction → Hysteresis gate → Hold timer → Cooldown
 */
class BlendshapeFilter(
    private val activateThreshold: Float,
    private val deactivateThreshold: Float,
    private val emaAlpha: Float = 0.30f,
    private val holdDurationMs: Long = 250L,
    private val cooldownMs: Long = 800L,
    private var baseline: Float = 0.0f
) {
    private var emaValue: Float = 0.0f
    private var isActive: Boolean = false

    // Hold: gesture must be sustained before it fires
    private var activeStartTime: Long = 0L
    private var holdFired: Boolean = false

    // Cooldown: prevents repeat-fire after a gesture triggers
    private var lastFiredTime: Long = 0L

    /**
     * Feed in the latest raw blendshape score (0.0–1.0).
     * Returns true once when the gesture is confirmed (hold met + cooldown clear).
     */
    fun process(rawScore: Float): Boolean {
        val now = System.currentTimeMillis()

        // Step 1: EMA smoothing — kills frame-to-frame jitter
        emaValue = emaAlpha * rawScore + (1f - emaAlpha) * emaValue

        // Step 2: Baseline subtraction — normalises user's resting face
        val normalised = (emaValue - baseline).coerceAtLeast(0f)

        // Step 3: Hysteresis gate
        if (!isActive && normalised > activateThreshold) {
            isActive = true
            activeStartTime = now
            holdFired = false
        } else if (isActive && normalised < deactivateThreshold) {
            isActive = false
            holdFired = false
        }

        // Step 4: Hold timer — gesture must be sustained
        if (isActive && !holdFired && (now - activeStartTime) >= holdDurationMs) {
            // Step 5: Cooldown check
            if (now - lastFiredTime >= cooldownMs) {
                holdFired = true
                lastFiredTime = now
                return true // ← gesture confirmed, fire event
            }
        }

        return false
    }

    /** Update the resting-face baseline (set during calibration). */
    fun setBaseline(value: Float) {
        baseline = value
        emaValue = value
    }

    /** Reset all state (e.g., when service restarts). */
    fun reset() {
        emaValue = baseline
        isActive = false
        holdFired = false
        activeStartTime = 0L
        lastFiredTime = 0L
    }

    /** Current smoothed value — useful for live calibration UI feedback. */
    val smoothedValue: Float get() = emaValue
}

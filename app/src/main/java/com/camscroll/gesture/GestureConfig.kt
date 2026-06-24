package com.camscroll.gesture

/**
 * Which face gesture triggers scroll up / scroll down.
 * The user picks these during onboarding or settings.
 */
enum class ScrollGesture(val displayName: String) {
    EYEBROW_RAISE("Eyebrow Raise"),
    EYEBROW_LOWER("Eyebrow Lower"),
    SLOW_BLINK("Slow Blink"),
    SMILE("Smile"),
    HEAD_TILT_RIGHT("Head Tilt Right"),
    HEAD_TILT_LEFT("Head Tilt Left"),
    MOUTH_OPEN("Mouth Open"),
    NONE("Disabled")
}

/**
 * Fast quit gesture options.
 */
enum class FastQuitGesture(val displayName: String) {
    CLOSE_FIST("Close Fist"),
    PEACE_SIGN("Peace Sign"),
    THUMBS_DOWN("Thumbs Down"),
    DISABLED("Disabled")
}

/**
 * All tuneable parameters for the gesture engine.
 * Defaults are calibrated for general use — calibration wizard overrides per-user.
 */
data class GestureConfig(

    // --- Gesture mapping ---
    val scrollUpGesture: ScrollGesture = ScrollGesture.EYEBROW_RAISE,
    val scrollDownGesture: ScrollGesture = ScrollGesture.EYEBROW_LOWER,
    val fastQuitGesture: FastQuitGesture = FastQuitGesture.CLOSE_FIST,

    // --- Blendshape thresholds (activation / deactivation) ---
    val browRaiseActivate: Float = 0.55f,
    val browRaiseDeactivate: Float = 0.35f,
    val browLowerActivate: Float = 0.45f,
    val browLowerDeactivate: Float = 0.28f,
    val blinkActivate: Float = 0.80f,       // High: natural blinks (~100ms) never reach this
    val blinkDeactivate: Float = 0.50f,
    val smileActivate: Float = 0.60f,
    val smileDeactivate: Float = 0.38f,
    val jawOpenActivate: Float = 0.55f,
    val jawOpenDeactivate: Float = 0.35f,

    // --- Head pose ---
    val headTiltThresholdDeg: Float = 15f,

    // --- EMA smoothing (0.0 = max smooth, 1.0 = raw) ---
    val emaAlpha: Float = 0.30f,

    // --- Timing ---
    val cooldownMs: Long = 800L,
    val gestureHoldMs: Long = 250L,   // Must sustain gesture before it fires
    val fastQuitHoldMs: Long = 1000L, // Hold fist for 1s to quit

    // --- Calibration baselines (set by calibration wizard) ---
    val browBaselineScore: Float = 0.0f,
    val blinkBaselineScore: Float = 0.0f,
    val smileBaselineScore: Float = 0.0f,
    val headYawBaseline: Float = 0.0f,

    // --- Sensitivity multiplier (UI slider: 0.5x to 2.0x) ---
    val sensitivity: Float = 1.0f
)

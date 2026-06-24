package com.camscroll.gesture

import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Core gesture engine. Receives blendshape scores and head pose from MediaPipe,
 * runs them through BlendshapeFilters, and emits GestureEvents.
 *
 * Usage:
 *   engine.updateConfig(config)
 *   engine.processFaceResult(blendshapes, yaw)
 *   engine.processHandResult(handLandmarks)
 *   collect engine.events to receive GestureEvents
 */
class GestureEngine {

    private val _events = MutableSharedFlow<GestureEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GestureEvent> = _events.asSharedFlow()

    private var config: GestureConfig = GestureConfig()

    // --- Blendshape filters (one per expression) ---
    private var browRaiseFilter = buildFilter(config.browRaiseActivate, config.browRaiseDeactivate)
    private var browLowerFilter = buildFilter(config.browLowerActivate, config.browLowerDeactivate)
    private var blinkFilter = buildFilter(config.blinkActivate, config.blinkDeactivate)
    private var smileFilter = buildFilter(config.smileActivate, config.smileDeactivate)
    private var jawOpenFilter = buildFilter(config.jawOpenActivate, config.jawOpenDeactivate)

    // --- Head tilt state ---
    private var headTiltRight = false
    private var headTiltLeft = false
    private var lastHeadTiltFire = 0L

    // --- Fast quit hold timer ---
    private var fastQuitStartTime = 0L
    private var fastQuitActive = false

    // --- No-face pause ---
    private var lastFaceSeenTime = System.currentTimeMillis()
    private var isPaused = false
    private val noFacePauseMs = 10_000L // pause after 10s without face

    /** Update config — rebuilds filters with new thresholds. */
    fun updateConfig(newConfig: GestureConfig) {
        config = newConfig
        browRaiseFilter = buildFilter(config.browRaiseActivate, config.browRaiseDeactivate, config.browBaselineScore)
        browLowerFilter = buildFilter(config.browLowerActivate, config.browLowerDeactivate, config.browBaselineScore)
        blinkFilter = buildFilter(config.blinkActivate, config.blinkDeactivate, config.blinkBaselineScore)
        smileFilter = buildFilter(config.smileActivate, config.smileDeactivate, config.smileBaselineScore)
        jawOpenFilter = buildFilter(config.jawOpenActivate, config.jawOpenDeactivate)
        headTiltRight = false
        headTiltLeft = false
    }

    /**
     * Called every frame with MediaPipe FaceLandmarker blendshape output.
     * @param blendshapes List of Category objects (name + score)
     * @param yawDeg Head yaw in degrees from facial transformation matrix
     */
    fun processFaceResult(blendshapes: List<Category>, yawDeg: Float) {
        lastFaceSeenTime = System.currentTimeMillis()
        if (isPaused) {
            isPaused = false
            emit(GestureEvent.TrackingResumed)
        }

        val brow = blendshapes.scoreFor("browInnerUp")
        val browDownL = blendshapes.scoreFor("browDown_L")
        val browDownR = blendshapes.scoreFor("browDown_R")
        val blinkL = blendshapes.scoreFor("eyeBlink_L")
        val blinkR = blendshapes.scoreFor("eyeBlink_R")
        val smileL = blendshapes.scoreFor("mouthSmile_L")
        val smileR = blendshapes.scoreFor("mouthSmile_R")
        val jaw = blendshapes.scoreFor("jawOpen")

        val browDownAvg = (browDownL + browDownR) / 2f
        val blinkAvg = (blinkL + blinkR) / 2f
        val smileAvg = (smileL + smileR) / 2f

        // Map user's chosen gesture to scroll events
        if (browRaiseFilter.process(brow) && config.scrollUpGesture == ScrollGesture.EYEBROW_RAISE)
            emit(GestureEvent.ScrollUp)
        if (browRaiseFilter.process(brow) && config.scrollDownGesture == ScrollGesture.EYEBROW_RAISE)
            emit(GestureEvent.ScrollDown)

        if (browLowerFilter.process(browDownAvg) && config.scrollUpGesture == ScrollGesture.EYEBROW_LOWER)
            emit(GestureEvent.ScrollUp)
        if (browLowerFilter.process(browDownAvg) && config.scrollDownGesture == ScrollGesture.EYEBROW_LOWER)
            emit(GestureEvent.ScrollDown)

        if (blinkFilter.process(blinkAvg) && config.scrollUpGesture == ScrollGesture.SLOW_BLINK)
            emit(GestureEvent.ScrollUp)
        if (blinkFilter.process(blinkAvg) && config.scrollDownGesture == ScrollGesture.SLOW_BLINK)
            emit(GestureEvent.ScrollDown)

        if (smileFilter.process(smileAvg) && config.scrollUpGesture == ScrollGesture.SMILE)
            emit(GestureEvent.ScrollUp)
        if (smileFilter.process(smileAvg) && config.scrollDownGesture == ScrollGesture.SMILE)
            emit(GestureEvent.ScrollDown)

        if (jawOpenFilter.process(jaw) && config.scrollUpGesture == ScrollGesture.MOUTH_OPEN)
            emit(GestureEvent.ScrollUp)
        if (jawOpenFilter.process(jaw) && config.scrollDownGesture == ScrollGesture.MOUTH_OPEN)
            emit(GestureEvent.ScrollDown)

        // Head tilt — with cooldown
        val now = System.currentTimeMillis()
        val adjustedYaw = yawDeg - config.headYawBaseline
        val threshold = config.headTiltThresholdDeg

        if (now - lastHeadTiltFire > config.cooldownMs) {
            if (adjustedYaw > threshold && !headTiltRight) {
                headTiltRight = true
                headTiltLeft = false
                if (config.scrollUpGesture == ScrollGesture.HEAD_TILT_RIGHT) {
                    emit(GestureEvent.ScrollUp); lastHeadTiltFire = now
                } else if (config.scrollDownGesture == ScrollGesture.HEAD_TILT_RIGHT) {
                    emit(GestureEvent.ScrollDown); lastHeadTiltFire = now
                }
            } else if (adjustedYaw < -threshold && !headTiltLeft) {
                headTiltLeft = true
                headTiltRight = false
                if (config.scrollUpGesture == ScrollGesture.HEAD_TILT_LEFT) {
                    emit(GestureEvent.ScrollUp); lastHeadTiltFire = now
                } else if (config.scrollDownGesture == ScrollGesture.HEAD_TILT_LEFT) {
                    emit(GestureEvent.ScrollDown); lastHeadTiltFire = now
                }
            } else if (adjustedYaw in -threshold..threshold) {
                headTiltRight = false
                headTiltLeft = false
            }
        }
    }

    /**
     * Called when no face is detected in frame.
     * After 10 seconds, pauses gesture tracking.
     */
    fun onNoFaceDetected() {
        val now = System.currentTimeMillis()
        if (!isPaused && (now - lastFaceSeenTime) > noFacePauseMs) {
            isPaused = true
            emit(GestureEvent.TrackingPaused)
        }
    }

    /**
     * Called every frame with MediaPipe Hand Landmarker output.
     * Only used for Fast Quit gesture detection.
     */
    fun processHandResult(landmarks: List<NormalizedLandmark>) {
        if (config.fastQuitGesture == FastQuitGesture.DISABLED) return

        val isQuitGesture = when (config.fastQuitGesture) {
            FastQuitGesture.CLOSE_FIST -> FistDetector.isFist(landmarks)
            FastQuitGesture.PEACE_SIGN -> FistDetector.isPeaceSign(landmarks)
            FastQuitGesture.THUMBS_DOWN -> FistDetector.isThumbsDown(landmarks)
            FastQuitGesture.DISABLED -> false
        }

        val now = System.currentTimeMillis()
        if (isQuitGesture) {
            if (!fastQuitActive) {
                fastQuitActive = true
                fastQuitStartTime = now
            } else if ((now - fastQuitStartTime) >= config.fastQuitHoldMs) {
                fastQuitActive = false
                emit(GestureEvent.FastQuit)
            }
        } else {
            fastQuitActive = false
        }
    }

    /** Called when no hand is detected. */
    fun onNoHandDetected() {
        fastQuitActive = false
    }

    /** Reset all filter state. */
    fun reset() {
        browRaiseFilter.reset()
        browLowerFilter.reset()
        blinkFilter.reset()
        smileFilter.reset()
        jawOpenFilter.reset()
        headTiltRight = false
        headTiltLeft = false
        fastQuitActive = false
        isPaused = false
        lastFaceSeenTime = System.currentTimeMillis()
    }

    /** Expose smoothed scores for calibration UI live feedback. */
    fun getBrowSmoothed() = browRaiseFilter.smoothedValue
    fun getBlinkSmoothed() = blinkFilter.smoothedValue
    fun getSmileSmoothed() = smileFilter.smoothedValue

    // --- Helpers ---

    private fun emit(event: GestureEvent) {
        _events.tryEmit(event)
    }

    private fun buildFilter(activate: Float, deactivate: Float, baseline: Float = 0f): BlendshapeFilter {
        val f = BlendshapeFilter(
            activateThreshold = activate / config.sensitivity,
            deactivateThreshold = deactivate / config.sensitivity,
            emaAlpha = config.emaAlpha,
            holdDurationMs = config.gestureHoldMs,
            cooldownMs = config.cooldownMs,
            baseline = baseline
        )
        return f
    }

    private fun List<Category>.scoreFor(name: String): Float =
        firstOrNull { it.categoryName() == name }?.score() ?: 0f
}

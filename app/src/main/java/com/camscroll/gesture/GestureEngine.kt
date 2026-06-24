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
 * FIX BUG-08: Evaluate filters once per frame to prevent double-firing and double-smoothing.
 * FIX BUG-22: Use Math.abs() for Float range checking.
 * FIX ARCH-01: Added @Volatile and @Synchronized for thread-safety.
 */
class GestureEngine {

    private val _events = MutableSharedFlow<GestureEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GestureEvent> = _events.asSharedFlow()

    @Volatile private var config: GestureConfig = GestureConfig()

    // --- Blendshape filters (one per expression) ---
    @Volatile private var browRaiseFilter = buildFilter(config.browRaiseActivate, config.browRaiseDeactivate)
    @Volatile private var browLowerFilter = buildFilter(config.browLowerActivate, config.browLowerDeactivate)
    @Volatile private var blinkFilter = buildFilter(config.blinkActivate, config.blinkDeactivate)
    @Volatile private var smileFilter = buildFilter(config.smileActivate, config.smileDeactivate)
    @Volatile private var jawOpenFilter = buildFilter(config.jawOpenActivate, config.jawOpenDeactivate)

    // --- Head tilt state ---
    @Volatile private var headTiltRight = false
    @Volatile private var headTiltLeft = false
    @Volatile private var lastHeadTiltFire = 0L

    // --- Fast quit hold timer ---
    @Volatile private var fastQuitStartTime = 0L
    @Volatile private var fastQuitActive = false

    // --- No-face pause ---
    @Volatile private var lastFaceSeenTime = System.currentTimeMillis()
    @Volatile private var isPaused = false
    private val noFacePauseMs = 10_000L // pause after 10s without face

    @Synchronized
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

    @Synchronized
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

        // FIX BUG-08: Evaluate filters exactly once per frame
        val browRaiseFired = browRaiseFilter.process(brow)
        val browLowerFired = browLowerFilter.process(browDownAvg)
        val blinkFired = blinkFilter.process(blinkAvg)
        val smileFired = smileFilter.process(smileAvg)
        val jawOpenFired = jawOpenFilter.process(jaw)

        // Map user's chosen gesture to scroll events
        if (browRaiseFired) {
            if (config.scrollUpGesture == ScrollGesture.EYEBROW_RAISE) emit(GestureEvent.ScrollUp)
            if (config.scrollDownGesture == ScrollGesture.EYEBROW_RAISE) emit(GestureEvent.ScrollDown)
        }

        if (browLowerFired) {
            if (config.scrollUpGesture == ScrollGesture.EYEBROW_LOWER) emit(GestureEvent.ScrollUp)
            if (config.scrollDownGesture == ScrollGesture.EYEBROW_LOWER) emit(GestureEvent.ScrollDown)
        }

        if (blinkFired) {
            if (config.scrollUpGesture == ScrollGesture.SLOW_BLINK) emit(GestureEvent.ScrollUp)
            if (config.scrollDownGesture == ScrollGesture.SLOW_BLINK) emit(GestureEvent.ScrollDown)
        }

        if (smileFired) {
            if (config.scrollUpGesture == ScrollGesture.SMILE) emit(GestureEvent.ScrollUp)
            if (config.scrollDownGesture == ScrollGesture.SMILE) emit(GestureEvent.ScrollDown)
        }

        if (jawOpenFired) {
            if (config.scrollUpGesture == ScrollGesture.MOUTH_OPEN) emit(GestureEvent.ScrollUp)
            if (config.scrollDownGesture == ScrollGesture.MOUTH_OPEN) emit(GestureEvent.ScrollDown)
        }

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
            } else if (Math.abs(adjustedYaw) < threshold) { // FIX BUG-22
                headTiltRight = false
                headTiltLeft = false
            }
        }
    }

    @Synchronized
    fun onNoFaceDetected() {
        val now = System.currentTimeMillis()
        if (!isPaused && (now - lastFaceSeenTime) > noFacePauseMs) {
            isPaused = true
            emit(GestureEvent.TrackingPaused)
        }
    }

    @Synchronized
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

    @Synchronized
    fun onNoHandDetected() {
        fastQuitActive = false
    }

    @Synchronized
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

    fun getBrowSmoothed() = browRaiseFilter.smoothedValue
    fun getBlinkSmoothed() = blinkFilter.smoothedValue
    fun getSmileSmoothed() = smileFilter.smoothedValue

    // --- Helpers ---

    private fun emit(event: GestureEvent) {
        _events.tryEmit(event)
    }

    private fun buildFilter(activate: Float, deactivate: Float, baseline: Float = 0f): BlendshapeFilter {
        return BlendshapeFilter(
            activateThreshold = activate / config.sensitivity,
            deactivateThreshold = deactivate / config.sensitivity,
            emaAlpha = config.emaAlpha,
            holdDurationMs = config.gestureHoldMs,
            cooldownMs = config.cooldownMs,
            baseline = baseline
        )
    }

    private fun List<Category>.scoreFor(name: String): Float =
        firstOrNull { it.categoryName() == name }?.score() ?: 0f
}

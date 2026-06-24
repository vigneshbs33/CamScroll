package com.camscroll.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

private const val TAG = "HandAnalyzer"
private const val MODEL_PATH = "hand_landmarker.task"

/**
 * ImageAnalysis.Analyzer that detects hand landmarks for Fast Quit gesture.
 *
 * FIX BUG-17: Bitmap recycled after each frame.
 * FIX SEC-04: GPU → CPU fallback on unsupported hardware.
 */
class HandAnalyzer(
    context: Context,
    private val onHandResult: (landmarks: List<NormalizedLandmark>) -> Unit,
    private val onNoHand: () -> Unit
) : ImageAnalysis.Analyzer {

    private val handLandmarker: HandLandmarker

    init {
        handLandmarker = createLandmarker(context)
        Log.d(TAG, "HandLandmarker initialized")
    }

    override fun analyze(image: ImageProxy) {
        val bitmap = image.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        handLandmarker.detectAsync(mpImage, image.imageInfo.timestamp)
        image.close()
        bitmap.recycle() // FIX BUG-17
    }

    private fun handleResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            onNoHand()
            return
        }
        onHandResult(result.landmarks()[0])
    }

    fun close() {
        handLandmarker.close()
    }

    // FIX SEC-04: GPU → CPU fallback
    private fun createLandmarker(context: Context): HandLandmarker {
        return try {
            val gpuBase = BaseOptions.builder().setDelegate(Delegate.GPU).build()
            HandLandmarker.createFromOptions(context, buildOptions(gpuBase))
        } catch (e: RuntimeException) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU: ${e.message}")
            val cpuBase = BaseOptions.builder().setDelegate(Delegate.CPU).build()
            HandLandmarker.createFromOptions(context, buildOptions(cpuBase))
        }
    }

    private fun buildOptions(base: BaseOptions) =
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e -> Log.e(TAG, "HandLandmarker error", e) }
            .build()
}

/** Convert ImageProxy to Bitmap, mirrored for front camera. */
private fun ImageProxy.toBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.copyPixelsFromBuffer(planes[0].buffer)
    val matrix = Matrix().apply { postScale(-1f, 1f, width / 2f, height / 2f) }
    val mirrored = Bitmap.createBitmap(bmp, 0, 0, width, height, matrix, false)
    bmp.recycle()
    return mirrored
}

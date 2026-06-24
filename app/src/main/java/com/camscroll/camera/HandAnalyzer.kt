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
 * Only active when Fast Quit is enabled in settings.
 */
class HandAnalyzer(
    context: Context,
    private val onHandResult: (landmarks: List<NormalizedLandmark>) -> Unit,
    private val onNoHand: () -> Unit
) : ImageAnalysis.Analyzer {

    private val handLandmarker: HandLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_PATH)
            .setDelegate(Delegate.GPU)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e -> Log.e(TAG, "HandLandmarker error", e) }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(context, options)
        Log.d(TAG, "HandLandmarker initialized")
    }

    override fun analyze(image: ImageProxy) {
        val bitmap = image.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        handLandmarker.detectAsync(mpImage, image.imageInfo.timestamp)
        image.close()
    }

    private fun handleResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            onNoHand()
            return
        }
        val landmarks: List<NormalizedLandmark> = result.landmarks()[0]
        onHandResult(landmarks)
    }

    fun close() {
        handLandmarker.close()
    }
}

// Extension to convert ImageProxy to Bitmap (mirrored for front camera)
private fun ImageProxy.toBitmap(): Bitmap {
    val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmapBuffer.copyPixelsFromBuffer(planes[0].buffer)
    val matrix = Matrix().apply { postScale(-1f, 1f, width / 2f, height / 2f) }
    return Bitmap.createBitmap(bitmapBuffer, 0, 0, width, height, matrix, false)
}

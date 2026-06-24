package com.camscroll.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.PI
import kotlin.math.atan2

private const val TAG = "FaceAnalyzer"
private const val MODEL_PATH = "face_landmarker.task"

/**
 * ImageAnalysis.Analyzer that feeds each camera frame to MediaPipe FaceLandmarker.
 * Extracts blendshapes and head yaw, then calls back into the GestureEngine.
 *
 * FIX BUG-01: Removed recursive extension that caused StackOverflowError.
 * FIX BUG-02: Removed wrong PointF3D extension; use Matrix.data() correctly.
 * FIX BUG-17: Bitmap is recycled after each frame to prevent memory leak.
 * FIX SEC-04: GPU delegate falls back to CPU if GPU is unavailable.
 */
class FaceAnalyzer(
    context: Context,
    private val onFaceResult: (blendshapes: List<Category>, yawDeg: Float) -> Unit,
    private val onNoFace: () -> Unit
) : ImageAnalysis.Analyzer {

    private val faceLandmarker: FaceLandmarker

    init {
        faceLandmarker = createLandmarker(context)
        Log.d(TAG, "FaceLandmarker initialized")
    }

    override fun analyze(image: ImageProxy) {
        val bitmap = image.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker.detectAsync(mpImage, image.imageInfo.timestamp)
        image.close()
        bitmap.recycle() // FIX BUG-17: recycle every frame bitmap
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        val blendshapesOpt = result.faceBlendshapes()
        if (blendshapesOpt.isEmpty || blendshapesOpt.get().isEmpty()) {
            onNoFace()
            return
        }

        val blendshapes: List<Category> = blendshapesOpt.get()[0]
        val yaw = extractYaw(result)
        onFaceResult(blendshapes, yaw)
    }

    /**
     * Extracts yaw (left-right head rotation) from the 4×4 facial transformation matrix.
     *
     * FIX BUG-01 + BUG-02: Previously had a recursive extension and wrong PointF3D type.
     * MediaPipe Matrix.data() returns float[][] (row-major 4x4).
     * Yaw = atan2(-R[0][2], R[0][0]) in this coordinate system.
     */
    private fun extractYaw(result: FaceLandmarkerResult): Float {
        val matricesOpt = result.facialTransformationMatrixes()
        if (matricesOpt.isEmpty || matricesOpt.get().isEmpty()) return 0f

        return try {
            val data: Array<FloatArray> = matricesOpt.get()[0].data()
            if (data.size < 3 || data[0].size < 3) return 0f
            // Row 0, columns 0 and 2 give us cos(yaw) and -sin(yaw)
            val yawRad = atan2(-data[0][2].toDouble(), data[0][0].toDouble())
            (yawRad * 180.0 / PI).toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract yaw: ${e.message}")
            0f
        }
    }

    fun close() {
        faceLandmarker.close()
    }

    // FIX SEC-04: Try GPU first, fall back to CPU on unsupported hardware
    private fun createLandmarker(context: Context): FaceLandmarker {
        return try {
            val gpuBase = BaseOptions.builder().setDelegate(Delegate.GPU).build()
            FaceLandmarker.createFromOptions(context, buildOptions(gpuBase))
        } catch (e: RuntimeException) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU: ${e.message}")
            val cpuBase = BaseOptions.builder().setDelegate(Delegate.CPU).build()
            FaceLandmarker.createFromOptions(context, buildOptions(cpuBase))
        }
    }

    private fun buildOptions(base: BaseOptions) =
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e -> Log.e(TAG, "FaceLandmarker error", e) }
            .build()
}

/** Convert ImageProxy to Bitmap, mirrored for front camera. */
private fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    buffer.rewind()
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.copyPixelsFromBuffer(planes[0].buffer)
    val matrix = Matrix().apply { postScale(-1f, 1f, width / 2f, height / 2f) }
    val mirrored = Bitmap.createBitmap(bmp, 0, 0, width, height, matrix, false)
    bmp.recycle()
    return mirrored
}

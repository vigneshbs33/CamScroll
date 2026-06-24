package com.camscroll.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.components.containers.Category
import kotlin.math.atan2
import kotlin.math.PI

private const val TAG = "FaceAnalyzer"
private const val MODEL_PATH = "face_landmarker.task"

/**
 * ImageAnalysis.Analyzer that feeds each camera frame to MediaPipe FaceLandmarker.
 * Extracts blendshapes and head yaw, then calls back into the GestureEngine.
 */
class FaceAnalyzer(
    context: Context,
    private val onFaceResult: (blendshapes: List<Category>, yawDeg: Float) -> Unit,
    private val onNoFace: () -> Unit
) : ImageAnalysis.Analyzer {

    private val faceLandmarker: FaceLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_PATH)
            .setDelegate(Delegate.GPU)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
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

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        Log.d(TAG, "FaceLandmarker initialized")
    }

    override fun analyze(image: ImageProxy) {
        val bitmap = image.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        faceLandmarker.detectAsync(mpImage, image.imageInfo.timestamp)
        image.close()
    }

    private fun handleResult(result: FaceLandmarkerResult) {
        if (result.faceBlendshapes().isEmpty || result.faceBlendshapes().get().isEmpty()) {
            onNoFace()
            return
        }

        val blendshapes: List<Category> = result.faceBlendshapes().get()[0]

        // Extract yaw from facial transformation matrix
        val yaw = extractYaw(result)

        onFaceResult(blendshapes, yaw)
    }

    /**
     * Extracts yaw (left-right head rotation) from the 4x4 facial transformation matrix.
     * Matrix is column-major. Yaw = atan2(M[8], M[10]).
     */
    private fun extractYaw(result: FaceLandmarkerResult): Float {
        if (result.facialTransformationMatrixes().isEmpty ||
            result.facialTransformationMatrixes().get().isEmpty()) return 0f

        val matrix = result.facialTransformationMatrixes().get()[0]
        // matrix is a 4x4 float array in column-major order
        val m = matrix.flattenToArray()
        if (m.size < 11) return 0f
        val yawRad = atan2(m[8].toDouble(), m[10].toDouble())
        return (yawRad * 180.0 / PI).toFloat()
    }

    private fun android.media.Image.toBitmap(): Bitmap {
        // handled via ImageProxy.toBitmap() extension below
        throw UnsupportedOperationException()
    }
}

// Extension to convert ImageProxy to Bitmap
private fun ImageProxy.toBitmap(): Bitmap {
    val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmapBuffer.copyPixelsFromBuffer(planes[0].buffer)
    // Front camera — mirror horizontally
    val matrix = Matrix().apply { postScale(-1f, 1f, width / 2f, height / 2f) }
    return Bitmap.createBitmap(bitmapBuffer, 0, 0, width, height, matrix, false)
}

private fun android.graphics.PointF3D.flattenToArray(): FloatArray = floatArrayOf(x, y, z)

private fun com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
    .facialTransformationMatrixes() = this.facialTransformationMatrixes()

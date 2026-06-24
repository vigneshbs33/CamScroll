package com.camscroll.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraController"

/**
 * Manages the CameraX lifecycle for CamScroll.
 *
 * Opens the front camera at 320×240, 15fps.
 * Binds an ImageAnalysis use case that feeds frames to FaceAnalyzer and HandAnalyzer.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val faceAnalyzer: FaceAnalyzer,
    private val handAnalyzer: HandAnalyzer
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        // Front camera only
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        // Face analysis — 320×240 is enough for landmark detection
        val faceAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, faceAnalyzer) }

        // Hand analysis — same stream, separate analyzer
        val handAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, handAnalyzer) }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, faceAnalysis, handAnalysis)
            Log.d(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        Log.d(TAG, "Camera stopped")
    }
}

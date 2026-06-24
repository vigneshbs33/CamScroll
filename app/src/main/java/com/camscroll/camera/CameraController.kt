package com.camscroll.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraController"

/**
 * Manages the CameraX lifecycle for CamScroll.
 *
 * FIX BUG-03: Recreate executors on start instead of permanent shutdown.
 * FIX BUG-09: Use separate executors for Face and Hand to prevent frame starvation.
 * FIX BUG-18: Use ResolutionSelector instead of deprecated setTargetResolution.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val faceAnalyzer: FaceAnalyzer,
    private val handAnalyzer: HandAnalyzer
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var faceExecutor: ExecutorService? = null
    private var handExecutor: ExecutorService? = null

    fun start() {
        if (faceExecutor?.isShutdown != false) faceExecutor = Executors.newSingleThreadExecutor()
        if (handExecutor?.isShutdown != false) handExecutor = Executors.newSingleThreadExecutor()

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
        val fExec = faceExecutor ?: return
        val hExec = handExecutor ?: return

        // Front camera only
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(320, 240),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        // Face analysis
        val faceAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(fExec, faceAnalyzer) }

        // Hand analysis
        val handAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(hExec, handAnalyzer) }

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
        faceExecutor?.shutdown()
        handExecutor?.shutdown()
        Log.d(TAG, "Camera stopped")
    }
}

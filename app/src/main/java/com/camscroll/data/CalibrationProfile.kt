package com.camscroll.data

import com.google.gson.Gson

/**
 * Stores per-user calibration baselines.
 * Saved as JSON in DataStore.
 */
data class CalibrationProfile(
    val browBaselineScore: Float = 0f,
    val browRaisePeak: Float = 0.6f,
    val browLowerPeak: Float = 0.5f,
    val blinkBaselineScore: Float = 0f,
    val blinkPeak: Float = 0.9f,
    val smileBaselineScore: Float = 0f,
    val smilePeak: Float = 0.7f,
    val headYawBaseline: Float = 0f,
    val headYawRange: Float = 25f,
    val calibratedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): CalibrationProfile =
            Gson().fromJson(json, CalibrationProfile::class.java)
    }
}

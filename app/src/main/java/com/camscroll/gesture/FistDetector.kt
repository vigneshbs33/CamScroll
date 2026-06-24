package com.camscroll.gesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Detects fist, peace sign, and thumbs-down from MediaPipe Hand Landmarker output.
 *
 * MediaPipe Hand landmark indices:
 *   Wrist = 0
 *   Thumb: 1(CMC) 2(MCP) 3(IP) 4(TIP)
 *   Index: 5(MCP) 6(PIP) 7(DIP) 8(TIP)
 *   Middle: 9(MCP) 10(PIP) 11(DIP) 12(TIP)
 *   Ring: 13(MCP) 14(PIP) 15(DIP) 16(TIP)
 *   Pinky: 17(MCP) 18(PIP) 19(DIP) 20(TIP)
 */
object FistDetector {

    /**
     * Returns true when a closed fist is detected.
     * All four fingers (index through pinky) must be curled:
     * tip Y > MCP Y (in image coords, Y increases downward).
     */
    fun isFist(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        val fingerTips = listOf(8, 12, 16, 20)
        val fingerMcps = listOf(5, 9, 13, 17)
        return fingerTips.zip(fingerMcps).all { (tip, mcp) ->
            landmarks[tip].y() > landmarks[mcp].y()
        }
    }

    /**
     * Peace sign: index and middle extended, ring and pinky curled.
     */
    fun isPeaceSign(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        val indexExtended = landmarks[8].y() < landmarks[5].y()
        val middleExtended = landmarks[12].y() < landmarks[9].y()
        val ringCurled = landmarks[16].y() > landmarks[13].y()
        val pinkyCurled = landmarks[20].y() > landmarks[17].y()
        return indexExtended && middleExtended && ringCurled && pinkyCurled
    }

    /**
     * Thumbs down: thumb tip is below wrist, all fingers curled.
     */
    fun isThumbsDown(landmarks: List<NormalizedLandmark>): Boolean {
        if (landmarks.size < 21) return false
        val thumbDown = landmarks[4].y() > landmarks[0].y()
        val fingersCurled = listOf(8, 12, 16, 20).zip(listOf(5, 9, 13, 17)).all { (tip, mcp) ->
            landmarks[tip].y() > landmarks[mcp].y()
        }
        return thumbDown && fingersCurled
    }
}

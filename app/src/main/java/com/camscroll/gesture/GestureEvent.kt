package com.camscroll.gesture

/**
 * All gesture events emitted by the GestureEngine.
 * The AccessibilityService maps these to real Android actions.
 */
sealed class GestureEvent {
    object ScrollUp : GestureEvent()
    object ScrollDown : GestureEvent()
    object Tap : GestureEvent()
    object Back : GestureEvent()
    object NextItem : GestureEvent()
    object PrevItem : GestureEvent()
    object PausePlay : GestureEvent()
    object FastQuit : GestureEvent()
    object TrackingPaused : GestureEvent()
    object TrackingResumed : GestureEvent()
}

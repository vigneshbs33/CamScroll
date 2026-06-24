# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }

# Keep CamScroll data classes (used with Gson)
-keep class com.camscroll.data.** { *; }
-keep class com.camscroll.gesture.GestureConfig { *; }
-keep class com.camscroll.gesture.ScrollGesture { *; }
-keep class com.camscroll.gesture.FastQuitGesture { *; }

# Keep enum values (for DataStore serialization)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

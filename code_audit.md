# CamScroll — Full Code Audit Report

> Complete review: crashes, logic bugs, security, memory leaks, thread safety, build issues, missing files, UX broken flows.

---

## Summary

| Severity | Count |
|---|---|
| 🔴 Critical (crash / security) | 7 |
| 🟠 High (logic / data loss) | 8 |
| 🟡 Medium (quality / UX) | 6 |
| 🟢 Low (style / minor) | 2 |

---

## 🔴 CRITICAL — Will crash or break at launch

---

### BUG-01 — `FaceAnalyzer.kt` lines 94–97 + 111–112: **Recursive infinite method call → crash**

**File:** `FaceAnalyzer.kt` lines 94–97 and 111–112

```kotlin
// Lines 94-97 — DEAD CODE that masks real extension
private fun android.media.Image.toBitmap(): Bitmap {
    throw UnsupportedOperationException()  // This is never hit — but confusing
}

// Lines 111-112 — RECURSIVE: calls itself forever
private fun com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
    .facialTransformationMatrixes() = this.facialTransformationMatrixes()
// ↑ This extension calls itself = StackOverflowError crash at runtime
```

**Fix:**
```kotlin
// DELETE both of those extension functions at lines 94-97 and 111-112.
// Call the real method directly in extractYaw():
private fun extractYaw(result: FaceLandmarkerResult): Float {
    val matrices = result.facialTransformationMatrixes()  // call directly
    if (matrices.isEmpty || matrices.get().isEmpty()) return 0f
    val matrix = matrices.get()[0]
    val data = matrix.data()  // correct MediaPipe API: Matrix3x3.data() returns FloatArray
    if (data.size < 11) return 0f
    val yawRad = atan2(data[8].toDouble(), data[10].toDouble())
    return (yawRad * 180.0 / PI).toFloat()
}
```

---

### BUG-02 — `FaceAnalyzer.kt` line 88: **`flattenToArray()` is wrong type**

**File:** `FaceAnalyzer.kt` line 88

```kotlin
// Line 109: extension defined on PointF3D — but matrix is NOT a PointF3D
private fun android.graphics.PointF3D.flattenToArray(): FloatArray = floatArrayOf(x, y, z)
// ↑ MediaPipe facial transformation matrix is a Matrix3x3 object, not a PointF3D
// This code will not compile / produce wrong results
```

**Fix:** The MediaPipe `FacialTransformationMatrix` has a `.data()` method returning `FloatArray` of 16 values (4x4 matrix). Delete the `PointF3D` extension and use:
```kotlin
val data: FloatArray = result.facialTransformationMatrixes().get()[0].data()
```

---

### BUG-03 — `CameraController.kt` line 27: **`ExecutorService` shutdown race condition → crash after stop/start**

**File:** `CameraController.kt` line 74–77

```kotlin
fun stop() {
    cameraProvider?.unbindAll()
    analysisExecutor.shutdown()  // ← shutdown is permanent
}
```

`shutdown()` permanently terminates the executor. If the user stops and restarts CamScroll (tap QS tile off/on), the second `start()` call tries to set the analyzer on a **terminated executor**, throwing `RejectedExecutionException`.

**Fix:** Use `shutdownNow()` and recreate on start, or use a single-thread executor per lifecycle:
```kotlin
private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

fun stop() {
    cameraProvider?.unbindAll()
    if (!analysisExecutor.isShutdown) analysisExecutor.shutdown()
}

fun start() {
    if (analysisExecutor.isShutdown) {
        analysisExecutor = Executors.newSingleThreadExecutor()
    }
    // ... rest of start
}
```

---

### BUG-04 — `CamScrollAccessibilityService.kt` line 13: **Wrong IPC mechanism → broadcast never received**

**File:** `CamScrollAccessibilityService.kt` line 13 + 35

```kotlin
import androidx.localbroadcastmanager.content.LocalBroadcastManager
// ...
registerReceiver(gestureReceiver, filter)  // ← uses context.registerReceiver, NOT LocalBroadcastManager
```

**Critical inconsistency:** `FaceTrackingService.broadcastGesture()` uses `sendBroadcast()` (system-level), but `CamScrollAccessibilityService` imports `LocalBroadcastManager` but then calls `registerReceiver()` on the context (system-level receiver). This means:

1. The import is unused (LocalBroadcastManager is never actually called)
2. The system broadcast `com.camscroll.GESTURE` is registered without `RECEIVER_NOT_EXPORTED` flag (Android 13+ requirement) → crash on API 33+

**Fix:** Either use LocalBroadcastManager consistently on BOTH sides, OR fix the system broadcast:
```kotlin
// Option A: LocalBroadcastManager everywhere (recommended — no permission needed)
// In FaceTrackingService.broadcastGesture():
LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

// In CamScrollAccessibilityService.onServiceConnected():
LocalBroadcastManager.getInstance(this)
    .registerReceiver(gestureReceiver, IntentFilter(FaceTrackingService.BROADCAST_GESTURE))

// In CamScrollAccessibilityService.onDestroy():
LocalBroadcastManager.getInstance(this).unregisterReceiver(gestureReceiver)
```

---

### BUG-05 — `CamScrollAccessibilityService.kt` line 35: **Android 13+ unregistered export crash**

Related to BUG-04. If keeping system broadcast instead of LocalBroadcastManager:

```kotlin
// API 33+ requires RECEIVER_NOT_EXPORTED or RECEIVER_EXPORTED flag:
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    registerReceiver(gestureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
} else {
    registerReceiver(gestureReceiver, filter)
}
```
Without this, app crashes on Android 13+ with `SecurityException`.

---

### BUG-06 — `OnboardingActivity.kt` lines 39–45: **Collect called before `setContentView` → NPE crash**

**File:** `OnboardingActivity.kt` lines 39–45

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // ← collect starts HERE (async coroutine)
    lifecycleScope.launch {
        UserPreferences.onboardingDone(this@OnboardingActivity).collect { done ->
            if (done) {
                goToMain()  // ← goToMain calls startActivity, then finish()
                return@collect
            }
        }
    }

    setContentView(R.layout.activity_onboarding)  // ← only set AFTER launch
    viewPager = findViewById(R.id.view_pager)     // ← crash if goToMain fires before this
```

If DataStore immediately emits `false` (first launch), the Activity continues fine. But if the coroutine runs and the value is `true` (onboarding done), `goToMain()` calls `finish()` before `setContentView`, which may cause:
- A window leak
- NullPointerException on `viewPager` if the coroutine fires between `setContentView` and `viewPager = ...`

**Fix:** Check DataStore BEFORE setting content, using `first()` (one-shot read):
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    lifecycleScope.launch {
        val done = UserPreferences.onboardingDone(this@OnboardingActivity).first()
        if (done) { goToMain(); return@launch }
        
        // Only set content if onboarding is not done
        setContentView(R.layout.activity_onboarding)
        viewPager = findViewById(R.id.view_pager)
        // ... rest of setup
    }
}
```

---

### BUG-07 — `UserPreferences.gestureConfig()`: **`valueOf()` crash on corrupted/old data**

**File:** `UserPreferences.kt` lines 41–48

```kotlin
scrollUpGesture = ScrollGesture.valueOf(
    prefs[KEY_SCROLL_UP_GESTURE] ?: ScrollGesture.EYEBROW_RAISE.name
),
```

`ScrollGesture.valueOf()` throws `IllegalArgumentException` if the stored string doesn't match any enum value. This happens if:
- User had an older version with different enum names
- DataStore gets corrupted
- A future refactor renames an enum value

**Fix:**
```kotlin
scrollUpGesture = try {
    ScrollGesture.valueOf(prefs[KEY_SCROLL_UP_GESTURE] ?: ScrollGesture.EYEBROW_RAISE.name)
} catch (e: IllegalArgumentException) {
    ScrollGesture.EYEBROW_RAISE  // safe fallback
},
// Same for scrollDownGesture and fastQuitGesture
```

---

## 🟠 HIGH — Logic bugs / broken features

---

### BUG-08 — `GestureEngine.kt` lines 85–108: **Filter called twice per frame for same blendshape**

**File:** `GestureEngine.kt` lines 85–88

```kotlin
if (browRaiseFilter.process(brow) && config.scrollUpGesture == ScrollGesture.EYEBROW_RAISE)
    emit(GestureEvent.ScrollUp)
if (browRaiseFilter.process(brow) && config.scrollDownGesture == ScrollGesture.EYEBROW_RAISE)
    emit(GestureEvent.ScrollDown)
```

`browRaiseFilter.process(brow)` is called **twice** per frame with the same `brow` score. The filter is **stateful** — each call to `process()` updates the EMA and the hold timer. Calling it twice means:
- EMA is applied twice (score gets double-smoothed)
- The hold timer advances twice as fast
- Cooldown resets twice

**Fix:** Evaluate the filter once, store result:
```kotlin
val browRaiseFired = browRaiseFilter.process(brow)
if (browRaiseFired && config.scrollUpGesture == ScrollGesture.EYEBROW_RAISE)
    emit(GestureEvent.ScrollUp)
if (browRaiseFired && config.scrollDownGesture == ScrollGesture.EYEBROW_RAISE)
    emit(GestureEvent.ScrollDown)

// Same pattern for browLower, blink, smile, jawOpen
```

---

### BUG-09 — `CameraController.kt` lines 50–63: **Two ImageAnalysis use cases on single executor → frame starvation**

**File:** `CameraController.kt`

Both `faceAnalysis` and `handAnalysis` share `analysisExecutor` (a single-thread executor). With `STRATEGY_KEEP_ONLY_LATEST`, if face analysis takes 60ms and hand analysis takes 40ms, frames will be dropped alternately and analysis will be serialized. MediaPipe inference on one thread for two models at 15fps each = 100ms/frame budget for a single thread — very tight.

**Fix:** Use separate executors for face and hand:
```kotlin
private val faceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
private val handExecutor: ExecutorService = Executors.newSingleThreadExecutor()

// In bindCamera():
faceAnalysis.also { it.setAnalyzer(faceExecutor, faceAnalyzer) }
handAnalysis.also { it.setAnalyzer(handExecutor, handAnalyzer) }

// In stop():
faceExecutor.shutdown()
handExecutor.shutdown()
```

---

### BUG-10 — `FaceTrackingService.kt` line 62: **`isRunning = true` in `onCreate`, never reset on crash**

**File:** `FaceTrackingService.kt` line 62

```kotlin
override fun onCreate() {
    super.onCreate()
    isRunning = true   // ← set in onCreate
```

```kotlin
override fun onDestroy() {
    isRunning = false  // ← reset in onDestroy
```

`isRunning` is an in-memory `companion object` variable. If the service crashes (OOM, native crash in MediaPipe), `onDestroy` may not be called, leaving `isRunning = true` permanently. On next app launch, the QS tile and MainActivity will think the service is still running — leading to a broken state where Start button does nothing.

**Fix:** Set `isRunning` only when `onStartCommand` successfully starts foreground:
```kotlin
override fun onStartCommand(...): Int {
    // ... foreground setup ...
    isRunning = true  // ← only set when actually running
    return START_STICKY
}
override fun onDestroy() {
    isRunning = false
    // ...
}
```
Also add `START_STICKY` so Android restarts the service and `onDestroy` gets called properly.

---

### BUG-11 — `OverlayManager.kt` lines 37–42: **Position loaded AFTER overlay is already added**

**File:** `OverlayManager.kt` lines 34–64

```kotlin
fun show() {
    // Load saved position — async coroutine
    scope.launch {
        UserPreferences.overlayPosition(context).collect { (x, y) ->
            savedX = x; savedY = y   // ← saved here
        }
    }

    val layout = WindowManager.LayoutParams(...).apply {
        x = savedX  // ← uses DEFAULT (16,100) because coroutine hasn't run yet
        y = savedY
    }
    windowManager.addView(dot, layout)  // ← overlay added with wrong position
```

The coroutine is async. The overlay is added immediately with `savedX=16, savedY=100` (the hardcoded default). The saved position from DataStore is never applied on startup.

**Fix:** Load position first, then show:
```kotlin
suspend fun show() {
    val (x, y) = UserPreferences.overlayPosition(context).first()
    val layout = WindowManager.LayoutParams(...).apply {
        this.x = x
        this.y = y
    }
    // ... add view
}
// Or keep it non-suspend and handle in service:
// Call overlayManager.showWithPosition(savedX, savedY) after loading from DataStore
```

---

### BUG-12 — `build.gradle.kts`: **Missing `localbroadcastmanager` dependency**

**File:** `app/build.gradle.kts`

`CamScrollAccessibilityService.kt` imports `androidx.localbroadcastmanager.content.LocalBroadcastManager` but there is no `localbroadcastmanager` dependency in `build.gradle.kts`. This is a **separate artifact** from AndroidX:

```kotlin
// Missing from build.gradle.kts:
implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
```

Add this to `libs.versions.toml` and `build.gradle.kts`.

---

### BUG-13 — `libs.versions.toml`: **Missing `viewpager2` dependency**

`OnboardingActivity.kt` imports `androidx.viewpager2.widget.ViewPager2` but `viewpager2` is not in `libs.versions.toml` or `build.gradle.kts`.

```toml
# Add to libs.versions.toml:
viewpager2 = "1.0.0"

[libraries]
viewpager2 = { group = "androidx.viewpager2", name = "viewpager2", version.ref = "viewpager2" }
```

```kotlin
// Add to build.gradle.kts:
implementation(libs.viewpager2)
```

---

### BUG-14 — `FaceTrackingService.kt` line 62: **`isRunning = true` set in `onCreate` before foreground started**

`isRunning = true` is set in `onCreate()`, but `startForeground()` is called in `onStartCommand()`. If `onStartCommand` is never called (edge case), or if it throws before `startForeground()`, `isRunning` will be `true` with no actual running service. Move `isRunning = true` to after `ServiceCompat.startForeground(...)` succeeds.

---

### BUG-15 — `CamScrollAccessibilityService.kt` line 91–95: **`findScrollableNode` leaks `AccessibilityNodeInfo` objects**

**File:** `CamScrollAccessibilityService.kt` lines 87–95

```kotlin
private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    root ?: return null
    if (root.isScrollable) return root
    for (i in 0 until root.childCount) {
        val found = findScrollableNode(root.getChild(i))
        if (found != null) return found
    }
    return null
}
```

`root.getChild(i)` returns `AccessibilityNodeInfo` objects that **must be recycled** when no longer needed. Any child node that is obtained but not returned is leaked. This is called every gesture event — it will cause memory growth over time.

**Fix:**
```kotlin
private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    root ?: return null
    if (root.isScrollable) return root
    for (i in 0 until root.childCount) {
        val child = root.getChild(i) ?: continue
        val found = findScrollableNode(child)
        if (found != null) {
            if (found !== child) child.recycle()  // recycle children we don't return
            return found
        }
        child.recycle()
    }
    return null
}
```

---

## 🟡 MEDIUM — Quality / UX issues

---

### BUG-16 — `OverlayManager.kt` line 29: **Unstructured `CoroutineScope` — leaks on destroy**

```kotlin
private val scope = CoroutineScope(Dispatchers.Main)
```

This scope has no lifecycle binding and no cancellation. When `hide()` is called and the overlay is destroyed, the scope keeps running indefinitely. On rapid show/hide cycles (QS tile spam), multiple scopes accumulate.

**Fix:** Cancel the scope in `hide()`:
```kotlin
private var scope = CoroutineScope(Dispatchers.Main)

fun hide() {
    scope.cancel()
    // ... rest of hide
}

fun show() {
    scope = CoroutineScope(Dispatchers.Main)  // fresh scope each time
    // ...
}
```

---

### BUG-17 — `FaceAnalyzer.kt` line 58–61: **Bitmap not recycled → memory leak**

```kotlin
override fun analyze(image: ImageProxy) {
    val bitmap = image.toBitmap()       // allocates Bitmap
    val mpImage = BitmapImageBuilder(bitmap).build()
    faceLandmarker.detectAsync(mpImage, image.imageInfo.timestamp)
    image.close()
    // ← bitmap is never recycled
}
```

At 15fps, a new 320×240 ARGB_8888 Bitmap (300KB) is created every frame and never recycled. The GC handles it eventually, but this causes GC pressure and can trigger GC pauses visible as jank in the scroll response.

**Fix:**
```kotlin
override fun analyze(image: ImageProxy) {
    val bitmap = image.toBitmap()
    val mpImage = BitmapImageBuilder(bitmap).build()
    faceLandmarker.detectAsync(mpImage, image.imageInfo.timestamp)
    image.close()
    bitmap.recycle()  // ← add this
}
// Same fix needed in HandAnalyzer.analyze()
```

---

### BUG-18 — `CameraController.kt` line 51: **`setTargetResolution` deprecated in CameraX 1.3+**

```kotlin
.setTargetResolution(android.util.Size(320, 240))
```

`setTargetResolution()` is deprecated in CameraX 1.3. Use `ResolutionSelector`:
```kotlin
val resolutionSelector = ResolutionSelector.Builder()
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(320, 240),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
        )
    )
    .build()

ImageAnalysis.Builder()
    .setResolutionSelector(resolutionSelector)
    // ...
```

---

### BUG-19 — `dispatchScrollGesture`: **Deprecated `Display.getMetrics()` on API 30+**

```kotlin
@Suppress("DEPRECATION")
windowManager.defaultDisplay.getMetrics(metrics)
```

Suppressed but still wrong. On API 30+, use `WindowMetrics`:
```kotlin
val screenWidth: Float
val screenHeight: Float
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    val bounds = windowManager.currentWindowMetrics.bounds
    screenWidth = bounds.width().toFloat()
    screenHeight = bounds.height().toFloat()
} else {
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getMetrics(metrics)
    screenWidth = metrics.widthPixels.toFloat()
    screenHeight = metrics.heightPixels.toFloat()
}
```

---

### BUG-20 — `OnboardingActivity.kt` line 87: **`updatePermissionScreen()` is empty — permission state never refreshes**

```kotlin
private fun updatePermissionScreen() {
    // Refresh page 2 (permissions) — handled by the adapter
}
```

This method is called after camera/notification permission result, but it does nothing. The permission buttons don't update to "✓ Granted" after the user grants. The fix is to call `viewPager.adapter?.notifyItemChanged(1)` to rebind the permissions page:

```kotlin
private fun updatePermissionScreen() {
    viewPager.adapter?.notifyItemChanged(1)
}
```

---

### BUG-21 — Missing `mipmap` launcher icons

**File:** `AndroidManifest.xml` line 23

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

There are no `mipmap` resource directories or `ic_launcher` files created. The app will fail to build without them.

**Fix:** Create placeholder launcher icons in `res/mipmap-mdpi/`, `res/mipmap-hdpi/`, etc., or use Android Studio's "Image Asset" tool to generate them. Minimum: create `res/mipmap-mdpi/ic_launcher.png` and `ic_launcher_round.png`.

---

## 🟢 LOW — Minor / style

---

### BUG-22 — `GestureEngine.kt` line 132: **Float range check on `FloatRange` uses `in` operator — potential precision issue**

```kotlin
} else if (adjustedYaw in -threshold..threshold) {
```

`Float` range with `in` uses `compareTo` which is fine for this use case, but if `threshold` is exactly at the boundary it can flicker. Not a crash — just a minor robustness note. Consider `abs(adjustedYaw) < threshold` instead:
```kotlin
} else if (Math.abs(adjustedYaw) < threshold) {
```

---

### BUG-23 — `FaceTrackingService.kt` line 157: **`event::class.simpleName` can return null**

```kotlin
putExtra(EXTRA_GESTURE_TYPE, event::class.simpleName)
```

`simpleName` is `String?` — it's null for anonymous objects. While `GestureEvent` subclasses are named objects so this won't be null in practice, it's safer to use explicit strings:
```kotlin
val typeName = when (event) {
    is GestureEvent.ScrollUp -> "ScrollUp"
    is GestureEvent.ScrollDown -> "ScrollDown"
    is GestureEvent.Tap -> "Tap"
    is GestureEvent.Back -> "Back"
    is GestureEvent.NextItem -> "NextItem"
    is GestureEvent.PrevItem -> "PrevItem"
    is GestureEvent.PausePlay -> "PausePlay"
    else -> return
}
putExtra(EXTRA_GESTURE_TYPE, typeName)
```

---

## Fixes Priority Order (for launch readiness)

| Priority | Bug | Impact |
|---|---|---|
| 1 | BUG-01 | App crashes immediately — recursive StackOverflow |
| 2 | BUG-02 | Wrong type — compile error or wrong yaw |
| 3 | BUG-04 | Gestures never work — broadcast never received |
| 4 | BUG-05 | Crash on Android 13+ — SecurityException |
| 5 | BUG-06 | Crash on first launch — NPE before setContentView |
| 6 | BUG-07 | Crash when DataStore has unexpected value |
| 7 | BUG-12 | Build fails — missing dependency |
| 8 | BUG-13 | Build fails — missing dependency |
| 9 | BUG-21 | Build fails — missing launcher icons |
| 10 | BUG-08 | Filters called twice — EMA double-applied, gestures fire wrong |
| 11 | BUG-03 | Stop/start cycle crashes |
| 12 | BUG-09 | Frame starvation on single executor |
| 13 | BUG-11 | Overlay always spawns at (16,100) regardless of saved position |
| 14 | BUG-15 | AccessibilityNodeInfo memory leak — grows over time |
| 15 | BUG-17 | Bitmap leak — GC pressure, jank |
| 16 | BUG-16 | CoroutineScope leak |
| 17 | BUG-10 | isRunning stuck true after crash |
| 18 | BUG-20 | Permissions page never refreshes |
| 19 | BUG-18 | Deprecated API warning |
| 20 | BUG-19 | Deprecated API warning |
| 21 | BUG-14 | isRunning set too early |
| 22 | BUG-22 | Float range precision |
| 23 | BUG-23 | Nullable simpleName |

---

## Security Review

### SEC-01 — Broadcast not restricted to own package ✅ ALREADY HANDLED
`setPackage(packageName)` is already set in `broadcastGesture()` — good. No external app can spoof gestures.

### SEC-02 — No face data stored or transmitted ✅ CLEAN
CalibrationProfile stores only float thresholds (not biometric images). No network calls anywhere.

### SEC-03 — `android:allowBackup="true"` in manifest — minor risk
If a user connects via ADB and runs `adb backup`, DataStore preferences (including calibration data) can be extracted. For a camera app, consider:
```xml
android:allowBackup="false"
```
Or add a `backup_rules.xml` that excludes `camscroll_prefs`.

### SEC-04 — GPU delegate fallback missing
If GPU delegate initialization fails (some older chips, Vulkan not available), `FaceLandmarker.createFromOptions()` throws `RuntimeException` with no fallback. The whole service crashes silently.

**Fix:** Wrap in try-catch and fall back to CPU:
```kotlin
val delegate = try {
    Delegate.GPU
} catch (e: Exception) {
    Delegate.CPU
}
```

Or better:
```kotlin
private fun createFaceLandmarker(context: Context): FaceLandmarker {
    val gpuOptions = BaseOptions.builder().setDelegate(Delegate.GPU).build()
    return try {
        FaceLandmarker.createFromOptions(context, buildOptions(gpuOptions))
    } catch (e: RuntimeException) {
        Log.w(TAG, "GPU delegate failed, falling back to CPU")
        val cpuOptions = BaseOptions.builder().setDelegate(Delegate.CPU).build()
        FaceLandmarker.createFromOptions(context, buildOptions(cpuOptions))
    }
}
```

---

## Architecture Notes (not bugs, but important)

### ARCH-01 — `GestureEngine` is not thread-safe
`processFaceResult`, `processHandResult`, `updateConfig`, and `reset` can be called from different threads (camera analysis executor vs main thread for config updates). Mutable state (`config`, `isPaused`, `fastQuitActive`, etc.) has no synchronization.

**Recommendation:** Add `@Volatile` to shared state or use a `Mutex`:
```kotlin
@Volatile private var config: GestureConfig = GestureConfig()
@Volatile private var isPaused = false
@Volatile private var fastQuitActive = false
```

### ARCH-02 — `FaceTrackingService` + `OverlayManager.setStatus()` called from background thread
`GestureEngine.events` is collected in `lifecycleScope.launch {}` which runs on `Dispatchers.Default` by default in a `LifecycleService`. `OverlayManager.setStatus()` calls `setColorFilter()` on a `View` — **must be on the main thread**.

**Fix:** Collect on main dispatcher:
```kotlin
lifecycleScope.launch(Dispatchers.Main) {
    gestureEngine.events.collect { event ->
        handleGestureEvent(event)
    }
}
```

---

## Final Launch Checklist

Before submitting to Play Store:

- [ ] Fix BUG-01 through BUG-09 (all critical + high)
- [ ] Add launcher icons (mipmap)
- [ ] Download MediaPipe model assets
- [ ] Add missing dependencies (viewpager2, localbroadcastmanager)
- [ ] Test on physical device, Android 12 + 13 + 14
- [ ] Test stop/start cycle (QS tile off/on 5 times)
- [ ] Test with no face for 10+ seconds (pause flow)
- [ ] Test Fast Quit on each gesture option
- [ ] Verify onboarding skip and re-open goes to MainActivity
- [ ] Profile memory: check for Bitmap/AccessibilityNode leaks with Android Profiler
- [ ] Privacy policy URL in Play Store listing

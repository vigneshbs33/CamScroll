# CamScroll — Implementation Plan

> **"Turn your face into an Android controller."**
> Hands-free scrolling and control, powered by your face. No accessories. No hands.

---

## What We're Building

CamScroll is an Android Accessibility app that:
- Opens the **front camera** and reads your face in real-time using **MediaPipe Face Landmarker**
- By default does **one thing only: scroll** — triggered by whatever gesture the user picks
- Can be toggled instantly via the **Quick Settings Tile** (the WiFi/Data/Torch panel)
- Has a **Fast Quit gesture** (default: close fist) to kill tracking instantly from any app
- Runs as a **foreground service** with a minimal floating status dot
- Stores everything locally — **no accounts, no cloud, no data ever leaves the device**

---

## Core Design Philosophy

> **Minimal. Professional. No clutter.**

- One screen to control everything. No tabs, no deep menus.
- Dark UI. Clean typography. Purposeful spacing.
- The app should feel like a tool, not a toy.
- Every setting has a sensible default. Advanced options are hidden unless needed.
- The user should be able to go: open app → tap start → use it → tap QS tile to stop. That's it.

---

## Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Language | **Kotlin** | Native Android, coroutines, concise |
| Camera | **CameraX** | Lifecycle-aware, works across all devices |
| Face AI | **MediaPipe Face Landmarker** (`tasks-vision:0.10.35`) | Blendshape API, low-light capable, on-device |
| Hand AI | **MediaPipe Hand Landmarker** (`tasks-vision:0.10.35`) | For Fast Quit fist detection |
| Action Injection | **AccessibilityService + dispatchGesture** | Only legal cross-app injection on Android |
| Background | **Foreground Service (type=camera)** | Required by Android 14+ for background camera |
| Overlay | **WindowManager TYPE_APPLICATION_OVERLAY** | Floating dot over all apps |
| Quick Toggle | **TileService** | Quick Settings panel toggle |
| Storage | **Preferences DataStore** | Async, coroutine-friendly, no SQL overhead |
| Build | **Gradle Kotlin DSL** | Modern, type-safe |
| Min SDK | **API 26 (Android 8.0)** | Covers ~95% of Android users |
| Target SDK | **API 35** | Required for foreground service camera type |

> No OpenCV. MediaPipe handles everything more accurately and adds zero weight compared to OpenCV's 30MB+.

---

## Architecture Overview

```
┌───────────────────────────────────────────────────────┐
│                    USER'S FACE                        │
└───────────────────────┬───────────────────────────────┘
                        │ CameraX (320×240, 15fps)
                        ▼
┌───────────────────────────────────────────────────────┐
│           FaceTrackingService (Foreground)            │
│                                                       │
│  CameraX ImageAnalysis                                │
│       │                                               │
│       ├──▶ MediaPipe FaceLandmarker                   │
│       │         └──▶ Blendshapes + Head Pose          │
│       │                  └──▶ GestureEngine           │
│       │                            │                  │
│       └──▶ MediaPipe HandLandmarker (Fast Quit only)  │
│                  └──▶ FistDetector                    │
│                            │                          │
│                       GestureEvent Bus (SharedFlow)   │
└───────────────────────────┬───────────────────────────┘
                            │
            ┌───────────────▼───────────────────────┐
            │    CamScrollAccessibilityService       │
            │  dispatchGesture / performGlobalAction │
            └───────────────────────────────────────┘
                            │
            ┌───────────────▼───────────────────────┐
            │           OverlayManager               │
            │  Floating dot — active / paused / off  │
            └───────────────────────────────────────┘
```

---

## Project File Structure

```
camscroll/
├── app/src/main/
│   ├── java/com/camscroll/
│   │   ├── service/
│   │   │   ├── FaceTrackingService.kt          # Core foreground service
│   │   │   ├── CamScrollAccessibilityService.kt
│   │   │   └── OverlayManager.kt
│   │   │
│   │   ├── gesture/
│   │   │   ├── GestureEngine.kt                # Maps blendshapes → events
│   │   │   ├── GestureEvent.kt                 # Sealed class
│   │   │   ├── GestureConfig.kt                # All thresholds
│   │   │   ├── BlendshapeFilter.kt             # EMA + hysteresis per blendshape
│   │   │   └── FistDetector.kt                 # Hand landmark → fist detection
│   │   │
│   │   ├── camera/
│   │   │   ├── CameraController.kt
│   │   │   ├── FaceAnalyzer.kt                 # ImageProxy → face blendshapes
│   │   │   └── HandAnalyzer.kt                 # ImageProxy → hand landmarks
│   │   │
│   │   ├── facelock/
│   │   │   ├── FaceLockManager.kt              # (v2) Capture + compare face embedding
│   │   │   └── FaceLockStore.kt                # (v2) Save reference embedding
│   │   │
│   │   ├── tile/
│   │   │   └── CamScrollTileService.kt
│   │   │
│   │   ├── data/
│   │   │   ├── UserPreferences.kt              # DataStore keys + flows
│   │   │   └── CalibrationProfile.kt
│   │   │
│   │   └── ui/
│   │       ├── MainActivity.kt                 # Single main screen
│   │       ├── OnboardingActivity.kt
│   │       ├── CalibrationActivity.kt
│   │       └── SettingsActivity.kt
│   │
│   ├── res/
│   │   ├── xml/accessibility_service_config.xml
│   │   ├── layout/overlay_dot.xml
│   │   └── drawable/ic_tile.xml               # White vector for QS tile
│   │
│   └── assets/
│       ├── face_landmarker.task               # ~6MB MediaPipe face model
│       └── hand_landmarker.task               # ~8MB MediaPipe hand model
│
└── build.gradle.kts
```

---

## Feature 1 — Scroll Only (Default Mode)

### How it works
When CamScroll starts for the first time, it is in **Scroll Mode only**. Nothing else is active.

The user picks **one gesture** that triggers scroll up, and optionally one for scroll down.

### Scroll Gesture Options (user picks one)
| Gesture | Blendshape Used | Notes |
|---|---|---|
| Eyebrow raise | `browInnerUp` | Default recommendation |
| Both eyes blink (slow) | `eyeBlink_L + eyeBlink_R` sustained >300ms | Natural blinks can't trigger — they're ~100ms |
| Smile | `mouthSmile_L + mouthSmile_R` | Good for lying down use |
| Head tilt right | Yaw angle from transformation matrix | Pairs with left for up/down |
| Head tilt left | Yaw angle | - |
| Mouth open | `jawOpen` | Visible and reliable |

### Default Config (out of the box)
```
Eyebrow Raise → Scroll Up
Eyebrow Lower → Scroll Down
Everything else: OFF
```

The user sees a simple gesture picker on first setup — not a settings page, just "which gesture do you want to use to scroll?". 2 taps and done.

### Why Scroll Only as Default
- Keeps the first experience dead simple
- Reduces false trigger surface — only one gesture type active
- Users can enable more gestures in settings once they're comfortable
- Matches the #1 use case: eating + watching TikTok

---

## Feature 2 — Quick Settings Tile (Core, Not Optional)

The QS tile is the **primary way to use CamScroll**. It's not a bonus — it's the main toggle.

```
Pull down notification shade
           │
           ▼
Tap CamScroll tile
           │
     ┌─────┴─────┐
  Active?       Inactive?
     │               │
  Stop service    Start service (via LaunchActivity)
  Tile = grey     Tile = blue/active
```

### Implementation
```kotlin
class CamScrollTileService : TileService() {

    override fun onClick() {
        val tile = qsTile ?: return
        if (FaceTrackingService.isRunning) {
            stopService(Intent(this, FaceTrackingService::class.java))
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        } else {
            // Android 14 rule: camera service must start from visible activity
            startActivityAndCollapse(
                Intent(this, LaunchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onStartListening() {
        qsTile?.state = if (FaceTrackingService.isRunning)
            Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }
}
```

### QS Tile Design
- Icon: simple eye outline (white, vector) — immediately recognizable
- Label: "CamScroll"
- Active state: tile tinted (system blue or green)
- Inactive state: tile grey/off

---

## Feature 3 — Fast Quit Gesture

The user needs a way to **immediately stop CamScroll** from any app without going back to the notification or app. This is the Fast Quit gesture.

### Default: Close Fist
The Hand Landmarker runs on a **separate, lightweight pipeline** — it only checks for fist, nothing else.

**Fist detection logic:**
```kotlin
// All 4 finger tips must be below their corresponding MCP joints (knuckles)
// i.e., fingers are curled in
fun isFist(landmarks: List<NormalizedLandmark>): Boolean {
    val fingerTips = listOf(8, 12, 16, 20)   // index, middle, ring, pinky tips
    val fingerMCPs = listOf(5,  9, 13, 17)   // their base knuckles
    return fingerTips.zip(fingerMCPs).all { (tip, mcp) ->
        landmarks[tip].y > landmarks[mcp].y  // tip lower than knuckle = curled
    }
}
```

A 1-second fist hold = Fast Quit. The hold requirement prevents accidental triggers.

### Fast Quit Options (user can change in settings)
| Option | Description |
|---|---|
| Close fist (default) | Hold fist for 1 second |
| Two fingers up (peace sign) | Index + middle extended, others curled |
| Thumbs down | Thumb pointing down, fist |
| Disabled | Turn off Fast Quit entirely |

### What Fast Quit Does
1. Stops `FaceTrackingService`
2. Hides the overlay dot
3. Updates QS tile to inactive
4. Shows a brief toast: "CamScroll stopped"

### Battery note
Hand Landmarker only runs if Fast Quit is enabled (default: yes). If the user disables Fast Quit, hand model is not loaded. Saves ~2% battery/hour.

---

## Feature 4 — Face Lock (v2 Roadmap)

> Not in v1. Architecture is designed so this slots in cleanly later.

### What it is
The user takes a selfie → CamScroll stores a face embedding → only responds to gestures from that specific face. If a different face appears in frame, gestures are ignored.

### Why it matters
- Prevents friends/family from triggering CamScroll accidentally
- Security: prevents someone else from controlling your phone with their face
- Useful in shared living spaces

### How it will work (v2)
```
User opens Face Lock settings
        │
        ▼
Takes a selfie via CameraX still capture
        │
        ▼
MediaPipe Face Landmarker extracts 478 landmarks from the photo
        │
        ▼
We create a normalized face descriptor (128-dimension vector from landmark distances)
        │
        ▼
Stored in DataStore as FloatArray
        │
        ─────────────────────────────────
        │  During live tracking (v2):   │
        ├──▶ Extract descriptor from    │
        │    current frame              │
        ├──▶ Cosine similarity vs saved │
        │    reference descriptor       │
        ├──▶ If similarity < 0.85:      │
        │    ignore all gestures        │
        └───────────────────────────────
```

**Note:** We do NOT use a separate face recognition model. We derive the descriptor purely from the 478 landmark positions — cheap, no extra model, no privacy risk.

### v2 UI
- Settings toggle: "Face Lock — Only respond to my face"
- Tap to enroll: camera opens, takes photo, shows preview, confirm/retake
- "Face enrolled on [date]" + option to clear/re-enroll

---

## Anti-False-Trigger System (Gesture Engine Core)

Every blendshape reading goes through this pipeline before it can fire an action:

```
Raw Blendshape Score (0.0 → 1.0)
            │
            ▼
    EMA Smoothing (α = 0.3)         ← kills frame-to-frame jitter
            │
            ▼
    Baseline Subtraction             ← removes user's resting face offset
            │
            ▼
    Hysteresis Gate
      ACTIVATE if score > 0.55       ← hard to accidentally hit
      DEACTIVATE if score < 0.35     ← prevents flickering at boundary
            │
            ▼
    Hold Timer (250ms minimum)       ← must sustain gesture to count
            │
            ▼
    Cooldown (800ms after fire)      ← blocks repeat-fire
            │
            ▼
    GestureEvent emitted ✓
```

This layered system means:
- A casual eyebrow movement mid-conversation won't scroll
- Natural blinks (100ms) never trigger slow-blink gesture (needs 300ms+)
- Accidental head movements during talking don't fire head-tilt
- No gesture fires more than once per 800ms

---

## Calibration Wizard

### Why it's needed
Blendshape scores vary by person. A threshold that's perfect for one face may be too high or too low for another. Calibration personalizes it.

### Flow (Optional but Recommended — ~2 minutes)
```
Step 1 — Neutral Face
  "Relax your face. Look at the camera."
  Record 3 seconds → save average baseline scores

Step 2 — Gesture Training (only for enabled gestures)
  "Raise your eyebrows now. Hold for 2 seconds."
  Record peak → HIGH threshold = baseline + 70% of (peak - baseline)

Step 3 — Test It
  "Try it! Raise your eyebrows to scroll."
  Live preview: fake scroll animation plays when gesture detected
  User can re-do any step
```

### Skip Option
If the user skips calibration, we use universal defaults that work for most faces. They can always run it later from settings.

---

## Floating Overlay

The overlay is intentionally minimal — just enough to know the app is running.

### Design
```
  ●       ← small dot, top-right corner
  │
  active = green
  paused = amber
  off    = hidden (no dot at all)
```

- Single tap: toggle pause/resume
- Long press: drag to a new screen position
- The dot is semi-transparent (70% opacity) and only 12dp in size
- No text, no labels on the dot — just color

---

## Main App UI

### Design Rules
- Dark background: `#0F0F0F`
- Accent: clean white or single-color brand accent (one color only, no rainbow)
- Font: Inter or Roboto — clean, no-nonsense
- No gradients on controls. Flat. Bold. Readable.
- Spacing: generous. Nothing cramped.

### Screens

**1. Onboarding (first launch only)**
```
Screen 1 — What is this?
  Big heading: "Scroll without touching your phone"
  Sub: "CamScroll uses your front camera to detect face gestures."
  [Next →]

Screen 2 — Permissions
  Camera — "To see your face. Processed on your phone only."
  Overlay — "To show the status dot over other apps."
  Accessibility — "To scroll and tap on your behalf."
  [Grant each one] ← one by one, with clear why-we-need-this

Screen 3 — Pick Your Scroll Gesture
  Simple list — radio button selection:
  ○ Eyebrow raise (recommended)
  ○ Slow blink
  ○ Smile
  ○ Head tilt
  ○ Mouth open
  [Set Up for My Face] / [Use Defaults]

Screen 4 — Add to Quick Settings
  Animated gif showing: pull down → hold tile → drag to panel
  [Done]
```

**2. Main Screen (after onboarding)**
```
┌────────────────────────────────────┐
│  CamScroll              ⚙           │
│                                    │
│      ┌─────────────┐               │
│      │    START    │  ← big toggle  │
│      └─────────────┘               │
│                                    │
│  Scroll gesture: Eyebrow raise     │
│  Fast quit: Close fist      ✎      │
│                                    │
│  Status: Ready                     │
│  Last session: 12 min ago          │
│                                    │
│  [Calibrate]   [Gesture Settings]  │
└────────────────────────────────────┘
```

**3. Gesture Settings Screen**
```
Scroll Up     [Eyebrow Raise     ▾]
Scroll Down   [Eyebrow Lower     ▾]

Advanced (collapsed by default):
  Tap         [Disabled          ▾]
  Back        [Disabled          ▾]
  Next        [Disabled          ▾]
  Pause/Play  [Disabled          ▾]

Sensitivity   [──●────────] Medium
Cooldown      400ms / 600ms / 800ms / 1s

Fast Quit     [Close Fist        ▾]
              [Hold duration: 1s ]
```

**4. Settings Screen**
```
Face Lock             → [Off]  (v2 — shown greyed out with "Coming Soon")
Overlay Position      → [Top Right ▾]
Overlay Opacity       → [70% ──●──]
Recalibrate           → [Run Calibration Wizard]
Reset to Defaults     → [Reset]
Privacy Policy        → [View]
```

---

## Permissions Required

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service android:name=".service.FaceTrackingService"
    android:foregroundServiceType="camera"
    android:exported="false" />

<service android:name=".service.CamScrollAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>

<service android:name=".tile.CamScrollTileService"
    android:label="@string/tile_label"
    android:icon="@drawable/ic_tile"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

---

## Performance Targets

| Metric | Target |
|---|---|
| Gesture → action latency | < 150ms |
| False trigger rate | < 3% in normal use |
| Battery drain | < 10% per hour |
| Memory footprint | < 130MB (face + hand models loaded) |
| APK size | < 35MB |
| Camera resolution | 320×240 (enough for landmark accuracy) |
| Inference FPS | 15fps (not 30 — saves battery) |

---

## Battery Strategy

1. **320×240 camera stream** — much less data than full resolution
2. **15fps inference** — MediaPipe is accurate enough at this rate
3. **GPU delegate** — offloads face/hand processing from CPU
4. **Duty cycling** — no face for 10 seconds → drop to 5fps scan mode → resume 15fps when face reappears
5. **Fast Quit disableable** — turns off hand model if not wanted (~2% battery saving)
6. **Zero network** — no background syncs, no analytics pings

---

## Privacy

- Everything runs on-device. Zero cloud. Zero servers.
- Camera frames are processed and immediately discarded. Not stored anywhere.
- No accounts. No sign-in. No email.
- No analytics or crash reporting by default (can add opt-in Crashlytics later)
- Face Lock reference (v2) stored locally in DataStore, never uploaded.

---

## Build Timeline

### Week 1–2 — Foundation
- [ ] Android Studio project, Kotlin DSL, version catalog
- [ ] All permissions + manifest declared
- [ ] CameraX front camera preview working
- [ ] MediaPipe face model integrated, landmarks detected

### Week 3–4 — Gesture Engine
- [ ] BlendshapeFilter (EMA + hysteresis)
- [ ] GestureEngine with scroll-only default
- [ ] GestureEvent bus (SharedFlow)
- [ ] Calibration profile save/load (DataStore)
- [ ] Unit tests for filter + engine

### Week 4–5 — Service + Overlay
- [ ] FaceTrackingService as foreground service (camera type)
- [ ] Overlay dot rendering over other apps
- [ ] AccessibilityService receiving events + scrolling
- [ ] **First milestone: eyebrow raise scrolls TikTok**

### Week 5–6 — QS Tile + Fast Quit
- [ ] QS Tile toggle working (on/off from notification shade)
- [ ] Hand Landmarker integrated (fist detection)
- [ ] Fast Quit: fist hold → service stops
- [ ] Fast Quit gesture remapping in settings

### Week 7–8 — Settings + Calibration UI
- [ ] Calibration wizard (neutral → gesture training → test)
- [ ] Gesture picker screen
- [ ] Sensitivity slider + cooldown options
- [ ] All gesture mapping dropdowns

### Week 9 — Main UI + Onboarding
- [ ] Onboarding flow (4 screens)
- [ ] Main screen with start/stop + status
- [ ] Dark theme, Inter font, minimal design
- [ ] Overlay position + opacity settings

### Week 10 — Testing + Polish
- [ ] Test on 5 devices: Pixel, Samsung, OnePlus, Xiaomi, Realme
- [ ] Test in: bright room, dim bedroom, outdoor
- [ ] Battery profiling (target: <10%/hr)
- [ ] False trigger rate audit
- [ ] Animations, transitions, small UI details

### Post-Week 10
- [ ] Play Store listing + screenshots
- [ ] Privacy policy (required for camera app)
- [ ] Beta via Play Store internal track
- [ ] v2 planning: Face Lock feature

---

## Risk Register

| Risk | Likelihood | Impact | Plan |
|---|---|---|---|
| Android 14 blocks background camera service | Low | High | Start from visible activity (LaunchActivity) — correct per Google spec |
| GPU delegate crashes on some chipsets | Medium | Medium | Auto-fallback to CPU delegate |
| False trigger rate too high | Medium | High | EMA + hysteresis + calibration covers this |
| Play Store rejects Accessibility Service | Low | High | Document accessibility use case explicitly in listing |
| Battery drain unacceptable | Medium | Medium | Duty cycling + 15fps + disableable hand model |
| Face Lock face comparison inaccurate (v2) | Medium | Medium | Use cosine similarity threshold, offer re-enroll |

---

## Roadmap

### v1 — Launch
- Scroll-only default
- User picks scroll gesture
- QS Tile toggle
- Fast Quit (fist)
- Calibration wizard
- Minimal overlay dot

### v2
- **Face Lock** — enroll your face, only you can control it
- App-specific gesture profiles
- More gesture combinations (chord gestures)

### v3
- Community gesture profiles (share/import)
- Adaptive threshold learning (auto-tunes over time)
- iOS (ARKit equivalent)

---

> **Tagline:** "Turn your face into an Android controller."
> **Default pitch:** "Scroll TikTok while eating. No hands. Just your eyebrows."

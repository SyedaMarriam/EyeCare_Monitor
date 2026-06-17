# 👁️ Eye Distance Monitor — Android App

A real-time eye safety app that continuously monitors the distance between the user's face
and the phone using CameraX + ML Kit Face Detection, running as a Foreground Service.

---

## 📁 Project Structure

```
EyeDistanceMonitor/
├── build.gradle                          ← Project-level Gradle
├── settings.gradle                       ← Module settings
├── gradle.properties                     ← Gradle config flags
└── app/
    ├── build.gradle                      ← App dependencies & SDK config
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml           ← Permissions + Service declaration
        ├── java/com/eyecare/monitor/
        │   ├── MainActivity.kt           ← CameraX setup, UI, lifecycle
        │   ├── FaceDistanceAnalyzer.kt   ← ML Kit face detection + distance math
        │   └── EyeCareService.kt         ← Foreground service + notifications
        └── res/
            ├── layout/activity_main.xml  ← Full dark-theme UI
            └── values/
                ├── colors.xml
                ├── strings.xml
                └── themes.xml
```

---

## 🚀 How to Run in Android Studio

1. **Open** Android Studio → `File → Open` → select the `EyeDistanceMonitor/` folder
2. Let Gradle sync (it downloads all dependencies automatically)
3. **Connect** a real Android device (API 24+) — emulators lack front-camera support
4. Press ▶️ Run

---

## 📦 Key Dependencies (app/build.gradle)

| Library | Version | Purpose |
|---|---|---|
| `camera-core` | 1.3.1 | CameraX foundation |
| `camera-camera2` | 1.3.1 | Camera2 backend |
| `camera-lifecycle` | 1.3.1 | Lifecycle-aware binding |
| `camera-view` | 1.3.1 | PreviewView widget |
| `mlkit:face-detection` | 16.1.5 | On-device face bounding box |
| `material` | 1.11.0 | MaterialButton, CardView themes |
| `lifecycle-service` | 2.7.0 | LifecycleService base class |

---

## 🧠 Distance Estimation Logic

Uses the **pinhole camera model**:

```
distance_cm = (FOCAL_LENGTH_px × REAL_FACE_WIDTH_cm) / faceBox_width_px
```

| Constant | Value | Meaning |
|---|---|---|
| `FOCAL_LENGTH_PX` | 800 | Empirical value for typical front cameras |
| `REAL_FACE_WIDTH_CM` | 14 | Average adult face width |

### Status Thresholds

| Distance | Status | Message |
|----------|---|---|
| < 30 cm   | ❌ TOO CLOSE | Mobile bohat qareeb hai... |
| 40–60 cm | ⚠️ WARNING | Thoda aur door rakhein |
| > 60 cm  | ✅ SAFE | Safe distance maintain ho rahi hai |

---

## 🔄 Background Architecture

```
MainActivity (CameraX + UI)
     │
     ├── binds to ──→ EyeCareService (Foreground Service)
     │                   └── Persistent notification
     │                   └── Stays alive when app minimized
     │
     └── ImageAnalysis ──→ FaceDistanceAnalyzer
                              └── ML Kit detector
                              └── Distance calculation
                              └── Callback → MainActivity UI + Service
```

### Why Foreground Service?
- Android kills background processes to save battery
- `startForeground()` with a visible notification keeps the service alive
- `START_STICKY` ensures the service restarts if the system kills it
- `foregroundServiceType="camera"` is required on Android 14+ for camera access

---

## 🔐 Required Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />  <!-- Android 13+ -->
```

---

## 🐛 Common Issues

| Issue | Fix |
|---|---|
| `IllegalStateException: ImageProxy not closed` | Always call `imageProxy.close()` in `addOnCompleteListener` |
| Camera doesn't start on emulator | Use a real device with a front camera |
| Notification not showing on Android 13+ | Grant `POST_NOTIFICATIONS` at runtime |
| Distance seems inaccurate | Adjust `FOCAL_LENGTH_PX` in `FaceDistanceAnalyzer.kt` for your device |

---

## ✅ Features Summary

- [x] Real-time face detection with ML Kit (on-device, no internet needed)
- [x] Distance estimation using pinhole camera model
- [x] Three-level alert system (Safe / Warning / Too Close)
- [x] Urdu/Roman Urdu status messages
- [x] Foreground Service with persistent notification
- [x] Background monitoring (continues when app is minimized)
- [x] `ImageProxy` always closed (no memory leaks)
- [x] View Binding (no `findViewById`)
- [x] Dark theme UI
- [x] Runtime permission handling (Camera + Notifications)
- [x] `START_STICKY` service for reliability

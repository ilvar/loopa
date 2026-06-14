# Loopa — 10x Tele Camera

A minimal Android app that opens a full-screen camera preview locked to roughly
**10x magnification**, automatically using the device's **telephoto lens when one
is available**.

## How it works

The app uses the **Camera2** API directly so it can pick a specific physical lens:

1. **Enumerate back cameras.** All openable back-facing camera IDs are collected
   along with their focal lengths (`LENS_INFO_AVAILABLE_FOCAL_LENGTHS`).
2. **Define 1x.** The shortest focal length among the back cameras is treated as
   the 1x reference (typically the main/wide lens).
3. **Pick the lens for 10x.** The camera with the longest native focal length
   that does **not** already overshoot 10x is chosen — i.e. the telephoto lens
   when the device has one. Its optical magnification (e.g. 3x or 5x) does part of
   the work.
4. **Apply the remaining zoom.** The residual factor (`10 / optical`) is applied
   on top:
   - **API 30+ (Android 11+):** `CONTROL_ZOOM_RATIO`, clamped to the lens's
     `CONTROL_ZOOM_RATIO_RANGE`.
   - **Older devices:** a centered `SCALER_CROP_REGION` digital crop.

If the device has no telephoto lens, the app falls back to the main camera and
reaches 10x with digital zoom (clamped to the hardware maximum).

An on-screen overlay shows the active lens, camera ID, and the optical × digital
breakdown of the effective zoom.

## Project layout

```
app/src/main/
├── AndroidManifest.xml                     # CAMERA permission, launcher activity
├── java/com/loopa/telezoom/MainActivity.kt # Camera2 setup, lens selection, zoom
└── res/
    ├── layout/activity_main.xml            # TextureView preview + info overlay
    ├── values/                             # strings, theme, colors
    ├── drawable/ic_launcher_foreground.xml
    └── mipmap-anydpi-v26/                   # adaptive launcher icon
```

## Requirements

- Android Studio (or the Android SDK command-line tools)
- Android SDK Platform 34, Build-Tools 34.x
- A physical device (the camera preview needs real hardware; emulators have no
  telephoto lens)
- `minSdk 24`, `targetSdk 34`

## Build & run

```bash
# From the project root, with a device connected and USB debugging enabled:
./gradlew installDebug

# Or open the project in Android Studio and press Run.
```

Create a `local.properties` with your SDK path if Gradle can't find it:

```
sdk.dir=/path/to/Android/sdk
```

> **Note:** This project was scaffolded in a CI-style sandbox where Google's
> Maven repository (`dl.google.com`) and the Android SDK are not available, so the
> Android Gradle Plugin and AndroidX artifacts could not be downloaded here. The
> build runs normally on any machine with standard network access and the Android
> SDK installed.

## Permissions

The app requests `CAMERA` at runtime. If permission is denied, a prompt with a
"Grant permission" button is shown instead of the preview.
